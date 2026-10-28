package com.lagradost.cloudstream3.plugins

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun allowsPluginPath(path: String?, premiumDirectory: File?, isPremium: () -> Boolean): Boolean =
    path == null || premiumDirectory == null ||
        File(path).absoluteFile.normalize().parentFile != premiumDirectory.absoluteFile.normalize() || isPremium()

internal fun movePluginFile(source: File, destination: File) {
    try {
        Files.move(source.toPath(), destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/** Keep the last working file until its replacement has initialized successfully. */
internal suspend fun replacePluginFile(
    staged: File,
    destination: File,
    allowed: () -> Boolean,
    activate: suspend () -> Boolean,
    restore: suspend () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    if (!allowed()) return false
    destination.parentFile?.mkdirs()
    val backup = if (destination.isFile) {
        File.createTempFile("plugin-rollback-", ".tmp", destination.parentFile).also {
            destination.copyTo(it, overwrite = true)
        }
    } else null
    var replaced = false
    var recovered = false
    var activationSucceeded = false
    try {
        currentCoroutineContext().ensureActive()
        if (!allowed()) return false
        movePluginFile(staged, destination)
        replaced = true
        val activated = allowed() && activate()
        currentCoroutineContext().ensureActive()
        activationSucceeded = activated && allowed()
        return activationSucceeded
    } finally {
        // Cancellation cannot interrupt restoration after the old file was replaced.
        if (replaced && !activationSucceeded) {
            withContext(NonCancellable) {
                if (backup != null) movePluginFile(backup, destination)
                else check(!destination.exists() || destination.delete()) { "Unable to roll back plugin file" }
                restore()
                recovered = true
            }
        }
        // If restoring the file itself failed, keep its backup for recovery.
        if (!replaced || activationSucceeded || recovered) backup?.delete()
    }
}
