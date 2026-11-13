package io.yiner.browser.core.update

/** 一个通过了来源与元数据检查、可以下载的新版本。 */
data class UpdateCandidate(
    val tag: String,
    val releaseName: String,
    val notes: String,
    val releasePage: String?,
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    /** GitHub 服务端计算的 sha256（若 Release 提供），与元数据中的值必须一致。 */
    val githubSha256: String?,
)

sealed interface UpdateDecision {
    data class Available(val candidate: UpdateCandidate) : UpdateDecision
    data class UpToDate(val remoteVersionName: String, val remoteVersionCode: Long) : UpdateDecision
    data class Unsupported(val reason: String) : UpdateDecision
    data class Invalid(val reason: String) : UpdateDecision
}

object UpdatePolicy {
    /** 第一步：从 Release 中找出可信的元数据附件。 */
    fun metadataAsset(release: ReleaseInfo, source: TrustedSource): Result<ReleaseAsset> {
        if (release.draft || release.prerelease) return Result.failure(UpdateParseException("最新 Release 不是正式版"))
        val asset = release.assets.singleOrNull { it.name == UpdateMetadata.ASSET_NAME }
            ?: return Result.failure(UpdateParseException("Release ${release.tag} 中没有 ${UpdateMetadata.ASSET_NAME}"))
        if (!source.isTrustedAssetUrl(asset.downloadUrl, release.tag, asset.name)) {
            return Result.failure(UpdateParseException("更新元数据的下载地址不属于本仓库"))
        }
        if (asset.size > UpdateMetadata.MAX_BYTES) return Result.failure(UpdateParseException("更新元数据过大"))
        return Result.success(asset)
    }

    /** 第二步：结合元数据与当前安装信息判断是否有可用更新。只比较 versionCode。 */
    fun decide(
        release: ReleaseInfo,
        metadata: UpdateMetadata,
        source: TrustedSource,
        packageName: String,
        currentVersionCode: Long,
        sdkInt: Int,
    ): UpdateDecision {
        if (release.draft || release.prerelease) return UpdateDecision.Invalid("最新 Release 不是正式版")
        if (metadata.packageName != packageName) {
            return UpdateDecision.Invalid("更新包的包名（${metadata.packageName}）与本应用不一致")
        }
        if (metadata.versionCode <= currentVersionCode) {
            return UpdateDecision.UpToDate(metadata.versionName, metadata.versionCode)
        }
        if (sdkInt < metadata.minSdk) {
            return UpdateDecision.Unsupported("新版本 ${metadata.versionName} 需要 Android API ${metadata.minSdk} 或更高")
        }
        val apk = release.assets.singleOrNull { it.name == metadata.apkName }
            ?: return UpdateDecision.Invalid("Release ${release.tag} 中没有 ${metadata.apkName}")
        if (!metadata.apkName.endsWith(".apk") || metadata.apkName.contains('/')) {
            return UpdateDecision.Invalid("更新包文件名无效")
        }
        if (!source.isTrustedAssetUrl(apk.downloadUrl, release.tag, apk.name)) {
            return UpdateDecision.Invalid("安装包的下载地址不属于本仓库")
        }
        if (apk.state != null && apk.state != "uploaded") return UpdateDecision.Invalid("安装包尚未上传完成")
        if (apk.size != metadata.size) return UpdateDecision.Invalid("安装包大小与元数据不一致")
        val githubSha = githubSha256(apk.digest)
        if (apk.digest != null && githubSha == null) return UpdateDecision.Invalid("无法识别 GitHub 提供的文件摘要")
        if (githubSha != null && githubSha != metadata.sha256) {
            return UpdateDecision.Invalid("GitHub 记录的文件摘要与元数据不一致")
        }
        return UpdateDecision.Available(
            UpdateCandidate(
                tag = release.tag,
                releaseName = release.name,
                notes = release.body.take(4000),
                releasePage = release.htmlUrl.takeIf { source.isTrustedReleasePage(it) },
                versionCode = metadata.versionCode,
                versionName = metadata.versionName,
                apkName = apk.name,
                apkUrl = apk.downloadUrl,
                size = apk.size,
                sha256 = metadata.sha256,
                githubSha256 = githubSha,
            ),
        )
    }

