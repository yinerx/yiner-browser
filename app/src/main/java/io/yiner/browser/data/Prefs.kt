package io.yiner.browser.data

import android.content.Context
import androidx.core.content.edit
import io.yiner.browser.core.ArchiveAfter
import io.yiner.browser.core.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    val searchSuggestions: Boolean = true,
    val archiveAfter: ArchiveAfter = ArchiveAfter.DEFAULT,
    val adBlockEnabled: Boolean = true,
    val strictTrackingProtection: Boolean = false,
    val httpsOnly: Boolean = false,
)

/** SharedPreferences 封装，同时暴露为 StateFlow 供界面观察。 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yiner_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun read() = Settings(
        searchEngine = SearchEngine.fromId(sp.getString("search_engine", null)),
        searchSuggestions = sp.getBoolean("search_suggestions", true),
        archiveAfter = ArchiveAfter.fromHours(sp.getInt("archive_hours", ArchiveAfter.DEFAULT.hours)),
        adBlockEnabled = sp.getBoolean("adblock", true),
        strictTrackingProtection = sp.getBoolean("strict_tp", false),
        httpsOnly = sp.getBoolean("https_only", false),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit {
            putString("search_engine", s.searchEngine.id)
            putBoolean("search_suggestions", s.searchSuggestions)
            putInt("archive_hours", s.archiveAfter.hours)
            putBoolean("adblock", s.adBlockEnabled)
            putBoolean("strict_tp", s.strictTrackingProtection)
            putBoolean("https_only", s.httpsOnly)
        }
        _settings.value = s
    }

    var selectedTabId: String?
        get() = sp.getString("selected_tab", null)
        set(v) = sp.edit { putString("selected_tab", v) }

    var installedAdBlockerVersion: String?
        get() = sp.getString("adblock_installed_version", null)
        set(v) = sp.edit { putString("adblock_installed_version", v) }
}
