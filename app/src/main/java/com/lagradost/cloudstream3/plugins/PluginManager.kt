package com.lagradost.cloudstream3.plugins

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.removePluginMapping
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainAPI.Companion.settingsForProvider
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.PROVIDER_STATUS_OK
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.PremiumManager
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.RepositoryManager.ONLINE_PLUGINS_FOLDER
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.plugins.RepositoryManager.downloadPluginToTemp
import com.lagradost.cloudstream3.plugins.RepositoryManager.getRepoPlugins
import com.lagradost.cloudstream3.plugins.RepositoryManager.sha256
import com.lagradost.cloudstream3.ui.settings.extensions.REPOSITORIES_KEY
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiProviderLangSettings
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.sanitizeFilename
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.txt
import dalvik.system.PathClassLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Different keys for local and not since local can be removed at any time without app knowing, hence the local are getting rebuilt on every app start
const val PLUGINS_KEY = "PLUGINS_KEY"
const val PLUGINS_KEY_LOCAL = "PLUGINS_KEY_LOCAL"

const val EXTENSIONS_CHANNEL_ID = "cloudstream3.extensions"
const val EXTENSIONS_CHANNEL_NAME = "Extensions"
const val EXTENSIONS_CHANNEL_DESCRIPT = "Extension notification channel"

// Data class for internal storage
@Serializable
data class PluginData(
    @JsonProperty("internalName") @SerialName("internalName") val internalName: String,
    @JsonProperty("url") @SerialName("url") val url: String?,
    @JsonProperty("isOnline") @SerialName("isOnline") val isOnline: Boolean,
    @JsonProperty("filePath") @SerialName("filePath") val filePath: String,
    @JsonProperty("version") @SerialName("version") val version: Int,
) {
    @WorkerThread
    fun toSitePlugin(): SitePlugin {
        return SitePlugin(
            this.filePath,
            PROVIDER_STATUS_OK,
            maxOf(1, version),
            1,
            internalName,
            internalName,
            emptyList(),
            File(this.filePath).name,
            null,
            null,
            null,
            null,
            File(this.filePath).length(),
            // No file hash for local plugins. Local plugins have no use for the hash, and it's expensive to compute.
            null
        )
    }
}

// This is used as a placeholder / not set version
const val PLUGIN_VERSION_NOT_SET = Int.MIN_VALUE

// This always updates
const val PLUGIN_VERSION_ALWAYS_UPDATE = -1

object PluginManager {
    // Prevent multiple writes at once
    val lock = Mutex()

    const val TAG = "PluginManager"
    private val lifecycleGuard = Any()
    private val installLocks = ConcurrentHashMap<String, Mutex>()
    private val loadLocks = ConcurrentHashMap<String, Mutex>()
    private val lifecycleVersions = ConcurrentHashMap<String, AtomicLong>()
    private val unloading = mutableSetOf<String>()

    private var hasCreatedNotChanel = false

