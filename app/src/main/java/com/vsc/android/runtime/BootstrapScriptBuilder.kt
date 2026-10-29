package com.vsc.android.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * 准备 rootfs 运行条件、写入编辑器配置、生成 proot 启动脚本。
 *
 * 解包工作已由 [TarExtractor] 在 Kotlin 侧完成，这里只做文本类配置，
 * 避免依赖 rootfs 内的 sed / cp 等工具。
 */
class BootstrapScriptBuilder(private val context: Context) {

    companion object {
        private const val TAG = "BootstrapScriptBuilder"

        /** 用 http 而非 https：ubuntu-base 默认未装 ca-certificates，https 源在 rootfs 内会验签失败 */
        private const val APT_MIRROR = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu"

        private const val OPEN_VSX_GALLERY = "https://open-vsx.org/vscode/gallery"
        private const val OPEN_VSX_ITEM = "https://open-vsx.org/vscode/item"
        private const val OPEN_VSX_RESOURCE =
            "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}"

        private const val MS_GALLERY = "https://marketplace.visualstudio.com/_apis/public/gallery"
        private const val MS_ITEM = "https://marketplace.visualstudio.com/items"
        private const val MS_RESOURCE =
            "https://{publisher}.vscode-unpkg.net/{publisher}/{name}/{version}/{path}"
    }

    fun buildRunScript(): File {
        val template = context.assets.open("scripts/run.sh.tpl").bufferedReader().use { it.readText() }
        var text = template
        placeholders().forEach { (key, value) -> text = text.replace("{{$key}}", value) }
        RuntimePaths.scriptsDir.mkdirs()
        val target = RuntimePaths.runScript
        target.writeText(text)
        target.setExecutable(true, false)
        Log.i(TAG, "生成启动脚本 ${target.absolutePath}")
        return target
    }

    // ------------------------------------------------------------------ rootfs

    /**
     * rootfs 解包后的必要修补：目录、apt 源、DNS。
     */
    fun applyRootfsConfig() {
        val rootfs = RuntimePaths.rootfsDir
        listOf("tmp", "opt", "root/.config", "root/.local/share", "dev", "proc", "sys").forEach {
            File(rootfs, it).mkdirs()
        }

        var rewrites = 0
        val sourcesCandidates = listOf(
            "etc/apt/sources.list",
            "etc/apt/sources.list.d/ubuntu.sources",
        )
        sourcesCandidates.forEach { relative ->
            val file = File(rootfs, relative)
            if (!file.isFile) return@forEach
            val original = file.readText()
            val patched = original.replace(
                Regex("https?://(archive|security|ports)\\.ubuntu\\.com/ubuntu(-ports)?"),
                APT_MIRROR,
            )
            if (patched != original) {
                file.writeText(patched)
                rewrites++
            }
        }
        Log.i(TAG, "apt 源已切换到 $APT_MIRROR（$rewrites 个文件）")

        // proot 内 systemd-resolved 不可用；先删符号链接再写实体文件
        val resolv = File(rootfs, "etc/resolv.conf").toPath()
        runCatching { Files.deleteIfExists(resolv) }
        File(rootfs, "etc/resolv.conf").writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
    }

    // ------------------------------------------------------------------ editor

    fun applyEditorConfig() {
        writeCodeServerConfig()
        writeUserSettings()
        patchProductJson()
    }

    private fun writeCodeServerConfig() {
        val file = RuntimePaths.codeServerConfigFile
        file.parentFile?.mkdirs()
        val auth = if (RuntimeConfig.authMode == RuntimeConfig.AuthMode.PASSWORD) {
            "auth: password\npassword: ${RuntimeConfig.password}"
        } else {
            "auth: none"
        }
        // 只绑定回环地址：避免同一 Wi-Fi 下他人无密码访问
        file.writeText(
            "bind-addr: 127.0.0.1:${RuntimeConfig.port}\n$auth\ncert: false\n",
        )
        Log.i(TAG, "写入 config.yaml，端口 ${RuntimeConfig.port}")
    }

