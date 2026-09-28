package fr.bonamy.movies.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Reads Vidbox's serialized show data and the episode endpoint used by its public client. */
internal class SeriesCatalog(private val http: OkHttpClient, private val origin: HttpUrl) {
    private var episodeApiKey: String? = null
    private val pages = mutableMapOf<String, String>()

    fun seasons(id: String): List<Season> {
        require(id.matches(Regex("[0-9]+")))
        val html = get(origin.resolve("tv/$id")!!)
        synchronized(pages) { pages.clear(); pages[id] = html }
        val payload = html.split("self.__next_f.push(").drop(1).mapNotNull { part ->
            runCatching {
                val chunk = JsonParser.parseString(part.substringBefore(")</script>")).asJsonArray
                if (chunk.size() > 1 && chunk[0].asInt == 1) chunk[1].asString else null
            }.getOrNull()
        }.joinToString("")
        val show = payload.lineSequence().mapNotNull { line ->
            runCatching { JsonParser.parseString(line.substringAfter(':')) }.getOrNull()
        }.mapNotNull { findShow(it, id) }.firstOrNull()
            ?: throw IOException("Vidbox did not return this show's seasons")
        return show.getAsJsonArray("seasons").mapNotNull { element ->
            val season = element.asJsonObject
            val number = season.get("season_number")?.asInt ?: return@mapNotNull null
            val count = season.get("episode_count")?.asInt ?: 0
            if (number < 0 || count <= 0) return@mapNotNull null
            Season(number, season.text("name").ifBlank { if (number == 0) "Specials" else "Season $number" }, count)
        }.distinctBy { it.number }.sortedBy { it.number }
    }

    fun episodes(id: String, season: Int): List<Episode> {
        require(id.matches(Regex("[0-9]+")) && season >= 0)
        val key = episodeApiKey ?: discoverEpisodeKey(id).also { episodeApiKey = it }
        val url = "https://api.themoviedb.org/3/tv/$id/season/$season".toHttpUrl().newBuilder()
            .addQueryParameter("language", "en-US").addQueryParameter("api_key", key).build()
        val response = JsonParser.parseString(get(url)).asJsonObject
        return response.getAsJsonArray("episodes")?.mapNotNull { value ->
            val item = value.asJsonObject
            val number = item.get("episode_number")?.asInt ?: return@mapNotNull null
            if (number <= 0 || item.get("season_number")?.asInt != season) return@mapNotNull null
            val still = item.text("still_path").takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty()
            Episode(number, season, item.text("name").ifBlank { "Episode $number" }, item.text("overview"), still)
        }?.distinctBy { it.number }?.sortedBy { it.number }.orEmpty()
    }

    private fun discoverEpisodeKey(id: String): String {
        val html = synchronized(pages) { pages[id] } ?: get(origin.resolve("tv/$id")!!)
        val scriptPath = COMMON_SCRIPT.find(html)?.groupValues?.get(1)
            ?: throw IOException("Vidbox's episode configuration was unavailable")
        val url = origin.resolve(scriptPath) ?: throw IOException("Invalid episode configuration")
        if (url.host != origin.host) throw IOException("Invalid episode configuration host")
        // Parse the public client configuration as data; never execute downloaded JavaScript.
        return EPISODE_KEY.find(get(url))?.groupValues?.get(1)
            ?: throw IOException("Vidbox's episode configuration changed")
    }

    private fun findShow(element: JsonElement, id: String): JsonObject? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id && obj.has("seasons")) return obj
            obj.entrySet().forEach { findShow(it.value, id)?.let { show -> return show } }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { findShow(it, id)?.let { show -> return show } }
        }
        return null
    }

    private fun get(url: HttpUrl): String {
        val request = Request.Builder().url(url).header("User-Agent", VidboxClient.USER_AGENT)
            .header("Referer", origin.toString()).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Episode catalog returned HTTP ${response.code}")
            val input = response.body?.byteStream() ?: throw IOException("Empty episode catalog")
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 8 * 1024 * 1024) throw IOException("Episode catalog is too large")
                bytes.write(buffer, 0, count)
            }
            return bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    private fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    companion object {
        private val COMMON_SCRIPT = Regex("""src="(/_next/static/chunks/common\.[^"]+\.js)"""")
        private val EPISODE_KEY = Regex("""/season/[^;]{0,400}api_key=[^;]{0,150}concat\("([a-f0-9]{32})"\)""")
    }
}
