package com.mosstts.edge.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

private val Light = lightColorScheme(
    primary = Color(0xFF087E8B), onPrimary = Color.White, primaryContainer = Color(0xFFDDF4F4),
    onPrimaryContainer = Color(0xFF07505A), secondary = Color(0xFF647487), secondaryContainer = Color(0xFFEAF0F6),
    background = Color(0xFFF4F7FA), surface = Color.White, surfaceVariant = Color(0xFFF0F4F7),
    onBackground = Color(0xFF172C39), onSurface = Color(0xFF172C39), onSurfaceVariant = Color(0xFF647487),
    outline = Color(0xFFABBAC5), outlineVariant = Color(0xFFE1E9EF),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF69D5DB), onPrimary = Color(0xFF00373F), primaryContainer = Color(0xFF123F47),
    onPrimaryContainer = Color(0xFFB5EDF0), secondary = Color(0xFFADBDCD), secondaryContainer = Color(0xFF263946),
    background = Color(0xFF101B23), surface = Color(0xFF192832), surfaceVariant = Color(0xFF233540),
    onBackground = Color(0xFFE5EFF5), onSurface = Color(0xFFE5EFF5), onSurfaceVariant = Color(0xFFADBDCD),
    outline = Color(0xFF6E8594), outlineVariant = Color(0xFF304651),
)

@Composable fun EdgeTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowInsetsControllerCompat(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content)
}
