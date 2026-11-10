package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import java.io.IOException

internal class VidaraExtractor(private val network: HttpTransport) : StreamExtractor<HttpUrl> {
    override fun resolve(request: HttpUrl): ResolvedPlayback {
        if (!request.isHttps || request.host != "vidara.to" || !request.encodedPath.matches(PLAYER_PATH)) {
            throw IOException("Unsupported Vidara player URL")
        }
        val origin = request.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val headers = mapOf("Referer" to request.toString(), "Origin" to origin.toString().removeSuffix("/"),
            "User-Agent" to HttpTransport.USER_AGENT)
        // An empty optional-codec list requests the site's baseline AVC rendition.
        val body = JsonObject().apply {
            addProperty("filecode", request.pathSegments.last())
            addProperty("device", "android")
            add("codecs", com.google.gson.JsonArray())
        }.toString().toByteArray(Charsets.UTF_8)
        val response = JsonParser.parseString(network.postBytes(origin.resolve("api/stream")!!, body, headers,
            contentType = "application/json; charset=utf-8").toString(Charsets.UTF_8)).asJsonObject
        val media = origin.resolve(response.string("streaming_url"))
            ?: throw IOException("Vidara returned an invalid media URL")
        if (!network.accepts(media)) throw IOException("Vidara returned an unsafe media URL")
        if (!network.text(media, headers).trimStart().startsWith("#EXTM3U")) throw IOException("Vidara returned an invalid HLS playlist")
        val tracks = response.getAsJsonArray("subtitles")?.mapNotNull { value ->
            val track = value.asJsonObject
            if (track.get("type")?.asInt != 0) return@mapNotNull null
            val language = when (val name = track.get("language")?.asString?.lowercase(java.util.Locale.ROOT)) {
                "english" -> "en"
                "french", "français" -> "fr"
                else -> subtitleLanguageCode(name)
            }?.takeIf { it == "en" || it == "fr" } ?: return@mapNotNull null
            val url = origin.resolve(track.string("file_path")) ?: return@mapNotNull null
            if (!network.accepts(url) || !(url.host == media.host ||
                    media.topPrivateDomain()?.let { it == url.topPrivateDomain() } == true)) return@mapNotNull null
            val format = when {
                url.encodedPath.endsWith(".vtt") -> SubtitleFormat.WEBVTT
                url.encodedPath.endsWith(".srt") -> SubtitleFormat.SRT
                else -> return@mapNotNull null
            }
            val id = track.get("id")?.asString ?: return@mapNotNull null
            PlaybackSubtitle("vidara:$id", url.toString(), language, if (language == "fr") "French" else "English", format)
        }.orEmpty().distinctBy { it.id }
        return ResolvedPlayback(media.toString(), headers, subtitles = tracks)
    }

    companion object { private val PLAYER_PATH = Regex("/e/[A-Za-z0-9_-]{1,128}") }
}
