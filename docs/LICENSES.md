# 第三方组件与许可

本项目自身代码以 MIT 发布。分发 APK 时会打包下列第三方组件，
需在分发包中保留对应许可声明。

| 组件 | 版本 | 许可 | 说明 |
|---|---|---|---|
| Microsoft VS Code（server / web 资源） | 1.143.1 | MIT | 通过 code-server 发行包内置 |
| code-server | 4.143.1 | MIT | coder/code-server |
| Ubuntu Base | 24.04.5 | 多种（GPL/LGPL 等） | 根文件系统内含大量系统库 |
| proot | 5.1.107.101 | GPL-2.0 | Termux 构建 |
| libtalloc | 2.5.0 | LGPL-3.0 | proot 依赖 |
| libandroid-shmem | 0.7 | MIT | proot 依赖 |
| Apache Commons Compress | 1.21 | Apache-2.0 | tar/gzip 解析 |
| AndroidX / Jetpack Compose | BOM 2025.12.00 | Apache-2.0 | UI 与基础库 |
| Kotlin 标准库 / 协程 | 2.2.21 / 1.9.0 | Apache-2.0 | 语言运行时 |

## 合规注意

1. **扩展市场**：默认索引 open-vsx.org。切换到 Microsoft Marketplace 可能违反其服务条款，
   本项目默认关闭该选项并在界面标注风险。
2. **商标**：`VS Code`、`Visual Studio Code` 是 Microsoft 商标。若公开发布，
   建议改名并在描述中声明"非官方构建，基于 MIT 许可的 VS Code 源码"。
3. **GPL 组件**：proot 为 GPL-2.0，随 APK 二进制分发时须提供相应源码获取方式
   （Termux packages 仓库地址即可满足）。
4. **Ubuntu 根文件系统**：内含 GPL/LGPL 库，分发时应附带 rootfs 内
   `/usr/share/doc/*/copyright`（解包后原样保留）。

## 未使用但需注意

- Microsoft Marketplace 的扩展包本身有其各自许可，安装后由用户自行承担。
- 本项目不修改 VS Code 源码，仅通过 `product.json` 与 `settings.json` 调整配置。
