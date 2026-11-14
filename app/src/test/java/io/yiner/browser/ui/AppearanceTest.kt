package io.yiner.browser.ui

import androidx.compose.ui.graphics.Color
import io.yiner.browser.core.ThemeMode
import io.yiner.browser.ui.arc.ArcPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeModeTest {
    @Test fun defaultIsFollowSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.DEFAULT)
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromId("未知值"))
    }

    @Test fun followSystemTracksSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test fun manualChoiceIgnoresSystem() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test fun persistedIdsAreStable() {
        // 已写入 SharedPreferences 的值，改名会让用户的选择失效
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.id })
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromId(it.id)) }
    }
}

/** WCAG 2.x 对比度：正文 ≥ 4.5；输入框占位提示与非文字控件 ≥ 3。 */
class PaletteContrastTest {
    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun check(p: ArcPalette, name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertTrue("${if (p.isDark) "深色" else "浅色"} $name 对比度 ${"%.2f".format(r)} < $min", r >= min)
    }

    private fun checkPalette(p: ArcPalette) {
        for ((bgName, bg) in listOf("面板" to p.panel, "胶囊" to p.pill, "窗口" to p.window, "格子" to p.tile, "卡片" to p.cardSurface, "设置底色" to p.settingsBase)) {
            check(p, "主要文字/$bgName", p.textPrimary, bg, 4.5)
        }
        check(p, "次要文字/面板", p.textSecondary, p.panel, 4.5)
        check(p, "次要文字/设置底色", p.textSecondary, p.settingsBase, 4.5)
        check(p, "占位提示/胶囊", p.placeholder, p.pill, 3.0)
        for ((bgName, bg) in listOf("左上" to p.settingsTopLeft, "右上" to p.settingsTopRight, "底色" to p.settingsBase)) {
            check(p, "设置标题/$bgName", p.settingsTitle, bg, 4.5)
            check(p, "分组标题/$bgName", p.sectionHeader, bg, 4.5)
            check(p, "主要文字/设置$bgName", p.textPrimary, bg, 4.5)
        }
        for ((bgName, bg) in listOf("左上" to p.switcherTopLeft, "右上" to p.switcherTopRight, "中上" to p.switcherUpperMid)) {
            check(p, "卡片标题/切换背景$bgName", p.cardTitle, bg, 4.5)
        }
        check(p, "无痕标记/面板", p.privateTint, p.panel, 4.5)
        check(p, "无痕标记/切换背景", p.privateTint, p.switcherUpperMid, 4.5)
        check(p, "开关/面板", p.switchOn, p.panel, 3.0)
        check(p, "对勾/设置底色", p.check, p.settingsBase, 3.0)
        check(p, "底栏分隔线/窗口", p.barDivider, p.window, 1.2)
    }

    @Test fun lightPaletteIsReadable() = checkPalette(ArcPalette.Light)

    @Test fun darkPaletteIsReadable() = checkPalette(ArcPalette.Dark)

    @Test fun darkIsActuallyDark() {
        assertTrue(luminance(ArcPalette.Dark.window) < 0.05)
        assertTrue(luminance(ArcPalette.Dark.panel) < luminance(ArcPalette.Light.panel))
    }
}
