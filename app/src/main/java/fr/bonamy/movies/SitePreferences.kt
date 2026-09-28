package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.MediaType
import fr.bonamy.movies.core.SiteDescriptor
import org.json.JSONArray

internal class SitePreferences(private val preferences: SharedPreferences) {
    init { LegacyVidboxMigration.preferences(preferences) }
    var activeSiteId: String?
        get() = preferences.getString("activeSite", null)
        set(value) { preferences.edit().putString("activeSite", value).apply() }
    fun mode(site: SiteDescriptor): MediaType = site.mediaTypes.firstOrNull {
        it.name == preferences.getString(key("mode", site.id), null)
    } ?: site.mediaTypes.first()
    fun selectMode(site: SiteDescriptor, type: MediaType) {
        require(type in site.mediaTypes)
        preferences.edit().putString(key("mode", site.id), type.name).apply()
    }
    fun source(siteId: String): String? = preferences.getString(key("source", siteId), null)
    fun selectSource(siteId: String, source: String) {
        preferences.edit().putString(key("source", siteId), source).apply()
    }
    companion object {
        fun key(kind: String, siteId: String): String = JSONArray(listOf("v2", kind, siteId)).toString()
    }
}
