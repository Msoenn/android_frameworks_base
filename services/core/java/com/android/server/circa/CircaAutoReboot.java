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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.widget.LockPatternUtils;

import java.util.List;

/**
 * Restarts the device after it has stayed locked for {@code circa_auto_reboot_ms} (default 18 h),
 * which puts the user's data back at rest (before first unlock: credential-encrypted storage
 * locked, keys evicted).
 *
 * <p>Same behaviour as GrapheneOS's auto-reboot (frameworks/base 404b6c1 "add auto-reboot feature"
 * and system/core 64779a0): the countdown starts when the device locks, an unlock cancels it, a
 * countdown that is already running is not restarted, and nothing is armed before the first unlock
 * after boot. GrapheneOS keeps the timer in init; here it is an exact ELAPSED_REALTIME_WAKEUP alarm
 * set by system_server, which fires in deep Doze (core-uid alarms are allowed while idle). After a
 * system_server restart the next lock, or the user-unlocked broadcast, arms a fresh countdown.
 *
 * <p>"Locked" is {@link KeyguardManager#isDeviceLocked(int)}: a PIN is set and it has not been
 * entered. On the watch the face and apps may run over a locked keyguard, so a due restart waits
 * while the screen is on or a workout is recording, and checks again every few minutes.
 */
final class CircaAutoReboot {
    private static final String TAG = "CircaAutoReboot";

    /** When the restart is due but the watch is in use, check again after this long. */
    private static final long IN_USE_RETRY_MS = 5 * 60 * 1000;
    /** Lock-state callbacks can run before the trust state follows; look again after this long. */
    private static final long LOCK_STATE_SETTLE_MS = 2000;

    /** The Exercise app's workout service; it is a foreground service only while recording. */
    private static final String EXERCISE_PACKAGE = "org.circa.exercise";
    private static final String EXERCISE_SERVICE = "org.circa.exercise.data.ExerciseService";

    private final Context mContext;
    private final Handler mHandler;
    private final AlarmManager mAlarmManager;
    private final KeyguardManager mKeyguardManager;
    private final UserManager mUserManager;
    private final PowerManager mPowerManager;
    private final ActivityManager mActivityManager;
    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager.OnAlarmListener mAlarmListener =
            () -> CircaSafe.run(TAG, "alarm", this::onAlarm);

    /** Elapsed-realtime deadline of the running countdown, 0 = none. Only on {@link #mHandler}. */
    private long mDeadline;

    private CircaAutoReboot(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        mAlarmManager = context.getSystemService(AlarmManager.class);
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mUserManager = context.getSystemService(UserManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
        mLockPatternUtils = new LockPatternUtils(context);
    }

    static void init(Context context, Handler handler) {
        new CircaAutoReboot(context, handler).start();
    }

    private void start() {
        mKeyguardManager.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                locked -> {
                    evaluateSafely("lock state");
                    mHandler.postDelayed(() -> evaluateSafely("lock state settled"),
                            LOCK_STATE_SETTLE_MS);
                });
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CircaSettings.AUTO_REBOOT_MS), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        // A new timeout applies from now; off cancels.
                        CircaSafe.run(TAG, "setting", () -> {
                            disarm("setting changed");
                            evaluate("setting changed");
                        });
                    }
                });
        // After a system_server restart the user's storage is unlocked again a little after boot.
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluateSafely("user unlocked");
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, mHandler);
        mHandler.post(() -> evaluateSafely("init"));
    }

    private void evaluateSafely(String why) {
        CircaSafe.run(TAG, why, () -> evaluate(why));
    }

    private long timeoutMs() {
        return CircaSettings.getDurationMs(mContext.getContentResolver(),
                CircaSettings.AUTO_REBOOT_MS, CircaSettings.AUTO_REBOOT_DEFAULT_MS,
                CircaSettings.AUTO_REBOOT_MIN_MS);
    }

    private boolean isDeviceLocked() {
        return mKeyguardManager.isDeviceLocked(ActivityManager.getCurrentUser());
    }

    private void evaluate(String why) {
        if (!isDeviceLocked()) {
            disarm(why + ", unlocked");
            return;
        }
        if (mDeadline != 0) {
            return; // a running countdown is not restarted
        }
        long timeout = timeoutMs();
        int user = ActivityManager.getCurrentUser();
        if (timeout == 0) {
            Slog.d(TAG, why + ": locked, auto-reboot off");
            return;
        }
        if (!mLockPatternUtils.isSecure(user)) {
            Slog.d(TAG, why + ": no PIN set, not armed");
            return;
        }
        if (!mUserManager.isUserUnlocked(user)) {
            Slog.i(TAG, why + ": locked, not unlocked since boot, not armed");
            return;
        }
        schedule(timeout);
        Slog.i(TAG, why + ": locked, rebooting in " + timeout + " ms unless unlocked");
    }

    private void schedule(long delayMs) {
        mDeadline = CircaTiming.deadline(SystemClock.elapsedRealtime(), delayMs);
        mAlarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, mDeadline, TAG,
                mAlarmListener, mHandler);
    }

    private void disarm(String why) {
        if (mDeadline != 0) {
            mAlarmManager.cancel(mAlarmListener);
            mDeadline = 0;
            Slog.i(TAG, why + ": countdown cancelled");
        }
    }

    private void onAlarm() {
        long now = SystemClock.elapsedRealtime();
        if (mDeadline == 0 || !CircaTiming.isDue(now, mDeadline)) {
            Slog.d(TAG, "stale alarm ignored (deadline " + mDeadline + ", now " + now + ")");
            return;
        }
        if (!isDeviceLocked() || timeoutMs() == 0) {
            mDeadline = 0;
            Slog.i(TAG, "alarm, but unlocked or turned off: no reboot");
            return;
        }
        String busy = mPowerManager.isInteractive() ? "screen on"
                : isWorkoutRecording() ? "workout recording" : null;
        if (busy != null) {
            schedule(IN_USE_RETRY_MS);
            Slog.i(TAG, "reboot due, but " + busy + ": checking again in " + IN_USE_RETRY_MS + " ms");
            return;
        }
        mDeadline = 0;
        Slog.i(TAG, "locked for " + timeoutMs() + " ms: rebooting");
        mPowerManager.reboot(null);
    }

    /** The Exercise app's workout service runs in the foreground (only while recording). */
    @SuppressWarnings("deprecation") // getRunningServices still lists every service for system_server
    private boolean isWorkoutRecording() {
        List<ActivityManager.RunningServiceInfo> services =
                mActivityManager.getRunningServices(Integer.MAX_VALUE);
        if (services == null) {
            return false;
        }
        for (ActivityManager.RunningServiceInfo s : services) {
            if (s.foreground && s.service != null
                    && EXERCISE_PACKAGE.equals(s.service.getPackageName())
                    && EXERCISE_SERVICE.equals(s.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
