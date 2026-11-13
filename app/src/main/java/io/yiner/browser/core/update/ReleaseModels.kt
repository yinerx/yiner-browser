package io.yiner.browser.core.update

import org.json.JSONObject

/** GitHub Release 中的一个附件。 */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    /** GitHub 服务端计算的摘要，形如 "sha256:..."；旧的 Release 可能没有。 */
    val digest: String?,
    val state: String?,
)

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
)

/**
 * 发布流程生成的更新元数据（Release 附件 [UpdateMetadata.ASSET_NAME]）。
 * 内容来自 CI 对已签名 APK 的 aapt2 解析，安装前还会与 APK 本身逐项比对。
 */
data class UpdateMetadata(
    val schema: Int,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkName: String,
    val sha256: String,
    val size: Long,
) {
    companion object {
        const val ASSET_NAME = "yiner-update.json"
        const val SUPPORTED_SCHEMA = 1
        const val MAX_BYTES = 64 * 1024
    }
}

class UpdateParseException(message: String) : Exception(message)

object ReleaseParser {
    /** 解析 GET /repos/{owner}/{repo}/releases/latest 的响应。 */
    fun parseRelease(json: String): ReleaseInfo {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UpdateParseException("Release 信息不是有效的 JSON")
        }
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: throw UpdateParseException("Release 缺少 tag_name")
        val arr = o.optJSONArray("assets")
        val assets = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReleaseAsset(
                name = name,
                size = a.optLong("size", -1),
                downloadUrl = a.optString("browser_download_url"),
                digest = a.optString("digest").takeIf { it.isNotBlank() && it != "null" },
                state = a.optString("state").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return ReleaseInfo(
            tag = tag,
            name = o.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: tag,
            body = o.optString("body").takeIf { it != "null" }.orEmpty(),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            htmlUrl = o.optString("html_url"),
            assets = assets,
        )
    }

    fun parseMetadata(json: String): UpdateMetadata {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw UpdateParseException("更新元数据不是有效的 JSON")
        }
        fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() } ?: throw UpdateParseException("更新元数据缺少 $k")
        val schema = o.optInt("schema", -1)
        if (schema != UpdateMetadata.SUPPORTED_SCHEMA) throw UpdateParseException("不支持的更新元数据格式（schema=$schema）")
        val versionCode = o.optLong("versionCode", -1)
        if (versionCode <= 0) throw UpdateParseException("更新元数据中的 versionCode 无效")
        val sha = str("sha256").lowercase()
        if (!Regex("^[0-9a-f]{64}$").matches(sha)) throw UpdateParseException("更新元数据中的 sha256 格式无效")
        val size = o.optLong("size", -1)
        if (size <= 0) throw UpdateParseException("更新元数据中的 size 无效")
        return UpdateMetadata(
            schema = schema,
            packageName = str("packageName"),
            versionCode = versionCode,
            versionName = str("versionName"),
            minSdk = o.optInt("minSdk", 1),
            apkName = str("apk"),
            sha256 = sha,
            size = size,
        )
    }
}
