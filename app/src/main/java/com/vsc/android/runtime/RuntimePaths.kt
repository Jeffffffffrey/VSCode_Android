package com.vsc.android.runtime

import android.content.Context
import java.io.File

/**
 * 全应用统一的路径定义。
 *
 * 目录布局（均位于应用私有目录，无需任何存储权限）：
 * ```
 * files/
 * ├── usr/                       proot 的 PREFIX，等价于 Termux 的 $PREFIX
 * │   ├── bin/                   bash / busybox / proot / loader 的符号链接
 * │   ├── tmp/
 * │   ├── home/
 * │   └── var/lib/proot-distro/installed-rootfs/ubuntu/   Ubuntu rootfs
 * ├── runtime/                   内置归档落盘位置（code-server.tar.gz / rootfs.tar.xz）
 * ├── scripts/                   生成的 prepare.sh / run.sh
 * └── logs/
 * ```
 */
object RuntimePaths {

    private const val ROOTFS_NAME = "ubuntu"

    private var initialized = false

    lateinit var appContext: Context
        private set

    lateinit var baseDir: File
        private set

    lateinit var usrDir: File
        private set

    lateinit var binDir: File
        private set

    lateinit var tmpDir: File
        private set

    lateinit var homeDir: File
        private set

    lateinit var rootfsDir: File
        private set

    lateinit var runtimeAssets: File
        private set

    lateinit var scriptsDir: File
        private set

    lateinit var logsDir: File
        private set

    lateinit var nativeLibraryDir: File
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        if (initialized && appContext === app) return
        appContext = app
        baseDir = app.filesDir
        usrDir = File(baseDir, "usr")
        binDir = File(usrDir, "bin")
        tmpDir = File(usrDir, "tmp")
        homeDir = File(usrDir, "home")
        rootfsDir = File(usrDir, "var/lib/proot-distro/installed-rootfs/$ROOTFS_NAME")
        runtimeAssets = File(baseDir, "runtime")
        scriptsDir = File(baseDir, "scripts")
        logsDir = File(baseDir, "logs")
        nativeLibraryDir = File(app.applicationInfo.nativeLibraryDir)
        initialized = true
    }

    fun ensureDirs() {
        listOf(usrDir, binDir, tmpDir, homeDir, runtimeAssets, scriptsDir, logsDir).forEach {
            if (!it.exists() && !it.mkdirs()) {
                throw IllegalStateException("无法创建目录：${it.absolutePath}")
            }
        }
    }

    /** 默认工作区：应用专属外部目录，免权限即可被 proot 绑定 */
    val defaultWorkspaceDir: File
        get() = File(appContext.getExternalFilesDir(null) ?: baseDir, "workspace").apply { mkdirs() }

    /** 设备公共存储根，仅在授权 MANAGE_EXTERNAL_STORAGE 后可用 */
    val publicStorageDir: File
        get() = File("/storage/emulated/0")

    val codeServerDir: File
        get() = File(rootfsDir, "opt/code-server-${RuntimeConfig.codeServerVersion}-linux-arm64")

    val prepareScript: File
        get() = File(scriptsDir, "prepare.sh")

    val runScript: File
        get() = File(scriptsDir, "run.sh")

    val hardLinkMapFile: File
        get() = File(runtimeAssets, "hardlinks.txt")

    val bootstrapMarker: File
        get() = File(baseDir, ".bootstrap-${RuntimeConfig.runtimeVersion}-${RuntimeConfig.codeServerVersion}.done")

    val userSettingsFile: File
        get() = File(rootfsDir, "root/.local/share/code-server/User/settings.json")

    val codeServerConfigFile: File
        get() = File(rootfsDir, "root/.config/code-server/config.yaml")

    val productJsonFile: File
        get() = File(codeServerDir, "lib/vscode/product.json")
}
