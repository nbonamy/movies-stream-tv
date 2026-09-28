package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Title
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import org.json.JSONObject

internal data class PlaybackBookmark(
    val movie: Title,
    val target: PlayableRef,
    val episodeName: String?,
    val artwork: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
)

/** Local progress follows MediaStation's 30-second start and 95% completion rules. */
internal class PlaybackProgress(private val preferences: SharedPreferences) {
    init { LegacyVidboxMigration.progress(preferences) }

    fun position(target: PlayableRef): Long = read(target.key)?.position ?: 0

    fun list(siteId: String, sectionId: String): List<PlaybackBookmark> = preferences.all.keys.mapNotNull(::read)
        .filter { it.target.siteId == siteId && it.movie.sectionId == sectionId }.sortedByDescending { it.updatedAt }

    fun save(movie: Title, target: PlayableRef, episodeName: String?, artwork: String,
        position: Long, duration: Long, ended: Boolean = false) {
        require(movie.ref == target.title)
        // Unprepared/erroring streams must not erase a previously saved position.
        if (!ended && (position < 0 || duration <= 0)) return
        val resumable = !ended && position > 30_000 && position.toDouble() <= duration * 0.95
        if (!resumable) {
            preferences.edit().remove(target.key).apply()
            return
        }
        if (read(target.key)?.let { it.position == position && it.duration == duration } == true) return
        val data = JSONObject().put("siteId", target.siteId).put("episodeId", target.episodeId).put("id", movie.id).put("type", target.type.name)
            .put("sectionId", movie.sectionId).put("title", movie.title).put("poster", movie.poster).put("backdrop", movie.backdrop)
            .put("rating", movie.rating).put("overview", movie.overview).put("releaseDate", movie.releaseDate)
            .put("season", target.season).put("episode", target.episode).put("episodeName", episodeName)
            .put("artwork", artwork).put("position", position).put("duration", duration)
            .put("updatedAt", System.currentTimeMillis())
        preferences.edit().putString(target.key, data.toString()).apply()
    }

    private fun read(key: String): PlaybackBookmark? = runCatching {
        val data = JSONObject(preferences.getString(key, null) ?: return null)
        val type = MediaType.valueOf(data.getString("type"))
        val movie = Title(data.getString("id"), data.getString("title"), data.optString("poster"),
            data.optString("backdrop"), data.optString("rating"), data.optString("overview"),
            data.optString("releaseDate"), type, data.getString("siteId"), data.optString("sectionId", type.apiValue))
        val target = PlayableRef(movie.ref, data.optString("episodeId").takeIf { it.isNotBlank() },
            if (data.has("season")) data.getInt("season") else null,
            if (data.has("episode")) data.getInt("episode") else null)
        val position = data.getLong("position")
        val duration = data.getLong("duration")
        require(target.key == key && position > 30_000 && duration > 0 && position <= duration * 0.95)
        PlaybackBookmark(movie, target, data.optString("episodeName").takeIf { it.isNotBlank() },
            data.optString("artwork"), position, duration, data.getLong("updatedAt"))
    }.getOrNull()
}
