package io.yiner.browser.ui.arc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yiner.browser.ui.arc.ArcTokens as T

/** 无痕标签的区分色（截图中没有无痕界面证据，这是银耳浏览器自己的设计）。 */
val PrivateTint: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalArcPalette.current.privateTint

// ============================================================ 网页底栏（参考图 01）

/**
 * 浏览网页时的底栏：左侧叠放的标签缩略图、中间白色胶囊“+”、右侧“^”展开菜单。
 * 三个控件分别位于屏宽 1/6、1/2、5/6 处（01：中心 x=186、558、930 px）。
 */
@Composable
fun ArcBottomBar(
    current: ImageBitmap?,
    behind: ImageBitmap?,
    tabCount: Int,
    isPrivate: Boolean,
    progress: Float?,
    onTabs: () -> Unit,
    onPlus: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(T.PanelGray)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(T.BarDivider)) {
            if (progress != null) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(T.SwitchOn))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = T.BarTopPadding).height(T.BarPillHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onTabs), contentAlignment = Alignment.Center) {
                TabStack(current, behind, tabCount, isPrivate)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                WhitePill(T.BarPillWidth, T.BarPillHeight, onPlus) {
                    Icon(ArcIcons.Plus, "新建", Modifier.size(T.BarPlusSize), tint = T.TextPrimary)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onExpand), contentAlignment = Alignment.Center) {
                Icon(ArcIcons.ChevronUpBold, "展开菜单", Modifier.size(22.dp), tint = T.TextPrimary)
            }
        }
        Spacer(Modifier.height(bottomGap()))
    }
}

/** 两张略微错开、旋转的标签缩略图（01：深色卡在后、浅色卡在前并向右旋转）。 */
@Composable
fun TabStack(current: ImageBitmap?, behind: ImageBitmap?, tabCount: Int, isPrivate: Boolean) {
    Box(Modifier.size(T.BarThumbBox), contentAlignment = Alignment.Center) {
        if (tabCount > 1) {
            MiniCard(behind, Modifier.offset((-7).dp, 6.dp).rotate(-14f), isPrivate, dark = behind == null)
        }
        MiniCard(current, Modifier.offset(6.dp, (-4).dp).rotate(12f), isPrivate)
    }
}

@Composable
private fun MiniCard(bitmap: ImageBitmap?, modifier: Modifier, isPrivate: Boolean, dark: Boolean = false) {
    val shape = RoundedCornerShape(5.dp)
    // 01：两张卡片合计约 49×48dp，单张约 30×37dp
    Box(
        modifier.size(30.dp, 37.dp).shadow(1.5.dp, shape).clip(shape).background(if (dark) LocalArcPalette.current.miniCardBack else LocalArcPalette.current.miniCardFront)
            .border(1.dp, if (isPrivate) PrivateTint else T.TextPrimary.copy(alpha = 0.13f), shape),
    ) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) { Box(Modifier.fillMaxWidth(if (it == 3) 0.6f else 1f).height(2.dp).background(LocalArcPalette.current.miniCardLine)) }
            }
        }
    }
}

@Composable
fun WhitePill(width: Dp, height: Dp, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(width, height).clip(CircleShape).background(T.Pill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ============================================================ 底部面板（02、03）

/** 浅灰色、顶部大圆角的底部面板。 */
@Composable
fun ArcSheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = T.SheetCorner, topEnd = T.SheetCorner))
            .background(T.PanelGray),
        content = content,
    )
}

/**
 * 菜单顶部的地址胶囊（02）：后退、前进、域名（省略号截断）、链接、刷新。
 * 点击域名进入地址编辑；链接按钮复制当前网址（截图未说明其功能，这里是银耳浏览器的选择）。
 */
@Composable
fun AddressPill(
    host: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHost: () -> Unit,
    onCopyLink: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = T.SheetHPadding).height(T.AddressPillHeight)
            .clip(CircleShape).background(T.Pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIcon(ArcIcons.ChevronLeft, "后退", canGoBack, onBack)
        PillIcon(ArcIcons.ChevronRight, "前进", canGoForward, onForward)
        Text(
            host.ifBlank { "新标签页" },
            Modifier.weight(1f).clickable(onClick = onHost).padding(horizontal = 6.dp),
            fontSize = T.HostTextSize, color = T.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        PillIcon(ArcIcons.Link, "复制链接", true, onCopyLink)
        PillIcon(if (loading) ArcIcons.Close else ArcIcons.Refresh, if (loading) "停止" else "刷新", true, onRefresh)
    }
}

