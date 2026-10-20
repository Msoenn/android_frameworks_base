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
 * * AOD and every doze wake source off ([CircaModeOverrides.QUIET]: `doze_always_on`,
 *   `doze_enabled`, pick-up / tap / double-tap pulses, `wake_gesture_enabled`,
 *   `double_tap_to_wake`), shared with Bedtime through [CircaModeOverrides] so the two can overlap;
 * * notifications silent and without vibration: ringer mode SILENT, the previous mode kept in
 *   Settings.Secure [SAVED_RINGER].
 * The power button still wakes the screen (temporarily: the doze settings stay off, so it goes
 * dark again on timeout).
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
    private val overrides: CircaModeOverrides,
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
        // An update in theater mode: the old store held the ringer too (isActive loads the book,
        // which imports the old store).
        overrides.isActive(MODE)
        overrides.legacyTheaterRinger?.let {
            if (Settings.Secure.getString(context.contentResolver, SAVED_RINGER) == null) {
                Settings.Secure.putInt(context.contentResolver, SAVED_RINGER, it)
            }
        }
        sync()
    }

    private fun sync() {
        val on = isOn(context)
        val active = overrides.isActive(MODE)
        if (on && !active) enter() else if (!on && active) exit()
    }

    private fun enter() {
        val cr = context.contentResolver
        val audio = context.getSystemService(AudioManager::class.java)
        overrides.set(MODE, CircaModeOverrides.QUIET)
        Settings.Secure.putInt(cr, SAVED_RINGER, audio?.ringerModeInternal ?: -1)
        audio?.ringerModeInternal = AudioManager.RINGER_MODE_SILENT
        context.getSystemService(PowerManager::class.java)?.goToSleep(SystemClock.uptimeMillis())
        Log.i(TAG, "Theater mode on")
    }

    private fun exit() {
        val cr = context.contentResolver
        overrides.set(MODE, null)
        val mode = Settings.Secure.getInt(cr, SAVED_RINGER, -1)
        if (mode >= 0) context.getSystemService(AudioManager::class.java)?.ringerModeInternal = mode
        Settings.Secure.putString(cr, SAVED_RINGER, null)
        Log.i(TAG, "Theater mode off")
    }

    companion object {
        private const val TAG = "CircaShade"
        const val MODE = "theater"
        private const val SAVED_RINGER = "circa_theater_ringer"

        fun isOn(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, Settings.Global.THEATER_MODE_ON, 0) != 0
    }
}
