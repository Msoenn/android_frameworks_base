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

/**
 * The decisions of {@link CircaWifiLinkedOff}, kept free of Android classes so they can be unit
 * tested on the host.
 */
public final class CircaWifiLinkPolicy {

    /** Wi-Fi only goes off once the screen has stayed off this long. */
    public static final long SCREEN_OFF_QUIET_MS = 2L * 60 * 1000;

    /** Never check again sooner than this. */
    public static final long MIN_RETRY_MS = 5 * 1000;

    /** {@code screenOffSince} while the screen is on. */
    public static final long SCREEN_ON = -1;

    private CircaWifiLinkPolicy() {}

    /**
     * Whether the countdown runs: Wi-Fi is on (connected or not), a bonded device holds a
     * Bluetooth LE connection to the watch, and Wi-Fi debugging is off.
     */
    public static boolean shouldArm(boolean wifiEnabled, boolean linked, boolean adbWifi) {
        return wifiEnabled && linked && !adbWifi;
    }

    /** Whether any of the bonded devices' LE connection handles is a real connection. */
    public static boolean anyLeLink(int[] leHandles, int noHandle) {
        for (int h : leHandles) {
            if (h != noHandle) {
                return true;
            }
        }
        return false;
    }

    /** {@code adb_wifi_enabled}: only 1 is on. */
    public static boolean adbWifiOn(String raw) {
        return raw != null && raw.trim().equals("1");
    }

    /**
     * When the countdown is over: 0 = turn Wi-Fi off now, otherwise check again in that many ms.
     * {@code screenOffSince} is the elapsed-realtime of the last screen-off, or {@link #SCREEN_ON}.
     */
    public static long waitBeforeOffMs(boolean interactive, long screenOffSince, long now) {
        if (interactive || screenOffSince < 0) {
            return SCREEN_OFF_QUIET_MS;
        }
        long off = Math.max(0, now - screenOffSince);
        if (off >= SCREEN_OFF_QUIET_MS) {
            return 0;
        }
        return Math.max(SCREEN_OFF_QUIET_MS - off, MIN_RETRY_MS);
    }
}
