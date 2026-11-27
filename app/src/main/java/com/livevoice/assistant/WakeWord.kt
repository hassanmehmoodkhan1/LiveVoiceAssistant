package com.livevoice.assistant

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

/** Always-on, offline wake-word detection ("Kali") using Vosk. */
class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit,
) : RecognitionListener {

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var lastWakeTime = 0L

    fun start(accessKey: String = "", keywordPath: String = "") {
        Thread {
            try {
                val modelDir = File(context.filesDir, "model-en")
                if (!File(modelDir, "conf").exists()) {
                    copyAssetFolder("model-en", modelDir)
                }
                val loadedModel = Model(modelDir.absolutePath)
                model = loadedModel

                // Restrict grammar to "kali" for fast, accurate wake-word detection
                val recognizer = Recognizer(loadedModel, 16000.0f, "[\"kali\", \"hey kali\", \"[unk]\"]")

                speechService = SpeechService(recognizer, 16000.0f).apply {
                    startListening(this@WakeWord)
                }
            } catch (e: Exception) {
                onError("Vosk wake word failed: ${e.message}")
            }
        }.start()
    }

    override fun onPartialResult(hypothesis: String?) {}


    override fun onResult(hypothesis: String?) {
        checkForWakeWord(hypothesis, "text")
    }

    override fun onFinalResult(hypothesis: String?) {
        checkForWakeWord(hypothesis, "text")
    }

    override fun onError(e: Exception?) {
        onError("Mic listener error: ${e?.message}")
    }

    override fun onTimeout() {}

    private fun checkForWakeWord(jsonStr: String?, key: String) {
        if (jsonStr.isNullOrBlank()) return
        val spoken = try {
            JSONObject(jsonStr).optString(key, "").trim().lowercase()
        } catch (_: Exception) {
            ""
        }
        if (spoken == "kali" || spoken == "hey kali") {

            val now = System.currentTimeMillis()
            if (now - lastWakeTime > 3000L) {
                lastWakeTime = now
                onWake()
            }
        }
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assets = context.assets.list(assetPath) ?: return
        if (assets.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            targetDir.mkdirs()
            for (child in assets) {
                copyAssetFolder("$assetPath/$child", File(targetDir, child))
            }
        }
    }

    fun stop() {
        try {
            speechService?.stop()
            speechService?.shutdown()
            model?.close()
        } catch (_: Exception) {
        }
        speechService = null
        model = null
    }
}
