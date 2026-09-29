package fr.bonamy.movies.core.sites.cinejoy

import com.google.gson.JsonParser
import fr.bonamy.movies.core.HttpTransport
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** Read the site's public client configuration as data; never execute its JavaScript. */
internal class CinejoyConfig(private val network: HttpTransport) {
    @Volatile private var cached: String? = null
    @Synchronized fun key(): String {
        cached?.let { return it }
        val bytes = network.bytes("https://cinejoy.pk/_app/immutable/chunks/BJh85vmL.js".toHttpUrl(),
            mapOf("Referer" to "https://cinejoy.pk/"), 256 * 1024, redirectAllowed = { it.host == "cinejoy.pk" })
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != "bad7a8cc319fcd3378fed7f77fc57b7d1b9b6e40a594343bc33a2ce2d4de4a17")
            throw IOException("Cinejoy catalog configuration changed; an app update is needed")
        val source = bytes.toString(Charsets.UTF_8)
        val array = Regex("function Tt\\(\\)\\{const s=(\\[.*?]);").find(source)?.groupValues?.get(1)
            ?: throw IOException("Cinejoy catalog configuration unavailable")
        val entries = JsonParser.parseString(array).asJsonArray.map { it.asString }
        fun decode(index: Int, key: String): String {
            val encoded = entries[(index - 397 + 236) % entries.size]
            val from = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/"
            val to = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val translated = encoded.map { ch -> from.indexOf(ch).takeIf { it >= 0 }?.let { to[it] } ?: ch }.joinToString("")
            val input = Base64.getDecoder().decode(translated).toString(Charsets.UTF_8)
            val box = IntArray(256) { it }; var j = 0
            for (i in 0..255) {
                j = (j + box[i] + key[i % key.length].code) and 255
                val t = box[i]; box[i] = box[j]; box[j] = t
            }
            var i = 0; j = 0
            return input.map { ch ->
                i = (i + 1) and 255; j = (j + box[i]) and 255
                val t = box[i]; box[i] = box[j]; box[j] = t
                (ch.code xor box[(box[i] + box[j]) and 255]).toChar()
            }.joinToString("")
        }
        if (decode(885, "Tzu)") != "https://api.themoviedb.org/3") throw IOException("Unexpected catalog endpoint")
        val key = decode(901, "2@Qq")
        if (!key.matches(Regex("[a-f0-9]{32}"))) throw IOException("Invalid catalog client configuration")
        cached = key // Public website client key stays in memory, never source, logs or bookmarks.
        return key
    }
}
