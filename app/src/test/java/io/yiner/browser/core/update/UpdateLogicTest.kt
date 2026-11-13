package io.yiner.browser.core.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val REPO = "yinerx/yiner-browser"
private const val PKG = "io.yiner.browser"
private val SHA = "ab".repeat(32)
private val OTHER_SHA = "cd".repeat(32)
private const val SIZE = 215_000_000L

private fun assetUrl(tag: String, name: String) = "https://github.com/$REPO/releases/download/$tag/$name"

private fun releaseJson(
    tag: String = "v0.3.0",
    draft: Boolean = false,
    prerelease: Boolean = false,
    apkUrl: String? = null,
    metaUrl: String? = null,
    apkSize: Long = SIZE,
    apkDigest: String? = "sha256:$SHA",
    includeMeta: Boolean = true,
): String {
    val assets = JSONArray()
    assets.put(
        JSONObject().put("name", "yiner-browser-0.3.0.apk").put("size", apkSize).put("state", "uploaded")
            .put("browser_download_url", apkUrl ?: assetUrl(tag, "yiner-browser-0.3.0.apk"))
            .apply { if (apkDigest != null) put("digest", apkDigest) },
    )
    if (includeMeta) {
        assets.put(
            JSONObject().put("name", "yiner-update.json").put("size", 300).put("state", "uploaded")
                .put("browser_download_url", metaUrl ?: assetUrl(tag, "yiner-update.json")),
        )
    }
    return JSONObject().put("tag_name", tag).put("name", "银耳浏览器 0.3.0").put("body", "- 修复若干问题")
        .put("draft", draft).put("prerelease", prerelease)
        .put("html_url", "https://github.com/$REPO/releases/tag/$tag").put("assets", assets).toString()
}

private fun metadataJson(
    versionCode: Long = 3,
    pkg: String = PKG,
    sha: String = SHA,
    size: Long = SIZE,
    minSdk: Int = 26,
    schema: Int = 1,
) = JSONObject().put("schema", schema).put("packageName", pkg).put("versionCode", versionCode)
    .put("versionName", "0.3.0").put("minSdk", minSdk).put("apk", "yiner-browser-0.3.0.apk")
    .put("sha256", sha).put("size", size).toString()

class ReleaseParserTest {
    @Test fun parsesRelease() {
        val r = ReleaseParser.parseRelease(releaseJson())
        assertEquals("v0.3.0", r.tag)
        assertFalse(r.draft || r.prerelease)
        assertEquals(2, r.assets.size)
        assertEquals("sha256:$SHA", r.assets[0].digest)
        assertNull(r.assets[1].digest)
    }

    @Test fun parsesMetadata() {
        val m = ReleaseParser.parseMetadata(metadataJson())
        assertEquals(3L, m.versionCode)
        assertEquals(PKG, m.packageName)
        assertEquals(SHA, m.sha256)
    }

    @Test fun rejectsBadMetadata() {
        for (bad in listOf(
            metadataJson(versionCode = 0),
            metadataJson(sha = "xyz"),
            metadataJson(size = 0),
            metadataJson(schema = 2),
            "{}",
            "not json",
        )) {
            try {
                ReleaseParser.parseMetadata(bad); fail("应当拒绝：$bad")
            } catch (e: UpdateParseException) {
                // 预期
            }
        }
    }

    @Test(expected = UpdateParseException::class)
    fun rejectsGarbageRelease() {
        ReleaseParser.parseRelease("<html>rate limited</html>")
    }
}

class TrustedSourceTest {
    private val src = TrustedSource(REPO)

    @Test fun apiIsPublicLatestEndpoint() =
        assertEquals("https://api.github.com/repos/yinerx/yiner-browser/releases/latest", src.latestReleaseApi)

    @Test fun acceptsExactAssetUrl() {
        assertTrue(src.isTrustedAssetUrl(assetUrl("v0.3.0", "a.apk"), "v0.3.0", "a.apk"))
        assertTrue(src.isTrustedAssetUrl("https://github.com/YinerX/Yiner-Browser/releases/download/v0.3.0/a.apk", "v0.3.0", "a.apk"))
    }

    @Test fun rejectsOtherSources() {
        val bad = listOf(
            "http://github.com/$REPO/releases/download/v0.3.0/a.apk",            // 非 HTTPS
            "https://github.com/evil/yiner-browser/releases/download/v0.3.0/a.apk", // 其它仓库
            "https://github.com.evil.com/$REPO/releases/download/v0.3.0/a.apk",  // 伪造主机
            "https://evil.com/$REPO/releases/download/v0.3.0/a.apk",
            "https://user@github.com/$REPO/releases/download/v0.3.0/a.apk",      // userinfo
            "https://github.com:8443/$REPO/releases/download/v0.3.0/a.apk",      // 端口
            "https://github.com/$REPO/releases/download/v0.3.0/a.apk?x=1",       // 查询参数
            "https://github.com/$REPO/releases/download/v0.2.0/a.apk",           // 其它 tag
            "https://github.com/$REPO/releases/download/v0.3.0/b.apk",           // 其它文件
            "https://github.com/$REPO/releases/download/v0.3.0/../../x/a.apk",
            "https://github.com/$REPO/releases/download/v0.3.0/sub/a.apk",
            "file:///sdcard/a.apk",
            "not a url",
        )
        bad.forEach { assertFalse(it, src.isTrustedAssetUrl(it, "v0.3.0", "a.apk")) }
    }

