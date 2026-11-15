package io.yiner.browser

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.yiner.browser.ui.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 在 Robolectric（JVM）中启动真实的 MainActivity，验证设置页的系统返回与页面返回按钮。
 * 这不是真机测试：系统返回通过 OnBackPressedDispatcher 模拟，WebView 是 Robolectric 的影子实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsBackNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val activity get() = compose.activity
    private val ui get() = activity.ui

    @Before
    fun startOnHome() {
        compose.runOnIdle { ui.search = null }
        compose.waitForIdle()
        assertEquals(Screen.TABS, ui.screen)
    }

    private fun systemBack() {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun pageBackButton() {
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
    }

    private fun openSettingsFromHome() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitForIdle()
        assertEquals("点设置后应停在设置页，不应自动跳进二级页", Screen.SETTINGS, ui.screen)
    }

    @Test fun homeSettingsSystemBack() {
        openSettingsFromHome()
        systemBack()
        assertEquals(Screen.TABS, ui.screen)
    }

    @Test fun homeSettingsPageBackButton() {
        openSettingsFromHome()
        pageBackButton()
        assertEquals(Screen.TABS, ui.screen)
    }

    @Test fun subPagesStepBackWithBothBackKinds() {
        val rows = mapOf(
            "全局网站设置" to Screen.SITE_SETTINGS_GLOBAL,
            "搜索引擎" to Screen.SEARCH_ENGINES,
            "已归档" to Screen.ARCHIVED,
            "归档不活跃的标签" to Screen.ARCHIVE_AFTER,
            "下载" to Screen.DOWNLOADS,
            "历史记录" to Screen.HISTORY,
            "外观" to Screen.APPEARANCE,
        )
        for ((title, page) in rows) {
            for (useButton in listOf(false, true)) {
                val back = if (useButton) ::pageBackButton else ::systemBack
                val how = if (useButton) "返回按钮" else "系统返回"
                openSettingsFromHome()
                compose.onNodeWithText(title).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(page, ui.screen)
                back()
                assertEquals("$title → $how 应回到设置", Screen.SETTINGS, ui.screen)
                back()
                assertEquals("设置 → $how 应回到主页", Screen.TABS, ui.screen)
            }
        }
    }

    @Test fun globalSiteSettingsKeepsRiskNotes() {
        openSettingsFromHome()
        compose.onNodeWithText("全局网站设置").performClick()
        compose.waitForIdle()
        assertEquals(Screen.SITE_SETTINGS_GLOBAL, ui.screen)
        // 精简后仍保留的风险提示
        compose.onNodeWithText("不支持 HTTPS 的网站将无法打开").performScrollTo().assertExists()
        compose.onNodeWithText("不能保证拦截所有广告", substring = true).performScrollTo().assertExists()
    }

    @Test fun webPageSettingsBackReturnsToSamePageWithoutHistoryBack() {
        val tm = activity.app.tabManager
        // Robolectric 没有 WebView provider，Service Worker 拦截无法初始化；与本测试无关，跳过
        tm.javaClass.getDeclaredField("serviceWorkerHooked").apply { isAccessible = true }.setBoolean(tm, true)
        compose.runOnIdle {
            tm.newTab("https://example.com/", isPrivate = false)
            activity.showBrowser()
        }
        compose.waitForIdle()
        val tab = tm.selectedTab!!
        val view = tab.view!!
        // 网页有可后退的历史：返回若漏到网页，就会触发 goBack
        compose.runOnIdle {
            shadowOf(view).setCanGoBack(true)
            tab.update { it.copy(canGoBack = true) }
        }
        compose.waitForIdle()
        assertEquals(Screen.BROWSER, ui.screen)

        for (useButton in listOf(false, true)) {
            // 网页界面没有设置入口；从网页直接进入设置的途径是更新通知（ACTION_SHOW_UPDATE → openPage）
            compose.runOnIdle { ui.openPage(Screen.SETTINGS) }
            compose.waitForIdle()
            assertEquals(Screen.SETTINGS, ui.screen)
            compose.onNodeWithText("外观").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(Screen.APPEARANCE, ui.screen)
            if (useButton) pageBackButton() else systemBack()
            assertEquals(Screen.SETTINGS, ui.screen)
            if (useButton) pageBackButton() else systemBack()
            assertEquals(Screen.BROWSER, ui.screen)
            assertSame(tab, tm.selectedTab)
            assertEquals("不应触发网页历史回退", 0, shadowOf(view).goBackInvocations)
        }

        // 回到网页后再按系统返回，才是网页内后退
        systemBack()
        assertEquals(1, shadowOf(view).goBackInvocations)
    }
}
