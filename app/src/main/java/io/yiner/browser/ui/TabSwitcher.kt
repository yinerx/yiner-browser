package io.yiner.browser.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

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
 * 上滑卡片（连同标题）关闭、下滑打开，是银耳浏览器的设计——截图中没有关闭标签的证据。
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
                        // 标题和卡片是一个整体：一起随手指上下移动，一起关闭或回弹
                        VerticalCardSwipe(
                            onClose = { onClose(card.id) },
                            onOpen = { onOpen(card.id) },
                            modifier = Modifier.fillMaxSize().testTag(TAG_CARD_PREFIX + card.id),
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Top,
                            ) {
                                Spacer(Modifier.height(topSpace - ArcTokens.CardFavicon - ArcTokens.CardTitleGap / 2 - 6.dp))
                                CardTitle(
                                    card.title, card.host, card.isPrivate,
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(TAG_CARD_TITLE_PREFIX + card.id),
                                )
                                // 04：标题中心到卡片顶约 100 px ≈ 37dp
                                Spacer(Modifier.height(ArcTokens.CardTitleGap / 2 + 10.dp))
                                TabCardBody(
                                    card.thumbnail, card.title, card.host, card.isPrivate,
                                    Modifier.size(cardWidth, cardHeight).testTag(TAG_CARD_BODY_PREFIX + card.id).clickable { onOpen(card.id) },
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

/** 测试用标识。 */
internal const val TAG_CARD_PREFIX = "tab-card:"
internal const val TAG_CARD_TITLE_PREFIX = "tab-card-title:"
internal const val TAG_CARD_BODY_PREFIX = "tab-card-body:"

/** 竖向滑动松手后的结果。 */
internal enum class CardSwipe { CLOSE, OPEN, SETTLE }

/**
 * 根据松手时的位移与速度（向下为正，px 与 px/s）决定关闭、打开还是回弹。
 * 位移超过阈值直接生效；快速甩动时位移达到阈值的三分之一也算。
 */
internal fun resolveCardSwipe(offset: Float, velocity: Float, closeDistance: Float, openDistance: Float, flingVelocity: Float): CardSwipe = when {
    offset <= -closeDistance || (velocity <= -flingVelocity && offset <= -closeDistance / 3) -> CardSwipe.CLOSE
    offset >= openDistance || (velocity >= flingVelocity && offset >= openDistance / 3) -> CardSwipe.OPEN
    else -> CardSwipe.SETTLE
}

/**
 * 判断一次拖动是否属于竖向手势：越过触摸阈值时，竖向位移需明显大于横向位移（至少 1.5 倍）。
 * 横向或斜向的拖动留给标签轮播，避免左右切换时误关、误开标签。
 */
internal fun isVerticalSwipe(dx: Float, dy: Float, touchSlop: Float): Boolean =
    abs(dy) > touchSlop && abs(dy) >= abs(dx) * 1.5f

/**
 * 卡片（连同标题）的竖向手势：上滑关闭，下滑打开。
 * 自己判定方向，只在确认是竖向拖动后才消费事件，此后横向轮播不会再接手；判定为横向则整次放手不管。
 */
@Composable
private fun VerticalCardSwipe(
    onClose: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val closeDistance = with(density) { 140.dp.toPx() }
    val openDistance = with(density) { 96.dp.toPx() }
    val flingVelocity = with(density) { 900.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var height by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .onSizeChanged { height = it.height.toFloat() }
            .graphicsLayer {
                val y = offset.value
                translationY = y
                // 上滑逐渐变淡；下滑稍微缩小，提示“松手打开”
                alpha = 1f - (-y / (closeDistance * 2.5f)).coerceIn(0f, 0.7f)
                val s = 1f - (y / openDistance).coerceIn(0f, 1f) * 0.04f
                scaleX = s
                scaleY = s
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 上一次关闭 / 打开 / 回弹的动画还没结束时不接新手势
                    if (offset.isRunning) return@awaitEachGesture
                    var dx = 0f
                    var dy = 0f
                    var claimed = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val delta = change.positionChange()
                        if (!claimed) {
                            if (change.isConsumed) return@awaitEachGesture
                            dx += delta.x
                            dy += delta.y
                            if (abs(dx) > slop && !isVerticalSwipe(dx, dy, slop)) return@awaitEachGesture
                            if (!isVerticalSwipe(dx, dy, slop)) {
                                // 同一事件传到外层后，若横向轮播已经接手（消费了位移），本次手势不再参与
                                val after = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                                if (after == null || after.isConsumed) return@awaitEachGesture
                                continue
                            }
                            claimed = true
                            change.consume()
                            // 越过阈值的那一段也算进去，手指与卡片不脱节
                            scope.launch { offset.snapTo(dampedOffset(dy, openDistance)) }
                            continue
                        }
                        change.consume()
                        dy += delta.y
                        scope.launch { offset.snapTo(dampedOffset(dy, openDistance)) }
                    }
                    if (!claimed) return@awaitEachGesture
                    val velocity = tracker.calculateVelocity().y
                    // 用手指的累计位移判定（offset 的更新是异步的，可能落后一帧）
                    when (resolveCardSwipe(dampedOffset(dy, openDistance), velocity, closeDistance, openDistance, flingVelocity)) {
                        CardSwipe.CLOSE -> scope.launch {
                            // 标题与卡片一起移出屏幕上方，再真正关闭
                            offset.animateTo(-(height + closeDistance), tween(180))
                            onClose()
                            // 正常情况下关闭后此卡片已离开组合；万一没有被移除，恢复原位而不是一直隐藏
                            delay(300)
                            offset.snapTo(0f)
                        }
                        CardSwipe.OPEN -> scope.launch {
                            onOpen()
                            offset.snapTo(0f)
                        }
                        CardSwipe.SETTLE -> scope.launch { offset.animateTo(0f, spring()) }
                    }
                }
            },
    ) { content() }
}

/** 上滑跟手；下滑到打开阈值之前也跟手，超过后加阻尼。 */
internal fun dampedOffset(dy: Float, openDistance: Float): Float =
    if (dy <= openDistance) dy else openDistance + (dy - openDistance) * 0.35f

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
