package com.lagradost.cloudstream3.ui.settings.extensions

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.PremiumManager
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.cmap
import com.lagradost.cloudstream3.plugins.PLUGIN_VERSION_NOT_SET
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.PluginManager.deletePlugin
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.plugins.PluginManager.unloadPlugin
import com.lagradost.cloudstream3.plugins.RepositoryManager.convertRawGitUrl
import com.lagradost.cloudstream3.plugins.RepositoryManager.parseRepoUrl
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.settings.ApkUpdater.isFileDigest
import com.lagradost.cloudstream4.viewmodels.DigestPair
import com.lagradost.cloudstream4.viewmodels.Plugin
import com.lagradost.cloudstream4.viewmodels.PluginDownloadSource
import com.lagradost.cloudstream4.viewmodels.PluginInfo
import com.lagradost.cloudstream4.viewmodels.PluginStatus
import com.lagradost.cloudstream4.viewmodels.RepositoryInfo
import com.lagradost.cloudstream4.viewmodels.RepositoryManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@InternalAPI
object AndroidRepositoryManager : RepositoryManager {
    private val policy get() = AdiXtreamRepositoryPolicy(
        PremiumManager.FREE_REPO_URL, PremiumManager.PREMIUM_REPO_URL
    )

    private fun requireInternalRepository(url: String) {
        check(policy.allowsCatalog(url)) { "Unsupported extension repository" }
    }

    private fun requirePluginAccess(url: String) {
        val context = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        check(policy.allowsUse(url, PremiumManager.isPremium(context))) {
            "Premium entitlement required"
        }
    }

    private suspend fun <T> protectRepositoryErrors(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Do not put transport exceptions (which can include internal URLs) into UI state.
        throw ErrorLoadingException("Unable to access internal extensions")
    }

    fun RepositoryInfo.toData() =
        RepositoryData(
            name = name,
            url = url,
            iconUrl = iconUrl,
            description = description
        )

    fun RepositoryData.toInfo() =
        RepositoryInfo(
            name = name,
            url = url,
            iconUrl = iconUrl,
            description = description
        )

    override fun getRepositories(): List<RepositoryInfo> {
        // Only repositories installed by the entitlement bootstrap, never public/prebuilt ones.
        return (getKey<Array<RepositoryData>>(REPOSITORIES_KEY)
            ?: emptyArray()).filter { policy.allowsCatalog(it.url) }.map { info ->
            info.toInfo()
        }
    }

    override suspend fun getPlugins(
        repository: RepositoryInfo,
        repositoryUuid: Uuid
    ): Pair<RepositoryInfo, List<Plugin>> =
        withContext(Dispatchers.IO) {
          protectRepositoryErrors {
            requireInternalRepository(repository.url)
            val activity =
                CommonActivity.activity ?: throw ErrorLoadingException("No activity found")

            val url = parseRepoUrl(repository.url)
            if (url.isNullOrBlank()) {
                throw ErrorLoadingException(activity.getString(R.string.error_invalid_url))
            }

            val parsedRepository = app.get(
                url = convertRawGitUrl(url),
                cacheTime = 15,
                cacheUnit = TimeUnit.MINUTES
            ).parsed<com.lagradost.cloudstream3.plugins.Repository>()

            /**
             * No sane repository should be able to DDoS the app when navigating to settings, so
             * we limit this to a maximum of 10
             *
             * Note that this list is on all verified repos = 1, as pluginLists is a list of lists
             * */
            require(parsedRepository.pluginLists.size <= 10)

            /** Add this as a new repository -> replaces the old data with new icon,name and description */
            updateRepositoryInfo(
                RepositoryInfo(
                    iconUrl = parsedRepository.iconUrl,
                    url = url,
                    name = parsedRepository.name,
                    description = parsedRepository.description
                )
            )

            val plugins = parsedRepository.pluginLists.cmap { url ->
                /** This should really be a list of size ~ 1 */
                app.get(
                    url = convertRawGitUrl(url),
                    cacheTime = 15,
                    cacheUnit = TimeUnit.MINUTES
                ).parsed<Array<SitePlugin>>()
            }.flatMap { plugins ->
                plugins.map { plugin ->
                    val file = getPluginPath(
                        context = activity,
                        internalName = plugin.internalName,
                        repositoryUrl = url
                    )
                    val language = plugin.language?.trim()
                    Plugin(
                        info = PluginInfo(
                            name = plugin.name,
                            status = plugin.status,
                            internalName = plugin.internalName,
                            version = plugin.version,
                            tvTypes = plugin.tvTypes?.mapNotNull { runCatching { TvType.valueOf(it) }.getOrNull() }
                                ?.toPersistentList()
                                ?: persistentListOf(),
                            authors = plugin.authors.toPersistentList(),
                            iconUrl = plugin.iconUrl,
                            language = if (language.isNullOrBlank()) {
                                null
                            } else {
                                language
                            },
                            description = plugin.description,
                            repositoryUrl = plugin.repositoryUrl
                        ),
                        source = PluginDownloadSource(
                            size = plugin.fileSize,
                            repositoryUrl = url,
                            pluginUrl = plugin.url,
                            internalName = plugin.internalName,
                            digest = DigestPair.parse(plugin.fileHash)
                        ),
                        status = PluginStatus.Idle,
                        downloadLocation = if (file.exists()) {
                            file.absolutePath
                        } else {
                            null
                        },
                        repository = repositoryUuid,
                    )
                }
            }

            val info = RepositoryInfo(
                iconUrl = parsedRepository.iconUrl,
                url = url,
                name = parsedRepository.name,
                description = parsedRepository.description,
            )
            info to plugins
          }
        }

