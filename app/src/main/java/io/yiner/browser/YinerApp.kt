package io.yiner.browser

import android.app.Application
import io.yiner.browser.blocking.ContentBlocker
import io.yiner.browser.browser.PrivateProfiles
import io.yiner.browser.browser.TabManager
import io.yiner.browser.browser.WebDialogs
import io.yiner.browser.data.BrowserDb
import io.yiner.browser.data.Prefs
import io.yiner.browser.download.Downloads
import io.yiner.browser.update.UpdateManager
import kotlinx.coroutines.MainScope

/**
 * 内核为 Android 系统 WebView（基于 Chromium，随系统 / Google Play 更新）。
 * 全部组件懒加载，只在主进程的界面或服务中真正创建。
 */
class YinerApp : Application() {
    val appScope = MainScope()
    val prefs by lazy { Prefs(this) }
    val db by lazy { BrowserDb(this).also { it.markInterruptedDownloads() } }
    val downloads by lazy { Downloads(this, db) }
    val updates by lazy { UpdateManager(this, appScope) }
    val blocker by lazy { ContentBlocker(this, prefs, appScope).also { it.start() } }
    val privateProfiles by lazy { PrivateProfiles().also { it.cleanupStale() } }

    val tabManager: TabManager by lazy {
        TabManager(
            context = this,
            db = db,
            prefs = prefs,
            scope = appScope,
            blocker = blocker,
            privateProfiles = privateProfiles,
            dialogs = WebDialogs { tabManager.uiHost },
        )
    }
}
