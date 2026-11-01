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
         * 输入法与点击的通用处理。
         *
         * 不再为「菜单 / 状态栏 / 聊天面板……」逐个列白名单——那种做法永远漏一处。
         * 这里只保留两条与具体控件无关的规则：
         *
         * 1. 落点是否可编辑，由 DOM 直接判定（input / textarea / contenteditable / 代码编辑区），
         *    不依赖任何容器类名；
         * 2. 一次触摸如果没有产出 click，就认定它被 Chromium 在输入法激活期间吞掉了，
         *    交给原生在同一坐标重放一次真实触摸。
         *
         * 收拾焦点与键盘的动作推迟到 pointerup 之后：在 pointerdown 里做会打断目标
         * 自己的 pointerdown 处理（VS Code 菜单栏就是在 pointerdown 上展开的）。
         */
        private const val IME_GUARD = """
            (function () {
              if (window.__vscImeGuard) return;
              window.__vscImeGuard = true;

              var EDITABLE = 'input, textarea, select, [contenteditable="true"], [role="textbox"]';

              // 「这块区域是不是编辑器/输入框」。只看落点自身，不回看祖先关系，
              // 因此不会因为「聊天面板里也嵌了 monaco」而把整个面板误判成输入区
              function isEditingSurface(el) {
                if (!el || !el.closest) return false;
                return !!(el.closest(EDITABLE) || el.closest('.monaco-editor'));
              }

              function isEditableTarget(el) {
                if (!el || !el.closest) return false;
                if (el.closest(EDITABLE)) return true;
                // Chromium 把编辑元素换成 native-edit-context 之后它不再匹配上面的选择器，
                // 所以把代码编辑区整体算作可编辑
                return !!(el.closest('.monaco-editor') && el.closest('.part.editor'));
              }

              function imeVisible() {
                try {
                  return !!(window.Android && Android.isKeyboardShown && Android.isKeyboardShown());
                } catch (e) {
                  return false;
                }
              }

              // 弹层开着时不能动焦点，否则会把刚打开的弹层关掉。
              // 必须按「有没有实际尺寸」判断：这些元素收起后仍留在 DOM 里，
              // 只判断存在性会一直误判成「弹层开着」，清理与重放就永远不会执行
              function hasOverlay() {
                var els = document.querySelectorAll(
                  '.quick-input-widget, .monaco-menu, .monaco-dialog-modal-block'
                );
                for (var i = 0; i < els.length; i++) {
                  if (els[i].getBoundingClientRect().height > 0) {
                    dbg('overlay hit=' + els[i].className + ' h=' +
                        Math.round(els[i].getBoundingClientRect().height));
                    return true;
                  }
                }
                return false;
              }

              function dropFocus() {
                try {
                  if (!document.body.hasAttribute('tabindex')) {
                    document.body.setAttribute('tabindex', '-1');
                  }
                  document.body.focus();
                } catch (e) { /* 忽略 */ }
              }

              function hideIme() {
                if (window.Android && Android.hideKeyboard) {
                  try { Android.hideKeyboard(); } catch (e) { /* 忽略 */ }
                }
              }

              var tracking = null;

              // 判断「这次点击是不是要输入」，既不能只看落点、也不能只看焦点：
              //   · 聊天输入区本身是一个 Monaco 编辑器，落点命中的是 .view-line，
              //     不是 textarea，只看落点会判错；
              //   · 点击被吞掉时焦点根本不会离开旧输入框，只看焦点也会判错。
              // 正确的判据是：焦点（的输入法宿主）是否落在这次点击的位置范围内
              function tapLandedInEditing(t) {
                var hit = document.elementFromPoint(t.x, t.y);
                var active = document.activeElement;
                if (!hit || !active || active === document.body) return false;
                if (isEditableTarget(hit)) return true;
                if (active === hit || active.contains(hit) || hit.contains(active)) return true;
                // Chromium 的 native-edit-context 被插在编辑元素内部
                var box = hit.closest && hit.closest('.monaco-editor');
                return !!(box && box.contains(active));
              }

              function dbg(msg) {
                try { if (window.Android && Android.debugLog) Android.debugLog(msg); } catch (e) { /* 忽略 */ }
              }

              document.addEventListener('pointerdown', function (event) {
                var t = {
                  x: event.clientX,
                  y: event.clientY,
                  ime: imeVisible(),
                  clicked: false,
                  dragged: false,
                  settled: false
                };
                tracking = t;

                // 必须在 VS Code 处理这次点击「之前」就把键盘收掉。
                // 反过来（先弹菜单、后收键盘）会触发窗口重排，
                // VS Code 的弹层监听 resize，会把自己刚打开的菜单关掉。
                if (t.ime && !isEditingSurface(event.target)) {
                    // 先移焦点再收键盘：只收键盘的话，编辑元素仍持有焦点，
                    // Chromium 几百毫秒后会把它重新弹出来
                    dropFocus();
                    hideIme();
                    dbg('early dismiss, target=' + (event.target.className || event.target.tagName));
                }

                // 不能只依赖 pointerup：输入法激活时 Chromium 可能连 pointerup 都不派发，
                // 那样清理和重放都不会发生。这里的定时兜底不依赖任何后续事件
                setTimeout(function () { settle(t); }, 160);
              }, true);

              document.addEventListener('pointermove', function (event) {
                if (!tracking) return;
                var dx = event.clientX - tracking.x;
                var dy = event.clientY - tracking.y;
                // 超过 12px 视为拖动（滚动、选中），不能当成点击重放
                if (dx * dx + dy * dy > 144) tracking.dragged = true;
              }, true);

              document.addEventListener('click', function () {
                if (tracking) tracking.clicked = true;
              }, true);

              document.addEventListener('pointerup', function () {
                if (tracking) settle(tracking);
              }, true);

              function settle(t) {
                if (!t || t.settled) return;
                t.settled = true;

                // 等这次触摸的默认处理（含 focus 与 click）走完再判断
                setTimeout(function () {
                  // 落点就在编辑区内：这次就是要打字，什么都不做
                  var landed = tapLandedInEditing(t);
                  dbg('settle at=' + Math.round(t.x) + ',' + Math.round(t.y) +
                      ' ime=' + t.ime + ' landed=' + landed +
                      ' active=' + (document.activeElement ? document.activeElement.tagName : 'null'));
                  if (landed) return;

                  // 目标自己已经处理成功（开出了菜单/弹层）。
                  // 但此时键盘可能还在收起动画中，随后的重排会把弹层挤掉，
                  // 所以要等布局稳定后再确认一次：还在就不管，没了才补一次触摸
                  if (hasOverlay()) {
                    dbg('target handled, verify later');
                    if (!t.ime || t.dragged || t.clicked) return;
                    setTimeout(function () {
                      if (hasOverlay()) return;
                      dbg('overlay lost, replay');
                      if (window.Android && Android.replayTap) {
                        try { Android.replayTap(t.x, t.y); } catch (e) { /* 忽略 */ }
                      }
                    }, 450);
                    return;
                  }

                  // 到这一步说明这次点击什么也没干成（被输入法吞了，或落在空白处）。
                  // 先把焦点移出编辑区：用户手动收起键盘后编辑元素仍持有焦点，
                  // 下一次触摸系统会把它重新弹出来
                  dropFocus();
                  if (t.ime) hideIme();

                  // 没产生 click 说明确实被吞了：键盘已收起，按同一坐标重放真实触摸
                  if (!t.ime || t.dragged || t.clicked) {
                    dbg('no-replay clicked=' + t.clicked + ' dragged=' + t.dragged);
                    return;
                  }
                  setTimeout(function () {
                    if (t.clicked || hasOverlay()) return;
                    dbg('replay at=' + Math.round(t.x) + ',' + Math.round(t.y));
                    if (window.Android && Android.replayTap) {
                      try { Android.replayTap(t.x, t.y); } catch (e) { /* 忽略 */ }
                    }
                  }, 90);
                }, 0);
              }

              // 输入框拿到焦点时显式请求键盘：
              // Chromium 的隐式弹出在 WebView 刚收过键盘后会静默失败
              document.addEventListener('focusin', function (event) {
                if (!isEditableTarget(event.target)) return;
                setTimeout(function () {
                  var active = document.activeElement;
                  var stillEditing = active === event.target ||
                    !!(active && active.classList && active.classList.contains('native-edit-context'));
                  if (!stillEditing) return;
                  if (window.Android && Android.showKeyboard) {
                    try { Android.showKeyboard(); } catch (e) { /* 忽略 */ }
                  }
                }, 60);
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
