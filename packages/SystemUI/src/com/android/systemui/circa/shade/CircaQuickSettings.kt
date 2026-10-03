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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.CustomTile
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.domain.model.TileModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BatteryController
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a quick-settings button draws. */
enum class CircaToggle {
    ON,
    OFF,
    UNAVAILABLE,
}

/**
 * One tile of the user's current tile list (`sysui_qs_tiles`), as SystemUI's tile pipeline created
 * it: platform tiles, Circa's own and third-party TileService tiles alike. State, label and icon are
 * the tile's own, mirrored into Compose state while the tray listens.
 */
class CircaTile(val spec: String, val tile: QSTile, private val mainExecutor: Executor) {
    val state = mutableStateOf(CircaToggle.UNAVAILABLE)
    val label = mutableStateOf<CharSequence>("")
    val secondaryLabel = mutableStateOf<CharSequence?>(null)
    val icon = mutableStateOf<QSTile.Icon?>(null)

    private val callback = QSTile.Callback { s -> mainExecutor.execute { apply(s) } }

    fun attach() {
        tile.addCallback(callback)
        apply(tile.state)
    }

    fun detach() {
        tile.removeCallback(callback)
        tile.setListening(this, false)
    }

    fun setListening(listening: Boolean) {
        tile.setListening(this, listening)
        if (listening) tile.refreshState()
    }

    /**
     * Tap = toggle, as on Wear. Tiles whose primary click opens a details dialog on the phone
     * (Wi-Fi, Bluetooth) toggle on their secondary click; they say so with handlesSecondaryClick.
     */
    fun toggle() {
        if (tile.state.handlesSecondaryClick) tile.secondaryClick(null) else tile.click(null)
    }

    /** Long press = the tile's settings page (the tile starts it, dismissing the keyguard). */
    fun longPress() = tile.longClick(null)

    private fun apply(s: QSTile.State) {
        label.value = s.label ?: tile.tileLabel ?: spec
        secondaryLabel.value = s.secondaryLabel
        icon.value = s.icon ?: s.iconSupplier?.get()
        state.value =
            when {
                s.disabledByPolicy -> CircaToggle.UNAVAILABLE
                s.state == Tile.STATE_ACTIVE -> CircaToggle.ON
                s.state == Tile.STATE_INACTIVE -> CircaToggle.OFF
                else -> CircaToggle.UNAVAILABLE
            }
    }
}

/** A tile that can be added in edit mode. */
data class CircaAvailableTile(val spec: String, val label: CharSequence, val icon: Drawable?)

/**
 * The quick-settings end of the tray, driven by SystemUI's real tile list: [CurrentTilesInteractor]
 * (backed by TileSpecRepository / `sysui_qs_tiles`) provides the tiles and their order; edit mode
 * adds, removes and reorders through the same interactor, so changes persist and the phone panel
 * (if ever shown) agrees. Besides the tiles: the battery level for the battery tile, the screen
 * brightness for the brightness ring, and the phone-connection pill.
 */
