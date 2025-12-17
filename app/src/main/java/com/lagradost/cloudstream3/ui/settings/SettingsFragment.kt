package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SettingsFragment : BaseFragment<MainSettingsBinding>(
    BaseFragment.BindingCreator.Inflate(MainSettingsBinding::inflate)
) {
    companion object {
        fun PreferenceFragmentCompat?.getPref(id: Int): Preference? {
            if (this == null) return null
            return try {
                findPreference(getString(id))
            } catch (e: Exception) {
                logError(e)
                null
            }
        }

        fun PreferenceFragmentCompat?.hidePrefs(ids: List<Int>, layoutFlags: Int) {
            if (this == null) return
            try {
                ids.forEach {
                    getPref(it)?.isVisible = !isLayout(layoutFlags)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        fun Preference?.hideOn(layoutFlags: Int): Preference? {
            if (this == null) return null
            this.isVisible = !isLayout(layoutFlags)
            return if(this.isVisible) this else null
        }

        fun PreferenceFragmentCompat.setPaddingBottom() {
            if (isLayout(TV or EMULATOR)) {
                listView?.setPadding(0, 0, 0, 100.toPx)
            }
        }

        fun PreferenceFragmentCompat.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setToolBarScrollFlags() {
            if (isLayout(TV or EMULATOR)) {
                val settingsAppbar = this?.view?.findViewById<MaterialToolbar>(R.id.settings_toolbar)
                settingsAppbar?.updateLayoutParams<AppBarLayout.LayoutParams> {
                    scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
                }
            }
        }

        fun Fragment?.setUpToolbar(title: String) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    setNavigationOnClickListener {
                        activity?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
            }
        }

        fun Fragment?.setUpToolbar(@StringRes title: Int) {
            if (this == null) return
            val settingsToolbar = view?.findViewById<MaterialToolbar>(R.id.settings_toolbar) ?: return
            settingsToolbar.apply {
                setTitle(title)
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    children.firstOrNull { it is ImageView }?.tag = getString(R.string.tv_no_focus_tag)
                    setNavigationOnClickListener {
                        safe { activity?.onBackPressedDispatcher?.onBackPressed() }
                    }
                }
            }
        }

        fun Fragment.setSystemBarsPadding() {
            view?.let {
                fixSystemBarsPadding(
                    it,
                    padLeft = isLayout(TV or EMULATOR),
                    padBottom = isLandscape()
                )
            }
        }

        fun getFolderSize(dir: File): Long {
            var size: Long = 0
            dir.listFiles()?.let {
                for (file in it) {
                    size += if (file.isFile) {
                        file.length()
                    } else getFolderSize(file)
                }
            }
            return size
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(
            view,
            padBottom = isLandscape(),
            padLeft = isLayout(TV or EMULATOR)
        )
    }

    override fun onBindingCreated(binding: MainSettingsBinding) {
        fun navigate(id: Int) {
            activity?.navigate(id, Bundle())
        }

        fun hasProfilePictureFromAccountManagers(accountManagers: Array<AuthRepo>): Boolean {
            for (syncApi in accountManagers) {
                val login = syncApi.authUser()
                val pic = login?.profilePicture ?: continue

                binding.settingsProfilePic.let { imageView ->
                    imageView.loadImage(pic) {
                        error { getImageFromDrawable(context ?: return@error null, errorProfilePic) }
                    }
                }
                binding.settingsProfileText.text = login.name
                return true 
            }
            return false 
        }

        if (!hasProfilePictureFromAccountManagers(AccountManager.allApis)) {
            val activity = activity ?: return
            val currentAccount = try {
                DataStoreHelper.accounts.firstOrNull {
                    it.keyIndex == DataStoreHelper.selectedKeyIndex
                } ?: activity.let { DataStoreHelper.getDefaultAccount(activity) }

            } catch (t: IllegalStateException) {
                Log.e("AccountManager", "Activity not found", t)
                null
            }

            binding.settingsProfilePic.loadImage(currentAccount?.image)
            binding.settingsProfileText.text = currentAccount?.name
        }

        binding.apply {
            settingsExtensions.visibility = View.GONE

            // --- TOMBOL TENTANG (METODE JALUR BELAKANG - PASTI BERHASIL) ---
            settingsAbout.setOnClickListener {
                val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.AlertDialogCustom)
                builder.setTitle("Tentang AdiXtream")
                builder.setMessage("AdiXtream dikembangkan oleh michat88.\n\nAplikasi ini berbasis pada proyek open-source CloudStream.\n\nTerima kasih yang sebesar-besarnya kepada Developer CloudStream (Lagradost & Tim) atas kode sumber yang luar biasa ini.")

                // Set tombol netral biasa dulu
                builder.setNeutralButton("Kode Sumber") { _, _ ->
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/michat88/AdiXtream"))
                        startActivity(browserIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                builder.setPositiveButton("Tutup") { dialog, _ ->
                    dialog.dismiss()
                }

                // Buat dialognya tapi jangan ditampilkan dulu
                val dialog = builder.create()

                // RAHASIA: Kita modifikasi tombolnya TEPAT SAAT dialog muncul (OnShow)
                // Ini menjamin tombol sudah punya ukuran, jadi warnanya tidak akan meleset.
                dialog.setOnShowListener {
                    try {
                        val btn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                        
                        // Buat Shader Merah Putih
                        val textHeight = btn.textSize
                        val shader = LinearGradient(
                            0f, 0f, 0f, textHeight,
                            intArrayOf(Color.RED, Color.RED, Color.WHITE, Color.WHITE),
                            floatArrayOf(0f, 0.5f, 0.5f, 1f), // Tepat 50:50
                            Shader.TileMode.CLAMP
                        )
                        
                        // Paksa tombol memakai warna ini
                        btn.paint.shader = shader
                        btn.invalidate() // Gambar ulang sekarang juga
                    } catch (e: Exception) {
                        Log.e("SettingsFragment", "Gagal mewarnai: ${e.message}")
                    }
                }

                dialog.show()
            }
            // ------------------------------------

            listOf(
                settingsGeneral to R.id.action_navigation_global_to_navigation_settings_general,
                settingsPlayer to R.id.action_navigation_global_to_navigation_settings_player,
                settingsCredits to R.id.action_navigation_global_to_navigation_settings_account,
                settingsUi to R.id.action_navigation_global_to_navigation_settings_ui,
                settingsProviders to R.id.action_navigation_global_to_navigation_settings_providers,
                settingsUpdates to R.id.action_navigation_global_to_navigation_settings_updates,
            ).forEach { (view, navigationId) ->
                view.apply {
                    setOnClickListener {
                        navigate(navigationId)
                    }
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

        val appVersion = BuildConfig.APP_VERSION
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
}
