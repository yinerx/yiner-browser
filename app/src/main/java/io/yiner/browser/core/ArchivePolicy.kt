package io.yiner.browser.core

/** 标签自动归档的时长选项。0 表示关闭。 */
enum class ArchiveAfter(val hours: Int, val label: String) {
    OFF(0, "从不"),
    HOURS_12(12, "12 小时"),
    DAY_1(24, "1 天"),
    DAYS_7(24 * 7, "7 天"),
    DAYS_30(24 * 30, "30 天");

    val millis: Long get() = hours * 3_600_000L

    companion object {
        val DEFAULT = DAY_1
        fun fromHours(h: Int): ArchiveAfter = entries.firstOrNull { it.hours == h } ?: DEFAULT
    }
}

data class ArchiveCandidate(val id: String, val lastActiveAt: Long, val isPrivate: Boolean)

object ArchivePolicy {
    /** 返回应当归档的标签 id。无痕标签不归档（它们不落盘，关闭即销毁）。 */
    fun tabsToArchive(tabs: List<ArchiveCandidate>, now: Long, after: ArchiveAfter): List<String> {
        if (after == ArchiveAfter.OFF) return emptyList()
        return tabs.filter { !it.isPrivate && now - it.lastActiveAt >= after.millis }.map { it.id }
    }
}
