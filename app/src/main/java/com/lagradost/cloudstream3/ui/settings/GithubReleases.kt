package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.viewmodels.GithubFile
import com.lagradost.cloudstream4.viewmodels.GithubRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.FileNotFoundException

object GithubReleases : GithubRepository {
    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        //@JsonProperty("type") @SerialName("type") val type: String,
        ///@JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        //@JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    const val APK_USERNAME = "michat88"
    const val APK_REPOSITORY = "AdiXtream"
    const val APK_PRERELEASE = "pre-release"
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    @Throws
    override suspend fun getSha(
        userName: String,
        repository: String,
        tag: String,
    ): String {
        return app.get(
            url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
            headers = defaultHeaders
        ).parsed<GithubTag>().githubObject.sha
    }

    @Throws
    override suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile {
        val selected: Pair<GithubRelease, GithubAsset> = if (prerelease) {
            val release = app.get(
                "https://api.github.com/repos/$userName/$repository/releases/tags/$prereleaseTag",
                headers = defaultHeaders
            ).parsed<GithubRelease>()
            val asset = release.assets.firstOrNull { it.contentType == contentType && it.name.endsWith(".apk", true) }
                ?: throw FileNotFoundException()
            release to asset
        } else {
            // Same releases-list and numeric APK version selection used by existing AdiXtream.
            val releases = app.get(
                "https://api.github.com/repos/$userName/$repository/releases",
                headers = defaultHeaders
            ).parsed<Array<GithubRelease>>()
            releases.filter { !it.prerelease }.flatMap { release ->
                release.assets.filter {
                    it.contentType == contentType && it.name.endsWith(".apk", true) && versionParts(it.name) != null
                }.map { release to it }
            }.maxWithOrNull { a, b -> compareVersions(a.second.name, b.second.name) } ?: throw FileNotFoundException()
        }
        val (latestRelease, foundAsset) = selected

        return GithubFile(
            digest = foundAsset.digest,
            downloadUrl = foundAsset.browserDownloadUrl,
            displayName = if (prerelease) foundAsset.name.substringBeforeLast(".") else versionParts(foundAsset.name)!!.joinToString("."),
            changeLog = latestRelease.body,
            tagName = latestRelease.tagName,
            nodeId = latestRelease.nodeId
        )
    }
    private fun versionParts(value: String): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    private fun compareVersions(candidate: String, current: String): Int {
        val a = versionParts(candidate) ?: return -1
        val b = versionParts(current) ?: return 1
        for ((left, right) in a.zip(b)) {
            val comparison = left.compareTo(right)
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun isNewerVersion(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0
}