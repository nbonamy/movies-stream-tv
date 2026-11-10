package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class XpassExtractor(private val network: HttpTransport, private val origin: HttpUrl) : StreamExtractor<TmdbPlayback> {
    private val contexts = mutableMapOf<HttpUrl, String>()

    fun sources(target: TmdbPlayback): List<PlaybackSource> =
        if (target.type == MediaType.MOVIE) sourceEndpoints(target).keys.toList() else emptyList()

    override fun resolve(request: TmdbPlayback): ResolvedPlayback {
        return resolve(request, VIP_SOURCE.id)
    }

    fun resolve(target: TmdbPlayback, sourceId: String): ResolvedPlayback {
        if (!sourceId.startsWith("xpass:")) throw IOException("Unknown Nebula source")
        if (target.type != MediaType.MOVIE) throw IOException("Nebula does not support TV playback")
        // Rediscover the selected mirror; its signed endpoint and media URL are transient.
        val (source, selected) = sourceEndpoints(target).entries.singleOrNull { it.key.id == sourceId }
            ?: throw IOException("The selected Nebula source is unavailable for this title")
        val headers = headers(target)
        val response = JsonParser.parseString(configuration(selected, target))
        val playlist = if (response.isJsonObject) response.asJsonObject.getAsJsonArray("playlist") else response.asJsonArray
        val stream = playlist?.firstOrNull()?.asJsonObject?.getAsJsonArray("sources")
            ?.firstOrNull { it.asJsonObject.get("type")?.asString == "hls" }?.asJsonObject
            ?.get("file")?.asString ?: throw IOException("${source.label} returned no HLS stream")
        val media = selected.resolve(stream) ?: throw IOException("${source.label} returned an invalid stream URL")
        if (!network.accepts(media)) throw IOException("${source.label} returned an unsafe stream URL")
        if (!network.text(media, headers).trimStart().startsWith("#EXTM3U")) {
            throw IOException("${source.label} returned an invalid HLS playlist")
        }
        return ResolvedPlayback(media.toString(), headers)
    }

    private fun sourceEndpoints(target: TmdbPlayback): Map<PlaybackSource, HttpUrl> {
        val page = origin.resolve("e/${target.path}") ?: throw IOException("Invalid Nebula origin")
        val html = configuration(page, target)
        val rawDataUrl = DATA_URL.find(html)?.groupValues?.get(1)
            ?: throw IOException("Nebula did not return a source configuration")
        val dataUrl = sameOrigin(JsonParser.parseString(rawDataUrl).asString)
        if (!dataUrl.encodedPath.startsWith("/data/")) throw IOException("Nebula returned an invalid source endpoint")
        val token = dataUrl.queryParameter("token")?.takeIf(TOKEN::matches)
            ?: throw IOException("Nebula did not return a valid source token")
        val loader = Jsoup.parse(html).select("script[src]").map { it.attr("src") }
            .firstOrNull { it.substringBefore('?').endsWith("/static/mainmini.js") }
            ?.let(::sameOrigin) ?: throw IOException("Nebula did not return its player configuration")
        val context = synchronized(contexts) { contexts[loader] } ?: run {
            // Read only known string literals; never execute the loader or player JavaScript.
            val playerPath = configurationStrings(configuration(loader, target))
                .filter(PLAYER_PATH::matches).distinct().singleOrNull()
                ?: throw IOException("Nebula's player configuration changed")
            val script = configuration(sameOrigin(playerPath), target)
            configurationStrings(script).filter(CONTEXT::matches).distinct().singleOrNull()
                ?.also { synchronized(contexts) { contexts[loader] = it } }
                ?: throw IOException("Nebula's playback context changed")
        }
        val key = MessageDigest.getInstance("SHA-256").digest(
            "spv3-data-response|$context|${dataUrl.encodedPath}|$token".toByteArray(Charsets.UTF_8))
        val encrypted = configuration(dataUrl, target).trim()
        val decoded = try {
            val bytes = Base64.getUrlDecoder().decode(encrypted)
            if (bytes.size < 28) throw IOException("Nebula returned an incomplete source response")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            throw IOException("Could not authenticate Nebula's sources", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Nebula returned an invalid source response", error)
        }
        val entries = JsonParser.parseString(decoded).asJsonArray.mapNotNull {
            val item = it.asJsonObject
            val name = item.get("name")?.asString?.trim()?.takeIf { it.isNotBlank() && it.length <= 80 }
                ?: return@mapNotNull null
            // Mirror labels survive the provider's per-title/per-session opaque IDs.
            val source = MOVIE_SOURCES[name] ?: PlaybackSource(
                "xpass:name:${Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))}", "Nebula • $name")
            source to sameOrigin(item.string("url"))
        }
        if (entries.map { it.first }.distinct().size != entries.size) throw IOException("Nebula returned ambiguous sources")
        return entries.toMap()
    }

    private fun headers(target: TmdbPlayback) = mapOf(
        "User-Agent" to HttpTransport.USER_AGENT,
        "Referer" to origin.resolve("e/${target.path}").toString(),
        "Origin" to origin.toString().removeSuffix("/"),
    )

    private fun sameOrigin(raw: String): HttpUrl {
        val url = origin.resolve(raw) ?: throw IOException("Nebula returned an invalid configuration URL")
        if (!isSameOrigin(url)) {
            throw IOException("Nebula returned an unsafe configuration URL")
        }
        return url
    }

    private fun isSameOrigin(url: HttpUrl) = network.accepts(url) &&
        url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port

    private fun configuration(url: HttpUrl, target: TmdbPlayback) = network.bytes(url, headers(target),
        limit = 1024 * 1024, redirectAllowed = ::isSameOrigin).toString(Charsets.UTF_8)

    private fun configurationStrings(script: String): Sequence<String> = LITERAL.findAll(script).flatMap { match ->
        val literal = match.groupValues[1]
        val decoded = runCatching {
            val standard = literal.map { if (it.isUpperCase()) it.lowercaseChar() else if (it.isLowerCase()) it.uppercaseChar() else it }.joinToString("")
            Base64.getDecoder().decode(standard).toString(Charsets.UTF_8)
        }.getOrNull()
        sequenceOf(literal, decoded).filterNotNull()
    }

    companion object {
        val VIP_SOURCE = PlaybackSource("xpass:vip", "Nebula • VIP 1")
        val MOVIE_SOURCES = linkedMapOf(
            "TOK 1" to PlaybackSource("xpass:tok1", "Nebula • TOK 1"),
            "VIP 1" to VIP_SOURCE,
            "LUL 1" to PlaybackSource("xpass:lul1", "Nebula • LUL 1"),
            "ARA 1" to PlaybackSource("xpass:ara1", "Nebula • ARA 1"),
            "MOL 1" to PlaybackSource("xpass:mol1", "Nebula • MOL 1"),
            "MOL 2" to PlaybackSource("xpass:mol2", "Nebula • MOL 2"),
            "VXR 1" to PlaybackSource("xpass:vxr1", "Nebula • VXR 1"),
            "VRK 1" to PlaybackSource("xpass:vrk1", "Nebula • VRK 1"),
        )
        private val DATA_URL = Regex("""\b(?:var|let|const)\s+dataUrl\s*=\s*("(?:\\.|[^"\\])*"|'[^']*')""")
        private val TOKEN = Regex("[A-Za-z0-9_-]+\\.[a-f0-9]{64}")
        private val LITERAL = Regex("""['"]([^'"\r\n]{4,8192})['"]""")
        private val PLAYER_PATH = Regex("/static/[A-Za-z0-9_-]+\\.js(?:\\?[^\\s]+)?")
        private val CONTEXT = Regex("spv3-build-[0-9]+-[a-f0-9]{16}")
    }
}