    private fun writeUserSettings() {
        val file = RuntimePaths.userSettingsFile
        if (file.exists() && file.length() > 2) {
            Log.i(TAG, "用户设置已存在，保留")
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {
              "telemetry.telemetryLevel": "off",
              "update.mode": "none",
              "extensions.autoCheckUpdates": false,
              "extensions.autoUpdate": false,
              "security.workspace.trust.enabled": false,
              "workbench.startupEditor": "none",
              "workbench.enableExperiments": false,
              "typescript.tsserver.maxTsServerMemory": 1024,
              "editor.fontSize": 14,
              "editor.minimap.enabled": false,
              "files.watcherExclude": {
                "**\/node_modules/**": true,
                "**\/.git/objects/**": true
              }
            }
            """.trimIndent() + "\n",
        )
        Log.i(TAG, "写入默认用户设置")
    }

    /**
     * 扩展市场补丁：默认指向 open-vsx（合规），可选切回 Microsoft 市场。
     * 首次修改前备份 product.json.orig。
     */
    private fun patchProductJson() {
        val file = RuntimePaths.productJsonFile
        if (!file.exists()) {
            Log.w(TAG, "未找到 product.json：${file.absolutePath}")
            return
        }
        val backup = File(file.parentFile, "product.json.orig")
        if (!backup.exists()) file.copyTo(backup, overwrite = true)

        var patched = backup.readText()
        val (gallery, item, resource) = when (RuntimeConfig.market) {
            RuntimeConfig.Market.MICROSOFT -> Triple(MS_GALLERY, MS_ITEM, MS_RESOURCE)
            RuntimeConfig.Market.OPEN_VSX -> Triple(OPEN_VSX_GALLERY, OPEN_VSX_ITEM, OPEN_VSX_RESOURCE)
        }

        patched = patched
            .replace(OPEN_VSX_GALLERY, gallery)
            .replace(OPEN_VSX_ITEM, item)
            .replace(OPEN_VSX_RESOURCE, resource)

        if (RuntimeConfig.market == RuntimeConfig.Market.OPEN_VSX) {
            patched = patched
                .replace(MS_GALLERY, OPEN_VSX_GALLERY)
                .replace(MS_ITEM, OPEN_VSX_ITEM)
                .replace(MS_RESOURCE, OPEN_VSX_RESOURCE)
        }

        patched = patched.replace(Regex("\"enableTelemetry\"\\s*:\\s*true"), "\"enableTelemetry\": false")

        file.writeText(patched)
        Log.i(TAG, "扩展市场：${RuntimeConfig.market}")
    }

    // --------------------------------------------------------------- placeholders

    private fun placeholders(): Map<String, String> {
        val extraBinds = buildString {
            append("--bind=\"${RuntimePaths.defaultWorkspaceDir.absolutePath}:/root/workspace\"")
            if (RuntimeConfig.externalStorageEnabled && RuntimePaths.publicStorageDir.canRead()) {
                append(" --bind=\"/storage/emulated/0:/sdcard\"")
            }
        }
        val authArgs = when (RuntimeConfig.authMode) {
            RuntimeConfig.AuthMode.PASSWORD -> "--auth password"
            RuntimeConfig.AuthMode.NONE -> "--auth none"
        }
        return mapOf(
            "PREFIX" to RuntimePaths.usrDir.absolutePath,
            "BIN" to RuntimePaths.binDir.absolutePath,
            "TMP" to RuntimePaths.tmpDir.absolutePath,
            "HOME" to RuntimePaths.homeDir.absolutePath,
            "ROOTFS" to RuntimePaths.rootfsDir.absolutePath,
            // 注意：进入 proot 后 rootfs 被重映射为 /，必须用 guest 路径
            "CS_GUEST_PATH" to "/opt/${RuntimeConfig.codeServerPackageName}",
            "PORT" to RuntimeConfig.port.toString(),
            "AUTH_ARGS" to authArgs,
            "EXTRA_BINDS" to extraBinds,
        )
    }
}
