/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.graphics.Color
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityPanelRoutingBinding
import org.amnezia.awg.databinding.ItemPanelExitBinding
import org.amnezia.awg.databinding.ItemPanelSubnetBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfigLinks
import org.amnezia.awg.panel.PanelConfig
import org.amnezia.awg.panel.PanelExit
import org.amnezia.awg.panel.PanelSession
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
    private lateinit var session: PanelSession
    private var loaded = false
    private var working = false
    private var pendingLocalRefresh = false
    private var routeResultUncertain = false
    private var confirmedEnabled: Boolean? = null
    private var toggling = false
    private var hours = 0
    private var historyJob: Job? = null
    private var historyGeneration = 0

    private var configId: Int = 0
    private val exits = mutableListOf<PanelExit>()
    private val subnets = mutableListOf<PanelSubnet>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelRoutingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title =
            intent.getStringExtra(EXTRA_CONFIG_NAME) ?: getString(R.string.panel_configuration)

        configId = intent.getIntExtra(EXTRA_CONFIG_ID, 0)
        api = PanelApi(applicationContext)
        sessionStore = PanelSessionStore(applicationContext)
        session = sessionStore.session ?: run {
            finish()
            return
        }
        links = PanelConfigLinks(applicationContext)

        binding.saveButton.setOnClickListener {
            if (routeResultUncertain) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(R.string.panel_routing_reload_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.panel_refresh) { _, _ -> load() }
                    .show()
            } else save()
        }
        binding.configEnabled.setOnCheckedChangeListener { _, checked ->
            if (confirmedEnabled != null && checked != confirmedEnabled && !toggling) toggleConfig()
        }
        binding.rangeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) selectRange(when (id) {
                R.id.range_3 -> 3
                R.id.range_7d -> 168
                else -> 24
            })
        }
        binding.rangeGroup.check(R.id.range_24)

        load()
        loadSnapshot()
    }

    private fun loadSnapshot() {
        lifecycleScope.launch {
            try {
                val config = api.fetchConfigs(session.server, session.token).firstOrNull { it.id == configId }
                if (sessionStore.session != session) return@launch finish()
                if (config == null) {
                    toast(getString(R.string.panel_error_connect))
                    return@launch
                }
                if (!toggling) showSnapshot(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast((e as? PanelApiException)?.message ?: getString(R.string.panel_error_connect))
            }
        }
    }

    private fun showSnapshot(config: PanelConfig) {
        binding.toolbar.title = config.name
        binding.statsAddress.text = if (config.remoteIp.isBlank()) config.ip else "${config.ip} • ${config.remoteIp}"
        setEnabledState(config.enabled)
        binding.statsHandshake.text = if (config.handshake <= 0) {
            getString(R.string.panel_handshake_never)
        } else {
            val age = (System.currentTimeMillis() / 1000 - config.handshake).coerceAtLeast(0)
            val text = when {
                age < 60 -> "$age s"
                age < 3600 -> "${age / 60} min"
                age < 86400 -> "${age / 3600} h"
                else -> "${age / 86400} d"
            }
            getString(R.string.panel_handshake_ago, text)
        }
        binding.statsTotals.text = getString(R.string.panel_traffic, formatBytes(config.tx), formatBytes(config.rx))
    }

    private fun setEnabledState(enabled: Boolean) {
        confirmedEnabled = enabled
        binding.configEnabled.isChecked = enabled
        binding.configEnabled.isEnabled = !toggling
        binding.statsState.setText(if (enabled) R.string.panel_stats_state_on else R.string.panel_stats_state_off)
    }

    private fun toggleConfig() {
        val previous = confirmedEnabled ?: return
        toggling = true
        binding.configEnabled.isEnabled = false
        lifecycleScope.launch {
            try {
                // The endpoint toggles rather than setting a value; never retry it automatically.
                api.toggleConfig(session.server, session.token, configId)
                if (sessionStore.session != session) return@launch finish()
                val current = api.fetchConfigs(session.server, session.token).firstOrNull { it.id == configId }
                    ?: throw IllegalStateException("Configuration no longer exists")
                toggling = false
                showSnapshot(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.session != session) return@launch finish()
                toggling = false
                setEnabledState(previous)
                // A failed request can still have reached the server. Do not allow a blind retry.
                binding.configEnabled.isEnabled = false
                toast(getString(R.string.panel_toggle_uncertain))
                loadSnapshot()
            }
        }
    }

    private fun selectRange(newHours: Int) {
        if (hours == newHours) return
        hours = newHours
        val buttons = listOf(binding.range3 to 3, binding.range24 to 24, binding.range7d to 168)
        buttons.forEach { (button, range) ->
            val active = range == newHours
            button.backgroundTintList = ColorStateList.valueOf(if (active) Color.parseColor("#FAB283") else Color.TRANSPARENT)
            button.setTextColor(Color.parseColor(if (active) "#161616" else "#F2F2F2"))
        }
        historyJob?.cancel()
        val generation = ++historyGeneration
        binding.statsChart.visibility = View.INVISIBLE
        binding.statsProgress.visibility = View.VISIBLE
        historyJob = lifecycleScope.launch {
            try {
                val traffic = api.fetchTraffic(session.server, session.token, configId, newHours)
                if (generation != historyGeneration || sessionStore.session != session) return@launch
                binding.statsChart.setPoints(traffic?.points.orEmpty(), newHours, traffic?.stepSeconds ?: 300)
                binding.statsChart.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == historyGeneration) {
                    toast((e as? PanelApiException)?.message ?: getString(R.string.panel_error_connect))
                }
            } finally {
                if (generation == historyGeneration) binding.statsProgress.visibility = View.GONE
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        busy(true)
        lifecycleScope.launch {
            try {
                val loadedExits = api.fetchExits(session.server, session.token, configId)
                val loadedSubnets = api.fetchSubnets(session.server, session.token, configId)
                if (sessionStore.session != session) return@launch finish()
                exits.clear()
                exits.addAll(loadedExits)
                subnets.clear()
                subnets.addAll(loadedSubnets)
                binding.exitsEmpty.visibility = if (exits.isEmpty()) View.VISIBLE else View.GONE
                binding.subnetsEmpty.visibility = if (subnets.isEmpty()) View.VISIBLE else View.GONE
                loaded = true
                routeResultUncertain = false
                binding.saveButton.setText(R.string.panel_save_routes)
            } catch (e: CancellationException) {
                throw e
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
        if (!loaded || working) return
        if (sessionStore.session != session) return finish()
        busy(true)
        lifecycleScope.launch {
            var subnetsSaved = false
            var writingServer = false
            try {
                if (!pendingLocalRefresh) {
                    writingServer = true
                    api.setSubnets(
                        session.server,
                        session.token,
                        configId,
                        subnets.filter { it.enabled }.map { it.id },
                    )
                    subnetsSaved = true
                    if (sessionStore.session != session) return@launch finish()
                    if (exits.isNotEmpty()) {
                        api.setExitOrder(session.server, session.token, configId, exits.map { it.id })
                    }
                    writingServer = false
                    pendingLocalRefresh = true
                }
                if (sessionStore.session != session) return@launch finish()
                val applied = refreshLinkedTunnel()
                toast(getString(if (applied) R.string.panel_routing_applied else R.string.panel_routing_saved))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = (e as? PanelApiException)?.message ?: getString(R.string.panel_error_connect)
                val status = (e as? PanelApiException)?.status
                val uncertain = writingServer && (status == null || status == 408 || status >= 500)
                when {
                    pendingLocalRefresh -> {
                        binding.saveButton.setText(R.string.panel_routing_retry_apply)
                        toast(getString(R.string.panel_config_refresh_failed))
                    }
                    uncertain -> {
                        routeResultUncertain = true
                        binding.saveButton.setText(R.string.panel_routing_reload)
                        toast(getString(R.string.panel_routing_uncertain))
                    }
                    subnetsSaved -> toast(getString(R.string.panel_routing_partial, detail))
                    else -> toast(detail)
                }
            } finally {
                busy(false)
            }
        }
    }

    /**
     * Subnets and exit order change `AllowedIPs` and DNS, so the tunnel of this device needs the new
     * `.conf`. Configurations of other devices leave the local tunnels alone.
     */
    private suspend fun refreshLinkedTunnel(): Boolean {
        if (links.tunnelName(session, configId) == null && links.legacyCandidate(session, configId) == null) {
            return false
        }
        val conf = api.fetchConfig(session.server, session.token, configId)
        val parsed = SplitTunnel.parse(conf)
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels()
        if (sessionStore.session != session) return false
        val currentName = links.tunnelName(session, configId)
        val legacyName = if (currentName == null) links.legacyCandidate(session, configId) else null
        val tunnel = tunnels[currentName ?: legacyName ?: return false] ?: return false
        if (legacyName != null) {
            if (tunnel.getConfigAsync().getInterface().keyPair.publicKey != parsed.getInterface().keyPair.publicKey) {
                return false
            }
            if (sessionStore.session != session || links.legacyCandidate(session, configId) != tunnel.name) return false
            links.save(session, configId, tunnel.name, own = false)
        }
        manager.updateTunnelConfig(tunnel) {
            val selection = SplitTunnel.selectionOf(tunnel.getConfigAsync())
            check(sessionStore.session == session)
            check(links.tunnelName(session, configId) == tunnel.name)
            SplitTunnel.withSelection(parsed, selection)
        }
        return true
    }

    private fun busy(value: Boolean) {
        working = value
        binding.saveButton.isEnabled = loaded && !value
        binding.progress.visibility = if (value) View.VISIBLE else View.GONE
        renderExits()
        renderSubnets()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun renderExits() {
        binding.exitsList.removeAllViews()
        exits.forEachIndexed { position, exit ->
            val item = ItemPanelExitBinding.inflate(LayoutInflater.from(this), binding.exitsList, false)
            item.exitName.text = exit.comment.ifBlank { exit.name }
            item.exitComment.text = exit.name
            item.exitActive.visibility = if (exit.active == true) View.VISIBLE else View.GONE
            item.exitRtt.text = exit.rttMs?.let { "${it.toInt()} ms" } ?: ""
            val (statusColor, statusText) = when {
                exit.alive == null -> R.color.exit_status_unknown to R.string.panel_exit_unknown
                exit.degraded == true -> R.color.exit_status_degraded to R.string.panel_exit_degraded
                exit.alive -> R.color.exit_status_healthy to R.string.panel_exit_healthy
                else -> R.color.exit_status_down to R.string.panel_exit_down
            }
            item.exitStatus.setBackgroundColor(getColor(statusColor))
            item.exitStatus.contentDescription = getString(statusText)
            item.exitStatus.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            item.exitUp.isEnabled = !working && !pendingLocalRefresh && !routeResultUncertain && position > 0
            item.exitDown.isEnabled = !working && !pendingLocalRefresh && !routeResultUncertain && position < exits.size - 1
            item.exitUp.setOnClickListener { moveExit(position, -1) }
            item.exitDown.setOnClickListener { moveExit(position, 1) }
            binding.exitsList.addView(item.root)
        }
    }

    private fun moveExit(position: Int, delta: Int) {
        val target = position + delta
        if (target !in exits.indices) return
        val moved = exits.removeAt(position)
        exits.add(target, moved)
        renderExits()
    }

    private fun renderSubnets() {
        binding.subnetsList.removeAllViews()
        subnets.forEachIndexed { position, subnet ->
            val item = ItemPanelSubnetBinding.inflate(LayoutInflater.from(this), binding.subnetsList, false)
            item.subnetName.text = subnet.comment.ifBlank { subnet.name }
            item.subnetRoutes.text = subnet.routes
            item.subnetEnabled.isEnabled = !working && !pendingLocalRefresh && !routeResultUncertain
            item.subnetEnabled.isChecked = subnet.enabled
            item.subnetEnabled.setOnCheckedChangeListener { _, checked ->
                subnets[position] = subnet.copy(enabled = checked)
            }
            binding.subnetsList.addView(item.root)
        }
    }

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
        const val EXTRA_CONFIG_NAME = "config_name"
    }
}
