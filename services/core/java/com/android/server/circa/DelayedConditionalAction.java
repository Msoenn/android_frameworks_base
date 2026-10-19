/*
 * Copyright (C) 2026 The Circa Project
 * Copyright (C) The GrapheneOS Project
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

package com.android.server.circa;

import android.app.AlarmManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

/**
 * Infrastructure for actions that:
 * - happen after a user-configurable device-wide delay
 * - need to be taken even when the device is in deep sleep
 * - need to be rescheduled based on some listenable event
 *
 * <p>Based on GrapheneOS's com.android.server.ext.DelayedConditionalAction
 * (https://github.com/GrapheneOS/platform_frameworks_base, services/core/java/com/android/server/ext/
 * DelayedConditionalAction.java), with the delay read from a Settings.Global key instead of
 * GrapheneOS's ExtSettings, and a way to retry a fired action later.
 *
 * <p>The alarm is an exact ELAPSED_REALTIME_WAKEUP alarm set by system_server: AlarmManagerService
 * gives core-uid alarms FLAG_ALLOW_WHILE_IDLE_UNRESTRICTED, so it fires on time in deep Doze.
 */
abstract class DelayedConditionalAction {
    protected final Context context;
    protected final Thread thread;
    protected final Handler handler;
    protected final AlarmManager alarmManager;

    private final String settingKey;
    private final long defaultDelayMs;
    private final long minDelayMs;
    private final AlarmManager.OnAlarmListener alarmListener;
    private boolean alarmScheduled;

    protected DelayedConditionalAction(Context context, Handler handler, String settingKey,
            long defaultDelayMs, long minDelayMs) {
        this.context = context;
        this.handler = handler;
        this.thread = handler.getLooper().getThread();
        this.settingKey = settingKey;
        this.defaultDelayMs = defaultDelayMs;
        this.minDelayMs = minDelayMs;

        if (thread != Thread.currentThread()) {
            throw new IllegalStateException("all calls should happen on the same thread");
        }

        alarmManager = context.getSystemService(AlarmManager.class);
        alarmListener = () -> {
            alarmScheduled = false;
            Slog.d(getLogTag(), "alarm triggered");

            if (getDelayDurationMillis() == 0) {
                Slog.d(getLogTag(), "alarm has been disabled, returning");
                return;
            }

            alarmTriggered();
        };
    }

    public void init() {
        registerStateListener();

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(settingKey), false, new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        update();
                    }
                });

        update();
    }

    /** Cancels any scheduled alarm and schedules a new one for the full delay if the condition holds. */
    protected final void update() {
        checkThread();
        cancelAlarm();

        boolean shouldScheduleAlarm = shouldScheduleAlarm();
        Slog.d(getLogTag(), "shouldScheduleAlarm: " + shouldScheduleAlarm);
        if (!shouldScheduleAlarm) {
            return;
        }

        long delayMillis = getDelayDurationMillis();
        Slog.d(getLogTag(), "delayMillis: " + delayMillis);
        if (delayMillis == 0) {
            return;
        }
        scheduleAlarm(delayMillis);
    }

    /** Schedules the alarm again {@code delayMillis} from now, e.g. when the action had to wait. */
    protected final void retryIn(long delayMillis) {
        checkThread();
        cancelAlarm();
        scheduleAlarm(delayMillis);
    }

    private void scheduleAlarm(long delayMillis) {
        long current = SystemClock.elapsedRealtime();
        if (Long.MAX_VALUE - delayMillis < current) {
            return;
        }
        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, current + delayMillis,
                getClass().getName(), alarmListener, handler);
        alarmScheduled = true;
        Slog.d(getLogTag(), "scheduled alarm in " + delayMillis + " ms");
    }

    private void cancelAlarm() {
        if (alarmScheduled) {
            alarmManager.cancel(alarmListener);
            alarmScheduled = false;
            Slog.d(getLogTag(), "canceled previous alarm");
        }
    }

    private void checkThread() {
        Thread curThread = Thread.currentThread();
        if (curThread != thread) {
            throw new IllegalStateException("update() called on an unknown thread " + curThread);
        }
    }

    protected final long getDelayDurationMillis() {
        return CircaSettings.getDurationMs(context.getContentResolver(), settingKey,
                defaultDelayMs, minDelayMs);
    }

    // Make sure to use the same Handler that is used for all other callbacks;
    // call update() to reschedule / cancel the alarm
    protected abstract void registerStateListener();

    protected abstract boolean shouldScheduleAlarm();

    protected abstract void alarmTriggered();

    protected abstract String getLogTag();
}
