@file:Suppress("DEPRECATION")

package com.lagradost.cloudstream3.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer.STATE_ENABLED
import androidx.media3.exoplayer.Renderer.STATE_STARTED
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.ui.SubtitleView
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.ui.player.CustomDecoder.Companion.fixSubtitleAlignment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.subtitles.SaveCaptionStyle
import com.lagradost.cloudstream3.ui.subtitles.SubtitlesFragment.Companion.applyStyle
import com.lagradost.cloudstream3.utils.AppContextUtils.isUsingMobileData
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.runOnMainThread
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import com.lagradost.cloudstream3.utils.DrmExtractorLink
import com.lagradost.cloudstream3.utils.EpisodeSkip
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PLAYREADY_UUID
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.WIDEVINE_UUID
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import org.chromium.net.CronetEngine
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession

const val TAG = "CS3ExoPlayer"
const val PREFERRED_AUDIO_LANGUAGE_KEY = "preferred_audio_language"

/** toleranceBeforeUs – The maximum time that the actual position seeked to may precede the
 * requested seek position, in microseconds. Must be non-negative. */
const val toleranceBeforeUs = 300_000L

/**
 * toleranceAfterUs – The maximum time that the actual position seeked to may exceed the requested
 * seek position, in microseconds. Must be non-negative.
 */
const val toleranceAfterUs = 300_000L

@OptIn(UnstableApi::class)
class CS3IPlayer : IPlayer {
    private var playerListener: Player.Listener? = null
    private var isPlaying = false
    private var exoPlayer: ExoPlayer? = null
        set(value) {
            // If the old value is not null then the player has not been properly released.
            debugAssert(
                { field != null && value != null },
                { "Previous player instance should be released!" })
            field = value
        }

    var cacheSize = 0L
    var simpleCacheSize = 0L
    var videoBufferMs = 0L

    val imageGenerator = IPreviewGenerator.new()

