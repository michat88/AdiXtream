package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.MainSettingsBinding
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.home.HomeFragment.Companion.errorProfilePic
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLandscape
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppUtils.openBrowser
import com.lagradost.cloudstream3.utils.AppUtils.setAppLayout
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getNavigationBarHeight
import com.lagradost.cloudstream3.utils.UIHelper.getStatusBarHeight
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.setImage
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showBottomDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.txt
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : BaseFragment() {
    override var layout = R.layout.main_settings
    private var binding: MainSettingsBinding? = null

    companion object {
        fun Fragment.setUpToolbar(@StringRes title: Int) {
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
            settingsToolbar?.setTitle(title)
            settingsToolbar?.setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            settingsToolbar?.setNavigationOnClickListener {
                activity?.onBackPressedDispatcher?.onBackPressed()
            }
        }

        fun View.setSystemBarsPadding() {
            fixSystemBarsPadding(padTop = true, padBottom = false)
        }

        fun View.setToolBarScrollFlags() {
            if (isLayout(TV)) return
            updateLayoutParams<AppBarLayout.LayoutParams> {
                scrollFlags =
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = MainSettingsBinding.bind(view)
        this.binding = binding

        binding.apply {
            settingsToolbar.setSystemBarsPadding()
            settingsToolbar.setToolBarScrollFlags()

            // --- Navigasi Diperbaiki ---
            listOf(
                settingsGeneral to R.id.action_navigation_global_to_navigation_settings_general,
                settingsPlayer to R.id.action_navigation_global_to_navigation_settings_player,
                
                // Sekarang tombol ini akan dicek oleh Satpam di MainActivity
                settingsExtensions to R.id.action_navigation_global_to_navigation_settings_extensions, 
                
                settingsCredits to R.id.action_navigation_global_to_navigation_settings_account,
                settingsUi to R.id.action_navigation_global_to_navigation_settings_ui,
                settingsProviders to R.id.action_navigation_global_to_navigation_settings_providers,
                settingsUpdates to R.id.action_navigation_global_to_navigation_settings_updates,
            ).forEach { (view, navigationId) ->
                view.apply {
                    setOnClickListener { navigate(navigationId) }
                    if (isLayout(TV)) {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                }
            }

            if (isLayout(TV)) {
                settingsGeneral.requestFocus()
            }
        }

        // Tampilkan Info Versi
        val appVersion = BuildConfig.VERSION_NAME
        val commitInfo = getString(R.string.commit_hash)
        val buildTimestamp = SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG,
            Locale.getDefault()
        ).apply { timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(BuildConfig.BUILD_DATE)).replace("UTC", "")

        binding.appVersion.text = appVersion
        binding.buildDate.text = buildTimestamp
        binding.appVersionInfo.setOnLongClickListener {
            clipboardHelper(txt(R.string.extension_version), "$appVersion $commitInfo $buildTimestamp")
            true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}
