/* SPDX-License-Identifier: Apache-2.0
 * Copyright © 2026 deeps kaskad
 */
package org.amnezia.awg.panel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.amnezia.awg.BuildConfig
import org.amnezia.awg.R
import org.json.JSONObject

sealed class UpdateCheck {
    data class Available(val update: PanelUpdate) : UpdateCheck()
    object Current : UpdateCheck()
    object Unavailable : UpdateCheck()
    object Invalid : UpdateCheck()
    object Error : UpdateCheck()
    object Deferred : UpdateCheck()
}

data class UpdateRequest(val server: String, val update: PanelUpdate)

object Updates {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 15L * 60 * 1000
    private const val STALE_MS = 7L * DAY_MS
    private const val MAX_SIZE = 200L * 1024 * 1024
    private const val FAMILY = "org.amnezia.awg.update_family"
    private const val FAMILY_VALUE = "deeps-kaskad-awg-v1"

    private fun prefs(context: Context) = context.getSharedPreferences("panel_updates_v2", Context.MODE_PRIVATE)
    fun panelAddress(server: String): String? {
        val url = server.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }

    private fun downloadUrl(server: String, update: PanelUpdate): HttpUrl? {
        val panel = server.toHttpUrlOrNull() ?: return null
        val url = update.url.toHttpUrlOrNull() ?: return null
        return url.takeIf {
            it.isHttps && it.host == panel.host && it.port == panel.port &&
                it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
        }
    }

    /** Version names look like `0.4.13` or `0.4.13.1`; the code is derived by Gradle only. */
    private val VERSION_NAME = Regex("[0-9]{1,4}(\\.[0-9]{1,2}){2,3}")

    private fun valid(update: PanelUpdate, context: Context): Boolean =
        update.packageName == context.packageName &&
            update.versionName.matches(VERSION_NAME) && update.versionCode > 0 &&
            update.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            update.size in 1..MAX_SIZE && update.url.isNotBlank()

    private fun compatible(context: Context, server: String, update: PanelUpdate): Boolean =
        valid(update, context) && downloadUrl(server, update) != null &&
            (update.minSdk == null || update.minSdk in 1..Build.VERSION.SDK_INT)

    private fun encode(update: PanelUpdate): String =
        JSONObject().put("package", update.packageName).put("code", update.versionCode)
            .put("name", update.versionName).put("sdk", update.minSdk ?: JSONObject.NULL)
            .put("url", update.url).put("sha", update.sha256).put("size", update.size).toString()

    private fun decode(stored: String?): PanelUpdate? = stored?.let {
        runCatching {
            val json = JSONObject(it)
            PanelUpdate(
                json.getString("package"), json.getInt("code"), json.getString("name"),
                if (json.isNull("sdk")) null else json.getInt("sdk"), json.getString("url"),
                json.getString("sha"), json.getLong("size"),
            )
        }.getOrNull()
    }

    fun rememberReady(context: Context, request: UpdateRequest) {
        prefs(context).edit().putString("${request.server}:ready", encode(request.update)).apply()
    }

    fun ready(context: Context, server: String): UpdateRequest? {
        val update = decode(prefs(context).getString("$server:ready", null)) ?: return null
        if (!compatible(context, server, update) || update.versionCode <= BuildConfig.VERSION_CODE) return null
        val file = target(context, update)
        return if (file.isFile && file.length() == update.size) UpdateRequest(server, update) else null
    }

    fun forgetReady(context: Context, request: UpdateRequest) {
        val p = prefs(context)
        val key = "${request.server}:ready"
        if (decode(p.getString(key, null)) == request.update) p.edit().remove(key).apply()
    }

    private fun revoke(context: Context, server: String) {
        prefs(context).edit().remove("$server:update").remove("$server:ready").apply()
    }

    suspend fun find(context: Context, api: PanelApi, server: String, force: Boolean): UpdateCheck {
        val p = prefs(context)
        val last = p.getLong("$server:last", 0)
        val now = System.currentTimeMillis()
        if (!force && now - last in 0 until DAY_MS) {
            val update = decode(p.getString("$server:update", null))
            return if (update != null && compatible(context, server, update) &&
                update.versionCode > BuildConfig.VERSION_CODE
            ) UpdateCheck.Available(update) else UpdateCheck.Deferred
        }
        // A transient failure is retried sooner than a successful check, without retrying on
        // every Activity resume (or hammering an unavailable panel). A known offer is still shown
        // while the retry waits: throttling the network must not hide verified data.
        if (!force && now - p.getLong("$server:attempt", 0) in 0 until RETRY_MS) {
            val cached = decode(p.getString("$server:update", null))
            return if (cached != null && compatible(context, server, cached) &&
                cached.versionCode > BuildConfig.VERSION_CODE
            ) UpdateCheck.Available(cached) else UpdateCheck.Deferred
        }
        p.edit().putLong("$server:attempt", now).apply()
        val update = try {
            api.fetchUpdate(server)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PanelApiException) {
            if (e.status == 404) {
                revoke(context, server)
                p.edit().putLong("$server:last", System.currentTimeMillis()).apply()
                return UpdateCheck.Unavailable
            }
            return UpdateCheck.Error
        } catch (e: Exception) {
            return UpdateCheck.Error
        }
        if (!compatible(context, server, update)) {
            p.edit().remove("$server:update").apply()
            return UpdateCheck.Invalid
        }
        val editor = p.edit().putLong("$server:last", System.currentTimeMillis())
        val ready = decode(p.getString("$server:ready", null))
        if (ready != update) editor.remove("$server:ready")
        cleanup(context, ready)
        if (update.versionCode <= BuildConfig.VERSION_CODE) {
            editor.remove("$server:update").apply()
            return UpdateCheck.Current
        }
        editor.putString("$server:update", encode(update)).apply()
        return UpdateCheck.Available(update)
    }

