package io.yiner.browser.ui.arc

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面尺寸与颜色，取自 Android Police 2024-10-04 发布的 Arc Search Android 公开 beta 截图
 * （六张参考图，原图 1116×2484 px）的像素测量，见 docs/arc-search-android-ui-references.md。
 *
 * 截图没有给出设备型号，px→dp 采用估算密度 [PX_PER_DP]（假设逻辑宽度约 411dp），
 * 因此所有 dp/sp 都是约值（±5%），不是官方规范。
 * 每个值后注明来源图片与原图像素。
 */
object ArcTokens {
    const val PX_PER_DP = 2.715f

    // ---- 通用色 ----
    /** 底栏、菜单面板、搜索面板底色。01/02/03：#ECECEE */
    val PanelGray = Color(0xFFECECEE)
    /** 底栏上沿分隔线。01：y=2256–2259，#D3D3D5，约 1dp */
    val BarDivider = Color(0xFFD3D3D5)
    /** 菜单中“拦截计数”上方分隔线。02：y=2118，#E3E3E5 */
    val MenuDivider = Color(0xFFE3E3E5)
    /** 菜单四宫格的格子底色。02：#E0E0E2–#E5E5E7 */
    val TileGray = Color(0xFFE3E3E5)
    val TextPrimary = Color(0xFF0D0D0F)
    /** 次要文字。02 “Problems? …”：#747476 */
    val TextSecondary = Color(0xFF747476)
    /** 搜索框占位符。03：#7F7F7F */
    val Placeholder = Color(0xFF7F7F7F)
    /** 设置页分组标题。05/06：#686B7E */
    val SectionHeader = Color(0xFF686B7E)
    /** 开关开启色。05：#6858FF */
    val SwitchOn = Color(0xFF6858FF)
    /** 选中对勾。06：#5C4CD0 */
    val Check = Color(0xFF5C4CD0)
    /** 页面菜单遮罩。02：白色网页被压到 #8C8D8F，约 45% 黑 */
    val MenuScrim = Color.Black.copy(alpha = 0.45f)

    /** 搜索面板背后的色调遮罩。03：左 #D8D3E7（淡紫），右 #F8DFE3（淡粉），网页内容仍隐约可见 */
    val SearchScrim = Brush.horizontalGradient(
        // 第一版用 #D8D3E7/#F8DFE3 @0.86 太不透明（对照时网页几乎看不见）。
        // 按白底→#D8D3E7、深色字→约 #746471 反推：不透明度约 0.55，左 #B8AFD3、右 #F2C5CC
        listOf(Color(0xFFB8AFD3).copy(alpha = 0.55f), Color(0xFFF2C5CC).copy(alpha = 0.55f)),
    )

    /** 标签切换背景。04：左上 #BDB5DA、右上 #FECFD7、中部近白、底部 #D6E1FD */
    val SwitcherBackground = Brush.linearGradient(
        0.0f to Color(0xFFBDB5DA),
        0.35f to Color(0xFFF6E6EE),
        0.55f to Color(0xFFF7F4FA),
        1.0f to Color(0xFFD6E1FD),
    )
    val SwitcherTopRight = Color(0xFFFECFD7)

    /** 设置页背景。05/06：顶部左 #C0BADE、右 #F7E3EC，约到半屏（y≈1300px）过渡为白色 */
    val SettingsTopLeft = Color(0xFFC0BADE)
    val SettingsTopRight = Color(0xFFF7E3EC)

    // ---- 网页底栏（01）----
    /** 白色胶囊“+”：x 428–687、y 2297–2439 px → 约 96×52dp */
    val BarPillWidth = 96.dp
    val BarPillHeight = 52.dp
    /** 分隔线到胶囊顶：2259→2297 px ≈ 14dp */
    val BarTopPadding = 14.dp
    /** “+”号：46 px ≈ 17dp */
    val BarPlusSize = 18.dp
    /** 叠放的标签缩略图：整体 133×129 px ≈ 49×48dp；中心 x=186 px（屏宽 1/6） */
    val BarThumbBox = 52.dp
    /** 胶囊底部到屏幕底：2439→2484 px ≈ 16dp（01、04 一致）；手势导航时用这个值代替完整导航栏高度 */
    val BarBottomGap = 16.dp
    /** 右侧“^”：38×22 px ≈ 14×8dp；中心 x=930 px（屏宽 5/6） */
    val BarChevronWidth = 14.dp

