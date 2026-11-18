/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.split

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import org.amnezia.awg.Application
import org.amnezia.awg.config.Config

/** The split tunneling choice of one tunnel: which apps go into the tunnel, or which stay out. */
data class SplitTunnelSelection(val onlySelected: Boolean, val packages: Set<String>)

/**
 * Per-tunnel split tunneling, stored in the tunnel's own `.conf`.
 *
 * The backend already understands `IncludedApplications` / `ExcludedApplications` and turns them
 * into `addAllowedApplication` / `addDisallowedApplication`, so the selection is written into the
 * configuration file where the tunnel lives. That keeps one source of truth and survives restarts;
 * the copy in preferences exists only to restore the choice after the configuration is refreshed
 * from the panel.
 */
object SplitTunnel {

    private const val PREFS_NAME = "split_tunnel"
    private const val INCLUDE_ATTRIBUTE = "IncludedApplications"
    private const val EXCLUDE_ATTRIBUTE = "ExcludedApplications"

    fun load(context: Context, tunnelName: String): SplitTunnelSelection {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val packages =
            prefs.getString(KEY_PACKAGES + tunnelName, null)
                ?.split('\n')
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
        val onlySelected = prefs.getBoolean(KEY_MODE + tunnelName, false)
        return SplitTunnelSelection(onlySelected, packages)
    }

    fun store(context: Context, tunnelName: String, selection: SplitTunnelSelection) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_MODE + tunnelName, selection.onlySelected)
            .putString(KEY_PACKAGES + tunnelName, selection.packages.joinToString("\n"))
            .apply()
    }

    /**
     * Writes the stored selection into the tunnel's `.conf` and reloads it. Called when the selection
     * is saved and after the panel refreshes a configuration, because a fresh `.conf` has no split
     * tunneling lines.
     */
    suspend fun apply(context: Context, tunnelName: String): Boolean {
        val tunnel = Application.getTunnelManager().getTunnels()[tunnelName] ?: return false
        val file = File(context.filesDir, "$tunnelName.conf").takeIf { it.isFile } ?: return false
        val updated = applyToText(file.readText(), load(context, tunnelName))
        if (updated == file.readText()) return true
        file.writeText(updated)
        val config = Config.parse(ByteArrayInputStream(updated.toByteArray(Charsets.UTF_8)))
        Application.getTunnelManager().setTunnelConfig(tunnel, config)
        return true
    }

    /** Removes both split tunneling attributes and adds the current one right after `[Interface]`. */
    fun applyToText(text: String, selection: SplitTunnelSelection): String {
        val lines =
            text.lines()
                .filterNot { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith(INCLUDE_ATTRIBUTE) || trimmed.startsWith(EXCLUDE_ATTRIBUTE)
                }
                .toMutableList()
        if (selection.packages.isEmpty()) return lines.joinToString("\n")
        val attribute =
            if (selection.onlySelected) INCLUDE_ATTRIBUTE else EXCLUDE_ATTRIBUTE
        val entry = "$attribute = " + selection.packages.sorted().joinToString(", ")
        val interfaceIndex = lines.indexOfFirst { it.trim() == "[Interface]" }
        lines.add(if (interfaceIndex >= 0) interfaceIndex + 1 else 0, entry)
        return lines.joinToString("\n")
    }

    private const val KEY_MODE = "mode_"
    private const val KEY_PACKAGES = "packages_"
}
