package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.mvvm.ioSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.ImageLoader.buildImageLoader
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.ref.WeakReference
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
        } catch (_: FileNotFoundException) {}
        try { onError() } catch (_: Exception) {}
        exitProcess(1)
    }
}

@Prerelease
class CloudStreamApp : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()

        ExceptionHandler(filesDir.resolve("last_error")) {
            val intent = context!!.packageManager.getLaunchIntentForPackage(context!!.packageName)
            startActivity(Intent.makeRestartActivityTask(intent!!.component))
        }.also {
            exceptionHandler = it
            Thread.setDefaultUncaughtExceptionHandler(it)
        }

        // ✅ Auto install plugin sekali saat first run
        autoInstallPluginsFirstRun()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        context = base
        AcraApplication.context = context
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
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

        /** Helper untuk SharedPreferences */
        fun getPrefBoolean(key: String, def: Boolean = false): Boolean {
            val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            return prefs?.getBoolean(key, def) ?: def
        }

        fun setPrefBoolean(key: String, value: Boolean) {
            val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            prefs?.edit()?.putBoolean(key, value)?.apply()
        }

        fun removePref(key: String) {
            val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
            prefs?.edit()?.remove(key)?.apply()
        }
    }

    /** Auto install plugin dari repository hanya sekali saat first run */
    private fun autoInstallPluginsFirstRun() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val hasInstalled = prefs.getBoolean("HAS_INSTALLED_PLUGINS", false)
        if (!hasInstalled) {
            ioSafe {
                try {
                    PluginManager.updateAllOnlinePluginsAndLoadThem(this@CloudStreamApp)
                    prefs.edit().putBoolean("HAS_INSTALLED_PLUGINS", true).apply()
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }
    }
}
