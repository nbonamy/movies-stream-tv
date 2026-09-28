package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Movie
import fr.bonamy.movies.core.PlaybackTarget
import org.json.JSONObject

internal data class PlaybackBookmark(
    val movie: Movie,
    val target: PlaybackTarget,
    val episodeName: String?,
    val artwork: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
)

/** Local progress follows MediaStation's 30-second start and 95% completion rules. */
internal class PlaybackProgress(private val preferences: SharedPreferences) {
    fun position(target: PlaybackTarget): Long = read(target.path)?.position ?: 0

    fun list(type: MediaType): List<PlaybackBookmark> = preferences.all.keys.mapNotNull(::read)
        .filter { it.target.type == type }.sortedByDescending { it.updatedAt }

    fun save(movie: Movie, target: PlaybackTarget, episodeName: String?, artwork: String,
        position: Long, duration: Long, ended: Boolean = false) {
        // Unprepared/erroring streams must not erase a previously saved position.
        if (!ended && (position < 0 || duration <= 0)) return
        val resumable = !ended && position > 30_000 && position.toDouble() <= duration * 0.95
        if (!resumable) {
            preferences.edit().remove(target.path).apply()
            return
        }
        if (read(target.path)?.let { it.position == position && it.duration == duration } == true) return
        val data = JSONObject().put("id", movie.id).put("type", target.type.name)
            .put("title", movie.title).put("poster", movie.poster).put("backdrop", movie.backdrop)
            .put("rating", movie.rating).put("overview", movie.overview).put("releaseDate", movie.releaseDate)
            .put("season", target.season).put("episode", target.episode).put("episodeName", episodeName)
            .put("artwork", artwork).put("position", position).put("duration", duration)
            .put("updatedAt", System.currentTimeMillis())
        preferences.edit().putString(target.path, data.toString()).apply()
    }

    private fun read(key: String): PlaybackBookmark? = runCatching {
        val data = JSONObject(preferences.getString(key, null) ?: return null)
        val type = MediaType.valueOf(data.getString("type"))
        val movie = Movie(data.getString("id"), data.getString("title"), data.optString("poster"),
            data.optString("backdrop"), data.optString("rating"), data.optString("overview"),
            data.optString("releaseDate"), type)
        val target = PlaybackTarget(movie.id, type,
            if (type == MediaType.TV) data.getInt("season") else null,
            if (type == MediaType.TV) data.getInt("episode") else null)
        val position = data.getLong("position")
        val duration = data.getLong("duration")
        require(target.path == key && position > 30_000 && duration > 0 && position <= duration * 0.95)
        PlaybackBookmark(movie, target, data.optString("episodeName").takeIf { it.isNotBlank() },
            data.optString("artwork"), position, duration, data.getLong("updatedAt"))
    }.getOrNull()
}
