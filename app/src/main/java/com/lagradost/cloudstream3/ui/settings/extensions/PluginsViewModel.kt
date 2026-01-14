package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import me.xdrop.fuzzywuzzy.FuzzySearch
import java.io.File

// String => repository url
typealias Plugin = Pair<String, SitePlugin>
/**
 * The boolean signifies if the plugin list should be scrolled to the top, used for searching.
 * */
typealias PluginViewDataUpdate = Pair<Boolean, List<PluginViewData>>

class PluginsViewModel : ViewModel() {

    /** plugins is an unaltered list of plugins */
    private var plugins: List<PluginViewData> = emptyList()
        set(value) {
            // Also set all the plugin languages for easier filtering
            value.map { pluginViewData ->
                val language = pluginViewData.plugin.second.language?.lowercase()
                pluginLanguages.add(
                    when {
                        language.isNullOrBlank() -> "none"
                        else -> language.lowercase()
                    }
                )
                // not sorting as most likely this is a language tag instead of name
            }
            field = value
        }
    var pluginLanguages = mutableSetOf<String>() // set to avoid duplicates

    /** filteredPlugins is a subset of plugins following the current search query and tv type selection */
    private var _filteredPlugins = MutableLiveData<PluginViewDataUpdate>()
    var filteredPlugins: LiveData<PluginViewDataUpdate> = _filteredPlugins

    val tvTypes = mutableListOf<String>()
    var selectedLanguages = listOf<String>()
    private var currentQuery: String? = null

