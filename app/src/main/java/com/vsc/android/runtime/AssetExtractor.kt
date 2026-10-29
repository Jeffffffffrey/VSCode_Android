package com.vsc.android.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 把 APK 内 assets/runtime 下的归档流式落盘。
 *
 * 判定跳过策略：目标文件已存在且大小与 asset 一致即认为已落盘。
 * 全程流式复制，绝不把 100MB+ 归档read入内存。
 */
class AssetExtractor(private val context: Context) {

    companion object {
        private const val TAG = "AssetExtractor"
        private const val ASSET_ROOT = "runtime"

        /**
         * 首启所需空间估算：归档约 250MB（code-server 216MB + rootfs 29MB），
         * 解压后 rootfs ~200MB + code-server ~600MB，再留出日志与扩展余量。
         */
        private const val REQUIRED_FREE_BYTES = 2_200L * 1024 * 1024
    }

    fun listRuntimeAssets(): List<String> =
        (context.assets.list(ASSET_ROOT) ?: emptyArray()).filter { it != "PLACEHOLDER.txt" }.sorted()

    fun ensureFreeSpace() {
        val free = RuntimePaths.baseDir.usableSpace
        if (free in 1 until REQUIRED_FREE_BYTES) {
            throw IllegalStateException(
                "可用存储空间不足（剩余 ${free / 1024 / 1024}MB，至少需要约 1.1GB）。请清理空间后重试。",
            )
        }
    }

    /**
     * @param onProgress (当前文件名, 本次总体进度 0f..1f)
     */
    fun extractIfNeeded(onProgress: (String, Float) -> Unit) {
        val assets = listRuntimeAssets()
        if (assets.isEmpty()) {
            throw IllegalStateException(
                "APK 内没有内置运行时资源。请在项目根目录执行 scripts\\fetch-runtime.ps1 重新打包。",
            )
        }
        RuntimePaths.ensureDirs()

        assets.forEachIndexed { index, name ->
            val assetPath = "$ASSET_ROOT/$name"
            val target = File(RuntimePaths.runtimeAssets, name)
            val expectedSize = assetSize(assetPath)

            if (target.exists() && expectedSize > 0 && target.length() == expectedSize) {
                onProgress(name, (index + 1f) / assets.size)
                return@forEachIndexed
            }

            Log.i(TAG, "落盘 $name → ${target.absolutePath}")
            val buffer = ByteArray(256 * 1024)
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val inFile = if (expectedSize > 0) {
                            (copied.toFloat() / expectedSize).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        onProgress(name, (index + inFile) / assets.size)
                    }
                    output.flush()
                }
            }
            onProgress(name, (index + 1f) / assets.size)
        }
    }

    private fun assetSize(assetPath: String): Long = runCatching {
        context.assets.openFd(assetPath).use { it.length }
    }.getOrDefault(-1L)
}
