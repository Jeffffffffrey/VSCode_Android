package com.vsc.android.webview

import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * 承接 WebView 的文件选择请求。
 *
 * 参考实现普遍缺失这一环，导致 VS Code 里「打开文件 / 上传」点了没反应。
 * 这里把请求转交给 Compose 侧的 ActivityResultLauncher。
 */
class FileChooserHost {

    var launcher: ((Intent) -> Unit)? = null

    private var pending: ValueCallback<Array<Uri>>? = null

    fun handle(params: WebChromeClient.FileChooserParams?, callback: ValueCallback<Array<Uri>>): Boolean {
        val launch = launcher ?: run {
            callback.onReceiveValue(null)
            return false
        }
        pending = callback
        val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        return runCatching {
            launch(intent)
            true
        }.getOrElse {
            pending = null
            callback.onReceiveValue(null)
            false
        }
    }

    fun deliver(uris: List<Uri>?) {
        val callback = pending ?: return
        pending = null
        callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() }?.toTypedArray())
    }
}
