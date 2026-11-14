package io.yiner.browser.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.yiner.browser.MainActivity
import io.yiner.browser.core.UrlInput
import io.yiner.browser.ui.arc.ArcTokens
import io.yiner.browser.ui.arc.CardTitle
import io.yiner.browser.ui.arc.SwitcherBottomRow
import io.yiner.browser.ui.arc.TabCardBody

/** 标签切换页中一张卡片需要的数据（与 Activity 解耦，便于离线渲染验证）。 */
data class TabCardModel(
    val id: String,
    val title: String,
    val host: String,
    val isPrivate: Boolean,
    val thumbnail: ImageBitmap?,
)

/** 标签切换的背景：04 测得左上淡紫、右上淡粉、中部近白、底部淡蓝。 */
@Composable
fun SwitcherBackdrop(modifier: Modifier = Modifier) {
    // 第一版用 320dp 高的横向渐变叠加，对照时右侧出现明显断边；改为以右上角为圆心的径向渐变
    val topRight = ArcTokens.SwitcherTopRight
    Box(
        modifier.fillMaxSize().background(ArcTokens.SwitcherBackground).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(topRight.copy(alpha = 0.85f), Color.Transparent),
                    center = Offset(size.width, 0f), radius = size.width * 1.15f,
                ),
            )
        },
    )
}

/**
 * 横向标签卡片（参考图 04）：当前卡片居中，两侧露出相邻卡片，卡片上方是网站图标与标题，
 * 底部中间白色胶囊“+”、右侧齿轮。
 * 关闭方式（上滑卡片）是银耳浏览器的设计——截图中没有关闭标签的证据。
 */
@Composable
fun TabSwitcherContent(
    cards: List<TabCardModel>,
    selectedId: String?,
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onPlus: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SwitcherBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (cards.isEmpty()) {
                    Text(
                        "没有打开的标签\n点下方“+”开始搜索",
                        Modifier.align(Alignment.Center), fontSize = 18.sp, color = ArcTokens.TextSecondary, textAlign = TextAlign.Center,
                    )
                } else {
                    val startIndex = cards.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                    val pager = rememberPagerState(initialPage = startIndex) { cards.size }
                    LaunchedEffect(cards.size) {
                        if (pager.currentPage >= cards.size) pager.scrollToPage(cards.size - 1)
                    }
                    val cardWidth = min(ArcTokens.CardWidth, maxWidth - 64.dp)
                    // 04：卡片顶部在状态栏下约 167dp；可用高度不足时等比缩短
                    val topSpace = min(167.dp, maxHeight * 0.22f)
                    val cardHeight = min(ArcTokens.CardHeight, maxHeight - topSpace - 12.dp)
                    HorizontalPager(
                        state = pager,
                        contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2),
                        pageSpacing = ArcTokens.CardGap,
                        modifier = Modifier.fillMaxSize(),
                        key = { cards[it].id },
                    ) { page ->
                        val card = cards[page]
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Spacer(Modifier.height(topSpace - ArcTokens.CardFavicon - ArcTokens.CardTitleGap / 2 - 6.dp))
                            CardTitle(card.title, card.host, card.isPrivate, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                            // 04：标题中心到卡片顶约 100 px ≈ 37dp
                            Spacer(Modifier.height(ArcTokens.CardTitleGap / 2 + 10.dp))
                            SwipeToClose(onClose = { onClose(card.id) }) {
                                TabCardBody(
                                    card.thumbnail, card.title, card.host, card.isPrivate,
                                    Modifier.size(cardWidth, cardHeight).clickable { onOpen(card.id) },
                                )
                            }
                        }
                    }
                }
            }
            SwitcherBottomRow(onPlus = onPlus, onSettings = onSettings)
        }
    }
}

/** 上滑超过一定距离关闭卡片；松手未达阈值则回弹。 */
@Composable
private fun SwipeToClose(onClose: () -> Unit, content: @Composable () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    val shown by animateFloatAsState(drag, label = "swipe")
    Box(
        Modifier
            .graphicsLayer {
                translationY = shown.coerceAtMost(0f)
                alpha = 1f - (-shown / (threshold * 2.5f)).coerceIn(0f, 0.7f)
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag < -threshold) onClose(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, dy -> drag = (drag + dy).coerceAtMost(0f) }
            },
    ) { content() }
}

@Composable
fun TabSwitcherScreen(activity: MainActivity) {
    val ui = activity.ui
    val tm = activity.app.tabManager
    val tabs by tm.tabs.collectAsState()
    val selectedId by tm.selectedId.collectAsState()
    val cards = tabs.map { t ->
        key(t.id) {
            val state by t.state.collectAsState()
            val thumb by t.thumbnail.collectAsState()
            TabCardModel(t.id, t.displayTitle, UrlInput.displayHost(state.url), t.isPrivate, thumb)
        }
    }
    TabSwitcherContent(
        cards = cards,
        selectedId = selectedId,
        onOpen = { id ->
            tm.select(id)
            ui.screen = Screen.BROWSER
        },
        onClose = { id -> tm.close(id) },
        onPlus = { ui.search = SearchRequest("", inCurrentTab = false, isPrivate = false) },
        onSettings = { ui.openPage(Screen.SETTINGS) },
    )
}
