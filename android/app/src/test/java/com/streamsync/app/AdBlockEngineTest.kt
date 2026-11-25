package com.streamsync.app

import com.streamsync.app.adblock.FilterMatchInput
import com.streamsync.app.adblock.FilterRuleEngine
import com.streamsync.app.adblock.ResourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdBlockEngineTest {

    private lateinit var engine: FilterRuleEngine

    @Before
    fun setUp() {
        engine = FilterRuleEngine()
        engine.loadRules(
            sequenceOf(
                "! Sample uBlock rules",
                "@@||cdn.jsdelivr.net^",
                "||doubleclick.net^\$third-party",
                "||exoclick.com^",
                "/vast.xml\$xmlhttprequest,media,third-party",
                "*preroll*\$media,third-party",
                "##.adsbygoogle",
                "##.video-ad-overlay"
            )
        )
    }

    @Test
    fun blocksThirdPartyAdDomainsAndSubdomains() {
        assertTrue(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://pubads.g.doubleclick.net/gampad/ads?iu=/123",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.XHR
                )
            )
        )
        assertTrue(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://syndication.exoclick.com/splash.js",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.SCRIPT
                )
            )
        )
    }

    @Test
    fun respectsThirdPartyConstraintAndExceptions() {
        // First-party request on doubleclick.net itself should not match $third-party rule
        assertFalse(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://stats.doubleclick.net/main.js",
                    pageUrl = "https://www.doubleclick.net/index.html",
                    resourceKind = ResourceKind.SCRIPT
                )
            )
        )

        // Whitelisted CDN library must never be blocked
        assertFalse(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://cdn.jsdelivr.net/npm/hls.js@latest",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.SCRIPT
                )
            )
        )
    }

    @Test
    fun blocksVastAndPrerollMediaWhileAllowingCleanContentStream() {
        assertTrue(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://ads.adnetwork.io/v1/vast.xml?correlator=99",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.XHR
                )
            )
        )
        assertTrue(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://adcdn.other.net/videos/preroll_720p.mp4",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.MEDIA
                )
            )
        )
        // Clean content HLS stream on CDN is allowed
        assertFalse(
            engine.shouldBlock(
                FilterMatchInput(
                    requestUrl = "https://edge-cdn.streamhost.org/hls/movie_1080p/master.m3u8",
                    pageUrl = "https://streaming-example.com/watch/42",
                    resourceKind = ResourceKind.MEDIA
                )
            )
        )
    }

    @Test
    fun extractsCosmeticSelectors() {
        assertEquals(listOf(".adsbygoogle", ".video-ad-overlay"), engine.cosmeticSelectors)
    }
}
