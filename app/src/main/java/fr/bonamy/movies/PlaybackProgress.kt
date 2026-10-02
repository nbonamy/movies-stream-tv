package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.Episode
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.Title
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import fr.bonamy.movies.core.SubtitleSelection
import fr.bonamy.movies.core.SubtitleLanguage
import fr.bonamy.movies.core.OnlineSubtitle
import org.json.JSONObject

internal data class PlaybackBookmark(
    val movie: Title,
    val target: PlayableRef,
    val episodeName: String?,
    val artwork: String,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    val subtitle: SubtitleSelection? = null,
    val queued: Boolean = false,
    val awaitingNext: Boolean = false,
    val queuedFrom: String? = null,
)

/** Watched progress plus explicit zero-position entries and durable pending episode transitions. */
internal class PlaybackProgress(private val preferences: SharedPreferences) {
    init { LegacyVidboxMigration.progress(preferences) }

    fun position(target: PlayableRef): Long = read(target.key)?.takeUnless { it.awaitingNext }?.position ?: 0

    fun bookmark(target: PlayableRef): PlaybackBookmark? = read(target.key)

    fun subtitle(target: PlayableRef): SubtitleSelection? = read(target.key)?.subtitle

    fun list(): List<PlaybackBookmark> = preferences.all.keys.mapNotNull(::read).sortedByDescending { it.updatedAt }

    fun remove(target: PlayableRef) { preferences.edit().remove(target.key).apply() }

    /** Explicit saving never overwrites an existing position or subtitle choice. */
    fun queue(movie: Title, target: PlayableRef, episodeName: String?, artwork: String): Boolean {
        require(movie.ref == target.title)
        if (read(target.key) != null) return false
        write(PlaybackBookmark(movie, target, episodeName, artwork, 0, 0, System.currentTimeMillis(), queued = true))
        return true
    }

    /** Apply a lookup only if the user has not removed, resumed or changed its bookmark. */
    fun advance(completed: PlaybackBookmark, next: Episode?): Boolean {
        require(completed.awaitingNext && completed.target.type == MediaType.TV)
        require(next == null || next.target.title == completed.target.title && next.target.key != completed.target.key)
        if (read(completed.target.key) != completed) return false
        val edit = preferences.edit().remove(completed.target.key)
        if (next != null) {
            val queued = PlaybackBookmark(completed.movie, next.target, next.name,
                next.still.ifBlank { completed.movie.backdrop.ifBlank { completed.movie.poster } },
                0, 0, System.currentTimeMillis(), completed.subtitle?.nextEpisode(),
                queued = true, queuedFrom = completed.target.key)
            edit.putString(next.target.key, encode(queued).toString())
        }
        edit.apply()
        return true
    }

    fun save(movie: Title, target: PlayableRef, episodeName: String?, artwork: String,
        position: Long, duration: Long, ended: Boolean = false, subtitle: SubtitleSelection? = null) {
        require(movie.ref == target.title)
        // Unprepared/erroring streams must not erase a previously saved position.
        if (!ended && (position < 0 || duration <= 0)) return
        val previous = read(target.key)
        val completed = ended || position.toDouble() > duration * 0.95
        val successor = if (target.type == MediaType.TV) list().firstOrNull { it.queued && it.queuedFrom == target.key } else null
        if (target.type == MediaType.TV && completed) {
            // Periodic checkpoints in the credits must not replace an already queued successor.
            if (successor != null) {
                val language = subtitle?.nextEpisode()
                if (successor.subtitle != language) write(successor.copy(subtitle = language))
                return
            }
            if (previous?.awaitingNext == true && previous.subtitle == subtitle) return
            write(PlaybackBookmark(movie, target, episodeName, artwork, position.coerceAtLeast(0),
                duration.coerceAtLeast(0), System.currentTimeMillis(), subtitle, awaitingNext = true))
            return
        }
        // Seeking back into the previous episode cancels only its automatically queued successor.
        successor?.let { preferences.edit().remove(it.target.key).apply() }
        if (completed) { remove(target); return }
        if (position <= 30_000) {
            if (previous?.queued == true) {
                if (previous.subtitle != subtitle) write(previous.copy(subtitle = subtitle))
            } else remove(target)
            return
        }
        if (previous?.let { !it.queued && !it.awaitingNext && it.position == position && it.duration == duration && it.subtitle == subtitle } == true) return
        write(PlaybackBookmark(movie, target, episodeName, artwork, position, duration, System.currentTimeMillis(), subtitle))
    }

