# VS Code for Android

在**未 root** 的 Android 12+ 平板上离线运行完整 VS Code 的原生应用。

不是远程客户端：APK 内置 `proot` + Ubuntu 24.04 arm64 rootfs + `code-server`，
首次启动在设备上完成解压与配置，之后完全离线可用，具备编辑器、集成终端、扩展市场与 LSP。

## 架构一句话

```
Kotlin(Compose) → 解包内嵌运行时 → proot 用户态伪造根文件系统 → code-server
                                                                  ↓
                                              WebView 加载 http://127.0.0.1:20000
```

Electron 没有 Android 运行时，因此不编译 `microsoft/vscode` 源码；
改为运行 VS Code 官方的 Web 版服务端（code-server 内置的 vscode-server 资源）。

## 快速开始

环境要求：JDK 17+、Android SDK（platform 36 / build-tools 36）、PowerShell 5.1+。

```powershell
# 1. 下载运行时归档（约 245MB，含 SHA-256 校验）
powershell -ExecutionPolicy Bypass -File scripts/fetch-runtime.ps1

# 2. 从 Termux 源提取 proot 及其依赖，伪装成 lib*.so 放进 jniLibs
powershell -ExecutionPolicy Bypass -File scripts/prepare-jnilibs.ps1

# 3. 构建
./gradlew :app:assembleDebug          # 或 Android Studio 直接 Run
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 300MB，仅 arm64-v8a）。

安装后首次启动需要 3-6 分钟解压运行环境，之后秒进编辑器。

## 目录结构

```
app/src/main/
├── java/com/vsc/android/
│   ├── runtime/          RuntimePaths / RuntimeConfig / NativeBinaryInstaller
│   │                     AssetExtractor / TarExtractor / BootstrapScriptBuilder
│   │                     RuntimeProcessManager / RuntimeRepository / LogBuffer
│   ├── service/          前台服务（specialUse）
│   ├── webview/          WebView 配置、剪贴板桥、文件选择器、外部链接处理
│   └── ui/               引导页、编辑器页、设置面板、日志抽屉
├── jniLibs/arm64-v8a/    proot / loader / libtalloc / libandroid-shmem（脚本生成，不入库）
└── assets/
    ├── runtime/          code-server 与 rootfs 归档（脚本下载，不入库）
    └── scripts/          run.sh 模板
scripts/                  fetch-runtime / prepare-jnilibs / verify-runtime
docs/                     架构、排障、许可
```

## 相对参考实现（Code FA）的差异

| 项 | Code FA | 本项目 |
|---|---|---|
| 外壳 | Flutter + Fragment 覆盖 WebView | Kotlin + Compose，`AndroidView` 直接挂载 |
| 监听地址 | `0.0.0.0` + `auth: none` | 仅 `127.0.0.1`，可选密码 |
| 文件选择器 | 未实现 | 已实现（`onShowFileChooser`） |
| 扩展市场 | 默认 Microsoft | 默认 open-vsx，可切换 |
| 遥测 | 未关闭 | 关闭 |
| 解包 | busybox + shell | Kotlin 直接解包（附单元测试） |
| Android 15+ | 未验证 16KB 对齐 | 四个 ELF 均为 16KB 页对齐 |

## 已知限制

- 仅 `arm64-v8a`；x86 平板与大多数模拟器无法运行
- APK 约 300MB，首启额外占用约 1.5GB
- `/sdcard` 需手动授权「所有文件访问权限」，否则只能使用应用私有工作区
- 服务仅监听回环地址，不提供局域网访问（如需可自行改 `config.yaml`）
- 首次启动耗时受设备 IO 影响较大

## 许可

本项目代码 MIT。内置组件（VS Code / code-server / Ubuntu / proot 等）许可详见 `docs/LICENSES.md`。
