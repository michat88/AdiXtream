package com.lagradost.cloudstream3

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.IdRes
import androidx.annotation.MainThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.core.view.children
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.marginStart
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.Session
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.snackbar.Snackbar
import com.google.common.collect.Comparators.min
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.initAll
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.loadThemes
import com.lagradost.cloudstream3.CommonActivity.onColorSelectedEvent
import com.lagradost.cloudstream3.CommonActivity.onDialogDismissedEvent
import com.lagradost.cloudstream3.CommonActivity.onUserLeaveHint
import com.lagradost.cloudstream3.CommonActivity.screenHeight
import com.lagradost.cloudstream3.CommonActivity.setActivityInstance
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CommonActivity.updateLocale
import com.lagradost.cloudstream3.CommonActivity.updateTheme
import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.databinding.ActivityMainBinding
import com.lagradost.cloudstream3.databinding.ActivityMainTvBinding
import com.lagradost.cloudstream3.databinding.BottomResultviewPreviewBinding
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins
import com.lagradost.cloudstream3.plugins.PluginManager.loadSinglePlugin
import com.lagradost.cloudstream3.receivers.VideoDownloadRestartReceiver
import com.lagradost.cloudstream3.services.SubscriptionWorkManager
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_PLAYER
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_REPO
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_RESUME_WATCHING
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SEARCH
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING_SHARE
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.localListApi
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.account.AccountHelper.showAccountSelectLinear
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_NAVIGATE_TO
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.library.LibraryViewModel
import com.lagradost.cloudstream3.ui.player.BasicLink
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.LinkGenerator
import com.lagradost.cloudstream3.ui.result.LinearListLayout
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.ui.result.SyncViewModel
import com.lagradost.cloudstream3.ui.search.SearchFragment
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.ui.settings.SettingsGeneral
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.ui.setup.SetupFragmentExtensions
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.AppContextUtils.getApiDubstatusSettings
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isLtr
import com.lagradost.cloudstream3.utils.AppContextUtils.isNetworkAvailable
import com.lagradost.cloudstream3.utils.AppContextUtils.isRtl
import com.lagradost.cloudstream3.utils.AppContextUtils.loadCache
import com.lagradost.cloudstream3.utils.AppContextUtils.loadRepository
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppContextUtils.updateHasTrailers
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackupUtils.backup
import com.lagradost.cloudstream3.utils.BackupUtils.setUpBackup
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.BiometricCallback
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.biometricPrompt
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.deviceHasPasswordPinLock
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.isAuthEnabled
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.promptInfo
import com.lagradost.cloudstream3.utils.BiometricAuthenticator.startBiometricAuthentication
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.accounts
import com.lagradost.cloudstream3.utils.DataStoreHelper.migrateResumeWatching
import com.lagradost.cloudstream3.utils.Event
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.InAppUpdater.Companion.runAutoUpdate
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SnackbarHelper.showSnackbar
import com.lagradost.cloudstream3.utils.UIHelper.changeStatusBarState
import com.lagradost.cloudstream3.utils.UIHelper.checkWrite
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.enableEdgeToEdgeCompat
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.requestRW
import com.lagradost.cloudstream3.utils.UIHelper.setNavigationBarColorCompat
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream3.utils.USER_SELECTED_HOMEPAGE_API
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.setTextHtml
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.safefile.SafeFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.system.exitProcess
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.TvContractCompat
import android.content.ComponentName
import android.content.ContentUris
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.utils.TvChannelUtils

// --- IMPORT TAMBAHAN ADIXTREAM ---
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.PluginsViewModel
import com.lagradost.cloudstream3.PremiumManager // File logic premium
// -----------------------

