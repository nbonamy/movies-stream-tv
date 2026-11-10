package fr.bonamy.movies.core

import com.google.gson.JsonParser
import fr.bonamy.movies.core.extractors.VidaraExtractor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class VidaraExtractorTest {
    @Test fun postsSelectedOpaqueFileAndSuppliesMediaHeadersAndOwnedFrenchEnglishTracks() {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.url.host == "vidara.to") request.newBuilder()
                    .url(server.url(request.url.encodedPath)).build() else request)
            }.build()
            server.enqueue(MockResponse().setBody("""{"streaming_url":"${server.url("/media/master.m3u8")}","subtitles":[
                {"id":1,"type":0,"language":"English","file_path":"${server.url("/media/en.vtt")}"},
                {"id":2,"type":0,"language":"French","file_path":"${server.url("/media/fr.vtt")}"},
                {"id":3,"type":0,"language":"Spanish","file_path":"${server.url("/media/es.vtt")}"},
                {"id":4,"type":0,"language":"English","file_path":"https://evil.example/en.vtt"}]}"""))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:7"))
            val result = VidaraExtractor(HttpTransport(http, true)).resolve("https://vidara.to/e/opaque-code".toHttpUrl())
            assertEquals(server.url("/media/master.m3u8").toString(), result.streamUrl)
            assertEquals(listOf("en", "fr"), result.subtitles.map { it.language })
            assertEquals("https://vidara.to/e/opaque-code", result.requestHeaders["Referer"])
            assertEquals("https://vidara.to", result.requestHeaders["Origin"])
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/stream", request.path)
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("opaque-code", body.get("filecode").asString)
            assertEquals("android", body.get("device").asString)
            assertTrue(body.getAsJsonArray("codecs").isEmpty)
            assertEquals("/media/master.m3u8", server.takeRequest().path)
        }
    }

    @Test fun rejectsUnrecognizedPlayerDestinationsWithoutRequests() {
        val extractor = VidaraExtractor(HttpTransport())
        for (url in listOf("https://evil.example/e/file", "https://vidara.to/other/file", "http://vidara.to/e/file")) {
            assertThrows(IOException::class.java) { extractor.resolve(url.toHttpUrl()) }
        }
    }
}