@Composable
private fun PillIcon(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp, T.AddressPillHeight).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, Modifier.size(24.dp), tint = if (enabled) T.TextPrimary else T.TextPrimary.copy(alpha = 0.35f))
    }
}

data class TileItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** 一组并排的方块按钮（02：Translate/Pin/Find/Share 四格，整体大圆角，格间细缝）。 */
@Composable
fun ActionTiles(items: List<TileItem>) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = T.SheetHPadding).height(T.TilesHeight)
            .clip(RoundedCornerShape(T.SheetCorner)),
        horizontalArrangement = Arrangement.spacedBy(T.TilesGap),
    ) {
        items.forEach { item ->
            Column(
                Modifier.weight(1f).fillMaxHeight().background(T.TileGray).clickable(onClick = item.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, null, Modifier.size(30.dp), tint = T.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text(item.label, fontSize = T.TileLabelSize, color = T.TextPrimary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** 菜单列表行（02：图标 + 标题/副标题 + 右侧控件，行高约 73dp）。 */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(T.MenuRowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(T.MenuRowTextStart), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = T.TextPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.MenuTitleSize, color = T.TextPrimary)
            if (subtitle != null) Text(subtitle, fontSize = T.MenuSubtitleSize, color = T.TextPrimary.copy(alpha = 0.8f))
        }
        if (trailing != null) Box(Modifier.padding(end = 18.dp)) { trailing() }
    }
}

/** 白色胶囊搜索框（03：高约 69dp，占位符灰色）。 */
@Composable
fun SearchPill(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onGo: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = T.SheetHPadding).height(T.SearchPillHeight)
            .clip(CircleShape).background(T.Pill).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) Text(placeholder, fontSize = T.SearchTextSize, color = T.Placeholder, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(fontSize = T.SearchTextSize, color = T.TextPrimary),
                cursorBrush = SolidColor(LocalArcPalette.current.cursor),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
            )
        }
        if (trailing != null) trailing()
    }
}

/** 网站图标位：取站点首字母画在圆角方块里（不加载第三方网站或品牌的图标）。 */
@Composable
fun SiteBadge(label: String, size: Dp, isPrivate: Boolean = false, white: Boolean = false) {
    val letter = label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '·'
    val hue = (label.lowercase().hashCode().let { if (it < 0) -it else it } % 360).toFloat()
    val dark = LocalArcPalette.current.isDark
    val bg = when {
        white -> T.Pill
        dark -> Color.hsl(hue, 0.30f, 0.24f)
        else -> Color.hsl(hue, 0.45f, 0.88f)
    }
    val fg = when {
        isPrivate -> PrivateTint
        dark -> Color.hsl(hue, 0.60f, 0.80f)
        else -> Color.hsl(hue, 0.55f, 0.32f)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), color = fg, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
    }
}

/** 搜索面板中的一行建议（03：左侧网站图标，文字单行省略）。 */
@Composable
fun SuggestionRow(
    text: String,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(T.SuggestionRowHeight).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(T.SuggestionTextStart), contentAlignment = Alignment.Center) {
            Box(Modifier.offset(x = (-4).dp)) { leading() }
        }
        Text(
            text, Modifier.weight(1f).padding(end = 12.dp), fontSize = T.SearchTextSize, color = T.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) Box(Modifier.padding(end = 18.dp)) { trailing() }
    }
}

// ============================================================ 标签卡片（04）

