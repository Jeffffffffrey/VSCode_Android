package com.vsc.android.runtime

import java.net.InetSocketAddress
import java.net.Socket

object PortProbe {

    fun isServing(port: Int, timeoutMs: Int = 400): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    fun isPortFree(port: Int): Boolean = !isServing(port, 200)
}
