package io.yiner.browser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.yiner.browser.ui.arc.ArcPalette
import io.yiner.browser.ui.arc.LocalArcPalette
import androidx.compose.ui.graphics.Color

/** 银耳配色：象牙白底 + 琥珀金点缀；无痕模式使用深紫灰。 */
private val Light = lightColorScheme(
    primary = Color(0xFFA8691A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E3C4),
    onPrimaryContainer = Color(0xFF3A2405),
    secondary = Color(0xFF7A6A55),
    background = Color(0xFFFBF6EE),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFCF7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF1E8DA),
    onSurfaceVariant = Color(0xFF51463A),
    surfaceContainer = Color(0xFFF4ECDF),
    surfaceContainerHigh = Color(0xFFEFE5D5),
    outline = Color(0xFFD6C7B1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE2A85A),
    onPrimary = Color(0xFF3A2405),
    primaryContainer = Color(0xFF5A3D12),
    onPrimaryContainer = Color(0xFFF6E3C4),
    secondary = Color(0xFFD3C3AC),
    background = Color(0xFF17140F),
    onBackground = Color(0xFFECE1D3),
    surface = Color(0xFF1E1A14),
    onSurface = Color(0xFFECE1D3),
    surfaceVariant = Color(0xFF2B251D),
    onSurfaceVariant = Color(0xFFD3C3AC),
    surfaceContainer = Color(0xFF241F18),
    surfaceContainerHigh = Color(0xFF2E281F),
    outline = Color(0xFF5A4F41),
)

/**
 * 应用主题。[dark] 由设置中的外观（跟随系统 / 浅色 / 深色）与系统当前外观共同决定，
 * 同时提供 Material 配色（对话框、按钮等）与 Arc 风格界面色板 [ArcPalette]。
 */
@Composable
fun YinerTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        CompositionLocalProvider(LocalArcPalette provides if (dark) ArcPalette.Dark else ArcPalette.Light, content = content)
    }
}
