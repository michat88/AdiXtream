package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

object RepoProtector {
    // Fungsi untuk memecahkan kode Base64 menjadi URL asli
    fun decode(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    // URL Premium yang sudah diacak (Base64)
    // Asli: https://raw.githubusercontent.com/michat88/PremiumRepo/builds/repo.json
    val PREMIUM_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL21pY2hhdDg4L1ByZW1pdW1SZXBvL2J1aWxkcy9yZXBvLmpzb24="

    // URL Gratis yang sudah diacak (Base64)
    // Asli: https://raw.githubusercontent.com/michat88/Repo_Gratis/refs/heads/builds/repo.json
    val FREE_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL21pY2hhdDg4L1JlcG9fR3JhdGlzL3JlZnMvaGVhZHMvYnVpbGRzL3JlcG8uanNvbg=="
}
