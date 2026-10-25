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

import android.content.Context;
import android.os.Handler;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

/**
 * Circa's security features in system_server: auto-reboot after a long lock, Wi-Fi auto-off
 * (unused, and while the phone is linked), the USB data modes and the one-shot privacy defaults migration. Their settings are in
 * {@link CircaSettings}.
 */
public final class CircaSystemServerExt {
    private static final String TAG = "CircaSystemServerExt";

    private CircaSystemServerExt() {}

    /**
     * Called once from SystemServer when the system is ready, before third-party apps start
     * (the place GrapheneOS starts its SystemServerExt).
     */
    public static void init(Context systemContext) {
        Handler bg = BackgroundThread.getHandler();
        bg.post(() -> {
            start("privacy defaults",
                    () -> CircaPrivacyDefaults.migrate(systemContext.getContentResolver()));
            start("auto-reboot", () -> CircaAutoReboot.init(systemContext, bg));
            start("Wi-Fi auto-off", () -> CircaWifiAutoOff.maybeInit(systemContext, bg));
            start("Wi-Fi off when linked",
                    () -> CircaWifiLinkedOff.maybeInit(systemContext, bg));
            start("USB guard", () -> CircaUsbPortGuard.maybeInit(systemContext));
        });
    }

    /** A failing feature logs and stays off; it never takes system_server down. */
    private static void start(String name, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Slog.wtf(TAG, "could not start " + name, e);
        }
    }
}
