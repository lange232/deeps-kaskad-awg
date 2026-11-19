/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context
import android.net.Uri

/** Maps panel configurations to local tunnels within one server and authenticated account. */
class PanelConfigLinks(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun tunnelName(session: PanelSession, configId: Int): String? =
        prefs.getString(scope(session) + configId, null)

    fun ownConfigId(session: PanelSession): Int? =
        prefs.getInt(scope(session) + "device", -1).takeIf { it > 0 }

    fun save(session: PanelSession, configId: Int, tunnelName: String, own: Boolean) {
        val scope = scope(session)
        val key = scope + configId
        prefs.edit().apply {
            if (own) putInt(scope + "device", configId)
            putString(key, tunnelName)
        }.apply()
    }

    fun remove(session: PanelSession, configId: Int) {
        val scope = scope(session)
        prefs.edit().apply {
            remove(scope + configId)
            if (ownConfigId(session) == configId) remove(scope + "device")
        }.apply()
    }

    /** A pre-upgrade record has no account. Only use it as a candidate for key verification. */
    fun legacyCandidate(session: PanelSession, configId: Int): String? {
        val value = prefs.getString(LEGACY_PREFIX + configId, null) ?: return null
        val (name, server) = value.split('\t', limit = 2).takeIf { it.size == 2 } ?: return null
        if (server.trimEnd('/') != session.server.trimEnd('/')) return null
        if (prefs.all.any { (key, linked) -> key.startsWith(SCOPED_PREFIX) && linked == name }) return null
        return name
    }

    fun forgetTunnel(name: String) = changeTunnelName(name, null)

    fun renameTunnel(oldName: String, newName: String) = changeTunnelName(oldName, newName)

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
        "$SCOPED_PREFIX${Uri.encode(session.server.trimEnd('/'))}:${Uri.encode(session.email)}:"

    private companion object {
        const val PREFS_NAME = "panel_links"
        const val LEGACY_PREFIX = "config_"
        const val SCOPED_PREFIX = "scope:"
    }
}
