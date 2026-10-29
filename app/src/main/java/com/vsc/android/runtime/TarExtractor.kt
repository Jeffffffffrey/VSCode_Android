package com.vsc.android.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/**
 * 纯 Kotlin 的 tar(.gz) 解包器。
 *
 * 为什么不用 busybox：Termux 的 busybox 只是一个 4KB 启动壳，
 * 依赖 libbusybox.so.1.38.0 与 libandroid-selinux.so，链式依赖不可控。
 * 改为在应用内解包后，运行时只需要 proot/loader/libtalloc/libandroid-shmem 四个 ELF。
 *
 * 需要正确处理：
 * - 文件权限位（rootfs 里的 /bin/bash 必须有可执行位）
 * - 符号链接（rootfs 里 /bin → usr/bin 这类链接缺一不可）
 * - 硬链接（退化为复制，Android 文件系统上更稳）
 * - 路径穿越（拒绝 ../ 逃逸）
 */
object TarExtractor {

    private const val BUFFER_SIZE = 256 * 1024
    private const val PROGRESS_INTERVAL = 200L

    fun extract(
        archive: File,
        destDir: File,
        onProgress: (entries: Long, current: String) -> Unit = { _, _ -> },
    ): Long {
        if (!archive.exists() || archive.length() == 0L) {
            throw IOException("归档不存在或为空：${archive.absolutePath}")
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("无法创建目标目录：${destDir.absolutePath}")
        }

        val rootPath = destDir.canonicalFile.toPath()
        val buffer = ByteArray(BUFFER_SIZE)
        var entries = 0L
        var skipped = 0L

        openArchiveStream(archive).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    entries++
                    if (entries % PROGRESS_INTERVAL == 0L) onProgress(entries, entry.name)

                    // 单条失败不影响整体：Windows 上非法文件名、设备节点等都应跳过
                    runCatching {
                        val target = resolveSafely(rootPath, entry.name) ?: return@runCatching
                        when {
                            entry.isDirectory -> {
                                Files.createDirectories(target)
                                applyMode(target, entry.mode, isDirectory = true)
                            }

                            entry.isSymbolicLink -> createSymbolicLink(target, entry.linkName)

                            entry.isLink -> copyLinkTarget(rootPath, target, entry.linkName)

                            entry.isFile -> {
                                target.parent?.let { Files.createDirectories(it) }
                                Files.newOutputStream(target).use { out ->
                                    while (true) {
                                        val read = tar.read(buffer)
                                        if (read <= 0) break
                                        out.write(buffer, 0, read)
                                    }
                                }
                                applyMode(target, entry.mode)
                            }

                            else -> Unit // 设备节点 / FIFO 在应用沙箱内无法创建
                        }
                    }.onFailure { skipped++ }
                }
            }
        }

        if (skipped > 0) {
            onProgress(entries, "跳过 $skipped 个无法写入的条目")
        }
        onProgress(entries, "完成")
        return entries
    }

    /** 只统计条目数量，用于预检与进度预估 */
    fun countEntries(archive: File): Long {
        var count = 0L
        openArchiveStream(archive).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                while (tar.nextTarEntry != null) count++
            }
        }
        return count
    }

    private fun openArchiveStream(archive: File): InputStream {
        val buffered = BufferedInputStream(FileInputStream(archive), BUFFER_SIZE)
        buffered.mark(2)
        val magic = ByteArray(2)
        val read = buffered.read(magic)
        buffered.reset()
        val isGzip = read == 2 &&
            (magic[0].toInt() and 0xFF) == 0x1F &&
            (magic[1].toInt() and 0xFF) == 0x8B
        return if (isGzip) GzipCompressorInputStream(buffered, true) else buffered
    }

    private fun resolveSafely(root: Path, rawName: String): Path? {
        val cleaned = rawName.removePrefix("./").trimEnd('/')
        if (cleaned.isEmpty() || cleaned == ".") return null
        val resolved = root.resolve(cleaned).normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("归档条目越界：$rawName")
        }
        return resolved
    }

    private fun createSymbolicLink(target: Path, linkName: String?) {
        val name = linkName?.takeIf { it.isNotBlank() } ?: return
        target.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(target)
        runCatching { Files.createSymbolicLink(target, Paths.get(name)) }
    }

    private fun copyLinkTarget(root: Path, target: Path, linkName: String?) {
        val name = linkName?.takeIf { it.isNotBlank() } ?: return
        val source = runCatching { resolveSafely(root, name) }.getOrNull() ?: return
        if (!Files.isRegularFile(source)) return
        target.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(target)
        Files.copy(source, target)
    }

    private fun applyMode(path: Path, mode: Int, isDirectory: Boolean = false) {
        if (mode == 0) return
        val permissions = HashSet<PosixFilePermission>(9)
        // 目录必须保留属主可写，否则先解出的 0555 目录会让后续子项写入失败
        if (isDirectory) {
            permissions.add(PosixFilePermission.OWNER_READ)
            permissions.add(PosixFilePermission.OWNER_WRITE)
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
        }
        if (mode and 0x100 != 0) permissions.add(PosixFilePermission.OWNER_READ)
        if (mode and 0x080 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0x040 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0x020 != 0) permissions.add(PosixFilePermission.GROUP_READ)
        if (mode and 0x010 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0x008 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0x004 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0x002 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0x001 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }
}
