package fr.bonamy.movies

import android.content.SharedPreferences
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
)

/** Local progress follows MediaStation's 30-second start and 95% completion rules. */
internal class PlaybackProgress(private val preferences: SharedPreferences) {
    init { LegacyVidboxMigration.progress(preferences) }

    fun position(target: PlayableRef): Long = read(target.key)?.position ?: 0

    fun subtitle(target: PlayableRef): SubtitleSelection? = read(target.key)?.subtitle

    fun list(): List<PlaybackBookmark> = preferences.all.keys.mapNotNull(::read).sortedByDescending { it.updatedAt }

    fun remove(target: PlayableRef) { preferences.edit().remove(target.key).apply() }

    fun save(movie: Title, target: PlayableRef, episodeName: String?, artwork: String,
        position: Long, duration: Long, ended: Boolean = false, subtitle: SubtitleSelection? = null) {
        require(movie.ref == target.title)
        // Unprepared/erroring streams must not erase a previously saved position.
        if (!ended && (position < 0 || duration <= 0)) return
        val resumable = !ended && position > 30_000 && position.toDouble() <= duration * 0.95
        if (!resumable) {
            preferences.edit().remove(target.key).apply()
            return
        }
        if (read(target.key)?.let { it.position == position && it.duration == duration && it.subtitle == subtitle } == true) return
        val data = JSONObject().put("siteId", target.siteId).put("episodeId", target.episodeId).put("id", movie.id).put("type", target.type.name)
            .put("sectionId", movie.sectionId).put("title", movie.title).put("poster", movie.poster).put("backdrop", movie.backdrop)
            .put("rating", movie.rating).put("overview", movie.overview).put("releaseDate", movie.releaseDate)
            .put("season", target.season).put("episode", target.episode).put("episodeName", episodeName)
            .put("artwork", artwork).put("position", position).put("duration", duration)
            .put("updatedAt", System.currentTimeMillis())
            .put("subtitle", subtitle?.toJson())
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
            data.optString("artwork"), position, duration, data.getLong("updatedAt"),
            data.optJSONObject("subtitle")?.let(::readSubtitle))
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
