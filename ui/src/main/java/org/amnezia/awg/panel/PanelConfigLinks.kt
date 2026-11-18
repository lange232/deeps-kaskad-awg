/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context

/** A configuration of the panel and the local tunnel it was imported into. */
data class PanelConfigLink(val configId: Int, val tunnelName: String, val server: String)

/**
 * Remembers which local tunnel belongs to which panel configuration.
 *
 * A configuration belongs to exactly one device, so the link also answers "is this configuration
 * mine": a configuration without a link on this phone was imported for someone else, and refreshing
 * its `.conf` must not touch the local tunnel.
 */
class PanelConfigLinks(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<PanelConfigLink> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
            val parts = value.split(SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            val configId = key.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
            PanelConfigLink(configId, parts[0], parts[1])
        }

    fun byConfigId(configId: Int): PanelConfigLink? =
        prefs.getString(KEY_PREFIX + configId, null)?.let { value ->
            val parts = value.split(SEPARATOR)
            if (parts.size != 2) null else PanelConfigLink(configId, parts[0], parts[1])
        }

    fun linkOfThisDevice(server: String): PanelConfigLink? =
        all().firstOrNull { it.server == server }

    fun save(link: PanelConfigLink) {
        // Tunnel names are unique, so a tunnel cannot point at two configurations.
        all().filter { it.tunnelName == link.tunnelName && it.configId != link.configId }
            .forEach { remove(it.configId) }
        prefs
            .edit()
            .putString(
                KEY_PREFIX + link.configId,
                listOf(link.tunnelName, link.server).joinToString(SEPARATOR),
            )
            .apply()
    }

    fun remove(configId: Int) {
        prefs.edit().remove(KEY_PREFIX + configId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "panel_links"
        const val KEY_PREFIX = "config_"
        const val SEPARATOR = "\u0000"
    }
}
