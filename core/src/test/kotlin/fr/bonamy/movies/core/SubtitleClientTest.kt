package fr.bonamy.movies.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class SubtitleClientTest {
    @Test
    fun searchesOnlyFrenchAndEnglishAndDoesNotDownloadUntilSelected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":{"imdb_id":"tt123456"}}"""))
            server.enqueue(MockResponse().setBody("""[
                ${entry(server, "1", "fre", "40")},
                ${entry(server, "2", "spa", "100")},
                ${entry(server, "3", "fre", "500")}
            ]"""))
            server.enqueue(MockResponse().setBody("[${entry(server, "4", "eng", "100")}]"))
            val client = SubtitleClient(searchOrigin = server.url("/"), metadataOrigin = server.url("/"))
            val result = client.search(PlaybackTarget("789"), null)
            assertEquals(listOf("3", "1", "4"), result.subtitles.map { it.id })
            assertTrue(result.failedLanguages.isEmpty())
            assertEquals(3, server.requestCount)
            assertEquals("/api.php?type=movie&tmdb=789", server.takeRequest().path)
            listOf("fre", "eng").forEach { language ->
                val request = server.takeRequest()
                assertEquals("/search/imdbid-123456/sublanguageid-$language", request.path)
                assertEquals("trailers.to-UA", request.getHeader("X-User-Agent"))
            }
            val text = "1\n00:00:01,000 --> 00:00:03,000\nUne étoile apparaît.\n"
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
            }.toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(compressed)))
            val document = client.download(result.subtitles.first().copy(encoding = "ISO-8859-1"))
            assertEquals(text, document.text)
            assertFalse(document.isWebVtt)
            assertEquals("/download/3", server.takeRequest().path)
        }
    }

    @Test
    fun preservesEnglishResultsWhenFrenchServiceFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("[${entry(server, "4", "eng", "100")}]"))
            val result = SubtitleClient(searchOrigin = server.url("/")).search(PlaybackTarget("789"), SubtitleContext("tt123456"))
            assertEquals(listOf(SubtitleLanguage.FRENCH), result.failedLanguages)
            assertEquals(listOf("4"), result.subtitles.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun scopesFrenchAndEnglishSearchToTheSelectedEpisode() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("[]")) }
            SubtitleClient(searchOrigin = server.url("/")).search(
                PlaybackTarget("55", MediaType.TV, 2, 3), SubtitleContext("tt123456"))
            listOf("fre", "eng").forEach { language ->
                assertEquals("/search/episode-3/imdbid-123456/season-2/sublanguageid-$language", server.takeRequest().path)
            }
        }
    }

    @Test
    fun rejectsNonSubtitleDownloadsGzipBombsAndUntrustedRedirects() {
        MockWebServer().use { server ->
            val client = SubtitleClient(searchOrigin = server.url("/"))
            val subtitle = OnlineSubtitle("1", SubtitleLanguage.FRENCH, "Movie.srt",
                server.url("/download/1").toString(), "UTF-8", 0)
            server.enqueue(MockResponse().setBody("<html>Unavailable</html>"))
            assertThrows(IOException::class.java) { client.download(subtitle) }
            val compressed = ByteArrayOutputStream().also { out ->
                GZIPOutputStream(out).use { it.write(ByteArray(4 * 1024 * 1024 + 1)) }
            }.toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(compressed)))
            assertThrows(IOException::class.java) { client.download(subtitle) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://192.168.1.1/private"))
            assertThrows(IOException::class.java) { client.download(subtitle) }
            assertEquals(3, server.requestCount)
        }
    }

    private fun entry(server: MockWebServer, id: String, language: String, downloads: String) = """
        {"IDSubtitleFile":"$id","SubLanguageID":"$language","SubFileName":"Movie.$language.srt",
         "SubDownloadLink":"${server.url("/download/$id")}","SubFormat":"srt","SubEncoding":"UTF-8",
         "SubDownloadsCnt":"$downloads"}
    """.trimIndent()
}
