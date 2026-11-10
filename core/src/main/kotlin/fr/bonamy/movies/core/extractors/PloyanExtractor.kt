package fr.bonamy.movies.core.extractors

import com.google.gson.JsonParser
import fr.bonamy.movies.core.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Ployan's direct streams and authenticated player descriptors. No provider script execution. */
internal class PloyanExtractor(private val network: HttpTransport) {
    private val origin = "https://ployan.me/".toHttpUrl()
    private val headers = mapOf("Referer" to origin.toString(), "Origin" to "https://ployan.me",
        "User-Agent" to HttpTransport.USER_AGENT)

    fun resolve(mid: String, episode: Int, server: Int = 1): ResolvedPlayback {
        require(mid.matches(Regex("[0-9]+")) && episode > 0 && server in listOf(1, 2, 5))
        val payload = "$mid+$episode+$server+${System.currentTimeMillis() / 1000}"
        val response = JsonParser.parseString(network.text(origin.resolve("get/${encode(payload)}")!!, headers)).asJsonObject
        if (response.get("code")?.asInt != 200) throw IOException("The selected server has no stream for this title")
        if (response.get("mode")?.asString == "embed") {
            val embed = decodeEmbed(response.string("info"))
            return when (server) {
                2 -> {
                    // The decrypted player response owns this TMDB mapping, not the page slug.
                    val id = Regex("movie/([0-9]+)-[0-9]{10}").matchEntire(embed)?.groupValues?.get(1)
                        ?: throw IOException("Server 2 returned an unsupported player identity")
                    MaxExtractor(network, "https://vidsrc.sh/".toHttpUrl()).resolve(TmdbPlayback(id))
                }
                5 -> {
                    val url = embed.toHttpUrl().let { raw ->
                        raw.newBuilder().encodedPath(raw.encodedPath.replace(Regex("-[0-9]{10}$"), "")).build()
                    }
                    VidaraExtractor(network).resolve(url)
                }
                else -> throw IOException("The selected server returned an unsupported player")
            }
        }
        if (response.get("mode")?.asString != "direct") throw IOException("The selected server returned an unsupported response")
        val token = response.string("info")
        if (!token.matches(Regex("[a-fA-F0-9]+-[a-fA-F0-9]+-[a-fA-F0-9]+")) || token.length > 4096)
            throw IOException("Ployan returned an invalid stream")
        return ResolvedPlayback(origin.resolve("hls/$token/master.m3u8")!!.toString(), headers,
            subtitles = subtitles(mid, episode))
    }

    private fun decodeEmbed(value: String): String {
        if (value.length > 4096 || !value.matches(Regex("[a-fA-F0-9]{16}-[a-fA-F0-9]{24}-[a-fA-F0-9]{32,}"))) {
            throw IOException("Ployan returned an invalid player descriptor")
        }
        return try {
            val parts = value.split('-').map { part -> part.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            val spec = PBEKeySpec("player".toCharArray(), parts[0], 1000, 256)
            val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                finally { spec.clearPassword() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, parts[1]))
            cipher.doFinal(parts[2]).toString(Charsets.UTF_8)
        } catch (error: java.security.GeneralSecurityException) {
            throw IOException("Could not authenticate the selected player", error)
        }
    }

    private fun subtitles(mid: String, episode: Int): List<PlaybackSubtitle> {
        val mask = "player".fold(0) { code, char -> code xor char.code }
        val item = "$mid-$episode".map { "%02x".format(it.code xor mask) }.joinToString("")
        val folder = "/sub/$item/"
        return try {
            JsonParser.parseString(network.text(origin.resolve("${folder}index.json")!!, headers)).asJsonArray
                .mapNotNull { element ->
                    val row = element.asJsonObject
                    val language = subtitleLanguageCode(row.get("lang")?.asString)
                    if (language !in listOf("fr", "en")) return@mapNotNull null
                    val url = origin.resolve(row.string("file")) ?: return@mapNotNull null
                    if (!ownedSubtitle(url, folder)) return@mapNotNull null
                    PlaybackSubtitle("ployan:${url.encodedPath}", url.toString(), language!!,
                        row.get("label")?.asString.orEmpty().ifBlank { if (language == "fr") "French" else "English" })
                }.distinctBy { it.id }
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw error
            emptyList() // Missing captions must not prevent video playback.
        }
    }

    private fun ownedSubtitle(url: HttpUrl, folder: String) = url.isHttps && url.host == origin.host &&
        url.port == origin.port && url.encodedPath.startsWith(folder) && url.encodedPath.endsWith(".vtt")

    private fun encode(value: String): String {
        val random = SecureRandom()
        val salt = ByteArray(8).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val spec = PBEKeySpec("player".toCharArray(), salt, 1000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return listOf(salt, iv, cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
            .joinToString("-") { bytes -> bytes.joinToString("") { "%02x".format(it.toInt() and 255) } }
    }
}