class MainActivity : AppCompatActivity(), ColorPickerDialogListener, BiometricCallback {
    companion object {
        var activityResultLauncher: ActivityResultLauncher<Intent>? = null

        const val TAG = "MAINACT"
        const val ANIMATED_OUTLINE: Boolean = false
        var lastError: String? = null

        private const val FILE_DELETE_KEY = "FILES_TO_DELETE_KEY"
        const val API_NAME_EXTRA_KEY = "API_NAME_EXTRA_KEY"

        private var filesToDelete: Set<String>
            get() = getKey<Set<String>>(FILE_DELETE_KEY) ?: setOf()
            private set(value) = setKey(FILE_DELETE_KEY, value)

        fun deleteFileOnExit(file: File) {
            filesToDelete = filesToDelete + file.path
        }

        var nextSearchQuery: String? = null

        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()

        @Suppress("DEPRECATION_ERROR")
        fun handleAppIntentUrl(
            activity: FragmentActivity?,
            str: String?,
            isWebview: Boolean,
            extraArgs: Bundle? = null
        ): Boolean =
            with(activity) {
                fun safeURI(uri: String) = safe { URI(uri) }

                if (str != null && this != null) {
                    if (str.startsWith("https://cs.repo")) {
                        val realUrl = "https://" + str.substringAfter("?")
                        println("Repository url: $realUrl")
                        loadRepository(realUrl)
                        return true
                    } else if (str.contains(APP_STRING)) {
                        for (api in AccountManager.allApis) {
                            if (api.isValidRedirectUrl(str)) {
                                ioSafe {
                                    Log.i(TAG, "handleAppIntent $str")
                                    try {
                                        val isSuccessful = api.login(str)
                                        if (isSuccessful) {
                                            Log.i(TAG, "authenticated ${api.name}")
                                        } else {
                                            Log.i(TAG, "failed to authenticate ${api.name}")
                                        }
                                        showToast(
                                            if (isSuccessful) {
                                                txt(R.string.authenticated_user, api.name)
                                            } else {
                                                txt(R.string.authenticated_user_fail, api.name)
                                            }
                                        )
                                    } catch (t: Throwable) {
                                        logError(t)
                                        showToast(txt(R.string.authenticated_user_fail, api.name))
                                    }
                                }
                                return true
                            }
                        }
                        if (str == "$APP_STRING:") {
                            ioSafe {
                                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(activity)
                            }
                        }
                    } else if (safeURI(str)?.scheme == APP_STRING_REPO) {
                        val url = str.replaceFirst(APP_STRING_REPO, "https")
                        loadRepository(url)
                        return true
                    } else if (safeURI(str)?.scheme == APP_STRING_SEARCH) {
                        val query = str.substringAfter("$APP_STRING_SEARCH://")
                        nextSearchQuery = try {
                                URLDecoder.decode(query, "UTF-8")
                            } catch (t: Throwable) {
                                logError(t)
                                query
                            }
                        activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId = R.id.navigation_search
                        activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId = R.id.navigation_search
                    } else if (safeURI(str)?.scheme == APP_STRING_PLAYER) {
                        val uri = Uri.parse(str)
                        val name = uri.getQueryParameter("name")
                        val url = URLDecoder.decode(uri.authority, "UTF-8")

                        navigate(
                            R.id.global_to_navigation_player,
                            GeneratorPlayer.newInstance(
                                LinkGenerator(
                                    listOf(BasicLink(url, name)),
                                    extract = true,
                                )
                            )
                        )
                    } else if (safeURI(str)?.scheme == APP_STRING_RESUME_WATCHING) {
                        val id = str.substringAfter("$APP_STRING_RESUME_WATCHING://").toIntOrNull() ?: return false
                        ioSafe {
                            val resumeWatchingCard = HomeViewModel.getResumeWatching()?.firstOrNull { it.id == id } ?: return@ioSafe
                            activity.loadSearchResult(resumeWatchingCard, START_ACTION_RESUME_LATEST)
                        }
                    } else if (str.startsWith(APP_STRING_SHARE)) {
                        try {
                            val data = str.substringAfter("$APP_STRING_SHARE:")
                            val parts = data.split("?", limit = 2)
                            loadResult(
                                String(base64DecodeArray(parts[1]), Charsets.UTF_8),
                                String(base64DecodeArray(parts[0]), Charsets.UTF_8),
                                ""
                            )
                            return true
                        } catch (e: Exception) {
                            showToast("Invalid Uri", Toast.LENGTH_SHORT)
                            return false
                        }
                    } else if (!isWebview) {
                        if (str.startsWith(DOWNLOAD_NAVIGATE_TO)) {
                            this.navigate(R.id.navigation_downloads)
                            return true
                        } else {
                            val apiName = extraArgs?.getString(API_NAME_EXTRA_KEY)?.takeIf { it.isNotBlank() }
                            if (apiName != null) {
                                loadResult(str, apiName, "")
                                return true
                            }
                            synchronized(apis) {
                                for (api in apis) {
                                    if (str.startsWith(api.mainUrl)) {
                                        loadResult(str, api.name, "")
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
                return false
            }

        fun centerView(view: View?) {
            if (view == null) return
            try {
                Log.v(TAG, "centerView: $view")
                val r = Rect(0, 0, 0, 0)
                view.getDrawingRect(r)
                val x = r.centerX()
                val y = r.centerY()
                val dx = r.width() / 2
                val dy = screenHeight / 2
                val r2 = Rect(x - dx, y - dy, x + dx, y + dy)
                view.requestRectangleOnScreen(r2, false)
            } catch (_: Throwable) {
            }
        }
    }

    var lastPopup: SearchResponse? = null
    fun loadPopup(result: SearchResponse, load: Boolean = true) {
        lastPopup = result
        val syncName = syncViewModel.syncName(result.apiName)

        if (result is SyncAPI.LibraryItem && syncName != null) {
            isLocalList = false
            syncViewModel.setSync(syncName, result.syncId)
            syncViewModel.updateMetaAndUser()
        } else {
            isLocalList = true
            syncViewModel.clear()
        }

        if (load) {
            viewModel.load(
                this, result.url, result.apiName, false, if (getApiDubstatusSettings().contains(DubStatus.Dubbed)) DubStatus.Dubbed else DubStatus.Subbed, null
            )
        } else {
            viewModel.loadSmall(result)
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        onColorSelectedEvent.invoke(Pair(dialogId, color))
    }

    override fun onDialogDismissed(dialogId: Int) {
        onDialogDismissedEvent.invoke(dialogId)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLocale()
        updateTheme(this)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navHostFragment.navController.currentDestination?.let { updateNavBar(it) }
    }

    private fun updateNavBar(destination: NavDestination) {
        this.hideKeyboard()
        binding?.castMiniControllerHolder?.isVisible = !listOf(R.id.navigation_results_phone, R.id.navigation_results_tv, R.id.navigation_player).contains(destination.id)

        val isNavVisible = listOf(
            R.id.navigation_home, R.id.navigation_search, R.id.navigation_library, R.id.navigation_downloads, R.id.navigation_settings,
            R.id.navigation_download_child, R.id.navigation_subtitles, R.id.navigation_chrome_subtitles, R.id.navigation_settings_player,
            R.id.navigation_settings_updates, R.id.navigation_settings_ui, R.id.navigation_settings_account, R.id.navigation_settings_providers,
            R.id.navigation_settings_general, R.id.navigation_settings_extensions, R.id.navigation_settings_plugins, R.id.navigation_test_providers,
        ).contains(destination.id)

        binding?.apply {
            navRailView.isVisible = isNavVisible && isLandscape()
            navView.isVisible = isNavVisible && !isLandscape()
            navHostFragment.apply {
                val marginPx = resources.getDimensionPixelSize(R.dimen.nav_rail_view_width)
                layoutParams = (navHostFragment.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = if (isNavVisible && isLandscape() && isLayout(TV or EMULATOR)) marginPx else 0
                }
            }

            when (destination.id) {
                in listOf(R.id.navigation_downloads, R.id.navigation_download_child) -> {
                    navRailView.menu.findItem(R.id.navigation_downloads).isChecked = true
                    navView.menu.findItem(R.id.navigation_downloads).isChecked = true
                }
                in listOf(
                    R.id.navigation_settings, R.id.navigation_subtitles, R.id.navigation_chrome_subtitles, R.id.navigation_settings_player,
                    R.id.navigation_settings_updates, R.id.navigation_settings_ui, R.id.navigation_settings_account, R.id.navigation_settings_providers,
                    R.id.navigation_settings_general, R.id.navigation_settings_extensions, R.id.navigation_settings_plugins, R.id.navigation_test_providers
                ) -> {
                    navRailView.menu.findItem(R.id.navigation_settings).isChecked = true
                    navView.menu.findItem(R.id.navigation_settings).isChecked = true
                }
            }
        }
    }
    @Suppress("DEPRECATION_ERROR")
    override fun onCreate(savedInstanceState: Bundle?) {
        app.initClient(this)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        val errorFile = filesDir.resolve("last_error")
        if (errorFile.exists() && errorFile.isFile) {
            lastError = errorFile.readText(Charset.defaultCharset())
            errorFile.delete()
        } else {
            lastError = null
        }

        val settingsForProvider = SettingsJson()
        settingsForProvider.enableAdult =
            settingsManager.getBoolean(getString(R.string.enable_nsfw_on_providers_key), false)

        MainAPI.settingsForProvider = settingsForProvider

        loadThemes(this)
        enableEdgeToEdgeCompat()
        setNavigationBarColorCompat(R.attr.primaryGrayBackground)
        updateLocale()
        super.onCreate(savedInstanceState)

        // --- ADIXTREAM REPO MANAGEMENT SYSTEM (FIXED) ---
        ioSafe {
            // URL Repo Gratis (AmanGacorRepo)
            val freeRepoUrl = "https://raw.githubusercontent.com/michat88/free_repo/refs/heads/builds/repo.json" 
            
            // URL Repo Premium (AdiManuLateri3 - Ambil dari PremiumManager)
            val premiumRepoUrl = PremiumManager.PREMIUM_REPO_URL

            if (PremiumManager.isPremium(this@MainActivity)) {
                // === STATUS: PREMIUM USER ===
                
                // 1. Pastikan Repo Premium Terpasang
                val repoAddedKey = "HAS_ADDED_PREMIUM_REPO_V4"
                if (getKey(repoAddedKey, false) != true) {
                     try {
                         val parsedRepo = RepositoryManager.parseRepository(premiumRepoUrl)
                         if (parsedRepo != null) {
                             val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                                 parsedRepo.iconUrl, parsedRepo.name, premiumRepoUrl
                             )
                             RepositoryManager.addRepository(repoData)
                             setKey(repoAddedKey, true)
                             
                             // Download isi repo premium
                             main { PluginsViewModel.downloadAll(this@MainActivity, premiumRepoUrl, null) }
                         }
                     } catch (e: Exception) { logError(e) }
                }

                // 2. HAPUS REPO GRATIS (Jika Masih Ada)
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == freeRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                        setKey("HAS_ADDED_FREE_REPO_V4", false) // Reset flag free
                        Log.i("AdiXtream", "Premium detected. Free repo removed.")
                    }
                }

            } else {
                // === STATUS: FREE USER / EXPIRED ===
                
                // 1. Hapus Repo Premium (Security Cleanup)
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == premiumRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                        setKey("HAS_ADDED_PREMIUM_REPO_V4", false) // Reset flag premium
                        Log.i("AdiXtream", "Premium expired. Premium repo removed.")
                    }
                }

                // 2. Pasang Repo Gratis (AmanGacorRepo)
                val freeRepoKey = "HAS_ADDED_FREE_REPO_V4"
                if (getKey(freeRepoKey, false) != true) {
                     try {
                         val parsedRepo = RepositoryManager.parseRepository(freeRepoUrl)
                         if (parsedRepo != null) {
                             val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                                 parsedRepo.iconUrl, parsedRepo.name, freeRepoUrl
                             )
                             RepositoryManager.addRepository(repoData)
                             setKey(freeRepoKey, true)
                             
                             // Download isi repo gratis
                             main { PluginsViewModel.downloadAll(this@MainActivity, freeRepoUrl, null) }
                         }
                     } catch (e: Exception) { logError(e) }
                }
            }
        }
        // -----------------------------------------------------

        try {
            if (isCastApiAvailable()) {
                CastContext.getSharedInstance(this) { it.run() }
                    .addOnSuccessListener { mSessionManager = it.sessionManager }
            }
        } catch (t: Throwable) {
            logError(t)
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        updateTv()

        // backup when we update the app
        safe {
            val appVer = BuildConfig.VERSION_NAME
            val lastAppAutoBackup: String = getKey("VERSION_NAME") ?: ""
            if (appVer != lastAppAutoBackup) {
                setKey("VERSION_NAME", BuildConfig.VERSION_NAME)
                safe {
                    backup(this)
                }
                safe {
                    PluginManager.deleteAllOatFiles(this)
                }
            }
        }

        binding = try {
            if (isLayout(TV or EMULATOR)) {
                val newLocalBinding = ActivityMainTvBinding.inflate(layoutInflater, null, false)
                setContentView(newLocalBinding.root)

                if (isLayout(TV) && ANIMATED_OUTLINE) {
                    TvFocus.focusOutline = WeakReference(newLocalBinding.focusOutline)
                    newLocalBinding.root.viewTreeObserver.addOnScrollChangedListener {
                        TvFocus.updateFocusView(TvFocus.lastFocus.get(), same = true)
                    }
                    newLocalBinding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                        TvFocus.updateFocusView(newFocus)
                    }
                } else {
                    newLocalBinding.focusOutline.isVisible = false
                }

                if (isLayout(TV)) {
                    val exceptionButtons = listOf(
                        R.id.home_preview_info_btt,
                        R.id.home_preview_hidden_next_focus,
                        R.id.home_preview_hidden_prev_focus,
                        R.id.result_play_movie_button,
                        R.id.result_play_series_button,
                        R.id.result_resume_series_button,
                        R.id.result_play_trailer_button,
                        R.id.result_bookmark_Button,
                        R.id.result_favorite_Button,
                        R.id.result_subscribe_Button,
                        R.id.result_search_Button,
                        R.id.result_episodes_show_button,
                    )

                    newLocalBinding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                        if (exceptionButtons.contains(newFocus?.id)) return@addOnGlobalFocusChangeListener
                        centerView(newFocus)
                    }
                }

                ActivityMainBinding.bind(newLocalBinding.root) 
            } else {
                val newLocalBinding = ActivityMainBinding.inflate(layoutInflater, null, false)
                setContentView(newLocalBinding.root)
                newLocalBinding
            }
        } catch (t: Throwable) {
            showToast(txt(R.string.unable_to_inflate, t.message ?: ""), Toast.LENGTH_LONG)
            null
        }
        binding?.apply {
            fixSystemBarsPadding(
                navView,
                heightResId = R.dimen.nav_view_height,
                padTop = false,
                overlayCutout = false
            )

            fixSystemBarsPadding(
                navRailView,
                widthResId = R.dimen.nav_rail_view_width,
                padRight = false,
                padTop = false
            )
        }

        // --- BYPASS SETUP WIZARD ---
        if (getKey(HAS_DONE_SETUP_KEY, false) != true) {
             setKey(HAS_DONE_SETUP_KEY, true)
             updateLocale() 
        }

        val padding = settingsManager.getInt(getString(R.string.overscan_key), 0).toPx
        binding?.homeRoot?.setPadding(padding, padding, padding, padding)

        changeStatusBarState(isLayout(EMULATOR))

        /** Biometric stuff **/
        val noAccounts = settingsManager.getBoolean(
            getString(R.string.skip_startup_account_select_key),
            false
        ) || accounts.count() <= 1

        if (isLayout(PHONE) && isAuthEnabled(this) && noAccounts) {
            if (deviceHasPasswordPinLock(this)) {
                startBiometricAuthentication(this, R.string.biometric_authentication_title, false)
                promptInfo?.let { prompt ->
                    biometricPrompt?.authenticate(prompt)
                }
                binding?.navHostFragment?.isInvisible = true
            }
        }

        if (this.getKey<Boolean>(getString(R.string.jsdelivr_proxy_key)) == null && isNetworkAvailable()) {
            main {
                if (checkGithubConnectivity()) {
                    this.setKey(getString(R.string.jsdelivr_proxy_key), false)
                } else {
                    this.setKey(getString(R.string.jsdelivr_proxy_key), true)
                    showSnackbar(
                        this@MainActivity,
                        R.string.jsdelivr_enabled,
                        Snackbar.LENGTH_LONG,
                        R.string.revert
                    ) { setKey(getString(R.string.jsdelivr_proxy_key), false) }
                }
            }
        }

        ioSafe { SafeFile.check(this@MainActivity) }

        if (PluginManager.checkSafeModeFile()) {
            safe {
                showToast(R.string.safe_mode_file, Toast.LENGTH_LONG)
            }
        } else if (lastError == null) {
            ioSafe {
                DataStoreHelper.currentHomePage?.let { homeApi ->
                    mainPluginsLoadedEvent.invoke(loadSinglePlugin(this@MainActivity, homeApi))
                } ?: run {
                    mainPluginsLoadedEvent.invoke(false)
                }

                ioSafe {
                    if (settingsManager.getBoolean(
                            getString(R.string.auto_update_plugins_key),
                            true
                        )
                    ) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(
                            this@MainActivity
                        )
                    } else {
                        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(this@MainActivity)
                    }

                    val autoDownloadPlugin = AutoDownloadMode.getEnum(
                        settingsManager.getInt(
                            getString(R.string.auto_download_plugins_key),
                            0
                        )
                    ) ?: AutoDownloadMode.Disable
                    if (autoDownloadPlugin != AutoDownloadMode.Disable) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
                            this@MainActivity,
                            autoDownloadPlugin
                        )
                    }
                }

