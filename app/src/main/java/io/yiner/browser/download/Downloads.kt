package io.yiner.browser.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.yiner.browser.data.BrowserDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 一次待执行的下载：GeckoView 已经建立好连接，正文通过 [response].body 读取。 */
class DownloadJob(
    val id: Long,
    val response: WebResponse,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val isPrivate: Boolean,
)

/** 下载过程中写入目标文件的位置。 */
class DownloadTarget(val uri: Uri, val output: OutputStream, val finish: (success: Boolean) -> String)

/**
 * 下载调度：把 GeckoView 交出的响应流写入系统“下载”目录。
 * 普通标签的下载记入下载列表；无痕标签的下载只保存文件、不留记录。
 */
class Downloads(private val context: Context, private val db: BrowserDb) {
    private val pending = ConcurrentHashMap<Long, DownloadJob>()
    private val privateIds = AtomicLong(-1)

    /** 每当下载列表有变化时递增，供界面刷新。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun changed() {
        _version.value++
    }

    fun enqueue(response: WebResponse, fileName: String, mimeType: String?, totalBytes: Long, isPrivate: Boolean) {
        val id = if (isPrivate) privateIds.getAndDecrement() else db.insertDownload(fileName, response.uri, mimeType, totalBytes)
        pending[id] = DownloadJob(id, response, fileName, mimeType, totalBytes, isPrivate)
        changed()
        val intent = Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_START).putExtra(DownloadService.EXTRA_ID, id)
        ContextCompat.startForegroundService(context, intent)
    }

    fun take(id: Long): DownloadJob? = pending.remove(id)

    /** 在系统“下载”目录创建目标文件。Android 10+ 走 MediaStore，8/9 直接写公共目录（需存储权限）。 */
    fun createTarget(fileName: String, mimeType: String?): DownloadTarget {
        val mime = mimeType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在“下载”目录创建文件")
            val out = resolver.openOutputStream(uri) ?: error("无法写入文件")
            return DownloadTarget(uri, out) { success ->
                if (success) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: fileName
                } else {
                    resolver.delete(uri, null, null)
                    fileName
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = uniqueFile(dir, fileName)
            val out = file.outputStream()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            return DownloadTarget(uri, out) { success ->
                if (!success) file.delete()
                file.name
            }
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
        return f
    }

    companion object {
        fun openIntent(uri: Uri, mimeType: String?): Intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
