/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.graphics.Color
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
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
    private var pinning = false
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
                R.id.range_1 -> 1
                R.id.range_3 -> 3
                R.id.range_7d -> 168
                else -> 24
            })
        }
        binding.rangeGroup.check(R.id.range_3)

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
        val buttons =
            listOf(binding.range1 to 1, binding.range3 to 3, binding.range24 to 24, binding.range7d to 168)
        buttons.forEach { (button, range) ->
            val active = range == newHours
            button.backgroundTintList = ColorStateList.valueOf(if (active) Color.parseColor("#FAB283") else Color.TRANSPARENT)
            button.setTextColor(Color.parseColor(if (active) "#161616" else "#F2F2F2"))
        }
        historyJob?.cancel()
        val generation = ++historyGeneration
        binding.statsChart.visibility = View.INVISIBLE
        binding.statsProgress.visibility = View.VISIBLE
        binding.statsPeriodTotals.text = ""
        historyJob = lifecycleScope.launch {
            try {
                val traffic = api.fetchTraffic(session.server, session.token, configId, newHours)
                if (generation != historyGeneration || sessionStore.session != session) return@launch
                val loadedPoints = traffic?.points.orEmpty()
                binding.statsChart.setPoints(loadedPoints, newHours, traffic?.stepSeconds ?: 300)
                binding.statsChart.visibility = View.VISIBLE
                binding.statsPeriodTotals.text =
                    if (loadedPoints.isEmpty()) {
                        getString(R.string.panel_stats_no_history)
                    } else {
                        getString(
                            R.string.panel_stats_period_totals,
                            rangeLabel(newHours),
                            formatBytes(loadedPoints.sumOf { it.tx }),
                            formatBytes(loadedPoints.sumOf { it.rx }),
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == historyGeneration) {
                    binding.statsPeriodTotals.text = ""
                    toast((e as? PanelApiException)?.message ?: getString(R.string.panel_error_connect))
                }
            } finally {
                if (generation == historyGeneration) binding.statsProgress.visibility = View.GONE
            }
        }
    }

    private fun rangeLabel(hours: Int): String =
        getString(
            when (hours) {
                1 -> R.string.panel_stats_range_1
                3 -> R.string.panel_stats_range_3
                168 -> R.string.panel_stats_range_7d
                else -> R.string.panel_stats_range_24
            },
        )

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

    private data class DraggedExit(val id: Int)

    @SuppressLint("ClickableViewAccessibility")
    private fun renderExits() {
        binding.exitsList.removeAllViews()
        exits.forEachIndexed { position, exit ->
            val item = ItemPanelExitBinding.inflate(LayoutInflater.from(this), binding.exitsList, false)
            item.exitDivider.visibility = if (position == exits.lastIndex) View.GONE else View.VISIBLE
            item.exitName.text = exit.name
            item.exitComment.text = exit.country
            item.exitComment.visibility = if (exit.country.isBlank()) View.GONE else View.VISIBLE
            item.exitActive.visibility = if (exit.current == true) View.VISIBLE else View.GONE
            item.exitPinned.visibility = if (exit.pinned == true) View.VISIBLE else View.GONE
            val canPin = !working && !pendingLocalRefresh && !routeResultUncertain
            item.root.setOnClickListener { if (canPin) showExitPinActions(exit) }
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
            val canReorder = !working && !pendingLocalRefresh && !routeResultUncertain && exits.size > 1
            item.exitDrag.isEnabled = canReorder
            item.exitDrag.alpha = if (canReorder) 1f else 0.4f
            item.exitDrag.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && canReorder) {
                    item.root.startDragAndDrop(
                        ClipData.newPlainText("", ""),
                        View.DragShadowBuilder(item.root),
                        DraggedExit(exit.id),
                        0,
                    )
                } else event.actionMasked != MotionEvent.ACTION_DOWN && canReorder
            }
            item.root.setOnDragListener { view, event ->
                val drag = event.localState as? DraggedExit
                if (drag == null || !canReorder) return@setOnDragListener false
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        view.alpha = 0.65f
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                        view.alpha = 1f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        view.alpha = 1f
                        view.post {
                            if (!working && !pendingLocalRefresh && !routeResultUncertain) {
                                val from = exits.indexOfFirst { it.id == drag.id }
                                val to = exits.indexOfFirst { it.id == exit.id }
                                if (from >= 0 && to >= 0) moveExit(from, to - from)
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
            if (canReorder && position > 0) {
                ViewCompat.addAccessibilityAction(item.root, getString(R.string.panel_move_up)) { _, _ ->
                    val index = exits.indexOfFirst { it.id == exit.id }
                    if (!working && !pendingLocalRefresh && !routeResultUncertain && index > 0) {
                        moveExit(index, -1)
                        true
                    } else false
                }
            }
            if (canReorder && position < exits.lastIndex) {
                ViewCompat.addAccessibilityAction(item.root, getString(R.string.panel_move_down)) { _, _ ->
                    val index = exits.indexOfFirst { it.id == exit.id }
                    if (!working && !pendingLocalRefresh && !routeResultUncertain && index in 0 until exits.lastIndex) {
                        moveExit(index, 1)
                        true
                    } else false
                }
            }
            binding.exitsList.addView(item.root)
        }
    }

    private fun showExitPinActions(exit: PanelExit) {
        if (pinning || !loaded) return
        val session = sessionStore.session ?: return finish()
        val pinned = exit.pinned == true
        val action = getString(if (pinned) R.string.panel_exit_unpin else R.string.panel_exit_pin)
        AlertDialog.Builder(this)
            .setTitle(exit.name)
            .setItems(arrayOf(action)) { _, _ ->
                if (sessionStore.session == session) {
                    setExitPin(session, if (pinned) null else exit.id)
                }
            }
            .show()
    }

    /** The panel keeps the pin per configuration, so it is saved on its own without the route order. */
    private fun setExitPin(session: PanelSession, exitId: Int?) {
        pinning = true
        lifecycleScope.launch {
            try {
                api.setExitPin(session.server, session.token, configId, exitId)
                if (sessionStore.session != session) return@launch finish()
                for (index in exits.indices) {
                    exits[index] = exits[index].copy(pinned = exits[index].id == exitId)
                }
                renderExits()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.session == session) {
                    toast((e as? PanelApiException)?.message ?: getString(R.string.panel_error_connect))
                }
            } finally {
                pinning = false
            }
        }
    }

    private fun moveExit(position: Int, delta: Int) {        val target = position + delta
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
