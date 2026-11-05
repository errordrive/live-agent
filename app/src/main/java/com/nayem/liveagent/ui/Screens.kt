package com.nayem.liveagent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nayem.liveagent.LiveAgentApp
import com.nayem.liveagent.agent.ToolResult
import com.nayem.liveagent.data.AppSettings
import com.nayem.liveagent.data.AuditEntry
import com.nayem.liveagent.data.ChatMessage
import com.nayem.liveagent.data.MemoryAuto
import com.nayem.liveagent.data.MemoryEntry
import com.nayem.liveagent.data.PlanStep
import com.nayem.liveagent.data.Routine
import com.nayem.liveagent.data.Skill
import com.nayem.liveagent.data.TaskPlan
import com.nayem.liveagent.data.skillPacks
import com.nayem.liveagent.data.validateSkillJson
import com.nayem.liveagent.live.AudioPlayer
import com.nayem.liveagent.live.GeminiLiveClient
import com.nayem.liveagent.live.GeminiRestClient
import com.nayem.liveagent.live.LiveEvent
import com.nayem.liveagent.live.MicCapture
import com.nayem.liveagent.mcp.McpServerConfig
import com.nayem.liveagent.mcp.McpStore
import com.nayem.liveagent.service.LiveAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatAuditTime(ms: Long): String =
    SimpleDateFormat("HH:mm dd MMM", Locale.getDefault()).format(Date(ms))

private val Voices = listOf(
    "Kore", "Aoede", "Callirrhoe", "Charon", "Enceladus",
    "Erinome", "Fenrir", "Iapetus", "Leda", "Orus"
)

private val Personas = listOf(
    "Friend" to "Speak like a warm, easygoing friend. Keep replies short and natural.",
    "English teacher" to "Speak like a patient English teacher. Correct mistakes gently and explain briefly.",
    "Interview practice" to "Act as an interviewer. Ask one question at a time and give short feedback on answers.",
    "Calm companion" to "Speak slowly and calmly, like a relaxing companion. Keep replies gentle and brief.",
    "Custom" to ""
)

// ---------------------------------------------------------------------------
// Talking animation — breathing idle, audio-reactive waveform bars and
// pulsing rings. States: idle / listening / speaking / thinking.
// Amplitude is 0..1 RMS from the mic (listening) or playback (speaking).
// ---------------------------------------------------------------------------

enum class TalkState { IDLE, LISTENING, SPEAKING, THINKING }

@Composable
fun TalkingOrb(
    state: TalkState,
    micAmplitude: Float,
    agentAmplitude: Float,
    modifier: Modifier = Modifier
) {
    val breathing by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 1f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave"
    )
    val level by animateFloatAsState(
        targetValue = when (state) {
            TalkState.IDLE -> 0.08f
            TalkState.LISTENING -> (0.25f + micAmplitude * 0.75f).coerceIn(0f, 1f)
            TalkState.SPEAKING -> (0.25f + agentAmplitude * 0.75f).coerceIn(0f, 1f)
            TalkState.THINKING -> 0.35f
        },
        animationSpec = smoothSpring(),
        label = "level"
    )
    val bronze = Color(0xFFB08D57)
    val sage = Color(0xFF7A8B6F)
    val accent = if (state == TalkState.SPEAKING) sage else bronze
    Canvas(modifier = modifier.size(200.dp)) {
        val c = center
        val base = size.minDimension / 2f * 0.62f * breathing
        // Soft outer glow ring, pulses with amplitude.
        drawCircle(
            color = accent.copy(alpha = 0.16f),
            radius = base * (1.25f + level * 0.45f),
            center = c
        )
        drawCircle(
            color = accent.copy(alpha = 0.28f),
            radius = base * (1.05f + level * 0.25f),
            center = c
        )
        drawCircle(color = accent, radius = base, center = c)
        // Waveform bars across the orb — alive while talking, calm while idle.
        val bars = 7
        val barWidth = size.minDimension / 22f
        val gap = size.minDimension / 16f
        val totalWidth = bars * barWidth + (bars - 1) * gap
        var x = c.x - totalWidth / 2f + barWidth / 2f
        for (i in 0 until bars) {
            val wave = kotlin.math.sin(phase + i * 0.9f).toFloat() * 0.5f + 0.5f
            val h = base * (0.28f + level * 0.9f * (0.35f + 0.65f * wave))
            drawLine(
                color = Color.White,
                start = Offset(x, c.y - h / 2f),
                end = Offset(x, c.y + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round
            )
            x += barWidth + gap
        }
    }
}

