package io.yiner.browser.ui.arc

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 界面色板。
 * - [Light]：取自 Arc Search Android beta 截图的像素测量（见 ArcTokens 注释），少数文字色为满足对比度略作加深；
 * - [Dark]：参考截图中没有深色界面，深色色板是银耳浏览器自己的设计，保持同样的层次关系
 *   （网页 > 底栏/面板 > 胶囊/格子），所有文字与背景的对比度由单元测试检查。
 */
@Immutable
data class ArcPalette(
    val isDark: Boolean,
    /** 浏览界面、窗口的底色。 */
    val window: Color,
    val panel: Color,
    val barDivider: Color,
    val menuDivider: Color,
    val tile: Color,
    /** 白色胶囊（“+”、地址栏、搜索框）在深色下的对应色。 */
    val pill: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val placeholder: Color,
    val sectionHeader: Color,
    val switchOn: Color,
    val switchOffTrack: Color,
    val switchOffThumb: Color,
    val check: Color,
    val menuScrim: Color,
    val searchScrimLeft: Color,
    val searchScrimRight: Color,
    val searchScrimAlpha: Float,
    val switcherTopLeft: Color,
    val switcherUpperMid: Color,
    val switcherMid: Color,
    val switcherBottom: Color,
    val switcherTopRight: Color,
    val settingsTopLeft: Color,
    val settingsTopRight: Color,
    /** 设置页渐变淡出后的底色。 */
    val settingsBase: Color,
    val settingsTitle: Color,
    val cardSurface: Color,
    val cardTitle: Color,
    val miniCardFront: Color,
    val miniCardBack: Color,
    val miniCardLine: Color,
    val privateTint: Color,
    val cursor: Color,
) {
    companion object {
        val Light = ArcPalette(
            isDark = false,
            window = Color.White,
            panel = Color(0xFFECECEE), // 01/02/03
            barDivider = Color(0xFFD3D3D5), // 01
            menuDivider = Color(0xFFE3E3E5), // 02
            tile = Color(0xFFE3E3E5), // 02
            pill = Color.White,
            textPrimary = Color(0xFF0D0D0F),
            // 02 测得 #747476；在 #ECECEE 上对比度仅约 4.0，为达到 4.5 略加深
            textSecondary = Color(0xFF68686B),
            placeholder = Color(0xFF7F7F7F), // 03（白底上约 4.0，提示文字，按 ≥3 检查）
            // 05/06 测得 #686B7E；放在最深的渐变色 #C0BADE 上对比度仅约 3.3（测试发现），加深到 4.6
            sectionHeader = Color(0xFF484B5E),
            switchOn = Color(0xFF6858FF), // 05
            switchOffTrack = Color(0xFFE4E3EA),
            switchOffThumb = Color(0xFF8E8C99),
            check = Color(0xFF5C4CD0), // 06
            menuScrim = Color.Black.copy(alpha = 0.45f), // 02
            searchScrimLeft = Color(0xFFB8AFD3), // 03 反推
            searchScrimRight = Color(0xFFF2C5CC),
            searchScrimAlpha = 0.55f,
            switcherTopLeft = Color(0xFFBDB5DA), // 04
            switcherUpperMid = Color(0xFFF6E6EE),
            switcherMid = Color(0xFFF7F4FA),
            switcherBottom = Color(0xFFD6E1FD),
            switcherTopRight = Color(0xFFFECFD7),
            settingsTopLeft = Color(0xFFC0BADE), // 05/06
            settingsTopRight = Color(0xFFF7E3EC),
            settingsBase = Color.White,
            settingsTitle = Color(0xFF0D1015),
            cardSurface = Color.White,
            // 04 中标题为半透明灰紫；在最深的渐变色 #BDB5DA 上需加深才能达到 4.5（测试发现）
            cardTitle = Color(0xFF4A3F4E),
            miniCardFront = Color.White,
            miniCardBack = Color(0xFF2B2D33),
            miniCardLine = Color(0xFFCFCFD3),
            privateTint = Color(0xFF6E5A9C),
            cursor = Color(0xFF3A5BFF),
        )

        val Dark = ArcPalette(
            isDark = true,
            window = Color(0xFF121215),
            panel = Color(0xFF1C1C20),
            barDivider = Color(0xFF34343A),
            menuDivider = Color(0xFF2E2E34),
            tile = Color(0xFF2A2A30),
            pill = Color(0xFF2E2E35),
            textPrimary = Color(0xFFEDEDF0),
            textSecondary = Color(0xFFA9A9B1),
            placeholder = Color(0xFF8E8E97),
            sectionHeader = Color(0xFFABADC2),
            switchOn = Color(0xFF8F82FF),
            switchOffTrack = Color(0xFF3A3A42),
            switchOffThumb = Color(0xFF9A98A6),
            check = Color(0xFFAA9EFF),
            menuScrim = Color.Black.copy(alpha = 0.6f),
            searchScrimLeft = Color(0xFF1E1A32),
            searchScrimRight = Color(0xFF2E1A26),
            searchScrimAlpha = 0.72f,
            switcherTopLeft = Color(0xFF2A2442),
            switcherUpperMid = Color(0xFF1F1B29),
            switcherMid = Color(0xFF17161C),
            switcherBottom = Color(0xFF161D31),
            switcherTopRight = Color(0xFF3F2236),
            settingsTopLeft = Color(0xFF2B2744),
            settingsTopRight = Color(0xFF3A2835),
            settingsBase = Color(0xFF121215),
            settingsTitle = Color(0xFFEFEFF3),
            // 第一版 #1F1F24 与标签切换背景中部 #17161C 太接近，离线渲染中卡片边缘几乎看不见；提亮并配合细描边
            cardSurface = Color(0xFF28282F),
            cardTitle = Color(0xFFC9C3D6),
            miniCardFront = Color(0xFF3A3A42),
            miniCardBack = Color(0xFF0E0E11),
            miniCardLine = Color(0xFF5A5A63),
            privateTint = Color(0xFFBBA8F2),
            cursor = Color(0xFF93A6FF),
        )
    }
}

val LocalArcPalette = staticCompositionLocalOf { ArcPalette.Light }
