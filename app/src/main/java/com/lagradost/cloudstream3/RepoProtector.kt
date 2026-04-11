package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.BuildConfig

object RepoProtector {
    
    /**
     * Fungsi untuk mengubah teks acak (Base64) kembali menjadi URL asli.
     * Menggunakan blok try-catch agar aplikasi tidak crash jika terjadi error decoding.
     */
    fun decode(encoded: String): String {
        return try {
            // Mencegah error jika variabel dari BuildConfig ternyata kosong
            if (encoded.isEmpty()) return ""
            
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // === DATA DIAMBIL DARI BUILDCONFIG (SANGAT AMAN DI GITHUB) ===
    // Nilai aslinya disuntikkan dari GitHub Secrets atau local.properties saat proses build APK
    
    val PREMIUM_REPO_ENCODED = BuildConfig.PREMIUM_REPO_ENCODED
    val FREE_REPO_ENCODED = BuildConfig.FREE_REPO_ENCODED
    val FIREBASE_URL_ENCODED = BuildConfig.FIREBASE_URL_ENCODED
}
