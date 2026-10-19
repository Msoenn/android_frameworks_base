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

package com.android.server.circa;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.widget.LockPatternUtils;

/**
 * Restarts the device after it has stayed locked for {@code circa_auto_reboot_ms} (default 18 h),
 * which puts the user's data back at rest (before first unlock: credential-encrypted storage
 * locked, keys evicted).
 *
 * <p>Same behaviour as GrapheneOS's auto-reboot (frameworks/base 404b6c1 "add auto-reboot feature"
 * and system/core 64779a0): the countdown starts when the device locks, an unlock cancels it, a
 * countdown that is already running is not restarted, and nothing is armed before the first unlock
 * after boot. GrapheneOS keeps the timer in init; here it is an exact ELAPSED_REALTIME_WAKEUP alarm
 * set by system_server, which fires in deep Doze (core-uid alarms are allowed while idle). If
 * system_server restarts, the next lock arms a fresh countdown (storage is still unlocked).
 */
final class CircaAutoReboot {
    private static final String TAG = "CircaAutoReboot";

    private final Context mContext;
    private final Handler mHandler;
    private final AlarmManager mAlarmManager;
    private final KeyguardManager mKeyguardManager;
    private final UserManager mUserManager;
    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager.OnAlarmListener mAlarmListener = this::onAlarm;

    /** Only touched on {@link #mHandler}. */
    private boolean mArmed;

    private CircaAutoReboot(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mUserManager = context.getSystemService(UserManager.class);
        mLockPatternUtils = new LockPatternUtils(context);
    }

    static void init(Context context, Handler handler) {
        new CircaAutoReboot(context, handler).start();
    }

    private void start() {
        mKeyguardManager.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                this::onLockedChanged);
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CircaSettings.AUTO_REBOOT_MS), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // A new timeout applies from now; off cancels.
                        disarm("setting changed");
                        onLockedChanged(mKeyguardManager.isKeyguardLocked());
                    }
                });
        mHandler.post(() -> onLockedChanged(mKeyguardManager.isKeyguardLocked()));
    }

    private long timeoutMs() {
        return CircaSettings.getDurationMs(mContext.getContentResolver(),
                CircaSettings.AUTO_REBOOT_MS, CircaSettings.AUTO_REBOOT_DEFAULT_MS,
                CircaSettings.AUTO_REBOOT_MIN_MS);
    }

    private void onLockedChanged(boolean locked) {
        if (!locked) {
            disarm("unlocked");
            return;
        }
        if (mArmed) {
            Slog.d(TAG, "locked, countdown already running");
            return;
        }
        long timeout = timeoutMs();
        int user = ActivityManager.getCurrentUser();
        if (timeout == 0) {
            Slog.d(TAG, "locked, auto-reboot off");
            return;
        }
        if (!mLockPatternUtils.isSecure(user)) {
            Slog.d(TAG, "locked, but no PIN set: not armed");
            return;
        }
        if (!mUserManager.isUserUnlocked(user)) {
            Slog.i(TAG, "locked, not unlocked since boot: not armed");
            return;
        }
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + timeout, TAG, mAlarmListener, mHandler);
        mArmed = true;
        Slog.i(TAG, "locked: rebooting in " + timeout + " ms unless unlocked");
    }

    private void disarm(String why) {
        if (mArmed) {
            mAlarmManager.cancel(mAlarmListener);
            mArmed = false;
            Slog.i(TAG, why + ": countdown cancelled");
        }
    }

    private void onAlarm() {
        mArmed = false;
        if (!mKeyguardManager.isKeyguardLocked() || timeoutMs() == 0) {
            Slog.i(TAG, "alarm, but unlocked or turned off: no reboot");
            return;
        }
        Slog.i(TAG, "locked for " + timeoutMs() + " ms: rebooting");
        mContext.getSystemService(PowerManager.class).reboot(null);
    }
}
