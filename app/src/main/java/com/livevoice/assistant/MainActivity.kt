package com.livevoice.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val closeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= 27) {

        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }


        ContextCompat.registerReceiver(
            this, closeReceiver, android.content.IntentFilter("com.livevoice.assistant.CLOSE_UI"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4FF),
                    background = Color(0xFF0B1020),
                    surface = Color(0xFF141B33),
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainScreen() }
            }
        }
    }
}

private val Muted = Color(0xFF9AA7CC)

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val store = remember { SettingsStore(ctx) }
    val status by KaliState.status.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val setupDone = remember(tick) {
        store.geminiKey.isNotBlank()
    }

    val running = status.phase != Phase.Off

    fun startKali() = ContextCompat.startForegroundService(ctx, Intent(ctx, KaliService::class.java))
    fun stopKali() { ctx.startService(Intent(ctx, KaliService::class.java).setAction(KaliService.ACTION_STOP)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->

        if (granted[Manifest.permission.RECORD_AUDIO] == true) startKali()
        else KaliState.status.value = KaliStatus(Phase.Off, "Microphone permission is required.")
    }
    LaunchedEffect(setupDone) {
        if (setupDone && !running) {
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissions.launch(needed.toTypedArray())
        }
    }

    fun toggle() {
        if (running) { stopKali(); return }
        if (!setupDone) { showSettings = true; return }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(needed.toTypedArray())
    }
    LaunchedEffect(setupDone) {
        if (setupDone && !running) {
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissions.launch(needed.toTypedArray())
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kali", style = MaterialTheme.typography.headlineLarge)
            Text("Your hands-free voice assistant", color = Muted)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(status.phase, ::toggle)
            Text(
                when (status.phase) {
                    Phase.Off -> "Kali is off"
                    Phase.Waiting -> "Listening for “Kali”…"
                    Phase.Connecting -> "Connecting…"
                    Phase.Live -> "Live. Go ahead and talk."
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            status.note?.let {
                Text(it, color = Color(0xFFFFB4A9), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!setupDone) {
                Text("Add your Gemini API key to activate Kali.", color = Muted, textAlign = TextAlign.Center)
                Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open setup")
                }
            }
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
        }

    }

    if (showSettings) SettingsDialog(store) { showSettings = false; tick++ }
}
  
@Composable
private fun Orb(phase: Phase, onClick: () -> Unit) {
    val (c1, c2) = when (phase) {
        Phase.Off -> Color(0xFF6C7BB0) to Color(0xFF2A3560)
        Phase.Waiting -> Color(0xFF5B8CFF) to Color(0xFF1E2F7A)
        Phase.Connecting -> Color(0xFFFFD166) to Color(0xFFEF8A17)
        Phase.Live -> Color(0xFF6EE7B7) to Color(0xFF0EA5A4)
    }
    val a by animateColorAsState(c1, tween(400), label = "c1")
    val b by animateColorAsState(c2, tween(400), label = "c2")
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 1f,
        targetValue = when (phase) { Phase.Off -> 1.01f; Phase.Waiting -> 1.06f; else -> 1.18f },
        animationSpec = infiniteRepeatable(
            tween(if (phase == Phase.Waiting) 2200 else 900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Box(
        Modifier
            .size(220.dp)
            .semantics { contentDescription = "Kali orb. Double tap to start or stop." }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(a.copy(alpha = .35f), Color.Transparent), center, r), r * pulse)
            val core = r * .55f * (1f + (pulse - 1f) * .5f)
            drawCircle(Brush.radialGradient(listOf(a, b), Offset(center.x, center.y), core), core)
        }
    }
}

@Composable
private fun SettingsDialog(store: SettingsStore, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var gemini by remember { mutableStateOf(store.geminiKey) }
    var pico by remember { mutableStateOf(store.picoKey) }
    var model by remember { mutableStateOf(store.model) }
    var voice by remember { mutableStateOf(store.voice) }
    var hasPpn by remember { mutableStateOf(store.keywordFile(ctx).exists()) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    store.keywordFile(ctx).outputStream().use { input.copyTo(it) }
                }
                hasPpn = true; message = "Wake-word file imported."
            } catch (e: Exception) { message = "Couldn’t import: ${e.message}" }
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(gemini, { gemini = it }, singleLine = true, label = { Text("Gemini API key") },
                    visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(pico, { pico = it }, singleLine = true, label = { Text("Picovoice AccessKey") },
                    visualTransformation = PasswordVisualTransformation())
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasPpn) "Wake-word file ✓ (tap to replace)" else "Import kali.ppn")
                }
                OutlinedTextField(model, { model = it }, singleLine = true, label = { Text("Gemini Live model") })
                OutlinedTextField(voice, { voice = it }, singleLine = true, label = { Text("Voice (Aoede, Puck, Charon, Kore, Fenrir)") })
                OutlinedButton(onClick = { allowBackground(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow background running")
                }
                message?.let { Text(it, color = Muted) }
                Text("Keys are stored encrypted on this phone.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                store.geminiKey = gemini; store.picoKey = pico; store.model = model; store.voice = voice
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private fun allowBackground(ctx: Context) {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
        )
    }
}
