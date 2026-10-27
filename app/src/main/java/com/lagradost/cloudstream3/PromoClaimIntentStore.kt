package com.lagradost.cloudstream3

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

internal interface PromoClaimIntentStore {
    /** Corrupt/unavailable state throws; it is never treated as an empty store. */
    fun read(): PendingPromoClaim?
    fun compareAndSet(expected: PendingPromoClaim?, next: PendingPromoClaim?): Boolean
}

/**
 * Future Android wiring must pass context.noBackupFilesDir. This store is not
 * constructed by the active app yet. No entitlement/secret/preference is stored.
 */
internal class PromoClaimIntentFileStore(privateDirectory: File) : PromoClaimIntentStore {
    private val directory = privateDirectory.canonicalFile
    private val stateFile = File(directory, "adixtream-promo-intent-v1.json")
    private val lockFile = File(directory, "adixtream-promo-intent-v1.lock")
    private val processGuard = guards.getOrPut(lockFile.path) { Any() }

    override fun read(): PendingPromoClaim? = locked { readUnlocked() }

    override fun compareAndSet(expected: PendingPromoClaim?, next: PendingPromoClaim?): Boolean = locked {
        if (readUnlocked() != expected) return@locked false
        if (next == null) {
            if (stateFile.exists() && !stateFile.delete()) return@locked false
            return@locked !stateFile.exists()
        }
        val text = next.storageJson()
        if (parsePendingPromoClaim(text) != next) return@locked false
        val temporary = File.createTempFile("promo-intent-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(stateFile)) return@locked false
            readUnlocked() == next
        } finally {
            temporary.delete()
        }
    }

    private fun readUnlocked(): PendingPromoClaim? {
        if (!stateFile.exists()) return null
        if (!stateFile.isFile || stateFile.length() > 4096L) throw IOException("PROMO_INTENT_STORAGE_UNAVAILABLE")
        val text = stateFile.inputStream().use { input ->
            val bytes = input.readNBytesCompat(4097)
            if (bytes.size > 4096) throw IOException("PROMO_INTENT_STORAGE_UNAVAILABLE")
            bytes.toString(Charsets.UTF_8)
        }
        return parsePendingPromoClaim(text) ?: throw IOException("PROMO_INTENT_STORAGE_UNAVAILABLE")
    }

    private fun <T> locked(action: () -> T): T = synchronized(processGuard) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use { action() } }
        } catch (_: Exception) {
            // Do not expose device/promo identifiers, filesystem paths or raw content.
            throw IOException("PROMO_INTENT_STORAGE_UNAVAILABLE")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(buffer, count, limit - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }

    private companion object { val guards = ConcurrentHashMap<String, Any>() }
}
