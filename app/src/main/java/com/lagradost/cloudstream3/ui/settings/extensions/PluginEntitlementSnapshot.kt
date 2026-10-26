package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lagradost.cloudstream3.PremiumManager
import com.lagradost.cloudstream3.plugins.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** Presentation only. Download/settings actions still check current authoritative entitlement. */
@Immutable
data class PluginEntitlementSnapshot(
    val isPremium: Boolean = false,
    val expiresAt: Long = 0L,
    private val premiumRepositoryUrl: String = "",
    private val premiumDirectory: File? = null,
) {
    // Pure row rendering: no preferences, keystore, network, or filesystem reads.
    fun isLocked(repositoryUrl: String, installedPath: String?): Boolean {
        if (isPremium || premiumRepositoryUrl.isBlank()) return false
        return repositoryUrl == premiumRepositoryUrl ||
            (repositoryUrl.isBlank() && installedPath != null && premiumDirectory != null &&
                File(installedPath).parentFile == premiumDirectory)
    }

    override fun toString() = "PluginEntitlementSnapshot(isPremium=$isPremium)"
}

private const val PREMIUM_REFRESH_MS = 5 * 60 * 1000L

@OptIn(ExperimentalCoroutinesApi::class)
internal fun pluginEntitlementSnapshots(
    changes: Flow<Unit>,
    nowMillis: () -> Long = System::currentTimeMillis,
    readSnapshot: suspend () -> PluginEntitlementSnapshot,
): Flow<PluginEntitlementSnapshot> = changes.conflate().transformLatest {
    while (currentCoroutineContext().isActive) {
        val snapshot = withContext(Dispatchers.IO) { readSnapshot() }
        emit(snapshot)
        val remaining = snapshot.expiresAt - nowMillis()
        val refreshAfter = if (snapshot.isPremium && snapshot.expiresAt > 0L && remaining < PREMIUM_REFRESH_MS) {
            (remaining + 1L).coerceAtLeast(1L)
        } else {
            PREMIUM_REFRESH_MS
        }
        delay(refreshAfter)
    }
}

private fun premiumPreferenceChanges(context: Context): Flow<Unit> = callbackFlow {
    // Existing activation/server/recovery writes always update this fallback store too.
    // Observe it without reopening encrypted preferences in each visible row.
    val preferences = context.getSharedPreferences("premium_fallback_prefs", Context.MODE_PRIVATE)
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    trySend(Unit)
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}.flowOn(Dispatchers.IO)

@Composable
internal fun rememberPluginEntitlementSnapshot(): State<PluginEntitlementSnapshot> {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val initial = remember(context) {
        val premiumUrl = PremiumManager.PREMIUM_REPO_URL
        PluginEntitlementSnapshot(
            premiumRepositoryUrl = premiumUrl,
            premiumDirectory = if (premiumUrl.isBlank()) null
                else PluginManager.getPluginPath(context, "", premiumUrl).parentFile,
        )
    }
    val snapshots = remember(context, initial) {
        pluginEntitlementSnapshots(premiumPreferenceChanges(context)) {
            val premium = PremiumManager.isPremium(context)
            initial.copy(
                isPremium = premium,
                expiresAt = if (premium) PremiumManager.getCachedExpiryForDisplay(context) else 0L,
            )
        }
    }
    // Collect once for the screen, refresh on resume, and release listeners/timers off-screen.
    return snapshots.collectAsStateWithLifecycle(
        initialValue = initial,
        lifecycleOwner = owner,
        minActiveState = Lifecycle.State.RESUMED,
    )
}