@SysUISingleton
class CircaQuickSettings
@Inject
constructor(
    @Application private val context: Context,
    private val currentTilesInteractor: CurrentTilesInteractor,
    private val batteryController: BatteryController,
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    @Background private val bgExecutor: Executor,
) {
    /** The current tiles, in the user's order. */
    val tiles = mutableStateOf<List<CircaTile>>(emptyList())

    /** Battery level 0..100, or -1 before the first report. */
    val batteryLevel = mutableIntStateOf(-1)

    /** Manual brightness (`Settings.System.SCREEN_BRIGHTNESS`, 0..255) and auto mode. */
    val brightness = mutableIntStateOf(0)
    val autoBrightness = mutableStateOf(false)

    /** A phone is connected to the watch's GATT server (WatchLink / Gadgetbridge). Read-only. */
    val phoneConnected = mutableStateOf(false)

    private var listening = false

    private val batteryCallback =
        object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                batteryLevel.intValue = level
            }
        }

    fun init() {
        batteryController.addCallback(batteryCallback)
        scope.launch {
            currentTilesInteractor.currentTiles.collect { models ->
                mainExecutor.execute { updateTiles(models) }
            }
        }
        applyDefaultTilesOnce()
    }

    /**
     * The first time the Circa shade runs, replace the phone's default tile list with the Circa
     * default (`circa_qs_tiles_default`). Afterwards the list is the user's (edit mode).
     */
    private fun applyDefaultTilesOnce() {
        val cr = context.contentResolver
        if (Settings.Secure.getInt(cr, DEFAULTS_APPLIED, 0) != 0) return
        val specs =
            context.getString(R.string.circa_qs_tiles_default)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // The DND-only toggle needs android.app.Flags.modesUiDndTile; else the Modes tile.
                .map { if (it == "modes_dnd" && availableLabel(it) == null) "dnd" else it }
                .map { TileSpec.create(it) }
        currentTilesInteractor.setTiles(specs)
        Settings.Secure.putInt(cr, DEFAULTS_APPLIED, 1)
    }

    private fun updateTiles(models: List<TileModel>) {
        val old = tiles.value.associateBy { it.tile }
        val next =
            models.map { m ->
                old[m.tile]
                    ?: CircaTile(m.spec.spec, m.tile, mainExecutor).also {
                        it.attach()
                        if (listening) it.setListening(true)
                    }
            }
        val kept = next.toSet()
        old.values.filter { it !in kept }.forEach { it.detach() }
        tiles.value = next
    }

    /** Called with true when the tray opens and false when it closes. */
    fun setListening(listening: Boolean) {
        this.listening = listening
        tiles.value.forEach { it.setListening(listening) }
        if (listening) {
            readBrightness()
            bgExecutor.execute {
                val connected = readPhoneConnected()
                mainExecutor.execute { phoneConnected.value = connected }
            }
        }
    }

    fun readBrightness() {
        val cr = context.contentResolver
        brightness.intValue = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
        autoBrightness.value =
            Settings.System.getInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    // ---- edit mode -------------------------------------------------------------------------

    fun addTile(spec: String) = currentTilesInteractor.addTile(TileSpec.create(spec))

    fun removeTile(spec: String) =
        currentTilesInteractor.removeTiles(listOf(TileSpec.create(spec)))

    /** Move the tile at [index] by [delta] places (-1 = earlier, +1 = later). */
    fun moveTile(index: Int, delta: Int) {
        val specs = tiles.value.map { it.spec }.toMutableList()
        val target = index + delta
        if (index !in specs.indices || target !in specs.indices) return
        val spec = specs.removeAt(index)
        specs.add(target, spec)
        currentTilesInteractor.setTiles(specs.map { TileSpec.create(it) })
    }

    /**
     * Tiles that can be added: SystemUI's stock tiles (`quick_settings_tiles_stock`), Circa's own,
     * and every installed TileService - minus the current ones and the ones that say they are not
     * available on this device (asked through throw-away tiles, on the main thread like the host).
     */
    fun loadAvailableTiles(onLoaded: (List<CircaAvailableTile>) -> Unit) {
        val current = tiles.value.map { it.spec }.toSet()
        val result = ArrayList<CircaAvailableTile>()
        val stock =
            (context.getString(R.string.quick_settings_tiles_stock).split(',') + CIRCA_TILES)
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in current }
                .distinct()
        for (spec in stock) {
            val label = availableLabel(spec) ?: continue
            result.add(CircaAvailableTile(spec, label, null))
        }
        bgExecutor.execute {
            val custom = ArrayList<CircaAvailableTile>()
            val pm = context.packageManager
            val services =
                pm.queryIntentServices(
                    Intent(TileService.ACTION_QS_TILE),
                    PackageManager.MATCH_DIRECT_BOOT_AWARE or
                        PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                )
            for (ri in services) {
                val si = ri.serviceInfo ?: continue
                if (si.permission != android.Manifest.permission.BIND_QUICK_SETTINGS_TILE) continue
                val spec = CustomTile.toSpec(ComponentName(si.packageName, si.name))
                if (spec in current) continue
                val icon = runCatching { si.loadIcon(pm) }.getOrNull()
                custom.add(CircaAvailableTile(spec, si.loadLabel(pm), icon))
            }
            mainExecutor.execute { onLoaded(result + custom) }
        }
    }

    /** The label of a stock tile if it is available here, else null. */
    private fun availableLabel(spec: String): CharSequence? {
        val t =
            try {
                currentTilesInteractor.createTileSync(TileSpec.create(spec))
            } catch (e: RuntimeException) {
                Log.w(TAG, "createTileSync($spec)", e)
                null
            } ?: return null
        return try {
            if (!t.isAvailable) null else (KNOWN_LABELS[spec] ?: t.tileLabel ?: spec)
        } finally {
            t.destroy()
        }
    }

    private fun readPhoneConnected(): Boolean =
        try {
            val manager = context.getSystemService(BluetoothManager::class.java)
            manager?.adapter?.isEnabled == true &&
                manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).isNotEmpty()
        } catch (e: RuntimeException) {
            false
        }

    fun openBluetoothSettings() =
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    companion object {
        private const val TAG = "CircaShade"
        private const val DEFAULTS_APPLIED = "circa_qs_defaults_applied"
        val CIRCA_TILES =
            listOf(
                CircaTheaterTile.TILE_SPEC,
                CircaBrightnessTile.TILE_SPEC,
                CircaSettingsTile.TILE_SPEC,
            )

        /** Short names for tiles whose own label is long or missing outside a tile view. */
        private val KNOWN_LABELS =
            mapOf(
                "internet" to "Internet",
                "wifi" to "Wi-Fi",
                "bt" to "Bluetooth",
                "dnd" to "Modes",
                "modes_dnd" to "Do Not Disturb",
                "battery" to "Battery Saver",
                "airplane" to "Airplane mode",
                "location" to "Location",
                "flashlight" to "Flashlight",
                "rotation" to "Auto-rotate",
                "hotspot" to "Hotspot",
                "saver" to "Data Saver",
                "dark" to "Dark theme",
                "screenrecord" to "Screen record",
                "theater" to "Theater mode",
                "circa_brightness" to "Brightness",
                "circa_settings" to "Settings",
            )
    }
}
