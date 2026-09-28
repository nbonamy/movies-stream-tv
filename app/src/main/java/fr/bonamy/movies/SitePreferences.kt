package fr.bonamy.movies

import android.content.SharedPreferences
import fr.bonamy.movies.core.SiteSection
import fr.bonamy.movies.core.SiteDescriptor
import org.json.JSONArray

internal class SitePreferences(private val preferences: SharedPreferences) {
    init { LegacyVidboxMigration.preferences(preferences) }
    var activeSiteId: String?
        get() = preferences.getString("activeSite", null)
        set(value) { preferences.edit().putString("activeSite", value).apply() }
    fun section(site: SiteDescriptor): SiteSection {
        val saved = preferences.getString(key("section", site.id), null)
        // Preserve the previous Vidbox movie/TV selection on upgrade.
        val legacy = if (site.id == "vidbox") preferences.getString(key("mode", site.id), null)?.lowercase() else null
        return site.sections.firstOrNull { it.id == (saved ?: legacy) } ?: site.sections.first()
    }
    fun selectSection(site: SiteDescriptor, section: SiteSection) {
        require(section in site.sections)
        preferences.edit().putString(key("section", site.id), section.id).apply()
    }
    fun source(siteId: String): String? = preferences.getString(key("source", siteId), null)
    fun selectSource(siteId: String, source: String) {
        preferences.edit().putString(key("source", siteId), source).apply()
    }
    companion object {
        fun key(kind: String, siteId: String): String = JSONArray(listOf("v2", kind, siteId)).toString()
    }
}
