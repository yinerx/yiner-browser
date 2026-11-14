package io.yiner.browser.browser

import android.util.Base64
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

/**
 * blob: 下载。blob 只存在于网页内存中，需在页面里读出后交给应用：
 * 用户确认下载后，应用向该页面注入一段脚本（带一次性随机 nonce），脚本读出 blob 后经消息通道回传；
 * 应用只接受与待处理 nonce 匹配的消息，网页无法自行触发保存文件。
 */
object BlobDownloads {
    private const val OBJECT = "yinerBlob"
    const val MAX_BYTES = 40 * 1024 * 1024
    private val pending = HashMap<String, (ByteArray?, String?, String?) -> Unit>()

    fun supported() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }.getOrDefault(false)

    fun install(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.addWebMessageListener(view, OBJECT, setOf("*")) { _, message, _, _, _ ->
            val o = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
            val cb = pending.remove(o.optString("n")) ?: return@addWebMessageListener
            val error = o.optString("e").ifBlank { null }
            if (error != null) {
                cb(null, null, error); return@addWebMessageListener
            }
            val dataUrl = o.optString("d")
            val bytes = decodeDataUrl(dataUrl)
            cb(bytes, o.optString("t").ifBlank { null }, if (bytes == null) "无法读取文件内容" else null)
        }
    }

    /** 读取页面中的 blob；回调参数：内容、MIME 类型、错误信息。 */
    fun fetch(view: WebView, blobUrl: String, callback: (ByteArray?, String?, String?) -> Unit) {
        if (!supported()) {
            callback(null, null, "系统 WebView 版本过旧，不支持此类下载"); return
        }
        val nonce = UUID.randomUUID().toString()
        pending[nonce] = callback
        val js = """
            (async function(){var c=window.$OBJECT;if(!c)return;try{
              var r=await fetch(${JSONObject.quote(blobUrl)});var b=await r.blob();
              if(b.size>$MAX_BYTES){c.postMessage(JSON.stringify({n:${JSONObject.quote(nonce)},e:'文件超过 40 MB，无法通过此方式下载'}));return}
              var f=new FileReader();f.onload=function(){c.postMessage(JSON.stringify({n:${JSONObject.quote(nonce)},d:f.result,t:b.type}))};
              f.onerror=function(){c.postMessage(JSON.stringify({n:${JSONObject.quote(nonce)},e:'读取失败'}))};f.readAsDataURL(b);
            }catch(e){c.postMessage(JSON.stringify({n:${JSONObject.quote(nonce)},e:String(e)}))}})();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    /** "data:[mime][;base64],payload" → 字节；不合法返回 null。 */
    fun decodeDataUrl(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val meta = dataUrl.substring(5, comma)
        val payload = dataUrl.substring(comma + 1)
        return runCatching {
            if (meta.endsWith(";base64")) Base64.decode(payload, Base64.DEFAULT)
            else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }.getOrNull()
    }

    fun dataUrlMime(dataUrl: String): String? =
        dataUrl.removePrefix("data:").substringBefore(',').substringBefore(';').ifBlank { null }
}
