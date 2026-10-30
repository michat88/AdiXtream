package com.lagradost.cloudstream3

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process memory only: never preferences, saved state, backup or entitlement storage. */
internal class TemporaryCaptureSession {
    private val requested = MutableStateFlow(false)
    val allowed = requested.asStateFlow()
    fun setAllowed(value: Boolean) { requested.value = value }
    fun reset() = setAllowed(false)
    fun requiresSecureWindow(safeScreen: Boolean, foreground: Boolean): Boolean =
        !requested.value || !safeScreen || !foreground
}
