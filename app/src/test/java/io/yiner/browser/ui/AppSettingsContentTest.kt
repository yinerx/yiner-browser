package io.yiner.browser.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置页的每一项只在点按时打开二级页。
 * 回归：0.3.0 中部分设置项把 `{ onOpen(...) }` 写成了尾随 lambda，被当成右侧内容在组合时执行，
 * 设置页一打开就连跳多个二级页，返回无法回到主页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppSettingsContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<SettingsTarget>()

    private fun show() {
        compose.setContent {
            YinerTheme(false) {
                AppSettingsContent(
                    AppSettingsModel(
                        isDefaultBrowser = false, searchEngineLabel = "Bing", archiveLabel = "从不",
                        autoOpenKeyboard = false, searchSuggestions = true, versionName = "0.3.0",
                        engineDescription = "内核", oldEngineDataLabel = "1 MB",
                    ),
                    onBack = {}, onOpen = { opened += it }, onAutoOpenKeyboard = {}, onSearchSuggestions = {}, updateSection = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun showingSettingsOpensNothing() {
        show()
        assertEquals(emptyList<SettingsTarget>(), opened)
    }

    @Test fun eachRowOpensItsTargetOnClick() {
        show()
        val rows = listOf(
            "全局网站设置" to SettingsTarget.GLOBAL_SITE,
            "将银耳浏览器设为默认浏览器" to SettingsTarget.DEFAULT_BROWSER,
            "搜索引擎" to SettingsTarget.SEARCH_ENGINES,
            "已归档" to SettingsTarget.ARCHIVED,
            "归档不活跃的标签" to SettingsTarget.ARCHIVE_AFTER,
            "清除浏览数据" to SettingsTarget.CLEAR_DATA,
            "下载" to SettingsTarget.DOWNLOADS,
            "历史记录" to SettingsTarget.HISTORY,
            "清理旧内核数据" to SettingsTarget.OLD_ENGINE_DATA,
            "外观" to SettingsTarget.APPEARANCE,
            "开源许可" to SettingsTarget.LICENSES,
        )
        for ((title, target) in rows) {
            opened.clear()
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("点按“$title”", listOf(target), opened)
        }
    }
}
