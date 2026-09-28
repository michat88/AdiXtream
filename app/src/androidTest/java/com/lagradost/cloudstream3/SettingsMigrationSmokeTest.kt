package com.lagradost.cloudstream3

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs only on an isolated emulator with empty backend configuration. Never submits a code. */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private var menuLabels = emptyList<String>()

    @Before fun requireIsolatedBuild() {
        check(InstrumentationRegistry.getArguments().getString("adiOfflineUi") == "true")
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.adixtream.app.debug")
        check(BuildConfig.FREE_REPO_ENCODED.isEmpty() && BuildConfig.PREMIUM_REPO_ENCODED.isEmpty())
        check(BuildConfig.FIREBASE_URL_ENCODED.isEmpty())
    }

    @Test fun phonePortraitAndLandscape() {
        launch(0).use { scenario ->
            orient(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            openSettings(scenario)
            capture("phone-portrait-settings-top")
            verifyMenuAndOpenSubscription()
            verifySubscription("phone-portrait")
            orient(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            openSettings(scenario)
            capture("phone-landscape-settings-top")
            verifyMenuAndOpenSubscription()
            verifySubscription("phone-landscape")
        }
    }

    @Test fun tvLayoutAndDpad() {
        launch(1).use { scenario ->
            orient(scenario, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            openSettings(scenario)
            capture("tv-settings-top")
            verifyMenuAndOpenSubscription()
            val copy = findVisible("Salin") ?: error("Missing copy action")
            val button = clickable(copy)
            assertTrue("TV copy button accepts input focus", button.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitText("ID Disalin")
            val before = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            val after = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            assertNotNull("D-pad retains a focus target", after)
            assertNotEquals("D-pad moves from copy to activation input", before, after)
            capture("tv-subscription-focus")
            findVisible("Klaim Promo", scroll = true) ?: error("Promo not reachable on TV")
            capture("tv-subscription-promo")
            findVisible("Hubungi admin", scroll = true) ?: error("Plan/contact not reachable on TV")
            capture("tv-subscription-plans")
        }
    }

    private fun launch(layout: Int): ActivityScenario<MainActivity> {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putInt(context.getString(R.string.app_layout_key), layout).commit()
        return ActivityScenario.launch(MainActivity::class.java)
    }

    private fun orient(scenario: ActivityScenario<MainActivity>, value: Int) {
        scenario.onActivity { it.requestedOrientation = value }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)
    }

    private fun openSettings(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { host ->
            val nav = (host.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
            nav.popBackStack(R.id.navigation_home, false)
            nav.navigate(R.id.navigation_settings)
            menuLabels = listOf(R.string.category_general, R.string.category_player, R.string.category_ui,
                R.string.category_updates, R.string.category_account, R.string.pref_category_extensions,
                R.string.adi_subscription_title).map(host::getString)
        }
        awaitText(menuLabels.first())
    }

    private fun verifyMenuAndOpenSubscription() {
        for (label in menuLabels) assertNotNull("Settings entry must be reachable", findVisible(label, scroll = true))
        assertNotNull("Build stamp reachable by scrolling", findVisible("Tentang AdiXtream", scroll = true))
        capture("settings-bottom-${context.resources.configuration.orientation}-${layout()}")
        val subscription = findVisible("Aktivasi dan Langganan") ?: error("Missing subscription entry")
        assertTrue(clickable(subscription).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitText("Status Langganan")
    }

    private fun layout() = PreferenceManager.getDefaultSharedPreferences(context).getInt("app_layout_key", -1)

    private fun verifySubscription(prefix: String) {
        assertNotNull(findVisible("Gratis"))
        val expectedId = PremiumManager.getDeviceId(context)
        assertNotNull("Existing device ID displayed", findVisible(expectedId))
        val copy = findVisible("Salin") ?: error("Copy action missing")
        assertTrue(clickable(copy).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitText("ID Disalin")
        instrumentation.runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expectedId, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        capture("$prefix-subscription-status")
        val activation = findVisible("Aktifkan", scroll = true) ?: error("Activation not reachable")
        assertFalse("Empty activation cannot be submitted", clickable(activation).isEnabled)
        val promo = findVisible("Klaim Promo", scroll = true) ?: error("Promo not reachable")
        assertFalse("Empty promo cannot be submitted", clickable(promo).isEnabled)
        capture("$prefix-subscription-promo")
        assertNotNull(findVisible("Hubungi admin", scroll = true))
        capture("$prefix-subscription-plans")
    }

    private fun clickable(start: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node = start
        while (!node.isClickable && node.parent != null) node = node.parent
        return node
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root != null) {
            add(root)
            for (i in 0 until root.childCount) addAll(nodes(root.getChild(i)))
        }
    }

    private fun findVisible(text: String, scroll: Boolean = false): AccessibilityNodeInfo? {
        repeat(if (scroll) 14 else 1) {
            val all = nodes(automation.rootInActiveWindow)
            all.firstOrNull { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }?.let { return it }
            if (!scroll) return null
            val container = all.firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return null
            container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
        }
        return null
    }

    private fun awaitText(text: String) {
        repeat(50) {
            if (findVisible(text) != null) return
            SystemClock.sleep(100)
        }
        fail("Screen did not display expected UI text: $text")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        val image = checkNotNull(automation.takeScreenshot())
        val display = Rect(0, 0, image.width, image.height)
        for (node in nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser && it.isClickable }) {
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue("Visible control intersects the display", bounds.isEmpty || Rect.intersects(display, bounds))
        }
        val directory = File(context.getExternalFilesDir(null), "migration-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