    companion object {
        private val repositoryCache: MutableMap<String, List<Plugin>> = mutableMapOf()
        const val TAG = "PLG"

        private fun isDownloaded(
            context: Context,
            pluginName: String,
            repositoryUrl: String
        ): Boolean {
            return getPluginPath(context, pluginName, repositoryUrl).exists()
        }

        private suspend fun getPlugins(
            repositoryUrl: String,
            canUseCache: Boolean = true
        ): List<Plugin> {
            Log.i(TAG, "getPlugins = $repositoryUrl")
            if (canUseCache && repositoryCache.containsKey(repositoryUrl)) {
                repositoryCache[repositoryUrl]?.let {
                    return it
                }
            }
            return RepositoryManager.getRepoPlugins(repositoryUrl)
                ?.also { repositoryCache[repositoryUrl] = it } ?: emptyList()
        }

        /**
         * @param viewModel optional, updates the plugins livedata for that viewModel if included
         * */
        fun downloadAll(activity: Activity?, repositoryUrl: String, viewModel: PluginsViewModel?) =
            ioSafe {
                if (activity == null) return@ioSafe

                // --- UPDATE FIX: ANTI-CACHE (FORCE RELOAD) ---
                // Kita tambahkan "?t=waktu" di belakang URL.
                // Ini menipu GitHub agar memberikan file JSON terbaru DETIK INI JUGA.
                // Tanpa ini, GitHub akan memberikan file lama (cache) selama 5-10 menit.
                val antiCacheUrl = "$repositoryUrl?t=${System.currentTimeMillis()}"
                
                // Gunakan URL anti-cache untuk mengambil daftar
                val plugins = getPlugins(antiCacheUrl, false)

                // SAFETY CHECK: Jika internet mati atau repo kosong, STOP. Jangan hapus apa-apa.
                if (plugins.isEmpty()) return@ioSafe

                // --- BAGIAN A: DOWNLOAD PLUGIN BARU ---
                plugins.filter { plugin ->
                    !isDownloaded(
                        activity,
                        plugin.second.internalName,
                        repositoryUrl // Cek folder pakai URL asli agar path sesuai
                    )
                }.also { list ->
                    // Silent Mode: Toast dimatikan
                }.amap { (repo, metadata) ->
                    PluginManager.downloadPlugin(
                        activity,
                        metadata.url,
                        metadata.internalName,
                        repositoryUrl, // Download pakai URL asli agar rapi
                        metadata.status != PROVIDER_STATUS_DOWN
                    )
                }.main { list ->
                    if (list.any { it }) {
                         viewModel?.updatePluginListPrivate(activity, repositoryUrl)
                    }
                }

                // --- BAGIAN B: AUTO-DELETE PLUGIN YANG HILANG DARI REPO ---
                try {
                    // 1. Ambil daftar nama plugin dari GitHub (yang sudah Fresh karena Anti-Cache)
                    val onlineInternalNames = plugins.map { it.second.internalName }.toSet()
                    
                    // 2. Ambil plugin di HP
                    val localPlugins = PluginManager.getPluginsLocal()
                    
                    // 3. Cari plugin di HP yang namanya TIDAK ADA di GitHub
                    val toDelete = localPlugins.filter { local ->
                        // Jika tidak ada di daftar online, berarti harus dihapus
                        !onlineInternalNames.contains(local.internalName)
                    }
                    
                    // 4. Eksekusi Hapus
                    toDelete.forEach { local ->
                         val file = File(local.filePath)
                         if (file.exists()) {
                             PluginManager.deletePlugin(file)
                             Log.i(TAG, "Auto-Delete: ${local.internalName} dihapus. Bye-bye!")
                         }
                    }
                    
                    // 5. Refresh UI
                    if (toDelete.isNotEmpty()) {
                        main {
                             viewModel?.updatePluginListLocal()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error Auto-Delete: ${e.message}")
                }
            }
    }

    /**
     * @param isLocal defines if the plugin data is from local data instead of repo
     * Will only allow removal of plugins. Used for the local file management.
     * */
    fun handlePluginAction(
        activity: Activity?,
        repositoryUrl: String,
        plugin: Plugin,
        isLocal: Boolean
    ) = ioSafe {
        Log.i(TAG, "handlePluginAction = $repositoryUrl, $plugin, $isLocal")

        if (activity == null) return@ioSafe
        val (repo, metadata) = plugin

        val file = if (isLocal) File(plugin.second.url) else getPluginPath(
            activity,
            plugin.second.internalName,
            plugin.first
        )

        val (success, message) = if (file.exists()) {
            PluginManager.deletePlugin(file) to R.string.plugin_deleted
        } else {
            val isEnabled = plugin.second.status != PROVIDER_STATUS_DOWN
            val message = if (isEnabled) R.string.plugin_loaded else R.string.plugin_downloaded
            PluginManager.downloadPlugin(
                activity,
                metadata.url,
                metadata.internalName,
                repo,
                isEnabled
            ) to message
        }

        runOnMainThread {
            if (success)
                showToast(message, Toast.LENGTH_SHORT)
            else
                showToast(R.string.error, Toast.LENGTH_SHORT)
        }

        if (success)
            if (isLocal)
                updatePluginListLocal()
            else
                updatePluginListPrivate(activity, repositoryUrl)
    }

    private suspend fun updatePluginListPrivate(context: Context, repositoryUrl: String) {
        val isAdult = PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(context.getString(R.string.prefer_media_type_key), emptySet())
            ?.contains(TvType.NSFW.ordinal.toString()) == true

        val plugins = getPlugins(repositoryUrl)
        val list = plugins.filter {
            // Show all non-nsfw plugins or all if nsfw is enabled
            it.second.tvTypes?.contains(TvType.NSFW.name) != true || isAdult
        }.map { plugin ->
            PluginViewData(plugin, isDownloaded(context, plugin.second.internalName, plugin.first))
        }

        this.plugins = list
        _filteredPlugins.postValue(
            false to list.filterTvTypes().filterLang().sortByQuery(currentQuery)
        )
    }

    // Perhaps can be optimized?
    private fun List<PluginViewData>.filterTvTypes(): List<PluginViewData> {
        if (tvTypes.isEmpty()) return this
        return this.filter {
            (it.plugin.second.tvTypes?.any { type -> tvTypes.contains(type) } == true) ||
                    (tvTypes.contains(TvType.Others.name) && (it.plugin.second.tvTypes
                        ?: emptyList()).isEmpty())
        }
    }

    private fun List<PluginViewData>.filterLang(): List<PluginViewData> {
        if (selectedLanguages.isEmpty()) return this // do not filter
        return this.filter {
            if (it.plugin.second.language == null) {
                return@filter selectedLanguages.contains("none")
            }
            selectedLanguages.contains(it.plugin.second.language?.lowercase())
        }
    }

    private fun List<PluginViewData>.sortByQuery(query: String?): List<PluginViewData> {
        return if (query == null) {
            // Return list to base state if no query
            this.sortedBy { it.plugin.second.name }
        } else {
            this.sortedBy {
                -FuzzySearch.partialRatio(
                    it.plugin.second.name.lowercase(),
                    query.lowercase()
                )
            }
        }
    }

    fun updateFilteredPlugins() {
        _filteredPlugins.postValue(
            false to plugins.filterTvTypes().filterLang().sortByQuery(currentQuery)
        )
    }

    fun clear() {
        currentQuery = null
        _filteredPlugins.postValue(
            false to emptyList()
        )
    }

    fun updatePluginList(context: Context?, repositoryUrl: String) = viewModelScope.launchSafe {
        if (context == null) return@launchSafe
        Log.i(TAG, "updatePluginList = $repositoryUrl")
        updatePluginListPrivate(context, repositoryUrl)
    }

    fun search(query: String?) {
        currentQuery = query
        _filteredPlugins.postValue(
            true to (filteredPlugins.value?.second?.sortByQuery(query) ?: emptyList())
        )
    }

    /**
     * Update the list but only with the local data. Used for file management.
     * */
    fun updatePluginListLocal() = viewModelScope.launchSafe {
        Log.i(TAG, "updatePluginList = local")

        val downloadedPlugins = (PluginManager.getPluginsOnline() + PluginManager.getPluginsLocal())
            .distinctBy { it.filePath }
            .map {
                PluginViewData("" to it.toSitePlugin(), true)
            }

        plugins = downloadedPlugins
        _filteredPlugins.postValue(
            false to downloadedPlugins.filterTvTypes().filterLang().sortByQuery(currentQuery)
        )
    }
}
