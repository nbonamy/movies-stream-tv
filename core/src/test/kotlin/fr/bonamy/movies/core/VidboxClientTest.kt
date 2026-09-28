package fr.bonamy.movies.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VidboxClientTest {
    @Test
    fun resolvesProviderDescriptorIntoValidatedHlsPlayback() {
        MockWebServer().use { catalog ->
            MockWebServer().use { player ->
                player.enqueue(MockResponse().setBody("""{"src":"/embed/231752?session=fresh"}"""))
                player.enqueue(MockResponse().setBody("""
                    <script>
                      window.masterPlaylist = {
                        params: {'token': 'playlist-token', 'expires': '2000000000'},
                        url: '${player.url("/playlist/231752?b=1")}',
                      }
                    </script>
                """.trimIndent()))
                player.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:7"))

                val result = VidboxClient(
                    catalogOrigin = catalog.url("/"),
                    playerOrigin = player.url("/"),
                ).resolve(PlaybackTarget("27205"), VidboxClient.VIDPRO_SOURCE.id)

                val playlist = result.playlistUrl.toHttpUrl()
                assertEquals("playlist-token", playlist.queryParameter("token"))
                assertEquals("2000000000", playlist.queryParameter("expires"))
                assertEquals("1", playlist.queryParameter("h"))
                assertTrue(result.requestHeaders.getValue("Referer").contains("/embed/231752"))
                assertEquals("/api/movie/27205", player.takeRequest().path)
                assertTrue(player.takeRequest().path!!.startsWith("/embed/231752"))
                assertTrue(player.takeRequest().path!!.startsWith("/playlist/231752"))
            }
        }
    }

    @Test
    fun resolvesVidboxDefaultMaxSourceIntoTokenizedHlsPlayback() {
        listOf(PlaybackTarget("1368337"), PlaybackTarget("108978", MediaType.TV, 2, 3)).forEach { target ->
        MockWebServer().use { max ->
            val key = ByteArray(32) { (it * 7 + 3).toByte() }
            val nonce = ByteArray(12) { (it + 11).toByte() }
            val rawPlaylist = max.url("/master.m3u8").toString()
            val encrypted = encryptMaxStreams(rawPlaylist, key, nonce)
            val wasm = maxDecoderWasm(key)

            max.enqueue(MockResponse().setBody("""{"src":"${max.url("/landing")}"}"""))
            max.enqueue(MockResponse().setBody("""<script>window.CFG = {"playerUrl":"/player"};</script>"""))
            val configKey = if (target.type == MediaType.TV) "streamBase" else "api"
            max.enqueue(MockResponse().setBody("""<script>window.CONFIG = {"$configKey":"${max.url("/api/streams")}"};</script>"""))
            max.enqueue(MockResponse().setBody("""{"data":{"stream_urls":"$encrypted"},"vs":{"wasm_url":"${max.url("/decoder.wasm")}"}}"""))
            max.enqueue(MockResponse().setBody(okio.Buffer().write(wasm)))
            max.enqueue(MockResponse().setBody("stream-token"))
            max.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:7"))

            val result = VidboxClient(maxOrigin = max.url("/")).resolve(target)

            assertEquals("stream-token", result.playlistUrl.toHttpUrl().queryParameter("token"))
            val descriptor = max.takeRequest().requestUrl!!
            assertEquals(target.id, descriptor.queryParameter("id"))
            assertEquals(target.type.apiValue, descriptor.queryParameter("type"))
            assertEquals(target.season?.toString(), descriptor.queryParameter("season"))
            assertEquals(target.episode?.toString(), descriptor.queryParameter("episode"))
            assertEquals("/landing", max.takeRequest().path)
            assertEquals("/player", max.takeRequest().path)
            val streamRequest = max.takeRequest().requestUrl!!
            assertEquals("/api/streams", streamRequest.encodedPath)
            assertEquals(target.season?.toString(), streamRequest.queryParameter("season"))
            assertEquals(target.episode?.toString(), streamRequest.queryParameter("episode"))
            if (target.type == MediaType.TV) assertTrue("stream_urls" in streamRequest.queryParameterNames)
            assertEquals("/decoder.wasm", max.takeRequest().path)
            assertEquals("/generate.php", max.takeRequest().path)
            assertEquals("/master.m3u8?token=stream-token", max.takeRequest().path)
        }
    }
        }

    @Test
    fun discoversAndResolvesAnEncryptedAlternativeSource() {
        MockWebServer().use { alternative ->
            val playlist = alternative.url("/atlas/master.m3u8").toString()
            val encrypted = encryptAlternativeUrl(playlist)
            val sources = """{"Nova":{"url":null,"type":null},"Atlas":{"url":"$encrypted","language":"English","type":"hls"}}"""
            alternative.enqueue(MockResponse().setBody(sources))
            alternative.enqueue(MockResponse().setBody(sources))
            alternative.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-VERSION:7"))

            val client = VidboxClient(alternativeOrigin = alternative.url("/"))
            val available = client.sources(PlaybackTarget("27205"))
            val atlas = available.single { it.label == "VidRock • Atlas" }
            val resolved = client.resolve(PlaybackTarget("27205"), atlas.id)

            assertEquals(playlist, resolved.playlistUrl)
            assertEquals(alternative.url("/").toString().removeSuffix("/"), resolved.requestHeaders["Origin"])
            assertEquals("/api/movie/27205", alternative.takeRequest().path)
            assertEquals("/api/movie/27205", alternative.takeRequest().path)
            assertEquals("/atlas/master.m3u8", alternative.takeRequest().path)
        }
    }

    private fun encryptAlternativeUrl(value: String): String {
        val key = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + cipher.doFinal(value.toByteArray()))
    }

    private fun encryptMaxStreams(value: String, key: ByteArray, nonce: ByteArray): String {
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(value.toByteArray()))
    }

    private fun maxDecoderWasm(key: ByteArray): ByteArray {
        val first = ByteArray(32) { (it * 13 + 5).toByte() }
        val second = ByteArray(32) { index -> (first[index].toInt() xor key[index].toInt()).toByte() }
        // The provider relocates the second key share in each decoder window.
        val keyOffset = 1536
        val data = byteArrayOf(2) + dataSegment(0, first) + dataSegment(keyOffset, second)
        val loads = (0 until 8).fold(byteArrayOf()) { bytes, word ->
            bytes + byteArrayOf(0x41) + signedLeb(word * 4) + byteArrayOf(0x28, 2, 0, 0x41) +
                signedLeb(keyOffset + word * 4) + byteArrayOf(0x28, 2, 0, 0x73, 0x21, (6 + word).toByte())
        }
        val body = byteArrayOf(1, 14, 0x7f) + loads + byteArrayOf(0x0b)
        val code = byteArrayOf(1) + unsignedLeb(body.size) + body
        return byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0) +
            byteArrayOf(1, 4, 1, 0x60, 0, 0, 3, 2, 1, 0, 10) + unsignedLeb(code.size) + code +
            byteArrayOf(11) + unsignedLeb(data.size) + data
    }

    private fun dataSegment(offset: Int, value: ByteArray): ByteArray =
        byteArrayOf(0, 0x41) + signedLeb(offset) + byteArrayOf(0x0b) + unsignedLeb(value.size) + value

    private fun unsignedLeb(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        do {
            var byte = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) byte = byte or 0x80
            bytes += byte.toByte()
        } while (remaining != 0)
        return bytes.toByteArray()
    }

    private fun signedLeb(value: Int): ByteArray {
        var remaining = value
        val bytes = mutableListOf<Byte>()
        var more: Boolean
        do {
            var byte = remaining and 0x7f
            remaining = remaining shr 7
            more = !((remaining == 0 && byte and 0x40 == 0) || (remaining == -1 && byte and 0x40 != 0))
            if (more) byte = byte or 0x80
            bytes += byte.toByte()
        } while (more)
        return bytes.toByteArray()
    }
}
