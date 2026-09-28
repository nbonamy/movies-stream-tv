package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.PlayableRef
import fr.bonamy.movies.core.TitleRef
import fr.bonamy.movies.core.sites.vidbox.VidboxSite
import org.json.JSONObject

/** The only Android persistence code aware of the pre-multi-site provider. */
internal object LegacyVidboxMigration {
    fun progress(preferences: SharedPreferences) {
        if (preferences.getInt("schemaVersion", 1) >= 2) return
        val edit = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (!key.startsWith("movie/") && !key.startsWith("tv/")) return@forEach
            runCatching {
                val data = JSONObject(value as String)
                val type = MediaType.valueOf(data.getString("type"))
                val season = if (type == MediaType.TV) data.getInt("season") else null
                val episode = if (type == MediaType.TV) data.getInt("episode") else null
                val id = data.getString("id")
                require(key == "${type.apiValue}/$id" + if (type == MediaType.TV) "/$season/$episode" else "")
                val target = PlayableRef(TitleRef(VidboxSite.ID, id, type),
                    if (type == MediaType.TV) "$season/$episode" else null, season, episode)
                data.put("siteId", VidboxSite.ID).put("episodeId", target.episodeId)
                if (!preferences.contains(target.key)) edit.putString(target.key, data.toString())
                edit.remove(key)
            }
        }
        edit.putInt("schemaVersion", 2).apply()
    }
    fun preferences(preferences: SharedPreferences) {
        val legacy = preferences.getString("currentProviderId", null) ?: return
        val key = SitePreferences.key("source", VidboxSite.ID)
        val edit = preferences.edit()
        if (!preferences.contains(key)) edit.putString(key, legacy)
        edit.remove("currentProviderId").apply()
    }
}
