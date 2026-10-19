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
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.LocalServices;

/**
 * Applies Settings.Global {@code circa_usb_data} to the USB data signal: on, charging only, or
 * charging only when locked (see {@link CircaUsbPolicy} for the rules).
 *
 * <p>Uses the platform's own mechanism, {@link IUsbManagerInternal#enableUsbDataSignal} with a Circa
 * disable reason, the same way AOSP's UsbDataAdvancedProtectionHook does. UsbService keeps one
 * request per reason, so this does not undo a disable requested by anyone else. GrapheneOS goes
 * further on Pixels with a USB HAL extension that a generic system image cannot use: here a
 * connection made before locking keeps its data until it is unplugged.
 */
final class CircaUsbPortGuard {
    private static final String TAG = "CircaUsbPortGuard";

    private final Context mContext;
    private final Handler mHandler;
    private final UsbManager mUsbManager;
    private final IUsbManagerInternal mUsbInternal;
    private final KeyguardManager mKeyguardManager;
    private final UserManager mUserManager;

    /** Circa's disable request is in effect. Only touched on {@link #mHandler}. */
    private boolean mDisabledByUs;
    private boolean mFirstApply = true;

    private CircaUsbPortGuard(Context context, IUsbManagerInternal usbInternal) {
        mContext = context;
        // A dedicated thread: USB HAL calls must not stall the shared background thread.
        HandlerThread ht = new HandlerThread(TAG);
        ht.start();
        mHandler = ht.getThreadHandler();
        mUsbManager = context.getSystemService(UsbManager.class);
        mUsbInternal = usbInternal;
        mKeyguardManager = context.getSystemService(KeyguardManager.class);
        mUserManager = context.getSystemService(UserManager.class);
    }

    static void maybeInit(Context context) {
        IUsbManagerInternal usbInternal = LocalServices.getService(IUsbManagerInternal.class);
        if (usbInternal == null || context.getSystemService(UsbManager.class) == null) {
            Slog.w(TAG, "no USB service, USB data setting not applied");
            return;
        }
        new CircaUsbPortGuard(context, usbInternal).init();
    }

    private void init() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CircaSettings.USB_DATA), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        evaluate("setting");
                    }
                });
        mKeyguardManager.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                locked -> evaluate(locked ? "locked" : "unlocked"));
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                evaluate("port changed");
            }
        }, new IntentFilter(UsbManager.ACTION_USB_PORT_CHANGED), null, mHandler);
        mHandler.post(() -> evaluate("init"));
    }

    private boolean anyPortConnected() {
        for (UsbPort port : mUsbManager.getPorts()) {
            UsbPortStatus status = port.getStatus();
            if (status != null && status.isConnected()) {
                return true;
            }
        }
        return false;
    }

    private void evaluate(String why) {
        int mode = CircaSettings.getUsbMode(mContext.getContentResolver());
        boolean locked = mKeyguardManager.isKeyguardLocked();
        boolean unlockedSinceBoot = mUserManager.isUserUnlocked(ActivityManager.getCurrentUser());
        boolean connected = anyPortConnected();
        CircaUsbPolicy.Action action = CircaUsbPolicy.decide(
                mode, locked, unlockedSinceBoot, connected, mDisabledByUs);
        Slog.i(TAG, why + ": mode=" + mode + " locked=" + locked
                + " unlockedSinceBoot=" + unlockedSinceBoot + " connected=" + connected
                + " disabledByUs=" + mDisabledByUs + " -> " + action);
        switch (action) {
            case ENABLE:
                // The first time, release unconditionally: a restarted system_server cannot know
                // what the previous instance left in the HAL.
                if (mDisabledByUs || mFirstApply) {
                    setDataSignal(true);
                }
                break;
            case DISABLE:
                if (!mDisabledByUs || mFirstApply) {
                    setDataSignal(false);
                }
                break;
            case KEEP:
                break;
        }
        mFirstApply = false;
    }

    private void setDataSignal(boolean enable) {
        boolean ok = false;
        // UsbDataAdvancedProtectionHook retries too: the HAL call can fail transiently.
        for (int attempt = 0; attempt < 3 && !ok; attempt++) {
            try {
                ok = mUsbInternal.enableUsbDataSignal(enable, USB_DISABLE_REASON_CIRCA);
            } catch (RemoteException e) {
                Slog.e(TAG, "enableUsbDataSignal(" + enable + ")", e);
            }
        }
        // Record the request even if the HAL refused: the request itself is staked in UsbService
        // and a later enable must release it.
        mDisabledByUs = !enable;
        Slog.i(TAG, "USB data " + (enable ? "allowed" : "blocked") + (ok ? "" : " (HAL call failed)")
                + ", ports: " + mUsbManager.getPorts().size());
    }
}
