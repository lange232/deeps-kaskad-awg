/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.launch
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityPanelStatsBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfig
import org.amnezia.awg.panel.PanelSessionStore
import org.amnezia.awg.panel.PanelTraffic

/**
 * Statistics of one configuration: the current state and totals, plus the traffic history the panel
 * collects. A panel without the history endpoint (or without samples yet) shows the totals only.
 */
class PanelStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelStatsBinding
    private lateinit var api: PanelApi
    private lateinit var sessionStore: PanelSessionStore
    private var configId: Int = 0
    private var hours: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        configId = intent.getIntExtra(EXTRA_CONFIG_ID, 0)
        api = PanelApi(applicationContext)
        sessionStore = PanelSessionStore(applicationContext)

        binding.rangeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectRange(if (checkedId == R.id.range_7d) 7 * 24 else 24)
        }
        binding.rangeGroup.check(R.id.range_24)
    }

    private fun selectRange(newHours: Int) {
        if (hours == newHours) return
        hours = newHours
        updateRangeChips()
        load()
    }

    /** The chosen range is a filled peach chip, the other one stays outlined. */
    private fun updateRangeChips() {
        val active = if (hours == 7 * 24) binding.range7d else binding.range24
        val inactive = if (active === binding.range7d) binding.range24 else binding.range7d
        active.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FAB283"))
        active.setTextColor(Color.parseColor("#161616"))
        inactive.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        inactive.setTextColor(Color.parseColor("#F2F2F2"))
    }

    private fun load() {
        val session = sessionStore.session ?: return finish()
        binding.statsProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val config =
                    api.fetchConfigs(session.server, session.token).firstOrNull { it.id == configId }
                config?.let(::showSnapshot)
                val traffic =
                    api.fetchTraffic(session.server, session.token, configId, hours)
                binding.statsChart.setPoints(traffic?.points.orEmpty(), hours, traffic?.stepSeconds ?: 300)
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_connect))
            } finally {
                binding.statsProgress.visibility = View.GONE
            }
        }
    }

    private fun showSnapshot(config: PanelConfig) {
        binding.statsName.text = config.name
        binding.statsAddress.text =
            if (config.remoteIp.isBlank()) config.ip else "${config.ip} • ${config.remoteIp}"
        binding.statsState.text =
            getString(
                if (config.enabled) R.string.panel_stats_state_on else R.string.panel_stats_state_off,
            )
        binding.statsHandshake.text =
            if (config.handshake <= 0) {
                getString(R.string.panel_handshake_never)
            } else {
                val age = (System.currentTimeMillis() / 1000 - config.handshake).coerceAtLeast(0)
                val text =
                    when {
                        age < 60 -> "$age s"
                        age < 3600 -> "${age / 60} min"
                        age < 86400 -> "${age / 3600} h"
                        else -> "${age / 86400} d"
                    }
                getString(R.string.panel_handshake_ago, text)
            }
        binding.statsTotals.text =
            getString(
                R.string.panel_traffic,
                formatBytes(config.tx),
                formatBytes(config.rx),
            )
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_CONFIG_ID = "config_id"
    }
}
