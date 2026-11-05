package com.nayem.liveagent.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nayem.liveagent.LiveAgentApp
import com.nayem.liveagent.agent.ToolResult
import com.nayem.liveagent.data.AppSettings
import com.nayem.liveagent.data.AuditEntry
import com.nayem.liveagent.data.ChatMessage
import com.nayem.liveagent.data.MemoryEntry
import com.nayem.liveagent.data.PlanStep
import com.nayem.liveagent.data.Routine
import com.nayem.liveagent.data.Skill
import com.nayem.liveagent.data.TaskPlan
import com.nayem.liveagent.live.AudioPlayer
import com.nayem.liveagent.live.GeminiLiveClient
import com.nayem.liveagent.live.GeminiRestClient
import com.nayem.liveagent.live.LiveEvent
import com.nayem.liveagent.live.MicCapture
import com.nayem.liveagent.service.LiveAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

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
// Live tab
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val stores = LiveAgentApp.stores
    val secure = LiveAgentApp.secure
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(secure.geminiApiKey) }
    var keyStatus by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(stores.loadSettings()) }
    var voice by remember { mutableStateOf(settings.voiceName.ifBlank { "Kore" }) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var persona by remember { mutableStateOf("Friend") }

    var connected by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Not connected") }
    var transcript by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var typeText by remember { mutableStateOf("") }

    var liveClient by remember { mutableStateOf<GeminiLiveClient?>(null) }
    var mic by remember { mutableStateOf<MicCapture?>(null) }
    val player = remember { AudioPlayer() }

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
    }

    fun stopLive() {
        try { mic?.stop() } catch (_: Exception) {}
        mic = null
        try { player.stop(); player.clear() } catch (_: Exception) {}
        try { liveClient?.disconnect() } catch (_: Exception) {}
        liveClient = null
        connected = false
        speaking = false
        status = "Not connected"
    }

    DisposableEffect(Unit) {
        onDispose { stopLive() }
    }

    fun startLive() {
        val key = secure.geminiApiKey.ifBlank { apiKey.trim() }
        if (key.isBlank()) {
            status = "Add your Gemini API key first."
            return
        }
        val personaSuffix = Personas.firstOrNull { it.first == persona }?.second.orEmpty()
        val prompt = if (personaSuffix.isBlank()) settings.systemPrompt
        else settings.systemPrompt + "\nPersona: $personaSuffix"
        status = "Connecting..."
        val client = GeminiLiveClient(key, settings.model, voice, prompt) { event ->
            // Events may arrive off the main thread; handle them on the UI thread.
            scope.launch {
            when (event) {
                is LiveEvent.Connected -> {
                    connected = true
                    speaking = false
                    status = "Connected — listening"
                }
                is LiveEvent.Disconnected -> {
                    connected = false
                    speaking = false
                    status = "Not connected"
                }
                is LiveEvent.SetupComplete -> {
                    status = "Ready — speak now"
                }
                is LiveEvent.UserTranscript -> {
                    appendTranscript("user", event.text, event.isFinal)
                }
                is LiveEvent.AgentTranscript -> {
                    speaking = !event.isFinal
                    appendTranscript("agent", event.text, event.isFinal)
                    if (event.isFinal) speaking = false
                }
                is LiveEvent.AgentAudio -> {
                    speaking = true
                    player.playPcm(event.bytes)
                }
                is LiveEvent.Interrupted -> {
                    speaking = false
                    player.stop()
                }
                is LiveEvent.Error -> {
                    status = "Connection error — please try again."
                }
            }
            }
        }
        liveClient = client
        try {
            client.connect()
            val capture = MicCapture(context) { pcm -> client.sendAudioPcm16(pcm) }
            mic = capture
            capture.start()
        } catch (e: Exception) {
            status = "Could not start: ${e.message}"
            stopLive()
        }
    }

    val orbRadius by animateFloatAsState(
        targetValue = when {
            speaking -> 92f
            connected -> 76f
            else -> 58f
        },
        animationSpec = tween(durationMillis = 500),
        label = "orb"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SoftCard {
            Text("Gemini API key", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    secure.geminiApiKey = apiKey.trim()
                    keyStatus = "Key saved on this device."
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    val key = apiKey.trim()
                    if (key.isBlank()) {
                        keyStatus = "Enter a key first."
                        return@OutlinedButton
                    }
                    keyStatus = "Checking key..."
                    scope.launch {
                        val result = GeminiRestClient(key).testKey()
                        keyStatus = result.fold(
                            onSuccess = { models -> "Key OK — ${models.size} models available." },
                            onFailure = { e -> "Key check failed: ${e.message}" }
                        )
                    }
                }) { Text("Test key") }
            }
            if (keyStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(keyStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                    Canvas(modifier = Modifier.size(200.dp)) {
                        drawCircle(
                            color = Color(0xFFB08D57).copy(alpha = 0.25f),
                            radius = orbRadius * 1.35f,
                            center = center
                        )
                        drawCircle(
                            color = Color(0xFFB08D57),
                            radius = orbRadius,
                            center = center
                        )
                    }
                    Text(
                        text = when {
                            speaking -> "Speaking"
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
                }
            }
        }
    }
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
    var openRouterKey by remember { mutableStateOf(secure.openRouterApiKey) }
    var groqKey by remember { mutableStateOf(secure.groqApiKey) }
    var notePackage by remember { mutableStateOf(settings.noteAppPackage) }
    var savedNote by remember { mutableStateOf("") }

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
            Text("Provider keys", fontFamily = HeadingFont, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
            Text(
                "Your Gemini key is set on the Live tab.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
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
            Text("Live Agent 1.0.0", fontSize = 15.sp)
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
// More tab
// ---------------------------------------------------------------------------

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    val rows = listOf(
        Triple("settings", "Settings", Icons.Filled.Settings),
        Triple("terminal", "Terminal", Icons.Filled.Terminal),
        Triple("routines", "Routines", Icons.Filled.Schedule),
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
