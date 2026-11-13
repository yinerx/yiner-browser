package io.yiner.browser

import android.app.Application
import io.yiner.browser.browser.AdBlocker
import io.yiner.browser.browser.PermissionHandler
import io.yiner.browser.browser.PromptHandler
import io.yiner.browser.browser.TabManager
import io.yiner.browser.data.BrowserDb
import io.yiner.browser.data.Prefs
import io.yiner.browser.download.Downloads
import io.yiner.browser.update.UpdateManager
import kotlinx.coroutines.MainScope
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * 注意：GeckoView 的子进程也会实例化 Application，所以这里不做任何启动期工作，
 * 全部懒加载，只会在主进程的界面/服务中被真正创建。
 */
class YinerApp : Application() {
    val appScope = MainScope()
    val prefs by lazy { Prefs(this) }
    val db by lazy { BrowserDb(this).also { it.markInterruptedDownloads() } }
    val downloads by lazy { Downloads(this, db) }
    val adBlocker by lazy { AdBlocker(this, prefs) }
    val updates by lazy { UpdateManager(this, appScope) }

    val runtime: GeckoRuntime by lazy {
        val s = prefs.settings.value
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(AdBlocker.contentBlockingSettings(s))
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .allowInsecureConnections(if (s.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL)
            .globalPrivacyControlEnabled(true)
            .aboutConfigEnabled(false)
            .remoteDebuggingEnabled(false)
            .consoleOutput(BuildConfig.DEBUG)
            .build()
        GeckoRuntime.create(this, settings).also { adBlocker.start(it) }
    }

    val tabManager: TabManager by lazy {
        TabManager(
            context = this,
            runtimeProvider = { runtime },
            db = db,
            prefs = prefs,
            scope = appScope,
            prompts = PromptHandler { tabManagerHost() },
            permissions = PermissionHandler { tabManagerHost() },
        )
    }

    private fun tabManagerHost() = tabManager.uiHost
}
