package com.livevoice.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs in the background (even with the screen off).
 * Waiting: listens offline for "Kali". Live: a Gemini voice call. Then back to waiting.
 */
class KaliService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: SettingsStore
    private var wake: WakeWord? = null
    private var session: GeminiLiveSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            KaliState.status.value = KaliStatus(Phase.Off, "Android blocked background listening: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (wake != null || session != null) return START_STICKY

        store = SettingsStore(this)
        if (store.geminiKey.isBlank()) {
            return fail("Add your Gemini API key in Settings.")
        }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kali:listen").apply { acquire() }
        listenForWakeWord(null)
        return START_STICKY
    }

    private fun listenForWakeWord(note: String?) {
        setPhase(Phase.Waiting, note)
        val w = WakeWord(this,
            onWake = { scope.launch { onWake() } },
            onError = { msg -> scope.launch { fail(msg) } })
        wake = w
        w.start(store.picoKey, store.keywordFile(this).absolutePath)
    }

    private fun onWake() {
        if (session != null) return
        wake?.stop(); wake = null // free the microphone for the call
        setPhase(Phase.Connecting, null)

        // Automatically bring the app screen up when "Kali" is heard
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try { startActivity(openIntent) } catch (_: Exception) {}

        session = GeminiLiveSession(
            this, store.geminiKey, store.model, store.voice,
            onReady = { scope.launch { setPhase(Phase.Live, null) } },
            onEnded = { reason ->
                scope.launch {
                    session = null
                    listenForWakeWord(reason?.let { "Last call ended: $it" })
                    // Automatically close the app screen when "Goodbye Kali" ends the call
                    sendBroadcast(Intent("com.livevoice.assistant.CLOSE_UI").setPackage(packageName))
                }
            },
        ).also { it.start() }
    }

    private fun fail(msg: String): Int {
        KaliState.status.value = KaliStatus(Phase.Off, msg)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun setPhase(p: Phase, note: String?) {
        KaliState.status.value = KaliStatus(p, note)
        val text = when (p) {
            Phase.Waiting -> "Say “Kali” to start talking"
            Phase.Connecting -> "Connecting…"
            Phase.Live -> "Live conversation"
            Phase.Off -> "Off"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kali")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }


    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Kali listening", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.stop(); wake = null
        session?.stop(); session = null
        wakeLock?.let { if (it.isHeld) it.release() }
        if (KaliState.status.value.note == null || KaliState.status.value.phase != Phase.Off)
            KaliState.status.value = KaliStatus(Phase.Off, KaliState.status.value.note.takeIf { KaliState.status.value.phase == Phase.Off })
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.livevoice.assistant.STOP"
        private const val CHANNEL = "kali"
        private const val NOTIF_ID = 1
    }
}
