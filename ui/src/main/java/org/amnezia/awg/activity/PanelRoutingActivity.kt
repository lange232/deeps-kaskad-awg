/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.config.Config
import org.amnezia.awg.databinding.ActivityPanelRoutingBinding
import org.amnezia.awg.databinding.ItemPanelExitBinding
import org.amnezia.awg.databinding.ItemPanelSubnetBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfigLinks
import org.amnezia.awg.panel.PanelExit
import org.amnezia.awg.panel.PanelSessionStore
import org.amnezia.awg.panel.PanelSubnet
import org.amnezia.awg.split.SplitTunnel

/**
 * Routing of one configuration: its internet exits in order and the subnets it may reach. Both live
 * in the configuration, not in the account, and are saved together.
 */
class PanelRoutingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelRoutingBinding
    private lateinit var api: PanelApi
    private lateinit var sessionStore: PanelSessionStore
    private lateinit var links: PanelConfigLinks

    private var configId: Int = 0
    private val exits = mutableListOf<PanelExit>()
    private val subnets = mutableListOf<PanelSubnet>()
    private val exitsAdapter = ExitsAdapter()
    private val subnetsAdapter = SubnetsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelRoutingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title =
            intent.getStringExtra(EXTRA_CONFIG_NAME) ?: getString(R.string.panel_routing)

        configId = intent.getIntExtra(EXTRA_CONFIG_ID, 0)
        api = PanelApi(applicationContext)
        sessionStore = PanelSessionStore(applicationContext)
        links = PanelConfigLinks(applicationContext)

        binding.exitsList.layoutManager = LinearLayoutManager(this)
        binding.exitsList.adapter = exitsAdapter
        binding.subnetsList.layoutManager = LinearLayoutManager(this)
        binding.subnetsList.adapter = subnetsAdapter
        binding.saveButton.setOnClickListener { save() }

        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        val session = sessionStore.session ?: return finish()
        busy(true)
        lifecycleScope.launch {
            try {
                val loadedExits = api.fetchExits(session.server, session.token, configId)
                val loadedSubnets = api.fetchSubnets(session.server, session.token, configId)
                exits.clear()
                exits.addAll(loadedExits)
                subnets.clear()
                subnets.addAll(loadedSubnets)
                exitsAdapter.notifyDataSetChanged()
                subnetsAdapter.notifyDataSetChanged()
                binding.exitsEmpty.visibility = if (exits.isEmpty()) View.VISIBLE else View.GONE
                binding.subnetsEmpty.visibility = if (subnets.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_connect))
            } finally {
                busy(false)
            }
        }
    }

    private fun save() {
        val session = sessionStore.session ?: return finish()
        busy(true)
        lifecycleScope.launch {
            try {
                api.setSubnets(
                    session.server,
                    session.token,
                    configId,
                    subnets.filter { it.enabled }.map { it.id },
                )
                if (exits.isNotEmpty()) {
                    api.setExitOrder(session.server, session.token, configId, exits.map { it.id })
                }
                refreshLinkedTunnel(session.server, session.token)
                toast(getString(R.string.panel_routing_saved))
                finish()
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_connect))
            } finally {
                busy(false)
            }
        }
    }

    /**
     * Subnets and exit order change `AllowedIPs` and DNS, so the tunnel of this device needs the new
     * `.conf`. Configurations of other devices leave the local tunnels alone.
     */
    private suspend fun refreshLinkedTunnel(server: String, token: String) {
        val link = links.byConfigId(configId) ?: return
        val manager = Application.getTunnelManager()
        val tunnel = manager.getTunnels()[link.tunnelName] ?: return
        try {
            val conf = api.fetchConfig(server, token, configId)
            val parsed = Config.parse(ByteArrayInputStream(conf.toByteArray(StandardCharsets.UTF_8)))
            manager.setTunnelConfig(tunnel, parsed)
            SplitTunnel.apply(applicationContext, tunnel.name)
        } catch (e: Exception) {
            toast(getString(R.string.panel_config_refresh_failed))
        }
    }

    private fun busy(value: Boolean) {
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class ExitsAdapter : RecyclerView.Adapter<ExitsAdapter.Holder>() {

        inner class Holder(val item: ItemPanelExitBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemPanelExitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val exit = exits[position]
            holder.item.exitName.text = exit.comment.ifBlank { exit.name }
            holder.item.exitComment.text = exit.name
            holder.item.exitRtt.text = exit.rttMs?.let { "${it.toInt()} ms" } ?: ""
            holder.item.exitStatus.setBackgroundColor(
                Color.parseColor(if (exit.alive) "#6DB58B" else "#CF6679"),
            )
            holder.item.exitUp.isEnabled = position > 0
            holder.item.exitDown.isEnabled = position < exits.size - 1
            holder.item.exitUp.setOnClickListener { moveExit(position, -1) }
            holder.item.exitDown.setOnClickListener { moveExit(position, 1) }
        }

        override fun getItemCount(): Int = exits.size
    }

    private fun moveExit(position: Int, delta: Int) {
        val target = position + delta
        if (target !in exits.indices) return
        val moved = exits.removeAt(position)
        exits.add(target, moved)
        exitsAdapter.notifyItemMoved(position, target)
        exitsAdapter.notifyItemRangeChanged(0, exits.size)
    }

    private inner class SubnetsAdapter : RecyclerView.Adapter<SubnetsAdapter.Holder>() {

        inner class Holder(val item: ItemPanelSubnetBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemPanelSubnetBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val subnet = subnets[position]
            holder.item.subnetName.text = subnet.comment.ifBlank { subnet.name }
            holder.item.subnetRoutes.text = subnet.routes
            holder.item.subnetEnabled.setOnCheckedChangeListener(null)
            holder.item.subnetEnabled.isChecked = subnet.enabled
            holder.item.subnetEnabled.setOnCheckedChangeListener { _, checked ->
                subnets[position] = subnet.copy(enabled = checked)
            }
        }

        override fun getItemCount(): Int = subnets.size
    }

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
        const val EXTRA_CONFIG_NAME = "config_name"
    }
}
