package io.yiner.browser.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页及其二级页的返回栈：从哪里进入，就逐级回到哪里。 */
class UiStateNavigationTest {
    @Test fun homeToSettingsAndBack() {
        val ui = UiState()
        ui.openPage(Screen.SETTINGS)
        assertEquals(Screen.SETTINGS, ui.screen)
        assertEquals(Overlay.PAGE, ui.closeTopOverlay())
        assertEquals(Screen.TABS, ui.screen)
        assertFalse(ui.overlayOpen)
    }

    @Test fun browserToSettingsReturnsToBrowser() {
        val ui = UiState().apply { screen = Screen.BROWSER }
        ui.openPage(Screen.SETTINGS)
        ui.closeTopOverlay()
        assertEquals(Screen.BROWSER, ui.screen)
    }

    @Test fun subPagesStepBackOneLevelAtATime() {
        val subPages = listOf(
            Screen.SITE_SETTINGS_GLOBAL, Screen.SEARCH_ENGINES, Screen.ARCHIVED, Screen.ARCHIVE_AFTER,
            Screen.DOWNLOADS, Screen.HISTORY, Screen.APPEARANCE,
        )
        for (sub in subPages) {
            val ui = UiState().apply { screen = Screen.BROWSER }
            ui.openPage(Screen.SETTINGS)
            ui.openPage(sub)
            assertEquals(sub, ui.screen)
            ui.closeTopOverlay()
            assertEquals("$sub 返回后应回到设置", Screen.SETTINGS, ui.screen)
            ui.closeTopOverlay()
            assertEquals("设置返回后应回到网页", Screen.BROWSER, ui.screen)
            assertNull(ui.closeTopOverlay())
        }
    }

    @Test fun reopeningSamePageDoesNotGrowStack() {
        val ui = UiState()
        ui.openPage(Screen.SETTINGS)
        ui.openPage(Screen.SETTINGS)
        ui.closeTopOverlay()
        assertEquals(Screen.TABS, ui.screen)
    }

    @Test fun pageClosesBeforeHiddenFindBar() {
        // 网页上开着查找栏时从通知进入设置：返回应先关设置页（看得见的那层），查找栏保留
        val ui = UiState().apply { screen = Screen.BROWSER; findOpen = true }
        ui.openPage(Screen.SETTINGS)
        assertEquals(Overlay.PAGE, ui.closeTopOverlay())
        assertEquals(Screen.BROWSER, ui.screen)
        assertTrue(ui.findOpen)
        assertEquals(Overlay.FIND, ui.closeTopOverlay())
        assertFalse(ui.findOpen)
    }

    @Test fun searchPanelOverSettingsClosesFirst() {
        val ui = UiState()
        ui.openPage(Screen.SETTINGS)
        ui.search = SearchRequest("", inCurrentTab = false, isPrivate = false)
        assertEquals(Overlay.SEARCH, ui.closeTopOverlay())
        assertEquals(Screen.SETTINGS, ui.screen)
    }

    @Test fun leavingPagesClearsStack() {
        val ui = UiState().apply { screen = Screen.BROWSER }
        ui.openPage(Screen.SETTINGS)
        ui.openPage(Screen.HISTORY)
        ui.leavePages(Screen.TABS)
        ui.openPage(Screen.SETTINGS)
        ui.closeTopOverlay()
        assertEquals(Screen.TABS, ui.screen)
    }
}
