package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class VidRockExtractor(private val network: HttpTransport, private val alternativeOrigin: HttpUrl) : StreamExtractor<Pair<TmdbPlayback, String>> {

    override fun resolve(request: Pair<TmdbPlayback, String>): ResolvedPlayback {
        val (target, sourceId) = request
        require(sourceId.startsWith("vidrock:")) { "Unknown movie source" }
        val name = sourceId.removePrefix("vidrock:")
        val item = alternativeEntries(target)[name] ?: throw IOException("Source is no longer available")
        val encrypted = item.get("url")?.takeUnless { it.isJsonNull }?.asString
            ?: throw IOException("Source did not return a stream")
        val candidate = decryptAlternativeUrl(encrypted).toHttpUrl()
        check(network.accepts(candidate)) { "Source returned an unsafe playlist URL" }
        val page = alternativeOrigin.resolve(target.path)!!.toString()
        val headers = mapOf(
            "User-Agent" to HttpTransport.USER_AGENT,
            "Referer" to page,
            "Origin" to alternativeOrigin.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        val playlist = playableAlternative(candidate, headers)
        return ResolvedPlayback(playlist.toString(), headers)
    }

    fun alternativeEntries(target: TmdbPlayback): Map<String, JsonObject> {
        val api = alternativeOrigin.resolve("api/${target.path}") ?: error("Invalid alternative origin")
        val page = alternativeOrigin.resolve(target.path) ?: error("Invalid alternative origin")
        val root = JsonParser.parseString(network.text(api, referer = page.toString())).asJsonObject
        return root.entrySet().mapNotNull { (name, value) ->
            value.takeIf { it.isJsonObject }?.asJsonObject?.let { name to it }
        }.toMap()
    }

    private fun playableAlternative(candidate: HttpUrl, headers: Map<String, String>): HttpUrl {
        val body = network.text(candidate, headers).trim()
        if (body.startsWith("#EXTM3U")) return candidate
        if (body.startsWith("[")) {
            val best = JsonParser.parseString(body).asJsonArray
                .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                .filter { it.get("url")?.takeUnless { value -> value.isJsonNull }?.asString?.isNotBlank() == true }
                .maxByOrNull { it.get("resolution")?.takeUnless { value -> value.isJsonNull }?.asInt ?: 0 }
                ?: throw IOException("Source returned no playable quality")
            val url = best.get("url").asString.toHttpUrl()
            check(network.accepts(url)) { "Source returned an unsafe quality URL" }
            if (!network.text(url, headers).trim().startsWith("#EXTM3U")) throw IOException("Source returned an invalid HLS playlist")
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

    companion object {
        private val ALTERNATIVE_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
