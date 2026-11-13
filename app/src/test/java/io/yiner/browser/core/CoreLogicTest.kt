package io.yiner.browser.core

import io.yiner.browser.browser.AdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlInputTest {
    private val g = SearchEngine.GOOGLE

    @Test fun defaultEngineIsGoogle() = assertEquals(SearchEngine.GOOGLE, SearchEngine.DEFAULT)

    @Test fun plainWordsAreSearched() =
        assertEquals("https://www.google.com/search?q=%E9%93%B6%E8%80%B3+%E6%B1%A4", UrlInput.toLoadableUrl("银耳 汤", g))

    @Test fun domainGetsHttps() = assertEquals("https://example.com", UrlInput.toLoadableUrl("example.com", g))

    @Test fun domainWithPathAndPort() =
        assertEquals("https://example.com:8443/a?b=1", UrlInput.toLoadableUrl("example.com:8443/a?b=1", g))

    @Test fun chineseDomain() = assertEquals("https://例子.中国", UrlInput.toLoadableUrl("例子.中国", g))

    @Test fun fullUrlKept() = assertEquals("http://foo.bar/x", UrlInput.toLoadableUrl("  http://foo.bar/x ", g))

    @Test fun localhostAndLanUseHttp() {
        assertEquals("http://localhost:3000", UrlInput.toLoadableUrl("localhost:3000", g))
        assertEquals("http://192.168.1.1", UrlInput.toLoadableUrl("192.168.1.1", g))
        assertEquals("https://8.8.8.8", UrlInput.toLoadableUrl("8.8.8.8", g))
    }

    @Test fun javascriptSchemeIsNotExecutedFromAddressBar() =
        assertTrue(UrlInput.toLoadableUrl("javascript:alert(1)", g).startsWith("https://www.google.com/search?q="))

    @Test fun singleWordWithoutDotIsSearch() =
        assertEquals("https://www.google.com/search?q=kotlin", UrlInput.toLoadableUrl("kotlin", g))

    @Test fun blankInput() = assertEquals("", UrlInput.toLoadableUrl("   ", g))

    @Test fun otherEngines() {
        assertEquals("https://www.bing.com/search?q=a%26b", UrlInput.toLoadableUrl("a&b", SearchEngine.BING))
        assertEquals("https://www.baidu.com/s?wd=test+q", UrlInput.toLoadableUrl("test q", SearchEngine.BAIDU))
    }

    @Test fun extractUrlFromSharedText() =
        assertEquals("https://example.com/a?b=1", UrlInput.extractUrl("看看这个 https://example.com/a?b=1。"))

    @Test fun displayHost() {
        assertEquals("example.com", UrlInput.displayHost("https://www.example.com/path?q"))
        assertEquals("", UrlInput.displayHost("about:blank"))
        assertEquals("host.com", UrlInput.displayHost("https://user:pw@host.com/"))
    }
}

class LinkPolicyTest {
    @Test fun webLoadsInBrowser() {
        assertEquals(LinkDecision.LoadInBrowser, LinkPolicy.decide("https://a.com", false))
        assertEquals(LinkDecision.LoadInBrowser, LinkPolicy.decide("moz-extension://x/dashboard.html", false))
        assertEquals(LinkDecision.LoadInBrowser, LinkPolicy.decide("blob:https://a.com/1", false))
    }

    @Test fun appSchemesNeedConfirmation() {
        assertEquals(LinkDecision.OpenExternally("tel:10086"), LinkPolicy.decide("tel:10086", false))
        assertTrue(LinkPolicy.decide("intent://x#Intent;scheme=foo;end", false) is LinkDecision.OpenExternally)
        assertTrue(LinkPolicy.decide("weixin://dl/business", false) is LinkDecision.OpenExternally)
    }

    @Test fun pagesCannotNavigateToLocalFiles() {
        assertEquals(LinkDecision.Block, LinkPolicy.decide("file:///sdcard/a.html", false))
        assertEquals(LinkDecision.LoadInBrowser, LinkPolicy.decide("file:///sdcard/a.html", true))
    }

    @Test fun intentFallbackOnlyHttp() {
        assertEquals(
            "https://m.example.com/x",
            LinkPolicy.intentFallbackUrl("intent://x#Intent;scheme=app;S.browser_fallback_url=https%3A%2F%2Fm.example.com%2Fx;end"),
        )
        assertNull(LinkPolicy.intentFallbackUrl("intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"))
        assertNull(LinkPolicy.intentFallbackUrl("intent://x#Intent;scheme=app;end"))
    }
}

