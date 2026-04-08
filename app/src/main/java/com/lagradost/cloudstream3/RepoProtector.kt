package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

object RepoProtector {
    
    /**
     * Fungsi untuk mengubah teks acak (Base64) kembali menjadi URL asli.
     * Menggunakan blok try-catch agar aplikasi tidak crash jika terjadi error decoding.
     */
    fun decode(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // === DATA URL YANG DISANDIKAN (ENCODED) ===
    
    // Repo Premium (Amanhnb88)
    val PREMIUM_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2FtYW5obmI4OC9QcmVtaXVtX1JlcG8vYnVpbGRzL3JlcG8uanNvbg=="

    // Repo Gratis (Michat88)
    val FREE_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL21pY2hhdDg4L1JlcG9fR3JhdGlzL3JlZnMvaGVhZHMvYnVpbGRzL3JlcG8uanNvbg=="
    
    // URL Firebase AdiXtream
    val FIREBASE_URL_ENCODED = "aHR0cHM6Ly9hZGl4dHJlYW0tcHJlbWl1bS1kZWZhdWx0LXJ0ZGIuYXNpYS1zb3V0aGVhc3QxLmZpcmViYXNlZGF0YWJhc2UuYXBwLw=="
}