                ioSafe {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(
                        this@MainActivity,
                        false
                    )
                }
            }
        } else {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.safe_mode_title)
            builder.setMessage(R.string.safe_mode_description)
            builder.apply {
                setPositiveButton(R.string.safe_mode_crash_info) { _, _ ->
                    val tbBuilder: AlertDialog.Builder = AlertDialog.Builder(context)
                    tbBuilder.setTitle(R.string.safe_mode_title)
                    tbBuilder.setMessage(lastError)
                    tbBuilder.show()
                }
                setNegativeButton("Ok") { _, _ -> }
            }
            builder.show().setDefaultFocus()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setNavigationBarColorCompat(R.attr.primaryGrayBackground)
                updateLocale()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onAuthenticationSuccess() { binding?.navHostFragment?.isInvisible = false }
    override fun onAuthenticationError() { finish() }

    suspend fun checkGithubConnectivity(): Boolean {
        return try {
            app.get("https://raw.githubusercontent.com/recloudstream/.github/master/connectivitycheck", timeout = 5).text.trim() == "ok"
        } catch (t: Throwable) { false }
    }

    // --- KODE POPUP & DOWNLOAD PREMIUM ADIXTREAM (SAFE VERSION) ---
    private fun showPremiumUnlockDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(android.graphics.Color.parseColor("#202020"))
        }

        val title = TextView(context).apply {
            text = "🔒 PREMIUM ACCESS REQUIRED"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        
        val subTitle = TextView(context).apply {
            text = "Fitur ini terkunci. Hubungi Admin untuk mendapatkan Kode Aktivasi.\n\n💰 Harga: Rp 10.000 / Device"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val deviceIdVal = PremiumManager.getDeviceId(context)
        val deviceIdText = TextView(context).apply {
            text = "DEVICE ID: $deviceIdVal"
            textSize = 18f
            setTextColor(android.graphics.Color.YELLOW)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceIdVal)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "ID Disalin!", Toast.LENGTH_SHORT).show()
            }
        }

        val inputCode = EditText(context).apply {
            hint = "Masukkan KODE UNLOCK"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setBackgroundColor(android.graphics.Color.parseColor("#303030"))
        }

        val btnUnlock = Button(context).apply {
            text = "UNLOCK NOW"
            setBackgroundColor(android.graphics.Color.parseColor("#FFD700"))
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 30, 0, 0)
            setOnClickListener {
                val code = inputCode.text.toString().trim().uppercase()
                val correctCode = PremiumManager.generateUnlockCode(deviceIdVal)
                
                // BACKDOOR DEV
                if (code == correctCode || code == "DEV123") {
                    PremiumManager.activatePremium(context)
                    Toast.makeText(context, "Premium Berhasil! Mengupdate Repository...", Toast.LENGTH_LONG).show()
                    
                    // Trigger pergantian repo otomatis
                    performPremiumDownload()
                    
                    (tag as? Dialog)?.dismiss()
                } else {
                    Toast.makeText(context, "Kode Salah!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val btnAdmin = Button(context).apply {
            text = "TELEGRAM ADMIN"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.CYAN)
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Telegram tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(subTitle)
        layout.addView(deviceIdText)
        layout.addView(inputCode)
        layout.addView(btnUnlock)
        layout.addView(btnAdmin)

        val alert = AlertDialog.Builder(context)
            .setView(layout)
            .setCancelable(true)
            .create()
        
        btnUnlock.tag = alert
        alert.show()
    }

    private fun performPremiumDownload() {
        ioSafe {
            try {
                // 1. TAMBAH REPO PREMIUM
                val premiumRepoUrl = PremiumManager.PREMIUM_REPO_URL
                val parsedRepo = RepositoryManager.parseRepository(premiumRepoUrl)
                
                if (parsedRepo != null) {
                    val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                        parsedRepo.iconUrl, parsedRepo.name, premiumRepoUrl
                    )
                    RepositoryManager.addRepository(repoData)
                }

                // 2. HAPUS REPO GRATIS (AmanGacorRepo)
                val freeRepoUrl = "https://raw.githubusercontent.com/michat88/free_repo/refs/heads/builds/repo.json"
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == freeRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                    }
                }

                // 3. DOWNLOAD PLUGIN PREMIUM & NAVIGASI KE LIST EKSTENSI
                main {
                    PluginsViewModel.downloadAll(this@MainActivity, premiumRepoUrl, null)
                    
                    // Navigasi ke halaman LIST ekstensi (Karena repo lain sudah dihapus, 
                    // list ini hanya akan berisi 1 repo premium kamu).
                    // Ini AMAN dari error crash.
                    navigate(R.id.navigation_settings_extensions)
                    
                    showToast("Premium Aktif. Silahkan atur plugin di Repo AdiManuLateri3!", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                logError(e)
                main { 
                    Toast.makeText(this@MainActivity, "Gagal update: ${e.message}", Toast.LENGTH_SHORT).show() 
                }
            }
        }
    }
}
    @Suppress("DEPRECATION_ERROR")
    override fun onCreate(savedInstanceState: Bundle?) {
        app.initClient(this)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        val errorFile = filesDir.resolve("last_error")
        if (errorFile.exists() && errorFile.isFile) {
            lastError = errorFile.readText(Charset.defaultCharset())
            errorFile.delete()
        } else {
            lastError = null
        }

        val settingsForProvider = SettingsJson()
        settingsForProvider.enableAdult =
            settingsManager.getBoolean(getString(R.string.enable_nsfw_on_providers_key), false)

        MainAPI.settingsForProvider = settingsForProvider

        loadThemes(this)
        enableEdgeToEdgeCompat()
        setNavigationBarColorCompat(R.attr.primaryGrayBackground)
        updateLocale()
        super.onCreate(savedInstanceState)

        // --- ADIXTREAM REPO MANAGEMENT SYSTEM (FIXED) ---
        ioSafe {
            // URL Repo Gratis (AmanGacorRepo / Free Repo)
            val freeRepoUrl = "https://raw.githubusercontent.com/michat88/free_repo/refs/heads/builds/repo.json" 
            
            // URL Repo Premium (AdiManuLateri3 - Ambil dari PremiumManager)
            val premiumRepoUrl = PremiumManager.PREMIUM_REPO_URL

            if (PremiumManager.isPremium(this@MainActivity)) {
                // === STATUS: PREMIUM USER ===
                
                // 1. Pastikan Repo Premium Terpasang
                val repoAddedKey = "HAS_ADDED_PREMIUM_REPO_V5"
                if (getKey(repoAddedKey, false) != true) {
                     try {
                         val parsedRepo = RepositoryManager.parseRepository(premiumRepoUrl)
                         if (parsedRepo != null) {
                             val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                                 parsedRepo.iconUrl, parsedRepo.name, premiumRepoUrl
                             )
                             RepositoryManager.addRepository(repoData)
                             setKey(repoAddedKey, true)
                             
                             // Download isi repo premium
                             main { PluginsViewModel.downloadAll(this@MainActivity, premiumRepoUrl, null) }
                         }
                     } catch (e: Exception) { logError(e) }
                }

                // 2. HAPUS REPO GRATIS (Jika Masih Ada)
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == freeRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                        setKey("HAS_ADDED_FREE_REPO_V5", false) // Reset flag free
                        Log.i("AdiXtream", "Premium detected. Free repo removed.")
                    }
                }

            } else {
                // === STATUS: FREE USER / EXPIRED ===
                
                // 1. Hapus Repo Premium (Security Cleanup)
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == premiumRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                        setKey("HAS_ADDED_PREMIUM_REPO_V5", false) // Reset flag premium
                        Log.i("AdiXtream", "Premium expired. Premium repo removed.")
                    }
                }

                // 2. Pasang Repo Gratis (AmanGacorRepo)
                val freeRepoKey = "HAS_ADDED_FREE_REPO_V5"
                if (getKey(freeRepoKey, false) != true) {
                     try {
                         val parsedRepo = RepositoryManager.parseRepository(freeRepoUrl)
                         if (parsedRepo != null) {
                             val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                                 parsedRepo.iconUrl, parsedRepo.name, freeRepoUrl
                             )
                             RepositoryManager.addRepository(repoData)
                             setKey(freeRepoKey, true)
                             
                             // Download isi repo gratis
                             main { PluginsViewModel.downloadAll(this@MainActivity, freeRepoUrl, null) }
                         }
                     } catch (e: Exception) { logError(e) }
                }
            }
        }
        // -----------------------------------------------------

        try {
            if (isCastApiAvailable()) {
                CastContext.getSharedInstance(this) { it.run() }
                    .addOnSuccessListener { mSessionManager = it.sessionManager }
            }
        } catch (t: Throwable) {
            logError(t)
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        updateTv()

        // backup when we update the app
        safe {
            val appVer = BuildConfig.VERSION_NAME
            val lastAppAutoBackup: String = getKey("VERSION_NAME") ?: ""
            if (appVer != lastAppAutoBackup) {
                setKey("VERSION_NAME", BuildConfig.VERSION_NAME)
                safe {
                    backup(this)
                }
                safe {
                    PluginManager.deleteAllOatFiles(this)
                }
            }
        }

        binding = try {
            if (isLayout(TV or EMULATOR)) {
                val newLocalBinding = ActivityMainTvBinding.inflate(layoutInflater, null, false)
                setContentView(newLocalBinding.root)

                if (isLayout(TV) && ANIMATED_OUTLINE) {
                    TvFocus.focusOutline = WeakReference(newLocalBinding.focusOutline)
                    newLocalBinding.root.viewTreeObserver.addOnScrollChangedListener {
                        TvFocus.updateFocusView(TvFocus.lastFocus.get(), same = true)
                    }
                    newLocalBinding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                        TvFocus.updateFocusView(newFocus)
                    }
                } else {
                    newLocalBinding.focusOutline.isVisible = false
                }

                if (isLayout(TV)) {
                    val exceptionButtons = listOf(
                        R.id.home_preview_info_btt,
                        R.id.home_preview_hidden_next_focus,
                        R.id.home_preview_hidden_prev_focus,
                        R.id.result_play_movie_button,
                        R.id.result_play_series_button,
                        R.id.result_resume_series_button,
                        R.id.result_play_trailer_button,
                        R.id.result_bookmark_Button,
                        R.id.result_favorite_Button,
                        R.id.result_subscribe_Button,
                        R.id.result_search_Button,
                        R.id.result_episodes_show_button,
                    )

                    newLocalBinding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                        if (exceptionButtons.contains(newFocus?.id)) return@addOnGlobalFocusChangeListener
                        centerView(newFocus)
                    }
                }

                ActivityMainBinding.bind(newLocalBinding.root) 
            } else {
                val newLocalBinding = ActivityMainBinding.inflate(layoutInflater, null, false)
                setContentView(newLocalBinding.root)
                newLocalBinding
            }
        } catch (t: Throwable) {
            showToast(txt(R.string.unable_to_inflate, t.message ?: ""), Toast.LENGTH_LONG)
            null
        }
        binding?.apply {
            fixSystemBarsPadding(
                navView,
                heightResId = R.dimen.nav_view_height,
                padTop = false,
                overlayCutout = false
            )

            fixSystemBarsPadding(
                navRailView,
                widthResId = R.dimen.nav_rail_view_width,
                padRight = false,
                padTop = false
            )
        }

        // --- BYPASS SETUP WIZARD ---
        if (getKey(HAS_DONE_SETUP_KEY, false) != true) {
             setKey(HAS_DONE_SETUP_KEY, true)
             updateLocale() 
        }

        val padding = settingsManager.getInt(getString(R.string.overscan_key), 0).toPx
        binding?.homeRoot?.setPadding(padding, padding, padding, padding)

        changeStatusBarState(isLayout(EMULATOR))

        /** Biometric stuff **/
        val noAccounts = settingsManager.getBoolean(
            getString(R.string.skip_startup_account_select_key),
            false
        ) || accounts.count() <= 1

        if (isLayout(PHONE) && isAuthEnabled(this) && noAccounts) {
            if (deviceHasPasswordPinLock(this)) {
                startBiometricAuthentication(this, R.string.biometric_authentication_title, false)
                promptInfo?.let { prompt ->
                    biometricPrompt?.authenticate(prompt)
                }
                binding?.navHostFragment?.isInvisible = true
            }
        }

        if (this.getKey<Boolean>(getString(R.string.jsdelivr_proxy_key)) == null && isNetworkAvailable()) {
            main {
                if (checkGithubConnectivity()) {
                    this.setKey(getString(R.string.jsdelivr_proxy_key), false)
                } else {
                    this.setKey(getString(R.string.jsdelivr_proxy_key), true)
                    showSnackbar(
                        this@MainActivity,
                        R.string.jsdelivr_enabled,
                        Snackbar.LENGTH_LONG,
                        R.string.revert
                    ) { setKey(getString(R.string.jsdelivr_proxy_key), false) }
                }
            }
        }

        ioSafe { SafeFile.check(this@MainActivity) }

        if (PluginManager.checkSafeModeFile()) {
            safe {
                showToast(R.string.safe_mode_file, Toast.LENGTH_LONG)
            }
        } else if (lastError == null) {
            ioSafe {
                DataStoreHelper.currentHomePage?.let { homeApi ->
                    mainPluginsLoadedEvent.invoke(loadSinglePlugin(this@MainActivity, homeApi))
                } ?: run {
                    mainPluginsLoadedEvent.invoke(false)
                }

                ioSafe {
                    if (settingsManager.getBoolean(
                            getString(R.string.auto_update_plugins_key),
                            true
                        )
                    ) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_updateAllOnlinePluginsAndLoadThem(
                            this@MainActivity
                        )
                    } else {
                        ___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(this@MainActivity)
                    }

                    val autoDownloadPlugin = AutoDownloadMode.getEnum(
                        settingsManager.getInt(
                            getString(R.string.auto_download_plugins_key),
                            0
                        )
                    ) ?: AutoDownloadMode.Disable
                    if (autoDownloadPlugin != AutoDownloadMode.Disable) {
                        PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(
                            this@MainActivity,
                            autoDownloadPlugin
                        )
                    }
                }

                ioSafe {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllLocalPlugins(
                        this@MainActivity,
                        false
                    )
                }
            }
        } else {
            val builder: AlertDialog.Builder = AlertDialog.Builder(this)
            builder.setTitle(R.string.safe_mode_title)
            builder.setMessage(R.string.safe_mode_description)
            builder.apply {
                setPositiveButton(R.string.safe_mode_crash_info) { _, _ ->
                    val tbBuilder: AlertDialog.Builder = AlertDialog.Builder(context)
                    tbBuilder.setTitle(R.string.safe_mode_title)
                    tbBuilder.setMessage(lastError)
                    tbBuilder.show()
                }
                setNegativeButton("Ok") { _, _ -> }
            }
            builder.show().setDefaultFocus()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                setNavigationBarColorCompat(R.attr.primaryGrayBackground)
                updateLocale()
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    override fun onAuthenticationSuccess() { binding?.navHostFragment?.isInvisible = false }
    override fun onAuthenticationError() { finish() }

    suspend fun checkGithubConnectivity(): Boolean {
        return try {
            app.get("https://raw.githubusercontent.com/recloudstream/.github/master/connectivitycheck", timeout = 5).text.trim() == "ok"
        } catch (t: Throwable) { false }
    }

    // --- KODE POPUP & DOWNLOAD PREMIUM ADIXTREAM (SAFE VERSION) ---
    private fun showPremiumUnlockDialog() {
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(android.graphics.Color.parseColor("#202020"))
        }

        val title = TextView(context).apply {
            text = "🔒 PREMIUM ACCESS REQUIRED"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        
        val subTitle = TextView(context).apply {
            text = "Fitur ini terkunci. Hubungi Admin untuk mendapatkan Kode Aktivasi.\n\n💰 Harga: Rp 10.000 / Device"
            textSize = 14f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val deviceIdVal = PremiumManager.getDeviceId(context)
        val deviceIdText = TextView(context).apply {
            text = "DEVICE ID: $deviceIdVal"
            textSize = 18f
            setTextColor(android.graphics.Color.YELLOW)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
            
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device ID", deviceIdVal)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "ID Disalin!", Toast.LENGTH_SHORT).show()
            }
        }

        val inputCode = EditText(context).apply {
            hint = "Masukkan KODE UNLOCK"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setBackgroundColor(android.graphics.Color.parseColor("#303030"))
        }

        val btnUnlock = Button(context).apply {
            text = "UNLOCK NOW"
            setBackgroundColor(android.graphics.Color.parseColor("#FFD700"))
            setTextColor(android.graphics.Color.BLACK)
            setPadding(0, 30, 0, 0)
            setOnClickListener {
                val code = inputCode.text.toString().trim().uppercase()
                val correctCode = PremiumManager.generateUnlockCode(deviceIdVal)
                
                // BACKDOOR DEV
                if (code == correctCode || code == "DEV123") {
                    PremiumManager.activatePremium(context)
                    Toast.makeText(context, "Premium Berhasil! Mengupdate Repository...", Toast.LENGTH_LONG).show()
                    
                    // Trigger pergantian repo otomatis
                    performPremiumDownload()
                    
                    (tag as? Dialog)?.dismiss()
                } else {
                    Toast.makeText(context, "Kode Salah!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val btnAdmin = Button(context).apply {
            text = "TELEGRAM ADMIN"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setTextColor(android.graphics.Color.CYAN)
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/michat88"))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Telegram tidak ditemukan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(subTitle)
        layout.addView(deviceIdText)
        layout.addView(inputCode)
        layout.addView(btnUnlock)
        layout.addView(btnAdmin)

        val alert = AlertDialog.Builder(context)
            .setView(layout)
            .setCancelable(true)
            .create()
        
        btnUnlock.tag = alert
        alert.show()
    }

    private fun performPremiumDownload() {
        ioSafe {
            try {
                // 1. TAMBAH REPO PREMIUM
                val premiumRepoUrl = PremiumManager.PREMIUM_REPO_URL
                val parsedRepo = RepositoryManager.parseRepository(premiumRepoUrl)
                
                if (parsedRepo != null) {
                    val repoData = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(
                        parsedRepo.iconUrl, parsedRepo.name, premiumRepoUrl
                    )
                    RepositoryManager.addRepository(repoData)
                }

                // 2. HAPUS REPO GRATIS (AmanGacorRepo)
                val freeRepoUrl = "https://raw.githubusercontent.com/michat88/free_repo/refs/heads/builds/repo.json"
                val currentRepos = RepositoryManager.getRepositories()
                currentRepos.forEach { repo ->
                    if (repo.url == freeRepoUrl) {
                        RepositoryManager.removeRepository(this@MainActivity, repo)
                    }
                }

                // 3. DOWNLOAD PLUGIN PREMIUM & NAVIGASI KE LIST EKSTENSI
                main {
                    PluginsViewModel.downloadAll(this@MainActivity, premiumRepoUrl, null)
                    
                    // Navigasi ke halaman LIST ekstensi (navigation_settings_extensions)
                    // Karena repo lain sudah dihapus, list ini hanya berisi 1 repo premium kamu.
                    // Ini AMAN dari error crash 'navigation_repository' yang tadi.
                    navigate(R.id.navigation_settings_extensions)
                    
                    showToast("Premium Aktif. Silahkan pilih plugin dari Repo AdiManuLateri3!", Toast.LENGTH_LONG)
                }
            } catch (e: Exception) {
                logError(e)
                main { 
                    Toast.makeText(this@MainActivity, "Gagal update: ${e.message}", Toast.LENGTH_SHORT).show() 
                }
            }
        }
    }
}
