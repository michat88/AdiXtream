package com.lagradost.cloudstream3

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** Private no-backup intent. No token or entitlement is stored; unlock codes remain private. */
internal class CentralPremiumIntentFileStore(privateDirectory: File) : CentralPremiumIntentStore {
    private val directory = privateDirectory.canonicalFile
    private val stateFile = File(directory, "adixtream-central-intent-v2.json")
    private val lockFile = File(directory, "adixtream-central-intent-v2.lock")
    private val processGuard = guards.getOrPut(lockFile.path) { Any() }

    override fun read(): CentralPremiumIntent? = locked { readUnlocked() }

    override fun compareAndSet(expected: CentralPremiumIntent?, next: CentralPremiumIntent?): Boolean = locked {
        if (readUnlocked() != expected) return@locked false
        if (next == null) {
            if (stateFile.exists() && !stateFile.delete()) return@locked false
            return@locked !stateFile.exists()
        }
        val text = next.json()
        if (CentralPremiumIntent.parse(text) != next) return@locked false
        val temporary = File.createTempFile("central-intent-", ".tmp", directory)
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

    private fun readUnlocked(): CentralPremiumIntent? {
        if (!stateFile.exists()) return null
        if (!stateFile.isFile || stateFile.length() > 4096L) throw IOException("CENTRAL_INTENT_STORAGE_UNAVAILABLE")
        val text = stateFile.inputStream().use { input ->
            val bytes = input.readNBytesCompat(4097)
            if (bytes.size > 4096) throw IOException("CENTRAL_INTENT_STORAGE_UNAVAILABLE")
            bytes.toString(Charsets.UTF_8)
        }
        return CentralPremiumIntent.parse(text)
    }

    private fun <T> locked(action: () -> T): T = synchronized(processGuard) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
            RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use { action() } }
        } catch (_: Exception) {
            // Do not expose device/promo identifiers, filesystem paths or raw content.
            throw IOException("CENTRAL_INTENT_STORAGE_UNAVAILABLE")
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
