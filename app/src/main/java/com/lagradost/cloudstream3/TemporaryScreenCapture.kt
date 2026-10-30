package com.lagradost.cloudstream3

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment

/** Capture policy only. Does not change destinations, playback, authentication or licenses. */
internal object TemporaryScreenCapture : Application.ActivityLifecycleCallbacks {
    const val SETTING_KEY = "temporary_screenshot_session"
    private val session = TemporaryCaptureSession()
    val allowed = session.allowed
    private data class Entry(var foreground: Boolean = false, var safe: Boolean = false,
        var controller: NavController? = null, var listener: NavController.OnDestinationChangedListener? = null)
    private val windows = mutableMapOf<Activity, Entry>()
    private val safeDestinations = setOf(R.id.navigation_home, R.id.navigation_search,
        R.id.navigation_library, R.id.navigation_downloads, R.id.navigation_settings,
        R.id.navigation_settings_general)

    fun initialize(application: Application) { application.registerActivityLifecycleCallbacks(this) }
    fun setAllowed(value: Boolean) {
        session.setAllowed(value)
        windows.forEach { (activity, entry) -> apply(activity, entry) }
    }
    private fun apply(activity: Activity, entry: Entry) {
        if (session.requiresSecureWindow(entry.safe, entry.foreground)) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        val entry = Entry()
        windows[activity] = entry
        apply(activity, entry)
    }
    override fun onActivityResumed(activity: Activity) {
        val entry = windows.getOrPut(activity) { Entry() }
        entry.foreground = true
        // All other activities (including activation, account selection and external login) stay secure.
        if (activity is MainActivity && entry.controller == null) {
            val host = (activity as FragmentActivity).supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            host?.navController?.let { controller ->
                val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                    entry.safe = destination.id in safeDestinations
                    apply(activity, entry)
                }
                entry.controller = controller
                entry.listener = listener
                controller.addOnDestinationChangedListener(listener)
            }
        }
        apply(activity, entry)
    }
    override fun onActivityPaused(activity: Activity) {
        windows[activity]?.let { it.foreground = false; apply(activity, it) }
    }
    override fun onActivityDestroyed(activity: Activity) {
        windows.remove(activity)?.let { entry -> entry.listener?.let { entry.controller?.removeOnDestinationChangedListener(it) } }
        if (windows.isEmpty() && !activity.isChangingConfigurations) session.reset()
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
