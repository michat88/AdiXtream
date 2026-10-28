package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.amap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

@Serializable
internal data class BootstrapProgress(val pending: List<String>, val complete: Boolean = false)

/** A durable initial-install journal; completed installs do not re-add user-deleted plugins. */
internal class RepositoryBootstrap(
    private val read: () -> BootstrapProgress?,
    private val write: (BootstrapProgress) -> Unit,
) {
    suspend fun prepare(
        catalog: List<String>,
        replacingRepository: Boolean,
        allowed: () -> Boolean,
        ensurePlugin: suspend (String) -> Boolean,
        commit: suspend () -> Boolean,
    ): Boolean {
        if (catalog.isEmpty() || !allowed()) return false
        val previous = read()
        val pending = if (!replacingRepository && previous != null) {
            previous.pending.filter { it in catalog }
        } else catalog.distinct()
        // Persist the entire plan before starting any download, including on the first install.
        write(BootstrapProgress(pending))
        val results = pending.amap { name ->
            currentCoroutineContext().ensureActive()
            name to (allowed() && ensurePlugin(name))
        }.toMap()
        val remaining = pending.filter { results[it] != true }
        write(BootstrapProgress(remaining))
        currentCoroutineContext().ensureActive()
        if (remaining.isNotEmpty() || !allowed() || !commit() || !allowed()) return false
        write(BootstrapProgress(emptyList(), complete = true))
        return true
    }
}
