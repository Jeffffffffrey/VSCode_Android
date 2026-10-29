package com.vsc.android.runtime

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 用仓库里真实的运行时归档验证解包器。
 *
 * 这些用例只在 assets/runtime 已由 scripts/fetch-runtime.ps1 填充时执行，
 * 干净检出时自动跳过，不会让 CI 失败。
 */
class TarExtractorTest {

    private val assetsDir = File("src/main/assets/runtime")

    @Test
    fun rootfsExtractsWithExecutables() {
        val archive = File(assetsDir, "rootfs.tar.gz")
        assumeTrue("rootfs 归档不存在，跳过", archive.exists())

        val dest = File("build/tmp-test/rootfs")
        dest.deleteRecursively()

        val entries = TarExtractor.extract(archive, dest)
        assertTrue("应解出大量条目，实际 $entries", entries > 1000)

        assertTrue("缺少 /etc", File(dest, "etc").isDirectory)
        assertTrue("缺少 /etc/passwd", File(dest, "etc/passwd").isFile)

        // Ubuntu 24.04 是 merged-usr，bash 位于 /usr/bin/bash
        val bash = File(dest, "usr/bin/bash")
        assertTrue("缺少 /usr/bin/bash", bash.isFile)
        assertTrue("/usr/bin/bash 没有可执行位", bash.canExecute())

        val apt = File(dest, "usr/bin/apt-get")
        assertTrue("缺少 /usr/bin/apt-get", apt.isFile)
    }

    @Test
    fun codeServerArchiveIsReadable() {
        val archive = File(assetsDir, "code-server-arm64.tar.gz")
        assumeTrue("code-server 归档不存在，跳过", archive.exists())

        // 4.143.1 实测约 7400 个条目
        val count = TarExtractor.countEntries(archive)
        assertTrue("条目数异常：$count", count > 5_000)
    }

    @Test
    fun rejectsPathTraversal() {
        // 直接构造一个含 ../ 的 tar，确认解包器拒绝越界写入
        val tmp = File("build/tmp-test/evil.tar")
        tmp.parentFile?.mkdirs()
        val payload = "boom".toByteArray()
        val name = "../escaped.txt"

        java.io.FileOutputStream(tmp).use { fos ->
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(fos).use { tar ->
                val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }

        val dest = File("build/tmp-test/evil-out")
        dest.deleteRecursively()
        val escaped = File("build/tmp-test/escaped.txt")
        escaped.delete()

        runCatching { TarExtractor.extract(tmp, dest) }
        assertTrue("不应写出越界文件", !escaped.exists())
    }
}
