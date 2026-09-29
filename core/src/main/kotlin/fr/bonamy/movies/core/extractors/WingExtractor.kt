package fr.bonamy.movies.core.extractors

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.MessageDigest

internal data class WingRequest(val id: String, val type: MediaType, val title: String, val year: String,
    val imdb: String?, val season: Int?, val episode: Int?, val source: String)

/** Cinejoy's selected Wing server. Configuration is parsed as data; no provider runtime. */
internal class WingExtractor(private val network: HttpTransport) : StreamExtractor<WingRequest> {
    private val origin = "https://api.wing.st/".toHttpUrl()
    private val subtitleOrigin = "https://subs.wing.st/".toHttpUrl()
    private val headers = mapOf("Origin" to "https://cinejoy.pk", "Referer" to "https://cinejoy.pk/",
        "User-Agent" to HttpTransport.USER_AGENT)
    @Volatile private var protection: Pair<Long, WingCrypto>? = null

    fun sources(): PlaybackOptions {
        val data = JsonParser.parseString(network.text(origin.resolve("servers")!!, headers)).asJsonObject
        val offered = data.getAsJsonArray("servers") ?: throw IOException("Cinejoy sources unavailable")
        val sources = offered.mapNotNull { element ->
            val name = element.asJsonObject.get("name")?.asString
            SERVERS.firstOrNull { it.label == name }
        }.distinctBy { it.id }
        if (sources.isEmpty()) throw IOException("No supported Cinejoy source is available")
        return PlaybackOptions(sources, sources.firstOrNull { it.id == "nebula" }?.id ?: sources.first().id)
    }

    override fun resolve(request: WingRequest): ResolvedPlayback {
        val server = SERVERS.firstOrNull { it.id == request.source } ?: throw IOException("Unknown Cinejoy source")
        val payload = JsonObject().apply {
            addProperty("tmdb", request.id)
            request.imdb?.let { addProperty("imdb", it) }
            if (request.year.isNotBlank()) addProperty("year", request.year)
            addProperty("title", request.title)
            if (request.type == MediaType.TV) {
                addProperty("season", requireNotNull(request.season).toString())
                addProperty("episode", requireNotNull(request.episode).toString())
            }
        }
        val input = JsonObject().apply {
            addProperty("path", "/${server.label}/${if (request.type == MediaType.MOVIE) "movie" else "series"}")
            add("payload", payload)
        }.toString().toByteArray()
        val envelope = exchange(input)
        if (envelope.get("status")?.asInt != 200) throw IOException("${server.label} has no stream for this title")
        val streams = envelope.getAsJsonObject("data")?.getAsJsonArray("stream")
            ?: throw IOException("${server.label} returned no streams")
        val stream = streams.map { it.asJsonObject }.firstOrNull {
            it.get("type")?.asString in listOf("hls", "mp4") && it.get("playlist")?.asString?.startsWith("https://") == true
        } ?: throw IOException("${server.label} returned an unsupported stream")
        val url = stream.string("playlist").toHttpUrl()
        if (!network.accepts(url)) throw IOException("Unsupported media destination")
        val format = if (stream.string("type") == "mp4") MediaFormat.MP4 else MediaFormat.HLS
        // Validate the returned destination before handing it to the native player.
        if (format == MediaFormat.HLS && !network.text(url, headers).trimStart().startsWith("#EXTM3U"))
            throw IOException("${server.label} returned an invalid playlist")
        return ResolvedPlayback(url.toString(), headers,
            request.imdb?.let { SubtitleContext(it, request.season, request.episode) }, format, subtitles(request))
    }

    private fun exchange(input: ByteArray): JsonObject {
        repeat(2) { attempt ->
            val crypto = crypto()
            val sealed = crypto.seal(input)
            try {
                val response = network.postBytes(origin.resolve("g")!!, crypto.requestBody(sealed), headers)
                return JsonParser.parseString(crypto.decryptResponse(response, sealed).toString(Charsets.UTF_8)).asJsonObject
            } catch (error: HttpStatusException) {
                if (error.status != 404 || attempt != 0) throw error
                protection = null // Website retries once after reloading its module.
            } finally { sealed.fill(0) }
        }
        throw IOException("Cinejoy request failed")
    }

