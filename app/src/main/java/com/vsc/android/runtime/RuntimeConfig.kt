package com.vsc.android.runtime

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 运行时可调参数。落盘在 SharedPreferences，改动后需要重启服务生效。
 */
object RuntimeConfig {

    private const val PREF_NAME = "runtime_config"
    private const val KEY_PORT = "port"
    private const val KEY_AUTH = "auth_mode"
    private const val KEY_MARKET = "market"
    private const val KEY_EXTERNAL = "external_storage"
    private const val KEY_CS_VERSION = "code_server_version"
    private const val KEY_PASSWORD = "code_server_password"

    const val DEFAULT_PORT = 20000
    const val DEFAULT_CS_VERSION = "4.143.1"
    const val RUNTIME_VERSION = "2"

    enum class AuthMode { NONE, PASSWORD }

    enum class Market { OPEN_VSX, MICROSOFT }

    var port: Int = DEFAULT_PORT
        private set
    var authMode: AuthMode = AuthMode.NONE
        private set
    var market: Market = Market.OPEN_VSX
        private set
    var externalStorageEnabled: Boolean = true
        private set
    var codeServerVersion: String = DEFAULT_CS_VERSION
        private set
    var password: String = ""
        private set

    val runtimeVersion: String get() = RUNTIME_VERSION
    val codeServerPackageName: String get() = "code-server-$codeServerVersion-linux-arm64"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        SecureStore.init(app)
        reload()
    }

    fun reload() {
        val p = prefs ?: return
        port = p.getInt(KEY_PORT, DEFAULT_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        authMode = runCatching { AuthMode.valueOf(p.getString(KEY_AUTH, AuthMode.NONE.name)!!) }
            .getOrDefault(AuthMode.NONE)
        market = runCatching { Market.valueOf(p.getString(KEY_MARKET, Market.OPEN_VSX.name)!!) }
            .getOrDefault(Market.OPEN_VSX)
        externalStorageEnabled = p.getBoolean(KEY_EXTERNAL, true)
        codeServerVersion = p.getString(KEY_CS_VERSION, DEFAULT_CS_VERSION) ?: DEFAULT_CS_VERSION
        password = SecureStore.get(KEY_PASSWORD) ?: ""
        if (authMode == AuthMode.PASSWORD && password.isBlank()) {
            password = generatePassword()
            SecureStore.put(KEY_PASSWORD, password)
        }
    }

    fun setPort(value: Int) {
        port = value.coerceIn(1024, 65535)
        prefs?.edit()?.putInt(KEY_PORT, port)?.apply()
    }

    fun setAuthMode(mode: AuthMode) {
        authMode = mode
        if (mode == AuthMode.PASSWORD && password.isBlank()) {
            password = generatePassword()
            SecureStore.put(KEY_PASSWORD, password)
        }
        prefs?.edit()?.putString(KEY_AUTH, mode.name)?.apply()
    }

    fun setMarket(value: Market) {
        market = value
        prefs?.edit()?.putString(KEY_MARKET, value.name)?.apply()
    }

    fun setExternalStorageEnabled(enabled: Boolean) {
        externalStorageEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_EXTERNAL, enabled)?.apply()
    }

    fun regeneratePassword(): String {
        password = generatePassword()
        SecureStore.put(KEY_PASSWORD, password)
        return password
    }

    private fun generatePassword(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }
}
