package io.yiner.browser.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private val rules = """
        [Adblock Plus 2.0]
        ! 注释
        ||ads.example.com^
        ||tracker.net^${'$'}third-party
        ||cdn.example.org/banners/
        ||wild*card.com^
        ||opts.com^${'$'}popup
        ||typed.net^${'$'}script,third-party,xmlhttprequest
        ||imgonly.net^${'$'}image
        @@||good.ads.example.com^
        ||dc.example.net^
        @@||dc.example.net/ddm/${'$'}image,domain=video.example.com
        @@||dc.example.net/gampad/ads?*&iu=x${'$'}xmlhttprequest,domain=shop.example.com
        ##.ad-banner
        ##div[id^="sponsor"]
        ##.foo:has-text(Sponsored)
        news.example.com,shop.example.com##.promo
        news.example.com#@#.ad-banner
        ~exclude.com##.neg
        example.com#?#.procedural
        /regex-like/
    """.trimIndent()

    private val engine = FilterEngine.parse(rules)

    @Test fun blocksDomainAndSubdomains() {
        assertTrue(engine.shouldBlock("https://ads.example.com/x.js", "site.com"))
        assertTrue(engine.shouldBlock("https://img.ads.example.com/x.png", "site.com"))
        assertFalse(engine.shouldBlock("https://example.com/", "site.com"))
        assertFalse(engine.shouldBlock("https://notads.example.com/", "site.com"))
    }

    @Test fun exceptionWins() = assertFalse(engine.shouldBlock("https://good.ads.example.com/a.js", "site.com"))

    @Test fun exceptionsKeepTheirPathAndDomainScope() {
        // 第一版把带路径/domain= 的例外当成整域放行，导致 doubleclick 全部漏拦；这里防止回归
        assertTrue(engine.shouldBlock("https://dc.example.net/gampad/ads?x", "news.example.com"))
        assertTrue(engine.shouldBlock("https://dc.example.net/ddm/1.gif", "news.example.com"))
        assertFalse(engine.shouldBlock("https://dc.example.net/ddm/1.gif", "www.video.example.com"))
        assertFalse(engine.shouldBlock("https://dc.example.net/gampad/ads?a=1&iu=x", "shop.example.com"))
        assertTrue(engine.shouldBlock("https://dc.example.net/other", "shop.example.com"))
    }

    @Test fun thirdPartyOnly() {
        assertTrue(engine.shouldBlock("https://tracker.net/p.gif", "site.com"))
        assertFalse(engine.shouldBlock("https://www.tracker.net/p.gif", "tracker.net"))
    }

    @Test fun pathPrefix() {
        assertTrue(engine.shouldBlock("https://cdn.example.org/banners/1.png", "site.com"))
        assertFalse(engine.shouldBlock("https://cdn.example.org/images/1.png", "site.com"))
    }

    @Test fun unsupportedRulesAreSkippedNotGuessed() {
        assertFalse(engine.shouldBlock("https://wildcard.com/", "site.com"))
        assertFalse(engine.shouldBlock("https://opts.com/a.js", "site.com"))
        assertTrue(engine.stats.skippedRules >= 5)
    }

    @Test fun resourceTypeRules() {
        val unknown = FilterEngine.ResourceType.UNKNOWN
        val image = FilterEngine.ResourceType.IMAGE
        assertTrue(engine.shouldBlock("https://typed.net/a.js", "site.com", unknown))
        assertFalse("仅第三方", engine.shouldBlock("https://typed.net/a.js", "typed.net", unknown))
        assertFalse("规则不含 image", engine.shouldBlock("https://typed.net/a.png", "site.com", image))
        assertTrue(engine.shouldBlock("https://imgonly.net/a.png", "site.com", image))
        assertFalse("仅图片规则不应拦截脚本", engine.shouldBlock("https://imgonly.net/a.js", "site.com", unknown))
        assertEquals(image, FilterEngine.typeFromAccept("image/avif,image/webp,image/apng,*/*;q=0.8"))
        assertEquals(FilterEngine.ResourceType.STYLESHEET, FilterEngine.typeFromAccept("text/css,*/*;q=0.1"))
        assertEquals(unknown, FilterEngine.typeFromAccept("*/*"))
    }

    @Test fun nonHttpIgnored() {
        assertFalse(engine.shouldBlock("data:text/plain,ads.example.com", "site.com"))
        assertFalse(engine.shouldBlock("blob:https://ads.example.com/1", "site.com"))
    }

    @Test fun cosmeticCss() {
        val generic = engine.cssFor("other.com")
        assertTrue(generic.contains(".ad-banner"))
        assertTrue(generic.contains("div[id^=\"sponsor\"]"))
        assertFalse(generic.contains("has-text"))
        assertFalse(generic.contains(".promo"))
        val news = engine.cssFor("www.news.example.com")
        assertTrue(news.contains(".promo"))
        assertFalse("站点例外应移除通用规则", news.contains(".ad-banner"))
        assertFalse(engine.cssFor("x.com").contains(".neg"))
    }

    @Test fun baseDomainApproximation() {
        assertEquals("example.com", FilterEngine.baseDomain("a.b.example.com"))
        assertEquals("bbc.co.uk", FilterEngine.baseDomain("www.bbc.co.uk"))
        assertTrue(FilterEngine.isThirdParty("cdn.other.com", "www.example.com"))
        assertFalse(FilterEngine.isThirdParty("static.example.com", "www.example.com"))
    }

    /** 用随 APK 打包的 EasyList / EasyPrivacy 快照做一次真实加载。 */
    @Test fun bundledListsLoad() {
        val dir = listOf(File("src/main/assets/filters"), File("app/src/main/assets/filters")).first { it.isDirectory }
        val e = FilterEngine.parse(File(dir, "easylist.txt").readText(), File(dir, "easyprivacy.txt").readText())
        println("YINER_RULE_STATS ${e.stats}")
        assertTrue("网络规则数应超过 8 万：${e.stats}", e.stats.networkRules > 80_000)
        assertTrue("元素隐藏规则应超过 1 万：${e.stats}", e.stats.cosmeticRules > 10_000)
        assertTrue(e.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "news.example.com"))
        assertTrue(e.shouldBlock("https://www.google-analytics.com/analytics.js", "news.example.com"))
        assertFalse(e.shouldBlock("https://www.example.com/index.html", "www.example.com"))
        assertFalse("不能拦截 Google 登录页的主要域名", e.shouldBlock("https://accounts.google.com/v3/signin/identifier", "accounts.google.com"))
    }
}
