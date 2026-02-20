package com.michat88

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class RebahinProvider : MainAPI() {
    override var mainUrl = "https://rebahinxxi3.biz"
    override var name = "Rebahin"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Memasukkan semua kategori berdasarkan menu di HTML yang kamu berikan
    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "Film Terbaru",
        "$mainUrl/series/page/" to "Serial TV",
        "$mainUrl/genre/animation/page/" to "Anime",
        "$mainUrl/genre/westseries/page/" to "Series Barat",
        "$mainUrl/genre/drama-jepang/page/" to "Drama Jepang",
        "$mainUrl/genre/drama-korea/page/" to "Drama Korea",
        "$mainUrl/genre/drama-china/page/" to "Drama Mandarin",
        "$mainUrl/genre/thailand-series/page/" to "Series Thailand",
        "$mainUrl/genre/series-indonesia/page/" to "Series Indonesia",
        "$mainUrl/genre/action/page/" to "Genre Action",
        "$mainUrl/genre/horror/page/" to "Genre Horror",
        "$mainUrl/genre/comedy/page/" to "Genre Comedy",
        "$mainUrl/genre/romance/page/" to "Genre Romance",
        "$mainUrl/genre/sci-fi/page/" to "Genre Sci-Fi"
    )

    // Fungsi utama untuk memuat halaman awal berdasarkan kategori
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Struktur URL pagination biasanya adalah domain.com/kategori/page/2/
        val url = if (page == 1) request.data.replace("page/", "") else request.data + page
        val document = app.get(url).document

        // Mengambil semua elemen film dari HTML (berdasarkan class ml-item)
        val home = document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // Fungsi untuk mencari film berdasarkan kata kunci
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document

        return document.select("div.ml-item").mapNotNull {
            it.toSearchResult()
        }
    }

    // Fungsi bantuan untuk mengubah HTML <div class="ml-item"> menjadi objek Cloudstream
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val title = this.selectFirst("a")?.attr("title") ?: this.selectFirst("span.mli-info h2")?.text() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        val quality = this.selectFirst("span.mli-quality")?.text()
        
        // Membedakan apakah ini TV Series atau Movie berdasarkan URL-nya
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

    // Fungsi untuk memuat detail film/series (Sinopsis, Rating, Trailer, dll)
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.mvic-desc h3")?.text() ?: ""
        val poster = document.selectFirst("div.mvic-thumb img")?.attr("src")
        val plot = document.selectFirst("div.desc")?.text() ?: document.selectFirst("div.sinopsis-indo")?.text()
        val year = document.selectFirst("a[href*=/release-year/]")?.text()?.toIntOrNull()
        val rating = document.selectFirst("span.mli-rating")?.ownText()?.trim()

        val isTvSeries = url.contains("/series/") || url.contains("/tv/")

        if (isTvSeries) {
            // Logika untuk mengambil daftar episode di TV Series
            val episodes = mutableListOf<Episode>()
            document.select("div#list-eps a.btn-eps").forEach { epsNode ->
                val epUrl = epsNode.attr("href")
                val epName = epsNode.text()
                episodes.add(
                    Episode(
                        data = epUrl,
                        name = epName
                    )
                )
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating?.toRatingInt()
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.rating = rating?.toRatingInt()
            }
        }
    }

    // Fungsi untuk mengambil link video player
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // CATATAN PENTING:
        // Di sini kita membutuhkan kode spesifik untuk mengekstrak iframe player dari Rebahin.
        // Biasanya Rebahin menggunakan AJAX/API untuk memuat videonya (seperti admin-ajax.php).
        // Untuk tahap awal, aku asumsikan kita mengambil link dari iframe yang ada di dalam elemen #play-video
        
        val doc = app.get(data).document
        
        // Cari iframe player
        val iframeSrc = doc.selectFirst("div.modal-body-trailer iframe")?.attr("src") 
            ?: doc.selectFirst("iframe")?.attr("src")

        if (iframeSrc != null && iframeSrc.startsWith("http")) {
            // Gunakan built-in extractor Cloudstream untuk melakukan resolve pada link iframe
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        
        return true
    }
}
