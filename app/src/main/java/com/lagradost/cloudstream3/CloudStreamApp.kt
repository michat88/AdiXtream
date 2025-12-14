package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder.initAll
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.setup.HAS_DONE_SETUP_KEY
import com.lagradost.cloudstream3.utils.AppContextUtils.loadRepository
import com.lagradost.cloudstream3.utils.AppContextUtils.openBrowser
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.getKeys
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.DataStore.removeKeys
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.ImageLoader.buildImageLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class ExceptionHandler(
    val errorFile: File,
    val onError: (() -> Unit)
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val threadId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                thread.threadId()
            } else {
                @Suppress("DEPRECATION")
                thread.id
            }

            PrintStream(errorFile).use { ps ->
                ps.println("Currently loading extension: ${PluginManager.currentlyLoading ?: "none"}")
                ps.println("Fatal exception on thread ${thread.name} ($threadId)")
                error.printStackTrace(ps)
            }
        } catch (_: FileNotFoundException) {
        }
        try {
            onError()
        } catch (_: Exception) {
        }
        exitProcess(1)
    }
}

@Prerelease
class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // If we want to initialize Coil as early as possible, maybe when
        // loading an image or GIF in a splash screen activity.
        // buildImageLoader(applicationContext)

        ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
            startActivity(Intent.makeRestartActivityTask(intent!!.component))
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        // --- MODIFICATION START ---
        // 1. Inisialisasi API Dasar & DataStore
        initAll(this)
        DataStore.init(this)

        // 2. Jalankan Auto-Install Plugins & Repo
        autoInstallPlugins(this)
        // --- MODIFICATION END ---
    }

    // === MOD FUNCTION ===
    private fun autoInstallPlugins(context: Context) {
        // Gunakan ioSafe agar berjalan di background thread (tidak memblokir startup app)
        ioSafe {
            try {
                // A. Bypass Setup Wizard (Langsung ke Home)
                // Cek apakah setup sudah selesai, jika belum, tandai selesai.
                if (getKey<Boolean>(HAS_DONE_SETUP_KEY) != true) {
                    setKey(HAS_DONE_SETUP_KEY, true)
                }

                // B. Auto Load Repository (Hanya jalan sekali)
                val repoAddedKey = "HAS_ADDED_MY_REPO"
                if (getKey<Boolean>(repoAddedKey) != true) {
                    val customRepoUrl = "https://raw.githubusercontent.com/michat88/AdiManuLateri3/refs/heads/builds/repo.json"
                    // Load repository
                    loadRepository(customRepoUrl)
                    setKey(repoAddedKey, true)
                    Log.i("CloudStreamApp", "MOD: Custom repository loaded successfully.")
                }

                // C. Auto Install Plugins (Hanya jalan sekali)
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val pluginInstalledKey = "HAS_INSTALLED_PLUGINS_AUTO"
                val hasInstalled = prefs.getBoolean(pluginInstalledKey, false)

                if (!hasInstalled) {
                    // Download & Load plugin dari repo yang baru ditambahkan
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_loadAllOnlinePlugins(context)
                    
                    // Simpan status agar tidak download ulang setiap buka aplikasi
                    prefs.edit().putBoolean(pluginInstalledKey, true).apply()
                    Log.i("CloudStreamApp", "MOD: Plugins auto-installed successfully.")
                }

            } catch (e: Exception) {
                Log.e("CloudStreamApp", "MOD: Error during auto-setup", e)
            }
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
        // This can be removed without deprecation after next stable
        AcraApplication.context = context
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Coil module will be initialized globally when first loadImage() is invoked.
        return buildImageLoader(applicationContext)
    }

    companion object {
        var exceptionHandler: ExceptionHandler? = null

        /** Use to get Activity from Context. */
        tailrec fun Context.getActivity(): Activity? {
            return when (this) {
                is Activity -> this
                is ContextWrapper -> baseContext.getActivity()
                else -> null
            }
        }

        private var _context: WeakReference<Context>? = null
        var context
            get() = _context?.get()
            private set(value) {
                _context = WeakReference(value)
                setContext(WeakReference(value))
            }

        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            return context?.getKey(path, valueType)
        }

        fun <T : Any> setKeyClass(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun removeKeys(folder: String): Int? {
            return context?.removeKeys(folder)
        }

        fun <T> setKey(path: String, value: T) {
            context?.setKey(path, value)
        }

        fun <T> setKey(folder: String, path: String, value: T) {
            context?.setKey(folder, path, value)
        }

        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? {
            return context?.getKey(path, defVal)
        }

        inline fun <reified T : Any> getKey(path: String): T? {
            return context?.getKey(path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String): T? {
            return context?.getKey(folder, path)
        }

        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            return context?.getKey(folder, path, defVal)
        }

        fun getKeys(folder: String): List<String>? {
            return context?.getKeys(folder)
        }

        fun removeKey(folder: String, path: String) {
            context?.removeKey(folder, path)
        }

        fun removeKey(path: String) {
            context?.removeKey(path)
        }

        /** If fallbackWebView is true and a fragment is supplied then it will open a WebView with the URL if the browser fails. */
        fun openBrowser(url: String, fallbackWebView: Boolean = false, fragment: Fragment? = null) {
            context?.openBrowser(url, fallbackWebView, fragment)
        }

        /** Will fall back to WebView if in TV or emulator layout. */
        fun openBrowser(url: String, activity: FragmentActivity?) {
            openBrowser(
                url,
                isLayout(TV or EMULATOR),
                activity?.supportFragmentManager?.fragments?.lastOrNull()
            )
        }
    }
}
