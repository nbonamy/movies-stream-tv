package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class MaxExtractor(private val network: HttpTransport, private val maxOrigin: HttpUrl) : StreamExtractor<TmdbPlayback> {

    private val maxTokens = mutableMapOf<String, Pair<String, Long>>()

    override fun resolve(target: TmdbPlayback): ResolvedPlayback {
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
        val landingUrl = JsonParser.parseString(network.text(descriptorUrl, referer = embedUrl.toString()))
            .asJsonObject.string("src").toHttpUrl()
        check(network.accepts(landingUrl)) { "Max returned an unsafe player URL" }

        val landing = network.text(landingUrl, referer = maxOrigin.toString())
        val playerPath = JsonParser.parseString(MAX_LANDING_CONFIG.find(landing)?.groupValues?.get(1)
            ?: throw IOException("Max did not return a player configuration"))
            .asJsonObject.string("playerUrl")
        val playerUrl = landingUrl.resolve(playerPath) ?: throw IOException("Max returned an invalid player URL")
        check(network.accepts(playerUrl)) { "Max returned an unsafe player URL" }

        val playerPage = network.text(playerUrl, referer = landingUrl.toString())
        val playerConfig = JsonParser.parseString(MAX_PLAYER_CONFIG.find(playerPage)?.groupValues?.get(1)
            ?: throw IOException("Max did not return a stream configuration"))
            .asJsonObject
        val apiUrl = if (target.type == MediaType.TV) {
            playerConfig.string("streamBase").toHttpUrl().newBuilder()
                .setQueryParameter("season", target.season.toString())
                .setQueryParameter("episode", target.episode.toString())
                .setQueryParameter("stream_urls", null).build()
        } else playerConfig.string("api").toHttpUrl()
        check(network.accepts(apiUrl)) { "Max returned an unsafe stream API" }
        val streamResponse = JsonParser.parseString(network.text(apiUrl, referer = playerUrl.toString())).asJsonObject
        val encrypted = streamResponse.getAsJsonObject("data")?.string("stream_urls")
            ?: throw IOException("Max did not return streams")
        val wasmUrl = streamResponse.getAsJsonObject("vs")?.string("wasm_url")?.toHttpUrl()
            ?: throw IOException("Max did not return its stream decoder")
        check(network.accepts(wasmUrl)) { "Max returned an unsafe stream decoder" }
        val key = maxKey(network.bytes(wasmUrl, mapOf("Referer" to playerUrl.toString())))
        val streams = decryptMaxStreams(encrypted, key)
        if (streams.isEmpty()) throw IOException("Max returned no streams")

        val mediaHeaders = mapOf(
            "User-Agent" to HttpTransport.USER_AGENT,
            "Referer" to playerUrl.toString(),
            "Origin" to playerUrl.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        streams.forEach { raw ->
            val candidate = runCatching { raw.toHttpUrl() }.getOrNull() ?: return@forEach
            if (!network.accepts(candidate)) return@forEach
            val token = runCatching { maxToken(candidate) }.getOrNull() ?: return@forEach
            val playlist = candidate.newBuilder().addQueryParameter("token", token).build()
            val body = runCatching { network.text(playlist, mediaHeaders) }.getOrNull() ?: return@forEach
            if (body.trimStart().startsWith("#EXTM3U")) return ResolvedPlayback(playlist.toString(), mediaHeaders,
                streamResponse.getAsJsonObject("data")?.get("imdb_id")?.takeUnless { it.isJsonNull }
                    ?.asString?.takeIf { it.isNotBlank() }?.let { SubtitleContext(it, target.season, target.episode) })
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
            try {
                val token = network.text(tokenUrl).trim()
                if (token.isEmpty()) throw IOException("Max returned an empty stream token")
                synchronized(maxTokens) { maxTokens[cacheKey] = token to System.currentTimeMillis() }
                return token
            } catch (error: HttpStatusException) {
                if (error.status != 429) throw error
                lastError = error
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

    companion object {
        private const val MAX_TOKEN_CACHE_MS = 30 * 60 * 1_000L
        private const val MAX_WASM_MEMORY = 64 * 1024
        private val WASM_HEADER = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
        private val MAX_LANDING_CONFIG = Regex("window\\.CFG\\s*=\\s*(\\{.*?\\});", setOf(RegexOption.DOT_MATCHES_ALL))
        private val MAX_PLAYER_CONFIG = Regex("window\\.CONFIG\\s*=\\s*(\\{.*?\\});", setOf(RegexOption.DOT_MATCHES_ALL))
    }
}
