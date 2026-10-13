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

import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.MetricsLogger.VIEW_UNKNOWN
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.qs.QSTile.State
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import javax.inject.Inject

/*
 * Circa's own quick-settings tiles. They are ordinary SystemUI tiles (QSTileImpl, bound into the
 * tile map in CircaShadeModule), so they live in `sysui_qs_tiles` next to the platform ones and
 * work in the phone panel too. All three are only available while the Circa shade is enabled.
 */

/** Base for the Circa tiles: the Circa flag gates availability; settings observed while listening. */
abstract class CircaSettingTile<T : State>(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    backgroundLooper: Looper,
    mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val observedUris: List<android.net.Uri>,
) :
    QSTileImpl<T>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {
    private val observer =
        object : ContentObserver(mHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshState()
            }
        }

    override fun isAvailable(): Boolean = CircaShadeStartable.isEnabled(mContext)

    override fun handleSetListening(listening: Boolean) {
        super.handleSetListening(listening)
        val cr = mContext.contentResolver
        if (listening) {
            observedUris.forEach { cr.registerContentObserver(it, false, observer) }
        } else {
            cr.unregisterContentObserver(observer)
        }
    }

    override fun handleDestroy() {
        super.handleDestroy()
        mContext.contentResolver.unregisterContentObserver(observer)
    }

    override fun getMetricsCategory(): Int = VIEW_UNKNOWN
}

/**
 * Theater mode (stock Wear): `Settings.Global.THEATER_MODE_ON`. What it does while on is applied by
 * [CircaTheaterMode], which follows the setting however it is changed.
 */
class CircaTheaterTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) :
    CircaSettingTile<BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
        listOf(Settings.Global.getUriFor(Settings.Global.THEATER_MODE_ON)),
    ) {

    override fun newTileState(): BooleanState = BooleanState().apply { handlesLongClick = false }

    override fun handleClick(expandable: Expandable?) {
        Settings.Global.putInt(
            mContext.contentResolver,
            Settings.Global.THEATER_MODE_ON,
            if (CircaTheaterMode.isOn(mContext)) 0 else 1,
        )
    }

    override fun getLongClickIntent(): Intent? = null

    // No long-press page: never post a null intent to the ActivityStarter (audit A01).
    override fun handleLongClick(expandable: Expandable?) {}

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.circa_theater_mode)

    override fun handleUpdateState(state: BooleanState, arg: Any?) {
        val on = CircaTheaterMode.isOn(mContext)
        state.value = on
        state.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        state.label = mContext.getString(R.string.circa_theater_mode)
        state.icon = maybeLoadResourceIcon(R.drawable.circa_ic_movie)
        state.hasLongClickEffect = false
    }

    companion object {
        const val TILE_SPEC = "theater"
    }
}

/**
 * Brightness as a tile: a tap (or long press) opens Circa Settings straight at its brightness page,
 * where the crown sets the level; back from there returns to the watch, not the settings list.
 */
class CircaBrightnessTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) :
    CircaSettingTile<State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
        listOf(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
        ),
    ) {

    override fun newTileState(): State = State()

    override fun handleClick(expandable: Expandable?) {
        mActivityStarter.postStartActivityDismissingKeyguard(brightnessPage(), 0)
    }

    override fun getLongClickIntent(): Intent = brightnessPage()

    /** Circa Settings' crown-driven brightness page (its SettingsNav EXTRA_PAGE deep link). */
    private fun brightnessPage(): Intent =
        Intent(Settings.ACTION_SETTINGS)
            .setPackage(SETTINGS_PACKAGE)
            .putExtra(EXTRA_PAGE, PAGE_BRIGHTNESS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.circa_brightness)

    override fun handleUpdateState(state: State, arg: Any?) {
        val cr = mContext.contentResolver
        val level = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
        val auto =
            Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        state.state = Tile.STATE_ACTIVE
        state.label = mContext.getString(R.string.circa_brightness)
        state.secondaryLabel = if (auto) "Auto" else "${level * 100 / MAX}%"
        state.icon = maybeLoadResourceIcon(R.drawable.circa_ic_brightness_medium)
    }

    companion object {
        const val TILE_SPEC = "circa_brightness"
        const val MAX = 255
        private const val SETTINGS_PACKAGE = "org.circa.settings"
        private const val EXTRA_PAGE = "org.circa.settings.extra.PAGE"
        private const val PAGE_BRIGHTNESS = "brightness"
    }
}

/** A Settings shortcut as a tile (stock Wear has one in quick settings). */
class CircaSettingsTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
) :
    CircaSettingTile<State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
        emptyList(),
    ) {

    override fun newTileState(): State = State().apply { handlesLongClick = false }

    override fun handleClick(expandable: Expandable?) {
        mActivityStarter.postStartActivityDismissingKeyguard(
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            0,
        )
    }

    override fun getLongClickIntent(): Intent? = null

    override fun handleLongClick(expandable: Expandable?) {}

    override fun getTileLabel(): CharSequence = mContext.getString(R.string.circa_settings)

    override fun handleUpdateState(state: State, arg: Any?) {
        state.state = Tile.STATE_INACTIVE
        state.label = mContext.getString(R.string.circa_settings)
        state.icon = maybeLoadResourceIcon(R.drawable.circa_ic_settings)
    }

    companion object {
        const val TILE_SPEC = "circa_settings"
    }
}
