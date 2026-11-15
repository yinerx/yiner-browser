package io.yiner.browser.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 搜索栏定高与标签卡片竖向手势的行为验证：在 Robolectric 中实际组合界面、注入触摸事件并测量节点位置。
 * JVM 上没有真实键盘与系统栏，键盘弹出以“窗口被压缩”的方式模拟（旧系统上的最坏情况）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-434dpi", application = Application::class)
class SearchAndTabGestureTest {
    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------ 搜索栏定高

    @Test
    fun pillTop_isTwentyPercentOfStableHeight_withLowerBound() {
        assertEquals(183.dp, searchPillTop(915.dp))
        assertEquals(128.dp, searchPillTop(640.dp))
        // 极矮的窗口（分屏）至少留出面板顶边距
        assertTrue(searchPillTop(60.dp) >= io.yiner.browser.ui.arc.ArcTokens.SheetTopPadding)
    }

    private fun suggestions(n: Int) = List(n) { SearchItem.Suggest("联想 $it") }

    private fun pillTop(): Dp = compose.onNodeWithTag(TAG_SEARCH_PILL).getUnclippedBoundsInRoot().top

    @Test
    fun pill_staysPut_whenSuggestionsChange_scroll_andWindowShrinks() {
        var items by mutableStateOf(emptyList<SearchItem>())
        var height by mutableStateOf(915.dp)
        compose.setContent {
            YinerTheme(false) {
                Box(Modifier.fillMaxWidth().height(height)) {
                    SearchPanelContent(
                        value = TextFieldValue(""), onValueChange = {}, isPrivate = false, items = items,
                        focusRequester = remember { FocusRequester() }, onGo = {}, onItem = {}, onDismiss = {},
                    )
                }
            }
        }
        val start = pillTop()
        // 无系统栏时，胶囊顶边在可用高度的 20%
        assertEquals(183f, start.value, 1f)

        items = suggestions(3)
        compose.waitForIdle()
        assertEquals(start, pillTop())

        // 联想很多：列表独立滚动，胶囊不动
        items = suggestions(40)
        compose.waitForIdle()
        assertEquals(start, pillTop())
        compose.onNodeWithTag(TAG_SEARCH_LIST).performScrollToIndex(35)
        assertEquals(start, pillTop())
        val list = compose.onNodeWithTag(TAG_SEARCH_LIST).getUnclippedBoundsInRoot()
        assertTrue("列表应在胶囊下方", list.top > start)

        // 联想清空（加载中 / 无结果）
        items = emptyList()
        compose.waitForIdle()
        assertEquals(start, pillTop())

        // 模拟旧系统键盘弹出压缩窗口：胶囊仍在原处，只是列表变矮
        height = 520.dp
        compose.waitForIdle()
        assertEquals(start, pillTop())
        height = 915.dp
        compose.waitForIdle()
        assertEquals(start, pillTop())
    }

    @Test
    fun privateNote_doesNotMovePill() {
        var private by mutableStateOf(false)
        compose.setContent {
            YinerTheme(false) {
                SearchPanelContent(
                    value = TextFieldValue(""), onValueChange = {}, isPrivate = private, items = emptyList(),
                    focusRequester = remember { FocusRequester() }, onGo = {}, onItem = {}, onDismiss = {},
                )
            }
        }
        val start = pillTop()
        private = true
        compose.waitForIdle()
        assertEquals(start, pillTop())
    }

    // ------------------------------------------------------------ 标签卡片手势

    @Test
    fun swipeDecision_pureLogic() {
        assertEquals(CardSwipe.CLOSE, resolveCardSwipe(-150f, 0f, 140f, 96f, 900f))
        assertEquals(CardSwipe.CLOSE, resolveCardSwipe(-50f, -2000f, 140f, 96f, 900f))
        assertEquals(CardSwipe.SETTLE, resolveCardSwipe(-30f, -2000f, 140f, 96f, 900f))
        assertEquals(CardSwipe.OPEN, resolveCardSwipe(100f, 0f, 140f, 96f, 900f))
        assertEquals(CardSwipe.OPEN, resolveCardSwipe(40f, 2000f, 140f, 96f, 900f))
        assertEquals(CardSwipe.SETTLE, resolveCardSwipe(60f, 0f, 140f, 96f, 900f))
        // 向上甩但卡片已被拉到下方：不关闭
        assertEquals(CardSwipe.SETTLE, resolveCardSwipe(10f, -3000f, 140f, 96f, 900f))

        assertTrue(isVerticalSwipe(dx = 2f, dy = -20f, touchSlop = 8f))
        assertTrue(!isVerticalSwipe(dx = 20f, dy = -20f, touchSlop = 8f))
        assertTrue(!isVerticalSwipe(dx = 30f, dy = 5f, touchSlop = 8f))
        assertTrue(!isVerticalSwipe(dx = 0f, dy = 5f, touchSlop = 8f))
    }

