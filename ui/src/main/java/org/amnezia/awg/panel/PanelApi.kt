/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.json.JSONArray
import org.json.JSONObject

/** One configuration of the account: one device with its own keys and address. */
data class PanelConfig(
    val id: Int,
    val name: String,
    /** Empty for configurations created before the panel had a separate device id. */
    val deviceId: String,
    val ip: String,
    /** The address the peer connects from, as seen by the server; empty until it handshakes. */
    val remoteIp: String,
    val enabled: Boolean,
    val internet: Boolean,
    val exits: List<String>,
    val handshake: Long,
    val rx: Long,
    val tx: Long,
)

/** An internet exit of a configuration, in its configured order. */
data class PanelExit(
    val id: Int,
    val name: String,
    val comment: String,
    val alive: Boolean?,
    val degraded: Boolean?,
    val rttMs: Double?,
    /** Default server route, not necessarily the route used by this configuration. */
    val active: Boolean?,
)

/** A routed subnet the account is granted, with its state for one configuration. */
data class PanelSubnet(
    val id: Int,
    val name: String,
    val comment: String,
    val routes: String,
    val enabled: Boolean,
)

/** One point of the traffic history: deltas for the interval, download and upload in bytes. */
data class PanelTrafficPoint(val ts: Long, val rx: Long, val tx: Long)

data class PanelTraffic(val stepSeconds: Int, val points: List<PanelTrafficPoint>)

data class PanelLoginResult(
    val token: String,
    val email: String,
    val configs: List<PanelConfig>,
    /** False for panels older than 0.4.0, which have no `configs` in the login response. */
    val supportsConfigs: Boolean,
    val expiresAt: Long? = null,
)

/**
 * A failed panel call. The message is meant for the user: for 400/404/409/500 it is the panel's own
 * `detail` text, for 401/429 a local string.
 */
class PanelApiException(val status: Int, message: String) : Exception(message)

/**
 * Client for the deeps kaskad panel API.
 *
 * An account owns several configurations, one per device, and every configuration has its own exit
 * order and its own set of enabled subnets. The transport is plain [HttpURLConnection]: the client
 * talks to one host with form posts and JSON answers, which does not justify a full HTTP stack.
 *
 * The panel normally serves a Let's Encrypt certificate and the system trust store is used. A
 * self-signed stand can pin its certificate by dropping the PEM into `res/raw/panel.crt`.
 */
class PanelApi(private val context: Context) {

    private val pinnedTrustManager: X509TrustManager? by lazy { loadPinnedTrustManager() }

    suspend fun login(server: String, email: String, password: String): PanelLoginResult =
        withContext(Dispatchers.IO) {
            val body =
                execute(
                    server = server,
                    path = "/api/login",
                    method = "POST",
                    token = null,
                    form = listOf("email" to email.trim(), "password" to password),
                    authError = true,
                )
            val json = JSONObject(body)
            val configs = json.optJSONArray("configs")
            PanelLoginResult(
                token = json.getString("token"),
                email = json.optString("email"),
                configs = configs?.let(::parseConfigs).orEmpty(),
                supportsConfigs = configs != null,
                expiresAt = json.optLong("expires_at", 0).takeIf { it > 0 },
            )
        }

    suspend fun fetchConfigs(server: String, token: String): List<PanelConfig> =
        withContext(Dispatchers.IO) {
            parseConfigs(JSONObject(execute(server, "/api/me", "GET", token))["configs"] as JSONArray)
        }

    /**
     * Creates a configuration, or returns the one this device already owns.
     *
     * `device_id` makes the call idempotent: a panel that knows the id answers with the existing
     * configuration (same id, same keys) instead of creating a second peer, so callers must always
     * take the id from the response rather than assume a new peer was created. A taken name gets a
     * `-2` suffix on the panel side.
     */
    suspend fun createConfig(
        server: String,
        token: String,
        name: String? = null,
        deviceId: String? = null,
    ): PanelConfig = withContext(Dispatchers.IO) {
        val form = buildList {
            if (!name.isNullOrBlank()) add("name" to name.trim())
            if (!deviceId.isNullOrBlank()) add("device_id" to deviceId.trim())
        }
        val body = execute(server, "/api/me/configs", "POST", token, form)
        parseConfig(JSONObject(body).getJSONObject("config"))
    }

    suspend fun fetchConfig(server: String, token: String, configId: Int): String =
        withContext(Dispatchers.IO) {
            execute(server, "/api/me/configs/$configId/conf", "GET", token)
        }

    suspend fun toggleConfig(server: String, token: String, configId: Int): Boolean =
        withContext(Dispatchers.IO) {
            val body = execute(server, "/api/me/configs/$configId/toggle", "POST", token)
            JSONObject(body).optBoolean("enabled", false)
        }

    suspend fun deleteConfig(server: String, token: String, configId: Int) {
        withContext(Dispatchers.IO) {
            execute(server, "/api/me/configs/$configId", "DELETE", token)
        }
    }

    suspend fun fetchExits(server: String, token: String, configId: Int): List<PanelExit> =
        withContext(Dispatchers.IO) {
            val array = JSONArray(execute(server, "/api/me/exits?config_id=$configId", "GET", token))
            (0 until array.length()).map { index ->
                val exit = array.getJSONObject(index)
                PanelExit(
                    id = exit.getInt("id"),
                    name = exit.getString("name"),
                    comment = exit.optString("comment"),
                    alive = if (exit.isNull("alive")) null else exit.optBoolean("alive"),
                    degraded = if (exit.isNull("degraded")) null else exit.optBoolean("degraded"),
                    rttMs = if (exit.isNull("rtt_ms")) null else exit.optDouble("rtt_ms"),
                    active = if (exit.isNull("active") || !exit.optBoolean("status_fresh", true)) null
                        else exit.optBoolean("active"),
                )
            }
        }

