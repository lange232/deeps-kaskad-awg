/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.util

import android.content.Context

/**
 * Remembers the order of tunnels as the user arranged them on the main screen.
 *
 * The tunnel list itself is kept sorted by [org.amnezia.awg.model.TunnelComparator]; this class
 * supplies the ranks that comparator sorts by, so a reorder is just a new list of names.
 */
object TunnelOrder {

    private const val PREFS_NAME = "tunnel_order"
    private const val KEY_NAMES = "names"

    fun names(context: Context): List<String> =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NAMES, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun ranks(context: Context): Map<String, Int> =
        names(context).withIndex().associate { (index, name) -> name to index }

    fun save(context: Context, names: List<String>) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAMES, names.joinToString("\n"))
            .apply()
    }

    /** A new tunnel goes to the end of the list. */
    fun append(context: Context, name: String) {
        val current = names(context)
        if (current.contains(name)) return
        save(context, current + name)
    }

    fun remove(context: Context, name: String) {
        save(context, names(context).filterNot { it == name })
    }
}