    private val opened = mutableListOf<String>()
    private val closed = mutableListOf<String>()

    private fun setSwitcher() {
        compose.setContent {
            YinerTheme(false) {
                TabSwitcherContent(
                    cards = listOf(
                        TabCardModel("a", "第一页", "a.example.com", false, null),
                        TabCardModel("b", "第二页", "b.example.com", false, null),
                        TabCardModel("c", "第三页", "c.example.com", false, null),
                    ),
                    selectedId = "b", onOpen = { opened += it }, onClose = { closed += it },
                    onPlus = {}, onSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun titleTop(id: String) = compose.onNodeWithTag(TAG_CARD_TITLE_PREFIX + id).getUnclippedBoundsInRoot().top
    private fun cardTop(id: String) = compose.onNodeWithTag(TAG_CARD_BODY_PREFIX + id).getUnclippedBoundsInRoot().top

    @Test
    fun swipeUp_movesTitleWithCard_andClosesThatTab() {
        setSwitcher()
        val title0 = titleTop("b")
        val card0 = cardTop("b")
        val step = with(compose.density) { 20.dp.toPx() }
        compose.onNodeWithTag(TAG_CARD_PREFIX + "b").performTouchInput {
            down(center)
            repeat(5) { moveBy(androidx.compose.ui.geometry.Offset(0f, -step)) }
        }
        compose.waitForIdle()
        val titleMoved = titleTop("b") - title0
        val cardMoved = cardTop("b") - card0
        assertTrue("标题应随手指上移：$titleMoved", titleMoved < (-60).dp)
        assertEquals("标题与卡片同步移动", cardMoved.value, titleMoved.value, 1f)

        compose.onNodeWithTag(TAG_CARD_PREFIX + "b").performTouchInput {
            repeat(5) { moveBy(androidx.compose.ui.geometry.Offset(0f, -step)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("b"), closed)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun shortSwipeUp_springsBackTogether() {
        setSwitcher()
        val title0 = titleTop("b")
        val card0 = cardTop("b")
        compose.onNodeWithTag(TAG_CARD_PREFIX + "b").performTouchInput {
            down(center)
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, -with(compose.density) { 4.dp.toPx() })) }
            // 停顿后松手，速度很小
            advanceEventTime(300)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 0f))
            advanceEventTime(300)
            up()
        }
        compose.waitForIdle()
        assertTrue(closed.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(title0.value, titleTop("b").value, 0.5f)
        assertEquals(card0.value, cardTop("b").value, 0.5f)
    }

    @Test
    fun swipeDown_opensThatTab() {
        setSwitcher()
        compose.onNodeWithTag(TAG_CARD_PREFIX + "b").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + with(compose.density) { 130.dp.toPx() }, durationMillis = 400)
        }
        compose.waitForIdle()
        assertEquals(listOf("b"), opened)
        assertTrue(closed.isEmpty())
    }

    @Test
    fun horizontalSwipe_onlyPages_neverOpensOrCloses() {
        setSwitcher()
        val bLeft0 = compose.onNodeWithTag(TAG_CARD_BODY_PREFIX + "b").getUnclippedBoundsInRoot().left
        compose.onNodeWithTag(TAG_CARD_PREFIX + "b").performTouchInput {
            swipeLeft(startX = centerX + 100f, endX = centerX - 500f, durationMillis = 300)
        }
        compose.waitForIdle()
        // 斜向但以横向为主
        compose.onRoot().performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(-400f, 180f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("横向滑动不应关闭：$closed", closed.isEmpty())
        assertTrue("横向滑动不应打开：$opened", opened.isEmpty())
        // 已翻到后一张：b 不在中间
        val bLeft = compose.onNodeWithTag(TAG_CARD_BODY_PREFIX + "b").getUnclippedBoundsInRoot().left
        assertTrue("应已翻页：$bLeft0 → $bLeft", abs((bLeft - bLeft0).value) > 200f)
    }
}
