package fr.bonamy.movies.core.extractors

import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.IOException

/** Reads the player's static source configuration; never executes provider scripts. */
internal class ShareCloudyExtractor(private val transport: HttpTransport) : StreamExtractor<HttpUrl> {
    fun supports(url: HttpUrl) = url.isHttps && url.host == "sharecloudy.com" &&
        url.encodedPath.matches(Regex("/iframe/[a-zA-Z0-9]+"))

    override fun resolve(request: HttpUrl): ResolvedPlayback {
        require(supports(request))
        val document = Jsoup.parse(transport.text(request, request.toString()))
        val source = document.select("script").asSequence().map { it.data() }
            .filter { it.contains("jwplayer(") }.flatMap { FILE.findAll(it) }
            .mapNotNull { match -> JsonParser.parseString(match.groupValues[1]).asString.toHttpUrlOrNull() }
            .firstOrNull { it.isHttps && (it.encodedPath.endsWith(".m3u8") || it.encodedPath.endsWith(".mp4")) }
            ?: throw IOException("ShareCloudy stream unavailable")
        return ResolvedPlayback(source.toString(), mapOf("Referer" to request.toString(),
            "Origin" to "https://sharecloudy.com", "User-Agent" to HttpTransport.USER_AGENT),
            format = if (source.encodedPath.endsWith(".mp4")) MediaFormat.MP4 else MediaFormat.HLS)
    }
    companion object { private val FILE = Regex("""\bfile\s*:\s*("(?:\\.|[^"\\])*")""") }
}
