/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

/**
 * Turns a hand-typed name into one the panel accepts (`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,31}$`).
 * Returns null when nothing usable is left, so the panel generates a name itself instead of
 * answering 400.
 */
fun slugifyPanelName(raw: String): String? =
    raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(32)
        .trim('-')
        .ifEmpty { null }

/**
 * Identifies this installation on the panel.
 *
 * Two separate values are sent when a configuration is created:
 * - [deviceId] is the machine identity. The panel keeps it unique per account, so asking for a
 *   configuration with a known id returns the existing one instead of creating a second peer: the
 *   same phone after a reinstall gets the same configuration, keys and address back.
 * - [name] is what a person reads in the panel: the slugified device model only.
 *
 * `ANDROID_ID` is tied to the signing key, so it survives reinstalls of a build signed by the same
 * key. On the rare device where it is empty, a random UUID is generated once and kept in the
 * app-private preferences.
 */
class PanelDevice(context: Context) {

    private val androidId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            .orEmpty()
            .trim()

    val deviceId: String = buildDeviceId(context)

    val name: String = buildName()

    /** The name earlier builds used: model plus the first eight characters of `ANDROID_ID`. */
    val legacyName: String = buildLegacyName()

    private fun buildDeviceId(context: Context): String {
        if (androidId.isNotEmpty()) return androidId
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun buildName(): String {
        val slug = slugifyModel()
        return if (slug.isNotEmpty() && NAME_PATTERN.matches(slug)) slug else FALLBACK_NAME
    }

    private fun buildLegacyName(): String {
        val id = androidId.lowercase().filter(Char::isLetterOrDigit).take(8).ifBlank { "unknown" }
        val model = slugifyModel().ifBlank { FALLBACK_NAME }.take(LEGACY_MODEL_LENGTH).trim('-')
        val candidate = "$model-$id"
        return if (candidate.length <= NAME_MAX_LENGTH && NAME_PATTERN.matches(candidate)) {
            candidate
        } else {
            id
        }
    }

    private fun slugifyModel(): String =
        (Build.MODEL ?: "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(NAME_MAX_LENGTH)
            .trim('-')

    private companion object {
        const val PREFS_NAME = "panel_device"
        const val KEY_DEVICE_ID = "device_id"
        const val NAME_MAX_LENGTH = 32
        const val LEGACY_MODEL_LENGTH = 12
        const val FALLBACK_NAME = "android"

        val NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]*$")
    }
}
