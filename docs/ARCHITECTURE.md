# 架构与启动链路

## 1. 为什么不是"编译 vscode 源码"

`microsoft/vscode` 是 Electron 应用，Electron 没有 Android 运行时。
把它搬到 Android 有三条路：

| 方案 | 结论 |
|---|---|
| 替换 Electron 平台层 | 等于重写整个平台层，不可行 |
| 内嵌 Node 跑 code-server | code-server 的 native 模块（node-pty / argon2 / spdlog / ripgrep）都是 glibc 产物，需全部重编为 bionic |
| **proot + glibc rootfs + code-server** | 未 root 设备上唯一能同时满足终端、扩展、LSP 的组合 |

采用第三种。VS Code 的 Web 工作台本身就是个 HTTP 服务，谁渲染都行。

## 2. 六步启动链

```
BootstrapState.Preparing(1..6)

1 检查运行环境     RuntimePaths.ensureDirs / ensureFreeSpace(2.2GB)
                  NativeBinaryInstaller.install()
                  ↳ nativeLibraryDir/lib*.so → files/usr/bin/<name> 符号链接
2 解压内置资源     AssetExtractor：assets/runtime/*.tgz → files/runtime/
                  （按文件大小比对跳过，流式复制 256KB 缓冲）
3 解压 rootfs      TarExtractor：rootfs.tgz → files/usr/var/lib/proot-distro/installed-rootfs/ubuntu
4 安装 VS Code     TarExtractor：code-server-arm64.tgz → <rootfs>/opt/code-server-<ver>-linux-arm64
5 写入配置         BootstrapScriptBuilder.applyRootfsConfig / applyEditorConfig
                  ↳ apt 源、resolv.conf、config.yaml、settings.json、product.json
6 启动服务         BootstrapScriptBuilder.buildRunScript → sh run.sh → exec proot …
                  端口探测 127.0.0.1:20000，就绪后切 WebView
```

每一步幂等：解压完成后在目标目录写 `.extract-ok` 标记，二次启动直接跳过。

## 3. 关键设计决策

### 3.1 绕过 noexec

`targetSdk > 28` 后应用私有目录挂载为 `noexec`，不能执行任何 ELF。
`nativeLibraryDir` 例外，于是把 ELF 以 `lib*.so` 命名打进 jniLibs，运行时建符号链接指回：

```
files/usr/bin/proot            → <nativeLibraryDir>/libproot.so
files/usr/bin/loader           → <nativeLibraryDir>/libloader.so
files/usr/bin/libtalloc.so.2   → <nativeLibraryDir>/liblibtalloc.so.2.so
files/usr/bin/libandroid-shmem.so → <nativeLibraryDir>/liblibandroid-shmem.so
```

后两个必须用 **SONAME 原名**，因为动态链接器按 `DT_NEEDED` 在 `LD_LIBRARY_PATH` 中查名；
改名会导致 proot 启动即 `library not found`。

`extractNativeLibs="true"` 是前提，否则 nativeLibraryDir 里没有真实文件。

### 3.2 为什么不用 busybox

Termux 的 `usr/bin/busybox` 只是一个 4KB 的 ET_DYN 启动壳，
`DT_NEEDED` 指向 `libbusybox.so.1.38.0`，而后者又依赖 `libandroid-selinux.so`。
链式依赖不可控，且这些库在无 Termux 环境时必须逐个改写 SONAME。

改为 `TarExtractor` 在应用内直接解包（Commons Compress），
顺带把"shell 脚本 + sed/cp/tar"整条链路消掉，配置全部由 Kotlin 写。
代价是解包走 Java IO，实测 PC 上 rootfs 3.3s / code-server 2s（仅统计），
设备上预计 30s–4min，可接受。

### 3.3 aapt2 会改写 `.gz` asset

`.tar.gz` 结尾的 asset 会被 aapt2 **自动解压并剥掉后缀**，
应用内拿到的文件名变成 `.tar`、内容也不对。
因此归档统一命名为 `.tgz`，并在 `androidResources.noCompress` 中登记，使其以 stored 方式打包。

### 3.4 安全收敛

- `bind-addr: 127.0.0.1:<port>`，不使用 `0.0.0.0`，避免同一 Wi-Fi 下无密码暴露
- `network_security_config` 只放行 `127.0.0.1` / `localhost` 的明文流量
- 密码开关开启后，密码经 AndroidKeyStore AES/GCM 加密落盘，并在登录页自动填充
- 扩展市场默认 open-vsx（Microsoft Marketplace 不允许第三方客户端使用），可切换并附风险提示
- 遥测：`telemetry.telemetryLevel = off`，同时 patch product.json 的 `enableTelemetry`

### 3.5 WebView 生命周期

`MainActivity` 声明了完整的 `configChanges`，旋转/分屏不重建 Activity；
`WebViewHolder` 持有单例 WebView，只在 `isFinishing` 时 `destroy()`。
否则每次旋转都会重新加载工作台，打开的文件与滚动位置全部丢失。

## 4. 数据布局

```
/data/data/com.vsc.android/
├── files/
│   ├── usr/                     proot 的 PREFIX
│   │   ├── bin/                 符号链接（proot / loader / libtalloc / libandroid-shmem）
│   │   ├── tmp/                 PRoot 与 /dev/shm 的落点
│   │   ├── home/
│   │   └── var/lib/proot-distro/installed-rootfs/ubuntu/   ← guest 的 /
│   │       └── opt/code-server-4.143.1-linux-arm64/
│   ├── runtime/                 落盘的 .tgz 归档
│   ├── scripts/run.sh           生成的 proot 启动脚本
│   └── logs/                    导出的诊断日志
└── (external)/Android/data/com.vsc.android/files/workspace/   默认工作区，bind 到 guest /root/workspace
```

## 5. proot 启动参数

```sh
proot --rootfs=<rootfs> --cwd=/root --change-id=0:0 \
      --bind=/dev --bind=/proc --bind=/sys \
      --bind=<rootfs>/tmp:/dev/shm \
      --link2symlink --sysvipc --kernel-release=6.2.1-PRoot-Distro \
      --bind=<workspace>:/root/workspace [--bind=/storage/emulated/0:/sdcard] \
      /bin/bash -l -c "exec /opt/code-server-<ver>/bin/code-server --bind-addr 127.0.0.1:<port> …"
```

注意脚本里用的是 **guest 路径** `/opt/...`，不是宿主的绝对路径——rootfs 在 guest 中被重映射为 `/`。
