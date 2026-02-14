package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

object RepoProtector {
    // Fungsi untuk memecahkan kode Base64 menjadi URL asli
    fun decode(encoded: String): String {
        return try {
            // Flag NO_WRAP penting agar string panjang tidak error
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // --- REPO PREMIUM BARU (amanhnb88) ---
    // URL Asli: https://raw.githubusercontent.com/amanhnb88/Premium_Repo/builds/repo.json
    val PREMIUM_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2FtYW5obmI4OC9QcmVtaXVtX1JlcG8vYnVpbGRzL3JlcG8uanNvbg=="

    // --- REPO GRATIS (Tetap yang lama) ---
    // URL Asli: https://raw.githubusercontent.com/michat88/Repo_Gratis/refs/heads/builds/repo.json
    val FREE_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL21pY2hhdDg4L1JlcG9fR3JhdGlzL3JlZnMvaGVhZHMvYnVpbGRzL3JlcG8uanNvbg=="
}
