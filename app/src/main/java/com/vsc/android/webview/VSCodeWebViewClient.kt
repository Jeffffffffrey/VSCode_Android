package com.vsc.android.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vsc.android.runtime.RuntimeConfig

/**
 * 只允许 WebView 停留在本机服务上，其余链接交给系统浏览器。
 * 同时在页面加载完成后注入剪贴板与登录辅助脚本。
 */
class VSCodeWebViewClient(
    private val onExternalUrl: (String) -> Unit,
    private val onLoadFailed: (String) -> Unit = {},
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (isInternal(uri)) return false
        onExternalUrl(uri.toString())
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        // 必须让 WebView 持有窗口焦点，否则页面里的输入框拿到焦点后
        // Chromium 的 showSoftInput 会被系统忽略（表现为点输入框不弹键盘）。
        // 「点非输入区也弹键盘」由 IME_GUARD 在页面侧拦掉。
        view.requestFocus()
        view.evaluateJavascript(DIALOG_LAYOUT_FIX, null)
        view.evaluateJavascript(DIALOG_DISMISS_ON_OUTSIDE_CLICK, null)
        view.evaluateJavascript(IME_GUARD, null)
        view.evaluateJavascript(CLIPBOARD_POLYFILL, null)
        val password = RuntimeConfig.password
        if (RuntimeConfig.authMode == RuntimeConfig.AuthMode.PASSWORD && password.isNotEmpty()) {
            view.evaluateJavascript(loginAutofill(password), null)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: android.webkit.WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onLoadFailed("${error.errorCode} ${error.description}")
        }
    }

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    companion object {
        /**
         * VS Code 1.143 的 `.monaco-dialog-box` 在 Web 构建下 max-height 被解析为 0px，
         * 导致所有 Monaco 对话框（Copilot 登录、重命名、确认框…）被裁成一条，
         * 只剩最后一个按钮露出来。
         *
         * 根因是 max-height:100% 的百分比在 auto 高度祖先上解析成 0；
         * 这里改用与父级无关的 vh 计算，同时保留"超高时收缩"的原意。
         */
        private const val DIALOG_LAYOUT_FIX = """
            (function () {
              if (window.__vscDialogFixApplied) return;
              window.__vscDialogFixApplied = true;

              // 这台设备的 Android WebView 里 vh 恒为 0（vw 正常，px/% 正常），
              // 而 VS Code 给对话框写的是 max-height:90vh → 0，内容被 overflow:hidden 全裁掉。
              // 因此只能用 innerHeight 换算成 px，并且要写成内联 !important
              // （VS Code 用的是内联样式，样式表规则压不过它）。
              function capPx() {
                var h = window.innerHeight || document.documentElement.clientHeight || 800;
                return Math.max(240, Math.round(h * 0.9)) + 'px';
              }

              function fix(el) {
                if (!el || !el.style) return;
                el.style.setProperty('max-height', capPx(), 'important');
              }

              function scan(root) {
                if (!root || !root.querySelectorAll) return;
                var list = root.querySelectorAll('.monaco-dialog-box');
                for (var i = 0; i < list.length; i++) fix(list[i]);
              }

              try {
                var observer = new MutationObserver(function (mutations) {
                  for (var i = 0; i < mutations.length; i++) {
                    var m = mutations[i];
                    if (m.type === 'attributes') {
                      // 必须校验目标确实是对话框：
                      // 否则 body / 工作台容器的 class、style 变化也会被误写 max-height，
                      // 整页会被压扁，底部露出一条 WebView 背景色的黑边
                      if (m.target && m.target.classList &&
                          m.target.classList.contains('monaco-dialog-box')) {
                        fix(m.target);
                      }
                      continue;
                    }
                    for (var j = 0; j < m.addedNodes.length; j++) {
                      var node = m.addedNodes[j];
                      if (!node || node.nodeType !== 1) continue;
                      if (node.classList && node.classList.contains('monaco-dialog-box')) {
                        fix(node);
                      } else {
                        scan(node);
                      }
                    }
                  }
                });
                observer.observe(document.documentElement, {
                  childList: true,
                  subtree: true,
                  attributes: true,
                  attributeFilter: ['style', 'class'],
                });
              } catch (e) { /* 老 WebView 忽略 */ }

              // 同一个 vh 缺陷会波及聊天里的卡片（max-height: min(300px, 45vh) 等），
              // 这里把样式表里所有 vh 规则按 innerHeight 重写成 px，追加到 head 末尾生效
              var VH_STYLE_ID = 'vsc-android-vh-fix';

              function rewriteVhRules() {
                var h = window.innerHeight || 800;
                var out = [];
                for (var i = 0; i < document.styleSheets.length; i++) {
                  var rules;
                  try { rules = document.styleSheets[i].cssRules } catch (e) { continue }
                  if (!rules) continue;
                  for (var j = 0; j < rules.length; j++) {
                    var text = rules[j].cssText;
                    if (!text || text.indexOf('vh') < 0) continue;
                    out.push(text.replace(/([\d.]+)vh/g, function (m, n) {
                      return Math.round(h * parseFloat(n) / 100) + 'px';
                    }));
                  }
                }
                if (!out.length) return;
                var el = document.getElementById(VH_STYLE_ID);
                if (!el) {
                  el = document.createElement('style');
                  el.id = VH_STYLE_ID;
                  (document.head || document.documentElement).appendChild(el);
                }
                el.textContent = out.join('\n');
              }

              function refresh() {
                rewriteVhRules();
                scan(document);
              }

              // 视口变化后重新计算
              window.addEventListener('resize', refresh);
              window.addEventListener('orientationchange', refresh);

              refresh();
            })();
        """

        /**
         * 点击浮层外部关闭。
         *
         * 桌面端靠 Esc 关闭浮层，平板上没有物理键盘，弹层会卡住退不掉。
         * 需要覆盖两种结构：
         * 1. `.quick-input-widget` —— 命令面板、「打开文件夹」等（打开文件夹走的就是这个）
         * 2. `.monaco-dialog-modal-block` —— 模态对话框，如 Copilot 登录
         */
        private const val DIALOG_DISMISS_ON_OUTSIDE_CLICK = """
            (function () {
              if (window.__vscDismissApplied) return;
              window.__vscDismissApplied = true;

              var CANCEL_TEXT = /^(cancel|close|取消|关闭|关闭窗口)$/i;

              function clickCancel(box) {
                var buttons = box.querySelectorAll('.dialog-buttons .monaco-button');
                for (var i = 0; i < buttons.length; i++) {
                  var text = (buttons[i].textContent || '').trim();
                  if (CANCEL_TEXT.test(text)) {
                    buttons[i].click();
                    return true;
                  }
                }
                return false;
              }

              function escapeEvent(type) {
                var event = new KeyboardEvent(type, {
                  key: 'Escape',
                  code: 'Escape',
                  bubbles: true,
                  cancelable: true,
                });
                // KeyboardEvent 构造器不接受 keyCode，Monaco 会读它，只能自己挂上
                try {
                  Object.defineProperty(event, 'keyCode', { get: function () { return 27; } });
                  Object.defineProperty(event, 'which', { get: function () { return 27; } });
                } catch (e) { /* 忽略 */ }
                return event;
              }

              function sendEscape(node) {
                if (!node || !node.dispatchEvent) return;
                node.dispatchEvent(escapeEvent('keydown'));
                node.dispatchEvent(escapeEvent('keyup'));
              }

              /**
               * 只发一次 Esc，避免多余的 Esc 落到工作台上把别的弹层也关掉；
               * 若 150ms 后对话框仍在，再补发一次到 document 兜底。
               */
              function tryEscape(box) {
                var active = document.activeElement;
                sendEscape(active && box.contains(active) ? active : box);

                setTimeout(function () {
                  if (document.contains(box)) sendEscape(document);
                }, 150);
              }

              /** 当前可见的 quick input（命令面板 / 打开文件夹等），没有则返回 null */
              function visibleQuickInput() {
                var widgets = document.querySelectorAll('.quick-input-widget');
                for (var i = 0; i < widgets.length; i++) {
                  var el = widgets[i];
                  var style = getComputedStyle(el);
                  if (style.display === 'none' || style.visibility === 'hidden') continue;
                  if (el.getBoundingClientRect().height > 0) return el;
                }
                return null;
              }

              /** 吞掉紧随其后的一次 click，避免关弹层的同时又点到了底下的编辑器 */
              function swallowNextClick() {
                var swallow = function (e) {
                  e.stopPropagation();
                  e.preventDefault();
                };
                document.addEventListener('click', swallow, true);
                setTimeout(function () {
                  document.removeEventListener('click', swallow, true);
                }, 350);
              }

              document.addEventListener('pointerdown', function (event) {
                var target = event.target;
                if (!target || !target.closest) return;

                // 1) quick input：VS Code 不会因为失焦自动关，必须显式发 Esc
                var quick = visibleQuickInput();
                if (quick) {
                  if (target.closest('.quick-input-widget')) return;
                  event.preventDefault();
                  event.stopPropagation();
                  sendEscape(quick.querySelector('input') || quick);
                  swallowNextClick();

                  setTimeout(function () {
                    var still = document.querySelector('.quick-input-widget');
                    if (still && still.getBoundingClientRect().height > 0) sendEscape(document);
                  }, 150);
                  return;
                }

                // 2) Monaco 模态对话框
                var block = target.closest('.monaco-dialog-modal-block');
                if (!block) return;
                // 点在对话框本体上就交给 VS Code 自己处理
                if (target.closest('.monaco-dialog-box')) return;

                var box = block.querySelector('.monaco-dialog-box');
                if (!box) return;

                if (clickCancel(box)) return;

                tryEscape(box);
              }, true);
            })();
        """

        /**
         * 只在点到真正可输入的区域时才允许弹键盘。
         *
         * Android WebView 的 IME 适配器一旦挂上（页面上有 input 持有焦点），
         * 之后**每次触摸**系统都会把输入法顶出来，于是点侧边栏、点菜单也会弹键盘。
         * 这里在触摸非编辑区时主动让焦点退出编辑态，适配器随之失活。
         */
        private const val IME_GUARD = """
            (function () {
              if (window.__vscImeGuard) return;
              window.__vscImeGuard = true;

              function isEditable(el) {
                if (!el || el.tagName === undefined) return false;
                var tag = el.tagName;
                if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true;
                if (el.isContentEditable) return true;
                return false;
              }

              // 这些容器点下去就该出键盘（即使点到的不是 input 元素本身，
              // 比如聊天输入框、搜索框、命令面板，点容器后内部才出现编辑元素）。
              // 注意别把外层工具条容器写进来：.interactive-input-part 覆盖了 Agent/Models
              // 那排按钮，写进来会导致这些按钮在键盘打开时点不动
              var INPUT_REGIONS = [
                '.monaco-editor',
                '.quick-input-widget',
                '.chat-input-container',
                '.find-widget',
                '.search-view',
                '.terminal-wrapper',
              ];

              // 记录最近一个真正获得焦点的可编辑元素。
              // Chromium 在输入法激活时会把它换成 native-edit-context，
              // 那时 activeElement 已不是输入框本身，光 blur 它没有任何效果
              var lastEditable = null;
              document.addEventListener('focusin', function (e) {
                if (!isEditable(e.target)) return;
                lastEditable = e.target;

                // 输入框拿到焦点时显式请求键盘：
                // Chromium 的隐式弹出在 WebView 刚收过键盘后会失效，
                // 不兜底就会出现「点输入框没反应」
                setTimeout(function () {
                  var active = document.activeElement;
                  var stillEditing = active === e.target ||
                    (active && active.classList && active.classList.contains('native-edit-context'));
                  if (!stillEditing) return;
                  if (window.Android && Android.showKeyboard) {
                    try { Android.showKeyboard(); } catch (err) { /* 忽略 */ }
                  }
                }, 60);
              }, true);

              // 用原生 insets 判断键盘是否真的在显示，比看 DOM 准得多
              function isImeShowing() {
                try {
                  return !!(window.Android && Android.isKeyboardShown && Android.isKeyboardShown());
                } catch (e) {
                  return false;
                }
              }

              /**
               * 把焦点从编辑区移走。
               *
               * 关键点：对 Chromium 的 native-edit-context 调 blur() 是**无效**的，
               * 所以之前用 blur 循环会表现为「键盘收起了又弹、弹了又收」。
               * 把焦点交给 body 才能真正结束输入会话，且不需要反复执行。
               */
              function moveFocusAwayFromEditor() {
                try {
                  if (!document.body.hasAttribute('tabindex')) {
                    document.body.setAttribute('tabindex', '-1');
                  }
                  document.body.focus();
                } catch (e) { /* 忽略 */ }
              }

              function allowsKeyboard(el) {
                if (!el || !el.closest) return false;
                if (el.closest('input, textarea, select')) return true;
                if (el.closest('[contenteditable="true"]')) return true;
                for (var i = 0; i < INPUT_REGIONS.length; i++) {
                  if (el.closest(INPUT_REGIONS[i])) return true;
                }
                return false;
              }

              document.addEventListener('pointerdown', function (event) {
                if (allowsKeyboard(event.target)) return;

                // 已经展开的菜单、浮层内部点击完全不干预：
                // 这些控件依赖焦点保持，一旦失焦会立刻收起（表现为「点二级菜单反而把菜单关掉」）。
                // 注意不能写 [role="menuitem"]：菜单栏（☰）的按钮也带这个 role，
                // 误判会让我们在该收键盘时直接跳过，反而把点击吞掉
                if (event.target.closest &&
                    event.target.closest('.monaco-menu, .context-view, .monaco-menu-container, [role="menu"]')) {
                  return;
                }

                // 输入法是否处于激活态。insets 可能滞后，因此再叠加一个 DOM 信号：
                // native-edit-context 是 Chromium 的输入法宿主，它存在就说明会话还在
                var activeNow = document.activeElement;
                var imeActive = isImeShowing() ||
                  !!(activeNow && activeNow.classList &&
                     activeNow.classList.contains('native-edit-context'));

                var clickX = event.clientX;
                var clickY = event.clientY;

                if (imeActive) {
                  // 顺序很关键：必须先把焦点移出编辑区，再收键盘。
                  // 只收键盘的话，Chromium 会因为编辑元素仍持有焦点而立刻把它弹回来，
                  // 菜单栏这类控件在输入法激活时会忽略合成事件，于是点了没反应。
                  setTimeout(function () {
                    if (document.querySelector('.quick-input-widget, .monaco-menu, .monaco-dialog-modal-block')) {
                      return;
                    }
                    moveFocusAwayFromEditor();
                    if (window.Android && Android.hideKeyboard) {
                      try { Android.hideKeyboard(); } catch (e) { /* 忽略 */ }
                    }
                  }, 150);

                  // 补一次合成点击。按坐标重新取元素：收键盘会触发重排，
                  // pointerdown 时捕获的节点可能已经脱离文档
                  var realClickArrived = false;
                  var markReal = function () { realClickArrived = true; };
                  document.addEventListener('click', markReal, true);

                  setTimeout(function () {
                    document.removeEventListener('click', markReal, true);
                    if (realClickArrived) return;

                    // 真实 pointerdown 已经起了作用（菜单/弹层已出现）就不能再补，
                    // 否则相当于又点了一次，会把刚展开的菜单切换关闭
                    if (document.querySelector('.quick-input-widget, .monaco-menu, .monaco-dialog-modal-block')) {
                      return;
                    }

                    var el = document.elementFromPoint(clickX, clickY) || event.target;
                    if (!el || !el.dispatchEvent) return;

                    // 补齐 pointer 与 mouse 两套事件：
                    // VS Code 的菜单栏是在 pointerdown 上展开的，只补 click 不会生效
                    var pointerOpts = {
                      bubbles: true,
                      cancelable: true,
                      view: window,
                      button: 0,
                      buttons: 1,
                      pointerId: 1,
                      pointerType: 'touch',
                      isPrimary: true,
                      clientX: clickX,
                      clientY: clickY,
                    };
                    try {
                      el.dispatchEvent(new PointerEvent('pointerdown', pointerOpts));
                      pointerOpts.buttons = 0;
                      el.dispatchEvent(new PointerEvent('pointerup', pointerOpts));
                    } catch (e) { /* 老 WebView 忽略 */ }

                    var mouseOpts = {
                      bubbles: true,
                      cancelable: true,
                      view: window,
                      button: 0,
                      clientX: clickX,
                      clientY: clickY,
                    };
                    el.dispatchEvent(new MouseEvent('mousedown', mouseOpts));
                    el.dispatchEvent(new MouseEvent('mouseup', mouseOpts));
                    el.dispatchEvent(new MouseEvent('click', mouseOpts));
                  }, 340);
                }

                // 键盘没开时也要移走焦点：用户手动收键盘后，编辑元素仍持有焦点，
                // 下一次触摸系统就会把键盘重新弹出来。这里只需要移焦点，不用收键盘。
                if (!imeActive) {
                  setTimeout(function () {
                    if (document.querySelector('.quick-input-widget, .monaco-menu, .monaco-dialog-modal-block')) {
                      return;
                    }
                    moveFocusAwayFromEditor();
                  }, 150);
                }

              }, true);
            })();
        """

        /**
         * Android WebView 在无用户手势时拒绝 navigator.clipboard，VS Code 的粘贴会失效。
         * 这里把读写改道到原生桥。
         */
        private const val CLIPBOARD_POLYFILL = """
            (function () {
              if (!window.Android) return;
              try {
                var nav = navigator;
                var c = nav.clipboard || {};
                c.readText = function () { return Promise.resolve(Android.getClipboard()); };
                c.writeText = function (text) {
                  Android.setClipboard(String(text == null ? '' : text));
                  return Promise.resolve();
                };
                Object.defineProperty(nav, 'clipboard', { value: c, configurable: true });
              } catch (e) { /* 老版本 WebView 忽略 */ }
            })();
        """

        private fun loginAutofill(password: String): String {
            val safe = password.replace("\\", "\\\\").replace("\"", "\\\"")
            return """
                (function () {
                  if (!location.pathname || location.pathname.indexOf('/login') < 0) return;
                  var pw = "$safe";
                  function fill() {
                    var input = document.querySelector('input[type="password"]');
                    if (!input || input.value) return;
                    input.value = pw;
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    var form = input.closest('form');
                    if (form) {
                      setTimeout(function () {
                        var btn = form.querySelector('button[type="submit"]') || form.querySelector('button');
                        if (btn) btn.click();
                      }, 300);
                    }
                  }
                  fill();
                  setTimeout(fill, 1200);
                })();
            """
        }
    }
}
