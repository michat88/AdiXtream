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

        // 1. Ambil stream MUXED (Video + Audio gabung, biasanya mentok di 360p atau 720p). Sangat ringan!
        val muxedStreams = info.videoStreams.orEmpty()
        
        // 2. Ambil stream TERPISAH (Video Only), tapi kita filter MAKSIMAL 1080p agar tidak bikin player macet.
        val separatedVideoStreams = info.videoOnlyStreams.orEmpty().filter { it.height <= 1080 }
        
        // Audio stream untuk digabungkan dengan separatedVideoStreams
        val audioStreams = info.audioStreams.orEmpty()

        var hasStreams = false

        // --- MASUKKAN VIDEO MUXED ---
        muxedStreams.forEach { video ->
            hasStreams = true
            callback(
                newExtractorLink(
                    source = name,
                    // Kita beri nama "Muxed" agar nanti mudah dilacak di logcat jika player memilih ini
                    name = "YouTube Muxed ${normalizeCodec(video.codec)}",
                    url = video.content
                ) {
                    quality = video.height
                    // Tidak perlu map audioTracks karena sudah gabung di dalam video
                }
            )
        }

        // --- MASUKKAN VIDEO TERPISAH (Max 1080p) ---
        separatedVideoStreams.forEach { video ->
            hasStreams = true
            callback(
                newExtractorLink(
                    source = name,
                    name = "YouTube HD ${normalizeCodec(video.codec)}",
                    url = video.content
                ) {
                    quality = video.height
                    // Harus pakai audioTracks karena videonya bisu
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