    override suspend fun downloadPlugin(
        source: PluginDownloadSource,
        downloadProgress: (Long, Long?) -> Unit
    ): String = withContext(Dispatchers.IO) {
      protectRepositoryErrors {
        requirePluginAccess(source.repositoryUrl)
        val context = CommonActivity.activity ?: throw ErrorLoadingException("No activity found")
        val file = getPluginPath(
            context = context,
            internalName = source.internalName,
            repositoryUrl = source.repositoryUrl
        )

        /**
         * Before we do anything that needs a network connection,
         * we actually check if we need to update it
         *
         * Because if the file digest is correct, then why do we need to do a network connection?
         * */
        if (isFileDigest(file, source.digest)) {
            return@protectRepositoryErrors file.absolutePath
        }

        val tempFile = File.createTempFile(file.name, ".tmp", context.cacheDir)

        try {
        tempFile.outputStream().use { writeStream ->
            app.get(convertRawGitUrl(source.pluginUrl)).body.use { body ->
                val readStream = body.byteStream()
                ApkUpdater.transfer(
                    writeStream = writeStream,
                    readStream = readStream,
                    length = source.size,
                    downloadProgress = downloadProgress,
                    digest = source.digest
                )
            }
        }

        // Entitlement can expire while the network download is in progress.
        requirePluginAccess(source.repositoryUrl)
        // Always load the plugin
        unloadPlugin(file.absolutePath)

        file.parentFile?.mkdirs() // make the directories as Files.move needs this to work

        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        requirePluginAccess(source.repositoryUrl)
        val result = PluginManager.loadPlugin(
            context = context,
            file = file,
            data = PluginData(
                internalName = source.internalName,
                url = source.pluginUrl,
                isOnline = true,
                filePath = file.absolutePath,
                version = PLUGIN_VERSION_NOT_SET
            )
        )
        if (!result) {
            throw ErrorLoadingException("Unable to load plugin ${source.internalName}")
        }

        return@protectRepositoryErrors file.absolutePath
        } finally {
            tempFile.delete()
        }
      }
    }

    override fun getLocalPlugins(localUuid: Uuid): List<Plugin> {
        val plugins = PluginManager.getPluginsLocal()
        return plugins.map { plugin ->
            Plugin(
                info = PluginInfo(
                    name = plugin.internalName,
                    internalName = plugin.internalName,
                    version = plugin.version,
                    tvTypes = persistentListOf(),
                    authors = persistentListOf(),
                    status = 1,
                    iconUrl = null,
                    language = null,
                    description = null,
                    repositoryUrl = null
                ),
                source = null,
                status = PluginStatus.Idle,
                downloadLocation = plugin.filePath,
                repository = localUuid,
            )
        }
    }

    override suspend fun deletePlugin(location: String) {
        deletePlugin(File(location))
    }

    override suspend fun deleteRepository(repository: RepositoryInfo) {
        // Repository replacement is exclusively owned by the entitlement startup flow.
        throw ErrorLoadingException("Repository management is disabled")
    }

    override suspend fun updateRepositoryInfo(repository: RepositoryInfo) {
        requireInternalRepository(repository.url)
        // Metadata refresh must never install or switch a customer's target repository.
        com.lagradost.cloudstream3.plugins.RepositoryManager.updateRepositoryMetadata(
            repository.toData()
        )
    }
}