// ---------------------------------------------------------------------------
// Live tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val stores = LiveAgentApp.stores
    val secure = LiveAgentApp.secure
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf(stores.loadSettings()) }
    var voice by remember { mutableStateOf(settings.voiceName.ifBlank { "Kore" }) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var persona by remember { mutableStateOf("Friend") }

    var connected by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not connected") }
    var transcript by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var typeText by remember { mutableStateOf("") }
    var micAmplitude by remember { mutableStateOf(0f) }
    var agentAmplitude by remember { mutableStateOf(0f) }
    var micPermissionNeeded by remember { mutableStateOf(false) }

    var liveClient by remember { mutableStateOf<GeminiLiveClient?>(null) }
    var mic by remember { mutableStateOf<MicCapture?>(null) }
    val player = remember { AudioPlayer() }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            status = "Microphone permission needed — allow it to talk."
            micPermissionNeeded = true
        }
    }

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun appendTranscript(role: String, text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val current = transcript.toMutableList()
        val last = current.lastOrNull()
        if (last != null && last.role == role && last.isPartial) {
            current[current.lastIndex] = last.copy(text = text, time = now, isPartial = !isFinal)
        } else {
            current.add(ChatMessage(role = role, text = text, time = now, isPartial = !isFinal))
        }
        transcript = current
        if (isFinal) MemoryAuto.autoSaveTurn(stores, role, text)
    }

    fun stopLive() {
        try { mic?.stop() } catch (_: Exception) {}
        mic = null
        try { player.stop(); player.clear() } catch (_: Exception) {}
        try { liveClient?.reset() } catch (_: Exception) {}
        liveClient = null
        connected = false
        speaking = false
        thinking = false
        micAmplitude = 0f
        agentAmplitude = 0f
        status = "Not connected"
    }

    DisposableEffect(Unit) {
        onDispose { stopLive() }
    }

    fun startLive() {
        // Never depend on the key-test result: connect directly and surface
        // the real error if it fails.
        if (!secure.hasGeminiKey()) {
            status = "Add your Gemini API key in Settings first."
            return
        }
        if (!hasMicPermission()) {
            status = "Microphone permission needed."
            micPermissionNeeded = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        micPermissionNeeded = false
        val key = secure.geminiApiKey
        val personaSuffix = Personas.firstOrNull { it.first == persona }?.second.orEmpty()
        val prompt = if (personaSuffix.isBlank()) settings.systemPrompt
        else settings.systemPrompt + "\nPersona: $personaSuffix"
        status = "Connecting..."
        thinking = true
        val client = GeminiLiveClient(key, settings.model, voice, prompt) { event ->
            scope.launch {
            when (event) {
                is LiveEvent.Connected -> {
                    connected = true
                    speaking = false
                    thinking = false
                    status = "Connected — listening"
                }
                is LiveEvent.Disconnected -> {
                    connected = false
                    speaking = false
                    thinking = false
                    if (status.startsWith("Connecting") || status.startsWith("Ready")) {
                        status = "Not connected — ${event.reason}"
                    }
                }
                is LiveEvent.SetupComplete -> {
                    thinking = false
                    status = "Ready — speak now"
                }
                is LiveEvent.UserTranscript -> {
                    appendTranscript("user", event.text, event.isFinal)
                }
                is LiveEvent.AgentTranscript -> {
                    speaking = !event.isFinal
                    thinking = false
                    appendTranscript("agent", event.text, event.isFinal)
                    if (event.isFinal) speaking = false
                }
                is LiveEvent.AgentAudio -> {
                    speaking = true
                    thinking = false
                    player.onAmplitude = { agentAmplitude = it }
                    player.playPcm(event.bytes)
                }
                is LiveEvent.Interrupted -> {
                    speaking = false
                    player.stop()
                }
                is LiveEvent.Error -> {
                    // Surface the real server/WebSocket error, not a generic line.
                    status = event.message
                    thinking = false
                    stopLive()
                    status = event.message
                }
            }
            }
        }
        liveClient = client
        try {
            client.connect()
            val capture = MicCapture(context) { pcm -> client.sendAudioPcm16(pcm) }
            capture.onAmplitude = { micAmplitude = it }
            mic = capture
            capture.start()
        } catch (e: Exception) {
            status = "Could not start: ${e.message}"
            stopLive()
            status = "Could not start: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // API key lives in Settings only — this tab shows status, never the key.
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gemini API key", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (secure.hasGeminiKey()) "Key configured — ready to talk."
                        else "Not configured yet.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }

        SoftCard {
            Text("Voice & persona", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "gemini-3.8-live (free, recommended)",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = voiceExpanded,
                onExpandedChange = { voiceExpanded = it }
            ) {
                OutlinedTextField(
                    value = voice,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = voiceExpanded,
                    onDismissRequest = { voiceExpanded = false }
                ) {
                    Voices.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                voice = name
                                settings = settings.copy(voiceName = name)
                                stores.saveSettings(settings)
                                voiceExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Personas.forEach { (name, _) ->
                    FilterChip(
                        selected = persona == name,
                        onClick = { persona = name },
                        label = { Text(name) }
                    )
                }
            }
        }

        SoftCard {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val talkState = when {
                    speaking -> TalkState.SPEAKING
                    thinking -> TalkState.THINKING
                    connected -> TalkState.LISTENING
                    else -> TalkState.IDLE
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                    TalkingOrb(
                        state = talkState,
                        micAmplitude = micAmplitude,
                        agentAmplitude = agentAmplitude
                    )
                    Text(
                        text = when {
                            speaking -> "Speaking"
                            thinking -> "Thinking"
                            connected -> "Listening"
                            else -> "Tap to talk"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { if (connected) stopLive() else startLive() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB08D57)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Icon(
                        if (connected) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (connected) "Stop call" else "Start call", fontSize = 17.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (micPermissionNeeded) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Allow microphone") }
                }
            }
        }

        SoftCard {
            Text("Conversation", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            if (transcript.isEmpty()) {
                Text(
                    "Your live conversation will appear here.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            transcript.forEach { message ->
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalAlignment = if (message.role == "user") Alignment.End else Alignment.Start
                    ) {
                        Text(
                            text = (if (message.role == "user") "You" else "Agent") +
                                "  ·  " + formatTime(message.time),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(message.text, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typeText,
                    onValueChange = { typeText = it },
                    label = { Text("Type to talk") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    val text = typeText.trim()
                    if (text.isBlank()) return@IconButton
                    appendTranscript("user", text, true)
                    liveClient?.sendText(text)
                    typeText = ""
                }) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
            if (!connected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Type-to-talk sends once a call is connected.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // Keep context referenced for future intent actions from this tab.
        @Suppress("UNUSED_VARIABLE") val unusedContext = context
    }
}

// ---------------------------------------------------------------------------
// Tasks tab
// ---------------------------------------------------------------------------

@Composable
fun AgentScreen() {
    val orchestrator = LiveAgentApp.orchestrator
    val scope = rememberCoroutineScope()

    var goal by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingPlan by remember { mutableStateOf<TaskPlan?>(null) }
    var planSteps by remember { mutableStateOf<List<PlanStep>>(emptyList()) }
    val stepStatus = remember { mutableStateMapOf<Int, String>() }
    var results by remember { mutableStateOf<List<Pair<PlanStep, ToolResult>>>(emptyList()) }

    var pendingStep by remember { mutableStateOf<PlanStep?>(null) }
    var pendingDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val confirmStep: suspend (PlanStep) -> Boolean = { step ->
        if (!step.risky) {
            true
        } else {
            val deferred = CompletableDeferred<Boolean>()
            pendingDeferred = deferred
            pendingStep = step
            val ok = deferred.await()
            pendingStep = null
            pendingDeferred = null
            ok
        }
    }

    fun runPlan(plan: TaskPlan) {
        planSteps = plan.steps
        stepStatus.clear()
        results = emptyList()
        working = true
        status = "Running..."
        scope.launch {
            try {
                val done = orchestrator.executePlan(
                    plan,
                    onStep = { index, message -> stepStatus[index] = message },
                    confirm = confirmStep
                )
                results = done
                status = "Done — ${done.count { it.second.success }} of ${done.size} steps succeeded."
            } catch (e: Exception) {
                status = "Failed: ${e.message}"
            }
            working = false
        }
    }

    fun submitGoal() {
        val text = goal.trim()
        if (text.isBlank()) return
        working = true
        status = "Planning..."
        planSteps = emptyList()
        results = emptyList()
        scope.launch {
            try {
                val plan = orchestrator.buildPlan(text)
                if (plan.needsConfirmation || plan.steps.any { it.risky }) {
                    pendingPlan = plan
                    status = "This plan needs your approval."
                } else {
                    runPlan(plan)
                }
            } catch (e: Exception) {
                status = "Planning failed: ${e.message}"
            }
            working = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SoftCard {
            Text("What should I do?", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("Describe your goal") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { submitGoal() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (working) "Working..." else "Run") }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { goal = "Research the latest news about artificial intelligence in Chrome" }, label = { Text("Research in Chrome") })
            AssistChip(onClick = { goal = "Play relaxing music on YouTube" }, label = { Text("Play on YouTube") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { goal = "Write a note titled Ideas with my thoughts" }, label = { Text("Write a note") })
            AssistChip(onClick = { goal = "Run ls in Termux" }, label = { Text("Run in Termux") })
        }

        pendingPlan?.let { plan ->
            SoftCard {
                Text("Plan preview", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(plan.goal, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                plan.steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. ${step.description}" + if (step.risky) "  (needs confirmation)" else "",
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        pendingPlan = null
                        runPlan(plan)
                    }) { Text("Approve & Run") }
                    OutlinedButton(onClick = { pendingPlan = null }) { Text("Cancel") }
                }
            }
        }

        if (planSteps.isNotEmpty()) {
            SoftCard {
                Text("Steps", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                planSteps.forEachIndexed { index, step ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text("${index + 1}. ${step.description}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        stepStatus[index]?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        results.getOrNull(index)?.let { (_, result) ->
                            Text(
                                (if (result.success) "Done: " else "Failed: ") + result.detail,
                                fontSize = 13.sp,
                                color = if (result.success) Color(0xFF7A8B6F) else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    pendingStep?.let { step ->
        AlertDialog(
            onDismissRequest = { pendingDeferred?.complete(false) },
            title = { Text("Confirm this step") },
            text = { Text(step.description) },
            confirmButton = {
                TextButton(onClick = { pendingDeferred?.complete(true) }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeferred?.complete(false) }) { Text("Skip") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Skills tab
// ---------------------------------------------------------------------------

@Composable
fun SkillsScreen() {
    val stores = LiveAgentApp.stores
    val skills = remember {
        mutableStateListOf<Skill>().also { it.addAll(stores.loadSkills()) }
    }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }
    var editSteps by remember { mutableStateOf("") }
    var editNeedsConfirmation by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showPacks by remember { mutableStateOf(false) }
    var importJson by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf("") }
    var exportNote by remember { mutableStateOf("") }
    var packStatus by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val clipboard = LocalContext.current.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun openEditor(skill: Skill) {
        expandedId = skill.id
        editName = skill.name
        editDescription = skill.description
        editSteps = skill.steps.joinToString("\n")
        editNeedsConfirmation = skill.needsConfirmation
    }

    fun saveSkills() {
        stores.saveSkills(skills.toList())
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "${skills.size} skills — tap a skill to edit it.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showAdd = true }) { Text("Add skill") }
                OutlinedButton(onClick = { showImport = true }) { Text("Import") }
                OutlinedButton(onClick = {
                    val json = Json.encodeToString(skills.toList())
                    clipboard.setPrimaryClip(ClipData.newPlainText("live-agent-skills", json))
                    exportNote = "All ${skills.size} skills copied as JSON — paste it anywhere to back it up."
                }) { Text("Export") }
                OutlinedButton(onClick = { showPacks = !showPacks }) { Text("Skill packs") }
            }
            if (exportNote.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(exportNote, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showPacks) {
                Spacer(Modifier.height(8.dp))
                com.nayem.liveagent.data.skillPacks().forEach { pack ->
                    SoftCard {
                        Text(pack.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                        Text(pack.description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(pack.guardNote, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Text("Needs: ${pack.requiredEnv}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Install: ${pack.installCommand}", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val already = skills.any { s -> pack.skills.any { it.id == s.id } }
                                if (already) {
                                    packStatus = "${pack.name} is already installed."
                                } else {
                                    skills.addAll(pack.skills)
                                    saveSkills()
                                    packStatus = "${pack.name} installed — ${pack.skills.size} skills added. " +
                                        "Install the tool itself with: ${pack.installCommand}"
                                }
                            }) { Text("Install pack") }
                            OutlinedButton(onClick = {
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("skill-pack-install", pack.installCommand)
                                )
                                packStatus = "Install command copied. Run it in the Ubuntu/Termux terminal."
                            }) { Text("Copy command") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (packStatus.isNotBlank()) {
                    Text(packStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(skills, key = { it.id }) { skill ->
            SoftCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (expandedId == skill.id) expandedId = null else openEditor(skill) }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                        Text(
                            skill.description,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { enabled ->
                            val index = skills.indexOfFirst { it.id == skill.id }
                            if (index >= 0) {
                                skills[index] = skill.copy(enabled = enabled)
                                saveSkills()
                            }
                        }
                    )
                }
                if (expandedId == skill.id) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editDescription,
                        onValueChange = { editDescription = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editSteps,
                        onValueChange = { editSteps = it },
                        label = { Text("Steps (one per line)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Needs confirmation", modifier = Modifier.weight(1f))
                        Switch(
                            checked = editNeedsConfirmation,
                            onCheckedChange = { editNeedsConfirmation = it }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            val index = skills.indexOfFirst { it.id == skill.id }
                            if (index >= 0) {
                                skills[index] = skill.copy(
                                    name = editName.trim().ifBlank { skill.name },
                                    description = editDescription.trim(),
                                    steps = editSteps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                                    needsConfirmation = editNeedsConfirmation
                                )
                                saveSkills()
                            }
                            expandedId = null
                        }) { Text("Save") }
                        OutlinedButton(onClick = {
                            val copy = skill.copy(
                                id = "${skill.id}_copy_${UUID.randomUUID().toString().take(4)}",
                                name = "${skill.name} (copy)"
                            )
                            skills.add(copy)
                            saveSkills()
                            expandedId = null
                        }) { Text("Duplicate") }
                        TextButton(onClick = {
                            skills.remove(skill)
                            saveSkills()
                            expandedId = null
                        }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddSkillDialog(
            onDismiss = { showAdd = false },
            onSave = { skill ->
                skills.add(skill)
                saveSkills()
                showAdd = false
            }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("Import skills") },
            text = {
                Column {
                    Text(
                        "Paste one skill or a list of skills as JSON.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importJson,
                        onValueChange = { importJson = it; importError = "" },
                        label = { Text("Skill JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 5
                    )
                    if (importError.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(importError, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = importJson.trim()
                    if (trimmed.isBlank()) {
                        importError = "Paste some JSON first."
                        return@TextButton
                    }
                    try {
                        val added = if (trimmed.startsWith("[")) {
                            Json.decodeFromString<List<Skill>>(trimmed)
                        } else {
                            validateSkillJson(trimmed).getOrElse {
                                importError = "Invalid skill: ${it.message}"
                                return@TextButton
                            }.let { listOf(it) }
                        }
                        val fresh = added.filter { s -> skills.none { it.id == s.id } }
                        skills.addAll(fresh)
                        saveSkills()
                        showImport = false
                        importJson = ""
                        exportNote = "Imported ${fresh.size} skill(s)" +
                            if (fresh.size < added.size) " (${added.size - fresh.size} already existed)." else "."
                    } catch (e: Exception) {
                        importError = "Invalid JSON: ${e.message}"
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("Cancel") }
            }
        )
    }
}


// ---------------------------------------------------------------------------
// Add skill — form + raw JSON editor with validation
// ---------------------------------------------------------------------------

@Composable
fun AddSkillDialog(onDismiss: () -> Unit, onSave: (Skill) -> Unit) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var triggers by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    var needsConfirmation by remember { mutableStateOf(false) }
    var useJson by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a skill") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Raw JSON", modifier = Modifier.weight(1f))
                    Switch(checked = useJson, onCheckedChange = { useJson = it })
                }
                Spacer(Modifier.height(8.dp))
                if (useJson) {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it; error = "" },
                        label = { Text("Skill JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Example: {\"id\": \"my_skill\", \"name\": \"My skill\", \"description\": \"...\", \"steps\": [\"speak: hello\"]}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = triggers, onValueChange = { triggers = it },
                        label = { Text("Trigger examples (one per line)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = steps, onValueChange = { steps = it },
                        label = { Text("Steps (one per line)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Needs confirmation", modifier = Modifier.weight(1f))
                        Switch(checked = needsConfirmation, onCheckedChange = { needsConfirmation = it })
                    }
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (useJson) {
                    validateSkillJson(jsonText.trim()).fold(
                        onSuccess = { onSave(it) },
                        onFailure = { error = "Invalid skill: ${it.message}" }
                    )
                } else {
                    if (name.isBlank()) {
                        error = "Give the skill a name."
                        return@TextButton
                    }
                    onSave(
                        Skill(
                            id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') +
                                "_" + UUID.randomUUID().toString().take(4),
                            name = name.trim(),
                            description = description.trim(),
                            triggerExamples = triggers.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            permissions = emptyList(),
                            needsConfirmation = needsConfirmation,
                            enabled = true
                        )
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ---------------------------------------------------------------------------
// Memory tab
// ---------------------------------------------------------------------------

@Composable
fun MemoryScreen() {
    val stores = LiveAgentApp.stores
    val entries = remember {
        mutableStateListOf<MemoryEntry>().also { it.addAll(stores.loadMemory()) }
    }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editValue by remember { mutableStateOf("") }

    fun saveAll() {
        stores.saveMemory(entries.toList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SoftCard {
            Text("Remember something", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (newKey.isBlank() || newValue.isBlank()) return@Button
                    val now = System.currentTimeMillis()
                    entries.add(
                        MemoryEntry(
                            id = UUID.randomUUID().toString(),
                            key = newKey.trim(),
                            value = newValue.trim(),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    saveAll()
                    newKey = ""
                    newValue = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add") }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(entries, key = { it.id }) { entry ->
                SoftCard {
                    Text(entry.key, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                    if (editingId == entry.id) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editValue,
                            onValueChange = { editValue = it },
                            label = { Text("Value") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                val index = entries.indexOfFirst { it.id == entry.id }
                                if (index >= 0) {
                                    entries[index] = entry.copy(
                                        value = editValue,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    saveAll()
                                }
                                editingId = null
                            }) { Text("Save") }
                            OutlinedButton(onClick = { editingId = null }) { Text("Cancel") }
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(entry.value, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = {
                                editingId = entry.id
                                editValue = entry.value
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                entries.remove(entry)
                                saveAll()
                            }) { Text("Forget") }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Audit (from More)
// ---------------------------------------------------------------------------

@Composable
fun AuditScreen(onBack: () -> Unit = {}) {
    val stores = LiveAgentApp.stores
    var entries by remember { mutableStateOf(stores.loadAudit().sortedByDescending { it.time }) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Audit log", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                stores.saveAudit(emptyList())
                entries = emptyList()
            }) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))
        if (entries.isEmpty()) {
            Text("No activity yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.id }) { entry: AuditEntry ->
                SoftCard {
                    Text(entry.skill.ifBlank { "Agent" }, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                    Text(entry.step, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${entry.result}  ·  ${formatAuditTime(entry.time)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.detail.isNotBlank()) {
                        Text(entry.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings (from More)
// ---------------------------------------------------------------------------

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val stores = LiveAgentApp.stores
    val secure = LiveAgentApp.secure
    var settings by remember { mutableStateOf(stores.loadSettings()) }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt) }
    var geminiKey by remember { mutableStateOf(secure.geminiApiKey) }
    var showGeminiKey by remember { mutableStateOf(false) }
    var geminiKeyStatus by remember { mutableStateOf("") }
    var openRouterKey by remember { mutableStateOf(secure.openRouterApiKey) }
    var groqKey by remember { mutableStateOf(secure.groqApiKey) }
    var notePackage by remember { mutableStateOf(settings.noteAppPackage) }
    var savedNote by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryExempt = remember {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    val accessibilityEnabled = remember {
        LiveAccessibilityService.isEnabled(context)
    }

    fun persistSettings(next: AppSettings) {
        settings = next
        stores.saveSettings(next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        SoftCard {
            Text("System prompt", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                persistSettings(settings.copy(systemPrompt = systemPrompt))
                savedNote = "System prompt saved."
            }) { Text("Save") }
        }

        SoftCard {
            Text("Locked rules", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(AppSettings.HARD_RULES, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SoftCard {
            Text("Gemini API key", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Stored on this device only, encrypted. Never shared.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = geminiKey,
                onValueChange = { geminiKey = it },
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = if (showGeminiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                        Icon(
                            if (showGeminiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showGeminiKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    secure.geminiApiKey = geminiKey.trim()
                    geminiKeyStatus = "Gemini key saved on this device."
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    val key = geminiKey.trim()
                    if (key.isBlank()) {
                        geminiKeyStatus = "Enter a key first."
                        return@OutlinedButton
                    }
                    geminiKeyStatus = "Checking key..."
                    scope.launch {
                        val result = GeminiRestClient(key).testKeyDetailed()
                        geminiKeyStatus = result.fold(
                            onSuccess = { it.message },
                            onFailure = { e -> "Key check failed: ${e.message}" }
                        )
                    }
                }) { Text("Test key") }
            }
            if (geminiKeyStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(geminiKeyStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SoftCard {
            Text("Other provider keys", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = openRouterKey,
                onValueChange = { openRouterKey = it },
                label = { Text("OpenRouter API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = groqKey,
                onValueChange = { groqKey = it },
                label = { Text("Groq API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                secure.openRouterApiKey = openRouterKey.trim()
                secure.groqApiKey = groqKey.trim()
                savedNote = "Provider keys saved."
            }) { Text("Save") }
        }

        SoftCard {
            Text("Note app", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = notePackage,
                onValueChange = { notePackage = it },
                label = { Text("Note app package") },
                singleLine = true,
                placeholder = { Text("e.g. com.example.notes") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                persistSettings(settings.copy(noteAppPackage = notePackage.trim()))
                savedNote = "Note app saved."
            }) { Text("Save") }
        }

        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Wake word", fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                    Text(
                        "Optional, off by default.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { persistSettings(settings.copy(wakeWordEnabled = it)) }
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Accessibility: " + if (accessibilityEnabled) "Enabled" else "Not enabled",
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp
                )
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text("Open settings") }
            }
            Spacer(Modifier.height(12.dp))
            if (batteryExempt) {
                Text("Battery optimization is already off for this app.", fontSize = 14.sp, color = Color(0xFF7A8B6F))
            } else {
                OutlinedButton(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }) { Text("Allow running in background") }
            }
        }

        SoftCard {
            Text("About", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Live Agent 1.1.0", fontSize = 15.sp)
            Text(
                "Sideloaded build. The Play Store is not eligible for this app.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (savedNote.isNotBlank()) {
            Text(savedNote, fontSize = 14.sp, color = Color(0xFF7A8B6F), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal (from More)
// ---------------------------------------------------------------------------

@Composable
fun TerminalScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val orchestrator = LiveAgentApp.orchestrator
    val scope = rememberCoroutineScope()

    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Output will appear here.") }
    var running by remember { mutableStateOf(false) }
    var pendingRun by remember { mutableStateOf<Boolean?>(null) } // true = Termux, false = built-in

    fun runCommand(inTermux: Boolean) {
        val cmd = command.trim()
        if (cmd.isBlank()) return
        running = true
        output = "Running..."
        scope.launch {
            try {
                val result = if (inTermux) {
                    orchestrator.terminalTools.runTermux(cmd)
                } else {
                    orchestrator.terminalTools.runBuiltIn(cmd)
                }
                output = result.detail.ifBlank { if (result.success) "Done." else "Failed." }
            } catch (e: Exception) {
                output = "Failed: ${e.message}"
            }
            running = false
        }
    }

    fun requestRun(inTermux: Boolean) {
        if (orchestrator.terminalTools.isDangerous(command.trim())) {
            pendingRun = inTermux
        } else {
            runCommand(inTermux)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Terminal", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        SoftCard {
            Text("Ubuntu environment", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ubuntu commands are not available in Live Agent v1.1.0 — the Ubuntu " +
                    "environment could not be delivered in this version. Use Termux " +
                    "below if it is installed, or the built-in terminal for basic shell commands.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SoftCard {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { requestRun(false) }, enabled = !running) { Text("Run built-in") }
                OutlinedButton(onClick = { requestRun(true) }, enabled = !running) { Text("Run in Termux") }
            }
        }
        SoftCard(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(output, fontSize = 14.sp)
            }
        }
        @Suppress("UNUSED_VARIABLE") val unusedContext = context
    }

    pendingRun?.let { inTermux ->
        AlertDialog(
            onDismissRequest = { pendingRun = null },
            title = { Text("Dangerous command") },
            text = { Text("This command looks risky. Run it anyway?\n\n$command") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRun = null
                    runCommand(inTermux)
                }) { Text("Run anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRun = null }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Routines (from More)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(onBack: () -> Unit = {}) {
    val stores = LiveAgentApp.stores
    val orchestrator = LiveAgentApp.orchestrator
    val scope = rememberCoroutineScope()

    val routines = remember {
        mutableStateListOf<Routine>().also { it.addAll(stores.loadRoutines()) }
    }
    var name by remember { mutableStateOf("") }
    var triggerType by remember { mutableStateOf("manual") }
    var triggerExpanded by remember { mutableStateOf(false) }
    var triggerValue by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var runStatus by remember { mutableStateOf("") }

    fun saveAll() {
        stores.saveRoutines(routines.toList())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Routines", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        SoftCard {
            Text("New routine", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(
                expanded = triggerExpanded,
                onExpandedChange = { triggerExpanded = it }
            ) {
                OutlinedTextField(
                    value = triggerType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Trigger type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = triggerExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = triggerExpanded,
                    onDismissRequest = { triggerExpanded = false }
                ) {
                    listOf("time", "device", "manual").forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type) },
                            onClick = {
                                triggerType = type
                                triggerExpanded = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = triggerValue,
                onValueChange = { triggerValue = it },
                label = { Text("Trigger value (e.g. 07:00)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("Command") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (name.isBlank() || command.isBlank()) return@Button
                    routines.add(
                        Routine(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            triggerType = triggerType,
                            triggerValue = triggerValue.trim(),
                            command = command.trim(),
                            enabled = true
                        )
                    )
                    saveAll()
                    name = ""
                    triggerValue = ""
                    command = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add routine") }
        }

        if (runStatus.isNotBlank()) {
            Text(runStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(routines, key = { it.id }) { routine ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(routine.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                            Text(
                                "${routine.triggerType} · ${routine.triggerValue.ifBlank { "—" }}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(routine.command, fontSize = 14.sp)
                        }
                        Switch(
                            checked = routine.enabled,
                            onCheckedChange = { enabled ->
                                val index = routines.indexOfFirst { it.id == routine.id }
                                if (index >= 0) {
                                    routines[index] = routine.copy(enabled = enabled)
                                    saveAll()
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        runStatus = "Running ${routine.name}..."
                        scope.launch {
                            try {
                                val plan = orchestrator.buildPlan(routine.command)
                                orchestrator.executePlan(
                                    plan,
                                    onStep = { _, _ -> },
                                    confirm = { true }
                                )
                                val index = routines.indexOfFirst { it.id == routine.id }
                                if (index >= 0) {
                                    routines[index] = routine.copy(lastRun = System.currentTimeMillis())
                                    saveAll()
                                }
                                runStatus = "${routine.name} finished."
                            } catch (e: Exception) {
                                runStatus = "${routine.name} failed: ${e.message}"
                            }
                        }
                    }) { Text("Run now") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// MCP servers — add any MCP server yourself, list its tools, call them.
// ---------------------------------------------------------------------------

@Composable
fun McpScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { McpStore(context) }
    val client = remember { com.nayem.liveagent.mcp.McpClient() }
    val scope = rememberCoroutineScope()

    val servers = remember {
        mutableStateListOf<McpServerConfig>().also { it.addAll(store.loadServers()) }
    }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var toolsText by remember { mutableStateOf("") }
    var callToolName by remember { mutableStateOf("") }
    var callArgs by remember { mutableStateOf("{}") }
    var callResult by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    fun saveAll() = store.saveServers(servers.toList())
    val selected = servers.firstOrNull { it.id == selectedId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("MCP servers", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            "Add any MCP server (JSON-RPC 2.0 over HTTP). The planner can call its tools. No server is built in.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SoftCard {
            Text("Add a server", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Server URL (http://host:port/...)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = headersText, onValueChange = { headersText = it },
                label = { Text("Headers (optional, one per line: Key: Value)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (name.isBlank() || url.isBlank()) {
                    status = "Name and URL are required."
                    return@Button
                }
                val headers = headersText.lines().mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank()) parts[0].trim() to parts[1].trim() else null
                }.toMap()
                servers.add(
                    McpServerConfig(
                        id = UUID.randomUUID().toString(),
                        name = name.trim(), url = url.trim(),
                        headers = headers, enabled = true
                    )
                )
                saveAll()
                name = ""
                url = ""
                headersText = ""
                status = "Server added."
            }) { Text("Add server") }
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        servers.forEach { server ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = HeadingFont)
                        Text(server.url, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = server.enabled,
                        onCheckedChange = { enabled ->
                            val index = servers.indexOfFirst { it.id == server.id }
                            if (index >= 0) {
                                servers[index] = server.copy(enabled = enabled)
                                saveAll()
                            }
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        selectedId = server.id
                        toolsText = ""
                        callResult = ""
                        busy = true
                        status = "Connecting to ${server.name}..."
                        scope.launch {
                            val ping = client.ping(server)
                            val tools = client.listTools(server)
                            busy = false
                            status = ping.fold(
                                onSuccess = { if (it) "Connected to ${server.name}." else "Server answered but did not confirm initialize." },
                                onFailure = { e -> "Connection failed: ${e.message}" }
                            )
                            toolsText = tools.fold(
                                onSuccess = { list ->
                                    if (list.isEmpty()) "No tools listed."
                                    else list.joinToString("\n") { "- ${it.name}: ${it.description}" }
                                },
                                onFailure = { e -> "Could not list tools: ${e.message}" }
                            )
                        }
                    }, enabled = !busy) { Text("Connect & list tools") }
                    TextButton(onClick = {
                        servers.remove(server)
                        saveAll()
                        if (selectedId == server.id) selectedId = null
                    }) { Text("Remove") }
                }
                if (selectedId == server.id && toolsText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(toolsText, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = callToolName, onValueChange = { callToolName = it },
                        label = { Text("Tool name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = callArgs, onValueChange = { callArgs = it },
                        label = { Text("Arguments (JSON)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (callToolName.isBlank()) {
                            callResult = "Enter a tool name first."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            val result = client.callTool(server, callToolName.trim(), callArgs)
                            busy = false
                            callResult = result.fold(
                                onSuccess = { it.text.ifBlank { it.error.ifBlank { "Done." } } },
                                onFailure = { e -> "Call failed: ${e.message}" }
                            )
                        }
                    }, enabled = !busy) { Text("Call tool") }
                    if (callResult.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(callResult, fontSize = 14.sp)
                    }
                }
            }
        }
        if (servers.isEmpty()) {
            Text(
                "No MCP servers yet. Run one in the Ubuntu/Termux terminal (for example a HyperFrames or REA MCP server) and add its URL here.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Screen Control — try hands-free phone control manually.
// ---------------------------------------------------------------------------

@Composable
fun ScreenControlScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val tools = LiveAgentApp.orchestrator.phoneTools
    val scope = rememberCoroutineScope()
    var tapText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val enabled = remember { LiveAccessibilityService.isEnabled(context) }

    fun run(label: String, block: () -> ToolResult) {
        scope.launch {
            val result = try { block() } catch (e: Exception) { ToolResult(false, e.message ?: "Failed") }
            status = "$label: " + if (result.success) result.detail else "Failed — ${result.detail}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Screen control", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            if (enabled) "Accessibility service is enabled — the agent can control this phone without touch."
            else "Accessibility service is NOT enabled. Open Settings and turn on Live Agent control first.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!enabled) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Open accessibility settings") }
        }
        SoftCard {
            Text("Tap by text", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tapText, onValueChange = { tapText = it },
                label = { Text("Text on screen, e.g. Search") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { run("Tap") { tools.screenTapText(tapText) } }) { Text("Tap") }
        }
        SoftCard {
            Text("Scroll & press", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { run("Scroll") { tools.screenScroll("up") } }) { Text("Scroll up") }
                OutlinedButton(onClick = { run("Scroll") { tools.screenScroll("down") } }) { Text("Scroll down") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { run("Back") { tools.screenPress("back") } }) { Text("Back") }
                OutlinedButton(onClick = { run("Home") { tools.screenPress("home") } }) { Text("Home") }
                OutlinedButton(onClick = { run("Recents") { tools.screenPress("recents") } }) { Text("Recents") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { run("Swipe") {
                    val m = context.resources.displayMetrics
                    tools.screenSwipe(m.widthPixels / 2f, m.heightPixels * 0.7f, m.widthPixels / 2f, m.heightPixels * 0.3f)
                } }) { Text("Swipe up") }
                OutlinedButton(onClick = { run("Volume") { tools.screenVolume("up") } }) { Text("Volume +") }
                OutlinedButton(onClick = { run("Lock") { tools.screenPress("lock") } }) { Text("Lock") }
            }
        }
        if (status.isNotBlank()) {
            Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// More tab
// ---------------------------------------------------------------------------

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val rows = listOf(
        Triple("settings", "Settings", Icons.Filled.Settings),
        Triple("terminal", "Terminal", Icons.Filled.Terminal),
        Triple("routines", "Routines", Icons.Filled.Schedule),
        Triple("mcp", "MCP servers", Icons.Filled.Hub),
        Triple("screen", "Screen control", Icons.Filled.TouchApp),
        Triple("audit", "Audit log", Icons.Filled.History)
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(rows) { (id, label, icon) ->
            SoftCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigate(id) }
                        .animateContentSize()
                ) {
                    Icon(icon, contentDescription = null, tint = Color(0xFFB08D57))
                    Spacer(Modifier.width(16.dp))
                    Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
