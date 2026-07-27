package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.BuildConfig

object RepoProtector {
    
    // KUNCI DIRAKIT SECARA DINAMIS (ANTI-MODDER)
    private val XOR_KEY: String
        get() {
            return try {
                val builder = StringBuilder()
                for (obfuscatedChar in BuildConfig.OBFUSCATED_KEY) {
                    builder.append((obfuscatedChar - 7).toChar())
                }
                builder.toString()
            } catch (e: Exception) {
                "DefaultKeyAman"
            }
        }

    /**
     * Fungsi untuk membuka gembok Hexadecimal + XOR
     */
    private fun xorDecrypt(hexInput: String): String {
        val currentKey = XOR_KEY
        if (hexInput.isEmpty() || currentKey.isEmpty()) return ""
        return try {
            val encryptedBytes = ByteArray(hexInput.length / 2)
            for (i in encryptedBytes.indices) {
                encryptedBytes[i] = hexInput.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            
            val keyBytes = currentKey.toByteArray(StandardCharsets.UTF_8)
            val decryptedBytes = ByteArray(encryptedBytes.size)
            for (i in encryptedBytes.indices) {
                decryptedBytes[i] = (encryptedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Fungsi utama yang dipanggil oleh aplikasi (SMART DECODE)
     */
    fun decode(encodedHex: String): String {
        return try {
            if (encodedHex.isEmpty()) return ""
            
            // Langkah A: Buka gembok XOR
            val decrypted = xorDecrypt(encodedHex).trim()
            
            // Jika hasil XOR sudah berupa URL asli (dimulai dengan http/https), langsung kembalikan!
            if (decrypted.startsWith("http://") || decrypted.startsWith("https://")) {
                return decrypted
            }
            
            // Langkah B: Jika bukan URL langsung, coba decode Base64
            val bytes = Base64.decode(decrypted, Base64.DEFAULT)
            val finalUrl = String(bytes, StandardCharsets.UTF_8).trim()
            
            if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                finalUrl
            } else {
                decrypted // Fallback ke teks hasil XOR
            }
        } catch (e: Exception) {
            // Jika Base64 gagal, kembalikan hasil XOR jika valid
            try {
                xorDecrypt(encodedHex).trim()
            } catch (_: Exception) {
                ""
            }
        }
    }

    // === DATA DIAMBIL DARI BUILDCONFIG (HEX-XOR) ===
    val PREMIUM_REPO_ENCODED = BuildConfig.PREMIUM_REPO_ENCODED
    val FREE_REPO_ENCODED = BuildConfig.FREE_REPO_ENCODED
    val FIREBASE_URL_ENCODED = BuildConfig.FIREBASE_URL_ENCODED
}
