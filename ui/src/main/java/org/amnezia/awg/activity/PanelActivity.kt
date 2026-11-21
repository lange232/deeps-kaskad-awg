/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.util.Locale
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.amnezia.awg.Application
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivityPanelBinding
import org.amnezia.awg.databinding.DialogAddConfigBinding
import org.amnezia.awg.databinding.ItemPanelConfigBinding
import org.amnezia.awg.databinding.ItemAccountExitBinding
import org.amnezia.awg.panel.PanelApi
import org.amnezia.awg.panel.PanelApiException
import org.amnezia.awg.panel.PanelConfig
import org.amnezia.awg.panel.PanelConfigLinks
import org.amnezia.awg.panel.PanelDevice
import org.amnezia.awg.panel.PanelExit
import org.amnezia.awg.panel.PanelSession
import org.amnezia.awg.panel.PanelSessionStore
import org.amnezia.awg.panel.Updates
import org.amnezia.awg.panel.UpdateDownloadService
import org.amnezia.awg.panel.UpdateCheck
import org.amnezia.awg.panel.UpdateRequest
import org.amnezia.awg.model.ObservableTunnel
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

    private var accountExitsJob: Job? = null
    private var requestGeneration = 0
    private var updateJob: Job? = null
    private var installJob: Job? = null
    private var pendingInstall: UpdateRequest? = null
    private var updatePanel: String? = null
    private var updateOffer: UpdateRequest? = null
    private var exitsGeneration = 0
    private var creatingDevice = false
    private var loadingConfigs = false
    private var uncertainListLoaded = false
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

        pendingInstall = savedInstanceState?.let {
            BundleCompat.getParcelable(it, PENDING_INSTALL, Intent::class.java)
        }?.let { UpdateDownloadService.requestFromIntent(it, packageName) }

        binding.signInButton.setOnClickListener { signIn() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.account_refresh -> { loadConfigs(); true }
                R.id.account_update_check -> { checkForUpdates(force = true); true }
                R.id.account_sign_out -> { confirmSignOut(); true }
                else -> false
            }
        }
        binding.reviewCreationButton.setOnClickListener {
            val session = sessionStore.session ?: return@setOnClickListener
            confirmReviewedCreation(session)
        }
        binding.addDeviceButton.setOnClickListener { promptAddDevice() }

        val session = sessionStore.session
        if (session == null) {
            showSignedOut()
            binding.serverInput.setText(defaultServer())
            binding.serverInput.setSelection(binding.serverInput.text?.length ?: 0)
            binding.emailInput.setText(sessionStore.lastEmail)
        } else {
            binding.serverInput.setText(session.server)
            binding.emailInput.setText(session.email)
            showSignedIn()
        }
        binding.serverInput.doAfterTextChanged { syncUpdatePanel() }
        syncUpdatePanel()
        observeDownload()
        handleInstallIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingInstall?.let {
            val saved = Intent()
            UpdateDownloadService.putRequest(saved, it)
            outState.putParcelable(PENDING_INSTALL, saved)
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
        }
        checkForUpdates(force = false)
        pendingInstall?.let { request ->
            if (Updates.canInstall(this)) installDownloaded(request)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallIntent(intent)
    }

    private fun handleInstallIntent(intent: Intent) {
        if (intent.action != UpdateDownloadService.ACTION_INSTALL) return
        val request = UpdateDownloadService.requestFromIntent(intent, packageName) ?: return
        intent.action = null
        // The explicit tap also identifies the panel when reopening a signed-out universal build.
        if (currentUpdatePanel() == null && sessionStore.session == null)
            binding.serverInput.setText(request.server)
        if (request.server != currentUpdatePanel()) {
            toast(getString(R.string.panel_update_different_panel))
            return
        }
        installDownloaded(request)
    }

    // ------------------------------------------------------------------ session

    private fun signIn() {
        val server = binding.serverInput.text?.toString()?.trim().orEmpty()
        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (server.isBlank() || server.equals("https://", ignoreCase = true) || email.isBlank() || password.isBlank()) {
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
                importConfig(own, quiet = true, own = true)
                showSignedIn()
                loadConfigs()
                checkForUpdates(force = false)
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
        accountExitsJob?.cancel()
        exitsGeneration++
        binding.accountExitsList.removeAllViews()
        requestGeneration++
        loadingConfigs = false
        uncertainListLoaded = false
        sessionStore.clear()
        if (session != null) lifecycleScope.launch { api.logout(session.server, session.token) }
        configs.clear()
        adapter.notifyDataSetChanged()
        // The address is typed by the user or comes from the local build setting, never carried over
        // from a previous session.
        binding.serverInput.setText(defaultServer())
        binding.serverInput.setSelection(binding.serverInput.text?.length ?: 0)
        binding.passwordInput.text?.clear()
        showSignedOut()
        syncUpdatePanel()
    }

    /** A provider build may pre-fill its own address; ordinary builds start with the scheme only. */
    private fun defaultServer(): String =
        BuildConfig.DEFAULT_SERVER.trim().ifBlank { "https://" }

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
        val exitsRequest = ++exitsGeneration
        loadingConfigs = true
        accountExitsJob?.cancel()
        binding.accountExitsList.removeAllViews()
        binding.accountExitsMessage.visibility = View.VISIBLE
        binding.accountExitsMessage.setText(R.string.panel_exits_loading)
        busy(true)
        lifecycleScope.launch {
            try {
                val loaded = api.fetchConfigs(session.server, session.token)
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                showConfigs(loaded)
                loadAccountExits(session, loaded.firstOrNull()?.id, exitsRequest)
            } catch (e: PanelApiException) {
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                binding.accountExitsMessage.setText(R.string.panel_exits_load_error)
                toast(e.message ?: getString(R.string.panel_error_connect))
                if (e.status == 401 && sessionStore.session == session) {
                    accountExitsJob?.cancel()
                    binding.accountExitsList.removeAllViews()
                    sessionStore.clear()
                    configs.clear()
                    adapter.notifyDataSetChanged()
                    showSignedOut()
                    syncUpdatePanel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.session != session || generation != requestGeneration) return@launch
                binding.accountExitsMessage.setText(R.string.panel_exits_load_error)
                toast(getString(R.string.panel_error_connect))
            } finally {
                if (generation == requestGeneration) {
                    loadingConfigs = false
                    busy(false)
                }
            }
        }
    }

    /**
     * The API requires a configuration ID. Grants and status belong to the account/server;
     * we use an existing ID only to read them, not to select or import a configuration.
     */
    private fun loadAccountExits(session: PanelSession, configId: Int?, generation: Int) {
        accountExitsJob?.cancel()
        binding.accountExitsList.removeAllViews()
        binding.accountExitsMessage.visibility = View.VISIBLE
        if (configId == null) {
            binding.accountExitsMessage.setText(R.string.panel_exits_need_config)
            return
        }
        binding.accountExitsMessage.setText(R.string.panel_exits_loading)
        accountExitsJob = lifecycleScope.launch {
            try {
                val exits = api.fetchExits(session.server, session.token, configId)
                if (sessionStore.session != session || generation != exitsGeneration) return@launch
                renderAccountExits(exits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (sessionStore.session == session && generation == exitsGeneration) {
                    binding.accountExitsMessage.setText(R.string.panel_exits_load_error)
                }
            }
        }
    }

    private fun renderAccountExits(exits: List<PanelExit>) {
        binding.accountExitsList.removeAllViews()
        binding.accountExitsMessage.visibility = if (exits.isEmpty()) View.VISIBLE else View.GONE
        if (exits.isEmpty()) binding.accountExitsMessage.setText(R.string.panel_exits_auto)
        exits.forEachIndexed { index, exit ->
            val item = ItemAccountExitBinding.inflate(layoutInflater, binding.accountExitsList, false)
            item.exitDivider.visibility = if (index == exits.lastIndex) View.GONE else View.VISIBLE
            item.exitName.text = exit.name
            item.exitCountry.text = exit.country
            item.exitCountry.visibility = if (exit.country.isBlank()) View.GONE else View.VISIBLE
            item.exitActive.visibility = if (exit.current == true) View.VISIBLE else View.GONE
            val latency = exit.rttMs?.takeIf { it.isFinite() && it >= 0 }
            item.exitLatency.text = latency?.let { getString(R.string.panel_exit_latency, it.toInt()) } ?: "—"
            item.exitLatency.contentDescription = if (latency == null) {
                getString(R.string.panel_exit_latency_unknown)
            } else {
                getString(R.string.panel_exit_latency_description, item.exitLatency.text)
            }
            val (color, description) = when {
                exit.alive == null -> R.color.exit_status_unknown to R.string.panel_exit_unknown
                exit.degraded == true -> R.color.exit_status_degraded to R.string.panel_exit_degraded
                exit.alive -> R.color.exit_status_healthy to R.string.panel_exit_healthy
                else -> R.color.exit_status_down to R.string.panel_exit_down
            }
            item.exitStatus.setBackgroundColor(getColor(color))
            item.exitStatus.contentDescription = getString(description)
            item.exitStatus.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            binding.accountExitsList.addView(item.root)
        }
    }
    private fun showConfigs(loaded: List<PanelConfig>) {
        val session = sessionStore.session ?: return
        configs.clear()
        configs.addAll(loaded)
        if (creationUncertain(session)) uncertainListLoaded = true
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
        updateCreationControls(session)
    }

    // ------------------------------------------------------------------ updates

    private fun currentUpdatePanel(): String? =
        Updates.panelAddress(sessionStore.session?.server ?: binding.serverInput.text?.toString().orEmpty())

    private fun syncUpdatePanel() {
        val server = currentUpdatePanel()
        if (server == updatePanel) return
        if (updatePanel != null) {
            installJob?.cancel()
            pendingInstall = null
        }
        updatePanel = server
        updateJob?.cancel()
        updateOffer = null
        renderUpdateCard()
    }

    /** Checks at most once a day; the manual menu entry bypasses the interval. */
    private fun checkForUpdates(force: Boolean) {
        syncUpdatePanel()
        val server = updatePanel
        if (server == null) {
            if (force) toast(getString(R.string.panel_update_enter_server))
            return
        }
        if (!force && updateJob?.isActive == true) return
        if (force) toast(getString(R.string.panel_update_checking))
        updateJob?.cancel()
        updateJob = lifecycleScope.launch {
            val found = Updates.find(applicationContext, api, server, force)
            if (currentUpdatePanel() != server) return@launch
            when (found) {
                is UpdateCheck.Available -> updateOffer = UpdateRequest(server, found.update)
                UpdateCheck.Current -> {
                    updateOffer = null
                    if (force) toast(getString(R.string.panel_update_none))
                }
                UpdateCheck.Unavailable -> if (force) toast(getString(R.string.panel_update_unavailable))
                UpdateCheck.Invalid -> {
                    updateOffer = null
                    if (force) toast(getString(R.string.panel_update_incompatible))
                }
                UpdateCheck.Error -> if (force) toast(getString(R.string.panel_update_check_failed))
                UpdateCheck.Deferred -> Unit
            }
            renderUpdateCard()
        }
    }

    private fun observeDownload() {
        lifecycleScope.launch {
            var lastState: UpdateDownloadService.State? = null
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateDownloadService.state.collect { state ->
                    if (state is UpdateDownloadService.State.Failed && state !== lastState &&
                        state.request.server == currentUpdatePanel()) toast(state.message)
                    lastState = state
                    renderUpdateCard(state)
                }
            }
        }
    }

    private fun renderUpdateCard(state: UpdateDownloadService.State = UpdateDownloadService.state.value) {
        val server = currentUpdatePanel()
        val running = state as? UpdateDownloadService.State.Running
        if (running != null && running.request.server == server) {
            binding.updateCard.visibility = View.VISIBLE
            binding.updateProgress.visibility = View.VISIBLE
            binding.updateProgress.progress = running.progress
            binding.updateTitle.text = getString(R.string.panel_update_downloading, running.progress)
            binding.updateButton.setText(R.string.panel_update_cancel)
            binding.updateButton.isEnabled = !running.cancelling
            binding.updateButton.setOnClickListener {
                if (currentUpdatePanel() != running.request.server) return@setOnClickListener
                UpdateDownloadService.cancel(running.request)
            }
            return
        }

        val offer = updateOffer?.takeIf { it.server == server }
        val ready = server?.let { Updates.ready(this, it) }
        binding.updateProgress.visibility = View.GONE
        binding.updateButton.setOnClickListener(null)
        if (ready != null && (offer == null || offer.update.versionCode < ready.update.versionCode ||
                offer.update.sha256 == ready.update.sha256)) {
            binding.updateCard.visibility = View.VISIBLE
            binding.updateTitle.setText(R.string.panel_update_ready)
            binding.updateButton.setText(R.string.panel_update_apply)
            binding.updateButton.isEnabled = installJob?.isActive != true
            binding.updateButton.setOnClickListener { installDownloaded(ready) }
        } else if (offer != null) {
            binding.updateCard.visibility = View.VISIBLE
            binding.updateTitle.text = getString(R.string.panel_update_available, offer.update.versionName)
            binding.updateButton.setText(R.string.panel_update_install)
            binding.updateButton.isEnabled = running == null
            binding.updateButton.setOnClickListener { startUpdate(offer) }
        } else {
            binding.updateCard.visibility = View.GONE
        }
    }

    private fun startUpdate(request: UpdateRequest) {
        if (request.server != currentUpdatePanel() ||
            UpdateDownloadService.state.value is UpdateDownloadService.State.Running) return
        try {
            UpdateDownloadService.start(this, request)
            requestNotificationPermission()
        } catch (_: RuntimeException) {
            toast(getString(R.string.panel_update_download_failed))
        }
    }

    /** Without this the progress notification stays hidden on Android 13+; the download still runs. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun installDownloaded(request: UpdateRequest) {
        if (request.server != currentUpdatePanel() || installJob?.isActive == true) return
        installJob = lifecycleScope.launch {
            try {
                val file = try {
                    Updates.verifiedFile(this@PanelActivity, request)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (request.server != currentUpdatePanel() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    return@launch
                pendingInstall = null
                if (file == null) {
                    Updates.forgetReady(this@PanelActivity, request)
                    toast(getString(R.string.panel_update_signature))
                } else if (!Updates.canInstall(this@PanelActivity)) {
                    pendingInstall = request
                    if (!Updates.openInstallSettings(this@PanelActivity)) {
                        pendingInstall = null
                        toast(getString(R.string.panel_update_installer_unavailable))
                    } else toast(getString(R.string.panel_update_allow_install))
                } else if (!Updates.install(this@PanelActivity, file)) {
                    toast(getString(R.string.panel_update_installer_unavailable))
                }
            } finally {
                installJob = null
                renderUpdateCard()
            }
        }
        renderUpdateCard()
    }

    private fun creationKey(session: PanelSession): String =
        "${Uri.encode(session.server.trimEnd('/'))}:${Uri.encode(session.email.lowercase(Locale.ROOT))}"

    private fun creationUncertain(session: PanelSession): Boolean =
        getSharedPreferences("panel_pending_creation", MODE_PRIVATE)
            .getBoolean(creationKey(session), false)

    private fun setCreationUncertain(session: PanelSession, uncertain: Boolean): Boolean {
        val stored = getSharedPreferences("panel_pending_creation", MODE_PRIVATE).edit()
            .putBoolean(creationKey(session), uncertain).commit()
        uncertainListLoaded = false
        if (sessionStore.session == session) updateCreationControls(session)
        return stored
    }

    private fun updateCreationControls(session: PanelSession) {
        val uncertain = creationUncertain(session)
        binding.createUncertain.visibility = if (uncertain) View.VISIBLE else View.GONE
        binding.reviewCreationButton.visibility = if (uncertain) View.VISIBLE else View.GONE
        binding.reviewCreationButton.isEnabled = uncertainListLoaded
        binding.addDeviceButton.isEnabled = !creatingDevice && !uncertain
    }

    private fun confirmReviewedCreation(session: PanelSession) {
        if (!uncertainListLoaded) return
        AlertDialog.Builder(this)
            .setTitle(R.string.panel_create_reviewed)
            .setMessage(R.string.panel_create_review_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.panel_create_reviewed) { _, _ ->
                if (sessionStore.session == session) setCreationUncertain(session, false)
            }
            .show()
    }

    private fun promptAddDevice() {
        val session = sessionStore.session ?: return
        if (creatingDevice) return
        val form = DialogAddConfigBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.panel_add_device)
            .setView(form.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.panel_create, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = form.nameInput.text.toString().trim()
                if (name.isNotEmpty() && !name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]{0,31}"))) {
                    form.nameLayout.error = getString(R.string.panel_device_name_invalid)
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
        if (creationUncertain(session)) return
        creatingDevice = true
        // Persist before the POST so an interrupted process cannot silently invite a duplicate.
        if (!setCreationUncertain(session, true)) {
            creatingDevice = false
            updateCreationControls(session)
            toast(getString(R.string.panel_error_connect))
            return
        }
        lifecycleScope.launch {
            try {
                // No device_id: the new peer belongs to a different device.
                val config = api.createConfig(session.server, session.token, name, null)
                setCreationUncertain(session, false)
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
                if (e.status in listOf(400, 401, 403, 404, 409, 422, 429)) {
                    setCreationUncertain(session, false)
                    if (sessionStore.session == session) toast(e.message ?: getString(R.string.panel_error_connect))
                } else if (sessionStore.session == session) {
                    toast(getString(R.string.panel_create_uncertain))
                    loadConfigs()
                }
            } catch (e: Exception) {
                if (sessionStore.session == session) {
                    toast(getString(R.string.panel_create_uncertain))
                    loadConfigs()
                }
            } finally {
                creatingDevice = false
                if (sessionStore.session == session) updateCreationControls(session)
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
            // Read every local key before deciding to create anything; a failed read must not
            // accidentally turn an existing tunnel into a duplicate.
            val matches = mutableListOf<ObservableTunnel>()
            for (candidate in tunnels.toList()) {
                if (candidate.getConfigAsync().getInterface().keyPair.publicKey ==
                    parsed.getInterface().keyPair.publicKey
                ) matches.add(candidate)
            }
            if (sessionStore.session != session) return
            val available = matches.filter { links.isAvailable(session, config.id, it.name) }
            if (matches.isNotEmpty() && available.isEmpty()) {
                toast(getString(R.string.panel_tunnel_bound_elsewhere))
                return
            }
            val existing = when (available.size) {
                0 -> null
                1 -> available.single()
                else -> chooseExistingTunnel(available) ?: return
            }
            if (existing != null) {
                check(sessionStore.session == session)
                check(tunnels[existing.name] === existing)
                check(links.isAvailable(session, config.id, existing.name))
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

    private suspend fun chooseExistingTunnel(matches: List<ObservableTunnel>): ObservableTunnel? =
        suspendCancellableCoroutine { continuation ->
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.panel_choose_existing_tunnel)
                .setItems(matches.map { it.name }.toTypedArray()) { _, index ->
                    if (continuation.isActive) continuation.resume(matches[index])
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(null)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .create()
            continuation.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
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
        binding.addDeviceButton.visibility = View.GONE
        binding.toolbar.menu.findItem(R.id.account_refresh).isVisible = false
        binding.toolbar.menu.findItem(R.id.account_sign_out).isVisible = false
        busy(false)
    }

    private fun showSignedIn() {
        binding.signInContainer.visibility = android.view.View.GONE
        binding.signedInContainer.visibility = android.view.View.VISIBLE
        binding.addDeviceButton.visibility = View.VISIBLE
        binding.toolbar.menu.findItem(R.id.account_refresh).isVisible = true
        binding.toolbar.menu.findItem(R.id.account_sign_out).isVisible = true
        sessionStore.session?.let(::updateCreationControls)
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
            val session = sessionStore.session
            val thisDevice = session != null && (
                (config.deviceId.isNotBlank() && config.deviceId.equals(device.deviceId, ignoreCase = true)) ||
                    (config.deviceId.isBlank() && links.ownConfigId(session) == config.id &&
                        links.tunnelName(session, config.id) != null)
                )
            val title =
                if (thisDevice) {
                    val label = getString(R.string.panel_this_device)
                    SpannableString("${config.name}  $label").apply {
                        setSpan(
                            RelativeSizeSpan(0.7f),
                            config.name.length,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        setSpan(
                            ForegroundColorSpan(getColor(R.color.panel_label_positive)),
                            config.name.length,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } else {
                    config.name
                }
            holder.item.configName.text = title
            holder.item.configIp.text =
                if (config.remoteIp.isBlank()) config.ip else "${config.ip} • ${config.remoteIp}"
            holder.item.configTraffic.text =
                getString(
                    R.string.panel_traffic,
                    formatBytes(config.tx),
                    formatBytes(config.rx),
                )
            holder.item.configHandshake.text = formatHandshake(config.handshake)
            // A disabled configuration still has its keys and history, but it cannot carry traffic.
            holder.item.root.alpha = if (config.enabled) 1f else 0.45f
            holder.item.root.contentDescription =
                if (config.enabled) title else getString(R.string.panel_config_disabled, title)
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

    companion object {
        private const val PENDING_INSTALL = "pending_install_update"
        private const val TUNNEL_NAME_MAX = 15
    }
}