    /** "sha256:abcd…" → "abcd…"；其它算法或格式返回 null。 */
    fun githubSha256(digest: String?): String? {
        if (digest == null) return null
        val v = digest.trim().lowercase()
        if (!v.startsWith("sha256:")) return null
        return v.removePrefix("sha256:").takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
    }
}

/** 从已下载 APK 中读出的事实（由 PackageManager 解析）。 */
data class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    /** 当前签名证书的 SHA-256（十六进制小写）集合。 */
    val signerDigests: Set<String>,
)

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/**
 * 安装前校验。哈希只证明“下载到的就是 Release 里那个文件”（防损坏、防中途替换）；
 * 真正证明“是同一个开发者发布的”是签名证书与已安装应用一致——两者缺一不可。
 */
object ApkVerifier {
    fun verify(
        candidate: UpdateCandidate,
        actualSize: Long,
        actualSha256: String,
        archive: ArchiveFacts?,
        installedPackageName: String,
        installedVersionCode: Long,
        installedSignerDigests: Set<String>,
    ): VerifyResult {
        val sha = actualSha256.lowercase()
        return when {
            actualSize != candidate.size -> VerifyResult.Rejected("文件大小不符（应为 ${candidate.size}，实际 $actualSize）")
            sha != candidate.sha256 -> VerifyResult.Rejected("SHA-256 校验失败，文件可能已损坏或被篡改")
            candidate.githubSha256 != null && sha != candidate.githubSha256 ->
                VerifyResult.Rejected("与 GitHub 记录的文件摘要不一致")
            archive == null -> VerifyResult.Rejected("无法解析安装包")
            archive.packageName != installedPackageName ->
                VerifyResult.Rejected("安装包的包名（${archive.packageName}）与本应用不一致")
            archive.versionCode != candidate.versionCode ->
                VerifyResult.Rejected("安装包的 versionCode（${archive.versionCode}）与发布信息（${candidate.versionCode}）不一致")
            archive.versionCode <= installedVersionCode ->
                VerifyResult.Rejected("安装包版本（${archive.versionCode}）不比当前版本（$installedVersionCode）新")
            installedSignerDigests.isEmpty() -> VerifyResult.Rejected("无法读取当前应用的签名")
            archive.signerDigests.isEmpty() -> VerifyResult.Rejected("安装包没有有效签名")
            archive.signerDigests != installedSignerDigests ->
                VerifyResult.Rejected("安装包的签名证书与当前应用不同，已拒绝安装")
            else -> VerifyResult.Ok
        }
    }
}

/** 检查频率限制，避免频繁请求 GitHub API（未认证每个 IP 每小时 60 次）。 */
object CheckPolicy {
    const val AUTO_INTERVAL_MS = 12 * 3_600_000L
    const val MANUAL_MIN_GAP_MS = 60_000L
    const val PERIODIC_INTERVAL_HOURS = 24L

    fun mayCheck(manual: Boolean, now: Long, lastCheckAt: Long, rateLimitResetAt: Long): Boolean {
        if (now < rateLimitResetAt) return false
        val gap = now - lastCheckAt
        if (gap < 0) return true // 系统时间被往回调过
        return gap >= if (manual) MANUAL_MIN_GAP_MS else AUTO_INTERVAL_MS
    }

    /**
     * 根据响应头计算限流解除时间（毫秒）。优先 Retry-After（秒），其次 x-ratelimit-reset（Unix 秒）；
     * 都没有时保守等待 1 小时。
     */
    fun rateLimitResetAt(now: Long, retryAfter: String?, rateLimitReset: String?): Long {
        retryAfter?.trim()?.toLongOrNull()?.let { return now + it.coerceIn(1, 24 * 3600) * 1000 }
        rateLimitReset?.trim()?.toLongOrNull()?.let { reset ->
            val ms = reset * 1000
            if (ms > now) return minOf(ms, now + 24 * 3_600_000L)
        }
        return now + 3_600_000L
    }
}
