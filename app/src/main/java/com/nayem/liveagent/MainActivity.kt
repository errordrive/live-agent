package com.nayem.liveagent

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nayem.liveagent.ui.AgentScreen
import com.nayem.liveagent.ui.AuditScreen
import com.nayem.liveagent.ui.HeadingFont
import com.nayem.liveagent.ui.LiveAgentTheme
import com.nayem.liveagent.ui.LiveScreen
import com.nayem.liveagent.ui.McpScreen
import com.nayem.liveagent.ui.MemoryScreen
import com.nayem.liveagent.ui.MoreScreen
import com.nayem.liveagent.ui.RoutinesScreen
import com.nayem.liveagent.ui.ScreenControlScreen
import com.nayem.liveagent.ui.SettingsScreen
import com.nayem.liveagent.ui.SkillsScreen
import com.nayem.liveagent.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiveAgentTheme {
                MainScreen()
            }
        }
    }
}

private data class Tab(val id: String, val label: String, val icon: ImageVector)

private val MainTabs = listOf(
    Tab("live", "Live", Icons.Filled.Mic),
    Tab("tasks", "Tasks", Icons.Filled.Assignment),
    Tab("skills", "Skills", Icons.Filled.Extension),
    Tab("memory", "Memory", Icons.Filled.Psychology),
    Tab("more", "More", Icons.Filled.MoreHoriz)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var tab by remember { mutableStateOf("live") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Permissions are best-effort; the Live tab explains if mic is missing. */ }

    LaunchedEffect(Unit) {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    val bottomSelected = if (tab in MainTabs.map { it.id }) tab else "more"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Live Agent",
                        fontFamily = HeadingFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTabs.forEach { item ->
                    NavigationBarItem(
                        selected = bottomSelected == item.id,
                        onClick = { tab = item.id },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(padding)
        ) {
            when (tab) {
                "live" -> LiveScreen(onOpenSettings = { tab = "settings" })
                "tasks" -> AgentScreen()
                "skills" -> SkillsScreen()
                "memory" -> MemoryScreen()
                "more" -> MoreScreen(onNavigate = { tab = it })
                "settings" -> SettingsScreen(onBack = { tab = "more" })
                "terminal" -> TerminalScreen(onBack = { tab = "more" })
                "routines" -> RoutinesScreen(onBack = { tab = "more" })
                "mcp" -> McpScreen(onBack = { tab = "more" })
                "screen" -> ScreenControlScreen(onBack = { tab = "more" })
                "audit" -> AuditScreen(onBack = { tab = "more" })
                else -> LiveScreen(onOpenSettings = { tab = "settings" })
            }
        }
    }
}
