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

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.quicksettings.Tile
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.QSHost
import com.android.systemui.statusbar.policy.BatteryController
import java.util.concurrent.Executor
import javax.inject.Inject

/** What a quick-settings button draws. */
enum class CircaToggle {
    ON,
    OFF,
    UNAVAILABLE,
}

/**
 * One quick-settings button backed by SystemUI's own tile implementation (`qs/tiles`), created
 * through [QSHost.createTile] - the same Wi-Fi / Bluetooth / DND / battery saver / airplane /
 * location logic the phone shade uses, not a re-implementation. [specs] lists fallbacks: the
 * first spec whose tile exists and reports itself available is used.
 */
class CircaTile(private val specs: List<String>, private val mainExecutor: Executor) {
    val state = mutableStateOf(CircaToggle.UNAVAILABLE)
    val label = mutableStateOf<CharSequence>("")

    var tile: QSTile? = null
        private set

    private val callback = QSTile.Callback { s -> mainExecutor.execute { apply(s) } }

    fun create(host: QSHost) {
        if (tile != null) return
        for (spec in specs) {
            val t =
                try {
                    host.createTile(spec)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "createTile($spec) failed", e)
                    null
                } ?: continue
            if (!t.isAvailable) {
                t.destroy()
                continue
            }
            t.setTileSpec(spec)
            t.addCallback(callback)
            tile = t
            apply(t.state)
            return
        }
        Log.w(TAG, "No available tile for $specs")
    }

    fun setListening(owner: Any, listening: Boolean) {
        val t = tile ?: return
        t.setListening(owner, listening)
        if (listening) t.refreshState()
    }

    /**
     * Tap = toggle, as on Wear. Tiles whose primary click opens a details dialog on the phone
     * (Wi-Fi, Bluetooth) toggle on their secondary click; they say so with handlesSecondaryClick.
     */
    fun toggle() {
        val t = tile ?: return
        if (t.state.handlesSecondaryClick) t.secondaryClick(null) else t.click(null)
    }

    /** Long press = the tile's settings page (the tile starts it, dismissing the keyguard). */
    fun openSettings() {
        tile?.longClick(null)
    }

    private fun apply(s: QSTile.State) {
        label.value = s.label ?: ""
        state.value =
            when {
                s.disabledByPolicy -> CircaToggle.UNAVAILABLE
                s.state == Tile.STATE_ACTIVE -> CircaToggle.ON
                s.state == Tile.STATE_INACTIVE -> CircaToggle.OFF
                else -> CircaToggle.UNAVAILABLE
            }
    }

    private companion object {
        const val TAG = "CircaShade"
    }
}

/**
 * Everything the quick-settings end of the tray shows (fork-plan A5, the launcher prototype's
 * "stock grid, fixed"): a status row (airplane, location, auto brightness), a 3 + 3 grid (DND,
 * Bluetooth, Wi-Fi; battery/saver, brightness, Settings) and the phone-connection pill.
 */
@SysUISingleton
class CircaQuickSettings
@Inject
constructor(
    @Application private val context: Context,
    private val host: QSHost,
    private val batteryController: BatteryController,
    private val activityStarter: ActivityStarter,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
) {
    val dnd = CircaTile(listOf("modes_dnd", "dnd"), mainExecutor)
    val bluetooth = CircaTile(listOf("bt"), mainExecutor)
    val wifi = CircaTile(listOf("wifi", "internet"), mainExecutor)
    val batterySaver = CircaTile(listOf("battery"), mainExecutor)
    val airplane = CircaTile(listOf("airplane"), mainExecutor)
    val location = CircaTile(listOf("location"), mainExecutor)
    private val tiles = listOf(dnd, bluetooth, wifi, batterySaver, airplane, location)

    /** Battery level 0..100, or -1 before the first report. */
    val batteryLevel = mutableIntStateOf(-1)
    val charging = mutableStateOf(false)

    /** Manual brightness (`Settings.System.SCREEN_BRIGHTNESS`, 0..255) and auto mode. */
    val brightness = mutableIntStateOf(0)
    val autoBrightness = mutableStateOf(false)

    /** A phone is connected to the watch's GATT server (WatchLink / Gadgetbridge). Read-only. */
    val phoneConnected = mutableStateOf(false)

    private var created = false

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, isCharging: Boolean) {
                batteryLevel.intValue = level
                charging.value = isCharging
            }
        }

    fun init() {
        batteryController.addCallback(batteryCallback)
    }

    /** Called with true when the tray opens and false when it closes. */
    fun setListening(listening: Boolean) {
        if (listening && !created) {
            created = true
            tiles.forEach { it.create(host) }
        }
        tiles.forEach { it.setListening(this, listening) }
        if (listening) {
            readBrightness()
            bgExecutor.execute {
                val connected = readPhoneConnected()
                mainExecutor.execute { phoneConnected.value = connected }
            }
        }
    }

    /** Step through the three brightness levels (manual mode), as the launcher tray did. */
    fun cycleBrightness() {
        val next = LEVELS.firstOrNull { it > brightness.intValue } ?: LEVELS.first()
        val cr = context.contentResolver
        Settings.System.putInt(
            cr,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, next)
        readBrightness()
    }

    fun toggleAutoBrightness() {
        val cr = context.contentResolver
        Settings.System.putInt(
            cr,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (autoBrightness.value) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            },
        )
        readBrightness()
    }

    fun openSettings() = startDismissingKeyguard(Intent(Settings.ACTION_SETTINGS))

    fun openBluetoothSettings() = startDismissingKeyguard(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openDisplaySettings() = startDismissingKeyguard(Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun openBatterySettings() =
        startDismissingKeyguard(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))

    private fun startDismissingKeyguard(intent: Intent) {
        activityStarter.postStartActivityDismissingKeyguard(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            0,
        )
    }

    private fun readBrightness() {
        val cr = context.contentResolver
        brightness.intValue =
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, LEVELS.first())
        autoBrightness.value =
            Settings.System.getInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    private fun readPhoneConnected(): Boolean =
        try {
            val manager = context.getSystemService(BluetoothManager::class.java)
            manager?.adapter?.isEnabled == true &&
                manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).isNotEmpty()
        } catch (e: RuntimeException) {
            false
        }

    companion object {
        /** The brightness steps the tile cycles through (0..255). */
        val LEVELS = listOf(40, 130, 230)
        const val BRIGHTNESS_MAX = 255
    }
}
