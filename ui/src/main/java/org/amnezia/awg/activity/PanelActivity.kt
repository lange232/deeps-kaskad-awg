/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.config.Config
import org.amnezia.awg.databinding.ActivityPanelBinding
import org.amnezia.awg.databinding.ItemPanelConfigBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfig
import org.amnezia.awg.panel.PanelConfigLink
import org.amnezia.awg.panel.PanelConfigLinks
import org.amnezia.awg.panel.PanelDevice
import org.amnezia.awg.panel.PanelSessionStore
import org.amnezia.awg.split.SplitTunnel

/**
 * The deeps kaskad panel: sign in, then manage the account configurations.
 *
 * An account owns one configuration per device. On sign-in the app reuses the configuration bound to
 * this phone, then the one carrying this device's `device_id`, then the one named after it, and only
 * then creates a new one — importing a configuration that belongs to another device would make the
 * two take the session from each other.
 */
class PanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelBinding
    private lateinit var api: PanelApi
    private lateinit var sessionStore: PanelSessionStore
    private lateinit var device: PanelDevice
    private lateinit var links: PanelConfigLinks

    private var refreshJob: Job? = null
    private val configs = mutableListOf<PanelConfig>()
    private val adapter = ConfigAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        api = PanelApi(applicationContext)
        sessionStore = PanelSessionStore(applicationContext)
        device = PanelDevice(applicationContext)
        links = PanelConfigLinks(applicationContext)

        binding.configList.layoutManager = LinearLayoutManager(this)
        binding.configList.adapter = adapter

        binding.signInButton.setOnClickListener { signIn() }
        binding.signOutButton.setOnClickListener { signOut() }

        val session = sessionStore.session
        if (session == null) {
            showSignedOut()
            binding.emailInput.setText(sessionStore.lastEmail)
        } else {
            binding.serverInput.setText(session.server)
            binding.emailInput.setText(session.email)
            showSignedIn()
            loadConfigs()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (sessionStore.session != null) {
            loadConfigs()
            startTrafficRefresh()
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        super.onPause()
    }

    /** Traffic counters live on the panel, so the list is refreshed while the screen is visible. */
    private fun startTrafficRefresh() {
        refreshJob?.cancel()
        refreshJob =
            lifecycleScope.launch {
                while (isActive) {
                    delay(TRAFFIC_REFRESH_MS)
                    val session = sessionStore.session ?: return@launch
                    try {
                        val loaded = api.fetchConfigs(session.server, session.token)
                        configs.clear()
                        configs.addAll(loaded)
                        adapter.notifyDataSetChanged()
                    } catch (e: Exception) {
                        // A silent poll: a failed refresh must not interrupt the screen.
                    }
                }
            }
    }

    // ------------------------------------------------------------------ session

    private fun signIn() {
        val server = binding.serverInput.text?.toString()?.trim().orEmpty()
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (server.isBlank() || email.isBlank() || password.isBlank()) {
            toast(getString(R.string.panel_error_fields))
            return
        }
        busy(true)
        lifecycleScope.launch {
            try {
                val result = api.login(server, email, password)
                sessionStore.session =
                    org.amnezia.awg.panel.PanelSession(server, result.email.ifBlank { email }, result.token)
                sessionStore.lastServer = server
                sessionStore.lastEmail = email
                binding.passwordInput.text?.clear()
                val own = resolveConfig(result.configs)
                sessionStore.selectedConfigId = own.id
                if (links.byConfigId(own.id) == null) {
                    importConfig(own, quiet = true)
                }
                showSignedIn()
                loadConfigs()
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_connect))
            } finally {
                busy(false)
            }
        }
    }

    private fun signOut() {
        val session = sessionStore.session
        sessionStore.clear()
        if (session != null) lifecycleScope.launch { api.logout(session.server, session.token) }
        configs.clear()
        adapter.notifyDataSetChanged()
        // The address is typed by the user, never carried over from a previous session.
        binding.serverInput.setText("")
        binding.passwordInput.text?.clear()
        showSignedOut()
    }

    /**
     * The configuration of this device: the locally linked one first, then the one carrying our
     * `device_id`, then the one named after the device, then the name earlier builds used, and only
     * then a fresh one created with `name` + `device_id` (idempotent on the panel side).
     */
    private suspend fun resolveConfig(accountConfigs: List<PanelConfig>): PanelConfig {
        val session = sessionStore.session
        val server = session?.server.orEmpty()
        links.linkOfThisDevice(server)?.let { link ->
            accountConfigs.firstOrNull { it.id == link.configId }?.let { return it }
        }
        accountConfigs.firstOrNull { it.deviceId.equals(device.deviceId, true) }?.let { return it }
        accountConfigs.firstOrNull { it.name.equals(device.name, true) }?.let { return it }
        accountConfigs.firstOrNull { it.name.equals(device.legacyName, true) }?.let { return it }
        return try {
            val created = api.createConfig(server, session?.token.orEmpty(), device.name, device.deviceId)
            configs.add(created)
            created
        } catch (e: PanelApiException) {
            if (e.status != 400) throw e
            api.createConfig(server, session?.token.orEmpty(), null, device.deviceId)
        }
    }

    // ------------------------------------------------------------------ configurations

    private fun loadConfigs() {
        val session = sessionStore.session ?: return
        busy(true)
        lifecycleScope.launch {
            try {
                val loaded = api.fetchConfigs(session.server, session.token)
                configs.clear()
                configs.addAll(loaded)
                adapter.notifyDataSetChanged()
                binding.accountText.text = "${session.server} · ${session.email}"
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
                if (e.status == 401) {
                    sessionStore.clear()
                    showSignedOut()
                }
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_connect))
            } finally {
                busy(false)
            }
        }
    }

    private fun toggleConfig(config: PanelConfig, enabled: Boolean) {
        val session = sessionStore.session ?: return
        lifecycleScope.launch {
            try {
                api.toggleConfig(session.server, session.token, config.id)
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } finally {
                loadConfigs()
            }
        }
    }

    private fun showActions(config: PanelConfig) {
        val linked = links.byConfigId(config.id) != null
        val items =
            arrayOf(
                getString(R.string.panel_routing),
                getString(R.string.panel_stats),
                getString(if (linked) R.string.panel_config_update else R.string.panel_config_import),
                getString(R.string.panel_config_share),
                getString(R.string.panel_config_qr),
                getString(R.string.panel_config_delete),
            )
        AlertDialog.Builder(this)
            .setTitle(config.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 ->
                        startActivity(
                            Intent(this, PanelRoutingActivity::class.java)
                                .putExtra(PanelRoutingActivity.EXTRA_CONFIG_ID, config.id)
                                .putExtra(PanelRoutingActivity.EXTRA_CONFIG_NAME, config.name),
                        )
                    1 ->
                        startActivity(
                            Intent(this, PanelStatsActivity::class.java)
                                .putExtra(PanelStatsActivity.EXTRA_CONFIG_ID, config.id),
                        )
                    2 -> importOrUpdate(config)
                    3 -> shareConfig(config)
                    4 -> showQr(config)
                    5 -> confirmDelete(config)
                }
            }
            .show()
    }

    private fun importOrUpdate(config: PanelConfig) {
        lifecycleScope.launch { importConfig(config, quiet = false) }
    }

    private fun confirmDelete(config: PanelConfig) {
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_config_delete)
            .setMessage(getString(R.string.panel_config_delete_message) + "\n\n" + config.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val session = sessionStore.session ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        api.deleteConfig(session.server, session.token, config.id)
                        links.byConfigId(config.id)?.let { link ->
                            Application.getTunnelManager().getTunnels()[link.tunnelName]?.let {
                                Application.getTunnelManager().delete(it)
                            }
                            links.remove(config.id)
                        }
                        loadConfigs()
                    } catch (e: PanelApiException) {
                        toast(e.message ?: getString(R.string.panel_error_connect))
                    }
                }
            }
            .show()
    }

    /**
     * Downloads the `.conf` of a configuration into the tunnel list, or replaces the config of the
     * tunnel it is already bound to. Subnets and exits change `AllowedIPs` and DNS, so an existing
     * tunnel is refreshed silently.
     */
    private suspend fun importConfig(config: PanelConfig, quiet: Boolean) {
        val session = sessionStore.session ?: return
        try {
            val conf = api.fetchConfig(session.server, session.token, config.id)
            val parsed =
                Config.parse(ByteArrayInputStream(conf.toByteArray(StandardCharsets.UTF_8)))
            val manager = Application.getTunnelManager()
            val link = links.byConfigId(config.id)
            val existing = link?.let { manager.getTunnels()[it.tunnelName] }
            if (existing != null) {
                manager.setTunnelConfig(existing, parsed)
                SplitTunnel.apply(applicationContext, existing.name)
                if (!quiet) toast(getString(R.string.panel_config_updated, existing.name))
                return
            }
            val name = uniqueTunnelName(config.name.ifBlank { "panel-${config.id}" })
            val tunnel = manager.create(name, parsed)
            links.save(PanelConfigLink(config.id, tunnel.name, session.server))
            SplitTunnel.apply(applicationContext, tunnel.name)
            if (!quiet) toast(getString(R.string.panel_config_imported, tunnel.name))
        } catch (e: PanelApiException) {
            toast(e.message ?: getString(R.string.panel_error_connect))
        } catch (e: Exception) {
            toast(getString(R.string.import_error, e.localizedMessage ?: ""))
        }
    }

    /**
     * The backend accepts `[a-zA-Z0-9_=+.-]{1,15}`, while a panel configuration name may be 32
     * characters long, so the tunnel name is sanitized and shortened; the full name stays on the
     * panel side.
     */
    private suspend fun uniqueTunnelName(base: String): String {
        val tunnels = Application.getTunnelManager().getTunnels()
        val sanitized = base.replace(Regex("[^a-zA-Z0-9_=+.-]"), "-").trim('-').ifEmpty { "panel" }
        val stem = sanitized.take(TUNNEL_NAME_MAX)
        if (tunnels[stem] == null) return stem
        var index = 2
        while (true) {
            val suffix = "-$index"
            val candidate = stem.take(TUNNEL_NAME_MAX - suffix.length) + suffix
            if (tunnels[candidate] == null) return candidate
            index++
        }
    }

    private fun shareConfig(config: PanelConfig) {
        val session = sessionStore.session ?: return
        lifecycleScope.launch {
            try {
                val conf = api.fetchConfig(session.server, session.token, config.id)
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, config.name)
                        putExtra(Intent.EXTRA_TEXT, conf)
                    }
                startActivity(Intent.createChooser(send, getString(R.string.panel_config_share)))
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            }
        }
    }

    private fun showQr(config: PanelConfig) {
        val session = sessionStore.session ?: return
        lifecycleScope.launch {
            try {
                val conf = api.fetchConfig(session.server, session.token, config.id)
                val bitmap = BarcodeEncoder().encodeBitmap(conf, com.google.zxing.BarcodeFormat.QR_CODE, 900, 900)
                val margin = (24 * resources.displayMetrics.density).toInt()
                val image =
                    android.widget.ImageView(this@PanelActivity).apply {
                        setImageBitmap(bitmap)
                        setPadding(margin, margin, margin, margin)
                        setBackgroundColor(android.graphics.Color.WHITE)
                    }
                AlertDialog.Builder(this@PanelActivity)
                    .setTitle(config.name)
                    .setView(image)
                    .setMessage(R.string.panel_config_qr_warning)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (e: PanelApiException) {
                toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                toast(getString(R.string.panel_error_qr))
            }
        }
    }

    // ------------------------------------------------------------------ ui helpers

    private fun showSignedOut() {
        binding.signInContainer.visibility = android.view.View.VISIBLE
        binding.signedInContainer.visibility = android.view.View.GONE
        busy(false)
    }

    private fun showSignedIn() {
        binding.signInContainer.visibility = android.view.View.GONE
        binding.signedInContainer.visibility = android.view.View.VISIBLE
        busy(false)
    }

    private fun busy(value: Boolean) {
        binding.progress.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class ConfigAdapter : RecyclerView.Adapter<ConfigAdapter.Holder>() {

        inner class Holder(val item: ItemPanelConfigBinding) : RecyclerView.ViewHolder(item.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemPanelConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val config = configs[position]
            holder.item.configName.text = config.name
            holder.item.configIp.text =
                if (config.remoteIp.isBlank()) config.ip else "${config.ip} • ${config.remoteIp}"
            holder.item.configEnabled.setOnCheckedChangeListener(null)
            holder.item.configEnabled.isChecked = config.enabled
            holder.item.configEnabled.setOnCheckedChangeListener { _, checked ->
                toggleConfig(config, checked)
            }
            holder.item.configTraffic.text =
                getString(
                    R.string.panel_traffic,
                    formatBytes(config.tx),
                    formatBytes(config.rx),
                )
            holder.item.configHandshake.text = formatHandshake(config.handshake)
            holder.item.configMore.setOnClickListener { showActions(config) }
            holder.item.root.setOnClickListener { showActions(config) }
        }

        override fun getItemCount(): Int = configs.size
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

    private fun formatHandshake(epochSeconds: Long): String {
        if (epochSeconds <= 0) return getString(R.string.panel_handshake_never)
        val age = (System.currentTimeMillis() / 1000 - epochSeconds).coerceAtLeast(0)
        val text =
            when {
                age < 60 -> "$age s"
                age < 3600 -> "${age / 60} min"
                age < 86400 -> "${age / 3600} h"
                else -> "${age / 86400} d"
            }
        return getString(R.string.panel_handshake_ago, text)
    }

    private companion object {
        const val TRAFFIC_REFRESH_MS = 5_000L
        const val TUNNEL_NAME_MAX = 15
    }
}
