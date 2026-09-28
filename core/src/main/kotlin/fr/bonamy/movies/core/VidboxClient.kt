package fr.bonamy.movies.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidboxClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val catalogOrigin: HttpUrl = "https://vidbox.vc/".toHttpUrl(),
    private val playerOrigin: HttpUrl = "https://vixsrc.to/".toHttpUrl(),
    private val alternativeOrigin: HttpUrl = "https://vidrock.net/".toHttpUrl(),
    private val maxOrigin: HttpUrl = "https://ythd.org/".toHttpUrl(),
) {
    private val maxTokens = mutableMapOf<String, Pair<String, Long>>()

    fun browse(type: MediaType, page: Int = 1, query: String? = null): CatalogPage =
        discover(type, page, query?.trim()?.takeIf(String::isNotEmpty))

    private val series by lazy { SeriesCatalog(http, catalogOrigin) }
    fun seasons(id: String): List<Season> = series.seasons(id)
    fun episodes(id: String, season: Int): List<Episode> = series.episodes(id, season)

    fun sources(target: PlaybackTarget): List<MovieSource> {
        val alternatives = runCatching { alternativeEntries(target) }.getOrDefault(emptyMap())
        return buildList {
            addAll(BUILT_IN_SOURCES)
            alternatives.forEach { (name, item) ->
                if (item.get("url")?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true &&
                    item.get("type")?.takeUnless { it.isJsonNull }?.asString == "hls") {
                    add(MovieSource("vidrock:$name", "VidRock • $name"))
                }
            }
        }
    }

    fun resolve(target: PlaybackTarget, sourceId: String = DEFAULT_SOURCE.id): ResolvedMovie {
        return when (sourceId) {
            DEFAULT_SOURCE.id -> resolveMax(target)
            VIDPRO_SOURCE.id -> resolveVidpro(target)
            else -> resolveAlternative(target, sourceId)
        }
    }

    private fun resolveMax(target: PlaybackTarget): ResolvedMovie {
        val embedUrl = maxOrigin.resolve("embed/${target.id}" + if (target.type == MediaType.TV) "/${target.season}-${target.episode}" else "") ?: error("Invalid Max origin")
        val descriptorUrl = maxOrigin.resolve("vs_src.php")!!.newBuilder()
            .addQueryParameter("type", target.type.apiValue)
            .addQueryParameter("id", target.id)
            .apply {
                if (target.type == MediaType.TV) {
                    addQueryParameter("season", target.season.toString())
                    addQueryParameter("episode", target.episode.toString())
                }
            }
            .build()
        val landingUrl = JsonParser.parseString(get(descriptorUrl, referer = embedUrl.toString()))
            .asJsonObject.string("src").toHttpUrl()
        check(isSafe(landingUrl)) { "Max returned an unsafe player URL" }

        val landing = get(landingUrl, referer = maxOrigin.toString())
        val playerPath = JsonParser.parseString(MAX_LANDING_CONFIG.find(landing)?.groupValues?.get(1)
            ?: throw IOException("Max did not return a player configuration"))
            .asJsonObject.string("playerUrl")
        val playerUrl = landingUrl.resolve(playerPath) ?: throw IOException("Max returned an invalid player URL")
        check(isSafe(playerUrl)) { "Max returned an unsafe player URL" }

        val playerPage = get(playerUrl, referer = landingUrl.toString())
        val playerConfig = JsonParser.parseString(MAX_PLAYER_CONFIG.find(playerPage)?.groupValues?.get(1)
            ?: throw IOException("Max did not return a stream configuration"))
            .asJsonObject
        val apiUrl = if (target.type == MediaType.TV) {
            playerConfig.string("streamBase").toHttpUrl().newBuilder()
                .setQueryParameter("season", target.season.toString())
                .setQueryParameter("episode", target.episode.toString())
                .setQueryParameter("stream_urls", null).build()
        } else playerConfig.string("api").toHttpUrl()
        check(isSafe(apiUrl)) { "Max returned an unsafe stream API" }
        val streamResponse = JsonParser.parseString(get(apiUrl, referer = playerUrl.toString())).asJsonObject
        val encrypted = streamResponse.getAsJsonObject("data")?.string("stream_urls")
            ?: throw IOException("Max did not return streams")
        val wasmUrl = streamResponse.getAsJsonObject("vs")?.string("wasm_url")?.toHttpUrl()
            ?: throw IOException("Max did not return its stream decoder")
        check(isSafe(wasmUrl)) { "Max returned an unsafe stream decoder" }
        val key = maxKey(getBytes(wasmUrl, mapOf("Referer" to playerUrl.toString())))
        val streams = decryptMaxStreams(encrypted, key)
        if (streams.isEmpty()) throw IOException("Max returned no streams")

        val mediaHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to playerUrl.toString(),
            "Origin" to playerUrl.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        streams.forEach { raw ->
            val candidate = runCatching { raw.toHttpUrl() }.getOrNull() ?: return@forEach
            if (!isSafe(candidate)) return@forEach
            val token = runCatching { maxToken(candidate) }.getOrNull() ?: return@forEach
            val playlist = candidate.newBuilder().addQueryParameter("token", token).build()
            val body = runCatching { get(playlist, mediaHeaders) }.getOrNull() ?: return@forEach
            if (body.trimStart().startsWith("#EXTM3U")) return ResolvedMovie(playlist.toString(), mediaHeaders,
                streamResponse.getAsJsonObject("data")?.get("imdb_id")?.takeUnless { it.isJsonNull }
                    ?.asString?.takeIf { it.isNotBlank() }?.let(::SubtitleContext))
        }
        throw IOException("Max streams were unavailable")
    }

    private fun maxToken(stream: HttpUrl): String {
        val origin = stream.newBuilder().encodedPath("/").query(null).build()
        val cacheKey = origin.host
        synchronized(maxTokens) {
            maxTokens[cacheKey]?.takeIf { System.currentTimeMillis() - it.second < MAX_TOKEN_CACHE_MS }?.let { return it.first }
        }
        val tokenUrl = origin.resolve("generate.php") ?: throw IOException("Max returned an invalid token service")
        var lastError: IOException? = null
        repeat(4) { attempt ->
            val request = Request.Builder().url(tokenUrl).header("User-Agent", USER_AGENT).build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val token = response.body?.string()?.trim().orEmpty()
                    if (token.isEmpty()) throw IOException("Max returned an empty stream token")
                    synchronized(maxTokens) { maxTokens[cacheKey] = token to System.currentTimeMillis() }
                    return token
                }
                lastError = IOException("${tokenUrl.host} returned HTTP ${response.code}")
                if (response.code != 429) throw lastError!!
            }
            if (attempt < 3) Thread.sleep((attempt + 1) * 1_500L)
        }
        throw lastError ?: IOException("Max stream token was unavailable")
    }

    private fun maxKey(wasm: ByteArray): ByteArray {
        if (wasm.size < 8 || !wasm.copyOfRange(0, 8).contentEquals(WASM_HEADER)) {
            throw IOException("Max returned an invalid stream decoder")
        }
        val memory = ByteArray(MAX_WASM_MEMORY)
        var keyOffset: Int? = null
        var cursor = 8
        while (cursor < wasm.size) {
            val sectionId = wasm[cursor++].toInt() and 0xff
            val sectionSize = readUnsignedLeb(wasm, cursor).also { cursor = it.second }.first
            val sectionEnd = cursor + sectionSize
            if (sectionSize < 0 || sectionEnd < cursor || sectionEnd > wasm.size) throw IOException("Max returned a truncated stream decoder")
            if (sectionId == 10) keyOffset = maxKeyOffset(wasm.copyOfRange(cursor, sectionEnd))
            if (sectionId == 11) readDataSection(wasm, cursor, sectionEnd, memory)
            cursor = sectionEnd
        }
        val offset = keyOffset ?: throw IOException("Max returned an unsupported stream decoder")
        return ByteArray(32) { index -> (memory[index].toInt() xor memory[offset + index].toInt()).toByte() }
    }

    // Recognize the eight ChaCha key-word loads as data; never execute provider code.
    // The second share's address changes with the provider's five-minute window.
    private fun maxKeyOffset(code: ByteArray): Int {
        val matches = mutableSetOf<Int>()
        for (start in code.indices) {
            if (code[start].toInt() and 0xff != 0x41) continue
            runCatching {
                var cursor = start
                fun expect(opcode: Int) {
                    check(cursor < code.size && code[cursor++].toInt() and 0xff == opcode)
                }
                fun constant(): Int {
                    expect(0x41)
                    return readSignedLeb(code, cursor).also { cursor = it.second }.first
                }
                fun load() {
                    expect(0x28)
                    check(readUnsignedLeb(code, cursor).also { cursor = it.second }.first <= 2)
                    check(readUnsignedLeb(code, cursor).also { cursor = it.second }.first == 0)
                }
                var shareOffset = -1
                repeat(8) { word ->
                    check(constant() == word * 4)
                    load()
                    val address = constant()
                    if (word == 0) shareOffset = address
                    check(shareOffset >= 32 && shareOffset <= MAX_WASM_MEMORY - 32)
                    check(address == shareOffset + word * 4)
                    load()
                    expect(0x73) // i32.xor
                    expect(0x21) // local.set
                    check(readUnsignedLeb(code, cursor).also { cursor = it.second }.first == 6 + word)
                }
                matches += shareOffset
            }
        }
        return matches.singleOrNull() ?: throw IOException("Max returned an unsupported stream decoder")
    }

    private fun readDataSection(wasm: ByteArray, start: Int, end: Int, memory: ByteArray) {
        var cursor = start
        val count = readUnsignedLeb(wasm, cursor).also { cursor = it.second }.first
        repeat(count) {
            val mode = readUnsignedLeb(wasm, cursor).also { cursor = it.second }.first
            val offset = when (mode) {
                0 -> readOffsetExpression(wasm, cursor).also { cursor = it.second }.first
                2 -> {
                    readUnsignedLeb(wasm, cursor).also { cursor = it.second }
                    readOffsetExpression(wasm, cursor).also { cursor = it.second }.first
                }
                1 -> -1
                else -> throw IOException("Max returned an unsupported stream decoder")
            }
            val length = readUnsignedLeb(wasm, cursor).also { cursor = it.second }.first
            if (length < 0 || cursor + length > end) throw IOException("Max returned a truncated stream decoder")
            if (offset >= 0) {
                if (offset + length > memory.size) throw IOException("Max stream decoder used too much memory")
                wasm.copyInto(memory, offset, cursor, cursor + length)
            }
            cursor += length
        }
    }

    private fun readOffsetExpression(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var cursor = start
        if (cursor >= bytes.size || bytes[cursor++].toInt() and 0xff != 0x41) {
            throw IOException("Max returned an unsupported stream decoder")
        }
        val value = readSignedLeb(bytes, cursor).also { cursor = it.second }.first
        if (cursor >= bytes.size || bytes[cursor++].toInt() and 0xff != 0x0b) {
            throw IOException("Max returned an unsupported stream decoder")
        }
        return value to cursor
    }

    private fun readUnsignedLeb(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var cursor = start
        while (cursor < bytes.size && shift < 35) {
            val byte = bytes[cursor++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return result to cursor
            shift += 7
        }
        throw IOException("Max returned an invalid stream decoder")
    }

    private fun readSignedLeb(bytes: ByteArray, start: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var cursor = start
        var byte: Int
        do {
            if (cursor >= bytes.size || shift >= 35) throw IOException("Max returned an invalid stream decoder")
            byte = bytes[cursor++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            shift += 7
        } while (byte and 0x80 != 0)
        if (shift < 32 && byte and 0x40 != 0) result = result or (-1 shl shift)
        return result to cursor
    }

    private fun decryptMaxStreams(value: String, key: ByteArray): List<String> {
        val bytes = runCatching { Base64.getDecoder().decode(value) }
            .getOrElse { throw IOException("Max returned an invalid stream payload", it) }
        if (bytes.size <= 12) throw IOException("Max returned an invalid stream payload")
        val nonce = bytes.copyOfRange(0, 12)
        val output = bytes.copyOfRange(12, bytes.size)
        var offset = 0
        var counter = 0
        while (offset < output.size) {
            val block = chachaBlock(key, nonce, counter++)
            val count = minOf(64, output.size - offset)
            for (index in 0 until count) output[offset + index] = (output[offset + index].toInt() xor block[index].toInt()).toByte()
            offset += count
        }
        return String(output, StandardCharsets.UTF_8).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    private fun chachaBlock(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        if (key.size != 32 || nonce.size != 12) throw IOException("Max returned an invalid stream key")
        val input = IntArray(16)
        input[0] = 0x61707865
        input[1] = 0x3320646e
        input[2] = 0x79622d32
        input[3] = 0x6b206574
        val keyWords = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 8) input[4 + index] = keyWords.int
        input[12] = counter
        val nonceWords = ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 3) input[13 + index] = nonceWords.int
        val state = input.copyOf()
        repeat(10) {
            quarterRound(state, 0, 4, 8, 12); quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14); quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15); quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13); quarterRound(state, 3, 4, 9, 14)
        }
        val output = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (index in state.indices) output.putInt(state[index] + input[index])
        return output.array()
    }

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] xor state[a], 16)
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] xor state[c], 12)
        state[a] += state[b]; state[d] = Integer.rotateLeft(state[d] xor state[a], 8)
        state[c] += state[d]; state[b] = Integer.rotateLeft(state[b] xor state[c], 7)
    }

    private fun resolveVidpro(target: PlaybackTarget): ResolvedMovie {
        val moviePage = playerOrigin.resolve(target.path) ?: error("Invalid player origin")
        val apiUrl = playerOrigin.resolve("api/${target.path}") ?: error("Invalid player origin")
        val api = get(apiUrl, referer = moviePage.toString())
        val embedPath = JsonParser.parseString(api).asJsonObject.string("src")
        require(embedPath.startsWith("/embed/")) { "Player returned an invalid embed path" }

        val embedUrl = playerOrigin.resolve(embedPath) ?: error("Player returned an invalid embed URL")
        val embed = get(embedUrl, referer = moviePage.toString())
        val playlistBase = MASTER_URL.find(embed)?.groupValues?.get(1)
            ?: throw IOException("Player page did not contain a playlist")
        val token = MASTER_TOKEN.find(embed)?.groupValues?.get(1)
            ?: throw IOException("Player page did not contain a playlist token")
        val expires = MASTER_EXPIRES.find(embed)?.groupValues?.get(1)
            ?: throw IOException("Player page did not contain a playlist expiry")

        val playlist = playlistBase.replace("\\/", "/").toHttpUrl().newBuilder()
            .setQueryParameter("token", token)
            .setQueryParameter("expires", expires)
            .setQueryParameter("h", "1")
            .setQueryParameter("lang", "en")
            .build()
        check(isSafe(playlist) && playlist.host == playerOrigin.host) { "Player returned an unsafe playlist URL" }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl.toString(),
            "Origin" to playerOrigin.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        val probe = get(playlist, headers)
        if (!probe.startsWith("#EXTM3U")) throw IOException("Player returned an invalid HLS playlist")
        return ResolvedMovie(playlist.toString(), headers)
    }

    private fun resolveAlternative(target: PlaybackTarget, sourceId: String): ResolvedMovie {
        require(sourceId.startsWith("vidrock:")) { "Unknown movie source" }
        val name = sourceId.removePrefix("vidrock:")
        val item = alternativeEntries(target)[name] ?: throw IOException("Source is no longer available")
        val encrypted = item.get("url")?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Source did not return a stream")
        val candidate = decryptAlternativeUrl(encrypted).toHttpUrl()
        check(isSafe(candidate)) { "Source returned an unsafe playlist URL" }
        val page = alternativeOrigin.resolve(target.path)!!.toString()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to page,
            "Origin" to alternativeOrigin.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        val playlist = playableAlternative(candidate, headers)
        return ResolvedMovie(playlist.toString(), headers)
    }

    private fun alternativeEntries(target: PlaybackTarget): Map<String, JsonObject> {
        val api = alternativeOrigin.resolve("api/${target.path}") ?: error("Invalid alternative origin")
        val page = alternativeOrigin.resolve(target.path) ?: error("Invalid alternative origin")
        val root = JsonParser.parseString(get(api, referer = page.toString())).asJsonObject
        return root.entrySet().mapNotNull { (name, value) ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.let { name to it }
        }.toMap()
    }

    private fun playableAlternative(candidate: HttpUrl, headers: Map<String, String>): HttpUrl {
        val body = get(candidate, headers).trim()
        if (body.startsWith("#EXTM3U")) return candidate
        if (body.startsWith("[")) {
            val best = JsonParser.parseString(body).asJsonArray
                .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                .filter { it.get("url")?.takeUnless { value -> value.isJsonNull }?.asString?.isNotBlank() == true }
                .maxByOrNull { it.get("resolution")?.takeUnless { value -> value.isJsonNull }?.asInt ?: 0 }
                ?: throw IOException("Source returned no playable quality")
            val url = best.get("url").asString.toHttpUrl()
            check(isSafe(url)) { "Source returned an unsafe quality URL" }
            if (!get(url, headers).trim().startsWith("#EXTM3U")) throw IOException("Source returned an invalid HLS playlist")
            return url
        }
        throw IOException("Source returned an invalid HLS playlist")
    }

    private fun decryptAlternativeUrl(value: String): String {
        val padded = value.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val bytes = Base64.getDecoder().decode(padded)
        if (bytes.size < 28) throw IOException("Source returned an invalid stream token")
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ALTERNATIVE_KEY, "AES"), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8)
        }.getOrElse { throw IOException("Could not decode source stream", it) }
    }

    private fun discover(type: MediaType, page: Int, query: String?): CatalogPage {
        require(page in 1..500)
        val url = catalogOrigin.resolve("api/search/discover")!!.newBuilder()
            .addQueryParameter("type", type.apiValue)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "popularity")
            .addQueryParameter("now_playing", "false")
            .addQueryParameter("trending", "false")
            .apply {
                listOf("country", "genre", "rating", "watch_provider", "year")
                    .forEach { addQueryParameter(it, "all") }
                if (query != null) addQueryParameter("q", query)
            }.build()
        val response = JsonParser.parseString(get(url, referer = catalogOrigin.toString())).asJsonObject
        val results = response.getAsJsonArray("results") ?: return CatalogPage(emptyList(), page, 1)
        val totalPages = (response.get("total_pages")?.asInt ?: 1).coerceIn(1, 500)
        val movies = results.mapNotNull { element ->
            val item = element.asJsonObject
            val id = item.get("id")?.asString.orEmpty()
            val title = item.get(if (type == MediaType.TV) "name" else "title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val posterPath = item.get("poster_path")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (id.isEmpty() || !id.all(Char::isDigit) || title.isBlank() || !posterPath.startsWith("/")) return@mapNotNull null
            Movie(
                id = id,
                title = title,
                poster = "https://image.tmdb.org/t/p/w500$posterPath",
                backdrop = item.imageUrl("backdrop_path", "original"),
                rating = item.get("vote_average")?.takeUnless { it.isJsonNull }?.asDouble
                    ?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                overview = item.get("overview")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                releaseDate = item.get(if (type == MediaType.TV) "first_air_date" else "release_date")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
                type = type,
            )
        }.distinctBy(Movie::id)
        return CatalogPage(movies, page, totalPages)
    }

    private fun get(url: HttpUrl, referer: String): String = get(url, mapOf("Referer" to referer))

    private fun get(url: HttpUrl, headers: Map<String, String>): String =
        String(getBytes(url, headers), StandardCharsets.UTF_8)

    private fun getBytes(url: HttpUrl, headers: Map<String, String>): ByteArray {
        require(isSafe(url)) { "Only HTTPS requests are allowed" }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${url.host} returned HTTP ${response.code}")
            return response.body?.bytes() ?: throw IOException("${url.host} returned an empty response")
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString ?: throw IOException("Missing $name")

    private fun JsonObject.imageUrl(name: String, size: String): String {
        val path = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        return if (path.startsWith("/")) "https://image.tmdb.org/t/p/$size$path" else ""
    }

    private fun requireMovieId(id: String) {
        require(id.isNotEmpty() && id.all(Char::isDigit)) { "Invalid movie ID" }
    }

    private fun isSafe(url: HttpUrl): Boolean = url.isHttps || url.host == "localhost" || url.host == "127.0.0.1"

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/128 Safari/537.36"
        val DEFAULT_SOURCE = MovieSource("max", "Max")
        val VIDPRO_SOURCE = MovieSource("vidpro", "Vidpro")
        val BUILT_IN_SOURCES = listOf(DEFAULT_SOURCE, VIDPRO_SOURCE)
        private const val MAX_TOKEN_CACHE_MS = 30 * 60 * 1_000L
        private const val MAX_WASM_MEMORY = 64 * 1024
        private val WASM_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        private val MAX_LANDING_CONFIG = Regex("window\\.CFG\\s*=\\s*(\\{.*?\\});", setOf(RegexOption.DOT_MATCHES_ALL))
        private val MAX_PLAYER_CONFIG = Regex("window\\.CONFIG\\s*=\\s*(\\{.*?\\});", setOf(RegexOption.DOT_MATCHES_ALL))
        private val ALTERNATIVE_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private val MASTER_URL = Regex("url:\\s*'([^']+/playlist/[^']+)'", RegexOption.IGNORE_CASE)
        private val MASTER_TOKEN = Regex("'token':\\s*'([^']+)'", RegexOption.IGNORE_CASE)
        private val MASTER_EXPIRES = Regex("'expires':\\s*'(\\d+)'", RegexOption.IGNORE_CASE)

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
