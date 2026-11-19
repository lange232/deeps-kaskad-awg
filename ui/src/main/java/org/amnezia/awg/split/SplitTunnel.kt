/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.split

import java.io.ByteArrayInputStream
import org.amnezia.awg.config.Config

/** The split tunneling choice of one tunnel: which apps go into the tunnel, or which stay out. */
data class SplitTunnelSelection(val onlySelected: Boolean, val packages: Set<String>)

/** Per-tunnel split tunneling, with the local configuration as the source of truth. */
object SplitTunnel {

    private const val INCLUDE_ATTRIBUTE = "IncludedApplications"
    private const val EXCLUDE_ATTRIBUTE = "ExcludedApplications"

    fun selectionOf(config: Config): SplitTunnelSelection {
        val iface = config.getInterface()
        return if (iface.includedApplications.isNotEmpty()) {
            SplitTunnelSelection(true, iface.includedApplications)
        } else {
            SplitTunnelSelection(false, iface.excludedApplications)
        }
    }

    fun parse(text: String): Config = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    /** Merges the filter before the config reaches the running VPN or the config store. */
    fun withSelection(config: Config, selection: SplitTunnelSelection): Config =
        if (selectionOf(config) == selection) config
        else parse(applyToText(config.toAwgQuickString(), selection))

    /** Removes both split tunneling attributes and adds the current one right after `[Interface]`. */
    private fun applyToText(text: String, selection: SplitTunnelSelection): String {
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
}
