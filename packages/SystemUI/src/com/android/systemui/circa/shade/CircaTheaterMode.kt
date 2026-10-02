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
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject

/**
 * Stock Wear's theater mode on top of AOSP's `Settings.Global.THEATER_MODE_ON`.
 *
 * What the Android 16 framework already does with the setting: PowerManagerService does not wake on
 * plug/unplug (`config_allowTheaterModeWakeFromUnplug`) or dock, and WindowState ignores
 * FLAG_TURN_SCREEN_ON / setTurnScreenOn (`config_allowTheaterModeWakeFromWindowLayout`). The older
 * key/motion/gesture/lid gates in PhoneWindowManager are gone, and nothing silences notifications.
 *
 * What this adds while the setting is on (and undoes when it goes off; follows the setting
 * however it is changed - the tile, `settings put global theater_mode_on 1`, another app):
 * * the screen goes off right away (as on stock);
 * * AOD and every doze wake source off: `doze_always_on`, `doze_enabled`, pick-up / tap /
 *   double-tap pulses, `wake_gesture_enabled` (tilt-to-wake), `double_tap_to_wake`;
 * * notifications silent and without vibration: ringer mode SILENT;
 * the previous values are kept in Settings.Secure [SAVED_SETTING] so a SystemUI restart in theater
 * mode still restores them. The power button still wakes the screen (temporarily: the doze
 * settings stay off, so it goes dark again on timeout).
 *
 * Not covered here (other layers): the crown press / crown rotation wake in PhoneWindowManager and
 * the launcher's own tilt-to-wake must check THEATER_MODE_ON themselves.
 */
@SysUISingleton
class CircaTheaterMode
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
) {
    private val observer =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                sync()
            }
        }

    fun start() {
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.THEATER_MODE_ON),
            false,
            observer,
        )
        sync()
    }

    private fun sync() {
        val on = isOn(context)
        val saved = Settings.Secure.getString(context.contentResolver, SAVED_SETTING)
        if (on && saved == null) enter() else if (!on && saved != null) exit(saved)
    }

    private fun enter() {
        val cr = context.contentResolver
        val audio = context.getSystemService(AudioManager::class.java)
        val saved = StringBuilder()
        for (key in SECURE_KEYS) {
            val v = Settings.Secure.getString(cr, key)
            saved.append(key).append('=').append(v ?: NULL).append(';')
            Settings.Secure.putInt(cr, key, 0)
        }
        saved.append(RINGER).append('=').append(audio?.ringerModeInternal ?: -1)
        Settings.Secure.putString(cr, SAVED_SETTING, saved.toString())
        audio?.ringerModeInternal = AudioManager.RINGER_MODE_SILENT
        context.getSystemService(PowerManager::class.java)?.goToSleep(SystemClock.uptimeMillis())
        Log.i(TAG, "Theater mode on")
    }

    private fun exit(saved: String) {
        val cr = context.contentResolver
        for (entry in saved.split(';')) {
            val i = entry.indexOf('=')
            if (i <= 0) continue
            val key = entry.substring(0, i)
            val value = entry.substring(i + 1)
            if (key == RINGER) {
                val mode = value.toIntOrNull() ?: continue
                if (mode >= 0) {
                    context.getSystemService(AudioManager::class.java)?.ringerModeInternal = mode
                }
            } else if (key in SECURE_KEYS) {
                Settings.Secure.putString(cr, key, if (value == NULL) null else value)
            }
        }
        Settings.Secure.putString(cr, SAVED_SETTING, null)
        Log.i(TAG, "Theater mode off")
    }

    companion object {
        private const val TAG = "CircaShade"
        private const val SAVED_SETTING = "circa_theater_saved"
        private const val RINGER = "ringer"
        private const val NULL = "null"
        private val SECURE_KEYS =
            listOf(
                Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.DOZE_ENABLED,
                Settings.Secure.DOZE_PICK_UP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.WAKE_GESTURE_ENABLED,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
            )

        fun isOn(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.THEATER_MODE_ON, 0) != 0
    }
}
