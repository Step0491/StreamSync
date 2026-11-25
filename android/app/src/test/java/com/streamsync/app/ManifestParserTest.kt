package com.streamsync.app

import com.streamsync.app.extractor.ManifestParser
import com.streamsync.app.extractor.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class ManifestParserTest {

    @Test
    fun stripsTransientRangeParametersWhilePreservingTokens() {
        val raw = "https://cdn.example.com/video.mp4?token=secret123&range=0-65535&bytestart=0&exp=99999"
        val clean = ManifestParser.stripRangeQueryParams(raw)
        assertEquals("https://cdn.example.com/video.mp4?token=secret123&exp=99999", clean)
    }

    @Test
    fun parsesHlsMasterWithDetachedAudioIntoCombinedVideoOnlyAndAudioOnly() {
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-aac",LANGUAGE="it",NAME="Italiano",DEFAULT=YES,CHANNELS="2",URI="audio/it/prog.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud-aac",LANGUAGE="en",NAME="English",DEFAULT=NO,CHANNELS="2",URI="audio/en/prog.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4800000,RESOLUTION=1920x1080,FRAME-RATE=60.000,CODECS="avc1.64002a,mp4a.40.2",AUDIO="aud-aac"
            video/1080p/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2200000,RESOLUTION=1280x720,FRAME-RATE=30.000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="aud-aac"
            video/720p/prog.m3u8
        """.trimIndent()

        val parsed = ManifestParser.parseHlsManifest(
            manifestUrl = "https://cdn.cinema.example/movies/42/master.m3u8",
            content = masterM3u8,
            headers = mapOf("Referer" to "https://cinema.example/watch/42"),
            pageTitle = "Sample Film"
        )

        assertNotNull(parsed)
        parsed!!
        assertFalse(parsed.isDrmProtected)

        val combined = parsed.streams.filter { it.kind == StreamKind.COMBINED }
        val videoOnly = parsed.streams.filter { it.kind == StreamKind.VIDEO_ONLY }
        val audioOnly = parsed.streams.filter { it.kind == StreamKind.AUDIO_ONLY }

        // Master playlist is COMBINED
        assertEquals(1, combined.size)
        assertTrue(combined[0].isMasterPlaylist)
        assertEquals(1080, combined[0].height)

        // Child video variant playlists are accurately classified as VIDEO_ONLY (not COMBINED!)
        assertEquals(2, videoOnly.size)
        val v1080 = videoOnly.first { it.height == 1080 }
        assertEquals("https://cdn.cinema.example/movies/42/video/1080p/prog.m3u8", v1080.url)
        assertNull(v1080.acodec)
        assertEquals(60.0, v1080.fps!!, 0.01)

        // Detached audio playlists are classified as AUDIO_ONLY with language metadata
        assertEquals(2, audioOnly.size)
        assertTrue(audioOnly.any { it.language == "it" && it.url.endsWith("audio/it/prog.m3u8") })
        assertTrue(audioOnly.any { it.language == "en" && it.url.endsWith("audio/en/prog.m3u8") })

        // Child override map reclassifies intercepted child .m3u8 URLs
        val childOverride = parsed.childOverrides["https://cdn.cinema.example/movies/42/video/1080p/prog.m3u8"]
        assertNotNull(childOverride)
        assertEquals(StreamKind.VIDEO_ONLY, childOverride!!.kind)
    }

    @Test
    fun parsesDashManifestWithBaseUrlTracksAndDetectsWidevineDrm() {
        val cleanMpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation id="v1" width="1920" height="1080" bandwidth="5200000" frameRate="24000/1001" codecs="avc1.640028">
                    <BaseURL>video_1080p.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="ja">
                  <Representation id="a1" bandwidth="160000" codecs="mp4a.40.2">
                    <BaseURL>audio_ja.m4a</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val dash = ManifestParser.parseDashManifest("https://cdn.example.org/dash/manifest.mpd", cleanMpd)
        assertNotNull(dash)
        dash!!
        assertFalse(dash.isDrmProtected)
        assertEquals(StreamKind.COMBINED, dash.streams[0].kind)
        assertEquals(1080, dash.streams[0].height)
        assertTrue(dash.streams.any { it.kind == StreamKind.VIDEO_ONLY && it.url == "https://cdn.example.org/dash/video_1080p.mp4" })
        assertTrue(dash.streams.any { it.kind == StreamKind.AUDIO_ONLY && it.language == "ja" })

        val drmMpd = cleanMpd.replace(
            "<AdaptationSet contentType=\"video\"",
            "<AdaptationSet contentType=\"video\"><ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"/>"
        )
        val drmDash = ManifestParser.parseDashManifest("https://cdn.example.org/dash/enc.mpd", drmMpd)
        assertTrue(drmDash!!.isDrmProtected)
    }

    @Test
    fun inspectsIsoBmffMoovHandlerBoxesToSeparateVideoOnlyFromMuxedMp4() {
        val muxedBytes = buildFakeMp4Header(includeVideo = true, includeAudio = true)
        val videoOnlyBytes = buildFakeMp4Header(includeVideo = true, includeAudio = false)
        val audioOnlyBytes = buildFakeMp4Header(includeVideo = false, includeAudio = true)

        val muxed = ManifestParser.inspectContainerBytes(muxedBytes, "video/mp4", "https://cdn.example.com/movie.mp4")
        assertEquals(StreamKind.COMBINED, muxed.kind)

        val vOnly = ManifestParser.inspectContainerBytes(videoOnlyBytes, "video/mp4", "https://cdn.example.com/track1.mp4")
        assertEquals(StreamKind.VIDEO_ONLY, vOnly.kind)

        val aOnly = ManifestParser.inspectContainerBytes(audioOnlyBytes, "audio/mp4", "https://cdn.example.com/track2.mp4")
        assertEquals(StreamKind.AUDIO_ONLY, aOnly.kind)
    }

    private fun buildFakeMp4Header(includeVideo: Boolean, includeAudio: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeBox(fourcc: String, payload: ByteArray) {
            val size = 8 + payload.size
            out.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            out.write(fourcc.toByteArray(StandardCharsets.US_ASCII))
            out.write(payload)
        }
        fun buildHdlrPayload(handlerFourCc: String, codecMarker: String): ByteArray {
            val p = ByteArrayOutputStream()
            p.write(ByteArray(8)) // version/flags + pre_defined
            p.write(handlerFourCc.toByteArray(StandardCharsets.US_ASCII))
            p.write(ByteArray(12))
            p.write(codecMarker.toByteArray(StandardCharsets.US_ASCII))
            return p.toByteArray()
        }

        writeBox("ftyp", "isom0000".toByteArray(StandardCharsets.US_ASCII))
        val moovPayload = ByteArrayOutputStream()
        if (includeVideo) {
            val hdlr = buildHdlrPayload("vide", "avc1")
            val size = 8 + hdlr.size
            moovPayload.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            moovPayload.write("hdlr".toByteArray(StandardCharsets.US_ASCII))
            moovPayload.write(hdlr)
        }
        if (includeAudio) {
            val hdlr = buildHdlrPayload("soun", "mp4a")
            val size = 8 + hdlr.size
            moovPayload.write(byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()))
            moovPayload.write("hdlr".toByteArray(StandardCharsets.US_ASCII))
            moovPayload.write(hdlr)
        }
        writeBox("moov", moovPayload.toByteArray())
        return out.toByteArray()
    }
}
