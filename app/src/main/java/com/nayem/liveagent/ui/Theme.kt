package com.nayem.liveagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Warm ivory light / charcoal dark, bronze accent, sage secondary.
private val Ivory = Color(0xFFFAF7F2)
private val Charcoal = Color(0xFF1C1B19)
private val Bronze = Color(0xFFB08D57)
private val BronzeLight = Color(0xFFC9A876)
private val Sage = Color(0xFF7A8B6F)
private val SageLight = Color(0xFF93A488)
private val SurfaceLight = Color(0xFFFFFDF9)
private val SurfaceDark = Color(0xFF262421)
private val SurfaceVariantLight = Color(0xFFF0EBE2)
private val SurfaceVariantDark = Color(0xFF322F2C)
private val OutlineLight = Color(0xFFD8CFC0)
private val OutlineDark = Color(0xFF4A463F)

private val LightColors = lightColorScheme(
    primary = Bronze,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE0C8),
    onPrimaryContainer = Charcoal,
    secondary = Sage,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8DD),
    onSecondaryContainer = Charcoal,
    tertiary = Sage,
    background = Ivory,
    onBackground = Charcoal,
    surface = SurfaceLight,
    onSurface = Charcoal,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF5C564A),
    outline = OutlineLight,
    error = Color(0xFF9C4A2F)
)

private val DarkColors = darkColorScheme(
    primary = BronzeLight,
    onPrimary = Charcoal,
    primaryContainer = Color(0xFF4A3A22),
    onPrimaryContainer = Ivory,
    secondary = SageLight,
    onSecondary = Charcoal,
    secondaryContainer = Color(0xFF39443A),
    onSecondaryContainer = Ivory,
    tertiary = SageLight,
    background = Charcoal,
    onBackground = Ivory,
    surface = SurfaceDark,
    onSurface = Ivory,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFCFC7B8),
    outline = OutlineDark,
    error = Color(0xFFE8A58C)
)

val HeadingFont = FontFamily.Serif

@Composable
fun LiveAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}

/** A soft premium card: large 20dp corners, gentle elevation, no hard edges. */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