    /** Replaces the configuration's exit order; the panel rejects partial lists. */
    suspend fun setExitOrder(server: String, token: String, configId: Int, exitIds: List<Int>) {
        withContext(Dispatchers.IO) {
            val form = buildList {
                add("config_id" to configId.toString())
                exitIds.forEach { add("exit_id" to it.toString()) }
            }
            execute(server, "/api/me/exits/order", "POST", token, form)
        }
    }

    suspend fun fetchSubnets(server: String, token: String, configId: Int): List<PanelSubnet> =
        withContext(Dispatchers.IO) {
            val array =
                JSONArray(execute(server, "/api/me/subnets?config_id=$configId", "GET", token))
            (0 until array.length()).map { index ->
                val subnet = array.getJSONObject(index)
                PanelSubnet(
                    id = subnet.getInt("id"),
                    name = subnet.getString("name"),
                    comment = subnet.optString("comment"),
                    routes = subnet.optString("routes"),
                    enabled = subnet.optBoolean("enabled", false),
                )
            }
        }

    /** Replaces the enabled subnets of a configuration: an empty list disables everything. */
    suspend fun setSubnets(server: String, token: String, configId: Int, subnetIds: List<Int>) {
        withContext(Dispatchers.IO) {
            val form = buildList {
                add("config_id" to configId.toString())
                subnetIds.forEach { add("subnet_id" to it.toString()) }
            }
            execute(server, "/api/me/subnets", "POST", token, form)
        }
    }

    /**
     * Traffic history of one configuration. Null when the panel has no such endpoint or no samples
     * yet, so the statistics screen can fall back to the current totals instead of failing.
     */
    suspend fun fetchTraffic(
        server: String,
        token: String,
        configId: Int,
        hours: Int,
    ): PanelTraffic? = withContext(Dispatchers.IO) {
        val body =
            try {
                execute(server, "/api/me/configs/$configId/traffic?hours=$hours", "GET", token)
            } catch (e: PanelApiException) {
                if (e.status == 404) return@withContext null
                throw e
            }
        val json = JSONObject(body)
        val points = json.optJSONArray("points") ?: JSONArray()
        PanelTraffic(
            stepSeconds = json.optInt("step_seconds", 300),
            points =
                (0 until points.length()).map { index ->
                    val point = points.getJSONObject(index)
                    PanelTrafficPoint(
                        ts = point.optLong("ts"),
                        rx = point.optLong("rx"),
                        tx = point.optLong("tx"),
                    )
                },
        )
    }

    /** Best-effort token revocation; the result does not matter for the caller. */
    suspend fun logout(server: String, token: String) {
        withContext(Dispatchers.IO) {
            runCatching { execute(server, "/api/logout", "POST", token) }
        }
    }

    // ------------------------------------------------------------------ transport

    private fun execute(
        server: String,
        path: String,
        method: String,
        token: String?,
        form: List<Pair<String, String>>? = null,
        authError: Boolean = false,
    ): String {
        val url = apiUrl(server, path)
        val connection =
            (URL(url).openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                pinnedTrustManager?.let { manager ->
                    val sslContext =
                        SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
                    sslSocketFactory = sslContext.socketFactory
                }
                if (form != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }

        try {
            if (form != null) {
                val body =
                    form.joinToString("&") { (key, value) ->
                        "${encode(key)}=${encode(value)}"
                    }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw mapError(status, text, authError)
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun apiUrl(server: String, path: String): String {
        val base = server.trim().trimEnd('/')
        if (!base.startsWith("https://")) {
            throw PanelApiException(0, context.getString(R.string.panel_error_insecure_server))
        }
        return base + path
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun mapError(status: Int, body: String, authError: Boolean): PanelApiException {
        val detail =
            runCatching { JSONObject(body).optString("detail") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        return when (status) {
            401 ->
                PanelApiException(
                    status,
                    if (authError) {
                        detail ?: context.getString(R.string.panel_error_auth)
                    } else {
                        context.getString(R.string.panel_error_session_expired)
                    },
                )
            429 -> PanelApiException(status, context.getString(R.string.panel_error_rate_limited))
            // 400/404/409/500 carry a user-facing explanation; 409 is also the configuration limit.
            400, 404, 409, 500 ->
                PanelApiException(
                    status,
                    detail ?: context.getString(R.string.panel_error_connect),
                )
            else -> PanelApiException(status, context.getString(R.string.panel_error_connect))
        }
    }

    private fun loadPinnedTrustManager(): X509TrustManager? =
        try {
            val certificate =
                context.resources.openRawResource(R.raw.panel).use { input ->
                    CertificateFactory.getInstance("X.509").generateCertificate(input)
                }
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    setCertificateEntry("panel", certificate)
                }
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            // No bundle or an empty file: a panel behind a real CA works with the system store.
            null
        }

    // ------------------------------------------------------------------ parsing

    private fun parseConfigs(array: JSONArray): List<PanelConfig> =
        (0 until array.length()).map { parseConfig(array.getJSONObject(it)) }

    private fun parseConfig(json: JSONObject): PanelConfig =
        PanelConfig(
            id = json.getInt("id"),
            name = json.getString("name"),
            deviceId = json.optString("device_id"),
            ip = json.optString("ip"),
            remoteIp = json.optString("remote_ip"),
            enabled = json.optBoolean("enabled", false),
            internet = json.optBoolean("internet", false),
            exits = json.optJSONArray("exits")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            }.orEmpty(),
            handshake = json.optLong("handshake"),
            rx = json.optLong("rx"),
            tx = json.optLong("tx"),
        )
}