    private fun target(context: Context, update: PanelUpdate): File =
        File(File(context.cacheDir, "updates"), "${update.versionCode}-${update.sha256.lowercase()}.apk")

    /**
     * Drops downloads that are no longer needed: interrupted transfers and versions older than
     * [minAgeMs]. [keep] and files newer than [minAgeMs] survive, so an in-flight transfer and a
     * download from another panel are never deleted.
     */
    suspend fun cleanup(
        context: Context,
        keep: PanelUpdate? = null,
        minAgeMs: Long = STALE_MS,
    ) = withContext(Dispatchers.IO) {
        val keepName = keep?.let { target(context, it).name }
        val cutoff = System.currentTimeMillis() - minAgeMs
        File(context.cacheDir, "updates").listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            } else if (entry.name != keepName && entry.name.endsWith(".apk") &&
                entry.lastModified() < cutoff
            ) {
                entry.delete()
            }
        }
    }

    suspend fun download(context: Context, request: UpdateRequest, onProgress: suspend (Int) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val (server, update) = request
            val url = downloadUrl(server, update)
            if (url == null || !compatible(context, server, update) || update.versionCode <= BuildConfig.VERSION_CODE)
                throw IOException(context.getString(R.string.panel_update_bad_url))
            val target = target(context, update)
            target.parentFile?.mkdirs()
            if (verified(context, target, update)) {
                onProgress(100)
                return@withContext
            }
            val staging = File(target.parentFile, "partial")
            // A download is serialized by the service, so anything left here belongs to a killed
            // process and can be discarded before writing.
            staging.deleteRecursively()
            staging.mkdirs()
            val partial = File(staging, target.name)
            val call = downloadClient(context).newCall(
                Request.Builder().url(url).header("Accept", "application/vnd.android.package-archive").build(),
            )
            try {
                call.cancellable {
                    call.execute().use { response ->
                        val body = response.body
                        if (response.code != 200 || body == null)
                            throw IOException(context.getString(R.string.panel_update_download_failed))
                        var read = 0L
                        var last = -1
                        partial.outputStream().use { output ->
                            val input = body.byteStream()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                read += count
                                if (read > update.size)
                                    throw IOException(context.getString(R.string.panel_update_size_mismatch))
                                output.write(buffer, 0, count)
                                val percent = (read * 100 / update.size).toInt()
                                if (percent != last) {
                                    last = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        if (read != update.size) throw IOException(context.getString(R.string.panel_update_size_mismatch))
                    }
                }
                currentCoroutineContext().ensureActive()
                if (sha256(partial) != update.sha256.lowercase()) throw IOException(context.getString(R.string.panel_update_hash_mismatch))
                currentCoroutineContext().ensureActive()
                if (!signedByThisApp(context, partial, update))
                    throw IOException(context.getString(R.string.panel_update_signature))
                currentCoroutineContext().ensureActive()
                if (!partial.renameTo(target))
                    throw IOException(context.getString(R.string.panel_update_download_failed))
            } finally {
                partial.delete()
            }
        }

    private fun downloadClient(context: Context): OkHttpClient =
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .apply {
                PanelApi(context).pinnedTrustManager?.let { manager ->
                    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                    sslSocketFactory(sslContext.socketFactory, manager)
                }
            }.build()

    /** Cancel closes the HTTP exchange; the scope waits for the writer to close before cleanup. */
    private suspend fun <T> Call.cancellable(block: suspend () -> T): T = coroutineScope {
        suspendCancellableCoroutine { result ->
            result.invokeOnCancellation { this@cancellable.cancel() }
            launch(Dispatchers.IO) {
                try {
                    currentCoroutineContext().ensureActive()
                    result.resume(block())
                } catch (e: Exception) {
                    result.resumeWithException(e)
                }
            }
        }
    }

    private fun verified(context: Context, apk: File, update: PanelUpdate): Boolean =
        valid(update, context) && apk.isFile && apk.length() == update.size &&
            sha256(apk) == update.sha256.lowercase() && signedByThisApp(context, apk, update)

    suspend fun verifiedFile(context: Context, request: UpdateRequest): File? = withContext(Dispatchers.IO) {
        val (server, update) = request
        // A notification created before a 404 or a replacement must not bypass revocation.
        if (ready(context, server) != request) return@withContext null
        if (!compatible(context, server, update) || update.versionCode <= BuildConfig.VERSION_CODE)
            return@withContext null
        val file = target(context, update)
        file.takeIf { verified(context, it, update) }
    }

    @Suppress("DEPRECATION")
    private fun signedByThisApp(context: Context, apk: File, update: PanelUpdate): Boolean {
        val manager = context.packageManager
        val flags = PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archived = manager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archived.packageName != context.packageName || archived.versionName != update.versionName ||
            (if (Build.VERSION.SDK_INT >= 28) archived.longVersionCode else archived.versionCode.toLong()) != update.versionCode.toLong() ||
            archived.applicationInfo?.metaData?.getString(FAMILY) != FAMILY_VALUE) return false
        val installed = manager.getPackageInfo(context.packageName, flags)
        val archiveKeys = if (Build.VERSION.SDK_INT >= 28) archived.signingInfo?.apkContentsSigners else archived.signatures
        val installedKeys = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        return !archiveKeys.isNullOrEmpty() && !installedKeys.isNullOrEmpty() &&
            archiveKeys.map { it.toCharsString() }.toSet() == installedKeys.map { it.toCharsString() }.toSet()
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
