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

import android.app.AlarmManager
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Settings
import android.service.notification.Condition
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Bedtime (stock Pixel Watch's Bedtime mode; docs/watch-ui/circa/bedtime.md).
 *
 * The state is the platform's own `Settings.Global.Wearable.BEDTIME_MODE` ("bedtime_mode", 0/1):
 * the display service already caps the brightness while it is 1 (BrightnessWearBedtimeModeModifier).
 * This class follows the setting however it is changed (the tile, Circa Settings, adb, the
 * schedule) and while it is on:
 * * Do Not Disturb through a SystemUI-owned AutomaticZenRule of type TYPE_BEDTIME (the global DND
 *   policy, which lets alarms through; the user's own DND is left alone);
 * * AOD, every doze wake source and tap-to-wake off ([CircaModeOverrides.QUIET], shared with theater
 *   mode); the launcher skips tilt-to-wake while the setting is 1. The buttons still wake.
 *
 * It also owns what turns Bedtime on and off: the schedule (Settings.Secure [KEY_SCHEDULE],
 * one exact alarm for the next boundary; a boundary acts once, so a manual change holds until the
 * next one), "Start when asleep" ([KEY_ON_SLEEP]: WatchLink's [ACTION_SLEEP_STATE], 20:00 - 10:00
 * only; a night end only ends a Bedtime the sleep detection started), and a dismissed Clock alarm
 * ([ACTION_ALARM_DISMISSED]). [KEY_SOURCE] records who turned it on.
 */
@SysUISingleton
class CircaBedtimeMode
@Inject
constructor(
    @Application private val context: Context,
    @Main private val mainHandler: Handler,
    private val overrides: CircaModeOverrides,
) {
    private val cr = context.contentResolver
    private var started = false

    private val stateObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                sync()
            }
        }

    private val scheduleObserver =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                handleSchedule(scheduleChanged = true)
            }
        }

    /** Own events: the schedule alarm, and the clock moving. */
    private val internalReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                handleSchedule(scheduleChanged = false)
            }
        }

    /** WatchLink's sleep state and Clock's dismissed alarm; the sender must identify itself. */
    private val externalReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val from = sentFromPackage
                when (intent.action) {
                    ACTION_SLEEP_STATE ->
                        if (from == WATCHLINK) onSleepState(intent.getBooleanExtra(EXTRA_ASLEEP, false))
                        else Log.w(TAG, "Bedtime: sleep state from $from ignored")
                    ACTION_ALARM_DISMISSED ->
                        if (from == CLOCK) onAlarmDismissed()
                        else Log.w(TAG, "Bedtime: alarm dismissal from $from ignored")
                }
            }
        }

    fun start() {
        if (started) return
        started = true
        cr.registerContentObserver(Settings.Global.getUriFor(KEY_STATE), false, stateObserver)
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_SCHEDULE), false, scheduleObserver)
        context.registerReceiver(
            internalReceiver,
            IntentFilter().apply {
                addAction(ACTION_EDGE)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            null,
            mainHandler,
            Context.RECEIVER_NOT_EXPORTED,
        )
        context.registerReceiver(
            externalReceiver,
            IntentFilter().apply {
                addAction(ACTION_SLEEP_STATE)
                addAction(ACTION_ALARM_DISMISSED)
            },
            null,
            mainHandler,
            Context.RECEIVER_EXPORTED,
        )
        sync()
        handleSchedule(scheduleChanged = false)
    }

    fun isOn(): Boolean = isOn(context)

    /** Turns Bedtime on or off; [source] is one of the SOURCE_ values. */
    fun set(on: Boolean, source: String) {
        if (on == isOn()) return
        if (on) Settings.Secure.putString(cr, KEY_SOURCE, source)
        Settings.Global.putInt(cr, KEY_STATE, if (on) 1 else 0)
        Log.i(TAG, "Bedtime ${if (on) "on" else "off"} ($source)")
    }

    private fun sync() {
        val on = isOn()
        overrides.set(MODE, if (on) CircaModeOverrides.QUIET else null)
        setZen(on)
        if (!on) Settings.Secure.putString(cr, KEY_SOURCE, null)
    }

    // ---- Do Not Disturb ----------------------------------------------------------------------

    private fun setZen(on: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            val id = zenRuleId(nm, create = on) ?: return
            nm.setAutomaticZenRuleState(
                id,
                Condition(
                    CONDITION_ID,
                    "Bedtime",
                    if (on) Condition.STATE_TRUE else Condition.STATE_FALSE,
                ),
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Bedtime: could not set Do Not Disturb", e)
        }
    }

    private fun zenRuleId(nm: NotificationManager, create: Boolean): String? {
        Settings.Secure.getString(cr, KEY_ZEN_RULE)?.let { id ->
            if (nm.getAutomaticZenRule(id) != null) return id
        }
        if (!create) return null
        val rule =
            AutomaticZenRule.Builder("Bedtime", CONDITION_ID)
                .setType(AutomaticZenRule.TYPE_BEDTIME)
                .setConfigurationActivity(SETTINGS_ACTIVITY)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                .setEnabled(true)
                .build()
        val id = nm.addAutomaticZenRule(rule)
        Settings.Secure.putString(cr, KEY_ZEN_RULE, id)
        Log.i(TAG, "Bedtime: Do Not Disturb rule $id")
        return id
    }

    // ---- schedule -----------------------------------------------------------------------------

    /**
     * Applies the last schedule boundary if it was not handled yet and arms the alarm for the next
     * one. After a schedule change only a start applies (turning the schedule on inside its window
     * starts Bedtime; an edit outside it must not end a Bedtime turned on by hand).
     */
    private fun handleSchedule(scheduleChanged: Boolean) {
        val s = BedtimeSchedule.decode(Settings.Secure.getString(cr, KEY_SCHEDULE))
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val last = s.lastEdge(now, zone)
        val handled = Settings.Secure.getLong(cr, KEY_EDGE, 0L)
        when (BedtimeSchedule.edgeAction(last, handled)) {
            true -> set(true, SOURCE_SCHEDULE)
            false -> if (!scheduleChanged) set(false, SOURCE_SCHEDULE)
            null -> {}
        }
        if (last != null && last.atMillis > handled) Settings.Secure.putLong(cr, KEY_EDGE, last.atMillis)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_EDGE).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val next = s.nextEdge(now, zone)
        if (next == null) {
            am.cancel(pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atMillis, pi)
            Log.i(TAG, "Bedtime: next ${if (next.start) "start" else "end"} ${Instant.ofEpochMilli(next.atMillis)}")
        }
    }

    // ---- sleep detection and alarms ------------------------------------------------------------

    private fun onSleepState(asleep: Boolean) {
        Log.i(TAG, "Bedtime: WatchLink says asleep=$asleep")
        if (asleep) {
            if (Settings.Secure.getInt(cr, KEY_ON_SLEEP, 1) == 0 || isOn()) return
            val local = Instant.now().atZone(ZoneId.systemDefault())
            if (!BedtimeSchedule.sleepStartAllowed(local.hour * 60 + local.minute)) return
            set(true, SOURCE_SLEEP)
        } else if (isOn() && Settings.Secure.getString(cr, KEY_SOURCE) == SOURCE_SLEEP) {
            set(false, SOURCE_SLEEP)
        }
    }

    private fun onAlarmDismissed() {
        if (isOn()) set(false, SOURCE_ALARM)
    }

    companion object {
        private const val TAG = "CircaShade"
        const val MODE = "bedtime"

        /** Settings.Global, the platform's Wear Bedtime switch (Settings.Global.Wearable.BEDTIME_MODE). */
        const val KEY_STATE = "bedtime_mode"

        /** Settings.Secure `enabled;startMin;endMin;days` ([BedtimeSchedule]); written by Circa Settings. */
        const val KEY_SCHEDULE = "circa_bedtime_schedule"

        /** Settings.Secure "1" = start Bedtime when WatchLink detects sleep; written by Circa Settings. */
        const val KEY_ON_SLEEP = "circa_bedtime_on_sleep"

        /** Settings.Secure: who turned Bedtime on (SOURCE_*), cleared when it ends. */
        const val KEY_SOURCE = "circa_bedtime_source"
        private const val KEY_EDGE = "circa_bedtime_edge"
        private const val KEY_ZEN_RULE = "circa_bedtime_zen_rule"

        const val SOURCE_MANUAL = "manual"
        const val SOURCE_SCHEDULE = "schedule"
        const val SOURCE_SLEEP = "sleep"
        const val SOURCE_ALARM = "alarm"

        private const val ACTION_EDGE = "com.android.systemui.circa.BEDTIME_EDGE"

        /** From WatchLink when its sleep tracker starts or ends a night; extra [EXTRA_ASLEEP]. */
        const val ACTION_SLEEP_STATE = "org.circa.watchlink.action.SLEEP_STATE"
        const val EXTRA_ASLEEP = "asleep"

        /** From Clock when an alarm is dismissed (not snoozed). */
        const val ACTION_ALARM_DISMISSED = "org.circa.clock.action.ALARM_DISMISSED"

        private const val WATCHLINK = "org.circa.watchlink"
        private const val CLOCK = "org.circa.clock"
        private val CONDITION_ID: Uri = Uri.parse("condition://com.android.systemui/circa_bedtime")
        private val SETTINGS_ACTIVITY =
            ComponentName("org.circa.settings", "org.circa.settings.MainActivity")

        fun isOn(context: Context): Boolean =
            Settings.Global.getInt(context.contentResolver, KEY_STATE, 0) != 0
    }
}
