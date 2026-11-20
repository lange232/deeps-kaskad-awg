/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.amnezia.awg.Application
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityPanelBinding
import org.amnezia.awg.databinding.ItemPanelConfigBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfig
import org.amnezia.awg.panel.PanelConfigLinks
import org.amnezia.awg.panel.PanelDevice
import org.amnezia.awg.panel.PanelSession
import org.amnezia.awg.panel.PanelSessionStore
import org.amnezia.awg.split.SplitTunnel

/**
 * The deeps kaskad panel: sign in, then manage the account configurations.
 *
 * An account owns one configuration per device. On sign-in the app reuses the configuration bound to
 * this phone by `device_id` or a verified local binding. Configuration names are display labels,
 * not device identities.
 */
class PanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPanelBinding
    private lateinit var api: PanelApi
    private lateinit var sessionStore: PanelSessionStore
    private lateinit var device: PanelDevice
    private lateinit var links: PanelConfigLinks

    private var refreshJob: Job? = null
    private var requestGeneration = 0
    private var creatingDevice = false
    private var loadingConfigs = false
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
        binding.signOutButton.setOnClickListener { confirmSignOut() }
        binding.refreshButton.setOnClickListener { loadConfigs() }
        binding.addDeviceButton.setOnClickListener { promptAddDevice() }

        val session = sessionStore.session
        if (session == null) {
            showSignedOut()
            binding.emailInput.setText(sessionStore.lastEmail)
        } else {
            binding.serverInput.setText(session.server)
            binding.emailInput.setText(session.email)
            showSignedIn()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (sessionStore.session != null) {
            startTrafficRefresh()
            loadConfigs()
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
                    if (loadingConfigs || creatingDevice) continue
                    val session = sessionStore.session ?: return@launch
                    val generation = ++requestGeneration
                    try {
                        val loaded = api.fetchConfigs(session.server, session.token)
                        if (sessionStore.session != session) return@launch
                        if (generation == requestGeneration) showConfigs(loaded)
                    } catch (e: CancellationException) {
                        throw e
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
                val session = PanelSession(server, result.email.ifBlank { email }, result.token, result.expiresAt)
                configs.clear()
                adapter.notifyDataSetChanged()
                sessionStore.session = session
                sessionStore.lastServer = server
                sessionStore.lastEmail = email
                binding.passwordInput.text?.clear()
                val own = resolveConfig(session, result.configs)
                val existing = links.tunnelName(session, own.id)
                    ?.let { Application.getTunnelManager().getTunnels()[it] }
                if (existing == null) {
                    importConfig(own, quiet = true, own = true)
                } else {
                    links.save(session, own.id, existing.name, own = true)
                }
                showSignedIn()
                loadConfigs()
                startTrafficRefresh()
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

    private fun confirmSignOut() {
        val session = sessionStore.session ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_sign_out)
            .setMessage(R.string.panel_sign_out_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.panel_sign_out) { _, _ ->
                if (sessionStore.session == session) signOut()
            }
            .show()
    }

    private fun signOut() {
        val session = sessionStore.session
        refreshJob?.cancel()
        requestGeneration++
        loadingConfigs = false
        sessionStore.clear()
        if (session != null) lifecycleScope.launch { api.logout(session.server, session.token) }
        configs.clear()
        adapter.notifyDataSetChanged()
        // The address is typed by the user, never carried over from a previous session.
        binding.serverInput.setText("")
        binding.passwordInput.text?.clear()
        showSignedOut()
    }

    private suspend fun resolveConfig(session: PanelSession, accountConfigs: List<PanelConfig>): PanelConfig {
        accountConfigs.firstOrNull { it.deviceId.equals(device.deviceId, true) }?.let { return it }
        // Older panels can return no device ID; only trust our own scoped binding in that case.
        links.ownConfigId(session)?.let { id ->
            val name = links.tunnelName(session, id)
            if (name != null && Application.getTunnelManager().getTunnels()[name] != null) {
                accountConfigs.firstOrNull { it.id == id && it.deviceId.isBlank() }?.let { return it }
            }
        }
        return try {
            api.createConfig(session.server, session.token, device.name, device.deviceId)
        } catch (e: PanelApiException) {
            if (e.status != 400) throw e
            api.createConfig(session.server, session.token, null, device.deviceId)
        }
    }

    // ------------------------------------------------------------------ configurations

    private fun loadConfigs() {
        val session = sessionStore.session ?: return
        val generation = ++requestGeneration
        loadingConfigs = true
        busy(true)
        lifecycleScope.launch {
            try {
                val loaded = api.fetchConfigs(session.server, session.token)
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                showConfigs(loaded)
            } catch (e: PanelApiException) {
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                toast(e.message ?: getString(R.string.panel_error_connect))
                if (e.status == 401 && sessionStore.session == session) {
                    sessionStore.clear()
                    configs.clear()
                    adapter.notifyDataSetChanged()
                    showSignedOut()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                toast(getString(R.string.panel_error_connect))
            } finally {
                if (generation == requestGeneration) {
                    loadingConfigs = false
                    busy(false)
                }
            }
        }
    }

    private fun showConfigs(loaded: List<PanelConfig>) {
        val session = sessionStore.session ?: return
        configs.clear()
        configs.addAll(loaded)
        adapter.notifyDataSetChanged()
        binding.accountText.text = "${session.server} · ${session.email}"
        binding.configCount.text = getString(R.string.panel_config_count, loaded.size)
        val expiresAt = session.expiresAt
        binding.sessionExpiry.text =
            if (expiresAt == null) getString(R.string.panel_session_expiry_unknown)
            else {
                val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(expiresAt * 1000))
                getString(
                    if (expiresAt <= System.currentTimeMillis() / 1000 + 3 * 86400)
                        R.string.panel_session_expiry_soon else R.string.panel_session_expiry,
                    date,
                )
            }
    }

    private fun promptAddDevice() {
        val session = sessionStore.session ?: return
        if (creatingDevice) return
        val input = EditText(this).apply {
            hint = getString(R.string.panel_device_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.panel_add_device)
            .setMessage(R.string.panel_add_device_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.panel_create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,31}"))) {
                    input.error = getString(R.string.panel_device_name_invalid)
                    return@setOnClickListener
                }
                if (sessionStore.session != session || creatingDevice) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                dialog.dismiss()
                createDevice(session, name.ifEmpty { null })
            }
        }
        dialog.show()
    }

    private fun createDevice(session: PanelSession, name: String?) {
        creatingDevice = true
        binding.addDeviceButton.isEnabled = false
        lifecycleScope.launch {
            try {
                // No device_id: the new peer belongs to a different device.
                val config = api.createConfig(session.server, session.token, name, null)
                if (sessionStore.session != session) return@launch
                AlertDialog.Builder(this@PanelActivity)
                    .setTitle(R.string.panel_device_created)
                    .setMessage(getString(R.string.panel_device_created_message, config.name))
                    .setNegativeButton(android.R.string.ok, null)
                    .setPositiveButton(R.string.panel_config_qr) { _, _ ->
                        if (sessionStore.session == session) showQr(config)
                    }
                    .show()
                loadConfigs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PanelApiException) {
                if (sessionStore.session == session) toast(e.message ?: getString(R.string.panel_error_connect))
            } catch (e: Exception) {
                if (sessionStore.session == session) {
                    toast(getString(R.string.panel_create_uncertain))
                    loadConfigs()
                }
            } finally {
                creatingDevice = false
                binding.addDeviceButton.isEnabled = true
            }
        }
    }

    private fun showActions(config: PanelConfig) {
        val session = sessionStore.session ?: return
        val items =
            arrayOf(
                getString(R.string.panel_config_share),
                getString(R.string.panel_config_qr),
                getString(R.string.panel_config_delete),
            )
        AlertDialog.Builder(this)
            .setTitle(config.name)
            .setItems(items) { _, which ->
                if (sessionStore.session != session) return@setItems
                when (which) {
                    0 -> shareConfig(config)
                    1 -> showQr(config)
                    2 -> confirmDelete(config)
                }
            }
            .show()
    }

    private fun confirmDelete(config: PanelConfig) {
        val session = sessionStore.session ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_config_delete)
            .setMessage(getString(R.string.panel_config_delete_message) + "\n\n" + config.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (sessionStore.session != session) return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        api.deleteConfig(session.server, session.token, config.id)
                        if (sessionStore.session != session) return@launch
                        links.tunnelName(session, config.id)?.let { name ->
                            Application.getTunnelManager().getTunnels()[name]?.let {
                                Application.getTunnelManager().delete(it)
                            }
                            links.remove(session, config.id)
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
    private suspend fun importConfig(config: PanelConfig, quiet: Boolean, own: Boolean = false) {
        val session = sessionStore.session ?: return
        try {
            val conf = api.fetchConfig(session.server, session.token, config.id)
            val parsed = SplitTunnel.parse(conf)
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            if (sessionStore.session != session) return
            val existing = links.tunnelName(session, config.id)?.let { tunnels[it] }
                ?: links.legacyCandidate(session, config.id)?.let { tunnels[it] }?.takeIf {
                    it.getConfigAsync().getInterface().keyPair.publicKey ==
                        parsed.getInterface().keyPair.publicKey
                }
            if (existing != null) {
                manager.updateTunnelConfig(existing) {
                    val selection = SplitTunnel.selectionOf(existing.getConfigAsync())
                    check(sessionStore.session == session)
                    SplitTunnel.withSelection(parsed, selection)
                }
                links.save(session, config.id, existing.name, own)
                if (!quiet) toast(getString(R.string.panel_config_updated, existing.name))
                return
            }
            val name = uniqueTunnelName(config.name.ifBlank { "panel-${config.id}" })
            val tunnel = manager.create(name, parsed)
            links.save(session, config.id, tunnel.name, own)
            if (!quiet) toast(getString(R.string.panel_config_imported, tunnel.name))
        } catch (e: CancellationException) {
            throw e
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
            val session = sessionStore.session
            val thisDevice = session != null && (
                (config.deviceId.isNotBlank() && config.deviceId.equals(device.deviceId, ignoreCase = true)) ||
                    (config.deviceId.isBlank() && links.ownConfigId(session) == config.id &&
                        links.tunnelName(session, config.id) != null)
                )
            holder.item.configThisDevice.visibility = if (thisDevice) View.VISIBLE else View.GONE
            holder.item.configIp.text =
                if (config.remoteIp.isBlank()) config.ip else "${config.ip} • ${config.remoteIp}"
            holder.item.configTraffic.text =
                getString(
                    R.string.panel_traffic,
                    formatBytes(config.tx),
                    formatBytes(config.rx),
                )
            holder.item.configHandshake.text = formatHandshake(config.handshake)
            holder.item.configMore.setOnClickListener { showActions(config) }
            holder.item.root.setOnClickListener {
                startActivity(
                    Intent(this@PanelActivity, PanelRoutingActivity::class.java)
                        .putExtra(PanelRoutingActivity.EXTRA_CONFIG_ID, config.id)
                        .putExtra(PanelRoutingActivity.EXTRA_CONFIG_NAME, config.name),
                )
            }
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
