package com.michat88

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Movie, 
        TvType.TvSeries, 
        TvType.Anime, 
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film Terbaru",
        "$mainUrl/series/page/" to "Serial TV",
        "$mainUrl/genre/animation/page/" to "Anime",
        "$mainUrl/genre/westseries/page/" to "Series Barat",
        "$mainUrl/genre/drama-jepang/page/" to "Drama Jepang",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/drama-china/page/" to "Drama Mandarin",
        "$mainUrl/genre/thailand-series/page/" to "Series Thailand",
        "$mainUrl/genre/series-indonesia/page/" to "Series Indonesia"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data.replace("page/", "") else request.data + page
        val document = app.get(url).document

        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title") ?: this.selectFirst("span.mli-info h2")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-original")
        val quality = this.selectFirst("span.mli-quality")?.text()
        
        val isTvSeries = href.contains("/series/") || href.contains("/tv/") || href.contains("/episode/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[itemprop=name]")?.attr("content") 
            ?: document.selectFirst("h3")?.text() ?: "Unknown"
            
        val poster = document.selectFirst("meta[itemprop=image]")?.attr("content") 
        val year = document.selectFirst("meta[itemprop=datePublished]")?.attr("content")?.substringBefore("-")?.toIntOrNull()
        
        val plot = document.select("div.desc-des-pendek p").joinToString("\n") { it.text() }.trim().ifEmpty { 
            document.selectFirst("div.desc")?.text() ?: ""
        }
        
        val ratingText = document.selectFirst("div.averagerate")?.text()?.toFloatOrNull()
        val ratingInt = ratingText?.let { (it * 20).toInt() }

        val tagsList = document.select("span[itemprop=genre]").map { it.text() }

        val isTvSeries = url.contains("/series/") || document.selectFirst("div#list-eps") != null

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val epsMap = mutableMapOf<Int, MutableList<String>>()
            
            val seasonMatch = Regex("""Season\s+(\d+)""", RegexOption.IGNORE_CASE).find(title)
            val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()

            document.select("div#list-eps a.btn-eps").forEach { epsNode ->
                val encodedIframe = epsNode.attr("data-iframe")
                if (encodedIframe.isNotBlank()) {
                    val epName = epsNode.text()
                    val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    val decodedUrl = String(Base64.decode(encodedIframe, Base64.DEFAULT))
                    
                    if (epsMap[epNum] == null) {
                        epsMap[epNum] = mutableListOf()
                    }
                    epsMap[epNum]?.add(decodedUrl)
                }
            }
            
            epsMap.forEach { (epNum, urlList) ->
                episodes.add(
                    newEpisode(urlList.toJson()) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                        this.season = seasonNum
                    }
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = ratingInt
                this.tags = tagsList
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = ratingInt
                this.tags = tagsList
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        if (data.startsWith("[")) {
            val urls = tryParseJson<List<String>>(data)
            urls?.forEach { url ->
                extractVideoLinks(url, mainUrl, subtitleCallback, callback)
            }
            return true
        }

        val document = app.get(data).document
        val servers = document.select("div.server-wrapper div.server")

        servers.forEach { server ->
            val encodedIframe = server.attr("data-iframe")
            if (encodedIframe.isNotBlank()) {
                try {
                    val decodedUrl = String(Base64.decode(encodedIframe, Base64.DEFAULT))
                    extractVideoLinks(decodedUrl, data, subtitleCallback, callback)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }

    private suspend fun extractVideoLinks(
        url: String, 
        referer: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return

        try {
            var finalUrl = url
            
            if (finalUrl.contains("short.icu")) {
                // Bypass redirect short.icu untuk mendapatkan URL tujuan asli
                finalUrl = app.get(finalUrl, allowRedirects = true).url 
            }

            // 1. Coba berikan ke Extractor bawaan Cloudstream
            loadExtractor(finalUrl, referer, subtitleCallback, callback)

            // 2. Ekstrak rahasia dari Server Privat Rebahin (IP based) / AbyssCDN
            val ipRegex = Regex("""https?://\d+\.\d+\.\d+\.\d+/.*""")
            
            if (ipRegex.matches(finalUrl) || finalUrl.contains("abysscdn.com")) {
                val embedText = app.get(finalUrl, referer = referer).text
                val unpacked = getAndUnpack(embedText)
                
                // PERBAIKAN REGEX: Hanya mencari 'file:' atau 'source:', DILARANG MENCARI 'src='
                val videoRegex = """["']?(?:file|source)["']?\s*:\s*["'](https?://[^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
                
                val allMatches = videoRegex.findAll(unpacked).map { it.groupValues[1] }.toList() + 
                                 videoRegex.findAll(embedText).map { it.groupValues[1] }.toList()
                
                // Saring URL agar tidak mengambil file subtitle/gambar
                val streamUrl = allMatches.firstOrNull { 
                    !it.endsWith(".vtt") && !it.endsWith(".srt") && !it.endsWith(".jpg") && !it.endsWith(".png") 
                }
                
                streamUrl?.let {
                    val isM3u = it.contains(".m3u8") || it.contains("/stream/") || it.contains("hls")
                    val sourceName = if (finalUrl.contains("abysscdn")) "AbyssCDN (HD)" else "Rebahin VIP"
                    
                    callback.invoke(
                        ExtractorLink(
                            source = this.name,
                            name = sourceName,
                            url = it,
                            referer = finalUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