    /**
     * Store data about the plugin for fetching later
     * */
    private suspend fun setPluginData(data: PluginData) {
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline()
                val newPlugins = plugins.filter { it.filePath != data.filePath } + data
                setKey(PLUGINS_KEY, newPlugins)
            } else {
                val plugins = getPluginsLocal()
                setKey(PLUGINS_KEY_LOCAL, plugins.filter { it.filePath != data.filePath } + data)
            }
        }
    }

    private suspend fun deletePluginData(data: PluginData?) {
        if (data == null) return
        lock.withLock {
            if (data.isOnline) {
                val plugins = getPluginsOnline().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY, plugins)
            } else {
                val plugins = getPluginsLocal().filter { it.filePath != data.filePath }
                setKey(PLUGINS_KEY_LOCAL, plugins)
            }
        }
    }

    suspend fun deleteRepositoryData(repositoryPath: String) {
        lock.withLock {
            val plugins = getPluginsOnline().filter {
                !it.filePath.contains(repositoryPath)
            }
            val file = File(repositoryPath)
            try {
                if (file.exists()) file.deleteRecursively()
            } catch (_: Exception) {
                Log.w(TAG, "Unable to clear replaced extension cache")
            }
            setKey(PLUGINS_KEY, plugins)
        }
    }

    /**
     * Deletes all generated oat files which will force Android to recompile the dex extensions.
     * This might fix unrecoverable SIGSEGV exceptions when old oat files are loaded in a new app update.
     */
    fun deleteAllOatFiles(context: Context) {
        File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}").listFiles()?.forEach { repo ->
            repo.listFiles { file -> file.name == "oat" && file.isDirectory }?.forEach { file ->
                val success = file.deleteRecursively()
                Log.i(TAG, "Cleared extension optimization cache: $success")
            }
        }
    }


    fun getPluginsOnline(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY) ?: emptyArray()
    }

    fun getPluginsLocal(): Array<PluginData> {
        return getKey<Array<PluginData>>(PLUGINS_KEY_LOCAL) ?: emptyArray()
    }

    private val CLOUD_STREAM_FOLDER =
        Environment.getExternalStorageDirectory().absolutePath + "/Cloudstream3/"

    private val LOCAL_PLUGINS_PATH = CLOUD_STREAM_FOLDER + "plugins"

    var currentlyLoading: String? = null

    // Maps filepath to plugin
    val plugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    // Maps urls to plugin
    val urlPlugins: MutableMap<String, BasePlugin> =
        LinkedHashMap<String, BasePlugin>()

    private val classLoaders: MutableMap<PathClassLoader, BasePlugin> =
        HashMap<PathClassLoader, BasePlugin>()

    var loadedLocalPlugins = false
        private set

    var loadedOnlinePlugins = false
        private set

    private suspend fun maybeLoadPlugin(context: Context, file: File) {
        val name = file.name
        if (file.extension == "zip" || file.extension == "cs3") {
            loadPlugin(
                context,
                file,
                PluginData(name, null, false, file.absolutePath, PLUGIN_VERSION_NOT_SET)
            )
        } else {
            Log.i(TAG, "Skipping invalid extension file")
        }
    }


    // Helper class for updateAllOnlinePluginsAndLoadThem
    data class OnlinePluginData(
        val savedData: PluginData,
        val onlineData: PluginWrapper,
    ) {
        val isOutdated =
            onlineData.plugin.version > savedData.version || onlineData.plugin.version == PLUGIN_VERSION_ALWAYS_UPDATE
        val isDisabled = onlineData.plugin.status == PROVIDER_STATUS_DOWN

        fun validOnlineData(context: Context): Boolean {
            return getPluginPath(
                context,
                savedData.internalName,
                onlineData.repositoryData.url
            ).absolutePath == savedData.filePath
        }
    }

    // var allCurrentOutDatedPlugins: Set<OnlinePluginData> = emptySet()

    suspend fun loadSinglePlugin(context: Context, apiName: String): Boolean {
        return (getPluginsOnline().firstOrNull {
            // Most of the time the provider ends with Provider which isn't part of the api name
            it.internalName.replace("provider", "", ignoreCase = true) == apiName
        }
            ?: getPluginsLocal().firstOrNull {
                it.internalName.replace("provider", "", ignoreCase = true) == apiName
            })?.let { savedData ->
            // OnlinePluginData(savedData, onlineData)
            loadPlugin(
                context,
                File(savedData.filePath),
                savedData
            )
        } ?: false
    }

    /**
     * Needs to be run before other plugin loading because plugin loading can not be overwritten
     * 1. Gets all online data about the downloaded plugins
     * 2. If disabled do nothing
     * 3. If outdated download and load the plugin
     * 4. Else load the plugin normally
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(activity: Activity) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES

        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        // Iterates over all offline plugins, compares to remote repo and returns the plugins which are outdated
        val outdatedPlugins = getPluginsOnline().map { savedData ->
            onlinePlugins
                .filter { onlineData -> savedData.internalName == onlineData.plugin.internalName }
                .map { onlineData ->
                    OnlinePluginData(savedData, onlineData)
                }.filter {
                    it.validOnlineData(activity)
                }
        }.flatten().distinctBy { it.onlineData.plugin.url }

        Log.d(TAG, "Outdated extension count: ${outdatedPlugins.count { it.isOutdated }}")

        val updatedPlugins = mutableListOf<String>()

        outdatedPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                //updatedPlugins.add(activity.getString(R.string.single_plugin_disabled, pluginData.onlineData.second.name))
                unloadPlugin(pluginData.savedData.filePath)
            } else if (pluginData.isOutdated) {
                downloadPlugin(
                    activity,
                    pluginData.onlineData.plugin.url,
                    pluginData.onlineData.plugin.fileHash,
                    pluginData.savedData.internalName,
                    File(pluginData.savedData.filePath),
                    true
                ).let { success ->
                    if (success)
                        updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }

        main {
            val uitext = txt(R.string.plugins_updated, updatedPlugins.size)
            createNotification(activity, uitext, updatedPlugins)
            /*val navBadge = (activity as MainActivity).binding?.navRailView?.getOrCreateBadge(R.id.navigation_settings)
            navBadge?.isVisible = true
            navBadge?.number = 5*/
        }

        // ioSafe {
        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin update done!")
    }

    /**
     * Automatically download plugins not yet existing on local
     * 1. Gets all online data from online plugins repo
     * 2. Fetch all not downloaded plugins
     * 3. Download them and reload plugins
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
        activity: Activity,
        mode: AutoDownloadMode
    ) {
        assertNonRecursiveCallstack()

        val newDownloadPlugins = mutableListOf<String>()
        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it)?.toList() ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val providerLang = activity.getApiProviderLangSettings()
        //Log.i(TAG, "providerLang => ${providerLang.toJson()}")

        // Iterate online repos and returns not downloaded plugins
        val notDownloadedPlugins = onlinePlugins.mapNotNull { onlineData ->
            val sitePlugin = onlineData.plugin
            val tvtypes = sitePlugin.tvTypes ?: listOf()

            //Don't include empty urls
            if (sitePlugin.url.isBlank()) {
                return@mapNotNull null
            }
            if (sitePlugin.repositoryUrl.isNullOrBlank()) {
                return@mapNotNull null
            }

            //Omit already existing plugins
            if (getPluginPath(activity, sitePlugin.internalName, onlineData.repositoryData.url).exists()) {
                Log.i(TAG, "Skip > ${sitePlugin.internalName}")
                return@mapNotNull null
            }

            //Omit non-NSFW if mode is set to NSFW only
            if (mode == AutoDownloadMode.NsfwOnly) {
                if (!tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }
            //Omit NSFW, if disabled
            if (!settingsForProvider.enableAdult) {
                if (tvtypes.contains(TvType.NSFW.name)) {
                    return@mapNotNull null
                }
            }

            //Omit lang not selected on language setting
            if (mode == AutoDownloadMode.FilterByLang) {
                val lang = sitePlugin.language ?: return@mapNotNull null
                //If set to 'universal', don't skip any language
                if (!providerLang.contains(AllLanguagesName) && !providerLang.contains(lang)) {
                    return@mapNotNull null
                }
                //Log.i(TAG, "sitePlugin lang => $lang")
            }

            val savedData = PluginData(
                url = sitePlugin.url,
                internalName = sitePlugin.internalName,
                isOnline = true,
                filePath = "",
                version = sitePlugin.version
            )
            OnlinePluginData(savedData, onlineData)
        }
        //Log.i(TAG, "notDownloadedPlugins => ${notDownloadedPlugins.toJson()}")

        notDownloadedPlugins.amap { pluginData ->
            downloadPlugin(
                activity,
                pluginData.onlineData.plugin.url,
                pluginData.onlineData.plugin.fileHash,
                pluginData.savedData.internalName,
                pluginData.onlineData.repositoryData.url,
                !pluginData.isDisabled
            ).let { success ->
                if (success)
                    newDownloadPlugins.add(pluginData.onlineData.plugin.name)
            }
        }

        main {
            val uitext = txt(R.string.plugins_downloaded, newDownloadPlugins.size)
            createNotification(activity, uitext, newDownloadPlugins)
        }

        // ioSafe {
        afterPluginsLoadedEvent.invoke(false)
        // }

        Log.i(TAG, "Plugin download done!")
    }

    @Throws
    private fun assertNonRecursiveCallstack() {
        if (Thread.currentThread().stackTrace.any { it.methodName == "loadPlugin" }) {
            throw Error("You tried to call a function that will recursively call loadPlugin, this will cause crashes or memory leaks. Do not do this, there is better ways to implement the feature than reloading plugins. Are you sure you read the compile error or docs?")
        }
    }

    /**
     * Use updateAllOnlinePluginsAndLoadThem
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context: Context) {
        assertNonRecursiveCallstack()

        // Load all plugins as fast as possible!
        (getPluginsOnline()).toList().amap { pluginData ->
            loadPlugin(
                context,
                File(pluginData.filePath),
                pluginData
            )
        }
    }

    /**
     * Reloads all local plugins and forces a page update, used for hot reloading with deployWithAdb
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(activity: FragmentActivity?) {
        assertNonRecursiveCallstack()

        Log.d(TAG, "Reloading all local plugins!")
        if (activity == null) return
        getPluginsLocal().forEach {
            unloadPlugin(it.filePath)
        }
        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(activity, true)
    }

    /**
     * @param forceReload see afterPluginsLoadedEvent, basically a way to load all local plugins
     * and reload all pages even if they are previously valid
     *
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(context: Context, forceReload: Boolean) {
        assertNonRecursiveCallstack()

        val dir = File(LOCAL_PLUGINS_PATH)

        if (!dir.exists()) {
            val res = dir.mkdirs()
            if (!res) {
                Log.w(TAG, "Failed to create local directories")
                // We have tried to load local plugins, but exit early.
                // This needs to be true to prevent the downloader waiting for plugins.
                loadedLocalPlugins = true
                return
            }
        }

        val sortedPlugins = dir.listFiles()
        // Always sort plugins alphabetically for reproducible results

        Log.d(TAG, "Files in '${LOCAL_PLUGINS_PATH}' folder: ${sortedPlugins?.size}")

        // Use app-specific external files directory and copy the file there.
        // We have to do this because on Android 14+, it otherwise gives SecurityException
        // due to dex files and setReadOnly seems to have no effect unless it it here.
        val pluginDirectory = File(context.getExternalFilesDir(null), "plugins")
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs() // Ensure the plugins directory exists
        }

        // Make sure all local plugins are fully refreshed.
        removeKey(PLUGINS_KEY_LOCAL)

        sortedPlugins?.sortedBy { it.name }?.amap { file ->
            try {
                val destinationFile = File(pluginDirectory, file.name)

                // Only copy the file if the destination file doesn't exist or if it
                // has been modified (check file length and modification time).
                if (!destinationFile.exists() ||
                    destinationFile.length() != file.length() ||
                    destinationFile.lastModified() != file.lastModified()
                ) {

                    // Copy the file to the app-specific plugin directory
                    file.copyTo(destinationFile, overwrite = true)

                    // After copying, set the destination file's modification time
                    // to match the source file. We do this for performance so that we
                    // can check the modification time and not make redundant writes.
                    destinationFile.setLastModified(file.lastModified())
                }

                // Load the plugin after it has been copied
                maybeLoadPlugin(context, destinationFile)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to copy the file")
                logError(t)
            }
        }

        loadedLocalPlugins = true
        afterPluginsLoadedEvent.invoke(forceReload)
    }

    /** @return true if safe mode is enabled in any possible way. */
    fun isSafeMode(): Boolean {
        return checkSafeModeFile() || lastError != null
    }

    /**
     * This can be used to override any extension loading to fix crashes!
     * @return true if safe mode file is present
     **/
    fun checkSafeModeFile(): Boolean {
        return safe {
            val folder = File(CLOUD_STREAM_FOLDER)
            if (!folder.exists()) return@safe false
            val files = folder.listFiles { _, name ->
                name.equals("safe", ignoreCase = true)
            }
            files?.any()
        } ?: false
    }

    /**
     * @return True if successful, false if not
     * */
    internal fun isPluginAccessAllowed(context: Context, path: String?): Boolean {
        val premiumUrl = PremiumManager.PREMIUM_REPO_URL
        val premiumDirectory = if (premiumUrl.isBlank()) null else getPluginPath(context, "", premiumUrl).parentFile
        return allowsPluginPath(path, premiumDirectory) { PremiumManager.isPremium(context) }
    }

    internal fun unloadUnauthorizedPlugins(context: Context) {
        val paths = synchronized(plugins) { plugins.keys.toList() }
        paths.filterNot { isPluginAccessAllowed(context, it) }.forEach(::unloadPlugin)
    }

    internal suspend fun loadPlugin(context: Context, file: File, data: PluginData): Boolean =
        loadLocks.getOrPut(file.absolutePath) { Mutex() }.withLock { loadPluginLocked(context, file, data) }

    private suspend fun loadPluginLocked(context: Context, file: File, data: PluginData): Boolean {
        currentCoroutineContext().ensureActive()
        val filePath = file.absolutePath
        val revision = lifecycleVersions.getOrPut(filePath) { AtomicLong() }
        val version = revision.get()
        val allowed = {
            isPluginAccessAllowed(context, filePath) && revision.get() == version &&
                synchronized(lifecycleGuard) { filePath !in unloading }
        }
        return try {
            val initializedData = run {
                if (!isPluginAccessAllowed(context, filePath)) {
                    unloadPlugin(filePath)
                    return false
                }
                if (!allowed()) return false
                if (synchronized(plugins) { plugins.containsKey(filePath) }) return true
                currentlyLoading = file.nameWithoutExtension
                try {
                    check(file.isFile && file.setReadOnly()) { "Plugin file unavailable" }
                    val loader = PathClassLoader(filePath, context.classLoader)
                    val manifest = loader.getResourceAsStream("manifest.json").use { stream ->
                        checkNotNull(stream) { "Plugin manifest missing" }
                        InputStreamReader(stream).use { parseJson<BasePlugin.Manifest>(it.readText()) }
                    }
                    val instance = loader.loadClass(manifest.pluginClassName)
                        .getDeclaredConstructor().newInstance() as BasePlugin
                    instance.filename = filePath
                    if (manifest.requiresResources) {
                        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
                        AssetManager::class.java.getMethod("addAssetPath", String::class.java)
                            .invoke(assets, filePath)
                        @Suppress("DEPRECATION")
                        (instance as? Plugin)?.resources = Resources(
                            assets, context.resources.displayMetrics, context.resources.configuration
                        )
                    }
                    val initialized = initializePluginTransaction(
                        allowed = allowed,
                        initialize = { if (instance is Plugin) instance.load(context) else instance.load() },
                        publish = {
                            synchronized(lifecycleGuard) {
                                check(allowed()) { "Extension access changed during initialization" }
                                synchronized(plugins) { plugins[filePath] = instance }
                                synchronized(classLoaders) { classLoaders[loader] = instance }
                                synchronized(urlPlugins) { urlPlugins[data.url ?: filePath] = instance }
                            }
                        },
                        rollback = {
                            try { instance.beforeUnload() } catch (_: Throwable) {
                                Log.w(TAG, "Plugin cleanup failed")
                            } finally { removePluginRegistrations(filePath, instance) }
                        },
                    )
                    if (!initialized) return false
                    data.copy(version = manifest.version ?: PLUGIN_VERSION_NOT_SET)
                } finally { currentlyLoading = null }
            }
            // A failed initializer never advances the stored plugin version.
            setPluginData(initializedData)
            currentCoroutineContext().ensureActive()
            if (!allowed()) {
                unloadPlugin(filePath)
                false
            } else true
        } catch (cancelled: CancellationException) {
            unloadPlugin(filePath)
            throw cancelled
        } catch (_: Throwable) {
            unloadPlugin(filePath)
            Log.w(TAG, "Unable to initialize extension")
            showToast(context.getString(R.string.plugin_load_fail).format(data.internalName), Toast.LENGTH_LONG)
            false
        }
    }

    fun unloadPlugin(absolutePath: String) {
        val plugin = synchronized(lifecycleGuard) {
            if (!unloading.add(absolutePath)) return
            lifecycleVersions.getOrPut(absolutePath) { AtomicLong() }.incrementAndGet()
            synchronized(plugins) { plugins.remove(absolutePath) }
        }
        // Never hold lifecycleGuard while invoking extension code or taking provider-list locks.
        try { plugin?.beforeUnload() } catch (_: Throwable) {
            Log.w(TAG, "Plugin cleanup failed")
        } finally {
            try { removePluginRegistrations(absolutePath, plugin) }
            finally { synchronized(lifecycleGuard) { unloading.remove(absolutePath) } }
        }
    }

    private fun removePluginRegistrations(path: String, plugin: BasePlugin?) {
        APIHolder.apis.filter { it.sourcePlugin == path }.forEach(::removePluginMapping)
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == path } }
        extractorApis.withLock { extractorApis.removeAll { it.sourcePlugin == path } }
        VideoClickActionHolder.allVideoClickActions.withLock {
            VideoClickActionHolder.allVideoClickActions.removeAll { it.sourcePlugin == path }
        }
        if (plugin != null) {
            synchronized(classLoaders) { classLoaders.values.removeIf { it == plugin } }
            synchronized(urlPlugins) { urlPlugins.values.removeIf { it == plugin } }
        }
        synchronized(plugins) { plugins.remove(path) }
    }

    /** Shared by initial bootstrap, automatic updates and the active Compose extension screen. */
    internal suspend fun installPlugin(
        context: Context, staged: File, file: File, data: PluginData, load: Boolean,
    ): Boolean = installLocks.getOrPut(file.absolutePath) { Mutex() }.withLock {
        val previous = (getPluginsOnline() + getPluginsLocal()).firstOrNull { it.filePath == file.absolutePath }
        val wasLoaded = synchronized(lifecycleGuard) { plugins.containsKey(file.absolutePath) }
        replacePluginFile(
            staged = staged,
            destination = file,
            allowed = { isPluginAccessAllowed(context, file.absolutePath) },
            activate = {
                currentCoroutineContext().ensureActive()
                unloadPlugin(file.absolutePath)
                if (load) loadPlugin(context, file, data) else { setPluginData(data); true }
            },
            restore = {
                unloadPlugin(file.absolutePath)
                if (previous != null) {
                    setPluginData(previous)
                    if (wasLoaded && isPluginAccessAllowed(context, file.absolutePath)) loadPlugin(context, file, previous)
                } else deletePluginData(data)
            },
        )
    }

    /**
     * Spits out a unique and safe filename based on name.
     * Used for repo folders (using repo url) and plugin file names (using internalName)
     * */
    fun getPluginSanitizedFileName(name: String): String {
        return sanitizeFilename(
            name,
            true
        ) + "." + name.hashCode()
    }

    /**
     * This should not be changed as it is used to also detect if a plugin is installed!
     **/
    fun getPluginPath(
        context: Context,
        internalName: String,
        repositoryUrl: String
    ): File {
        val folderName = getPluginSanitizedFileName(repositoryUrl) // Guaranteed unique
        val fileName = getPluginSanitizedFileName(internalName)
        return File("${context.filesDir}/${ONLINE_PLUGINS_FOLDER}/${folderName}/$fileName.cs3")
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        repositoryUrl: String,
        loadPlugin: Boolean
    ): Boolean {
        val file = getPluginPath(activity, internalName, repositoryUrl)
        return downloadPlugin(activity, pluginUrl, pluginHash, internalName, file, loadPlugin)
    }

    suspend fun downloadPlugin(
        activity: Activity,
        pluginUrl: String,
        pluginHash: String?,
        internalName: String,
        file: File,
        loadPlugin: Boolean,
    ): Boolean {
        if (!isPluginAccessAllowed(activity, file.absolutePath)) return false
        val staged = downloadPluginToTemp(activity, pluginUrl, pluginHash) ?: return false
        return try {
            currentCoroutineContext().ensureActive()
            installPlugin(activity, staged, file, PluginData(
                internalName, pluginUrl, true, file.absolutePath, PLUGIN_VERSION_NOT_SET
            ), loadPlugin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(TAG, "Unable to install extension")
            false
        } finally { staged.delete() }
    }

    suspend fun deletePlugin(file: File): Boolean {
        val list =
            (getPluginsLocal() + getPluginsOnline()).filter { it.filePath == file.absolutePath }

        return try {
            if (File(file.absolutePath).delete()) {
                unloadPlugin(file.absolutePath)
                list.forEach { deletePluginData(it) }
                return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * DO NOT USE THIS IN A PLUGIN! It may case an infinite recursive loop lagging or crashing everyone's devices.
     * If you use it from a plugin, do not expect a stable jvmName, SO DO NOT USE IT!
     */
    @Suppress("FunctionName")
    @InternalAPI
    @Throws
    suspend fun ___DO_NOT_CALL_FROM_A_PLUGIN_manuallyReloadAndUpdatePlugins(activity: Activity) {
        assertNonRecursiveCallstack()

        showToast(activity.getString(R.string.starting_plugin_update_manually), Toast.LENGTH_LONG)

        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(activity)
        afterPluginsLoadedEvent.invoke(false)

        val urls = (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()) + PREBUILT_REPOSITORIES
        val onlinePlugins = urls.toList().amap {
            getRepoPlugins(it) ?: emptyList()
        }.flatten().distinctBy { it.plugin.url }

        val allPlugins = getPluginsOnline().flatMap { savedData ->
            onlinePlugins
                .filter { it.plugin.internalName == savedData.internalName }
                .mapNotNull { onlineData ->
                    OnlinePluginData(savedData, onlineData).takeIf { it.validOnlineData(activity) }
                }
        }.distinctBy { it.onlineData.plugin.url }

        val updatedPlugins = mutableListOf<String>()

        allPlugins.amap { pluginData ->
            if (pluginData.isDisabled) {
                Log.e(
                    "PluginManager",
                    "Unloading disabled plugin: ${pluginData.onlineData.plugin.name}"
                )
                unloadPlugin(pluginData.savedData.filePath)
            } else {
                val existingFile = File(pluginData.savedData.filePath)
                if (downloadPlugin(
                        activity,
                        pluginData.onlineData.plugin.url,
                        pluginData.onlineData.plugin.fileHash,
                        pluginData.savedData.internalName,
                        existingFile,
                        true
                    )
                ) {
                    updatedPlugins.add(pluginData.onlineData.plugin.name)
                }
            }
        }.also {
            main {
                val message = if (updatedPlugins.isNotEmpty()) {
                    activity.getString(R.string.plugins_updated_manually, updatedPlugins.size)
                } else {
                    activity.getString(R.string.no_plugins_updated_manually)
                }
                showToast(message, Toast.LENGTH_LONG)

                val notificationText = UiText.StringResource(
                    R.string.plugins_updated_manually,
                    listOf(updatedPlugins.size)
                )
                createNotification(activity, notificationText, updatedPlugins)

            }
        }

        loadedOnlinePlugins = true
        afterPluginsLoadedEvent.invoke(false)

        Log.i("PluginManager", "Plugin update done!")
    }

    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = EXTENSIONS_CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText =
                EXTENSIONS_CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(EXTENSIONS_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        context: Context,
        uitext: UiText,
        extensions: List<String>
    ): Notification? {
        try {

            if (extensions.isEmpty()) return null

            val content = extensions.joinToString(", ")
//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, EXTENSIONS_CHANNEL_ID)
                .setAutoCancel(false)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(uitext.asString(context))
                //.setContentTitle(context.getString(title, extensionNames.size))
                .setSmallIcon(R.drawable.ic_baseline_extension_24)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(content)
                )
                .setContentText(content)

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            // notificationId is a unique int for each notification that you must define
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(context)
                    .notify((System.currentTimeMillis() / 1000).toInt(), notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }
}
