/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context
import android.net.Uri
import java.util.Locale

/** Maps panel configurations to local tunnels within one server and authenticated account. */
class PanelConfigLinks(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun tunnelName(session: PanelSession, configId: Int): String? =
        matchingScopes(session).mapNotNull { prefs.getString(it + configId, null) }.distinct().singleOrNull()

    fun ownConfigId(session: PanelSession): Int? =
        matchingScopes(session).mapNotNull { prefs.getInt(it + "device", -1).takeIf { id -> id > 0 } }
            .distinct().singleOrNull()

    fun save(session: PanelSession, configId: Int, tunnelName: String, own: Boolean) {
        val scope = scope(session)
        val key = scope + configId
        prefs.edit().apply {
            matchingScopes(session).filter { it != scope }.forEach {
                remove(it + configId)
                if (own && prefs.getInt(it + "device", -1) == configId) remove(it + "device")
            }
            if (own) putInt(scope + "device", configId)
            putString(key, tunnelName)
        }.apply()
    }

    fun remove(session: PanelSession, configId: Int) {
        prefs.edit().apply {
            matchingScopes(session).forEach { scope ->
                remove(scope + configId)
                if (prefs.getInt(scope + "device", -1) == configId) remove(scope + "device")
            }
        }.apply()
    }

    /** A pre-upgrade record has no account. Only use it as a candidate for key verification. */
    fun legacyCandidate(session: PanelSession, configId: Int): String? {
        val value = prefs.getString(LEGACY_PREFIX + configId, null) ?: return null
        val (name, server) = value.split('\t', limit = 2).takeIf { it.size == 2 } ?: return null
        if (canonicalServer(server) != canonicalServer(session.server)) return null
        if (prefs.all.any { (key, linked) -> key.startsWith(SCOPED_PREFIX) && linked == name }) return null
        return name
    }

    fun forgetTunnel(name: String) = changeTunnelName(name, null)

    fun renameTunnel(oldName: String, newName: String) = changeTunnelName(oldName, newName)

    /** Never bind a local tunnel that is already owned by another account or configuration. */
    fun isAvailable(session: PanelSession, configId: Int, name: String): Boolean {
        val allowed = matchingScopes(session).map { it + configId }.toSet()
        return prefs.all.none { (key, value) ->
            key.startsWith(SCOPED_PREFIX) && value == name && key !in allowed
        }
    }

    private fun changeTunnelName(oldName: String, newName: String?) {
        prefs.edit().apply {
            prefs.all.forEach { (key, value) ->
                if (key.startsWith(SCOPED_PREFIX) && value == oldName) {
                    if (newName == null) {
                        remove(key)
                        val deviceKey = key.substringBeforeLast(':') + ":device"
                        if (prefs.getInt(deviceKey, -1) == key.substringAfterLast(':').toIntOrNull()) {
                            remove(deviceKey)
                        }
                    } else putString(key, newName)
                } else if (key.startsWith(LEGACY_PREFIX) && value is String && value.substringBefore('\t') == oldName) {
                    if (newName == null) remove(key)
                    else putString(key, "$newName\t${value.substringAfter('\t')}")
                }
            }
        }.apply()
    }

    private fun scope(session: PanelSession): String =
        "$SCOPED_PREFIX${Uri.encode(canonicalServer(session.server))}:${Uri.encode(session.email.trim().lowercase(Locale.ROOT))}:"

    private fun matchingScopes(session: PanelSession): Set<String> {
        val canonical = scope(session)
        val scopes = mutableSetOf(canonical)
        prefs.all.keys.filter { it.startsWith(SCOPED_PREFIX) }.forEach { key ->
            val parts = key.removePrefix(SCOPED_PREFIX).split(':')
            if (parts.size == 3 &&
                canonicalServer(Uri.decode(parts[0])) == canonicalServer(session.server) &&
                Uri.decode(parts[1]).equals(session.email.trim(), ignoreCase = true)
            ) {
                scopes.add("$SCOPED_PREFIX${parts[0]}:${parts[1]}:")
            }
        }
        return scopes
    }

    private fun canonicalServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        val uri = Uri.parse(trimmed)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null ||
            uri.userInfo != null || uri.query != null || uri.fragment != null
        ) return trimmed
        val host = uri.host!!.lowercase(Locale.ROOT)
        val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://$authority$port${uri.encodedPath.orEmpty().trimEnd('/')}"
    }

    private companion object {
        const val PREFS_NAME = "panel_links"
        const val LEGACY_PREFIX = "config_"
        const val SCOPED_PREFIX = "scope:"
    }
}