class BackPolicyTest {
    private val base = BackContext(
        inFullscreen = false, overlayOpen = false, showingHome = false, hasTab = true,
        canGoBack = false, tabOpenedByOtherApp = false, tabHasParent = false,
    )

    @Test fun order() {
        assertEquals(BackAction.EXIT_FULLSCREEN, BackPolicy.decide(base.copy(inFullscreen = true, overlayOpen = true, canGoBack = true)))
        assertEquals(BackAction.CLOSE_OVERLAY, BackPolicy.decide(base.copy(overlayOpen = true, canGoBack = true)))
        assertEquals(BackAction.GO_BACK_IN_PAGE, BackPolicy.decide(base.copy(canGoBack = true, tabOpenedByOtherApp = true)))
        assertEquals(BackAction.CLOSE_TAB_AND_RETURN_TO_APP, BackPolicy.decide(base.copy(tabOpenedByOtherApp = true, tabHasParent = true)))
        assertEquals(BackAction.CLOSE_TAB_AND_SHOW_PARENT, BackPolicy.decide(base.copy(tabHasParent = true)))
        assertEquals(BackAction.SHOW_HOME, BackPolicy.decide(base))
        assertEquals(BackAction.SYSTEM, BackPolicy.decide(base.copy(showingHome = true)))
        assertEquals(BackAction.SYSTEM, BackPolicy.decide(base.copy(hasTab = false)))
    }
}

class ArchivePolicyTest {
    @Test fun archivesOnlyOldNormalTabs() {
        val now = 100L * 3_600_000
        val tabs = listOf(
            ArchiveCandidate("old", now - 25 * 3_600_000L, false),
            ArchiveCandidate("new", now - 1 * 3_600_000L, false),
            ArchiveCandidate("oldPrivate", now - 99 * 3_600_000L, true),
        )
        assertEquals(listOf("old"), ArchivePolicy.tabsToArchive(tabs, now, ArchiveAfter.DAY_1))
        assertEquals(emptyList<String>(), ArchivePolicy.tabsToArchive(tabs, now, ArchiveAfter.OFF))
    }
}

class DownloadNamingTest {
    private val ext: (String) -> String? = { mapOf("application/pdf" to "pdf", "image/png" to "png")[it] }

    @Test fun rfc5987() = assertEquals(
        "报告 2026.pdf",
        DownloadNaming.fromContentDisposition("attachment; filename=\"fallback.pdf\"; filename*=UTF-8''%E6%8A%A5%E5%91%8A%202026.pdf"),
    )

    @Test fun quotedAndBare() {
        assertEquals("a b.zip", DownloadNaming.fromContentDisposition("attachment; filename=\"a b.zip\""))
        assertEquals("c.txt", DownloadNaming.fromContentDisposition("attachment;filename=c.txt"))
        assertNull(DownloadNaming.fromContentDisposition("inline"))
    }

    @Test fun pathTraversalIsSanitized() {
        val n = DownloadNaming.fromContentDisposition("attachment; filename=\"../../etc/passwd\"")!!
        assertFalse(n.contains('/'))
    }

    @Test fun fromUrlAndMime() {
        assertEquals("file.pdf", DownloadNaming.choose(null, "https://x.com/dl/file?id=1", "application/pdf", ext))
        assertEquals("doc.pdf", DownloadNaming.choose(null, "https://x.com/doc.pdf#p", null, ext))
        assertEquals("download.png", DownloadNaming.choose(null, "blob:https://x.com/123", "image/png", ext))
    }

    @Test fun sizes() {
        assertEquals("未知大小", DownloadNaming.formatSize(-1))
        assertEquals("1.5 MB", DownloadNaming.formatSize(1_572_864))
    }
}

class SuggestionsTest {
    @Test fun openSearch() = assertEquals(
        listOf("银耳汤", "银耳羹"),
        Suggestions.parseOpenSearch("[\"银耳\",[\"银耳汤\",\"银耳羹\",\"银耳汤\"],[],{}]"),
    )

    @Test fun garbage() = assertEquals(emptyList<String>(), Suggestions.parseOpenSearch("<html>"))
}

class AdBlockerVersionTest {
    @Test fun compare() {
        assertTrue(AdBlocker.isOlder("1.75.2", "1.76.0"))
        assertFalse(AdBlocker.isOlder("1.76.0", "1.76.0"))
        assertFalse(AdBlocker.isOlder("1.77.0b3", "1.76.0"))
        assertTrue(AdBlocker.isOlder("1.9", "1.76"))
    }
}
