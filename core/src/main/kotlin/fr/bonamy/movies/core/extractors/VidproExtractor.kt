package fr.bonamy.movies.core.extractors

import fr.bonamy.movies.core.*

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

internal class VidproExtractor(private val network: HttpTransport, private val playerOrigin: HttpUrl) : StreamExtractor<TmdbPlayback> {

    override fun resolve(target: TmdbPlayback): ResolvedPlayback {
        val moviePage = playerOrigin.resolve(target.path) ?: error("Invalid player origin")
        val apiUrl = playerOrigin.resolve("api/${target.path}") ?: error("Invalid player origin")
        val api = network.text(apiUrl, referer = moviePage.toString())
        val embedPath = JsonParser.parseString(api).asJsonObject.string("src")
        require(embedPath.startsWith("/embed/")) { "Player returned an invalid embed path" }

        val embedUrl = playerOrigin.resolve(embedPath) ?: error("Player returned an invalid embed URL")
        val embed = network.text(embedUrl, referer = moviePage.toString())
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
        check(network.accepts(playlist) && playlist.host == playerOrigin.host) { "Player returned an unsafe playlist URL" }

        val headers = mapOf(
            "User-Agent" to HttpTransport.USER_AGENT,
            "Referer" to embedUrl.toString(),
            "Origin" to playerOrigin.newBuilder().encodedPath("/").query(null).build().toString().removeSuffix("/"),
        )
        val probe = network.text(playlist, headers)
        if (!probe.startsWith("#EXTM3U")) throw IOException("Player returned an invalid HLS playlist")
        return ResolvedPlayback(playlist.toString(), headers)
    }

    companion object {
        private val MASTER_URL = Regex("url:\\s*'([^']+/playlist/[^']+)'", RegexOption.IGNORE_CASE)
        private val MASTER_TOKEN = Regex("'token':\\s*'([^']+)'", RegexOption.IGNORE_CASE)
        private val MASTER_EXPIRES = Regex("'expires':\\s*'(\\d+)'", RegexOption.IGNORE_CASE)

    }
}
