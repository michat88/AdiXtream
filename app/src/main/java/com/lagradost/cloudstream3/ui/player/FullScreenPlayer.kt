package com.lagradost.cloudstream3.ui.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity.keyEventListener
import com.lagradost.cloudstream3.CommonActivity.playerEventListener
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentPlayerBinding
import com.lagradost.cloudstream3.databinding.PlayerCustomLayoutBinding
import com.lagradost.cloudstream3.databinding.SpeedDialogBinding
import com.lagradost.cloudstream3.databinding.SubtitleOffsetBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer.Companion.subsProvidersIsActive
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.isUsingMobileData
import com.lagradost.cloudstream3.utils.AppContextUtils.shouldShowPlayerMetadata
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.attachBackPressedCallback
import com.lagradost.cloudstream3.utils.BackPressedCallbackHelper.detachBackPressedCallback
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.hideSystemUI
import com.lagradost.cloudstream3.utils.UIHelper.popCurrentPage
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.setText
import com.lagradost.cloudstream3.utils.txt
import kotlin.math.roundToInt

private const val SUBTITLE_DELAY_BUNDLE_KEY = "subtitle_delay"

@OptIn(UnstableApi::class)
open class FullScreenPlayer : AbstractPlayerFragment<FragmentPlayerBinding>(
    BindingCreator.Bind(FragmentPlayerBinding::bind)
) {
    override fun pickLayout(): Int = R.layout.fragment_player
  
    protected open var lockRotation = true
    protected var playerBinding: PlayerCustomLayoutBinding? = null

    protected var isShowing = false
    protected var isLocked = false
    protected var timestampShowState = false
    private var metadataVisibilityToken = 0
    protected var hasEpisodes = false
        private set

    protected var currentQualityProfile = 1
    protected var androidTVInterfaceOffSeekTime = 10000L
    protected var androidTVInterfaceOnSeekTime = 30000L
    protected var playBackSpeedEnabled = false
    protected var playerResizeEnabled = false
    protected var playerRotateEnabled = false
    protected var rotatedManually = false
    private var hideControlsNames = false
    protected var subtitleDelay
        set(value) = try {
            player.setSubtitleOffset(-value)
        } catch (e: Exception) {
            logError(e)
        }
        get() = try {
            -player.getSubtitleOffset()
        } catch (e: Exception) {
            logError(e)
            0L
        }

    private var isShowingEpisodeOverlay: Boolean = false
    private var previousPlayStatus: Boolean = false

    override fun fixLayout(view: View) = Unit

    protected var selectSourceDialog: Dialog? = null
        set(value) {
            val prevField = field
            field = value
            if (value == null && prevField != null) {
                autoHide()
            }
        }
    protected var selectTrackDialog: Dialog? = null
        set(value) {
            val prevField = field
            field = value
            if (value == null && prevField != null) {
                autoHide()
            }
        }
    protected var selectSpeedDialog: Dialog? = null
        set(value) {
            val prevField = field
            field = value
            if (value == null && prevField != null) {
                autoHide()
            }
        }
  
    protected var selectSubtitlesDialog: Dialog? = null
        set(value) {
            val prevField = field
            field = value
            if (value == null && prevField != null) {
                autoHide()
            }
        }

    fun isDialogOpen() =
        selectSourceDialog?.isShowing == true ||
        selectTrackDialog?.isShowing == true ||
        selectSpeedDialog?.isShowing == true ||
        selectSubtitlesDialog?.isShowing == true ||
        isShowingEpisodeOverlay

    private fun scheduleMetadataVisibility() {
        val metadataScrim = playerBinding?.playerMetadataScrim ?: return
        val ctx = metadataScrim.context ?: return

        if (!ctx.shouldShowPlayerMetadata() || isLayout(PHONE)) {
            metadataScrim.isVisible = false
            metadataVisibilityToken++
            return
        }

        val isPaused = currentPlayerStatus == CSPlayerLoading.IsPaused
        val token = ++metadataVisibilityToken

        if (isPaused) {
            metadataScrim.postDelayed({
                if (token != metadataVisibilityToken) return@postDelayed
                if (metadataScrim.isVisible) return@postDelayed
                if (currentPlayerStatus != CSPlayerLoading.IsPaused) return@postDelayed
                if (isDialogOpen()) return@postDelayed

                metadataScrim.alpha = 0f
                metadataScrim.isVisible = true
                metadataScrim.animate()
                    .alpha(1f)
                    .setDuration(500L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                hidePlayerUI()
            }, 8000L)
        } else {
            if (metadataScrim.isVisible) {
                metadataScrim.animate()
                    .alpha(0f)
                    .setDuration(300L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        metadataScrim.alpha = 0f
                        metadataScrim.isVisible = false
                    }
                    .start()
            }
        }
    }

    override fun onDestroyView() {
        playerHostView?.releaseOverlayLayoutListener()
        playerBinding = null
        super.onDestroyView()
    }

    open fun showMirrorsDialogue() { throw NotImplementedError() }
    open fun showTracksDialogue() { throw NotImplementedError() }
    open fun openOnlineSubPicker(context: Context, loadResponse: LoadResponse?, dismissCallback: (() -> Unit)) { throw NotImplementedError() }
    open fun showEpisodesOverlay() { throw NotImplementedError() }
    open fun isThereEpisodes(): Boolean = false

    override fun exitedPipMode() {
        animateLayoutChanges()
    }

    private fun animateLayoutChangesForSubtitles() =
        playerBinding?.bottomPlayerBar?.post {
            val sView = subView ?: return@post
            val sStyle = CustomDecoder.style
            val binding = playerBinding ?: return@post

            val move = if (isShowing) minOf(
                -sStyle.elevation.toPx,
                binding.previewFrameLayout.height - binding.bottomPlayerBar.height
            ) else -sStyle.elevation.toPx
            ObjectAnimator.ofFloat(sView, "translationY", move.toFloat()).apply {
                duration = 200
                start()
            }
        }

    protected fun animateLayoutChanges() {
        if (isLayout(PHONE)) {
            playerBinding?.exoProgress?.isEnabled = isShowing
        }

        if (isShowing) {
            updateUIVisibility()
        } else {
            toggleEpisodesOverlay(false)
            playerBinding?.playerHolder?.postDelayed({ updateUIVisibility() }, 200)
        }

        val titleMove = if (isShowing) 0f else -50.toPx.toFloat()
        playerBinding?.playerVideoTitleHolder?.let {
            ObjectAnimator.ofFloat(it, "translationY", titleMove).apply { duration = 200; start() }
        }
        playerBinding?.playerVideoTitleRez?.let {
            ObjectAnimator.ofFloat(it, "translationY", titleMove).apply { duration = 200; start() }
        }
        playerBinding?.playerVideoInfo?.let {
            ObjectAnimator.ofFloat(it, "translationY", titleMove).apply { duration = 200; start() }
        }

        val playerBarMove = if (isShowing) 0f else 50.toPx.toFloat()
        playerBinding?.bottomPlayerBar?.let {
            ObjectAnimator.ofFloat(it, "translationY", playerBarMove).apply { duration = 200; start() }
        }
        
        val fadeTo = if (isShowing) 1f else 0f
        val fadeAnimation = AlphaAnimation(1f - fadeTo, fadeTo).apply {
            duration = 100
            fillAfter = true
        }

        animateLayoutChangesForSubtitles()

        playerBinding?.apply {
            if (!isLocked) {
                playerHostView?.gestureHelper?.animateCenterControls(fadeTo)
                shadowOverlay.isVisible = true
                shadowOverlay.startAnimation(fadeAnimation)
                downloadBothHeader.startAnimation(fadeAnimation)
            }
            bottomPlayerBar.startAnimation(fadeAnimation)
            playerTopHolder.startAnimation(fadeAnimation)
        }
    }

    override fun subtitlesChanged() {
        val tracks = player.getVideoTracks()
        val isBuiltinSubtitles = tracks.currentTextTracks.all { it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES }
        playerBinding?.playerSubtitleOffsetBtt?.isGone = isBuiltinSubtitles || tracks.currentTextTracks.isEmpty()
    }

    private fun updateOrientation(ignoreDynamicOrientation: Boolean = false) {
        activity?.apply {
            if (lockRotation) {
                if (isLocked) {
                    lockOrientation(this)
                } else {
                    if (ignoreDynamicOrientation || rotatedManually) {
                        restoreOrientationWithSensor(this)
                    } else {
                        this.requestedOrientation = playerHostView?.dynamicOrientation() ?: return@apply
                    }
                }
            }
        }
    }

    private fun setupKeyEventListener() {
        keyEventListener = { eventNav ->
            val (event, hasNavigated) = eventNav
            when {
                event == null -> false
                event.action == KeyEvent.ACTION_DOWN &&
                  (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) ->
                    playerHostView?.handleVolumeKey(event.keyCode) ?: false
                player.isActive() -> handleKeyEvent(event, hasNavigated)
                else -> false
            }
        }
    }

    override fun onResume() {
        playerHostView?.enterFullscreen { updateOrientation() }
        setupKeyEventListener()
        playerHostView?.verifyVolume()
        activity?.attachBackPressedCallback("FullScreenPlayer") {
            if (isShowingEpisodeOverlay) {
                toggleEpisodesOverlay(show = false)
                return@attachBackPressedCallback
            } else {
                activity?.popCurrentPage("FullScreenPlayer")
            }
        }
        super.onResume()
    }

    override fun onDestroy() {
        playerHostView?.exitFullscreen()
        super.onDestroy()
    }

    private fun handleKeyEvent(event: KeyEvent, hasNavigated: Boolean): Boolean {
        if (hasNavigated) {
            autoHide()
            return false
        }
        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (!isShowing) {
                        if (!isLocked) player.handleEvent(CSPlayerEvent.PlayPauseToggle)
                        onClickChange()
                        return true
                    }
                }
                // Logika navigasi lainnya tetap ada di sini...
            }
        }

        when (keyCode) {
            // RESTORE BACK BUTTON LOGIC
            KeyEvent.KEYCODE_BACK -> {
                // Perintah untuk langsung keluar saat tombol back HP ditekan
                activity?.popCurrentPage("FullScreenPlayer")
                return true
            }
            
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                if (!isShowing) {
                    onClickChange()
                    return true
                }
            }
        }
        return false
    }

    private fun onClickChange() {
        isShowing = !isShowing
        if (isShowing) autoHide()
        activity?.hideSystemUI()
        animateLayoutChanges()
    }

    private fun toggleLock() {
        if (!isShowing) onClickChange()
        isLocked = !isLocked
        playerHostView?.isLocked = isLocked
        updateOrientation(true)
        updateUIVisibility()
        updateLockUI()
    }

    private fun updateUIVisibility() {
        val isGone = isLocked || !isShowing
        playerBinding?.apply {
            playerLockHolder.isGone = isGone
            playerPausePlay.isGone = isGone
            playerTopHolder.isGone = isGone
            playerGoBackHolder.isGone = isGone
            playerLock.isGone = !isShowing
        }
    }

    private fun updateLockUI() {
        playerBinding?.apply {
            playerLock.setIconResource(if (isLocked) R.drawable.video_locked else R.drawable.video_unlocked)
        }
    }

    protected fun autoHide() {
        metadataVisibilityToken++
        playerHostView?.scheduleAutoHide()
        scheduleMetadataVisibility()
    }

    override fun onAutoHideUI() {
        if (player.getIsPlaying()) onClickChange()
    }

    override fun onBindingCreated(binding: FragmentPlayerBinding, savedInstanceState: Bundle?) {
        playerBinding = PlayerCustomLayoutBinding.bind(binding.root.findViewById(R.id.player_holder))
        super.onBindingCreated(binding, savedInstanceState)
        playerHostView?.isFullScreen = true

        playerBinding?.apply {
            playerGoBack.setOnClickListener {
                activity?.popCurrentPage("FullScreenPlayer")
            }
            playerPausePlay.setOnClickListener {
                player.handleEvent(CSPlayerEvent.PlayPauseToggle)
            }
            playerLock.setOnClickListener {
                toggleLock()
            }
        }
        uiReset()
    }

    protected fun uiReset() {
        isShowing = false
        updateUIVisibility()
        animateLayoutChanges()
    }

    private fun toggleEpisodesOverlay(show: Boolean) {
        if (show && !isShowingEpisodeOverlay) {
            player.handleEvent(CSPlayerEvent.Pause)
            isShowingEpisodeOverlay = true
            animateEpisodesOverlay(true)
        } else if (isShowingEpisodeOverlay) {
            isShowingEpisodeOverlay = false
            animateEpisodesOverlay(false)
        }
    }

    private fun animateEpisodesOverlay(show: Boolean) {
        playerBinding?.playerEpisodeOverlay?.isVisible = show
    }
}
