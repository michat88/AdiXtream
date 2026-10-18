package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream4.viewmodels.DigestPair
import com.lagradost.cloudstream4.viewmodels.GithubRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdiXtreamUpdaterTest {
    private val repository: GithubRepository = GithubReleases

    @Test
    fun `shared updater keeps AdiXtream numeric version ordering`() {
        assertTrue(repository.isNewerVersion("4.8.5", "4.8.4"))
        assertTrue(repository.isNewerVersion("4.10.0", "4.9.9"))
        assertTrue(repository.isNewerVersion("5.0.0", "4.99.99"))
        assertFalse(repository.isNewerVersion("4.8.4", "4.8.4"))
        assertFalse(repository.isNewerVersion("4.8.3", "4.8.4"))
        assertFalse(repository.isNewerVersion("4.9.9", "4.10.0"))
    }

    @Test
    fun `APK filename versions remain compatible with existing releases`() {
        assertTrue(repository.isNewerVersion("AdiXtream-v4.8.5.apk", "4.8.4"))
        assertFalse(repository.isNewerVersion("AdiXtream-v4.8.4.apk", "4.8.4"))
        assertFalse(repository.isNewerVersion("invalid.apk", "4.8.4"))
        assertFalse(repository.isNewerVersion("99999999999999999999.8.5", "4.8.4"))
    }

    @Test
    fun `release source stays on AdiXtream after moving the viewmodel`() {
        assertEquals("michat88", GithubReleases.APK_USERNAME)
        assertEquals("AdiXtream", GithubReleases.APK_REPOSITORY)
    }

    @Test
    fun `shared digest parser accepts existing GitHub digest formats`() {
        val colon = DigestPair.parse("sha256:0123abcdef")!!
        val dash = DigestPair.parse("sha256-0123abcdef")!!
        assertEquals("sha256", colon.algorithm)
        assertArrayEquals(byteArrayOf(0x01, 0x23, 0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), colon.digest)
        assertEquals(colon, dash)
        assertEquals(colon.hashCode(), dash.hashCode())
    }

    @Test
    fun `absent and malformed digests retain optional verification behavior`() {
        assertNull(DigestPair.parse(null))
        assertNull(DigestPair.parse("invalid"))
        assertNull(DigestPair.parse("sha256:zz"))
        assertNull(DigestPair.parse("sha256:123"))
    }
}
