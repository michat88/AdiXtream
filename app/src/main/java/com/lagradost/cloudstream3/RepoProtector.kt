package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.BuildConfig

object RepoProtector {
    
    // KUNCI DIRAKIT SECARA DINAMIS (ANTI-MODDER)
    // Tidak ada lagi teks rahasia di dalam file DEX/Smali
    private val XOR_KEY: String
        get() {
            val builder = StringBuilder()
            // Kita balikkan jebakan matematikanya (dikurangi 7)
            for (obfuscatedChar in BuildConfig.OBFUSCATED_KEY) {
                builder.append((obfuscatedChar - 7).toChar())
            }
            return builder.toString()
        }

    /**
     * Fungsi untuk membuka gembok Hexadecimal + XOR kembali menjadi teks Base64
     */
    private fun xorDecrypt(hexInput: String): String {
        val currentKey = XOR_KEY
        if (hexInput.isEmpty() || currentKey.isEmpty()) return ""
        return try {
            // 1. Ubah teks Hexadecimal kembali menjadi ByteArray
            val encryptedBytes = ByteArray(hexInput.length / 2)
            for (i in encryptedBytes.indices) {
                encryptedBytes[i] = hexInput.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            
            // 2. Lakukan operasi XOR Decrypt dengan Kunci yang dirakit dinamis
            val keyBytes = currentKey.toByteArray(StandardCharsets.UTF_8)
            val decryptedBytes = ByteArray(encryptedBytes.size)
            for (i in encryptedBytes.indices) {
                decryptedBytes[i] = (encryptedBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
            }
            
            // 3. Kembalikan menjadi teks utuh (Base64 asli)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Fungsi utama yang dipanggil oleh aplikasi
     */
    fun decode(encodedHex: String): String {
        return try {
            if (encodedHex.isEmpty()) return ""
            
            // Langkah A: Buka gembok XOR untuk mendapatkan Base64-nya
            val base64String = xorDecrypt(encodedHex)
            
            // Langkah B: Decode Base64 menjadi URL asli
            val bytes = Base64.decode(base64String, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // === DATA DIAMBIL DARI BUILDCONFIG (HEX-XOR) ===
    val PREMIUM_REPO_ENCODED = BuildConfig.PREMIUM_REPO_ENCODED
    val FREE_REPO_ENCODED = BuildConfig.FREE_REPO_ENCODED
    val FIREBASE_URL_ENCODED = BuildConfig.FIREBASE_URL_ENCODED
}
