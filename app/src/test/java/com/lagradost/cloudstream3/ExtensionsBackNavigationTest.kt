package com.lagradost.cloudstream3

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import com.lagradost.cloudstream3.ui.settings.extensions.handlesExtensionsBack
import com.lagradost.cloudstream4.viewmodels.ExtensionsState
import com.lagradost.cloudstream4.viewmodels.FilterPlugin
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ExtensionsBackNavigationTest {
    private val repositoryState = ExtensionsState(
        searchPlugins = true,
        pluginFilter = FilterPlugin(repository = Uuid.random())
    )

    @Test
    fun `system back from the auto-selected catalog reaches parent navigation once`() {
        val back = BackDispatch(repositoryState, hasQuery = false)
        back.dispatcher.onBackPressed()
        assertEquals(listOf("parent"), back.events)
    }

    @Test
    fun `toolbar back from the catalog uses the same parent navigation`() {
        val back = BackDispatch(repositoryState, hasQuery = false)
        // LocalBackPress supplied to AppBar is this dispatcher entry point.
        val toolbarBack = back.dispatcher::onBackPressed
        toolbarBack()
        assertEquals(listOf("parent"), back.events)
    }

    @Test
    fun `back closes a repository query then the next back leaves the catalog`() {
        val back = BackDispatch(repositoryState, hasQuery = true)
        back.dispatcher.onBackPressed()
        assertEquals(listOf("search"), back.events)

        // Recomposition after clearing the query keeps the selected repository.
        back.update(repositoryState, hasQuery = false)
        back.dispatcher.onBackPressed()
        assertEquals(listOf("search", "parent"), back.events)
    }

    @Test
    fun `global plugin search consumes back before returning control to parent`() {
        val back = BackDispatch(ExtensionsState(searchPlugins = true), hasQuery = false)
        back.dispatcher.onBackPressed()
        assertEquals(listOf("search"), back.events)

        back.update(ExtensionsState(), hasQuery = false)
        back.dispatcher.onBackPressed()
        assertEquals(listOf("search", "parent"), back.events)
    }

    private class BackDispatch(state: ExtensionsState, hasQuery: Boolean) {
        val dispatcher = OnBackPressedDispatcher()
        val events = mutableListOf<String>()
        private val search = object : OnBackPressedCallback(state.handlesExtensionsBack(hasQuery)) {
            override fun handleOnBackPressed() {
                events += "search"
            }
        }

        init {
            dispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    events += "parent"
                }
            })
            dispatcher.addCallback(search)
        }

        fun update(state: ExtensionsState, hasQuery: Boolean) {
            search.isEnabled = state.handlesExtensionsBack(hasQuery)
        }
    }
}
