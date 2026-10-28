package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.plugins.BootstrapProgress
import com.lagradost.cloudstream3.plugins.RepositoryBootstrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RepositoryBootstrapTest {
    @Test fun `partial bootstrap retains old repository and resumes missing plugins after restart`() = runBlocking {
        var progress: BootstrapProgress? = null
        var committed = false
        val installed = mutableSetOf<String>()
        val first = RepositoryBootstrap({ progress }, { progress = it })
        assertFalse(first.prepare(listOf("one", "two"), false, { true }, { name ->
            if (name == "one") { installed.add(name); true } else false
        }, { committed = true; true }))
        assertFalse(committed)
        assertEquals(listOf("two"), progress!!.pending)
        val retried = mutableListOf<String>()
        val restarted = RepositoryBootstrap({ progress }, { progress = it })
        assertTrue(restarted.prepare(listOf("one", "two"), false, { true }, { name ->
            retried.add(name); installed.add(name); true
        }, { assertEquals(setOf("one", "two"), installed); committed = true; true }))
        assertEquals(listOf("two"), retried)
        assertTrue(committed); assertTrue(progress!!.complete)
    }

    @Test fun `cancellation retains full initial plan for the replacement activity`() = runBlocking {
        var progress: BootstrapProgress? = null
        val bootstrap = RepositoryBootstrap({ progress }, { progress = it })
        try {
            bootstrap.prepare(listOf("one", "two"), true, { true }, {
                throw CancellationException("synthetic process stop")
            }, { fail("Cannot remove old repository"); true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(setOf("one", "two"), progress!!.pending.toSet())
        assertFalse(progress!!.complete)
    }

    @Test fun `revocation while preparing plugins prevents repository switch`() = runBlocking {
        var progress: BootstrapProgress? = null
        var entitled = true
        var committed = false
        assertFalse(RepositoryBootstrap({ progress }, { progress = it }).prepare(
            listOf("one"), true, { entitled }, { entitled = false; true }, { committed = true; true }
        ))
        assertFalse(committed); assertFalse(progress!!.complete)
    }

    @Test fun `completed installation does not reinstall deliberate deletions`() = runBlocking {
        var progress: BootstrapProgress? = BootstrapProgress(emptyList(), complete = true)
        assertTrue(RepositoryBootstrap({ progress }, { progress = it }).prepare(
            listOf("removed-by-user"), false, { true }, { fail("Must preserve manual removal"); true }, { true }
        ))
        assertTrue(progress!!.complete)
    }

    @Test fun `changing repositories validates every target plugin before committing`() = runBlocking {
        var progress: BootstrapProgress? = BootstrapProgress(emptyList(), complete = true)
        val validated = mutableSetOf<String>()
        assertTrue(RepositoryBootstrap({ progress }, { progress = it }).prepare(
            listOf("target-one", "target-two"), true, { true }, { validated.add(it); true }, {
                assertEquals(setOf("target-one", "target-two"), validated); true
            }
        ))
    }

    @Test fun `catalog no longer listing a pending plugin does not trap bootstrap forever`() = runBlocking {
        var progress: BootstrapProgress? = BootstrapProgress(listOf("retired", "remaining"))
        assertTrue(RepositoryBootstrap({ progress }, { progress = it }).prepare(
            listOf("remaining"), false, { true }, { assertEquals("remaining", it); true }, { true }
        ))
        assertTrue(progress!!.complete)
    }

    @Test fun `empty failed or forbidden target never commits or fabricates completion`() = runBlocking {
        for (catalog in listOf(emptyList(), listOf("one"))) {
            var progress: BootstrapProgress? = null
            assertFalse(RepositoryBootstrap({ progress }, { progress = it }).prepare(
                catalog, true, { false }, { fail("Not allowed"); true }, { fail("No switch"); true }
            ))
            assertNull(progress)
        }
    }

    @Test fun `failed repository persistence keeps bootstrap incomplete`() = runBlocking {
        var progress: BootstrapProgress? = null
        assertFalse(RepositoryBootstrap({ progress }, { progress = it }).prepare(
            listOf("one"), true, { true }, { true }, { false }
        ))
        assertFalse(progress!!.complete)
    }

    @Test fun `failure to save initial plan prevents all downloads and cleanup`() = runBlocking {
        try {
            RepositoryBootstrap({ null }, { error("synthetic storage failure") }).prepare(
                listOf("one"), true, { true }, { fail("Plan not saved"); true }, { fail("No cleanup"); true }
            )
            fail("Storage failure must propagate")
        } catch (_: IllegalStateException) { }
    }
}