    @Test fun redirectAllowlist() {
        assertTrue(TrustedSource.isAllowedRedirect("https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=x"))
        assertTrue(TrustedSource.isAllowedRedirect("https://objects.githubusercontent.com/x"))
        assertFalse(TrustedSource.isAllowedRedirect("http://release-assets.githubusercontent.com/x"))
        assertFalse(TrustedSource.isAllowedRedirect("https://evil.githubusercontent.com.attacker.net/x"))
        assertFalse(TrustedSource.isAllowedRedirect("https://example.com/a.apk"))
    }

    @Test fun releasePageOnlyThisRepo() {
        assertTrue(src.isTrustedReleasePage("https://github.com/$REPO/releases/tag/v0.3.0"))
        assertFalse(src.isTrustedReleasePage("https://github.com/other/repo/releases/tag/v0.3.0"))
        assertFalse(src.isTrustedReleasePage("javascript:alert(1)"))
    }
}

class UpdatePolicyTest {
    private val src = TrustedSource(REPO)

    private fun decide(
        release: String = releaseJson(),
        meta: String = metadataJson(),
        current: Long = 2,
        sdk: Int = 34,
    ) = UpdatePolicy.decide(ReleaseParser.parseRelease(release), ReleaseParser.parseMetadata(meta), src, PKG, current, sdk)

    @Test fun newerVersionCodeIsAvailable() {
        val d = decide()
        assertTrue(d is UpdateDecision.Available)
        val c = (d as UpdateDecision.Available).candidate
        assertEquals(3L, c.versionCode)
        assertEquals("0.3.0", c.versionName)
        assertEquals(SHA, c.githubSha256)
        assertEquals("- 修复若干问题", c.notes)
    }

    @Test fun comparesVersionCodeNotVersionName() {
        // 远端 versionName 看起来“更大”，但 versionCode 不更新 → 不升级
        val meta = JSONObject(metadataJson(versionCode = 2)).put("versionName", "9.9.9").toString()
        assertTrue(decide(meta = meta) is UpdateDecision.UpToDate)
        // 远端 versionName 看起来“更小”，但 versionCode 更大 → 升级
        val meta2 = JSONObject(metadataJson(versionCode = 10)).put("versionName", "0.0.1").toString()
        assertTrue(decide(meta = meta2) is UpdateDecision.Available)
    }

    @Test fun sameOrOlderIsUpToDate() {
        assertTrue(decide(current = 3) is UpdateDecision.UpToDate)
        assertTrue(decide(current = 5) is UpdateDecision.UpToDate)
    }

    @Test fun rejectsDraftAndPrerelease() {
        assertTrue(decide(release = releaseJson(prerelease = true)) is UpdateDecision.Invalid)
        assertTrue(decide(release = releaseJson(draft = true)) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.metadataAsset(ReleaseParser.parseRelease(releaseJson(prerelease = true)), src).isFailure)
    }

    @Test fun rejectsOtherPackage() =
        assertTrue(decide(meta = metadataJson(pkg = "com.evil.app")) is UpdateDecision.Invalid)

    @Test fun rejectsUntrustedApkUrl() {
        val r = releaseJson(apkUrl = "https://evil.com/yiner-browser-0.3.0.apk")
        assertTrue(decide(release = r) is UpdateDecision.Invalid)
    }

    @Test fun rejectsUntrustedMetadataUrl() {
        val r = ReleaseParser.parseRelease(releaseJson(metaUrl = "https://evil.com/yiner-update.json"))
        assertTrue(UpdatePolicy.metadataAsset(r, src).isFailure)
    }

    @Test fun missingMetadataAsset() {
        val r = ReleaseParser.parseRelease(releaseJson(includeMeta = false))
        assertTrue(UpdatePolicy.metadataAsset(r, src).isFailure)
    }

    @Test fun rejectsSizeOrDigestMismatch() {
        assertTrue(decide(release = releaseJson(apkSize = SIZE - 1)) is UpdateDecision.Invalid)
        assertTrue(decide(release = releaseJson(apkDigest = "sha256:$OTHER_SHA")) is UpdateDecision.Invalid)
        assertTrue(decide(release = releaseJson(apkDigest = "md5:abc")) is UpdateDecision.Invalid)
    }