    // ---- 页面菜单（02）----
    /** 面板顶部圆角：x=5 处起点 y=960，中心 y=888 → 约 26dp */
    val SheetCorner = 26.dp
    /** 左右内边距：59 px ≈ 22dp */
    val SheetHPadding = 22.dp
    /** 面板顶到地址胶囊：888→947 px ≈ 22dp */
    val SheetTopPadding = 22.dp
    /** 地址胶囊：y 947–1102 px ≈ 57dp */
    val AddressPillHeight = 57.dp
    /** 胶囊到四宫格：1102→1166 px ≈ 24dp；四宫格高 1166–1424 px ≈ 95dp；格间约 8 px ≈ 3dp */
    val TilesTopGap = 24.dp
    val TilesHeight = 95.dp
    val TilesGap = 3.dp
    /** 列表行距：1600→1802→1996 px ≈ 73dp；图标中心 x≈115 px ≈ 42dp；文字起点 x≈211 px ≈ 78dp */
    val MenuRowHeight = 73.dp
    val MenuRowTextStart = 78.dp
    val MenuTitleSize = 19.sp
    val MenuSubtitleSize = 15.sp
    val TileLabelSize = 15.sp
    val HostTextSize = 19.sp

    // ---- 搜索面板（03）----
    /** 面板顶到搜索胶囊：864→923 px ≈ 22dp；胶囊高 923–1115 px ≈ 69dp（比菜单里的地址胶囊高） */
    val SearchPillHeight = 69.dp
    val SearchTextSize = 20.sp
    /** 建议行：行距 1259→1438 px ≈ 66dp；网站图标 75 px ≈ 28dp；文字起点 x≈190 px ≈ 70dp */
    val SuggestionRowHeight = 66.dp
    val SuggestionIcon = 28.dp
    val SuggestionTextStart = 70.dp

    // ---- 标签切换（04）----
    /** 当前卡片：x 205–909、y 579–1916 px ≈ 259×492dp；圆角约 62 px ≈ 23dp；相邻卡片间隔约 53 px ≈ 20dp */
    val CardWidth = 259.dp
    val CardHeight = 492.dp
    val CardCorner = 23.dp
    val CardGap = 20.dp
    /** 卡片上方标题：网站图标 80 px ≈ 29dp；标题文字约 20sp；标题中心到卡片顶约 24dp */
    val CardFavicon = 29.dp
    val CardTitleSize = 20.sp
    val CardTitleGap = 24.dp
    /** 底部“+”胶囊：x 429–685、y 2304–2432 px ≈ 94×47dp；齿轮 59 px ≈ 22dp，中心 x=930 px */
    val SwitcherPillWidth = 94.dp
    val SwitcherPillHeight = 47.dp
    val GearSize = 22.dp

    // ---- 设置（05/06）----
    /** 标题约 24sp，#0D1015；返回箭头中心 x≈90 px ≈ 33dp；标题起点 x≈183 px ≈ 67dp */
    val SettingsTitleSize = 24.sp
    val SettingsTitleColor = Color(0xFF0D1015)
    val SettingsTitleStart = 67.dp
    /** 分组标题约 18sp，左边距 x≈52 px ≈ 19dp */
    val SectionHeaderSize = 18.sp
    val SectionHeaderStart = 19.dp
    /**
     * 列表项：单行行距约 182 px ≈ 67dp；图标可见部分 63 px ≈ 23dp（自绘图标线条约占视口 3/4，故绘制尺寸取 30dp），
     * 中心 x≈128 px ≈ 47dp；文字起点 x≈222 px ≈ 82dp（第一版误用了标题起点 66dp，对照后修正）；文字约 20sp
     */
    val SettingsRowHeight = 67.dp
    val SettingsIcon = 30.dp
    val SettingsIconCenter = 47.dp
    val SettingsTextStart = 82.dp
    /** 标题中心在状态栏下约 166 px ≈ 61dp */
    val SettingsTitleCenter = 61.dp
    val SettingsItemSize = 20.sp
    val SettingsSubtitleSize = 15.sp
    /** 搜索引擎图标 99 px ≈ 36dp；行距约 182 px ≈ 67dp；对勾 55×41 px ≈ 20dp，中心距右约 49dp */
    val EngineIcon = 36.dp
    val EngineRowHeight = 67.dp
}
