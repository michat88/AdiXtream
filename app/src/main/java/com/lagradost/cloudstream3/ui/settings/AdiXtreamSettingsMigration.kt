package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality

/** Keep old keys for rollback/backup; never touch license or account stores. */
object AdiXtreamSettingsMigration {
    fun migrate(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        if (!prefs.contains("dns_key")) editor.putInt("dns_key", prefs.getInt(context.getString(R.string.dns_pref), 1))
        if (!prefs.contains("pref_filter_search_quality_key2") && prefs.contains("pref_filter_search_quality_key")) {
            editor.putStringSet("pref_filter_search_quality_key2",
                prefs.getStringSet("pref_filter_search_quality_key", emptySet()).orEmpty().mapNotNull {
                    it.toIntOrNull()?.let { n -> SearchQuality.entries.getOrNull(n)?.name }
                }.toSet())
        }
        editor.apply()
    }
}
