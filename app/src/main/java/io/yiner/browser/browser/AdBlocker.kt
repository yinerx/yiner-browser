package io.yiner.browser.browser

import android.content.Context
import android.util.Log
import io.yiner.browser.data.Prefs
import io.yiner.browser.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/**
 * 广告与跟踪屏蔽，两层：
 * 1. Gecko 内置的增强型跟踪保护（ETP）+ 全面 Cookie 保护（第三方 Cookie 按站点隔离）；
 * 2. 随应用打包的 uBlock Origin（AMO 官方签名的 .xpi，GPL-3.0），首次启动时通过
 *    WebExtensionController.install() 从本地文件安装，不需要联网下载扩展本身。
 *
 * 只允许安装这一个扩展；网页触发的其它扩展安装请求一律拒绝。
 */
class AdBlocker(private val context: Context, private val prefs: Prefs) {

    sealed interface Status {
        data object Installing : Status
        data class Ready(val version: String, val enabled: Boolean) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Installing)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var extension: WebExtension? = null

    val optionsPageUrl: String? get() = extension?.metaData?.optionsPageUrl

    fun start(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                val ours = extension.id == UBLOCK_ID
                // 第二个参数：允许在无痕标签中运行；第三个：技术与交互数据收集（拒绝）
                return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(ours, ours, false))
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>,
            ): GeckoResult<AllowOrDeny> =
                if (extension.id == UBLOCK_ID) GeckoResult.allow() else GeckoResult.deny()

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ): GeckoResult<AllowOrDeny> =
                if (extension.id == UBLOCK_ID) GeckoResult.allow() else GeckoResult.deny()
        }

        controller.list().accept({ list ->
            val existing = list?.firstOrNull { it.id == UBLOCK_ID }
            if (existing != null && !isOlder(existing.metaData.version, BUNDLED_VERSION)) {
                onInstalled(runtime, existing)
            } else {
                installBundled(runtime)
            }
        }, { e -> fail("读取扩展列表失败", e) })
    }

    private fun installBundled(runtime: GeckoRuntime) {
        val file = File(context.filesDir, "extensions/ublock_origin.xpi")
        try {
            file.parentFile?.mkdirs()
            context.assets.open(ASSET_PATH).use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            fail("无法读取内置的 uBlock Origin", e); return
        }
        runtime.webExtensionController.install("file://" + file.absolutePath).accept({ ext ->
            file.delete()
            if (ext != null) onInstalled(runtime, ext) else fail("uBlock Origin 安装被取消", null)
        }, { e ->
            file.delete()
            fail("uBlock Origin 安装失败", e)
        })
    }

    private fun onInstalled(runtime: GeckoRuntime, installed: WebExtension) {
        val controller = runtime.webExtensionController
        var ext = installed
        prefs.installedAdBlockerVersion = ext.metaData.version
        if (!ext.metaData.allowedInPrivateBrowsing) {
            controller.setAllowedInPrivateBrowsing(ext, true).accept { it?.let { updated -> extension = updated } }
        }
        val wanted = prefs.settings.value.adBlockEnabled
        if (wanted != ext.metaData.enabled) {
            val op = if (wanted) controller.enable(ext, WebExtensionController.EnableSource.USER)
            else controller.disable(ext, WebExtensionController.EnableSource.USER)
            op.accept { it?.let { updated -> ext = updated; publish(updated) } }
        }
        publish(ext)
    }

    private fun publish(ext: WebExtension) {
        extension = ext
        _status.value = Status.Ready(ext.metaData.version, ext.metaData.enabled)
    }

    fun setEnabled(runtime: GeckoRuntime, enabled: Boolean) {
        prefs.update { it.copy(adBlockEnabled = enabled) }
        val ext = extension ?: return
        val controller = runtime.webExtensionController
        val op = if (enabled) controller.enable(ext, WebExtensionController.EnableSource.USER)
        else controller.disable(ext, WebExtensionController.EnableSource.USER)
        op.accept({ it?.let(::publish) }, { e -> fail("切换广告屏蔽失败", e) })
    }

    /** 通过 AMO 检查 uBlock Origin 是否有新版本（需要联网，由用户手动触发）。 */
    fun checkForUpdate(runtime: GeckoRuntime, onDone: (String) -> Unit) {
        val ext = extension ?: return onDone("广告屏蔽尚未就绪")
        val before = ext.metaData.version
        runtime.webExtensionController.update(ext).accept({ updated ->
            if (updated == null) {
                onDone("已是最新版本（$before）")
            } else {
                publish(updated)
                onDone("已更新到 ${updated.metaData.version}")
            }
        }, { e -> onDone("检查更新失败：${e?.message ?: "未知错误"}") })
    }

    private fun fail(message: String, e: Throwable?) {
        Log.w(TAG, message, e)
        _status.value = Status.Failed(message + (e?.message?.let { "：$it" } ?: ""))
    }

    companion object {
        private const val TAG = "AdBlocker"
        const val UBLOCK_ID = "uBlock0@raymondhill.net"
        const val BUNDLED_VERSION = "1.76.0"
        private const val ASSET_PATH = "extensions/ublock_origin.xpi"

        fun isOlder(installed: String, bundled: String): Boolean {
            val a = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val b = bundled.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x < y
            }
            return false
        }

        /** Gecko 内置跟踪保护设置。标准模式对应 Firefox“标准”，严格模式额外拦截指纹与全部社交跟踪。 */
        fun contentBlockingSettings(s: Settings): ContentBlocking.Settings =
            ContentBlocking.Settings.Builder()
                .antiTracking(antiTrackingCategories(s.strictTrackingProtection))
                .enhancedTrackingProtectionLevel(
                    if (s.strictTrackingProtection) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT,
                )
                .strictSocialTrackingProtection(s.strictTrackingProtection)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
                .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                .cookiePurging(true)
                .queryParameterStrippingPrivateBrowsingEnabled(true)
                .build()

        fun antiTrackingCategories(strict: Boolean): Int =
            if (strict) ContentBlocking.AntiTracking.STRICT else ContentBlocking.AntiTracking.DEFAULT

        /** 运行中修改设置（无需重启）。 */
        fun applyRuntimeSettings(runtime: GeckoRuntime, s: Settings) {
            runtime.settings.contentBlocking.apply {
                setAntiTracking(antiTrackingCategories(s.strictTrackingProtection))
                setEnhancedTrackingProtectionLevel(
                    if (s.strictTrackingProtection) ContentBlocking.EtpLevel.STRICT else ContentBlocking.EtpLevel.DEFAULT,
                )
                setStrictSocialTrackingProtection(s.strictTrackingProtection)
            }
            runtime.settings.setAllowInsecureConnections(
                if (s.httpsOnly) GeckoRuntimeSettings.HTTPS_ONLY else GeckoRuntimeSettings.ALLOW_ALL,
            )
        }
    }
}
