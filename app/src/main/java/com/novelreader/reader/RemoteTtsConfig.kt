package com.novelreader.reader

import com.novelreader.BuildConfig
import java.net.URI

/** Configuración compilada desde local.properties; no contiene secretos en el repositorio. */
object RemoteTtsConfig {
    val websocketUrl: String
        get() = BuildConfig.REMOTE_TTS_URL.trim()

    val token: String
        get() = BuildConfig.REMOTE_TTS_TOKEN

    val enabled: Boolean
        get() = websocketUrl.isNotBlank()

    /**
     * Acepta una IP, una URL HTTP(S) o una URL WebSocket y devuelve una
     * dirección lista para el cliente TTS.
     *
     * Ejemplo: 192.168.0.40 -> ws://192.168.0.40:8765/ws/tts
     */
    fun normalizeWebsocketUrl(input: String): String {
        var value = input.trim()
        if (value.isBlank()) return ""
        value = when {
            value.startsWith("http://", ignoreCase = true) -> "ws://${value.substring(7)}"
            value.startsWith("https://", ignoreCase = true) -> "wss://${value.substring(8)}"
            value.startsWith("ws://", ignoreCase = true) || value.startsWith("wss://", ignoreCase = true) -> value
            else -> "ws://$value"
        }
        return runCatching {
            val parsed = URI(value)
            val host = parsed.host ?: return@runCatching value
            val port = if (parsed.port == -1) {
                if (parsed.scheme.equals("wss", ignoreCase = true)) 443 else 8765
            } else parsed.port
            val path = parsed.path.takeUnless { it.isNullOrBlank() || it == "/" } ?: "/ws/tts"
            URI(parsed.scheme, parsed.userInfo, host, port, path, parsed.query, null).toString()
        }.getOrDefault(value)
    }
}
