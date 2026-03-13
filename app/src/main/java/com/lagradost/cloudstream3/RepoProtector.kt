package com.lagradost.cloudstream3.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

object RepoProtector {
    
    fun decode(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    val PREMIUM_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2FtYW5obmI4OC9QcmVtaXVtX1JlcG8vYnVpbGRzL3JlcG8uanNvbg=="

    val FREE_REPO_ENCODED = "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL21pY2hhdDg4L1JlcG9fR3JhdGlzL3JlZnMvaGVhZHMvYnVpbGRzL3JlcG8uanNvbg=="
}