    @Test fun worksWithoutGithubDigest() {
        val d = decide(release = releaseJson(apkDigest = null))
        assertTrue(d is UpdateDecision.Available)
        assertNull((d as UpdateDecision.Available).candidate.githubSha256)
    }

    @Test fun unsupportedSdk() = assertTrue(decide(meta = metadataJson(minSdk = 40), sdk = 34) is UpdateDecision.Unsupported)

    @Test fun githubDigestParsing() {
        assertEquals(SHA, UpdatePolicy.githubSha256("sha256:${SHA.uppercase()}"))
        assertNull(UpdatePolicy.githubSha256("sha512:$SHA"))
        assertNull(UpdatePolicy.githubSha256(null))
    }
}

class ApkVerifierTest {
    private val signer = setOf("11".repeat(32))
    private val candidate = (
        UpdatePolicy.decide(
            ReleaseParser.parseRelease(releaseJson()), ReleaseParser.parseMetadata(metadataJson()),
            TrustedSource(REPO), PKG, 2, 34,
        ) as UpdateDecision.Available
        ).candidate

    private fun verify(
        size: Long = SIZE,
        sha: String = SHA,
        archive: ArchiveFacts? = ArchiveFacts(PKG, 3, signer),
        installedVersion: Long = 2,
        installedSigners: Set<String> = signer,
    ) = ApkVerifier.verify(candidate, size, sha, archive, PKG, installedVersion, installedSigners)

    @Test fun acceptsMatchingApk() = assertEquals(VerifyResult.Ok, verify())

    @Test fun acceptsUppercaseHash() = assertEquals(VerifyResult.Ok, verify(sha = SHA.uppercase()))

    @Test fun rejectsHashMismatch() = assertTrue(verify(sha = OTHER_SHA) is VerifyResult.Rejected)

    @Test fun rejectsSizeMismatch() = assertTrue(verify(size = SIZE + 1) is VerifyResult.Rejected)

    @Test fun rejectsUnparseableApk() = assertTrue(verify(archive = null) is VerifyResult.Rejected)

    @Test fun rejectsOtherPackageName() =
        assertTrue(verify(archive = ArchiveFacts("com.evil.app", 3, signer)) is VerifyResult.Rejected)

    @Test fun rejectsVersionCodeDifferentFromRelease() =
        assertTrue(verify(archive = ArchiveFacts(PKG, 4, signer)) is VerifyResult.Rejected)

    @Test fun rejectsNotNewerThanInstalled() =
        assertTrue(verify(installedVersion = 3) is VerifyResult.Rejected)

    @Test fun rejectsDifferentSigner() {
        val r = verify(archive = ArchiveFacts(PKG, 3, setOf("22".repeat(32))))
        assertTrue(r is VerifyResult.Rejected)
        assertTrue((r as VerifyResult.Rejected).reason.contains("签名"))
    }

    @Test fun rejectsExtraSigner() =
        assertTrue(verify(archive = ArchiveFacts(PKG, 3, signer + "33".repeat(32))) is VerifyResult.Rejected)

    @Test fun rejectsUnsignedOrUnknownInstalledSigner() {
        assertTrue(verify(archive = ArchiveFacts(PKG, 3, emptySet())) is VerifyResult.Rejected)
        assertTrue(verify(installedSigners = emptySet()) is VerifyResult.Rejected)
    }
}

class CheckPolicyTest {
    private val h = 3_600_000L

    @Test fun autoCheckThrottled() {
        val now = 100 * h
        assertFalse(CheckPolicy.mayCheck(false, now, now - 2 * h, 0))
        assertTrue(CheckPolicy.mayCheck(false, now, now - 13 * h, 0))
        assertTrue(CheckPolicy.mayCheck(false, now, 0, 0))
    }

    @Test fun manualCheckHasShortGap() {
        val now = 100 * h
        assertFalse(CheckPolicy.mayCheck(true, now, now - 10_000, 0))
        assertTrue(CheckPolicy.mayCheck(true, now, now - 2 * 60_000, 0))
    }

    @Test fun rateLimitBlocksEvenManual() {
        val now = 100 * h
        assertFalse(CheckPolicy.mayCheck(true, now, 0, now + 60_000))
        assertTrue(CheckPolicy.mayCheck(true, now, 0, now - 1))
    }

    @Test fun clockMovedBackwards() = assertTrue(CheckPolicy.mayCheck(false, 10 * h, 50 * h, 0))

    @Test fun rateLimitReset() {
        val now = 1_000_000_000_000L
        assertEquals(now + 120_000, CheckPolicy.rateLimitResetAt(now, "120", null))
        assertEquals(now / 1000 * 1000 + 600_000, CheckPolicy.rateLimitResetAt(now, null, ((now / 1000) + 600).toString()))
        assertEquals(now + h, CheckPolicy.rateLimitResetAt(now, null, null))
        // reset 时间在过去：保守等 1 小时
        assertEquals(now + h, CheckPolicy.rateLimitResetAt(now, null, "1"))
    }
}
