package io.yiner.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

data class HistoryEntry(val url: String, val title: String, val visitedAt: Long)

data class ArchivedTab(
    val id: String,
    val url: String,
    val title: String,
    val sessionState: String?,
    val archivedAt: Long,
)

enum class DownloadStatus { RUNNING, DONE, FAILED, CANCELLED }

data class DownloadRecord(
    val id: Long,
    val fileName: String,
    val url: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus,
    val contentUri: String?,
    val createdAt: Long,
)

/** 本地 SQLite：历史记录、已归档标签、下载列表。无痕标签的数据不会写到这里。 */
class BrowserDb(context: Context) : SQLiteOpenHelper(context, "yiner.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', " +
                "visited_at INTEGER NOT NULL, visit_count INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL("CREATE INDEX history_time ON history(visited_at DESC)")
        db.execSQL(
            "CREATE TABLE archived_tabs (id TEXT PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL, " +
                "state TEXT, archived_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE downloads (id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT NOT NULL, " +
                "url TEXT NOT NULL, mime TEXT, total INTEGER NOT NULL, done INTEGER NOT NULL, " +
                "status TEXT NOT NULL, content_uri TEXT, created_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- 历史 ----

    fun recordVisit(url: String, title: String?, now: Long = System.currentTimeMillis()) {
        // Android 8 自带的 SQLite 3.18 不支持 UPSERT，用“先更新、没有再插入”
        writableDatabase.transaction {
            val db = this
            val values = ContentValues().apply {
                put("visited_at", now)
                if (!title.isNullOrBlank()) put("title", title)
            }
            val updated = db.update("history", values, "url = ?", arrayOf(url))
            if (updated > 0) {
                db.execSQL("UPDATE history SET visit_count = visit_count + 1 WHERE url = ?", arrayOf(url))
            } else {
                values.put("url", url)
                if (!values.containsKey("title")) values.put("title", "")
                db.insert("history", null, values)
            }
        }
    }

    fun updateTitle(url: String, title: String) {
        writableDatabase.execSQL("UPDATE history SET title = ? WHERE url = ?", arrayOf(title, url))
    }

    fun isVisited(urls: Array<String>): BooleanArray {
        if (urls.isEmpty()) return BooleanArray(0)
        val visited = HashSet<String>()
        urls.toList().chunked(500).forEach { chunk ->
            val q = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery("SELECT url FROM history WHERE url IN ($q)", chunk.toTypedArray()).use {
                while (it.moveToNext()) visited += it.getString(0)
            }
        }
        return BooleanArray(urls.size) { urls[it] in visited }
    }

    fun history(query: String = "", limit: Int = 300): List<HistoryEntry> {
        val (sql, args) = if (query.isBlank()) {
            "SELECT url, title, visited_at FROM history ORDER BY visited_at DESC LIMIT $limit" to emptyArray()
        } else {
            val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
            ("SELECT url, title, visited_at FROM history WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' " +
                "ORDER BY visit_count DESC, visited_at DESC LIMIT $limit") to arrayOf(like, like)
        }
        return readableDatabase.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(HistoryEntry(c.getString(0), c.getString(1), c.getLong(2))) }
        }
    }

    fun deleteHistory(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    // ---- 归档标签 ----

    fun archive(tabs: List<ArchivedTab>) {
        writableDatabase.transaction {
            tabs.forEach { t ->
                insertWithOnConflict(
                    "archived_tabs", null,
                    ContentValues().apply {
                        put("id", t.id); put("url", t.url); put("title", t.title)
                        put("state", t.sessionState); put("archived_at", t.archivedAt)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    fun archivedTabs(): List<ArchivedTab> =
        readableDatabase.rawQuery(
            "SELECT id, url, title, state, archived_at FROM archived_tabs ORDER BY archived_at DESC LIMIT 500",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ArchivedTab(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
                }
            }
        }

    fun removeArchived(id: String) {
        writableDatabase.delete("archived_tabs", "id = ?", arrayOf(id))
    }

    fun clearArchived() {
        writableDatabase.delete("archived_tabs", null, null)
    }

    // ---- 下载 ----

    fun insertDownload(fileName: String, url: String, mime: String?, total: Long): Long =
        writableDatabase.insert(
            "downloads", null,
            ContentValues().apply {
                put("file_name", fileName); put("url", url); put("mime", mime); put("total", total)
                put("done", 0L); put("status", DownloadStatus.RUNNING.name)
                put("created_at", System.currentTimeMillis())
            },
        )

    fun updateDownload(id: Long, done: Long, status: DownloadStatus, contentUri: String? = null, fileName: String? = null) {
        writableDatabase.update(
            "downloads",
            ContentValues().apply {
                put("done", done); put("status", status.name)
                if (contentUri != null) put("content_uri", contentUri)
                if (fileName != null) put("file_name", fileName)
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    fun downloads(): List<DownloadRecord> =
        readableDatabase.rawQuery(
            "SELECT id, file_name, url, mime, total, done, status, content_uri, created_at FROM downloads " +
                "ORDER BY created_at DESC LIMIT 500",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DownloadRecord(
                            c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                            DownloadStatus.valueOf(c.getString(6)), c.getString(7), c.getLong(8),
                        ),
                    )
                }
            }
        }

    /** 上次进程被杀时仍处于 RUNNING 的下载已经无法续传，标记为失败。 */
    fun markInterruptedDownloads() {
        writableDatabase.execSQL(
            "UPDATE downloads SET status = ? WHERE status = ?",
            arrayOf(DownloadStatus.FAILED.name, DownloadStatus.RUNNING.name),
        )
    }

    fun deleteDownload(id: Long) {
        writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString()))
    }

    fun clearDownloads() {
        writableDatabase.delete("downloads", "status != ?", arrayOf(DownloadStatus.RUNNING.name))
    }
}
