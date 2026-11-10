package fr.bonamy.movies.core

import fr.bonamy.movies.core.sites.movies123.Movies123Site
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class Movies123SiteTest {
    @Test fun movieOffersListedServersWithoutProbingTitleAvailabilityAndResolvesServerThree() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.url.host in listOf("ployan.me", "vidara.to")) request.newBuilder()
                    .url(server.url(request.url.encodedPath)).build() else request)
            }.build()
            val site = Movies123Site(http, server.url("/"))
            val item = PlayableRef(TitleRef("123movies", "opaque-film-999", MediaType.MOVIE))
            val page = """<div id="mid" data-mid="42"></div><button id="srv-1"></button><button id="srv-2"></button><button id="srv-5"></button>"""
            server.enqueue(html(page))
            val options = site.sources(item)
            assertEquals(listOf("1", "2", "5"), options.sources.map { it.id })
            assertEquals("Server 3", options.sources.last().label)
            assertEquals("1", options.defaultSourceId)
            assertEquals(1, server.requestCount)
            server.takeRequest()
            server.enqueue(html(page))
            server.enqueue(html("""{"code":200,"mode":"embed","info":"${encryptedEmbed("https://vidara.to/e/opaque-code-2000000000")}"}"""))
            server.enqueue(html("""{"streaming_url":"${server.url("/movie/master.m3u8")}","subtitles":[]}"""))
            server.enqueue(html("#EXTM3U\n#EXT-X-VERSION:7"))
            val playback = site.resolve(item, "5")
            assertEquals(server.url("/movie/master.m3u8").toString(), playback.streamUrl)
            assertEquals("https://vidara.to/e/opaque-code", playback.requestHeaders["Referer"])
            server.takeRequest()
            val request = server.takeRequest()
            assertEquals(listOf("42", "1", "5"), decryptedRequest(request).split('+').take(3))
            val post = server.takeRequest()
            assertEquals("/api/stream", post.path)
            assertEquals("opaque-code", com.google.gson.JsonParser.parseString(post.body.readUtf8()).asJsonObject.get("filecode").asString)
            assertEquals("/movie/master.m3u8", server.takeRequest().path)
        }
    }

    @Test fun selectedServerTwoUsesAuthenticatedTmdbMappingAndNeverFallsBack() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.url.host in listOf("ployan.me", "vidsrc.sh")) request.newBuilder()
                    .url(server.url(request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: ""))).build() else request)
            }.build()
            val site = Movies123Site(http, server.url("/"))
            val item = PlayableRef(TitleRef("123movies", "opaque-film-999", MediaType.MOVIE))
            server.enqueue(html("""<div id="mid" data-mid="42"></div><button id="srv-2"></button>"""))
            server.enqueue(html("""{"code":200,"mode":"embed","info":"${encryptedEmbed("movie/1765072-2000000000")}"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            assertThrows(IOException::class.java) { runBlocking { site.resolve(item, null) } }
            server.takeRequest()
            assertEquals(listOf("42", "1", "2"), decryptedRequest(server.takeRequest()).split('+').take(3))
            val selected = server.takeRequest().requestUrl!!
            assertEquals("/vs_src.php", selected.encodedPath)
            assertEquals("1765072", selected.queryParameter("id"))
            assertEquals(3, server.requestCount)
        }
    }

    private fun encryptedEmbed(value: String): String {
        val salt = ByteArray(8) { it.toByte() }
        val iv = ByteArray(12) { (it + 8).toByte() }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("player".toCharArray(), salt, 1000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return listOf(salt, iv, cipher.doFinal(value.toByteArray())).joinToString("-") { bytes ->
            bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    private fun decryptedRequest(request: RecordedRequest): String {
        val parts = request.requestUrl!!.pathSegments.last().split('-').map { value -> value.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("player".toCharArray(), parts[0], 1000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parts[1]))
        return cipher.doFinal(parts[2]).toString(Charsets.UTF_8)
    }

    @Test fun catalogContinuationAndUnifiedSearchKeepOpaqueSiteIdentities() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val site = Movies123Site(OkHttpClient(), server.url("/"))
            server.enqueue(html("""<div class="list-movie">
                <a class="poster" href="/movie/practical-magic-4743/"><img data-src="/poster.jpg"><h2>Practical Magic</h2></a>
                </div><a aria-label="Next" href="/movies//2/">Next</a>"""))
            val request = CatalogRequest("movie")
            val first = site.browse(request)
            assertEquals("practical-magic-4743", first.items.single().id)
            assertEquals(server.url("/poster.jpg").toString(), first.items.single().poster)
            assertEquals("/movies/", server.takeRequest().path)
            server.enqueue(html("""<div class="list-movie"><a class="poster" href="/movie/another-222/">
                <h2>Another</h2></a></div>"""))
            assertNull(site.browse(request, first.next).next)
            assertEquals("/movies//2/", server.takeRequest().path)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { site.browse(CatalogRequest("tv"), first.next) } }
            server.enqueue(search("""{"t":"Reacher - Season 3","s":"reacher-season-3-1630858563","d":"s","n":3,"e":8,"y":2025},
                {"t":"Jack Reacher","s":"jack-reacher-123","d":"m","y":2012}""", 3))
            val query = CatalogRequest("movie", "reacher")
            val found = site.browse(query)
            assertEquals(listOf("reacher", "jack-reacher-123"), found.items.map { it.id })
            assertEquals(listOf("tv", "movie"), found.items.map { it.sectionId })
            assertEquals("Reacher", found.items.first().title)
            assertEquals("reacher", server.takeRequest().requestUrl!!.queryParameter("q"))
            server.enqueue(search("""{"t":"Reacher - Season 2","s":"reacher-season-2-1630858563","d":"s","n":2,"e":8}""", 3))
            assertNull(site.browse(query, found.next).next)
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun seriesNavigationUsesFullSeasonReferencesAndRejectsAmbiguousSeasons() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val site = Movies123Site(OkHttpClient(), server.url("/"))
            val show = TitleRef("123movies", "reacher", MediaType.TV)
            val seasons = """{"t":"Reacher - Season 3","s":"reacher-season-3-1630858563","d":"s","n":3,"e":1},
                {"t":"Preacher - Season 1","s":"preacher-season-1-9","d":"s","n":1,"e":8},
                {"t":"Reacher - Season 2","s":"reacher-season-2-1630858563","d":"s","n":2,"e":2}"""
            server.enqueue(search(seasons))
            val result = site.series.seasons(show)
            assertEquals(listOf(2, 3), result.map { it.number })
            assertEquals("reacher-season-2-1630858563", result.first().ref.id)
            server.enqueue(search(seasons))
            server.enqueue(detail(1630858564, 2))
            server.enqueue(detail(1630858565, 1))
            val last = PlayableRef(show, "reacher-season-2-1630858563/2", 2, 2)
            val next = site.series.nextEpisode(last)!!
            assertEquals(PlayableRef(show, "reacher-season-3-1630858563/1", 3, 1), next.target)
            server.enqueue(search(seasons))
            server.enqueue(detail(1630858565, 1))
            assertNull(site.series.nextEpisode(next.target))
            server.enqueue(search("""$seasons,{"t":"Reacher - Season 2","s":"reacher-season-2-999","d":"s","n":2,"e":1}"""))
            assertThrows(IOException::class.java) { runBlocking { site.series.seasons(show) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                site.series.episodes(SeasonRef(show, "preacher-season-1-9", 1))
            } }
        }
    }

    @Test fun selectedEpisodeUsesPageMidInFreshEncryptedRequestAndReturnsOnlyOwnedLanguageTracks() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.url.host == "ployan.me") request.newBuilder()
                    .url(server.url(request.url.encodedPath)).build() else request)
            }.build()
            val site = Movies123Site(http, server.url("/"))
            val item = PlayableRef(TitleRef("123movies", "reacher", MediaType.TV), "reacher-season-2-1630858563/3", 2, 3)
            server.enqueue(detail(1630858564, 3))
            assertEquals("1", site.sources(item).defaultSourceId)
            server.takeRequest()
            server.enqueue(detail(1630858564, 3))
            server.enqueue(html("""{"code":200,"mode":"direct","info":"abcd-1234-5678"}"""))
            server.enqueue(html("""[
                {"file":"/sub/222520232b262b2625273e20/en.vtt","lang":"en","label":"English"},
                {"file":"/sub/222520232b262b2625273e20/fr.vtt","lang":"fr","label":"French"},
                {"file":"/sub/222520232b262b2625273e20/es.vtt","lang":"es","label":"Spanish"},
                {"file":"https://evil.example/fr.vtt","lang":"fr","label":"Untrusted"},
                {"file":"/sub/other/en.vtt","lang":"en","label":"Wrong episode"}]
            """))
            val stream = site.resolve(item, "1")
            assertEquals("/season/reacher-season-2-1630858563/", server.takeRequest().path)
            val request = server.takeRequest()
            val token = request.path!!.removePrefix("/get/").split('-').map { hex ->
                hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            }
            assertEquals(8, token[0].size)
            assertEquals(12, token[1].size)
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("player".toCharArray(), token[0], 1000, 256)).encoded
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, token[1]))
            val payload = String(cipher.doFinal(token[2])).split('+')
            assertEquals(listOf("1630858564", "3", "1"), payload.take(3))
            assertTrue(kotlin.math.abs(System.currentTimeMillis() / 1000 - payload[3].toLong()) < 10)
            assertEquals("/sub/222520232b262b2625273e20/index.json", server.takeRequest().path)
            assertEquals("https://ployan.me/hls/abcd-1234-5678/master.m3u8", stream.streamUrl)
            assertEquals("https://ployan.me/", stream.requestHeaders["Referer"])
            assertEquals(listOf("en", "fr"), stream.subtitles.map { it.language })
            assertEquals("ployan:/sub/222520232b262b2625273e20/fr.vtt", stream.subtitles.last().id)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { site.resolve(item, "2") } }
            server.enqueue(detail(1630858564, 3))
            server.enqueue(html("""{"code":200,"mode":"embed","info":"unknown"}"""))
            assertThrows(IOException::class.java) { runBlocking { site.resolve(item, "1") } }
        }
    }

    private fun html(body: String) = MockResponse().setBody(body)
    private fun search(rows: String, total: Int? = null): MockResponse {
        val count = total ?: com.google.gson.JsonParser.parseString("[$rows]").asJsonArray.size()
        return html("""{"data":[$rows],"meta":{"total_items":$count}}""")
    }
    private fun detail(mid: Int, count: Int) = html("""<div id="mid" data-mid="$mid"></div><button id="srv-1">Server 1</button>
        <div id="eps-list">${(1..count).joinToString("") { "<button class='episode' id='ep-$it'>Episode $it</button>" }}</div>""")
}
