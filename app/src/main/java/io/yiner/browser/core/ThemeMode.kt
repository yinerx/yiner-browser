package io.yiner.browser.core

/** 外观：跟随系统（默认）、浅色、深色。手动选择浅色或深色后不受系统主题影响。 */
enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    /** 结合系统当前是否为深色，得出界面实际使用的外观。 */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val DEFAULT = SYSTEM
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