    private fun write(bookmark: PlaybackBookmark) {
        preferences.edit().putString(bookmark.target.key, encode(bookmark).toString()).apply()
    }

    private fun encode(bookmark: PlaybackBookmark): JSONObject = with(bookmark) {
        JSONObject().put("siteId", target.siteId).put("episodeId", target.episodeId).put("id", movie.id).put("type", target.type.name)
            .put("sectionId", movie.sectionId).put("title", movie.title).put("poster", movie.poster).put("backdrop", movie.backdrop)
            .put("rating", movie.rating).put("overview", movie.overview).put("releaseDate", movie.releaseDate)
            .put("season", target.season).put("episode", target.episode).put("episodeName", episodeName)
            .put("artwork", artwork).put("position", position).put("duration", duration)
            .put("updatedAt", updatedAt).put("subtitle", subtitle?.toJson())
            .put("queued", queued).put("awaitingNext", awaitingNext).put("queuedFrom", queuedFrom)
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
        val queued = data.optBoolean("queued")
        val awaitingNext = data.optBoolean("awaitingNext")
        require(target.key == key && when {
            queued -> !awaitingNext && position == 0L && duration == 0L
            awaitingNext -> type == MediaType.TV && position >= 0 && duration >= 0
            else -> position > 30_000 && duration > 0 && position <= duration * 0.95
        })
        PlaybackBookmark(movie, target, data.optString("episodeName").takeIf { it.isNotBlank() },
            data.optString("artwork"), position, duration, data.getLong("updatedAt"),
            data.optJSONObject("subtitle")?.let(::readSubtitle), queued, awaitingNext,
            data.optString("queuedFrom").takeIf { it.isNotBlank() })
    }.getOrNull()

    private fun SubtitleSelection.toJson(): JSONObject = JSONObject().apply {
        when (val selection = this@toJson) {
            SubtitleSelection.Off -> put("kind", "off")
            is SubtitleSelection.Language -> put("kind", "language").put("language", selection.code)
            is SubtitleSelection.Embedded -> put("kind", "embedded").put("language", selection.language)
                .put("id", selection.id).put("label", selection.label).put("roles", selection.roleFlags)
            is SubtitleSelection.Online -> with(selection.subtitle) {
                put("kind", "online").put("id", id).put("language", language.name)
                    .put("release", release).put("url", downloadUrl).put("encoding", encoding).put("downloads", downloads)
            }
        }
    }

    // Older or malformed subtitle data must not discard an otherwise valid resume position.
    private fun readSubtitle(data: JSONObject): SubtitleSelection? = runCatching {
        fun optional(key: String) = data.optString(key).takeIf { it.isNotBlank() }
        when (data.getString("kind")) {
            "off" -> SubtitleSelection.Off
            "language" -> SubtitleSelection.Language(data.getString("language"))
            "embedded" -> SubtitleSelection.Embedded(optional("language"), optional("id"), optional("label"), data.optInt("roles"))
            "online" -> {
                val id = data.getString("id")
                require(id.matches(Regex("[0-9]+")))
                SubtitleSelection.Online(OnlineSubtitle(id, SubtitleLanguage.valueOf(data.getString("language")),
                    data.getString("release"), data.getString("url"), data.getString("encoding"), data.optLong("downloads")))
            }
            else -> null
        }
    }.getOrNull()
}
