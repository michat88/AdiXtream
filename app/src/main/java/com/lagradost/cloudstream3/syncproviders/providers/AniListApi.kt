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

    // Kunci API AdiXtream
    val key = "33370"
    private val secret = "H8Lt1PrYHLCWrpzQln4FremNk1JLvgJpbUyt8Nr1"

    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val icon = R.drawable.ic_anilist_icon
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    override fun loginRequest(): AuthLoginPage? =
        AuthLoginPage("https://anilist.co/api/v2/oauth/authorize?client_id=$key&response_type=code&redirect_uri=$APP_STRING://$redirectUrlIdentifier")

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val uri = Uri.parse(redirectUrl)
        val code = uri.getQueryParameter("code") ?: throw ErrorLoadingException("No code found")

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

    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null)
            ?: throw ErrorLoadingException("Unable to fetch user data")

        return AuthUser(
            id = user.id,
            name = user.name,
            profilePicture = user.picture,
        )
    }

    override fun urlToId(url: String): String? =
        url.removePrefix("$mainUrl/anime/").removeSuffix("/")

    private fun getUrlFromId(id: Int): String {
        return "$mainUrl/anime/$id"
    }

    override suspend fun search(auth : AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        val data = searchShows(query) ?: return null
        return data.data?.page?.media?.map {
            SyncAPI.SyncSearchResult(
                it.title.romaji ?: "",
                this.name,
                it.id.toString(),
                getUrlFromId(it.id),
                it.bannerImage
            )
        }
    }

    override suspend fun load(auth : AuthData?, id: String): SyncAPI.SyncResult? {
        val internalId = (Regex("anilist\\.co/anime/(\\d*)").find(id)?.groupValues?.getOrNull(1)
            ?: id).toIntOrNull() ?: throw ErrorLoadingException("Invalid internalId")
        val season = getSeason(internalId).data.media

        return SyncAPI.SyncResult(
            season.id.toString(),
            nextAiring = season.nextAiringEpisode?.let {
                NextAiring(
                    it.episode ?: return@let null,
                    (it.timeUntilAiring ?: return@let null) + unixTime
                )
            },
            title = season.title?.userPreferred,
            synonyms = season.synonyms,
            isAdult = season.isAdult,
            totalEpisodes = season.episodes,
            synopsis = season.description,
            actors = season.characters?.edges?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                ActorData(
                    actor = Actor(
                        name = node.name?.userPreferred ?: node.name?.full ?: node.name?.native
                        ?: return@mapNotNull null,
                        image = node.image?.large ?: node.image?.medium
                    ),
                    role = when (edge.role) {
                        "MAIN" -> ActorRole.Main
                        "SUPPORTING" -> ActorRole.Supporting
                        "BACKGROUND" -> ActorRole.Background
                        else -> null
                    },
                    voiceActor = edge.voiceActors?.firstNotNullOfOrNull { staff ->
                        Actor(
                            name = staff.name?.userPreferred ?: staff.name?.full
                            ?: staff.name?.native
                            ?: return@mapNotNull null,
                            image = staff.image?.large ?: staff.image?.medium
                        )
                    }
                )
            },
            publicScore = Score.from100(season.averageScore),
            recommendations = season.recommendations?.edges?.mapNotNull { rec ->
                val recMedia = rec.node.mediaRecommendation
                SyncAPI.SyncSearchResult(
                    name = recMedia?.title?.userPreferred ?: return@mapNotNull null,
                    this.name,
                    recMedia.id?.toString() ?: return@mapNotNull null,
                    getUrlFromId(recMedia.id),
                    recMedia.coverImage?.extraLarge ?: recMedia.coverImage?.large
                    ?: recMedia.coverImage?.medium
                )
            },
            trailers = when (season.trailer?.site?.lowercase()?.trim()) {
                "youtube" -> listOf("https://www.youtube.com/watch?v=${season.trailer.id}")
                else -> null
            }
        )
    }

    override suspend fun status(auth : AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        val internalId = id.toIntOrNull() ?: return null
        val data = getDataAboutId(auth ?: return null, internalId) ?: return null

        return SyncAPI.SyncStatus(
            score = Score.from100(data.score),
            watchedEpisodes = data.progress,
            status = SyncWatchType.fromInternalId(data.type?.value ?: return null),
            isFavorite = data.isFavourite,
            maxEpisodes = data.episodes,
        )
    }

    override suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus
    ): Boolean {
        return postDataAboutId(
            auth ?: return false,
            id.toIntOrNull() ?: return false,
            fromIntToAnimeStatus(newStatus.status.internalId),
            newStatus.score,
            newStatus.watchedEpisodes
        )
    }

    private suspend fun postDataAboutId(
        auth : AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?
    ): Boolean {
        val userID = auth.user.id

        val q =
            if (type == AniListStatusType.None) {
                val idQuery = """
                  query MediaList(${'$'}userId: Int = $userID, ${'$'}mediaId: Int = $id) {
                    MediaList(userId: ${'$'}userId, mediaId: ${'$'}mediaId) {
                      id
                    }
                  }
                """
                val response = postApi(auth.token, idQuery)
                val listId =
                    tryParseJson<MediaListItemRoot>(response)?.data?.mediaList?.id ?: return false
                """
                    mutation(${'$'}id: Int = $listId) {
                        DeleteMediaListEntry(id: ${'$'}id) {
                            deleted
                        }
                    }
                """
            } else {
                """mutation (${'$'}id: Int = $id, ${'$'}status: MediaListStatus = ${
                    aniListStatusString[maxOf(
                        0,
                        type.value
                    )]
                }, ${if (score != null) "${'$'}scoreRaw: Int = ${score.toInt(100)}" else ""} , ${if (progress != null) "${'$'}progress: Int = $progress" else ""}) {
                    SaveMediaListEntry (mediaId: ${'$'}id, status: ${'$'}status, scoreRaw: ${'$'}scoreRaw, progress: ${'$'}progress) {
                        id
                        status
                        progress
                        score
                    }
                }"""
            }

        val data = postApi(auth.token, q)
        return data != ""
    }

    private suspend fun postApi(token : AuthToken, q: String, cache: Boolean = false): String? {
        return app.post(
            "https://graphql.anilist.co/",
            headers = mapOf(
                "Authorization" to "Bearer ${token.accessToken ?: return null}"
            ),
            data = mapOf("query" to q)
        ).text
    }

    private suspend fun getUser(token : AuthToken): AniListUser? {
        val q = "{ Viewer { id name avatar { large } } }"
        val res = postApi(token, q) ?: return null
        val u = parseJson<AniListRoot>(res).data?.viewer ?: return null
        return AniListUser(u.id, u.name, u.avatar?.large)
    }

    override suspend fun library(auth : AuthData?): SyncAPI.LibraryMetadata? {
        val list = getAniListAnimeListSmart(auth ?: return null)?.groupBy {
            convertAniListStringToStatus(it.status ?: "").stringRes
        }?.mapValues { group ->
            group.value.map { it.entries.map { entry -> entry.toLibraryItem() } }.flatten()
        } ?: emptyMap()

        val baseMap =
            AniListStatusType.entries.filter { it.value >= 0 }.associate {
                it.stringRes to emptyList<SyncAPI.LibraryItem>()
            }

        return SyncAPI.LibraryMetadata(
            (baseMap + list).map { SyncAPI.LibraryList(txt(it.key), it.value) },
            setOf(
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.ReleaseDateNew,
                ListSorting.ReleaseDateOld,
                ListSorting.RatingHigh,
                ListSorting.RatingLow,
            )
        )
    }

    private suspend fun getAniListAnimeListSmart(auth: AuthData): Array<Lists>? {
        return if (requireLibraryRefresh) {
            val list = getFullAniListList(auth)?.data?.mediaListCollection?.lists?.toTypedArray()
            if (list != null) {
                setKey(ANILIST_CACHED_LIST, auth.user.id.toString(), list)
            }
            list
        } else {
            getKey<Array<Lists>>(
                ANILIST_CACHED_LIST,
                auth.user.id.toString()
            ) as? Array<Lists>
        }
    }

    private suspend fun getFullAniListList(auth : AuthData): FullAnilistList? {
        val userID = auth.user.id
        val query = """
                query (${'$'}userID: Int = $userID, ${'$'}MEDIA: MediaType = ANIME) {
                    MediaListCollection (userId: ${'$'}userID, type: ${'$'}MEDIA) { 
                        lists {
                            status
                            entries
                            {
                                status
                                updatedAt
                                progress
                                score (format: POINT_100)
                                media
                                {
                                    id
                                    idMal
                                    seasonYear
                                    format
                                    episodes
                                    title
                                    {
                                        english
                                        romaji
                                    }
                                    coverImage { extraLarge large medium }
                                    synonyms
                                    description
                                }
                            }
                        }
                    }
                    }
            """
        val text = postApi(auth.token, query)
        return text?.toKotlinObject()
    }

    companion object {
        const val MAX_STALE = 60 * 10
        private val aniListStatusString =
            arrayOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")

        const val ANILIST_CACHED_LIST: String = "anilist_cached_list"

        private fun fixName(name: String): String {
            return name.lowercase(Locale.ROOT).replace(" ", "")
                .replace("[^a-zA-Z0-9]".toRegex(), "")
        }

        private suspend fun searchShows(name: String): GetSearchRoot? {
            try {
                val query = """
                query (${"$"}id: Int, ${"$"}page: Int, ${"$"}search: String, ${"$"}type: MediaType) {
                    Page (page: ${"$"}page, perPage: 10) {
                        media (id: ${"$"}id, search: ${"$"}search, type: ${"$"}type) {
                            id
                            idMal
                            seasonYear
                            startDate { year }
                            title {
                                romaji
                            }
                            averageScore
                            nextAiringEpisode {
                                timeUntilAiring
                                episode
                            }
                            bannerImage
                            recommendations {
                                nodes {
                                    id
                                    mediaRecommendation {
                                        id
                                        title {
                                            english
                                            romaji
                                            userPreferred
                                        }
                                        coverImage { medium large extraLarge }
                                    }
                                }
                            }
                        }
                    }
                }
                """
                val data =
                    mapOf(
                        "query" to query,
                        "variables" to
                                mapOf(
                                    "search" to name,
                                    "page" to 1,
                                    "type" to "ANIME"
                                ).toJson()
                    )

                val res = app.post(
                    "https://graphql.anilist.co/",
                    data = data,
                    timeout = 5000
                ).text.replace("\\", "")
                return res.toKotlinObject()
            } catch (e: Exception) {
                logError(e)
            }
            return null
        }

        enum class AniListStatusType(var value: Int, @StringRes val stringRes: Int) {
            Watching(0, R.string.type_watching),
            Completed(1, R.string.type_completed),
            Paused(2, R.string.type_on_hold),
            Dropped(3, R.string.type_dropped),
            Planning(4, R.string.type_plan_to_watch),
            ReWatching(5, R.string.type_re_watching),
            None(-1, R.string.none)
        }

        fun fromIntToAnimeStatus(inp: Int): AniListStatusType = when (inp) {
            -1 -> AniListStatusType.None
            0 -> AniListStatusType.Watching
            1 -> AniListStatusType.Completed
            2 -> AniListStatusType.Paused
            3 -> AniListStatusType.Dropped
            4 -> AniListStatusType.Planning
            5 -> AniListStatusType.ReWatching
            else -> AniListStatusType.None
        }

        fun convertAniListStringToStatus(string: String): AniListStatusType = fromIntToAnimeStatus(aniListStatusString.indexOf(string))

        private suspend fun getSeason(id: Int): SeasonResponse {
            val q = """
               query (${'$'}id: Int = $id) {
                   Media (id: ${'$'}id, type: ANIME) {
                       id
                       idMal
                       coverImage {
                           extraLarge
                           large
                           medium
                           color
                       }
                       title {
                           romaji
                           english
                           native
                           userPreferred
                       }
                       episodes
                       synonyms
                       averageScore
                       isAdult
                       description(asHtml: false)
                       characters(sort: ROLE page: 1 perPage: 20) {
                           edges {
                               role
                               voiceActors {
                                   name {
                                       userPreferred
                                       full
                                       native
                                   }
                                   image {
                                       large
                                       medium
                                   }
                               }
                               node {
                                   name {
                                       userPreferred
                                       full
                                       native
                                   }
                                   image {
                                       large
                                       medium
                                   }
                               }
                           }
                       }
                       trailer {
                           id
                           site
                       }
                       recommendations {
                           edges {
                               node {
                                   mediaRecommendation {
                                       id
                                       coverImage {
                                           extraLarge
                                           large
                                           medium
                                       }
                                       title {
                                           userPreferred
                                       }
                                   }
                               }
                           }
                       }
                       nextAiringEpisode {
                           episode
                           timeUntilAiring
                       }
                   }
               }
        """
            val data = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to q),
                cacheTime = 0,
            ).text

            return tryParseJson(data) ?: throw ErrorLoadingException("Error parsing $data")
        }
    }

    // --- Data Classes ---
    data class MediaRecommendation(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: Title?,
        @JsonProperty("coverImage") val coverImage: CoverImage?,
    )

    data class FullAnilistList(@JsonProperty("data") val data: Data?)
    data class Title(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?,
        @JsonProperty("userPreferred") val userPreferred: String? = null
    )
    data class CoverImage(
        @JsonProperty("medium") val medium: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("extraLarge") val extraLarge: String?
    )
    data class Media(
        @JsonProperty("id") val id: Int,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("seasonYear") val seasonYear: Int,
        @JsonProperty("title") val title: Title,
        @JsonProperty("description") val description: String?,
        @JsonProperty("coverImage") val coverImage: CoverImage,
        @JsonProperty("synonyms") val synonyms: List<String>,
        @JsonProperty("episodes") val episodes: Int,
    )
    data class Entries(
        @JsonProperty("status") val status: String?,
        @JsonProperty("updatedAt") val updatedAt: Int,
        @JsonProperty("progress") val progress: Int,
        @JsonProperty("score") val score: Int,
        @JsonProperty("media") val media: Media
    ) {
        fun toLibraryItem(): SyncAPI.LibraryItem = SyncAPI.LibraryItem(
            this.media.title.english ?: this.media.title.romaji ?: "",
            "https://anilist.co/anime/${this.media.id}/",
            this.media.id.toString(),
            this.progress,
            this.media.episodes,
            Score.from100(this.score),
            this.updatedAt.toLong(),
            "AniList",
            TvType.Anime,
            this.media.coverImage.extraLarge ?: this.media.coverImage.large ?: "",
            null, null, this.media.seasonYear.toYear(), null, plot = this.media.description
        )
    }
    data class Lists(@JsonProperty("status") val status: String?, @JsonProperty("entries") val entries: List<Entries>)
    data class MediaListCollection(@JsonProperty("lists") val lists: List<Lists>)
    data class Data(@JsonProperty("MediaListCollection") val mediaListCollection: MediaListCollection)
    data class AniListRoot(@JsonProperty("data") val data: AniListData?)
    data class AniListData(@JsonProperty("Viewer") val viewer: AniListViewer?)
    data class AniListViewer(@JsonProperty("id") val id: Int, @JsonProperty("name") val name: String, @JsonProperty("avatar") val avatar: AniListAvatar?)
    data class AniListAvatar(@JsonProperty("large") val large: String?)
    data class AniListUser(val id: Int, val name: String, val picture: String?)
    data class GetDataMedia(@JsonProperty("isFavourite") val isFavourite: Boolean?, @JsonProperty("episodes") val episodes: Int?, @JsonProperty("title") val title: Title?, @JsonProperty("mediaListEntry") val mediaListEntry: GetDataMediaListEntry?)
    data class GetDataMediaListEntry(@JsonProperty("progress") val progress: Int?, @JsonProperty("status") val status: String?, @JsonProperty("score") val score: Int?)
    data class GetDataData(@JsonProperty("Media") val media: GetDataMedia?)
    data class GetDataRoot(@JsonProperty("data") val data: GetDataData?)
    data class SeasonResponse(@JsonProperty("data") val data: SeasonData)
    data class SeasonData(@JsonProperty("Media") val media: SeasonMedia)
    data class SeasonMedia(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: MediaTitle?,
        @JsonProperty("coverImage") val coverImage: MediaCoverImage?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("synonyms") val synonyms: List<String>?,
        @JsonProperty("averageScore") val averageScore: Int?,
        @JsonProperty("isAdult") val isAdult: Boolean?,
        @JsonProperty("trailer") val trailer: MediaTrailer?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("characters") val characters: CharacterConnection?,
        @JsonProperty("recommendations") val recommendations: RecommendationConnection?,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?
    )
    data class MediaTitle(@JsonProperty("romaji") val romaji: String?, @JsonProperty("english") val english: String?, @JsonProperty("native") val native: String?, @JsonProperty("userPreferred") val userPreferred: String?)
    data class MediaCoverImage(@JsonProperty("extraLarge") val extraLarge: String?, @JsonProperty("large") val large: String?, @JsonProperty("medium") val medium: String?, @JsonProperty("color") val color: String?)
    data class MediaTrailer(@JsonProperty("id") val id: String?, @JsonProperty("site") val site: String?)
    data class CharacterConnection(@JsonProperty("edges") val edges: List<CharacterEdge>?)
    data class CharacterEdge(@JsonProperty("role") val role: String?, @JsonProperty("node") val node: Character?, @JsonProperty("voiceActors") val voiceActors: List<Staff>?)
    data class Character(@JsonProperty("name") val name: CharacterName?, @JsonProperty("image") val image: CharacterImage?)
    data class CharacterName(@JsonProperty("userPreferred") val userPreferred: String?, @JsonProperty("full") val full: String?, @JsonProperty("native") val native: String?)
    data class CharacterImage(@JsonProperty("large") val large: String?, @JsonProperty("medium") val medium: String?)
    data class Staff(@JsonProperty("name") val name: StaffName?, @JsonProperty("image") val image: StaffImage?)
    data class StaffName(@JsonProperty("userPreferred") val userPreferred: String?, @JsonProperty("full") val full: String?, @JsonProperty("native") val native: String?)
    data class StaffImage(@JsonProperty("large") val large: String?, @JsonProperty("medium") val medium: String?)
    data class RecommendationConnection(@JsonProperty("edges") val edges: List<RecommendationEdge>?)
    data class RecommendationEdge(@JsonProperty("node") val node: Recommendation)
    data class Recommendation(@JsonProperty("mediaRecommendation") val mediaRecommendation: MediaRecommendation?)
    data class SeasonNextAiringEpisode(@JsonProperty("episode") val episode: Int?, @JsonProperty("timeUntilAiring") val timeUntilAiring: Int?)
    data class GetSearchTitle(@JsonProperty("romaji") val romaji: String?)
    data class StartedAt(@JsonProperty("year") val year: Int?)
    data class GetSearchMedia(@JsonProperty("id") val id: Int, @JsonProperty("idMal") val idMal: Int?, @JsonProperty("seasonYear") val seasonYear: Int, @JsonProperty("title") val title: GetSearchTitle, @JsonProperty("startDate") val startDate: StartedAt, @JsonProperty("bannerImage") val bannerImage: String?)
    data class GetSearchData(@JsonProperty("media") val media: List<GetSearchMedia>?)
    data class GetSearchPage(@JsonProperty("Page") val page: GetSearchData?)
    data class GetSearchRoot(@JsonProperty("data") val data: GetSearchPage?)
    data class MediaListItemRoot(@JsonProperty("data") val data: MediaListItem? = null)
    data class MediaListItem(@JsonProperty("MediaList") val mediaList: MediaListId? = null)
    data class MediaListId(@JsonProperty("id") val id: Long? = null)
    data class AniListTitleHolder(val title: Title?, val isFavourite: Boolean?, val id: Int?, val progress: Int?, val episodes: Int?, val score: Int?, val type: AniListStatusType?)
}
