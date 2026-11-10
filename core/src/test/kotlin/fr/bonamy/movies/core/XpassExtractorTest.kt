package fr.bonamy.movies.core

import com.google.gson.Gson
import fr.bonamy.movies.core.extractors.TmdbPlayback
import fr.bonamy.movies.core.extractors.XpassExtractor
import fr.bonamy.movies.core.sites.vidbox.VidboxClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class XpassExtractorTest {
    @Test fun vidboxRoutesExplicitVipSelectionToItsOwnExtractor() = fixture { test ->
        val playback = VidboxClient(xpassOrigin = test.server.url("/"))
            .resolve(TmdbPlayback("1765072"), XpassExtractor.VIP_SOURCE.id)
        assertEquals(test.server.url("/media/master.m3u8").toString(), playback.streamUrl)
        assertEquals(1, test.selectedRequests.size)
    }

    @Test fun discoversNamedMirrorsAndResolvesFreshSelectedUrlsWithMediaHeaders() = fixture { test ->
        val extractor = test.extractor
        assertEquals(listOf(XpassExtractor.MOVIE_SOURCES.getValue("TOK 1"), XpassExtractor.VIP_SOURCE), extractor.sources(TmdbPlayback("1765072")))
        val playback = extractor.resolve(TmdbPlayback("1765072"))
        assertEquals(test.server.url("/media/master.m3u8").toString(), playback.streamUrl)
        assertEquals(test.server.url("/e/movie/1765072").toString(), playback.requestHeaders["Referer"])
        assertEquals(test.server.url("/").toString().removeSuffix("/"), playback.requestHeaders["Origin"])
        assertEquals(HttpTransport.USER_AGENT, playback.requestHeaders["User-Agent"])
        assertEquals(2, test.embedRequests)
        assertEquals(1, test.scriptRequests)
        assertTrue(test.selectedRequests.single().contains("fresh=2"))
        assertFalse(test.paths.any { it.startsWith("/other") })
    }

    @Test fun rejectsTamperedResponsesAndDoesNotFallBackWhenVipDisappears() = fixture { test ->
        test.tamper = true
        assertThrows(IOException::class.java) { test.extractor.resolve(TmdbPlayback("1765072")) }
        test.tamper = false
        test.hasVip = false
        assertEquals(listOf(XpassExtractor.MOVIE_SOURCES.getValue("TOK 1")), test.extractor.sources(TmdbPlayback("1765072")))
        assertThrows(IOException::class.java) { test.extractor.resolve(TmdbPlayback("1765072")) }
        assertTrue(test.selectedRequests.isEmpty())
    }

    @Test fun rejectsOffOriginConfigurationAndSelectedSourceUrls() = fixture { test ->
        test.dataPath = "https://example.com/data/movie/1765072"
        assertThrows(IOException::class.java) { test.extractor.resolve(TmdbPlayback("1765072")) }
        test.dataPath = "/data/movie/1765072"
        test.vipPath = "https://example.com/selected"
        assertThrows(IOException::class.java) { test.extractor.resolve(TmdbPlayback("1765072")) }
        assertTrue(test.selectedRequests.isEmpty())
    }

    @Test fun doesNotAdvertiseUnverifiedTvPlayback() = fixture { test ->
        assertTrue(test.extractor.sources(TmdbPlayback("108978", MediaType.TV, 2, 3)).isEmpty())
        assertEquals(0, test.server.requestCount)
    }

    @Test fun rejectsOffOriginConfigurationRedirects() = fixture { test ->
        test.redirect = true
        assertThrows(IOException::class.java) { test.extractor.resolve(TmdbPlayback("1765072")) }
        assertEquals(1, test.server.requestCount)
    }

    @Test fun preservesEveryListedMirrorAndResolvesOnlyTheExplicitSelection() = fixture { test ->
        test.extraNames = listOf("LUL 1", "ARA 1", "MOL 1", "MOL 2", "VXR 1", "VRK 1", "NEW 1")
        val target = TmdbPlayback("1765072")
        val sources = test.extractor.sources(target)
        assertEquals(XpassExtractor.MOVIE_SOURCES.values.toList(), sources.take(8))
        assertEquals("Nebula • NEW 1", sources.last().label)
        sources.forEach { test.extractor.resolve(target, it.id) }
        assertEquals(listOf("/other", "/selected") + (0..6).map { "/extra/$it" }, test.selectedRequests.map { it.substringBefore('?') })
        assertThrows(IOException::class.java) { test.extractor.resolve(target, "xpass:unknown") }
    }

    private fun fixture(action: (Fixture) -> Unit) = MockWebServer().use { server ->
        val test = Fixture(server)
        server.dispatcher = test
        server.start()
        action(test)
    }

    private class Fixture(val server: MockWebServer) : Dispatcher() {
        val extractor by lazy { XpassExtractor(HttpTransport(allowLoopback = true), server.url("/")) }
        var embedRequests = 0
        var scriptRequests = 0
        var tamper = false
        var hasVip = true
        var redirect = false
        var dataPath = "/data/movie/1765072"
        var vipPath = "/selected"
        var extraNames = emptyList<String>()
        val paths = mutableListOf<String>()
        val selectedRequests = mutableListOf<String>()
        private val context = "spv3-build-1000000000-0123456789abcdef"
        private val gson = Gson()
        private fun token() = "fixture$embedRequests.${"a".repeat(64)}"

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            paths += url.encodedPath
            return when (url.encodedPath) {
                "/e/movie/1765072" -> {
                    if (redirect) return MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/config")
                    embedRequests++
                    MockResponse().setBody("""<script>var dataUrl=${gson.toJson("$dataPath?token=${token()}")};</script><script src="/static/mainmini.js?v=fixture"></script>""")
                }
                "/static/mainmini.js" -> MockResponse().setBody("const configuration=['${encode("/static/player.js?v=fixture")}'];")
                "/static/player.js" -> {
                    scriptRequests++
                    MockResponse().setBody("const configuration=['${encode(context)}'];")
                }
                "/data/movie/1765072" -> {
                    val sources = buildList {
                        add(mapOf("id" to "rotating-other", "name" to "TOK 1", "url" to "/other"))
                        if (hasVip) add(mapOf("id" to "rotating-vip-$embedRequests", "name" to "VIP 1", "url" to "$vipPath?fresh=$embedRequests"))
                        extraNames.forEachIndexed { index, name -> add(mapOf("id" to "rotating-$index-$embedRequests", "name" to name, "url" to "/extra/$index?fresh=$embedRequests")) }
                    }
                    val key = MessageDigest.getInstance("SHA-256").digest("spv3-data-response|$context|${url.encodedPath}|${token()}".toByteArray())
                    val iv = ByteArray(12) { (it + embedRequests).toByte() }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    val encrypted = iv + cipher.doFinal(gson.toJson(sources).toByteArray())
                    if (tamper) encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
                    MockResponse().setBody(Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted))
                }
                "/selected", "/other" -> playlist(request)
                "/media/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:7")
                else -> if (url.encodedPath.startsWith("/extra/")) playlist(request) else MockResponse().setResponseCode(404)
            }
        }

        private fun playlist(request: RecordedRequest): MockResponse {
            selectedRequests += request.path!!
            return MockResponse().setBody("""{"playlist":[{"sources":[{"file":"${server.url("/media/master.m3u8")}","type":"hls"}]}]}""")
        }

        private fun encode(value: String) = Base64.getEncoder().withoutPadding().encodeToString(value.toByteArray())
            .map { if (it.isUpperCase()) it.lowercaseChar() else if (it.isLowerCase()) it.uppercaseChar() else it }.joinToString("")
    }
}
