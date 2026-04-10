package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.pm.PackageManager.NameNotFoundException
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash // IMPORT BARU DARI CLOUDSTREAM
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    // --- PENYESUAIAN ADIXTREAM (DIPERTAHANKAN) ---
    private const val GITHUB_USER_NAME = "michat88"
    private const val GITHUB_REPO = "AdiXtream"

    private const val PRERELEASE_PACKAGE_NAME = "com.lagradost.cloudstream3.prerelease"
    private const val LOG_TAG = "InAppUpdater"

    private data class GithubAsset(
        @JsonProperty("name") val name: String,
        @JsonProperty("size") val size: Int,
        @JsonProperty("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") val contentType: String,
    )

    private data class GithubRelease(
        @JsonProperty("tag_name") val tagName: String,
        @JsonProperty("body") val body: String,
        @JsonProperty("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") val targetCommitish: String,
        @JsonProperty("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") val nodeId: String,
    )

    private data class GithubObject(
        @JsonProperty("sha") val sha: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("url") val url: String,
    )

    private data class GithubTag(
        @JsonProperty("object") val githubObject: GithubObject,
    )

    private data class Update(
        @JsonProperty("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") val updateNodeId: String?,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            when {
                BuildConfig.DEBUG -> Update(false, null, null, null, null)
                BuildConfig.FLAVOR == "prerelease" || installPrerelease -> getPreReleaseUpdate()
                else -> getReleaseUpdate()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(false, null, null, null, null)
        }
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<List<GithubRelease>>(app.get(url, headers = headers).text)

        val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+))\.apk)""")
        val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")
        val foundList = response.filter { !it.prerelease }.sortedWith(compareBy { release ->
            release.assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" }?.name?.let { it1 ->
                versionRegex.find(it1)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }
        }).toList()

        val found = foundList.lastOrNull()
        val foundAsset = found?.assets?.getOrNull(0)
        val foundVersion = foundAsset?.name?.let { versionRegex.find(it) } ?: return Update(false, null, null, null, null)

        val currentVersion = packageName?.let { packageManager.getPackageInfo(it, 0) }
        val shouldUpdate = if (foundAsset.browserDownloadUrl.isBlank()) false else {
            currentVersion?.versionName?.let { versionName ->
                versionRegexLocal.find(versionName)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }?.compareTo(foundVersion.groupValues.let {
                it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
            })!! < 0
        }

        return Update(shouldUpdate, foundAsset.browserDownloadUrl, foundVersion.groupValues[2], found.body, found.nodeId)
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<List<GithubRelease>>(app.get(releaseUrl, headers = headers).text)

        val found = response.lastOrNull { it.prerelease || it.tagName == "pre-release" }
        val foundAsset = found?.assets?.firstOrNull { it.contentType == "application/vnd.android.package-archive" } ?: return Update(false, null, null, null, null)

        val tagResponse = parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        val updateCommitHash = tagResponse.githubObject.sha.trim().take(7)

        // --- SISTEM PENGECEKAN DINAMIS (DIADopsi DARI CLOUDSTREAM) ---
        return Update(currentCommitHash() != updateCommitHash, foundAsset.browserDownloadUrl, updateCommitHash, found.body, found.nodeId)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }

    suspend fun Activity.runAutoUpdate(checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false): Boolean {
        // --- SISTEM KEAMANAN UNDUHAN GANDA (DIPERTAHANKAN DARI ADIXTREAM) ---
        if (PackageInstallerService.isDownloading) {
            Log.d(LOG_TAG, "Update dibatalkan karena unduhan sedang berjalan di latar belakang.")
            return false
        }

        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        if (checkAutoUpdate && !settingsManager.getBoolean(getString(R.string.auto_update_key), true)) return false

        val update = getAppUpdate(installPrerelease)
        if (!update.shouldUpdate || update.updateURL == null) return false
        if (update.updateNodeId == settingsManager.getString(getString(R.string.skip_update_key), "") && checkAutoUpdate) return false

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let { packageManager.getPackageInfo(it, 0) }
                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(getString(R.string.new_update_format).format(currentVersion?.versionName, update.updateVersion))
                
                val sanitizedChangelog = update.changelog?.replace(Regex("\\[(.*?)]\\((.*?)\\)")) { it.groupValues[1] }
                builder.setMessage(sanitizedChangelog)
                
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton
                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        if (settingsManager.getInt(getString(R.string.apk_installer_key), -1) == -1 && isMiUi()) {
                            settingsManager.edit { putInt(getString(R.string.apk_installer_key), 1) }
                        }

                        val currentInstaller = settingsManager.getInt(getString(R.string.apk_installer_key), 1)
                        val intent = PackageInstallerService.getIntent(this@runAutoUpdate, update.updateURL, currentInstaller)
                        ContextCompat.startForegroundService(this@runAutoUpdate, intent)
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }
                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit { putString(getString(R.string.skip_update_key), update.updateNodeId ?: "") }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use { it.readLine() }
    } catch (_: IOException) { null }
}
