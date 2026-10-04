package com.novelreader.reader

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.max

/**
 * Reproduce PCM remoto sin crear archivos temporales. La cola contiene solo
 * texto pendiente; cada bloque binario se escribe inmediatamente en AudioTrack.
 */
class RemoteTtsPlaybackManager(
    private val url: String,
    private val token: String = "",
    // Mantiene el segmento actual y uno adelantado sin multiplicar la carga
    // de inferencia ni acumular demasiados audios en memoria.
    private val prefetch: Int = 2
) {
    interface Listener {
        fun onStatus(status: String)
        fun onSegmentStarted(segmentId: Int)
        fun onSegmentFinished(segmentId: Int)
        fun onUtteranceStarted(utteranceKey: String)
        /**
         * Se dispara cuando el audio ya recibido llega al último segmento
         * conocido. La pantalla debe pedir el siguiente párrafo aquí, mientras
         * el actual todavía puede seguir reproduciéndose.
         */
        fun onNeedsNextUtterance()
        fun onFinished()
        fun onError(message: String)
    }

    private data class Segment(val id: Int, val text: String, val utteranceKey: String)

    /** Audio recibido completo y listo para reproducirse en orden. */
    private class AudioBuffer(val id: Int, val sampleRate: Int) {
        val pcm = ByteArrayOutputStream()
        val completed = CountDownLatch(1)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var listener: Listener? = null
    private var sessionId = ""
    private val segments = mutableListOf<Segment>()
    private var nextSegmentId = 0
    private var nextToSend = 0
    private var completed = 0
    private var audioTrack: AudioTrack? = null
    private val audioBuffers = mutableMapOf<Int, AudioBuffer>()
    private var incomingSegmentId = -1
    private var nextSegmentToPlay = 0
    private var playbackThread: Thread? = null
    private var playing = false
    private var shuttingDown = false
    private var inputFinished = false
    private var nextUtteranceRequested = false
    private val startedUtterances = mutableSetOf<String>()
    private var connectionGeneration = 0L

    @Synchronized
    fun start(text: String, speed: Float, voice: String, utteranceKey: String, listener: Listener) {
        // Invalida primero la conexión anterior. Su callback de cierre puede
        // llegar después de abrir la siguiente y no debe detenerla.
        val generation = ++connectionGeneration
        stopInternal(sendCancel = true, closeSocket = true)
        this.listener = listener
        this.sessionId = UUID.randomUUID().toString()
        this.segments.clear()
        this.nextSegmentId = 0
        appendSegmentsLocked(text, utteranceKey)
        this.nextToSend = 0
        this.completed = 0
        this.audioBuffers.clear()
        this.incomingSegmentId = -1
        this.nextSegmentToPlay = 0
        this.playing = true
        this.shuttingDown = false
        this.inputFinished = false
        this.nextUtteranceRequested = false
        this.startedUtterances.clear()
        if (segments.isEmpty()) {
            notifyFinished()
            return
        }
        playbackThread = Thread({ playbackLoop(generation) }, "novelreader-tts-playback").also { it.start() }
        notifyStatus("Conectando al servidor TTS…")
        val request = Request.Builder().url(url).build()
        val socketHandler = SocketListener(speed, voice, generation)
        socketListener = socketHandler
        socket = client.newWebSocket(request, socketHandler)
    }

    /**
     * Añade otro párrafo a la sesión WebSocket actual. Antes cada párrafo
     * llamaba a start(), cerraba el socket y volvía a negociar la conexión;
     * ese ciclo era la pausa audible entre párrafos.
     */
    @Synchronized
    fun append(text: String, utteranceKey: String) {
        if (!playing || shuttingDown) return
        val previousSize = segments.size
        appendSegmentsLocked(text, utteranceKey)
        if (segments.size == previousSize) return
        nextUtteranceRequested = false
        socket?.let { ws -> socketListener?.sendMore(ws, prefetch) }
    }

    /** Indica que no habrá más párrafos en esta reproducción. */
    @Synchronized
    fun finishInput() {
        inputFinished = true
        nextUtteranceRequested = false
    }

    @Synchronized
    fun isRunning(): Boolean = playing && !shuttingDown

    private fun appendSegmentsLocked(text: String, utteranceKey: String) {
        SpeechChunker.split(text).forEach { value ->
            segments += Segment(nextSegmentId++, value, utteranceKey)
        }
    }

    @Synchronized
    fun pause() {
        if (!playing) return
        playing = false
        sendCancel()
        stopTrack()
        notifyStatus("En pausa")
    }

    @Synchronized
    fun stop() {
        connectionGeneration++
        stopInternal(sendCancel = true, closeSocket = true)
        notifyStatus("Detenido")
    }

    fun close() {
        synchronized(this) { connectionGeneration++ }
        stopInternal(sendCancel = true, closeSocket = true)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Synchronized
    private fun stopInternal(sendCancel: Boolean, closeSocket: Boolean) {
        if (sendCancel) sendCancel()
        playing = false
        playbackThread?.interrupt()
        playbackThread = null
        audioBuffers.values.forEach { it.completed.countDown() }
        audioBuffers.clear()
        segments.clear()
        startedUtterances.clear()
        nextSegmentId = 0
        nextToSend = 0
        inputFinished = false
        nextUtteranceRequested = false
        incomingSegmentId = -1
        stopTrack()
        if (closeSocket) socket?.close(1000, "replaced")
        socket = null
        socketListener = null
        shuttingDown = true
    }

    private fun sendCancel() {
        val ws = socket ?: return
        if (sessionId.isBlank()) return
        ws.send(JSONObject().apply {
            put("type", "cancel")
            put("sessionId", sessionId)
        }.toString())
    }

    private inner class SocketListener(
        private val speed: Float,
        private val voice: String,
        private val generation: Long
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RemoteTtsPlaybackManager) {
                if (!isCurrent(webSocket) || shuttingDown || !playing) return
                if (token.isNotBlank()) {
                    webSocket.send(JSONObject().apply {
                        put("type", "auth")
                        put("token", token)
                    }.toString())
                } else {
                    sendMore(webSocket)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
            val type = payload.optString("type")
            synchronized(this@RemoteTtsPlaybackManager) {
                if (!isCurrent(webSocket) || (!playing && type != "auth_result")) return
                when (type) {
                    "auth_result" -> {
                        if (!payload.optBoolean("ok", false)) notifyError("El servidor rechazó el token")
                        else sendMore(webSocket)
                    }
                    "audio_start" -> registerAudioStart(payload)
                    "audio_end" -> registerAudioEnd(payload)
                    "backpressure" -> notifyStatus("Servidor ocupado; esperando…")
                    "cancelled" -> Unit
                    "error" -> notifyError(payload.optString("message", "Error TTS remoto"))
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(this@RemoteTtsPlaybackManager) {
                if (!isCurrent(webSocket) || !playing || incomingSegmentId < 0) return
                val buffer = audioBuffers[incomingSegmentId] ?: return
                buffer.pcm.write(bytes.toByteArray())
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@RemoteTtsPlaybackManager) {
                if (isCurrent(webSocket) && !shuttingDown && playing) {
                    notifyError(t.message ?: "No se pudo conectar al TTS remoto")
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@RemoteTtsPlaybackManager) {
                if (isCurrent(webSocket) && playing && !shuttingDown) {
                    val detail = reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                    notifyError("Conexión TTS cerrada ($code)$detail")
                }
            }
        }

        fun sendMore(webSocket: WebSocket, limit: Int = prefetch) {
            var sent = 0
            while (nextToSend < segments.size && sent < limit) {
                val segment = segments[nextToSend++]
                webSocket.send(JSONObject().apply {
                    put("type", "tts_request")
                    put("sessionId", sessionId)
                    put("segmentId", segment.id)
                    put("text", segment.text)
                    put("voice", voice.ifBlank { "M5" })
                    put("speed", speed.coerceIn(.5f, 2f).toDouble())
                }.toString())
                sent++
            }
        }
        private fun isCurrent(webSocket: WebSocket): Boolean {
            return generation == connectionGeneration && socket === webSocket
        }
    }

    private fun registerAudioStart(payload: JSONObject) {
        val id = payload.optInt("segmentId", -1)
        if (id < 0) return
        val sampleRate = payload.optInt("sampleRate", 24_000).coerceAtLeast(8_000)
        audioBuffers[id] = AudioBuffer(id, sampleRate)
        incomingSegmentId = id
    }

    private fun registerAudioEnd(payload: JSONObject) {
        val id = payload.optInt("segmentId", incomingSegmentId)
        audioBuffers[id]?.completed?.countDown()
        requestNextUtteranceIfNeeded(id)
    }

    private fun requestNextUtteranceIfNeeded(id: Int) {
        if (inputFinished || nextUtteranceRequested) return
        if (segments.lastOrNull()?.id != id) return
        nextUtteranceRequested = true
        main.post { listener?.onNeedsNextUtterance() }
    }

    /**
     * Reproduce con doble búfer: espera a recibir por completo cada segmento,
     * lo escribe en un único AudioTrack y, mientras ese segmento suena, el
     * servidor puede generar el siguiente.
     */
    private fun playbackLoop(generation: Long) {
        var track: AudioTrack? = null
        var queuedFrames = 0L
        try {
            var playIndex = 0
            while (isPlaybackActive(generation)) {
                val segment = synchronized(this) {
                    segments.getOrNull(playIndex)
                }
                if (segment == null) {
                    val canFinish = synchronized(this) {
                        inputFinished && playIndex >= segments.size
                    }
                    if (canFinish) break
                    Thread.sleep(30L)
                    continue
                }
                val buffer = awaitBuffer(segment.id, generation) ?: return
                if (!isPlaybackActive(generation)) return

                if (track == null) {
                    track = createAudioTrack(buffer.sampleRate)
                    audioTrack = track
                    track.play()
                    notifyStatus("Leyendo")
                }
                notifyStarted(segment.id)
                val utteranceStarted = synchronized(this) {
                    segment.utteranceKey.isNotBlank() && startedUtterances.add(segment.utteranceKey)
                }
                if (utteranceStarted) notifyUtteranceStarted(segment.utteranceKey)

                val pcm = buffer.pcm.toByteArray()
                var offset = 0
                while (offset < pcm.size && isPlaybackActive(generation)) {
                    val count = min(64 * 1024, pcm.size - offset)
                    val written = track.write(pcm, offset, count, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) error("AudioTrack no pudo reproducir el segmento ${segment.id}")
                    offset += written
                    queuedFrames += written / 2L
                }
                if (!isPlaybackActive(generation)) return

                synchronized(this) {
                    completed++
                    socket?.let { ws -> socketListener?.sendMore(ws, 1) }
                }
                notifyFinishedSegment(segment.id)
                synchronized(this) { audioBuffers.remove(segment.id) }
                playIndex++
            }

            val finalTrack = track ?: return
            while (isPlaybackActive(generation) && finalTrack.playbackHeadPosition.toLong() < queuedFrames) {
                Thread.sleep(20L)
            }
            val shouldNotify = synchronized(this) {
                if (generation == connectionGeneration && playing) {
                    playing = false
                    true
                } else false
            }
            if (shouldNotify) notifyFinished()
        } catch (_: InterruptedException) {
            // Pausa, detención o sustitución de la conexión.
        } catch (error: Throwable) {
            if (isPlaybackActive(generation)) notifyError(error.message ?: "No se pudo reproducir el audio remoto")
        } finally {
            synchronized(this) {
                if (playbackThread === Thread.currentThread()) playbackThread = null
            }
            if (synchronized(this) { generation == connectionGeneration }) stopTrack()
        }
    }

    private fun awaitBuffer(id: Int, generation: Long): AudioBuffer? {
        while (isPlaybackActive(generation)) {
            val buffer = synchronized(this) { audioBuffers[id] }
            if (buffer != null) {
                buffer.completed.await(100L, TimeUnit.MILLISECONDS)
                if (buffer.completed.count == 0L) return buffer
            } else {
                Thread.sleep(20L)
            }
        }
        return null
    }

    private fun isPlaybackActive(generation: Long): Boolean = synchronized(this) {
        generation == connectionGeneration && playing && !shuttingDown
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = max(minBuffer, sampleRate / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // Se conserva la referencia al listener actual para alimentar la ventana
    // de prefetch después de cada audio_end.
    private var socketListener: SocketListener? = null

    private fun notifyStatus(value: String) = main.post { listener?.onStatus(value) }
    private fun notifyStarted(id: Int) = main.post { listener?.onSegmentStarted(id) }
    private fun notifyFinishedSegment(id: Int) = main.post { listener?.onSegmentFinished(id) }
    private fun notifyUtteranceStarted(key: String) = main.post { listener?.onUtteranceStarted(key) }
    private fun notifyFinished() = main.post { listener?.onFinished() }
    private fun notifyError(value: String) {
        Log.e("RemoteTts", value)
        playing = false
        stopTrack()
        main.post { listener?.onError(value) }
    }

    @Synchronized
    private fun stopTrack() {
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        audioTrack = null
    }
}
