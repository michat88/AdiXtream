package com.lagradost.cloudstream3

import android.util.Xml
import android.content.ContentValues
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Runs only on an isolated emulator with empty backend configuration. Never submits a code. */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private var menuLabels = emptyList<String>()

    @get:Rule val failureEvidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { saveScreenshot("failure-${description.methodName}") }
        }
    }

    @Before fun requireIsolatedBuild() {
        check(InstrumentationRegistry.getArguments().getString("adiOfflineUi") == "true")
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.adixtream.app.debug")
        check(BuildConfig.FREE_REPO_ENCODED.isEmpty() && BuildConfig.PREMIUM_REPO_ENCODED.isEmpty())
        check(BuildConfig.FIREBASE_URL_ENCODED.isEmpty())
        // A fresh install's permission dialog otherwise covers the Compose screen.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
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
            capture("tv-subscription-hero")
            val copy = findVisible("Salin", scroll = true) ?: error("Missing copy action")
            val button = clickable(copy)
            assertTrue("TV copy button accepts input focus", button.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitText("ID Disalin")
            val before = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            // Compose may scroll/lazily compose the next field before changing focus;
            // wait for the accessibility event rather than reading the previous cache frame.
            var after: AccessibilityNodeInfo? = null
            for (attempt in 0 until 30) {
                val root = automation.rootInActiveWindow
                root?.refresh()
                after = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.apply { refresh() }
                if (after != null && after != before) break
                SystemClock.sleep(100)
            }
            saveScreenshot("tv-after-dpad-down")
            assertNotNull("D-pad retains a focus target", after)
            assertNotEquals("D-pad moves from copy to activation input", before, after)
            scenario.onActivity { host ->
                (host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300)
            capture("tv-subscription-focus")
            findVisible("Klaim Promo", scroll = true) ?: error("Promo not reachable on TV")
            capture("tv-subscription-promo")
            findVisible("Hubungi admin", scroll = true) ?: error("Plan/contact not reachable on TV")
            capture("tv-subscription-plans")
            val activateShortcut = findVisible("Aktivasi kode", scroll = true, backward = true)
                ?: error("Activation shortcut unreachable")
            assertTrue(clickable(activateShortcut).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText("Kode aktivasi")
            var inputFocused = false
            repeat(30) {
                val focused = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused?.isEditable == true) inputFocused = true
                if (!inputFocused) SystemClock.sleep(100)
            }
            assertTrue("Activation shortcut transfers remote focus to code input", inputFocused)
            scenario.onActivity { host ->
                (host.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
            }
            instrumentation.waitForIdleSync()
            val plansShortcut = findVisible("Berlangganan", scroll = true, backward = true, exact = true)
                ?: error("Subscription shortcut unreachable")
            assertTrue(clickable(plansShortcut).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitText("Berlangganan · Hubungi admin")
            capture("tv-subscription-shortcut")
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
        for (label in menuLabels) assertNotNull("Settings entry must be reachable: $label", findVisible(label, scroll = true))
        assertNotNull("Build stamp reachable by scrolling", findVisible("Tentang AdiXtream", scroll = true))
        capture("settings-bottom-${context.resources.configuration.orientation}-${layout()}")
        val subscription = findVisible("Aktivasi dan Langganan")
            ?: findVisible("Aktivasi dan Langganan", scroll = true, backward = true)
            ?: error("Missing subscription entry")
        assertTrue(clickable(subscription).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        awaitText("ADIXTREAM")
    }

    private fun layout() = PreferenceManager.getDefaultSharedPreferences(context).getInt("app_layout_key", -1)

    private fun verifySubscription(prefix: String) {
        capture("$prefix-subscription-hero")
        assertNotNull(findVisible("Gratis", scroll = true))
        val expectedId = PremiumManager.getDeviceId(context)
        assertNotNull("Existing device ID displayed", findVisible(expectedId, scroll = true))
        val copy = findVisible("Salin", scroll = true) ?: error("Copy action missing")
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
            root.refresh()
            add(root)
            for (i in 0 until root.childCount) addAll(nodes(root.getChild(i)))
        }
    }

    private fun findVisible(text: String, scroll: Boolean = false, backward: Boolean = false, exact: Boolean = false): AccessibilityNodeInfo? {
        repeat(if (scroll) 24 else 1) {
            val all = nodes(automation.rootInActiveWindow)
            all.firstOrNull { it.isVisibleToUser && (if (exact) it.text?.toString() == text else it.text?.toString()?.contains(text) == true) }?.let { return it }
            if (!scroll) return null
            // TV also has a scrollable navigation rail. Scroll the main content,
            // and use overlapping swipes so short cards cannot be skipped.
            val container = all.filter { it.isScrollable && it.isVisibleToUser }
                .maxByOrNull { node -> Rect().also(node::getBoundsInScreen).let { it.width() * it.height() } }
                ?: return null
            swipeContent(container, backward)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
        }
        saveScreenshot("missing-${text.hashCode()}")
        return null
    }

    private fun swipeContent(node: AccessibilityNodeInfo, backward: Boolean) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val window = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        check(bounds.intersect(window)) { "Scrollable content must intersect its window" }
        val x = bounds.exactCenterX()
        val start = bounds.top + bounds.height() * (if (backward) 0.4f else 0.65f)
        val end = bounds.top + bounds.height() * (if (backward) 0.65f else 0.4f)
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(motion, true)
            motion.recycle()
        }
        event(MotionEvent.ACTION_DOWN, start)
        for (step in 1..20) {
            SystemClock.sleep(30)
            event(MotionEvent.ACTION_MOVE, start + (end - start) * step / 20f)
        }
        // End with a stationary pointer: a fling can skip whole preference rows.
        SystemClock.sleep(200)
        event(MotionEvent.ACTION_MOVE, end)
        event(MotionEvent.ACTION_UP, end)
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
        // Instrumentation targetContext may retain portrait metrics after rotation.
        val bitmap = checkNotNull(automation.takeScreenshot())
        val display = Rect(0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        saveScreenshot(name)
        for (node in nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser && it.isClickable }) {
            val bounds = Rect().also(node::getBoundsInScreen)
            assertTrue("Visible control ${node.className} at $bounds intersects $display",
                bounds.isEmpty || Rect.intersects(display, bounds))
        }
    }

    private fun saveScreenshot(name: String) {
        check(name.matches(Regex("[a-zA-Z0-9_-]+")))
        // AGP uninstalls the tested APK, deleting its external-files directory.
        // Shell-owned emulator Downloads survives that cleanup; no app permission changes.
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("mkdir -p /sdcard/Download/AdiXtream-migration-ui")
        shell("screencap -p /sdcard/Download/AdiXtream-migration-ui/$name.png")
        // Test-only hierarchy contains isolated emulator state, never production credentials.
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.xml")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/AdiXtream-migration-ui")
            }))
        checkNotNull(resolver.openOutputStream(uri)).use { output ->
            val xml = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true) }
            xml.startTag(null, "hierarchy")
            for (node in nodes(automation.rootInActiveWindow)) {
                xml.startTag(null, "node")
                xml.attribute(null, "text", node.text?.toString().orEmpty())
                xml.attribute(null, "visible", node.isVisibleToUser.toString())
                xml.attribute(null, "scrollable", node.isScrollable.toString())
                xml.attribute(null, "bounds", Rect().also(node::getBoundsInScreen).toShortString())
                xml.endTag(null, "node")
            }
            xml.endTag(null, "hierarchy"); xml.endDocument()
        }
    }
}
