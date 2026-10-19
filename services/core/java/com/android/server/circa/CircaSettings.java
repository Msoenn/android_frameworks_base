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

import android.content.ContentResolver;
import android.provider.Settings;

/**
 * The Settings.Global keys of the Circa system_server extension, with their defaults. An unset key
 * means the default below, so flipping a default is a one-line change here. Circa Settings writes
 * the keys; they can also be set with {@code adb shell settings put global <key> <value>}.
 */
public final class CircaSettings {

    /**
     * Restart after the device has stayed locked this long (ms) since it was last unlocked; 0 = off.
     * Only armed when a PIN is set and the user's storage has been unlocked since boot.
     */
    public static final String AUTO_REBOOT_MS = "circa_auto_reboot_ms";
    public static final long AUTO_REBOOT_DEFAULT_MS = 18L * 60 * 60 * 1000;
    public static final long AUTO_REBOOT_MIN_MS = 20L * 1000;

    /** Turn Wi-Fi off after it has been on without a connection this long (ms); 0 = off. */
    public static final String WIFI_AUTO_OFF_MS = "circa_wifi_auto_off_ms";
    public static final long WIFI_AUTO_OFF_DEFAULT_MS = 15L * 60 * 1000;
    public static final long WIFI_AUTO_OFF_MIN_MS = 60L * 1000;

    /**
     * USB data signalling of the charging port: {@link CircaUsbPolicy#MODE_ON},
     * {@link CircaUsbPolicy#MODE_CHARGING_ONLY} or {@link CircaUsbPolicy#MODE_CHARGING_ONLY_WHEN_LOCKED}.
     * Values 1 and 0 keep the meaning of the original on/off toggle.
     */
    public static final String USB_DATA = "circa_usb_data";
    public static final int USB_DATA_DEFAULT = CircaUsbPolicy.MODE_ON;

    private CircaSettings() {}

    /** A duration setting, see {@link CircaTiming#parseDurationMs}. */
    static long getDurationMs(ContentResolver cr, String key, long def, long min) {
        return CircaTiming.parseDurationMs(Settings.Global.getString(cr, key), def, min);
    }

    static int getUsbMode(ContentResolver cr) {
        String raw = Settings.Global.getString(cr, USB_DATA);
        return CircaUsbPolicy.parseMode(raw, USB_DATA_DEFAULT);
    }
}
