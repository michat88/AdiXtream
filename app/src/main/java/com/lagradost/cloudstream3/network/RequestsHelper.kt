package com.lagradost.cloudstream3.network

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ignoreAllSSLErrors
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security

// ==========================================
// MODIFIKASI ADIXTREAM
// Daftar DNS provider yang valid (sinkron dengan dns_pref_values di arrays.xml).
// Index 3 (OpenDns) sengaja diskip karena provider-nya tidak aktif.
// ==========================================
private val VALID_DNS_PROVIDERS = setOf(1, 2, 4, 5, 6, 7, 8)
private const val DEFAULT_DNS_PROVIDER = 1 // Google DNS

/**
 * Resolusi nilai DNS dari SharedPreferences.
 * - First install / pref belum ada            -> DEFAULT_DNS_PROVIDER (Google)
 * - Legacy user dengan nilai 0 (None)         -> DEFAULT_DNS_PROVIDER (Google)
 * - Nilai di luar VALID_DNS_PROVIDERS         -> DEFAULT_DNS_PROVIDER (Google)
 * - Nilai valid (1,2,4,5,6,7,8)               -> nilai user
 */
private fun Context.resolveDnsProvider(): Int {
    val raw = PreferenceManager.getDefaultSharedPreferences(this)
        .getInt(getString(R.string.dns_pref), DEFAULT_DNS_PROVIDER)
    return if (raw in VALID_DNS_PROVIDERS) raw else DEFAULT_DNS_PROVIDER
}

// Backwards compatible constructor, mark as deprecated later
fun Requests.initClient(context: Context) {
    this.baseClient = buildDefaultClient(context)
}

/** Only use ignoreSSL if you know what you are doing*/
@Prerelease
fun Requests.initClient(context: Context, ignoreSSL: Boolean = false) {
    this.baseClient = buildDefaultClient(context, ignoreSSL)
}

// Backwards compatible constructor, mark as deprecated later
fun buildDefaultClient(context: Context): OkHttpClient {
    return buildDefaultClient(context, false)
}

/** Only use ignoreSSL if you know what you are doing*/
@Prerelease
fun buildDefaultClient(context: Context, ignoreSSL: Boolean = false): OkHttpClient {
    safe { Security.insertProviderAt(Conscrypt.newProvider(), 1) }

    val dns = context.resolveDnsProvider()

    val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .apply {
            if (ignoreSSL) {
                ignoreAllSSLErrors()
            }
        }
        .cache(
            // Note that you need to add a ResponseInterceptor to make this 100% active.
            // The server response dictates if and when stuff should be cached.
            Cache(
                directory = File(context.cacheDir, "http_cache"),
                maxSize = 50L * 1024L * 1024L // 50 MiB
            )
        ).apply {
            when (dns) {
                1 -> addGoogleDns()
                2 -> addCloudFlareDns()
//                3 -> addOpenDns()
                4 -> addAdGuardDns()
                5 -> addDNSWatchDns()
                6 -> addQuad9Dns()
                7 -> addDnsSbDns()
                8 -> addCanadianShieldDns()
            }
        }
        // Needs to be build as otherwise the other builders will change this object
        .build()
    return baseClient
}

private val DEFAULT_HEADERS = mapOf("user-agent" to USER_AGENT)

/**
 * Set headers > Set cookies > Default headers > Default Cookies
 * TODO REMOVE AND REPLACE WITH NICEHTTP
 */
fun getHeaders(
    headers: Map<String, String>,
    cookie: Map<String, String>
): Headers {
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap)
    return tempHeaders.toHeaders()
}
