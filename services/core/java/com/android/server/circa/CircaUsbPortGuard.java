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

import static android.hardware.usb.InternalUsbDataSignalDisableReason.USB_DISABLE_REASON_CIRCA;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.usb.IUsbManagerInternal;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.LocalServices;

import java.util.List;

/**
 * Applies Settings.Global {@code circa_usb_data} to the USB data signal: on, charging only, or
 * charging only when locked (see {@link CircaUsbPolicy} for the rules).
 *
 * <p>Uses the platform's own mechanism, {@link IUsbManagerInternal#enableUsbDataSignal} with a Circa
 * disable reason, the same way AOSP's UsbDataAdvancedProtectionHook does. UsbService keeps one
 * request per reason, so this does not undo a disable requested by anyone else. GrapheneOS goes
 * further on Pixels with a USB HAL extension that a generic system image cannot use: here a
 * connection made before locking keeps its data until it is unplugged.
 *
 * <p>A request that fails, or that the port status later contradicts, is sent again on the next
 * port change and after {@link #RETRY_DELAY_MS}, up to {@link #MAX_RETRIES} times.
 */
final class CircaUsbPortGuard {
    private static final String TAG = "CircaUsbPortGuard";

    private static final long RETRY_DELAY_MS = 10_000;
    private static final int MAX_RETRIES = 5;
    /** Lock-state callbacks can run before the trust state follows; look again after this long. */
    private static final long LOCK_STATE_SETTLE_MS = 2000;

    private final Context mContext;
    private final Handler mHandler;
    private final UsbManager mUsbManager;
    private final IUsbManagerInternal mUsbInternal;
    private final KeyguardManager mKeyguardManager;
    private final UserManager mUserManager;
    private final Runnable mVerify = () -> CircaSafe.run(TAG, "verify", this::verify);

    // All state is only touched on mHandler.
    /** Circa's disable request is in effect (the last request was a disable). */
    private boolean mDisabledByUs;
    /** The last request failed or is not confirmed yet; true before the first one. */
    private boolean mPending = true;
    private int mRetries;

    private CircaUsbPortGuard(Context context, UsbManager usbManager,
            IUsbManagerInternal usbInternal) {
        mContext = context;
        // A dedicated thread: USB HAL calls must not stall the shared background thread.
        HandlerThread ht = new HandlerThread(TAG);
        ht.start();
        mHandler = ht.getThreadHandler();
        mUsbManager = usbManager;
        mUsbInternal = usbInternal;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mUserManager = context.getSystemService(UserManager.class);
    }

    static void maybeInit(Context context) {
        IUsbManagerInternal usbInternal = LocalServices.getService(IUsbManagerInternal.class);
        UsbManager usbManager = context.getSystemService(UsbManager.class);
        if (usbInternal == null || usbManager == null) {
            Slog.w(TAG, "no USB service, USB data setting not applied");
            return;
        }
        List<UsbPort> ports = usbManager.getPorts();
        if (ports == null || ports.isEmpty()) {
            Slog.i(TAG, "no USB ports, USB data setting not applied");
            return;
        }
        new CircaUsbPortGuard(context, usbManager, usbInternal).init();
    }

    private void init() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CircaSettings.USB_DATA), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        evaluateSafely("setting", false);
                    }
                });
        mKeyguardManager.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                locked -> {
                    evaluateSafely("lock state", false);
                    mHandler.postDelayed(() -> evaluateSafely("lock state settled", false),
                            LOCK_STATE_SETTLE_MS);
                });
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluateSafely("port changed", true);
            }
        }, new IntentFilter(UsbManager.ACTION_USB_PORT_CHANGED), null, mHandler);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluateSafely("user unlocked", false);
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), null, mHandler);
        mHandler.post(() -> evaluateSafely("init", false));
    }

    private void evaluateSafely(String why, boolean portEvent) {
        CircaSafe.run(TAG, why, () -> evaluate(why, portEvent));
    }

    private void evaluate(String why, boolean portEvent) {
        int mode = CircaSettings.getUsbMode(mContext.getContentResolver());
        int user = ActivityManager.getCurrentUser();
        boolean locked = mKeyguardManager.isDeviceLocked(user);
        boolean unlockedSinceBoot = mUserManager.isUserUnlocked(user);
        boolean connected = anyPort(true);
        CircaUsbPolicy.Action action = CircaUsbPolicy.decide(
                mode, locked, unlockedSinceBoot, connected, mDisabledByUs);
        boolean apply = CircaUsbPolicy.shouldApply(action, mDisabledByUs, mPending, portEvent);
        Slog.i(TAG, why + ": mode=" + mode + " locked=" + locked
                + " unlockedSinceBoot=" + unlockedSinceBoot + " connected=" + connected
                + " disabledByUs=" + mDisabledByUs + " pending=" + mPending + " -> " + action
                + (apply ? "" : " (no change)"));
        if (apply) {
            if (action == CircaUsbPolicy.Action.ENABLE) {
                request(true, false);
            } else if (action == CircaUsbPolicy.Action.DISABLE) {
                request(false, false);
            }
        }
    }

    /** Sends the request to UsbService and schedules a check of the port status. */
    private void request(boolean enable, boolean retry) {
        if (!retry) {
            mRetries = 0;
        }
        boolean ok = false;
        try {
            ok = mUsbInternal.enableUsbDataSignal(enable, USB_DISABLE_REASON_CIRCA);
        } catch (Exception e) { // RemoteException or anything from the HAL path
            Slog.e(TAG, "enableUsbDataSignal(" + enable + ")", e);
        }
        // The request is staked in UsbService even if the HAL refused, so the direction is recorded
        // either way; mPending makes the next port change or the retry send it again.
        mDisabledByUs = !enable;
        mPending = !ok;
        Slog.i(TAG, "USB data " + (enable ? "allowed" : "blocked")
                + (ok ? "" : " (request failed)") + (retry ? " (retry " + mRetries + ")" : ""));
        mHandler.removeCallbacks(mVerify);
        mHandler.postDelayed(mVerify, RETRY_DELAY_MS);
    }

    /** Some time after a request: send it again if it failed or the port status says otherwise. */
    private void verify() {
        boolean wantEnabled = !mDisabledByUs;
        boolean mismatch = CircaUsbPolicy.statusMismatch(wantEnabled, anyPort(false));
        if (!mPending && !mismatch) {
            return;
        }
        if (mRetries >= MAX_RETRIES) {
            Slog.w(TAG, "USB data still not " + (wantEnabled ? "allowed" : "blocked") + " after "
                    + mRetries + " retries; trying again on the next port change");
            mPending = true;
            return;
        }
        mRetries++;
        request(wantEnabled, true);
    }

    /**
     * @param connected true: whether any port is connected; false: whether any port reports
     *     DATA_STATUS_DISABLED_FORCE
     */
    private boolean anyPort(boolean connected) {
        for (UsbPort port : mUsbManager.getPorts()) {
            UsbPortStatus status = port.getStatus();
            if (status == null) {
                continue;
            }
            if (connected ? status.isConnected()
                    : (status.getUsbDataStatus() & UsbPortStatus.DATA_STATUS_DISABLED_FORCE) != 0) {
                return true;
            }
        }
        return false;
    }
}