    private val seekActionTime = 30000L
    private val isMediaSeekable
        get() = exoPlayer?.let {
            it.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) && it.isCurrentMediaItemSeekable
        } ?: false

    private var ignoreSSL: Boolean = true
    private var playBackSpeed: Float = 1.0f

    private var lastMuteVolume: Float = 1.0f

    private var currentLink: ExtractorLink? = null
    private var currentDownloadedFile: ExtractorUri? = null
    private var hasUsedFirstRender = false

    private var currentWindow: Int = 0
    private var playbackPosition: Long = 0

    private val subtitleHelper = PlayerSubtitleHelper()

    /** If we want to play the audio only in the background when the app is not open */
    private var isAudioOnlyBackground = false

    /**
     * This is a way to combine the MediaItem and its duration for the concatenating MediaSource.
     * @param durationUs does not matter if only one slice is present, since it will not concatenate
     * */
    data class MediaItemSlice(
        val mediaItem: MediaItem,
        val durationUs: Long,
        val drm: DrmMetadata? = null
    )

    data class DrmMetadata(
        val kid: String? = null,
        val key: String? = null,
        val uuid: UUID,
        val kty: String? = null,
        val licenseUrl: String? = null,
        val keyRequestParameters: HashMap<String, String>,
    )

    override fun getDuration(): Long? = exoPlayer?.duration
    override fun getPosition(): Long? = exoPlayer?.currentPosition
    override fun getIsPlaying(): Boolean = isPlaying
    override fun getPlaybackSpeed(): Float = playBackSpeed

    /**
     * Tracks reported to be used by exoplayer, since sometimes it has a mind of it's own when selecting subs.
     * String = id (without exoplayer track number)
     * Boolean = if it's active
     * */
    private var playerSelectedSubtitleTracks = listOf<Pair<String, Boolean>>()
    private var requestedListeningPercentages: List<Int>? = null

    private var eventHandler: ((PlayerEvent) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun event(event: PlayerEvent) {
        // Ensure that all work is done on the main looper, aka main thread
        if (Looper.myLooper() == mainHandler.looper) {
            eventHandler?.invoke(event)
        } else {
            mainHandler.post {
                eventHandler?.invoke(event)
            }
        }
    }

    /**
     * As initCallbacks and releaseCallbacks must always be done,
     * we use this to say that the player is in use.
     * */
    @Volatile
    var isPlayerActive: Boolean = false

    override fun releaseCallbacks() {
        eventHandler = null
        if (isPlayerActive) {
            isPlayerActive = false
            activePlayers -= 1
            releaseCronetEngine()
        }
    }

    override fun initCallbacks(
        eventHandler: ((PlayerEvent) -> Unit),
        requestedListeningPercentages: List<Int>?,
    ) {
        this.requestedListeningPercentages = requestedListeningPercentages
        this.eventHandler = eventHandler
        if (!isPlayerActive) {
            isPlayerActive = true
            activePlayers += 1
        }
    }

    // I know, this is not a perfect solution, however it works for fixing subs
    private fun reloadSubs() {
        exoPlayer?.applicationLooper?.let {
            try {
                Handler(it).post {
                    try {
                        seekTime(1L, source = PlayerEventSource.Player)
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    fun String.stripTrackId(): String {
        return this.replace(Regex("""^\d+:"""), "")
    }

    fun initSubtitles(subView: SubtitleView?, subHolder: FrameLayout?, style: SaveCaptionStyle?) {
        subtitleHelper.initSubtitles(subView, subHolder, style)
    }

    override fun getPreview(fraction: Float): Bitmap? {
        return imageGenerator.getPreviewImage(fraction)
    }

    override fun hasPreview(): Boolean {
        return imageGenerator.hasPreview()
    }

    override fun loadPlayer(
        context: Context,
        sameEpisode: Boolean,
        link: ExtractorLink?,
        data: ExtractorUri?,
        startPosition: Long?,
        subtitles: Set<SubtitleData>,
        subtitle: SubtitleData?,
        autoPlay: Boolean?,
        preview: Boolean,
    ) {
        Log.i(TAG, "loadPlayer")
        if (sameEpisode) {
            saveData()
        } else {
            currentSubtitles = subtitle
            playbackPosition = 0
        }

        startPosition?.let {
            playbackPosition = it
        }

        // we want autoplay because of TV and UX
        isPlaying = autoPlay ?: isPlaying

        // release the current exoplayer and cache
        releasePlayer()

        if (link != null) {
            // only video support atm
            (imageGenerator as? PreviewGenerator)?.let { gen ->
                if (preview) {
                    gen.load(link, sameEpisode)
                } else {
                    gen.clear(sameEpisode)
                }
            }

            loadOnlinePlayer(context, link)
        } else if (data != null) {
            (imageGenerator as? PreviewGenerator)?.let { gen ->
                if (preview) {
                    gen.load(context, data, sameEpisode)
                } else {
                    gen.clear(sameEpisode)
                }
            }
            loadOfflinePlayer(context, data)
        } else {
            throw IllegalArgumentException("Requires link or uri")
        }

    }

    override fun setActiveSubtitles(subtitles: Set<SubtitleData>) {
        Log.i(TAG, "setActiveSubtitles ${subtitles.size}")
        subtitleHelper.setAllSubtitles(subtitles)
    }

    private var currentSubtitles: SubtitleData? = null

    private fun List<Tracks.Group>.getTrack(id: String?): Pair<TrackGroup, Int>? {
        if (id == null) return null
        // This beast of an expression does:
        // 1. Filter all audio tracks
        // 2. Get all formats in said audio tacks
        // 3. Gets all ids of the formats
        // 4. Filters to find the first audio track with the same id as the audio track we are looking for
        // 5. Returns the media group and the index of the audio track in the group
        return this.firstNotNullOfOrNull { group ->
            (0 until group.mediaTrackGroup.length).map {
                group.getTrackFormat(it) to it
            }.firstOrNull {
                // The format id system is "trackNumber:trackID"
                // The track number is not generated by us so we filter it out
                it.first.id?.stripTrackId() == id
            }
                ?.let { group.mediaTrackGroup to it.second }
        }
    }

    override fun setMaxVideoSize(width: Int, height: Int, id: String?) {
        if (id != null) {
            val videoTrack =
                exoPlayer?.currentTracks?.groups?.filter { it.type == TRACK_TYPE_VIDEO }
                    ?.getTrack(id)

            if (videoTrack != null) {
                exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
                    ?.buildUpon()
                    ?.setOverrideForType(
                        TrackSelectionOverride(
                            videoTrack.first,
                            videoTrack.second
                        )
                    )
                    ?.build()
                    ?: return
                return
            }
        }

        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
            ?.buildUpon()
            ?.setMaxVideoSize(width, height)
            ?.build()
            ?: return
    }

    override fun setPreferredAudioTrack(trackLanguage: String?, id: String?, formatIndex: Int?) {
        preferredAudioTrackLanguage = trackLanguage
        id?.let { trackId ->
            val trackFormatIndex = formatIndex ?: 0
            exoPlayer?.currentTracks?.groups
                ?.filter { it.type == TRACK_TYPE_AUDIO }
                ?.find { group ->
                    group.getFormats().any { (format, _) ->
                        format.id == trackId
                    }
                }
                ?.let { group ->
                    exoPlayer?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackFormatIndex))
                        ?.build()
                }
                ?.let { newParams ->
                    exoPlayer?.trackSelectionParameters = newParams
                    return
                }
        }
        // Fallback to language-based selection
        exoPlayer?.trackSelectionParameters = exoPlayer?.trackSelectionParameters
            ?.buildUpon()
            ?.setPreferredAudioLanguage(trackLanguage)
            ?.build() ?: return
    }

    /**
     * Gets all supported formats in a list
     * */
    private fun List<Tracks.Group>.getFormats(): List<Pair<Format, Int>> {
        return this.map {
            it.getFormats()
        }.flatten()
    }

    private fun Tracks.Group.getFormats(): List<Pair<Format, Int>> {
        return (0 until this.mediaTrackGroup.length).mapNotNull { i ->
            if (this.isSupported)
                this.mediaTrackGroup.getFormat(i) to i
            else null
        }
    }

    private fun Format.toAudioTrack(formatIndex: Int?): AudioTrack {
        return AudioTrack(
            this.id,
            this.label,
            this.language,
            this.sampleMimeType,
            this.channelCount,
            formatIndex ?: 0,
        )
    }

    private fun Format.toSubtitleTrack(): TextTrack {
        return TextTrack(
            this.id?.stripTrackId(),
            this.label,
            this.language,
            this.sampleMimeType,
        )
    }

    private fun Format.toVideoTrack(): VideoTrack {
        return VideoTrack(
            this.id?.stripTrackId(),
            this.label,
            this.language,
            this.width,
            this.height,
            this.sampleMimeType
        )
    }

    override fun getVideoTracks(): CurrentTracks {
        val allTrackGroups = exoPlayer?.currentTracks?.groups ?: emptyList()
        val videoTracks = allTrackGroups.filter { it.type == TRACK_TYPE_VIDEO }
            .getFormats()
            .map { it.first.toVideoTrack() }
        var currentAudioTrack: AudioTrack? = null
        val audioTracks = allTrackGroups.filter { it.type == TRACK_TYPE_AUDIO }
            .flatMap { group ->
                group.getFormats().map { (format, formatIndex) ->
                    val audioTrack = format.toAudioTrack(formatIndex)
                    if (group.isTrackSelected(formatIndex)) {
                        currentAudioTrack = audioTrack
                    }
                    audioTrack
                }
            }
        val textTracks = allTrackGroups.filter { it.type == TRACK_TYPE_TEXT }
            .getFormats()
            .map { it.first.toSubtitleTrack() }
        val currentTextTracks = textTracks.filter { track ->
            playerSelectedSubtitleTracks.any { it.second && it.first == track.id }
        }
        return CurrentTracks(
            exoPlayer?.videoFormat?.toVideoTrack(),
            currentAudioTrack,
            currentTextTracks,
            videoTracks,
            audioTracks,
            textTracks
        )
    }

    /**
     * @return True if the player should be reloaded
     * */
    override fun setPreferredSubtitles(subtitle: SubtitleData?): Boolean {
        Log.i(TAG, "setPreferredSubtitles init $subtitle")
        currentSubtitles = subtitle
        val trackSelector = exoPlayer?.trackSelector as? DefaultTrackSelector ?: return false
        // Disable subtitles if null
        if (subtitle == null) {
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .setTrackTypeDisabled(TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(TRACK_TYPE_TEXT)
            )
            return false
        }
        // Handle subtitle based on status
        when (subtitleHelper.subtitleStatus(subtitle)) {
            SubtitleStatus.REQUIRES_RELOAD -> {
                Log.i(TAG, "setPreferredSubtitles REQUIRES_RELOAD")
                return true
            }
            SubtitleStatus.NOT_FOUND -> {
                Log.i(TAG, "setPreferredSubtitles NOT_FOUND")
                return true
            }
            SubtitleStatus.IS_ACTIVE -> {
                Log.i(TAG, "setPreferredSubtitles IS_ACTIVE")
                exoPlayer?.currentTracks?.groups
                    ?.filter { it.type == TRACK_TYPE_TEXT }
                    ?.getTrack(subtitle.getId())
                    ?.let { (trackGroup, trackIndex) ->
                        trackSelector.setParameters(
                            trackSelector.buildUponParameters()
                                .setTrackTypeDisabled(TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(trackGroup, trackIndex))
                        )
                    }
                return false
            }
        }
    }

    private var currentSubtitleOffset: Long = 0

    override fun setSubtitleOffset(offset: Long) {
        currentSubtitleOffset = offset
        CustomDecoder.subtitleOffset = offset
        if (currentTextRenderer?.state == STATE_ENABLED || currentTextRenderer?.state == STATE_STARTED) {
            exoPlayer?.currentPosition?.also { pos ->
                // This seems to properly refresh all subtitles
                // It needs to be done as all subtitle cues with timings are pre-processed
                currentTextRenderer?.resetPosition(pos, false)
            }
        }
    }

    override fun getSubtitleOffset(): Long {
        return currentSubtitleOffset
    }

    override fun getSubtitleCues(): List<SubtitleCue> {
        return currentSubtitleDecoder?.getSubtitleCues() ?: emptyList()
    }

    override fun getCurrentPreferredSubtitle(): SubtitleData? {
        return subtitleHelper.getAllSubtitles().firstOrNull { sub ->
            playerSelectedSubtitleTracks.any { (id, isSelected) ->
                isSelected && sub.getId() == id
            }
        }
    }

    override fun getAspectRatio(): Rational? {
        return exoPlayer?.videoFormat?.let { format ->
            Rational(format.width, format.height)
        }
    }

    override fun updateSubtitleStyle(style: SaveCaptionStyle) {
        subtitleHelper.setSubStyle(style)
    }

    override fun saveData() {
        Log.i(TAG, "saveData")
        updatedTime()

        exoPlayer?.let { exo ->
            playbackPosition = exo.currentPosition
            currentWindow = exo.currentMediaItemIndex
            isPlaying = exo.isPlaying
        }
    }

    private fun releasePlayer(saveTime: Boolean = true) {
        Log.i(TAG, "releasePlayer")
        eventLooperIndex += 1
        if (saveTime)
            updatedTime()

        currentTextRenderer = null
        currentSubtitleDecoder = null

        exoPlayer?.apply {
            playWhenReady = false

            // This may look weird, however on some TV devices the audio does not stop playing
            // so this may fix it?
            try {
                pause()
            } catch (t: Throwable) {
                // No documented exception, but just to be extra safe
                logError(t)
            }
            playerListener?.let {
                removeListener(it)
                playerListener = null
            }
            stop()
            release()
        }
        //simpleCache?.release()

        exoPlayer = null
        event(PlayerAttachedEvent(null))
        //simpleCache = null
    }

    override fun onStop() {
        Log.i(TAG, "onStop")

        saveData()
        if (!isAudioOnlyBackground) {
            handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Player)
        }
        //releasePlayer()
    }

    override fun onPause() {
        Log.i(TAG, "onPause")
        saveData()
        if (!isAudioOnlyBackground) {
            handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Player)
        }
        //releasePlayer()
    }

    override fun onResume(context: Context) {
        isAudioOnlyBackground = false
        if (exoPlayer == null)
            reloadPlayer(context)
    }

    override fun release() {
        imageGenerator.release()
        releasePlayer()
    }

    override fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed)
        playBackSpeed = speed
    }

    companion object {
        private const val CRONET_TIMEOUT_MS = 15_000

        /**
         * Single shared engine, to minimize the overhead of maintaining many as:
         * 1. Cpu time/Startup time
         * 2. Mem consumption/GC
         * 3. Disk usage, as we simply use the same folder
         * */
        private var cronetEngine: CronetEngine? = null

        /**
         * How many active sessions we have.
         *
         * However in reality it should never go negative or be more than 1,
         * but this makes more sense architecturally.
         * */
        @Volatile
        private var activePlayers = 0

        /** Unique monotonically increasing id to keep track of the last release call */
        @Volatile
        private var cronetReleasedId = 0

        fun releaseCronetEngine() {
            if (cronetEngine == null) return

            // Delayed release, as we do not want to restart it when opening trailers ect
            val id = ++cronetReleasedId
            val posted = Handler(Looper.getMainLooper()).postDelayed({
                // This might get dropped, but that should be very rare
                // and should not affect it.
                releaseCronetEngineInstantly(id)
            }, 60_000) // 1min timeout before release

            // If not posted, then run instantly
            if (!posted) {
                releaseCronetEngineInstantly(id)
            }
        }

        private fun releaseCronetEngineInstantly(id: Int) {
            // We should release if and only if this was the last call, and
            // there is no active players
            if (activePlayers == 0 && id == cronetReleasedId) {
                try {
                    cronetEngine?.shutdown()
                } catch (t: Throwable) {
                    logError(t)
                } finally {
                    Log.d(TAG, "CronetEngine shutdown")
                    // Even if it fails to shutdown, the GC should take care of it
                    cronetEngine = null
                }
            }
        }

        /**
         * Setting this variable is permanent across app sessions.
         **/
        var preferredAudioTrackLanguage: String? = null
            get() {
                return field ?: getKey(
                    "$currentAccount/$PREFERRED_AUDIO_LANGUAGE_KEY",
                    field
                )?.also {
                    field = it
                }
            }
            set(value) {
                setKey("$currentAccount/$PREFERRED_AUDIO_LANGUAGE_KEY", value)
                field = value
            }

        private var simpleCache: SimpleCache? = null

        /// Create a small factory for small things, no cache, no cronet
        private fun createOnlineSource(
            headers: Map<String, String>?,
            interceptor: Interceptor?
        ): HttpDataSource.Factory {
            val client = if (interceptor == null) {
                app.baseClient
            } else {
                app.baseClient.newBuilder()
                    .addInterceptor(interceptor)
                    .build()
            }
            val source = OkHttpDataSource.Factory(client).setUserAgent(USER_AGENT)

            if (!headers.isNullOrEmpty()) {
                source.setDefaultRequestProperties(headers)
            }
            return source
        }

        fun tryCreateEngine(context: Context, diskCacheSize: Long): CronetEngine? {
            // Fast case, no need to recreate it
            cronetEngine?.let {
                return it
            }

            // https://gist.github.com/ShivamKumarJha/3c8398b47053ae05112d2a8f8b5de531
            return try {
                val cacheDirectory = File(context.cacheDir, "CronetEngine")
                cacheDirectory.deleteRecursively()
                if (!cacheDirectory.exists()) {
                    cacheDirectory.mkdirs()
                }
                CronetEngine.Builder(context)
                    .enableBrotli(true)
                    .enableHttp2(true)
                    .enableQuic(false) // QUIC DISABLED
                    .setStoragePath(cacheDirectory.absolutePath)
                    .setLibraryLoader(null)
                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, diskCacheSize)
                    .build().also { buildEngine ->
                        Log.d(
                            TAG,
                            "Created CronetEngine with cache at ${cacheDirectory.absolutePath}"
                        )
                        cronetEngine = buildEngine
                    }
            } catch (t: Throwable) {
                logError(t)
                // Something went wrong, so we use the backup okhttp
                null
            }
        }

        private fun createVideoSource(
            link: ExtractorLink,
            engine: CronetEngine?,
            interceptor: Interceptor?,
        ): HttpDataSource.Factory {
            val userAgent = link.headers.entries.find {
                it.key.equals("User-Agent", ignoreCase = true)
            }?.value ?: USER_AGENT

            val source = if (interceptor == null) {
                if (engine == null) {
                    Log.d(TAG, "Using DefaultHttpDataSource for $link")
                    OkHttpDataSource.Factory(app.baseClient).setUserAgent(userAgent)
                } else {
                    Log.d(TAG, "Using CronetDataSource for $link")
                    CronetDataSource.Factory(engine, Executors.newSingleThreadExecutor())
                        .setUserAgent(userAgent)
                        .setConnectionTimeoutMs(CRONET_TIMEOUT_MS)
                        .setReadTimeoutMs(CRONET_TIMEOUT_MS)
                        .setResetTimeoutOnRedirects(true)
                        .setHandleSetCookieRequests(true)
                }
            } else {
                Log.d(TAG, "Using OkHttpDataSource for $link")
                val client = app.baseClient.newBuilder()
                    .addInterceptor(interceptor)
                    .build()
                OkHttpDataSource.Factory(client).setUserAgent(userAgent)
            }

            // Do no include empty referer, if the provider wants those they can use the header map.
            val refererMap =
                if (link.referer.isBlank()) emptyMap() else mapOf("referer" to link.referer)

            // These are extra headers the browser like to insert, not sure if we want to include them
            // for WIDEVINE/drm as well? Do that if someone gets 404 and creates an issue.
            val headers = refererMap + link.headers // Adds the headers from the provider, e.g Authorization

            return source.apply {
                setDefaultRequestProperties(headers)
            }
        }

        private fun Context.createOfflineSource(): DataSource.Factory {
            return DefaultDataSource.Factory(
                this,
                DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
            )
        }

        private fun getCache(context: Context, cacheSize: Long): SimpleCache? {
            return try {
                val databaseProvider = StandaloneDatabaseProvider(context)
                SimpleCache(
                    File(
                        context.cacheDir, "exoplayer"
                    ).also { deleteFileOnExit(it) }, // Ensures always fresh file
                    LeastRecentlyUsedCacheEvictor(cacheSize),
                    databaseProvider
                )
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        private fun getMediaItemBuilder(mimeType: String):
                MediaItem.Builder {
            return MediaItem.Builder()
                //Replace needed for android 6.0.0  https://github.com/google/ExoPlayer/issues/5983
                .setMimeType(mimeType)
        }

        private fun getMediaItem(mimeType: String, uri: Uri): MediaItem {
            return getMediaItemBuilder(mimeType).setUri(uri).build()
        }

        private fun getMediaItem(mimeType: String, url: String): MediaItem {
            return getMediaItemBuilder(mimeType).setUri(url).build()
        }

        private fun getTrackSelector(context: Context, maxVideoHeight: Int?): TrackSelector {
            val trackSelector = DefaultTrackSelector(context)
            trackSelector.parameters = trackSelector.buildUponParameters()
                // This will not force higher quality videos to fail
                // but will make the m3u8 pick the correct preferred
                .setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight ?: Int.MAX_VALUE)
                .setPreferredAudioLanguage(null)
                .build()
            return trackSelector
        }

        private var currentSubtitleDecoder: CustomSubtitleDecoderFactory? = null
        private var currentTextRenderer: TextRenderer? = null
    }
    private var eventLooperIndex = 0

    private fun handleEvent(event: CSPlayerEvent, source: PlayerEventSource) {
        val currentIdx = eventLooperIndex
        mainHandler.post {
            if (currentIdx == eventLooperIndex) {
                event(PlayerEvent(source, event))
            }
        }
    }

    private fun buildExoPlayer(
        context: Context,
        trackSelector: TrackSelector,
        cacheSize: Long,
        videoBufferMs: Long,
    ): ExoPlayer {
        val renderersFactory = object : NextRenderersFactory(context) {
            override fun buildTextRenderers(
                context: Context,
                output: TextOutput,
                extensionRendererMode: Int,
                out: ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                val customDecoder = currentSubtitleDecoder ?: CustomSubtitleDecoderFactory().also {
                    currentSubtitleDecoder = it
                }
                val textRenderer = TextRenderer(output, handler.looper, customDecoder)
                currentTextRenderer = textRenderer
                out.add(textRenderer)
            }
        }.apply {
            setEnableDecoderFallback(true)
        }

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setTargetBufferBytes(
                        if (cacheSize <= 0) {
                            DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES
                        } else {
                            if (cacheSize > Int.MAX_VALUE) Int.MAX_VALUE else cacheSize.toInt()
                        }
                    )
                    .setBackBuffer(
                        30000,
                        true
                    )
                    .setBufferDurationsMs(
                        25000, // UBAHAN: MIN BUFFER JADI 25 DETIK
                        if (videoBufferMs <= 0) {
                            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
                        } else {
                            videoBufferMs.toInt()
                        },
                        1500, // UBAHAN: PLAYBACK START JADI 1.5 DETIK
                        2000  // UBAHAN: REBUFFER START JADI 2 DETIK
                    ).build()
            )
            .build()
    }

    private fun getMediaSource(
        source: DataSource.Factory,
        item: MediaItemSlice,
    ): MediaSource {
        val isM3u8 = item.mediaItem.localConfiguration?.uri?.toString()?.contains("m3u8") == true
        var mediaSource: MediaSource =
            DefaultMediaSourceFactory(source).createMediaSource(item.mediaItem)

        if (item.drm != null) {
            val drmParams = item.drm
            val drmSchemeUuid = when (drmParams.uuid) {
                PLAYREADY_UUID -> androidx.media3.common.C.PLAYREADY_UUID
                WIDEVINE_UUID -> androidx.media3.common.C.WIDEVINE_UUID
                CLEARKEY_UUID -> androidx.media3.common.C.CLEARKEY_UUID
                else -> androidx.media3.common.C.WIDEVINE_UUID
            }
            
            val httpMediaDrmCallback =
                if (drmParams.key != null && drmParams.kid != null && drmParams.kty != null) {
                    val keyString =
                        "{\"keys\":[{\"kty\":\"${drmParams.kty}\",\"k\":\"${drmParams.key}\",\"kid\":\"${drmParams.kid}\"}],\"type\":\"temporary\"}"
                    LocalMediaDrmCallback(keyString.toByteArray())
                } else {
                    HttpMediaDrmCallback(drmParams.licenseUrl, DefaultHttpDataSource.Factory())
                }
                
            for ((key, value) in drmParams.keyRequestParameters) {
                httpMediaDrmCallback.setKeyRequestProperty(key, value)
            }
            
            val drmSessionManager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(drmSchemeUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(httpMediaDrmCallback)
                
            mediaSource = DefaultMediaSourceFactory(source)
                .setDrmSessionManagerProvider { drmSessionManager }
                .createMediaSource(item.mediaItem)
        }
        
        if (item.durationUs > 0) {
            mediaSource = ClippingMediaSource(mediaSource, item.durationUs)
        }
        
        return mediaSource
    }

    private fun loadExo(
        context: Context,
        mediaItems: List<MediaItemSlice>,
        subSources: List<MediaSource>?,
        cacheFactory: DataSource.Factory,
        audioSources: List<MediaSource>? = null,
        onlineSource: DataSource.Factory? = null,
    ) {
        Log.i(TAG, "loadExo")
        if (mediaItems.isEmpty()) return

        val player = buildExoPlayer(
            context,
            getTrackSelector(context, currentLink?.quality),
            cacheSize,
            videoBufferMs
        )
        exoPlayer = player
        
        val concatenatingMediaSource = ConcatenatingMediaSource2.Builder().build()
        for (item in mediaItems) {
            val mediaSource = getMediaSource(cacheFactory, item)
            concatenatingMediaSource.addSource(mediaSource)
        }

        var source: MediaSource = concatenatingMediaSource
        
        if (subSources != null) {
            source = MergingMediaSource(
                source,
                *subSources.toTypedArray()
            )
        }
        
        if (!audioSources.isNullOrEmpty()) {
            source = MergingMediaSource(
                source,
                *audioSources.toTypedArray()
            )
        }

        player.setMediaSource(source)
        player.prepare()
        player.setPlaybackSpeed(playBackSpeed)
        player.playWhenReady = isPlaying
        player.seekTo(currentWindow, playbackPosition)
        
        if (lastMuteVolume == 0f) {
            player.volume = lastMuteVolume
        }

        player.analyticsCollector.addListener(tracksAnalyticsListener)
        
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playerSelectedSubtitleTracks =
                        player.currentTracks.groups.filter { it.type == TRACK_TYPE_TEXT }
                            .getFormats().map { (format, idx) ->
                                (format.id?.stripTrackId() ?: "") to
                                        player.currentTracks.groups.filter { it.type == TRACK_TYPE_TEXT }
                                            .any { it.isTrackSelected(idx) }
                            }
                }
                
                this@CS3IPlayer.isPlaying = isPlaying
                if (isPlaying) {
                    updatedTime()
                }
                super.onIsPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                logError(error)
                event(ErrorEvent(error))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (!hasUsedFirstRender) {
                        hasUsedFirstRender = true
                        
                        val format = player.videoFormat
                        if (format != null) {
                            event(ResizedEvent(format.height, format.width))
                        }
                    }
                    setPreferredSubtitles(currentSubtitles)
                    setPreferredAudioTrack(preferredAudioTrackLanguage, null, null)
                }

                if (playbackState == Player.STATE_ENDED) {
                    handleEvent(CSPlayerEvent.NextEpisode, PlayerEventSource.Player)
                }

                super.onPlaybackStateChanged(playbackState)
            }
        }
        
        playerListener = listener
        player.addListener(listener)
    }

    private fun getSubSources(
        onlineSourceFactory: HttpDataSource.Factory,
        offlineSourceFactory: DataSource.Factory,
        subtitleHelper: PlayerSubtitleHelper,
    ): Pair<List<SingleSampleMediaSource>?, List<SubtitleData>> {
        val activeSubtitles = ArrayList<SubtitleData>()
        val subSources = subtitleHelper.getAllSubtitles().mapNotNull { sub ->
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(sub.mimeType)
                .setLanguage(sub.name)
                .setId(sub.getId())
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()
                
            val source = if (sub.isDownloaded) {
                offlineSourceFactory
            } else {
                onlineSourceFactory
            }
            
            try {
                val mediaSource = SingleSampleMediaSource.Factory(source)
                    .createMediaSource(subConfig, TIME_UNSET)
                activeSubtitles.add(sub)
                mediaSource
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
        
        return Pair(subSources.ifEmpty { null }, activeSubtitles)
    }
    private fun loadOfflinePlayer(context: Context, data: ExtractorUri) {
        try {
            currentDownloadedFile = data
            val mime = MimeTypes.APPLICATION_MP4
            val mediaItems = listOf(MediaItemSlice(getMediaItem(mime, data.uri), Long.MIN_VALUE))
            
            val offlineSourceFactory = context.createOfflineSource()

            val (subSources, activeSubtitles) = getSubSources(
                onlineSourceFactory = createOnlineSource(emptyMap(), null),
                offlineSourceFactory = offlineSourceFactory,
                subtitleHelper
            )

            subtitleHelper.setActiveSubtitles(activeSubtitles.toSet())

            if (simpleCache == null)
                simpleCache = getCache(context, simpleCacheSize)

            val cacheFactory = CacheDataSource.Factory().apply {
                simpleCache?.let { setCache(it) }
                setUpstreamDataSourceFactory(offlineSourceFactory)
            }

            loadExo(context, mediaItems, subSources, cacheFactory)
        } catch (t: Throwable) {
            logError(t)
            event(ErrorEvent(t))
        }
    }

    private fun loadOnlinePlayer(context: Context, link: ExtractorLink) {
        try {
            currentLink = link
            val mime = when (link.type) {
                ExtractorLinkType.M3U8 -> MimeTypes.APPLICATION_M3U8
                ExtractorLinkType.DASH -> MimeTypes.APPLICATION_MPD
                else -> MimeTypes.APPLICATION_MP4
            }

            val mediaItems = when (link) {
                is ExtractorLinkPlayList -> {
                    link.playlist.map { ep ->
                        MediaItemSlice(getMediaItem(mime, ep.url), Long.MIN_VALUE)
                    }
                }
                is DrmExtractorLink -> {
                    listOf(
                        MediaItemSlice(
                            getMediaItem(mime, link.url), Long.MIN_VALUE,
                            DrmMetadata(
                                link.kid,
                                link.key,
                                link.uuid,
                                link.kty,
                                link.licenseUrl,
                                link.keyRequestParameters
                            )
                        )
                    )
                }
                else -> listOf(
                    MediaItemSlice(getMediaItem(mime, link.url), Long.MIN_VALUE)
                )
            }

            val onlineSourceFactory = createVideoSource(link, tryCreateEngine(context, cacheSize), null)
            val offlineSourceFactory = context.createOfflineSource()

            val (subSources, activeSubtitles) = getSubSources(
                onlineSourceFactory = createOnlineSource(link.headers, null),
                offlineSourceFactory = offlineSourceFactory,
                subtitleHelper
            )

            subtitleHelper.setActiveSubtitles(activeSubtitles.toSet())

            val audioSources = link.audioTracks?.map { track ->
                val trackSourceFactory = createVideoSource(
                    ExtractorLink(
                        source = link.source,
                        name = track.name ?: "Audio",
                        url = track.url,
                        referer = link.referer,
                        quality = 0,
                        type = ExtractorLinkType.AUDIO,
                        headers = link.headers,
                        extractorData = link.extractorData
                    ),
                    tryCreateEngine(context, cacheSize),
                    null
                )
                val audioConfig = MediaItem.Builder()
                    .setUri(track.url)
                    .setMimeType(MimeTypes.getMediaMimeType(track.url))
                    .build()
                DefaultMediaSourceFactory(trackSourceFactory).createMediaSource(audioConfig)
            }

            if (simpleCache == null)
                simpleCache = getCache(context, simpleCacheSize)

            val cacheFactory = CacheDataSource.Factory().apply {
                simpleCache?.let { setCache(it) }
                setUpstreamDataSourceFactory(onlineSourceFactory)
            }

            loadExo(
                context = context,
                mediaItems = mediaItems,
                subSources = subSources,
                cacheFactory = cacheFactory,
                audioSources = audioSources,
                onlineSource = onlineSourceFactory
            )
        } catch (t: Throwable) {
            Log.e(TAG, "loadOnlinePlayer error", t)
            event(ErrorEvent(t))
        }
    }

    override fun reloadPlayer(context: Context) {
        Log.i(TAG, "reloadPlayer")

        releasePlayer(false)
        currentLink?.let {
            loadOnlinePlayer(context, it)
        } ?: currentDownloadedFile?.let {
            loadOfflinePlayer(context, it)
        }
    }

    override fun seekTime(timeMs: Long, source: PlayerEventSource) {
        exoPlayer?.let { exo ->
            seekTo(exo.currentPosition + timeMs, source)
        }
    }

    override fun seekTo(timeMs: Long, source: PlayerEventSource) {
        exoPlayer?.let { exo ->
            exo.setSeekParameters(SeekParameters(toleranceBeforeUs, toleranceAfterUs))
            exo.seekTo(currentWindow, timeMs)
            playbackPosition = timeMs
            handleEvent(CSPlayerEvent.Seek, source)
        }
    }

    private fun updatedTime() {
        exoPlayer?.let { exo ->
            val position = exo.currentPosition
            val duration = exo.duration
            if (position >= 0 && duration > 0) {
                event(PositionEvent(position, duration))
            }
        }
    }

    private val tracksAnalyticsListener = object : AnalyticsListener {

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            event(TracksChangedEvent())
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            event(TracksChangedEvent())
        }

        override fun onVideoDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters
        ) {
            event(TracksChangedEvent())
        }

        override fun onAudioDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters
        ) {
            event(TracksChangedEvent())
        }
    }
}