/** 标签卡片上方的网站图标 + 标题（04：白底圆角图标，标题灰色单行）。 */
@Composable
fun CardTitle(title: String, host: String, isPrivate: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        SiteBadge(host.ifBlank { title }, T.CardFavicon, isPrivate, white = true)
        Spacer(Modifier.width(12.dp))
        if (isPrivate) {
            Text("无痕 ", fontSize = T.CardTitleSize, color = PrivateTint, fontWeight = FontWeight.SemiBold)
        }
        Text(
            title, fontSize = T.CardTitleSize, color = LocalArcPalette.current.cardTitle,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 大圆角的网页预览卡片。没有缩略图时显示标题与域名。 */
@Composable
fun TabCardBody(thumbnail: ImageBitmap?, title: String, host: String, isPrivate: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(T.CardCorner)
    Box(
        modifier.shadow(6.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(shape).background(LocalArcPalette.current.cardSurface)
            .then(
                when {
                    isPrivate -> Modifier.border(2.dp, PrivateTint, shape)
                    // 深色下卡片与背景亮度接近，加一圈淡描边勾出轮廓
                    LocalArcPalette.current.isDark -> Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                    else -> Modifier
                },
            ),
    ) {
        if (thumbnail != null) {
            Image(thumbnail, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(title, fontSize = 22.sp, color = T.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(host, fontSize = 15.sp, color = T.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 标签切换底部：中间白色胶囊“+”、右侧齿轮（04）。 */
@Composable
fun SwitcherBottomRow(onPlus: () -> Unit, onSettings: () -> Unit, leading: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = T.BarTopPadding).height(T.BarPillHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { leading() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                WhitePill(T.SwitcherPillWidth, T.SwitcherPillHeight, onPlus) {
                    Icon(ArcIcons.Plus, "新建", Modifier.size(T.BarPlusSize), tint = T.TextPrimary)
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSettings), contentAlignment = Alignment.Center) {
                // 04 中是实心齿轮；使用 Material 图标库（Apache-2.0）中的通用齿轮
                Icon(Icons.Filled.Settings, "设置", Modifier.size(T.GearSize + 6.dp), tint = T.TextPrimary)
            }
        }
        Spacer(Modifier.height(bottomGap()))
    }
}

// ============================================================ 设置（05、06）

/**
 * 底部胶囊下方的留白：手势导航（导航栏不高于 32dp）时按参考图固定 16dp，让胶囊贴近手势条；
 * 三键导航时让出完整的导航栏高度，避免与系统按键重叠。
 */
@Composable
fun bottomGap(): Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return if (nav <= 32.dp) T.BarBottomGap else nav
}

@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(T.SettingsBase)) {
        // 两层渐变叠加：上半屏左紫右粉，向下淡出为白色。两层高度必须一致，否则会出现接缝（第一版的问题）
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.6f)) {
            Box(
                Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(T.SettingsTopLeft, T.SettingsTopRight)))
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.15f to Color.Transparent, 1f to T.SettingsBase)),
            )
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height(T.SettingsTitleCenter - 41.dp))
            Row(Modifier.fillMaxWidth().height(82.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(T.SettingsTitleStart).fillMaxHeight().clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(ArcIcons.ArrowBack, "返回", Modifier.size(26.dp), tint = T.SettingsTitleColor)
                }
                Text(title, fontSize = T.SettingsTitleSize, color = T.SettingsTitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text, Modifier.padding(start = T.SectionHeaderStart, top = 20.dp, bottom = 14.dp),
        fontSize = T.SectionHeaderSize, color = T.SectionHeader, fontWeight = FontWeight.Medium,
    )
}

/** 设置项（05：细线图标、无分割线、文字约 20sp，可带副标题与右侧开关）。 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = if (subtitle != null) 10.dp else 0.dp)
            .then(if (subtitle == null) Modifier.height(T.SettingsRowHeight) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(T.SettingsTextStart), contentAlignment = Alignment.CenterStart) {
            if (icon != null) {
                Icon(icon, null, Modifier.padding(start = T.SettingsIconCenter - T.SettingsIcon / 2).size(T.SettingsIcon), tint = T.TextPrimary)
            }
        }
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = T.SettingsItemSize, color = T.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, fontSize = T.SettingsSubtitleSize, color = T.TextPrimary.copy(alpha = 0.8f))
            }
        }
        if (trailing != null) Box(Modifier.padding(end = 20.dp)) { trailing() }
    }
}

@Composable
fun ArcSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = T.SwitchOn, checkedThumbColor = Color.White, checkedBorderColor = T.SwitchOn,
            uncheckedTrackColor = LocalArcPalette.current.switchOffTrack, uncheckedThumbColor = LocalArcPalette.current.switchOffThumb,
            uncheckedBorderColor = LocalArcPalette.current.switchOffThumb,
        ),
    )
}

/** 单选列表行（06：左侧方形图标、右侧对勾）。 */
@Composable
fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(T.EngineRowHeight).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(T.SettingsTextStart), contentAlignment = Alignment.CenterStart) {
            // 06：图标左缘 x≈90 px ≈ 33dp
            Box(Modifier.padding(start = 33.dp)) { leading() }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontSize = T.SettingsItemSize, color = T.TextPrimary)
        if (selected) Icon(ArcIcons.Check, "已选择", Modifier.padding(end = 38.dp).size(24.dp), tint = T.Check)
    }
}

@Composable
fun SettingsNote(text: String) {
    Text(
        text, Modifier.padding(start = T.SettingsTextStart, end = 24.dp, bottom = 8.dp),
        fontSize = 14.sp, color = T.TextSecondary,
    )
}
