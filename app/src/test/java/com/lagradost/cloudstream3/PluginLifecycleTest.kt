package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.plugins.allowsPluginPath
import com.lagradost.cloudstream3.plugins.initializePluginTransaction
import com.lagradost.cloudstream3.plugins.replacePluginFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginLifecycleTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `premium path checks current entitlement without blocking Free or local plugins`() {
        val premium = File(folder.root, "premium")
        var entitled = true
        val path = File(premium, "plugin.cs3").absolutePath
        assertTrue(allowsPluginPath(path, premium) { entitled })
        entitled = false
        assertFalse(allowsPluginPath(path, premium) { entitled })
        assertFalse(allowsPluginPath(File(premium, "../premium/plugin.cs3").path, premium) { entitled })
        assertTrue(allowsPluginPath(File(folder.root, "free/plugin.cs3").path, premium) { entitled })
        assertTrue(allowsPluginPath(null, premium) { entitled })
    }

    @Test fun `failed initializer removes partial registrations and really retries next time`() {
        val providers = mutableListOf<String>()
        val registry = mutableSetOf<String>()
        var calls = 0
        var fail = true
        fun load() = initializePluginTransaction(
            allowed = { true },
            initialize = { calls++; providers.add("provider"); if (fail) error("synthetic initializer") },
            publish = { registry.add("plugin") },
            rollback = { providers.clear(); registry.clear() },
        )
        assertTrue(runCatching { load() }.isFailure)
        assertTrue(providers.isEmpty()); assertTrue(registry.isEmpty())
        fail = false
        assertTrue(load())
        assertEquals(2, calls)
        assertEquals(listOf("provider"), providers)
        assertEquals(setOf("plugin"), registry)
    }

    @Test fun `revocation during initializer removes providers before registry publication`() {
        var entitled = true
        val providers = mutableListOf<String>()
        var published = false
        assertFalse(initializePluginTransaction(
            allowed = { entitled },
            initialize = { providers.add("provider"); entitled = false },
            publish = { published = true },
            rollback = { providers.clear() },
        ))
        assertFalse(published); assertTrue(providers.isEmpty())
    }

    @Test fun `publication failure also removes partial plugin state`() {
        var partial = false
        assertTrue(runCatching {
            initializePluginTransaction({ true }, {}, { partial = true; error("synthetic publication") }, { partial = false })
        }.isFailure)
        assertFalse(partial)
    }

    @Test fun `successful update retains new bytes and removes rollback files`() = runBlocking {
        val old = folder.newFile("plugin.cs3").apply { writeText("old") }
        val staged = folder.newFile("staged.tmp").apply { writeText("new") }
        var restored = false
        assertTrue(replacePluginFile(staged, old, { true }, {
            assertEquals("new", old.readText()); true
        }, { restored = true }))
        assertEquals("new", old.readText()); assertFalse(restored)
        assertEquals(listOf("plugin.cs3"), folder.root.list()!!.toList())
    }

    @Test fun `failed replacement restores old bytes before old plugin is reloaded`() = runBlocking {
        val old = folder.newFile("plugin.cs3").apply { writeText("old") }
        val staged = folder.newFile("staged.tmp").apply { writeText("bad") }
        var restored = false
        assertFalse(replacePluginFile(staged, old, { true }, { false }, {
            assertEquals("old", old.readText()); restored = true
        }))
        assertTrue(restored)
        assertEquals(listOf("plugin.cs3"), folder.root.list()!!.toList())
    }

    @Test fun `cancellation during activation cannot interrupt file rollback`() = runBlocking {
        val old = folder.newFile("plugin.cs3").apply { writeText("old") }
        val staged = folder.newFile("staged.tmp").apply { writeText("new") }
        var restored = false
        try {
            replacePluginFile(staged, old, { true }, { throw CancellationException("synthetic cancellation") }, {
                restored = true
            })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(restored); assertEquals("old", old.readText())
    }

    @Test fun `revocation during activation rolls back bytes without granting access again`() = runBlocking {
        val old = folder.newFile("plugin.cs3").apply { writeText("old") }
        val staged = folder.newFile("staged.tmp").apply { writeText("new") }
        var entitled = true
        var restored = false
        assertFalse(replacePluginFile(staged, old, { entitled }, { entitled = false; true }, {
            restored = true
        }))
        assertTrue(restored); assertFalse(entitled); assertEquals("old", old.readText())
    }

    @Test fun `denied installation leaves working file untouched and never activates`() = runBlocking {
        val old = folder.newFile("plugin.cs3").apply { writeText("old") }
        val staged = folder.newFile("staged.tmp").apply { writeText("new") }
        assertFalse(replacePluginFile(staged, old, { false }, { fail("Must not initialize"); true }, {}))
        assertEquals("old", old.readText())
    }

    @Test fun `failed first install leaves no installed file`() = runBlocking {
        val destination = File(folder.root, "new/plugin.cs3")
        val staged = folder.newFile("staged.tmp").apply { writeText("bad") }
        assertFalse(replacePluginFile(staged, destination, { true }, { false }, {}))
        assertFalse(destination.exists())
    }
}
