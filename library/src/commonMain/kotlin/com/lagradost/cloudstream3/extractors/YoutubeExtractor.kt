package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class YoutubeShortLinkExtractor : YoutubeExtractor() {
    override val mainUrl = "https://youtu.be"
}

class YoutubeMobileExtractor : YoutubeExtractor() {
    override val mainUrl = "https://m.youtube.com"
}

class YoutubeNoCookieExtractor : YoutubeExtractor() {
    override val mainUrl = "https://www.youtube-nocookie.com"
}

open class YoutubeExtractor : ExtractorApi() {

    override val mainUrl = "https://www.youtube.com"
    override val name = "YouTube"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = extractYouTubeId(url)
        val watchUrl = "$mainUrl/watch?v=$videoId"

        val info = StreamInfo.getInfo(watchUrl)

        val isLive =
            info.streamType == StreamType.LIVE_STREAM
                    || info.streamType == StreamType.AUDIO_LIVE_STREAM
                    || info.streamType == StreamType.POST_LIVE_STREAM
                    || info.streamType == StreamType.POST_LIVE_AUDIO_STREAM

        if (isLive && info.hlsUrl != null) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube Live",
                    url = info.hlsUrl
                ) {
                    type = ExtractorLinkType.M3U8
                }
            )
        } else {
            processVideo(info, subtitleCallback, callback)
        }
    }

    private suspend fun processVideo(
        info: StreamInfo,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        var hasStreams = false

        // STRATEGI BARU: Kita abaikan Muxed (karena sering mentok di 360p)
        // Kita gunakan video terpisah, TAPI dengan batas resolusi maksimal 720p!
        
        // Memfilter video agar yang dilempar ke ExoPlayer hanya yang resolusinya <= 720
        val separatedVideoStreams = info.videoOnlyStreams.orEmpty().filter { stream ->
            stream.height <= 720 
        }
        
        // Ambil link audio untuk dipasangkan nanti
        val audioStreams = info.audioStreams.orEmpty()

        // Lempar link video yang sudah difilter (max 720p) ke player
        separatedVideoStreams.forEach { video ->
            hasStreams = true
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube HD ${normalizeCodec(video.codec)}",
                    url = video.content
                ) {
                    quality = video.height
                    // Gabungkan dengan audio secara manual
                    audioTracks = audioStreams.map { newAudioFile(it.content) }
                }
            )
        }

        if (!hasStreams) return false

        info.subtitles.forEach { subtitle ->
            subtitleCallback(
                newSubtitleFile(
                    lang = subtitle.displayLanguageName
                        ?: subtitle.languageTag
                        ?: "Unknown",
                    url = subtitle.content
                )
            )
        }

        return true
    }

    // ---------------- HELPERS ----------------

    private fun extractYouTubeId(url: String): String {
        val regex = Regex(
            "(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/(?:.*v=|v/|u/\\w/|embed/|shorts/|live/))([\\w-]{11})"
        )
        return regex.find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid YouTube URL: $url")
    }

    private fun normalizeCodec(codec: String?): String {
        if (codec.isNullOrBlank()) return ""

        val c = codec.lowercase()

        return when {
            c.startsWith("av01") -> "AV1"
            c.startsWith("vp9") -> "VP9"
            c.startsWith("avc1") || c.startsWith("h264") -> "H264"
            c.startsWith("hev1") || c.startsWith("hvc1") || c.startsWith("hevc") -> "H265"
            else -> codec.substringBefore('.').uppercase()
        }
    }
}
