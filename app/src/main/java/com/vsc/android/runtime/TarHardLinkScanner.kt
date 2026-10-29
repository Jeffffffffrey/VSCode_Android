package com.vsc.android.runtime

import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 扫描 code-server 的 tar.gz，收集所有硬链接条目（TypeFlag = LNKTYPE）。
 *
 * Android 文件系统上解压这类归档时，硬链接常退化/丢失成空文件，导致 Node 启动即崩。
 * 拿到 `链接路径 → 目标路径` 映射后，由 shell 侧 `cp` 逐条兜底重写。
 */
object TarHardLinkScanner {

    private const val TAG = "TarHardLinkScanner"

    fun scan(archive: File, onProgress: (Long) -> Unit = {}): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var count = 0L

        BufferedInputStream(FileInputStream(archive), 256 * 1024).use { raw ->
            GzipCompressorInputStream(raw, true).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (entry.isLink) {
                            val link = normalize(entry.name)
                            val target = normalize(entry.linkName ?: "")
                            if (link.isNotEmpty() && target.isNotEmpty()) {
                                result[link] = target
                            }
                        }
                        count++
                        if (count % 2000 == 0L) onProgress(count)
                    }
                }
            }
        }

        Log.i(TAG, "共扫描 $count 个条目，发现 ${result.size} 个硬链接")
        return result
    }

    /** 写入 tab 分隔的映射文件，供 shell 侧 while read 循环消费 */
    fun writeMap(map: Map<String, String>, target: File) {
        target.bufferedWriter().use { writer ->
            map.forEach { (link, dest) ->
                writer.write(link)
                writer.write("\t")
                writer.write(dest)
                writer.write("\n")
            }
        }
    }

    private fun normalize(path: String): String =
        path.removePrefix("./").removePrefix("/").trimEnd('/')
}
