package com.vsc.android.runtime

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 绕过 Android 10+ 的 noexec 限制。
 *
 * targetSdk > 28 后应用私有目录被挂载为 noexec，无法执行任何 ELF；
 * 但 `nativeLibraryDir`（APK 解压出的 lib 目录）允许执行。
 * 因此把 proot 及其依赖以 `lib*.so` 命名打进 jniLibs，
 * 运行时在 `files/usr/bin` 建立符号链接指回真实文件。
 *
 * 依赖链（依据 Termux 包元数据实测）：
 *   proot            → libproot.so          (PT_INTERP=/system/bin/linker64)
 *   loader           → libloader.so         (PROOT_LOADER)
 *   libtalloc.so.2   → liblibtalloc.so.2.so
 *   libandroid-shmem → liblibandroid-shmem.so
 */
class NativeBinaryInstaller {

    companion object {
        private const val TAG = "NativeBinaryInstaller"

        /** jniLibs 文件名 → usr/bin 中的文件名（后者必须与 DT_NEEDED 完全一致） */
        private val BINARIES = linkedMapOf(
            "libproot.so" to "proot",
            "libloader.so" to "loader",
            "liblibtalloc.so.2.so" to "libtalloc.so.2",
            "liblibandroid-shmem.so" to "libandroid-shmem.so",
        )
    }

    fun install() {
        RuntimePaths.ensureDirs()

        val missing = BINARIES.keys.filterNot { File(RuntimePaths.nativeLibraryDir, it).exists() }
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "缺少内置可执行组件：${missing.joinToString()}。" +
                    "请在项目根目录执行 scripts\\prepare-jnilibs.ps1 后重新打包。",
            )
        }

        BINARIES.forEach { (libName, execName) ->
            val source = File(RuntimePaths.nativeLibraryDir, libName)
            replaceLink(execName, source)
            if (!source.canExecute()) runCatching { source.setExecutable(true, false) }
        }

        Log.i(TAG, "可执行组件就绪：${RuntimePaths.binDir.absolutePath}")
    }

    private fun replaceLink(name: String, target: File) {
        val linkFile = File(RuntimePaths.binDir, name)
        val linkPath = linkFile.toPath()

        // 必须先删除历史遗留的实体文件或旧链接，否则 createSymbolicLink 会失败
        runCatching {
            if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(linkPath)
            }
        }.onFailure { throw IllegalStateException("无法清理旧文件 ${linkFile.absolutePath}", it) }

        runCatching {
            Files.createSymbolicLink(linkPath, target.toPath())
        }.onFailure {
            throw IllegalStateException("创建符号链接失败：${linkFile.absolutePath} → ${target.absolutePath}", it)
        }
    }
}
