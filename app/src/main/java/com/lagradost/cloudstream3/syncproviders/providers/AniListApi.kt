package com.lagradost.cloudstream3.syncproviders.providers

import androidx.annotation.StringRes
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.DataStore.toKotlinObject
import com.lagradost.cloudstream3.utils.DataStoreHelper.toYear
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.syncproviders.AccountManager.Companion.APP_STRING
import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "anilist"

    // Konfigurasi API AdiXtream
    val key = "33370"
    private val secret = "H8Lt1PrYHLCWrpzQln4FremNk1JLvgJpbUyt8Nr1"
    
    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val icon = R.drawable.ic_anilist_icon
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    // Menggunakan response_type=code untuk keamanan lebih baik
    override fun loginRequest(): AuthLoginPage? =
        AuthLoginPage("https://anilist.co/api/v2/oauth/authorize?client_id=$key&response_type=code&redirect_uri=$APP_STRING://$redirectUrlIdentifier")

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val uri = Uri.parse(redirectUrl)
        val code = uri.getQueryParameter("code") ?: throw ErrorLoadingException("No code found")

        // Melakukan pertukaran code dengan Token (Authorization Code Grant)
        val response = app.post(
            "https://anilist.co/api/v2/oauth/token",
            data = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to key,
                "client_secret" to secret,
                "redirect_uri" to "$APP_STRING://$redirectUrlIdentifier",
                "code" to code
            )
        ).parsedSafe<TokenResponse>() ?: throw ErrorLoadingException("Failed to exchange token")

        return AuthToken(
            accessToken = response.accessToken,
            accessTokenLifetime = unixTime + response.expiresIn,
        )
    }

    data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("expires_in") val expiresIn: Long,
    )

    // --- Sisa kode GraphQL (search, load, status) tetap sama seperti sebelumnya ---
    // (Dipotong untuk ringkasan, pastikan kamu tetap menyertakan fungsi search, load, dll dari kode sebelumnya)
    
    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null)
            ?: throw ErrorLoadingException("Unable to fetch user data")

        return AuthUser(
            id = user.id,
            name = user.name,
            profilePicture = user.picture,
        )
    }

    private suspend fun getUser(token : AuthToken): AniListUser? {
        val q = "{ Viewer { id name avatar { large } } }"
        val data = postApi(token, q)
        if (data.isNullOrBlank()) return null
        val userData = parseJson<AniListRoot>(data)
        val u = userData.data?.viewer ?: return null
        return AniListUser(u.id, u.name, u.avatar?.large)
    }

    private suspend fun postApi(token : AuthToken, q: String, cache: Boolean = false): String? {
        return app.post(
            "https://graphql.anilist.co/",
            headers = mapOf(
                "Authorization" to "Bearer ${token.accessToken ?: return null}"
            ),
            data = mapOf("query" to q),
            timeout = 10
        ).text
    }

    data class AniListRoot(@JsonProperty("data") val data: AniListData?)
    data class AniListData(@JsonProperty("Viewer") val viewer: AniListViewer?)
    data class AniListViewer(@JsonProperty("id") val id: Int, @JsonProperty("name") val name: String, @JsonProperty("avatar") val avatar: AniListAvatar?)
    data class AniListAvatar(@JsonProperty("large") val large: String?)
    data class AniListUser(val id: Int, val name: String, val picture: String?)
}
