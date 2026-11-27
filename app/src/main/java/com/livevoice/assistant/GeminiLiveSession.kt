package com.livevoice.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File




/**
 * One live, audio-only conversation with Gemini (like a phone call).
 * Listens directly for "goodbye" in your speech transcript so it hangs up reliably.
 */
class GeminiLiveSession(
    private val context: Context,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val onReady: () -> Unit,
    private val onEnded: (reason: String?) -> Unit, // null = normal end
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val playQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val effects = mutableListOf<AudioEffect>()
    private val speechBuffer = StringBuilder()

    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var localModel: Model? = null
    private var localRecognizer: Recognizer? = null

    private var oldMode = AudioManager.MODE_NORMAL
    private val ended = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var endAfterTurn = false
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()

    fun start() {
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = true

        track = buildTrack().also { it.play() }
        scope.launch { for (chunk in playQueue) track?.write(chunk, 0, chunk.size) }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (SystemClock.elapsedRealtime() - lastActivity > IDLE_MS) { end(null); break }
            }
        }
        ws = http.newWebSocket(Request.Builder().url(URL + apiKey.trim()).build(), listener)
    }

    fun stop() = end(null)

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupJson()) }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            end(if (code == 1000) null else "Closed ($code) $reason")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(response?.let { "HTTP ${it.code} (check your Gemini key and model)" } ?: t.message ?: "Connection failed")
        }
    }

    private fun setupJson(): String {
        val endTool = JSONObject()
            .put("name", "end_conversation")
            .put("description", "Call this when the user says goodbye or wants to end the call.")
        return JSONObject().put("setup", JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)))))
            .put("inputAudioTranscription", JSONObject())
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(endTool))))
        ).toString()
    }

    private fun handle(raw: String) {
        val o = try { JSONObject(raw) } catch (_: Exception) { return }

        if (o.has("setupComplete")) {
            ready = true
            lastActivity = SystemClock.elapsedRealtime()
            try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).startTone(ToneGenerator.TONE_PROP_ACK, 150) } catch (_: Exception) {}
            startCapture()
            onReady()
            return
        }

        o.optJSONObject("serverContent")?.let { sc ->
            if (sc.optBoolean("interrupted")) flushPlayback()

            // Immediately shut down Live Mode if the user says "goodbye" or "bye kali"
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { spoken ->
                if (spoken.isNotBlank()) {
                    speechBuffer.append(spoken.lowercase())
                    if (speechBuffer.length > 120) {
                        speechBuffer.delete(0, speechBuffer.length - 120)
                    }
                    val recent = speechBuffer.toString()
                    if (recent.contains("goodbye") || recent.contains("good bye") || recent.contains("bye kali")) {
                        scope.launch {
                            delay(500)
                            end(null)
                        }
                        return
                    }
                }
            }

            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                    playQueue.trySend(Base64.decode(inline.getString("data"), Base64.DEFAULT))
                    lastActivity = SystemClock.elapsedRealtime()
                }
            }
            if (sc.optBoolean("turnComplete") && endAfterTurn) scope.launch { delay(500); end(null) }
        }

        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                if (c.optString("name") == "end_conversation") {
                    endAfterTurn = true
                    scope.launch { delay(500); end(null) }
                }
                responses.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("name"))
                    .put("response", JSONObject().put("result", "ok")))
            }
            ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
        }

        if (o.has("goAway")) end(null)
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { end("Couldn’t open the microphone."); return }
        recorder = rec
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
        rec.startRecording()

        scope.launch {
            try {
                val modelDir = File(context.filesDir, "model-en")
                if (File(modelDir, "conf").exists()) {
                    val m = Model(modelDir.absolutePath)
                    localModel = m
                    localRecognizer = Recognizer(m, IN_RATE.toFloat(), "[\"goodbye\", \"good bye\",  \"[unk]\"]")
                }
            } catch (_: Exception) {}

            val samples = ShortArray(800) // 50 ms
            try {
                while (isActive && !ended.get()) {
                    val n = rec.read(samples, 0, samples.size)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
                    if (sqrt(sum / n) > VOICE_RMS) lastActivity = SystemClock.elapsedRealtime()

                    val bytes = ByteArray(n * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, n)
                    localRecognizer?.let { vosk ->
                        if (vosk.acceptWaveForm(bytes, bytes.size)) {
                            val json = vosk.result
                            if (json.contains("goodbye") || json.contains("good bye")) {
                                end(null)
                                return@launch
                            }
                        }

                }


                    val socket = ws ?: continue
                    if (socket.queueSize() > 1_000_000) continue
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val msg = JSONObject().put("realtimeInput", JSONObject().put("mediaChunks",
                        JSONArray().put(JSONObject().put("mimeType", "audio/pcm;rate=$IN_RATE").put("data", b64))))
                    socket.send(msg.toString())
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(min, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun flushPlayback() {
        while (playQueue.tryReceive().isSuccess) {}
        try {
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {}
    }

    private fun end(reason: String?) {
        if (!ended.compareAndSet(false, true)) return
        try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).startTone(ToneGenerator.TONE_PROP_BEEP2, 150) } catch (_: Exception) {}
        try { ws?.close(1000, "done") } catch (_: Exception) {}
        ws = null
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { localRecognizer?.close(); localModel?.close() } catch (_: Exception) {}
        localRecognizer = null
        localModel = null

        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        playQueue.close()
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        track = null
        try {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
            audio.mode = oldMode
        } catch (_: Exception) {}
        scope.cancel()
        onEnded(reason)
    }

    companion object {
        private const val URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
        private const val IN_RATE = 16000
        private const val OUT_RATE = 24000
        private const val IDLE_MS = 45_000L
        private const val VOICE_RMS = 200.0
        private const val SYSTEM = "You are Kali, a helpful, concise, friendly voice assistant on a live phone call. Keep replies natural and conversational."
    }
}
