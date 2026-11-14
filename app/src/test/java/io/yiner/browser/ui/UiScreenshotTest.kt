package io.yiner.browser.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import io.yiner.browser.ui.arc.ArcBottomBar
import io.yiner.browser.ui.arc.ArcTokens
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 离线渲染银耳浏览器的六个界面，用于与 Arc Search Android beta 参考截图逐项对照。
 *
 * - 只在 `./gradlew testDebugUnitTest -Pyiner.screenshots=true` 时运行，输出到 build/ui-screenshots/；
 * - 这是 Robolectric 在 JVM 上的渲染，不是设备截图：没有状态栏、导航栏和键盘，网页内容是占位文字；
 * - 设备配置取 411dp 宽、约 2.71 px/dp（434dpi），与参考图估算的逻辑尺寸一致，输出约 1116px 宽。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h915dp-434dpi", application = Application::class)
class UiScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("yiner.screenshots.dir") ?: "build/ui-screenshots")

    @Before
    fun onlyWhenRequested() {
        assumeTrue("未开启界面截图（-Pyiner.screenshots=true）", System.getProperty("yiner.screenshots") == "true")
        System.setProperty("roborazzi.test.record", "true")
        outDir.mkdirs()
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").absolutePath)
    }

    /** 占位网页：白底、几段正文。 */
    @Composable
    private fun FakePage() {
        Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 16.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("示例网页标题：银耳浏览器界面渲染", fontSize = 30.sp, lineHeight = 38.sp, color = Color(0xFF222222))
            repeat(3) {
                Text(
                    "这是一段用于界面对照的占位正文。网页内容在浏览时占满屏幕主体，底部只保留轻薄的工具栏；" +
                        "常用操作集中在向上展开的菜单面板中。",
                    fontSize = 22.sp, lineHeight = 34.sp, color = Color(0xFF333333),
                )
            }
        }
    }

    @Test
    fun s01_webpageBottomBar() = shot("01-webpage-bottom-bar") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { FakePage() }
            ArcBottomBar(null, null, tabCount = 2, isPrivate = false, progress = null, onTabs = {}, onPlus = {}, onExpand = {})
        }
    }

    @Test
    fun s02_expandedMenu() = shot("02-expanded-address-menu") {
        Box(Modifier.fillMaxSize()) {
            FakePage()
            Box(Modifier.fillMaxSize().background(ArcTokens.MenuScrim))
            Box(Modifier.align(Alignment.BottomCenter)) {
                PageMenuContent(
                    PageMenuModel(
                        url = "https://www.example.com/article", canGoBack = true, canGoForward = false, loading = false,
                        pinned = false, textZoomPercent = 100, desktopMode = false, blockedCount = 2,
                    ),
                    PageMenuActions(),
                )
            }
        }
    }

    @Test
    fun s03_searchPanel() = shot("03-search-keyboard") {
        Box(Modifier.fillMaxSize()) {
            FakePage()
            SearchPanelContent(
                value = TextFieldValue(""), onValueChange = {}, isPrivate = false,
                items = listOf(
                    SearchItem.OpenTab("1", "示例新闻：热带风暴带来的影响", "news.example.com", false),
                    SearchItem.OpenTab("2", "示例网站：安卓资讯与评测", "android.example.org", false),
                    SearchItem.Pinned("https://mail.example.com", "示例邮箱"),
                ),
                focusRequester = remember { FocusRequester() }, onGo = {}, onItem = {}, onDismiss = {},
            )
        }
    }

    @Test
    fun s04_tabSwitcher() = shot("04-tab-switcher") {
        TabSwitcherContent(
            cards = listOf(
                TabCardModel("a", "示例新闻：热带风暴带来的影响", "news.example.com", false, null),
                TabCardModel("b", "示例文章：运营商数据安全", "android.example.org", false, null),
                TabCardModel("c", "示例食谱", "food.example.net", false, null),
            ),
            selectedId = "b", onOpen = {}, onClose = {}, onPlus = {}, onSettings = {},
        )
    }

    @Test
    fun s05_appSettings() = shot("05-app-settings") {
        AppSettingsContent(
            AppSettingsModel(
                isDefaultBrowser = false, searchEngineLabel = "Google", archiveLabel = "1 天后",
                autoOpenKeyboard = true, searchSuggestions = true, versionName = "0.2.0", engineDescription = "内核 GeckoView 160.0",
            ),
            onBack = {}, onOpen = {}, onAutoOpenKeyboard = {}, onSearchSuggestions = {},
            updateSection = { Text("（更新分区）", Modifier.fillMaxWidth().padding(start = 66.dp)) },
        )
    }

    @Test
    fun s06_searchEngines() = shot("06-search-engines") {
        SearchEnginesContent(listOf("Google", "Bing", "DuckDuckGo", "百度"), selected = 0, onSelect = {}, onBack = {})
    }
}
