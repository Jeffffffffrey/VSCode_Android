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
        // 页面就绪后把焦点给 WebView，保证第一次点输入框就能唤起输入法
        view.requestFocus()
        view.evaluateJavascript(DIALOG_LAYOUT_FIX, null)
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
