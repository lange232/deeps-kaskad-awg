/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.activity.PanelActivity

/**
 * Downloads an update in the background, so leaving the app or locking the phone does not cancel
 * it. The download keeps a foreground notification with progress; when the file is verified the
 * notification invites the user to install it.
 */
class UpdateDownloadService : Service() {

    sealed interface State {
        data object Idle : State
        data class Running(val request: UpdateRequest, val progress: Int = 0, val cancelling: Boolean = false) : State
        data class Failed(val request: UpdateRequest, val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            requestFromIntent(intent, packageName)?.let { cancel(it) }
            if (job == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        val request = intent?.let { requestFromIntent(it, packageName) }
        if (request == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, progressNotification(0, request))
        } catch (_: RuntimeException) {
            mutableState.value = State.Failed(request, getString(R.string.panel_update_download_failed))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        mutableState.value = State.Running(request)
        job = scope.launch {
            var finalState: State = State.Idle
            var keepNotification = false
            try {
                Updates.download(this@UpdateDownloadService, request) { percent ->
                    withContext(Dispatchers.Main.immediate) {
                        mutableState.value = State.Running(request, percent)
                        notify(progressNotification(percent, request))
                    }
                }
                ensureActive()
                Updates.rememberReady(this@UpdateDownloadService, request)
                keepNotification = notify(readyNotification(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: getString(R.string.panel_update_download_failed)
                finalState = State.Failed(request, message)
                keepNotification = notify(failureNotification(message))
            } finally {
                stopForeground(if (keepNotification) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStartId)
                job = null
                cancelJob = null
                mutableState.value = finalState
            }
        }
        cancelJob = job
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun progressNotification(percent: Int, request: UpdateRequest): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.panel_update_downloading, percent))
            .setProgress(100, percent, false)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.panel_update_cancel), cancelIntent(request))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, PanelActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The action runs in this service, so it also works while the screen is gone. */
    private fun cancelIntent(request: UpdateRequest): PendingIntent {
        val intent = Intent(this, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)
        putRequest(intent, request)
        return PendingIntent.getService(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun readyNotification(request: UpdateRequest): Notification {
        val intent = Intent(this, PanelActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            .setAction(ACTION_INSTALL)
            .setData(android.net.Uri.parse("kaskad-update:${request.update.sha256}"))
        putRequest(intent, request)
        val pending = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.panel_update_ready))
            .setContentText(getString(R.string.panel_update_ready_hint))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun failureNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.panel_update_download_failed))
            .setContentText(message)
            .setAutoCancel(true)
            .build()

    private fun notify(notification: Notification): Boolean = runCatching {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }.isSuccess

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.panel_update_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.panel_update_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_INSTALL = "org.amnezia.awg.INSTALL_UPDATE"
        private const val ACTION_CANCEL = "org.amnezia.awg.CANCEL_UPDATE"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_MIN_SDK = "min_sdk"
        private const val EXTRA_URL = "url"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"

        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 0x4b50
        private val mutableState = MutableStateFlow<State>(State.Idle)
        val state = mutableState.asStateFlow()
        private var cancelJob: Job? = null

        fun putRequest(intent: Intent, request: UpdateRequest) {
            val (server, update) = request
            intent.putExtra(EXTRA_SERVER, server)
                .putExtra(EXTRA_VERSION_CODE, update.versionCode)
                .putExtra(EXTRA_VERSION_NAME, update.versionName)
                .putExtra(EXTRA_MIN_SDK, update.minSdk ?: 0)
                .putExtra(EXTRA_URL, update.url)
                .putExtra(EXTRA_SHA256, update.sha256)
                .putExtra(EXTRA_SIZE, update.size)
        }

        fun requestFromIntent(intent: Intent, packageName: String): UpdateRequest? {
            val server = intent.getStringExtra(EXTRA_SERVER)?.let(Updates::panelAddress) ?: return null
            val code = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val sha = intent.getStringExtra(EXTRA_SHA256) ?: return null
            if (code <= 0) return null
            val minSdk = intent.getIntExtra(EXTRA_MIN_SDK, 0).takeIf { it > 0 }
            return UpdateRequest(server, PanelUpdate(packageName, code, intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty(),
                minSdk, url, sha, intent.getLongExtra(EXTRA_SIZE, 0)))
        }

        @MainThread
        fun cancel(request: UpdateRequest) {
            val running = mutableState.value as? State.Running ?: return
            if (running.request != request || running.cancelling) return
            mutableState.value = running.copy(cancelling = true)
            cancelJob?.cancel()
        }

        fun start(context: Context, request: UpdateRequest) {
            val intent = Intent(context, UpdateDownloadService::class.java)
            putRequest(intent, request)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
