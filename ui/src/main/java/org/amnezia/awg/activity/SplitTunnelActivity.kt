/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivitySplitTunnelBinding
import org.amnezia.awg.databinding.ItemSplitAppBinding
import org.amnezia.awg.split.SplitTunnel
import org.amnezia.awg.split.SplitTunnelSelection

/**
 * Split tunneling of one tunnel: the apps that go into the tunnel (include mode) or the apps that
 * stay outside it (exclude mode). Each tunnel keeps its own choice.
 */
class SplitTunnelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplitTunnelBinding
    private lateinit var tunnelName: String
    private val selected = mutableSetOf<String>()
    private val allApps = mutableListOf<AppEntry>()
    private val shown = mutableListOf<AppEntry>()
    private val adapter = AppsAdapter()

    private data class AppEntry(val packageName: String, val label: String, val info: ApplicationInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        tunnelName = intent.getStringExtra(EXTRA_TUNNEL_NAME).orEmpty()
        if (tunnelName.isEmpty()) {
            finish()
            return
        }
        binding.toolbar.title = tunnelName

        val selection = SplitTunnel.load(applicationContext, tunnelName)
        selected.addAll(selection.packages)
        binding.modeInclude.isChecked = selection.onlySelected
        binding.modeExclude.isChecked = !selection.onlySelected

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
        binding.search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    filter(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: android.text.Editable?) = Unit
            },
        )
        binding.saveButton.setOnClickListener { save() }
        loadApps()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadApps() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps =
                withContext(Dispatchers.IO) {
                    val manager = packageManager
                    manager
                        .getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { manager.getLaunchIntentForPackage(it.packageName) != null }
                        .mapNotNull { info ->
                            runCatching {
                                AppEntry(
                                    info.packageName,
                                    manager.getApplicationLabel(info).toString(),
                                    info,
                                )
                            }
                                .getOrNull()
                        }
                        .sortedBy { it.label.lowercase() }
                }
            allApps.clear()
            allApps.addAll(apps)
            filter(binding.search.text?.toString().orEmpty())
            binding.progress.visibility = View.GONE
        }
    }

    private fun filter(query: String) {
        val matching =
            if (query.isBlank()) {
                allApps
            } else {
                allApps.filter {
                    it.label.contains(query, true) || it.packageName.contains(query, true)
                }
            }
        shown.clear()
        // Selected apps stay on top, so the choice is visible without scrolling.
        shown.addAll(
            matching.sortedWith(
                compareByDescending<AppEntry> { selected.contains(it.packageName) }
                    .thenBy { it.label.lowercase() },
            ),
        )
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        val onlySelected = binding.modeInclude.isChecked
        val selection = SplitTunnelSelection(onlySelected, selected.toSet())
        SplitTunnel.store(applicationContext, tunnelName, selection)
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                SplitTunnel.apply(applicationContext, tunnelName)
                Toast.makeText(
                    this@SplitTunnelActivity,
                    getString(R.string.split_tunnel_saved),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@SplitTunnelActivity,
                    getString(R.string.split_tunnel_failed, e.localizedMessage ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppsAdapter.Holder>() {

        inner class Holder(val item: ItemSplitAppBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSplitAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = shown[position]
            holder.item.appLabel.text = app.label
            holder.item.appIcon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(app.info) }.getOrNull(),
            )
            holder.item.appChecked.setOnCheckedChangeListener(null)
            holder.item.appChecked.isChecked = selected.contains(app.packageName)
            holder.item.appChecked.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                filter(binding.search.text?.toString().orEmpty())
            }
            holder.item.root.setOnClickListener {
                holder.item.appChecked.isChecked = !holder.item.appChecked.isChecked
            }
        }

        override fun getItemCount(): Int = shown.size
    }

    companion object {
        const val EXTRA_TUNNEL_NAME = "tunnel_name"
    }
}
