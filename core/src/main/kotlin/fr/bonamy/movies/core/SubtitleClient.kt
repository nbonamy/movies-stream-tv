package fr.bonamy.movies.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

enum class SubtitleLanguage(val code: String, val searchCode: String, val label: String) {
    FRENCH("fr", "fre", "French"), ENGLISH("en", "eng", "English")
}

data class SubtitleContext(val imdbId: String, val season: Int? = null, val episode: Int? = null) {
    init { require((season == null && episode == null) || (season != null && season >= 0 && episode != null && episode > 0)) }
}
data class OnlineSubtitle(
    val id: String,
    val language: SubtitleLanguage,
    val release: String,
    val downloadUrl: String,
    val encoding: String,
    val downloads: Long,
)
data class SubtitleSearch(val subtitles: List<OnlineSubtitle>, val failedLanguages: List<SubtitleLanguage>)
data class SubtitleDocument(val text: String, val isWebVtt: Boolean)

/** Provider-independent IMDb lookup, restricted to French and English. */
class SubtitleClient(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val searchOrigin: HttpUrl = "https://rest.opensubtitles.org/".toHttpUrl(),
) {
    private val network = HttpTransport(http, searchOrigin.host in listOf("localhost", "127.0.0.1"))

    fun search(context: SubtitleContext): SubtitleSearch {
        val imdb = context.imdbId.removePrefix("tt")
        if (!imdb.matches(Regex("[0-9]+"))) throw IOException("No IMDb reference for this movie")
        val failed = mutableListOf<SubtitleLanguage>()
        val subtitles = SubtitleLanguage.entries.flatMap { language ->
            try {
                val episodePath = if (context.episode != null) "episode-${context.episode}/" else ""
                val seasonPath = if (context.episode != null) "season-${context.season}/" else ""
                val url = searchOrigin.resolve("search/${episodePath}imdbid-$imdb/${seasonPath}sublanguageid-${language.searchCode}")!!
                val body = request(url, mapOf("X-User-Agent" to "trailers.to-UA"))
                JsonParser.parseString(body.toString(Charsets.UTF_8)).asJsonArray.mapNotNull { element ->
                    val row = element.asJsonObject
                    if (row.text("SubLanguageID") != language.searchCode ||
                        row.text("SubFormat").lowercase() !in listOf("srt", "vtt")) return@mapNotNull null
                    val urlString = row.text("SubDownloadLink")
                    val download = runCatching { urlString.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
                    if (!allowedDownload(download)) return@mapNotNull null
                    val id = row.text("IDSubtitleFile")
                    if (!id.matches(Regex("[0-9]+"))) return@mapNotNull null
                    OnlineSubtitle(id, language, row.text("SubFileName").ifBlank { row.text("MovieReleaseName") },
                        urlString, row.text("SubEncoding"), row.text("SubDownloadsCnt").toLongOrNull() ?: 0)
                }.distinctBy { it.id }.sortedByDescending { it.downloads }
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw error
                failed.add(language)
                emptyList()
            }
        }
        return SubtitleSearch(subtitles, failed)
    }

    fun download(subtitle: OnlineSubtitle): SubtitleDocument {
        val url = subtitle.downloadUrl.toHttpUrl()
        if (!allowedDownload(url)) throw IOException("Unsupported subtitle download host")
        val bytes = request(url)
        val compressed = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val decoded = if (compressed) GZIPInputStream(bytes.inputStream()).use { readBounded(it) } else bytes
        val charset = runCatching { Charset.forName(subtitle.encoding.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
        val text = decoded.toString(charset).removePrefix("\uFEFF").replace("\r\n", "\n")
        val vtt = text.trimStart().startsWith("WEBVTT")
        if (!CUE.containsMatchIn(text) || text.trimStart().startsWith("<")) {
            throw IOException("The download did not contain subtitle cues")
        }
        return SubtitleDocument(text, vtt)
    }

    private fun allowedDownload(url: HttpUrl): Boolean =
        (url.isHttps && (url.host == "opensubtitles.org" || url.host.endsWith(".opensubtitles.org"))) ||
            (searchOrigin.host in listOf("localhost", "127.0.0.1") && url.host == searchOrigin.host && url.port == searchOrigin.port)

    private fun request(url: HttpUrl, headers: Map<String, String> = emptyMap()): ByteArray =
        network.bytes(url, headers, 4 * 1024 * 1024) { next ->
            (next.host == url.host && next.scheme == url.scheme) || allowedDownload(next)
        }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > 4 * 1024 * 1024) throw IOException("Subtitle response is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        private val CUE = Regex("(?m)^\\s*(?:\\d{2}:)?\\d{2}:\\d{2}[,.]\\d{3}\\s+-->\\s+(?:\\d{2}:)?\\d{2}:\\d{2}[,.]\\d{3}")
    }
}
