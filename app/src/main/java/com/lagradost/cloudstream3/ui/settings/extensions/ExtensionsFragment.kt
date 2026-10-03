package com.lagradost.cloudstream3.ui.settings.extensions

import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity.Companion.afterRepositoryLoadedEvent
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.FragmentExtensionsBinding
import com.lagradost.cloudstream3.mvvm.observe
import com.lagradost.cloudstream3.mvvm.observeNullable
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setSystemBarsPadding
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setToolBarScrollFlags
import com.lagradost.cloudstream3.ui.settings.SettingsFragment.Companion.setUpToolbar
import com.lagradost.cloudstream3.utils.setText

class ExtensionsFragment : BaseFragment<FragmentExtensionsBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentExtensionsBinding::inflate)
) {

    private val extensionViewModel: ExtensionsViewModel by activityViewModels()
    private val pluginViewModel: PluginsViewModel by activityViewModels()

    private fun View.setLayoutWidth(weight: Int) {
        val param = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            weight.toFloat()
        )
        this.layoutParams = param
    }

    override fun onResume() {
        super.onResume()
        afterRepositoryLoadedEvent += ::reloadRepositories
    }

    override fun onStop() {
        super.onStop()
        afterRepositoryLoadedEvent -= ::reloadRepositories
    }

    private fun reloadRepositories(success: Boolean = true) {
        extensionViewModel.loadStats()
        extensionViewModel.loadRepositories()
    }

    override fun fixLayout(view: View) {
        setSystemBarsPadding()
    }

    override fun onBindingCreated(binding: FragmentExtensionsBinding) {
        setUpToolbar(R.string.extensions)
        setToolBarScrollFlags()

        binding.repoRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextUp = R.id.settings_toolbar,
                nextDown = R.id.plugin_storage_appbar,
                nextRight = FOCUS_SELF,
                nextLeft = R.id.nav_rail_view
            )

            adapter = RepoAdapter(false, { repo ->
                val navOptions = NavOptions.Builder()
                    .setPopUpTo(R.id.navigation_settings_extensions, true)
                    .build()

                findNavController().navigate(
                    R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                    PluginsFragment.newInstance(repo),
                    navOptions
                )
            }, { /* Internal repositories are managed by MainActivity. */ })
        }

        observe(extensionViewModel.repositories) { repos ->
            binding.repoRecyclerView.isVisible = repos.isNotEmpty()
            binding.blankRepoScreen.isVisible = repos.isEmpty()
            (binding.repoRecyclerView.adapter as? RepoAdapter)?.submitList(repos.toList())
            pluginViewModel.updatePluginList(binding.root.context, repos.toList())


        }

        observeNullable(extensionViewModel.pluginStats) { value ->
            binding.apply {
                if (value == null) {
                    pluginStorageAppbar.isVisible = false
                    return@observeNullable
                }

                pluginStorageAppbar.isVisible = true
                if (value.total == 0) {
                    pluginDownload.setLayoutWidth(1)
                    pluginDisabled.setLayoutWidth(0)
                    pluginNotDownloaded.setLayoutWidth(0)
                } else {
                    pluginDownload.setLayoutWidth(value.downloaded)
                    pluginDisabled.setLayoutWidth(value.disabled)
                    pluginNotDownloaded.setLayoutWidth(value.notDownloaded)
                }
                pluginNotDownloadedTxt.setText(value.notDownloadedText)
                pluginDisabledTxt.setText(value.disabledText)
                pluginDownloadTxt.setText(value.downloadedText)
            }
        }

        binding.pluginStorageAppbar.setOnClickListener {
            val navOptions = NavOptions.Builder()
                .setPopUpTo(R.id.navigation_settings_extensions, true)
                .build()

            findNavController().navigate(
                R.id.navigation_settings_extensions_to_navigation_settings_plugins,
                PluginsFragment.newLocalInstance(
                    getString(R.string.extensions),
                ),
                navOptions
            )
        }

        binding.pluginRecyclerView.apply {
            setLinearListLayout(
                isHorizontal = false,
                nextDown = FOCUS_SELF,
                nextRight = FOCUS_SELF,
            )
            setRecycledViewPool(PluginAdapter.sharedPool)
            adapter =
                PluginAdapter(true) {
                    val urls = extensionViewModel.repositories.value?.toList() ?: emptyList()
                    pluginViewModel.handlePluginAction(activity, urls, it, false)
                }
        }

        observe(pluginViewModel.filteredPlugins) { (scrollToTop, list) ->
            (binding.pluginRecyclerView.adapter as? PluginAdapter)?.submitList(list)
            if (scrollToTop) {
                binding.pluginRecyclerView.scrollToPosition(0)
            }
        }

        binding.settingsToolbar.apply {
            val searchItem = menu?.findItem(R.id.search_button)
            val searchView = searchItem?.actionView as? SearchView

            searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionCollapse(p0: MenuItem): Boolean {
                    binding.pluginRecyclerView.isVisible = false
                    binding.repoRecyclerView.isVisible = true
                    return true
                }

                override fun onMenuItemActionExpand(p0: MenuItem): Boolean {
                    binding.pluginRecyclerView.isVisible = true
                    binding.repoRecyclerView.isVisible = false
                    return true
                }
            })

            setNavigationOnClickListener {
                if (searchView?.isIconified == false) {
                    searchView.isIconified = true
                } else {
                    dispatchBackPressed()
                }
            }

            searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    pluginViewModel.search(query)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    pluginViewModel.search(newText)
                    return true
                }
            })
        }

        binding.apply {
            addRepoButton.isGone = true
            addRepoButtonImageviewHolder.isGone = true
            addRepoButtonImageview.isFocusable = false
            pluginStorageAppbar.isFocusableInTouchMode = isLayout(TV)
        }
        reloadRepositories()
    }
}
