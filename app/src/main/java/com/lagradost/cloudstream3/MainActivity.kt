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
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
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
                                        showToast(
                                            txt(R.string.authenticated_user_fail, api.name)
                                        )
                                    }
                                }
                                return true
                            }
                        }
                        if (str == "$APP_STRING:") {
                            ioSafe {
                                PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_hotReloadAllLocalPlugins(
                                    activity
                                )
                            }
                        }
                    } else if (safeURI(str)?.scheme == APP_STRING_REPO) {
                        val url = str.replaceFirst(APP_STRING_REPO, "https")
                        loadRepository(url)
                        return true
                    } else if (safeURI(str)?.scheme == APP_STRING_SEARCH) {
                        val query = str.substringAfter("$APP_STRING_SEARCH://")
                        nextSearchQuery =
                            try {
                                URLDecoder.decode(query, "UTF-8")
                            } catch (t: Throwable) {
                                logError(t)
                                query
                            }
                        activity?.findViewById<BottomNavigationView>(R.id.nav_view)?.selectedItemId =
                            R.id.navigation_search
                        activity?.findViewById<NavigationRailView>(R.id.nav_rail_view)?.selectedItemId =
                            R.id.navigation_search
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
                        val id =
                            str.substringAfter("$APP_STRING_RESUME_WATCHING://").toIntOrNull()
                                ?: return false
                        ioSafe {
                            val resumeWatchingCard =
                                HomeViewModel.getResumeWatching()?.firstOrNull { it.id == id }
                                    ?: return@ioSafe
                            activity.loadSearchResult(
                                resumeWatchingCard,
                                START_ACTION_RESUME_LATEST
                            )
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
                            val apiName = extraArgs?.getString(API_NAME_EXTRA_KEY)
                                ?.takeIf { it.isNotBlank() }
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
                this, result.url, result.apiName, false,
                if (getApiDubstatusSettings().contains(DubStatus.Dubbed))
                    DubStatus.Dubbed else DubStatus.Subbed,
                null
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

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navHostFragment.navController.currentDestination?.let { updateNavBar(it) }
    }

    private fun updateNavBar(destination: NavDestination) {
        this.hideKeyboard()

        binding?.castMiniControllerHolder?.isVisible =
            !listOf(
                R.id.navigation_results_phone,
                R.id.navigation_results_tv,
                R.id.navigation_player
            ).contains(destination.id)

        val isNavVisible = listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_library,
            R.id.navigation_downloads,
            R.id.navigation_settings,
            R.id.navigation_download_child,
            R.id.navigation_subtitles,
            R.id.navigation_chrome_subtitles,
            R.id.navigation_settings_player,
            R.id.navigation_settings_updates,
            R.id.navigation_settings_ui,
            R.id.navigation_settings_account,
            R.id.navigation_settings_providers,
            R.id.navigation_settings_general,
            R.id.navigation_settings_extensions,
            R.id.navigation_settings_plugins,
            R.id.navigation_test_providers,
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
                    R.id.navigation_settings,
                    R.id.navigation_subtitles,
                    R.id.navigation_chrome_subtitles,
                    R.id.navigation_settings_player,
                    R.id.navigation_settings_updates,
                    R.id.navigation_settings_ui,
                    R.id.navigation_settings_account,
                    R.id.navigation_settings_providers,
                    R.id.navigation_settings_general,
                    R.id.navigation_settings_extensions,
                    R.id.navigation_settings_plugins,
                    R.id.navigation_test_providers
                ) -> {
                    navRailView.menu.findItem(R.id.navigation_settings).isChecked = true
                    navView.menu.findItem(R.id.navigation_settings).isChecked = true
                }
            }
        }
    }

    var mSessionManager: SessionManager? = null
    private val mSessionManagerListener: SessionManagerListener<Session> by lazy { SessionManagerListenerImpl() }

    private inner class SessionManagerListenerImpl : SessionManagerListener<Session> {
        override fun onSessionStarting(session: Session) {}
        override fun onSessionStarted(session: Session, sessionId: String) { invalidateOptionsMenu() }
        override fun onSessionStartFailed(session: Session, i: Int) {}
        override fun onSessionEnding(session: Session) {}
        override fun onSessionResumed(session: Session, wasSuspended: Boolean) { invalidateOptionsMenu() }
        override fun onSessionResumeFailed(session: Session, i: Int) {}
        override fun onSessionSuspended(session: Session, i: Int) {}
        override fun onSessionEnded(session: Session, error: Int) {}
        override fun onSessionResuming(session: Session, s: String) {}
    }

    override fun onResume() {
        super.onResume()
        afterPluginsLoadedEvent += ::onAllPluginsLoaded
        setActivityInstance(this)
        try {
            if (isCastApiAvailable()) {
                mSessionManager?.addSessionManagerListener(mSessionManagerListener)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (ApkInstaller.delayedInstaller?.startInstallation() == true) {
            Toast.makeText(this, R.string.update_started, Toast.LENGTH_LONG).show()
        }
        try {
            if (isCastApiAvailable()) {
                mSessionManager?.removeSessionManagerListener(mSessionManagerListener)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        CommonActivity.dispatchKeyEvent(this, event) ?: super.dispatchKeyEvent(event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        CommonActivity.onKeyDown(this, keyCode, event) ?: super.onKeyDown(keyCode, event)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onUserLeaveHint(this)
    }

    @SuppressLint("ApplySharedPref")
    private fun showConfirmExitDialog(settingsManager: SharedPreferences) {
        val confirmBeforeExit = settingsManager.getInt(getString(R.string.confirm_exit_key), -1)

        if (confirmBeforeExit == 1 || (confirmBeforeExit == -1 && isLayout(PHONE))) {
            if (isLayout(TV)) exitProcess(0) else finish()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.confirm_exit_dialog, null)
        val dontShowAgainCheck: CheckBox = dialogView.findViewById(R.id.checkboxDontShowAgain)
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
            .setTitle(R.string.confirm_exit_dialog)
            .setNegativeButton(R.string.no) { _, _ -> }
            .setPositiveButton(R.string.yes) { _, _ ->
                if (dontShowAgainCheck.isChecked) {
                    settingsManager.edit(commit = true) {
                        putInt(getString(R.string.confirm_exit_key), 1)
                    }
                }
                if (isLayout(TV)) exitProcess(0) else finish()
            }

        builder.show().setDefaultFocus()
    }

    override fun onDestroy() {
        filesToDelete.forEach { path ->
            val result = File(path).deleteRecursively()
            Log.d(TAG, if (result) "Deleted temporary file: $path" else "Failed to delete temporary file: $path")
        }
        filesToDelete = setOf()
        val broadcastIntent = Intent().apply {
            action = "restart_service"
            setClass(this@MainActivity, VideoDownloadRestartReceiver::class.java)
        }
        sendBroadcast(broadcastIntent)
        afterPluginsLoadedEvent -= ::onAllPluginsLoaded
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        handleAppIntent(intent)
        super.onNewIntent(intent)
    }

    private fun handleAppIntent(intent: Intent?) {
        if (intent == null) return
        val str = intent.dataString
        loadCache()
        handleAppIntentUrl(this, str, false, intent.extras)
    }

    private fun NavDestination.matchDestination(@IdRes destId: Int): Boolean =
        hierarchy.any { it.id == destId }

    private var lastNavTime = 0L
    private fun onNavDestinationSelected(item: MenuItem, navController: NavController): Boolean {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNavTime < 400) return false
        lastNavTime = currentTime

        val destinationId = item.itemId
        if (navController.currentDestination?.id == destinationId) return false

        val targetView = when (destinationId) {
            R.id.navigation_search -> R.id.main_search
            R.id.navigation_library -> R.id.main_search
            R.id.navigation_downloads -> R.id.download_appbar
            else -> null
        }
        if (targetView != null && isLayout(TV or EMULATOR)) {
            val fromView = binding?.navRailView
            if (fromView != null) {
                fromView.nextFocusRightId = targetView
                for (focusView in arrayOf(
                    R.id.navigation_downloads,
                    R.id.navigation_home,
                    R.id.navigation_search,
                    R.id.navigation_library,
                    R.id.navigation_settings,
                )) {
                    fromView.findViewById<View?>(focusView)?.nextFocusRightId = targetView
                }
            }
        }

        val builder = NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
            .setEnterAnim(R.anim.enter_anim)
            .setExitAnim(R.anim.exit_anim)
            .setPopEnterAnim(R.anim.pop_enter)
            .setPopExitAnim(R.anim.pop_exit)
        if (item.order and Menu.CATEGORY_SECONDARY == 0) {
            builder.setPopUpTo(
                navController.graph.findStartDestination().id,
                inclusive = false,
                saveState = true
            )
        }
        return try {
            navController.navigate(destinationId, null, builder.build())
            navController.currentDestination?.matchDestination(destinationId) == true
        } catch (e: IllegalArgumentException) {
            Log.e("NavigationError", "Failed to navigate: ${e.message}")
            false
        }
    }

    private val pluginsLock = Mutex()
    private fun onAllPluginsLoaded(success: Boolean = false) {
        ioSafe {
            pluginsLock.withLock {
                synchronized(allProviders) {
                    try {
                        getKey<Array<SettingsGeneral.CustomSite>>(USER_PROVIDER_API)?.let { list ->
                            list.forEach { custom ->
                                allProviders.firstOrNull { it.javaClass.simpleName == custom.parentJavaClass }
                                    ?.let {
                                        allProviders.add(
                                            it.javaClass.getDeclaredConstructor().newInstance()
                                                .apply {
                                                    name = custom.name
                                                    lang = custom.lang
                                                    mainUrl = custom.url.trimEnd('/')
                                                    canBeOverridden = false
                                                })
                                    }
                            }
                        }
                        apis = allProviders.distinctBy { it.lang + it.name + it.mainUrl + it.javaClass.name }
                        APIHolder.apiMap = null
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }
        }
    }

    lateinit var viewModel: ResultViewModel2
    lateinit var syncViewModel: SyncViewModel
    private var libraryViewModel: LibraryViewModel? = null
    var isLocalList: Boolean = false

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        viewModel = ViewModelProvider(this)[ResultViewModel2::class.java]
        syncViewModel = ViewModelProvider(this)[SyncViewModel::class.java]
        return super.onCreateView(name, context, attrs)
    }
    private fun hidePreviewPopupDialog() {
        bottomPreviewPopup.dismissSafe(this)
        bottomPreviewPopup = null
        bottomPreviewBinding = null
    }

    private var bottomPreviewPopup: Dialog? = null
    private var bottomPreviewBinding: BottomResultviewPreviewBinding? = null
    private fun showPreviewPopupDialog(): BottomResultviewPreviewBinding {
        val ret = (bottomPreviewBinding ?: run {
            val builder: Dialog
            val layout: Int

            if (isLayout(PHONE)) {
                builder = BottomSheetDialog(this)
                layout = R.layout.bottom_resultview_preview
            } else {
                builder = Dialog(this, R.style.DialogHalfFullscreen)
                layout = R.layout.bottom_resultview_preview_tv
                builder.window?.setGravity(Gravity.CENTER_VERTICAL or Gravity.END)
            }

            val root = layoutInflater.inflate(layout, null, false)
            val binding = BottomResultviewPreviewBinding.bind(root)

            bottomPreviewBinding = binding
            builder.setContentView(root)
            builder.setOnDismissListener {
                bottomPreviewPopup = null
                bottomPreviewBinding = null
                viewModel.clear()
            }
            builder.setCanceledOnTouchOutside(true)
            builder.show()
            bottomPreviewPopup = builder
            binding
        })
        return ret
    }

    var binding: ActivityMainBinding? = null

    object TvFocus {
        data class FocusTarget(
            val width: Int,
            val height: Int,
            val x: Float,
            val y: Float,
        ) {
            companion object {
                fun lerp(a: FocusTarget, b: FocusTarget, lerp: Float): FocusTarget {
                    val ilerp = 1 - lerp
                    return FocusTarget(
                        width = (a.width * ilerp + b.width * lerp).toInt(),
                        height = (a.height * ilerp + b.height * lerp).toInt(),
                        x = a.x * ilerp + b.x * lerp,
                        y = a.y * ilerp + b.y * lerp
                    )
                }
            }
        }

        var last: FocusTarget = FocusTarget(0, 0, 0.0f, 0.0f)
        var current: FocusTarget = FocusTarget(0, 0, 0.0f, 0.0f)

        var focusOutline: WeakReference<View> = WeakReference(null)
        var lastFocus: WeakReference<View> = WeakReference(null)

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            lastFocus.get()?.apply {
                updateFocusView(this, same = true)
                postDelayed({ updateFocusView(lastFocus.get(), same = false) }, 300)
            }
        }
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { updateFocusView(v) }
            override fun onViewDetachedFromWindow(v: View) { focusOutline.get()?.isVisible = false }
        }

        private fun setTargetPosition(target: FocusTarget) {
            focusOutline.get()?.apply {
                layoutParams = layoutParams?.apply {
                    width = target.width
                    height = target.height
                }
                translationX = target.x
                translationY = target.y
                bringToFront()
            }
        }

        private var animator: ValueAnimator? = null
        private const val NO_MOVE_LIST: Boolean = false
        private const val LEFTMOST_MOVE_LIST: Boolean = true

        private val reflectedScroll by lazy {
            try {
                RecyclerView::class.java.declaredMethods.firstOrNull { it.name == "scrollStep" }?.also { it.isAccessible = true }
            } catch (t: Throwable) { null }
        }

        @MainThread
        fun updateFocusView(newFocus: View?, same: Boolean = false) {
            val focusOutline = focusOutline.get() ?: return
            val lastView = lastFocus.get()
            val exactlyTheSame = lastView == newFocus && newFocus != null
            if (!exactlyTheSame) {
                lastView?.removeOnLayoutChangeListener(layoutListener)
                lastView?.removeOnAttachStateChangeListener(attachListener)
                (lastView?.parent as? RecyclerView)?.removeOnLayoutChangeListener(layoutListener)
            }

            val wasGone = focusOutline.isGone
            val visible = newFocus != null &&
                    newFocus.measuredHeight > 0 &&
                    newFocus.measuredWidth > 0 &&
                    newFocus.isShown &&
                    newFocus.tag != "tv_no_focus_tag"
            focusOutline.isVisible = visible

            if (newFocus != null) {
                lastFocus = WeakReference(newFocus)
                val parent = newFocus.parent
                var targetDx = 0
                if (parent is RecyclerView) {
                    val layoutManager = parent.layoutManager
                    if (layoutManager is LinearListLayout && layoutManager.orientation == LinearLayoutManager.HORIZONTAL) {
                        val dx = LinearSnapHelper().calculateDistanceToFinalSnap(layoutManager, newFocus)?.get(0)
                        if (dx != null) {
                            val rdx = if (LEFTMOST_MOVE_LIST) {
                                val diff = ((layoutManager.width - layoutManager.paddingStart - newFocus.measuredWidth) / 2) - newFocus.marginStart
                                dx + if (parent.isRtl()) -diff else diff
                            } else {
                                if (dx > 0) dx else 0
                            }

                            if (!NO_MOVE_LIST) {
                                parent.smoothScrollBy(rdx, 0)
                            } else {
                                val smoothScroll = reflectedScroll
                                if (smoothScroll == null) {
                                    parent.smoothScrollBy(rdx, 0)
                                } else {
                                    try {
                                        val out = IntArray(2)
                                        smoothScroll.invoke(parent, rdx, 0, out)
                                        val scrolledX = out[0]
                                        if (abs(scrolledX) <= 0) {
                                            smoothScroll.invoke(parent, -rdx, 0, out)
                                            parent.smoothScrollBy(scrolledX, 0)
                                            if (NO_MOVE_LIST) targetDx = scrolledX
                                        }
                                    } catch (t: Throwable) {
                                        parent.smoothScrollBy(rdx, 0)
                                    }
                                }
                            }
                        }
                    }
                }

                val out = IntArray(2)
                newFocus.getLocationInWindow(out)
                val (screenX, screenY) = out
                var (x, y) = screenX.toFloat() to screenY.toFloat()
                val (currentX, currentY) = focusOutline.translationX to focusOutline.translationY

                if (!newFocus.isLtr()) x = x - focusOutline.rootView.width + newFocus.measuredWidth
                x -= targetDx

                if (screenX == 0 && screenY == 0) {
                    focusOutline.isVisible = false
                }
                if (!exactlyTheSame) {
                    (newFocus.parent@Suppress("DEPRECATION_ERROR")
    override fun onCreate(savedInstanceState: Bundle?) {
        app.initClient(this)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)

        val errorFile = filesDir.resolve("last_error")
        lastError = if (errorFile.exists() && errorFile.isFile) {
            errorFile.readText(Charset.defaultCharset()).also { errorFile.delete() }
        } else null

        val settingsForProvider = SettingsJson()
        settingsForProvider.enableAdult =
            settingsManager.getBoolean(getString(R.string.enable_nsfw_on_providers_key), false)
        MainAPI.settingsForProvider = settingsForProvider

        loadThemes(this)
        enableEdgeToEdgeCompat()
        setNavigationBarColorCompat(R.attr.primaryGrayBackground)
        updateLocale()
        super.onCreate(savedInstanceState)

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

        // --- MOD: Auto Repository & Bypass Setup ---
        ioSafe {
            val repoAddedKey = "HAS_ADDED_MY_REPO"
            if (getKey(repoAddedKey, false) != true) {
                try {
                    val customRepoUrl = "https://raw.githubusercontent.com/michat88/AdiManuLateri3/refs/heads/builds/repo.json"
                    loadRepository(customRepoUrl)
                    setKey(repoAddedKey, true)
                    Log.i(TAG, "Auto-loaded custom repository: $customRepoUrl")
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
        if (getKey(HAS_DONE_SETUP_KEY, false) != true) {
            setKey(HAS_DONE_SETUP_KEY, true)
            updateLocale()
        }

        // --- MOD: Auto Install Plugins First Run ---
        autoInstallPluginsFirstRun()

        // ...lanjutan isi onCreate (binding, nav setup, dll) tetap sama seperti file asli...
    }

    override fun onAuthenticationSuccess() {
        binding?.navHostFragment?.isInvisible = false
    }

    override fun onAuthenticationError() {
        finish()
    }

    suspend fun checkGithubConnectivity(): Boolean {
        return try {
            app.get(
                "https://raw.githubusercontent.com/recloudstream/.github/master/connectivitycheck",
                timeout = 5
            ).text.trim() == "ok"
        } catch (t: Throwable) {
            false
        }
    }

    // === MOD FUNCTION ===
    private fun autoInstallPluginsFirstRun() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val hasInstalled = prefs.getBoolean("HAS_INSTALLED_PLUGINS", false)
        if (!hasInstalled) {
            ioSafe {
                try {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(this@MainActivity)
                    prefs.edit().putBoolean("HAS_INSTALLED_PLUGINS", true).apply()
                    Log.i(TAG, "Auto-installed plugins from repository (first run)")
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
    }
}
