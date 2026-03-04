package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.syncproviders.providers.Addic7ed
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.LocalList
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.syncproviders.providers.SubDlApi
import com.lagradost.cloudstream3.syncproviders.providers.SubSourceApi
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.concurrent.TimeUnit

abstract class AccountManager {
    companion object {
        // --- Bagian Konfigurasi AdiXtream (Dipertahankan) ---
        const val APP_STRING = "adixtream"
        const val APP_STRING_REPO = "adixtreamrepo"
        const val APP_STRING_PLAYER = "adixtreamplayer"
        const val APP_STRING_SEARCH = "adixtreamsearch"
        const val APP_STRING_RESUME_WATCHING = "adixtreamcontinuewatching"
        const val APP_STRING_SHARE = "csshare"
        
        const val ACCOUNT_TOKEN = "auth_tokens"
        const val ACCOUNT_IDS = "auth_ids"
        const val NONE_ID: Int = -1
        // ------------------------------------

        val malApi = MALApi()
        val kitsuApi = KitsuApi() // Ditambahkan dari update terbaru CloudStream
        val aniListApi = AniListApi()
        val simklApi = SimklApi()
        val localListApi = LocalList()

        val openSubtitlesApi = OpenSubtitlesApi()
        val addic7ed = Addic7ed()
        val subDlApi = SubDlApi()
        val subSourceApi = SubSourceApi() // Ditambahkan dari update terbaru CloudStream

        var cachedAccounts: MutableMap<String, Array<AuthData>> = mutableMapOf()
        var cachedAccountIds: MutableMap<String, Int> = mutableMapOf()

        fun accounts(prefix: String): Array<AuthData> {
            require(prefix != "NONE")
            return getKey<Array<AuthData>>(
                ACCOUNT_TOKEN,
                "${prefix}/${DataStoreHelper.currentAccount}"
            ) ?: arrayOf()
        }

        fun updateAccounts(prefix: String, array: Array<AuthData>) {
            require(prefix != "NONE")
            setKey(ACCOUNT_TOKEN, "${prefix}/${DataStoreHelper.currentAccount}", array)
            synchronized(cachedAccounts) {
                cachedAccounts[prefix] = array
            }
        }

        fun updateAccountsId(prefix: String, id: Int) {
            require(prefix != "NONE")
            setKey(ACCOUNT_IDS, "${prefix}/${DataStoreHelper.currentAccount}", id)
            synchronized(cachedAccountIds) {
                cachedAccountIds[prefix] = id
            }
        }

        val allApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi), // Kitsu ditambahkan kembali
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi),
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi)
        )

        fun updateAccountIds() {
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            synchronized(cachedAccountIds) {
                cachedAccountIds = ids
            }
        }

        init {
            val data = mutableMapOf<String, Array<AuthData>>()
            val ids = mutableMapOf<String, Int>()
            for (api in allApis) {
                data.put(api.idPrefix, accounts(api.idPrefix))
                ids.put(
                    api.idPrefix,
                    getKey<Int>(
                        ACCOUNT_IDS,
                        "${api.idPrefix}/${DataStoreHelper.currentAccount}",
                        NONE_ID
                    ) ?: NONE_ID
                )
            }
            cachedAccounts = data
            cachedAccountIds = ids
        }

        // Fungsi ini disesuaikan dengan update CloudStream terbaru
        fun initMainAPI() {
            LoadResponse.malIdPrefix = malApi.idPrefix
            LoadResponse.kitsuIdPrefix = kitsuApi.idPrefix // Kitsu prefix ditambahkan
            LoadResponse.aniListIdPrefix = aniListApi.idPrefix
            LoadResponse.simklIdPrefix = simklApi.idPrefix
        }

        val subtitleProviders = arrayOf(
            SubtitleRepo(openSubtitlesApi),
            SubtitleRepo(addic7ed),
            SubtitleRepo(subDlApi)
        )
        
        val syncApis = arrayOf(
            SyncRepo(malApi),
            SyncRepo(kitsuApi), // Kitsu ditambahkan kembali
            SyncRepo(aniListApi),
            SyncRepo(simklApi),
            SyncRepo(localListApi)
        )

        // Diperbarui agar persis dengan akurasi format hitungan CloudStream terbaru
        fun secondsToReadable(seconds: Int, completedValue: String): String {
            var secondsLong = seconds.toLong()
            val days = TimeUnit.SECONDS.toDays(secondsLong)
            secondsLong -= TimeUnit.DAYS.toSeconds(days)

            val hours = TimeUnit.SECONDS.toHours(secondsLong)
            secondsLong -= TimeUnit.HOURS.toSeconds(hours)

            val minutes = TimeUnit.SECONDS.toMinutes(secondsLong)
            if (minutes < 0) {
                return completedValue
            }
            
            return "${if (days != 0L) "$days" + "d " else ""}${if (hours != 0L) "$hours" + "h " else ""}${minutes}m"
        }
    }
}
