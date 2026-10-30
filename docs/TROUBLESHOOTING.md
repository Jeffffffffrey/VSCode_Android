# 排障手册

引导失败时，界面上会给出主因 + 建议，完整输出在「查看日志」里。
设置页可导出诊断日志（`files/logs/diagnostics-*.log`）。

## 构建期

### `缺少内置可执行组件：libproot.so…`
未执行 jniLibs 提取。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/prepare-jnilibs.ps1
```

### `APK 内没有内置运行时资源`
未下载归档，或归档名带了 `.tar.gz`。

```powershell
powershell -ExecutionPolicy Bypass -File scripts/fetch-runtime.ps1
```

若手动放置文件，必须是 `code-server-arm64.tgz` 与 `rootfs.tgz`：
aapt2 会解压并剥掉 `.gz` 后缀，导致应用内校验失败。

### `校验未通过：… p_align=4KB`
Termux 当前版本已是 16KB 对齐；若出现 4KB，说明取到了旧包，
在 Android 15+ 设备上可能无法加载，需退回旧版 Android 测试。

## 运行期

### 首启卡在「解压 Ubuntu 运行环境 / 安装 VS Code 服务端」
属正常，216MB 归档解压在低端设备上可能超过 3 分钟。
若超过 10 分钟无条目数增长：

- 检查剩余存储（需 ≥2.2GB）
- 展开日志看是否出现 `跳过 N 个无法写入的条目`
- 设置页「重置运行环境」后重试

### `服务进程已退出（退出码 1）`
多半是 proot 自身起不来。检查日志中是否有：

- `library "libtalloc.so.2" not found` → `LD_LIBRARY_PATH` 未生效或 jniLibs 缺失
- `proot error: … ptrace` → 部分 ROM 的 SELinux 策略限制，需换设备或关闭相关安全策略
- `Permission denied` 执行 `bin/proot` → 确认 `extractNativeLibs` 生效

### 等待服务响应超时
端口被占用或 code-server 启动缓慢：

1. 设置页换端口（1024–65535）后重启服务
2. 日志里搜索 `HTTP server listening`，确认 code-server 是否真的起来了

### 白屏
WebView 打开 `127.0.0.1:port` 一片空白：

- 系统 WebView 版本过低（需 Chrome 108+），在系统设置里更新「Android System WebView」
- 先用平板浏览器访问同一地址验证服务本身是否正常
- 设置页「导出日志」看到 `onReceivedError` 相关条目

### 终端打不开 / 无法创建 PTY
部分机型 `--bind=/dev` 受 SELinux 限制。
可在 `run.sh` 模板中把 `--bind=/dev` 换成 `--bind=/dev/pts`，或额外加 `--bind=/dev/ptmx`。

### 编辑器里「打开文件夹」选不到手机目录
WebView 无法提供目录选择器（File System Access API 在 Android 不可用）。
改用侧边栏输入路径，或开启「公共存储访问」后直接操作 `/sdcard`。

### 对话框只剩一个按钮 / 登录白框
现象：点 Copilot 登录、重命名、确认框等，弹窗只有一个按钮露出，其余内容被裁掉。

根因：VS Code 1.143 的 `.monaco-dialog-box` 在 Web 构建下 `max-height` 被解析成 `0px`
（`max-height:100%` 在 auto 高度祖先上解析为 0），`overflow:hidden` 把内容全裁了。
该问题影响所有 Monaco 对话框，不只是 Copilot。

已在 `VSCodeWebViewClient.onPageFinished` 注入修正：把样式表里所有 `N vh` 按 `innerHeight`
重写成像素（该设备 WebView 的 `vh` 恒为 0），并用 MutationObserver 给对话框补一条内联 `!important`。

> 踩过的坑：MutationObserver 监听的是 `documentElement` 的 `subtree` 属性变化，
> **必须校验 target 的 class 才写样式**。早期版本对任意元素都写 `max-height`，
> 结果写到了 `body` 上，工作台被压到 90% 高度，底部露出一条 WebView 背景色的黑边。

排查同类问题的方法见 `scripts/cdp-eval.ps1`（连 WebView 的 DevTools 协议执行 JS）：

```powershell
adb forward tcp:9222 localabstract:webview_devtools_remote_<应用pid>
powershell -ExecutionPolicy Bypass -File scripts/cdp-eval.ps1 -Expression "document.querySelector('.chat-setup-dialog').getBoundingClientRect().height"
```

### 底部状态栏被系统栏盖住 / 键盘弹出后内容被挡
`targetSdk` 35 起 Android 15+ 强制边到边，系统不再自动为状态栏、导航条留白，
而 WebView 不会自己处理 insets，结果 VS Code 的底部状态栏落在导航条下面。

修复在 `VscApp.kt` 的根容器：

```kotlin
.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
```

`union(ime)` 的作用是键盘弹出时一起收缩内容区，避免输入框被键盘挡住。
实测视口高度由 776 降到 728（状态栏 22 + 导航条 26），`.part.statusbar` 位于 700–728，完整可见。

### 键盘一会儿弹一会儿收 / 键盘开着时按钮点不动
Android WebView 的输入法会话是「粘」在编辑元素上的，由此派生出一串问题：

- 键盘开着时 Chromium 会吞掉编辑区以外的点击（只发 `pointerdown`，不发 `mousedown/click`）
- 对 Chromium 的 `native-edit-context` 调 `blur()` 无效，反复 blur 会造成键盘闪动
- 用户手动收起键盘后，编辑元素仍持有焦点，下一次触摸系统又会把键盘弹出来

处置顺序（`VSCodeWebViewClient` 的 `IME_GUARD`）：

1. 键盘真在显示时才干预，用原生 `Android.isKeyboardShown()`（读 IME insets）判断
2. 先把焦点交给 `body`（`tabindex=-1` + `focus()`），这一步才能真正结束会话；`blur()` 不行
3. 再收键盘
4. 最后按坐标重新取元素补发 `pointerdown/pointerup` + 鼠标序列 —— 菜单栏是在 `pointerdown` 上展开的，只补 `click` 无效
5. 补发前先检查是否已经开出弹层，否则相当于又点一次，会把刚打开的菜单切换关闭

时序上必须「移焦点 → 收键盘 → 补点击」分三步延后执行，任一步提前都会被 Chromium 的默认处理覆盖。

### 扩展安装报 socket hang up
扩展的平台包（如 CodeLLDB 的 `code-lldb-linux-arm64.vsix`）直接从 github.com 下载，
国内网络常超时。用代理，或按提示 "Open download URL in a browser" 手动下载后用
"Install from VSIX..." 安装。

### 扩展装不上
- 确认市场选的是 open-vsx（Microsoft 市场需要切换且存在 ToS 风险）
- 部分扩展依赖原生二进制，只提供 amd64 包，在 arm64 上无法安装

### 修改端口/密码后没生效
这些配置在 `run.sh` 与 `config.yaml` 里，需要「重启服务」。
扩展市场切换需要重启服务后重新写 `product.json`。

## 重置

设置页 → 诊断 → 重置运行环境：
删除 rootfs 与解压标记，保留已落盘的归档，下次启动重新解压（不会重新下载）。
