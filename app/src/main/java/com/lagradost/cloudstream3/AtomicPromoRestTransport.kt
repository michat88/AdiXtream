package com.lagradost.cloudstream3

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded, unlogged REST calls; never carries an Admin token or follows redirects. */
internal class AtomicPromoRestTransport(baseUrl: String) : AtomicPromoTransport {
    private val base = baseUrl.toHttpUrl().also {
        require(it.scheme == "https" || (it.scheme == "http" && it.host == "127.0.0.1"))
        require(it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null)
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override suspend fun user(deviceId: String): AtomicPromoResponse {
        require(validPromoDeviceId(deviceId))
        return exchange("users/$deviceId.json")
    }

    override suspend fun promo(code: String): AtomicPromoResponse {
        require(canonicalPromoCode(code) == code)
        return exchange("promo_codes/$code.json")
    }

    override suspend fun commit(patch: String): AtomicPromoResponse = exchange(".json", patch)

    private fun exchange(path: String, patch: String? = null): AtomicPromoResponse {
        val url = base.newBuilder().addPathSegments(path).build()
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache")
        if (patch != null) {
            val bytes = patch.toByteArray(Charsets.UTF_8)
            val body = object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                // retryOnConnectionFailure(false) alone does not stop 503/Retry-After: 0.
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            }
            // POST override remains compatible with the app's existing REST mechanism.
            request.header("X-HTTP-Method-Override", "PATCH")
                .post(body)
        }
        return client.newCall(request.build()).execute().use { response ->
            val body = response.body ?: throw IOException("PROMO_RESPONSE_MISSING")
            val bytes = body.source().apply {
                if (request(MAX_BODY_BYTES + 1L)) throw IOException("PROMO_RESPONSE_TOO_LARGE")
            }.readByteArray()
            val serverTime = response.headers.getDate("Date")?.time ?: 0L
            AtomicPromoResponse(response.code, bytes.toString(Charsets.UTF_8), serverTime)
        }
    }

    private companion object { const val MAX_BODY_BYTES = 256L * 1024L }
}
