/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2026 deeps kaskad
 */

package org.amnezia.awg.panel

import android.content.Context

/** A signed-in panel session: where the panel lives, who signed in and the bearer token. */
data class PanelSession(val server: String, val email: String, val token: String, val expiresAt: Long? = null)

/**
 * Keeps the panel session in the app-private preferences. The token lives 30 days on the server
 * side; an expired session must ask the user to sign in again, never silently drop the
 * configuration.
 */
class PanelSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var session: PanelSession?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            val email = prefs.getString(KEY_EMAIL, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return PanelSession(server, email, token, prefs.getLong(KEY_EXPIRES_AT, 0).takeIf { it > 0 })
        }
        set(value) {
            prefs
                .edit()
                .apply {
                    if (value == null) {
                        remove(KEY_SERVER)
                        remove(KEY_EMAIL)
                        remove(KEY_TOKEN)
                        remove(KEY_EXPIRES_AT)
                    } else {
                        putString(KEY_SERVER, value.server)
                        putString(KEY_EMAIL, value.email)
                        putString(KEY_TOKEN, value.token)
                        if (value.expiresAt != null) putLong(KEY_EXPIRES_AT, value.expiresAt)
                        else remove(KEY_EXPIRES_AT)
                    }
                }
                .apply()
        }

    /** The configuration bound to this device, if any. */
    var selectedConfigId: Int?
        get() = prefs.getInt(KEY_SELECTED_CONFIG, -1).takeIf { it > 0 }
        set(value) {
            prefs.edit().putInt(KEY_SELECTED_CONFIG, value ?: -1).apply()
        }

    /** Last server and email, so the sign-in form is prefilled after signing out. */
    var lastServer: String?
        get() = prefs.getString(KEY_LAST_SERVER, null)
        set(value) = prefs.edit().putString(KEY_LAST_SERVER, value).apply()

    var lastEmail: String?
        get() = prefs.getString(KEY_LAST_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_LAST_EMAIL, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "panel_session"
        const val KEY_SERVER = "server"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_SELECTED_CONFIG = "selected_config_id"
        const val KEY_LAST_SERVER = "last_server"
        const val KEY_LAST_EMAIL = "last_email"
    }
}
