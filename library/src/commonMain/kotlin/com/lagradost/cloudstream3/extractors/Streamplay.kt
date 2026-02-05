package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.getCaptchaToken
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URI

open class Streamplay : ExtractorApi() {
    override val name = "Streamplay"
    override val mainUrl = "https://streamplay.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val request = app.get(url, referer = referer)
        val redirectUrl = request.url
        val mainServer = URI(redirectUrl).let {
            "${it.scheme}://${it.host}"
        }
        val key = redirectUrl.substringAfter("embed-").substringBefore(".html")
        
        // Ambil token captcha jika ada
        val captchaKey = request.document.select("script")
            .find { it.data().contains("sitekey:") }?.data()
            ?.substringAfterLast("sitekey: '")?.substringBefore("',")
            
        val token = if (!captchaKey.isNullOrEmpty()) {
             getCaptchaToken(
                redirectUrl,
                captchaKey,
                referer = "$mainServer/"
            )
        } else {
            // Streamplay biasanya butuh token, tapi kita coba lanjut kalau null
            null 
        }

        // Post request untuk dapat link video
        app.post(
            "$mainServer/player-$key-488x286.html", 
            data = mapOf(
                "op" to "embed",
                "token" to (token ?: "")
            ),
            referer = redirectUrl,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Content-Type" to "application/x-www-form-urlencoded",
                "User-Agent" to com.lagradost.cloudstream3.USER_AGENT
            )
        ).document.select("script").find { script ->
            script.data().contains("eval(function(p,a,c,k,e,d)")
        }?.let {
            val unpacked = getAndUnpack(it.data())
            val data = unpacked.substringAfter("sources=[").substringBefore(",desc")
                .replace("file", "\"file\"")
                .replace("label", "\"label\"")
            
            // PERBAIKAN PENTING:
            // Yang lama: "[$data}]" (Salah, ada kurung kurawal '}')
            // Yang baru: "[$data]" (Benar)
            val jsonString = "[$data]"
            
            tryParseJson<List<Source>>(jsonString)?.forEach { res ->
                val fileUrl = res.file ?: return@forEach
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        fileUrl,
                        referer = "$mainServer/",
                        quality = when (res.label) {
                            "HD" -> Qualities.P720.value
                            "SD" -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                    )
                )
            }
        }
    }

    data class Source(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
    )
}
