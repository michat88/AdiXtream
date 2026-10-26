package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.settings.extensions.AdiXtreamRepositoryPolicy
import com.lagradost.cloudstream4.viewmodels.DerivedPluginsState
import com.lagradost.cloudstream4.viewmodels.DerivedRepositoryState
import com.lagradost.cloudstream4.viewmodels.FilterPlugin
import com.lagradost.cloudstream4.viewmodels.Plugin
import com.lagradost.cloudstream4.viewmodels.PluginInfo
import com.lagradost.cloudstream4.viewmodels.PluginStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AdiXtreamExtensionsTest {
    private val policy = AdiXtreamRepositoryPolicy("internal-free", "internal-premium")

    @Test
    fun `catalog stays visible but Premium use requires entitlement`() {
        assertTrue(policy.allowsCatalog("internal-premium"))
        assertFalse(policy.allowsUse("internal-premium", isPremium = false))
        assertTrue(policy.allowsUse("internal-premium", isPremium = true))
        assertTrue(policy.allowsUse("internal-free", isPremium = false))
    }

    @Test
    fun `unknown and blank repository identities fail closed`() {
        for (premium in listOf(false, true)) {
            assertFalse(policy.allowsUse("external", premium))
            assertFalse(policy.allowsUse("internal-premium/suffix", premium))
            assertFalse(policy.allowsUse("", premium))
            assertFalse(AdiXtreamRepositoryPolicy("", "").allowsUse("", premium))
        }
        assertFalse(policy.allowsCatalog("external"))
        assertFalse(policy.allowsCatalog("internal-free/suffix"))
    }

    @Test
    fun `expiry revokes Premium use without hiding the catalog`() {
        assertTrue(policy.allowsUse("internal-premium", true))
        assertFalse(policy.allowsUse("internal-premium", false))
        assertTrue(policy.allowsCatalog("internal-premium"))
    }

    @Test
    fun `updating a plugin does not leave phantom content types or counts`() {
        val movie = plugin(TvType.Movie)
        val downloaded = movie.copy(downloadLocation = "installed-plugin")
        val state = DerivedRepositoryState().adding(movie).removing(movie).adding(downloaded)
        assertEquals(1, state.total)
        assertEquals(1, state.downloadedAndEnabled)
        assertEquals(0, state.notDownloaded)
        assertTrue(state.containsType(TvType.Movie))
        val removed = state.removing(downloaded)
        assertEquals(DerivedRepositoryState(), removed)
        assertFalse(removed.containsType(TvType.Movie))
    }

    @Test
    fun `unknown content type is removed from Other when plugin disappears`() {
        val other = plugin()
        val state = DerivedRepositoryState().adding(other)
        assertTrue(state.containsType(TvType.Others))
        val removed = state.removing(other)
        assertFalse(removed.containsType(TvType.Others))
        assertEquals(0, removed.total)
    }

    @Test
    fun `language-only metadata updates invalidate derived state`() {
        val original = plugin(TvType.Movie)
        val translated = original.copy(info = original.info.copy(language = "id"))
        val before = DerivedRepositoryState().adding(original)
        val after = before.removing(original).adding(translated)
        assertFalse(before == after)
        assertFalse(after.languageCount.containsKey("en"))
        assertEquals(1, after.languageCount["id"])
    }

    @Test
    fun `repository stats remain independent across plugin updates`() {
        val first = plugin(TvType.Movie)
        val second = plugin(TvType.TvSeries)
        val state = DerivedPluginsState().adding(first).adding(second).removing(first)
        assertEquals(1, state.sumOfRepositories.total)
        assertEquals(0, state.repositories[first.repository]?.total)
        assertEquals(1, state.repositories[second.repository]?.total)
        assertFalse(state.sumOfRepositories.containsType(TvType.Movie))
        assertTrue(state.sumOfRepositories.containsType(TvType.TvSeries))
    }

    @Test
    fun `search and language filters preserve repository isolation`() {
        val first = plugin(TvType.Movie)
        val otherRepository = first.copy(repository = Uuid.random())
        val filter = FilterPlugin(repository = first.repository)
        assertTrue(filter(first))
        assertFalse(filter(otherRepository))
        assertFalse(filter.copy(removeLanguages = persistentSetOf("en"))(first))
        assertTrue(filter.copy(query = filter.query.update("movie"))(first))
        assertFalse(filter.copy(query = filter.query.update("unmatched"))(first))
    }

    private fun plugin(vararg types: TvType) = Plugin(
        info = PluginInfo(
            name = "Movie provider",
            internalName = "MovieProvider",
            version = 1,
            tvTypes = persistentListOf(*types),
            authors = persistentListOf(),
            status = 1,
            iconUrl = null,
            language = "en",
            description = null,
            repositoryUrl = null,
        ),
        source = null,
        status = PluginStatus.Idle,
        downloadLocation = null,
        repository = Uuid.random(),
    )
}