    @Synchronized private fun crypto(): WingCrypto {
        protection?.takeIf { System.currentTimeMillis() - it.first < 60 * 60 * 1000 }?.let { return it.second }
        val bytes = network.bytes(origin.resolve("crush.wasm")!!, headers, 256 * 1024,
            redirectAllowed = { it.host == origin.host })
        if (sha256(bytes) != MODULE_HASH) throw IOException("Cinejoy playback protocol changed; an app update is needed")
        val value = WingCrypto(modulePublicKey(bytes), 2)
        protection = System.currentTimeMillis() to value
        return value
    }

    private fun subtitles(item: WingRequest): List<PlaybackSubtitle> = try {
        val url = subtitleOrigin.resolve("subtitles")!!.newBuilder()
            .addQueryParameter("type", item.type.apiValue).addQueryParameter("tmdb", item.id)
            .apply { if (item.type == MediaType.TV) {
                addQueryParameter("season", item.season.toString()); addQueryParameter("episode", item.episode.toString())
            } }.build()
        val rows = JsonParser.parseString(network.text(url, headers)).asJsonObject.getAsJsonArray("subtitles")
        rows?.mapNotNull { element ->
            val row = element.asJsonObject
            val language = subtitleLanguageCode(row.get("language")?.asString)
            if (language !in listOf("fr", "en")) return@mapNotNull null
            val format = when (row.get("type")?.asString?.lowercase()) {
                "srt" -> SubtitleFormat.SRT; "vtt" -> SubtitleFormat.WEBVTT; else -> return@mapNotNull null
            }
            val trackUrl = runCatching { row.string("url").toHttpUrl() }.getOrNull() ?: return@mapNotNull null
            if (!trackUrl.isHttps || trackUrl.host != subtitleOrigin.host) return@mapNotNull null
            val display = row.get("display")?.asString.orEmpty()
            val identity = listOf(item.type.apiValue, item.id, item.season, item.episode, language,
                row.get("source")?.asString, display).joinToString("\u0000")
            PlaybackSubtitle("wing:${sha256(identity.toByteArray())}", trackUrl.toString(), language!!,
                (if (language == "fr") "French" else "English") + display.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), format)
        }?.distinctBy { it.id }?.sortedBy { if (it.language == "fr") 0 else 1 }.orEmpty()
    } catch (error: Exception) {
        if (Thread.currentThread().isInterrupted) throw error
        emptyList() // Optional subtitle availability must not stop video.
    }

    /** Only the reviewed module version reaches this parser; bytes are never executed. */
    private fun modulePublicKey(bytes: ByteArray): ByteArray {
        var pos = 8
        fun uint(): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                val b = bytes[pos++].toInt() and 255
                value = value or ((b and 127) shl shift)
                if (b and 128 == 0) return value
            }
            throw IOException("Invalid module integer")
        }
        while (pos < bytes.size) {
            val section = bytes[pos++].toInt() and 255
            val end = uint() + pos
            if (section == 11) repeat(uint()) {
                require(uint() == 0 && bytes[pos++].toInt() == 0x41)
                val offset = uint()
                require(bytes[pos++].toInt() == 0x0b)
                val length = uint()
                val keyOffset = 1052336 - offset
                if (keyOffset >= 0 && keyOffset + 65 <= length) return bytes.copyOfRange(pos + keyOffset, pos + keyOffset + 65)
                pos += length
            }
            pos = end
        }
        throw IOException("Cinejoy public key unavailable")
    }

    companion object {
        private const val MODULE_HASH = "40c923580779e2a850fc5ab3f0046be565ed717883cf4547ed3a87de2450bdcf"
        val SERVERS = listOf("Nebula", "Lisbon", "Solara", "Athens").map { PlaybackSource(it.lowercase(), it) }
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
