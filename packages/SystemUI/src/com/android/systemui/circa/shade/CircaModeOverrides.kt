/*
 * Copyright (C) 2026 The Circa Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.circa.shade

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject

/**
 * The Settings.Secure keys theater mode and Bedtime force while they are on, through one shared
 * [ModeOverrideBook] stored in [STORE]: the modes can overlap and end in any order, and a key goes
 * back to its value from before only if nobody changed it in between. Called on the main thread.
 */
@SysUISingleton
class CircaModeOverrides @Inject constructor(@Application private val context: Context) {

    private var book: ModeOverrideBook? = null

    private fun load(): ModeOverrideBook {
        book?.let {
            return it
        }
        val cr = context.contentResolver
        var b = ModeOverrideBook.decode(Settings.Secure.getString(cr, STORE))
        // Theater mode's store before the shared book (2026-10): carry an active theater mode over.
        val legacy = Settings.Secure.getString(cr, LEGACY_THEATER_STORE)
        if (legacy != null) {
            if (b.isEmpty()) {
                b = ModeOverrideBook.fromLegacyTheater(
                    CircaTheaterMode.MODE, legacy, QUIET_KEYS, OFF)
            }
            legacyTheaterRinger = legacy.split(';')
                .firstOrNull { it.startsWith("ringer=") }
                ?.substringAfter('=')
                ?.toIntOrNull()
            Settings.Secure.putString(cr, LEGACY_THEATER_STORE, null)
            Settings.Secure.putString(cr, STORE, b.encode().ifEmpty { null })
            Log.i(TAG, "Mode overrides: imported $LEGACY_THEATER_STORE")
        }
        book = b
        return b
    }

    /** The ringer mode theater mode saved in its old store, if it was on during the update. */
    var legacyTheaterRinger: Int? = null
        private set

    fun isActive(mode: String): Boolean = mode in load().activeModes()

    /** Turns [mode]'s overrides on (each key forced to its value) or off (null). Idempotent. */
    fun set(mode: String, overrides: Map<String, String>?) {
        val b = load()
        val cr = context.contentResolver
        val writes = b.set(mode, overrides) { Settings.Secure.getString(cr, it) }
        for ((key, value) in writes) Settings.Secure.putString(cr, key, value)
        Settings.Secure.putString(cr, STORE, b.encode().ifEmpty { null })
        if (writes.isNotEmpty()) Log.i(TAG, "Mode overrides ($mode ${if (overrides == null) "off" else "on"}): $writes")
    }

    companion object {
        private const val TAG = "CircaShade"
        const val STORE = "circa_mode_overrides"
        private const val LEGACY_THEATER_STORE = "circa_theater_saved"
        private const val OFF = "0"

        /** Always-on display, every doze wake source, tilt-to-wake and tap-to-wake. */
        val QUIET_KEYS =
            listOf(
                Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.DOZE_ENABLED,
                Settings.Secure.DOZE_PICK_UP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.WAKE_GESTURE_ENABLED,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
            )

        /** [QUIET_KEYS], all forced off. */
        val QUIET: Map<String, String> = QUIET_KEYS.associateWith { OFF }
    }
}
