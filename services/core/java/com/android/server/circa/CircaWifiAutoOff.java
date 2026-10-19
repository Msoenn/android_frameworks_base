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

import static android.net.wifi.WifiManager.WifiNetworkStateChangedListener.WIFI_ROLE_CLIENT_PRIMARY;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiNetworkStateChangedListener;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.util.Slog;

/**
 * Turns Wi-Fi off after it has been on for {@code circa_wifi_auto_off_ms} without a connection.
 *
 * <p>Based on GrapheneOS's com.android.server.ext.WifiAutoOff
 * (https://github.com/GrapheneOS/platform_frameworks_base, services/core/java/com/android/server/ext/
 * WifiAutoOff.java). Circa changes: the delay comes from a Settings.Global key with a 15 min
 * default; while the screen is on the action waits a minute and checks again, so Wi-Fi never
 * goes away under someone who is picking a network or typing its password; and the connection
 * state is read again from Wi-Fi right before turning it off.
 */
final class CircaWifiAutoOff extends DelayedConditionalAction
        implements WifiNetworkStateChangedListener {
    private static final String TAG = "CircaWifiAutoOff";

    /** While the screen is on, check again after this long instead of turning Wi-Fi off. */
    private static final long SCREEN_ON_RETRY_MS = 60 * 1000;

    private final WifiManager wifiManager;
    private final PowerManager powerManager;

    private int currentWifiState;
    private int currentWifiNetworkState;

    private CircaWifiAutoOff(Context context, Handler handler) {
        super(context, handler, CircaSettings.WIFI_AUTO_OFF_MS,
                CircaSettings.WIFI_AUTO_OFF_DEFAULT_MS, CircaSettings.WIFI_AUTO_OFF_MIN_MS);
        wifiManager = context.getSystemService(WifiManager.class);
        powerManager = context.getSystemService(PowerManager.class);
    }

    static void maybeInit(Context context, Handler handler) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            new CircaWifiAutoOff(context, handler).init();
        }
    }

    @Override
    protected boolean shouldScheduleAlarm() {
        return isEligibleForAutoOff();
    }

    @Override
    protected void alarmTriggered() {
        if (!isEligibleForAutoOff()) {
            return;
        }
        // The listener's view can lag (e.g. it registered while a connection came up at boot):
        // ask Wi-Fi itself before turning anything off.
        if (wifiManager.getCurrentNetwork() != null) {
            Slog.i(TAG, "Wi-Fi is connected after all, not turning it off");
            currentWifiNetworkState = WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTED;
            return;
        }
        if (powerManager.isInteractive()) {
            Slog.d(TAG, "screen is on, checking again in " + SCREEN_ON_RETRY_MS + " ms");
            retryIn(SCREEN_ON_RETRY_MS);
            return;
        }
        Slog.i(TAG, "no Wi-Fi connection for " + getDelayDurationMillis()
                + " ms, setWifiEnabled(false)");
        // setWifiEnabled() is not deprecated for system apps and components
        // noinspection deprecation
        wifiManager.setWifiEnabled(false);
    }

    private boolean isEligibleForAutoOff() {
        boolean res = currentWifiState == WifiManager.WIFI_STATE_ENABLED
                && !isConnectedOrConnectingNetworkState(currentWifiNetworkState);

        Slog.d(TAG, "isEligibleForAutoOff: " + res
                + "; wifiState: " + wifiStateToString(currentWifiState)
                + ", wifiNetworkState: " + wifiNetworkStateToString(currentWifiNetworkState));

        return res;
    }

    private static boolean isConnectedOrConnectingNetworkState(int state) {
        switch (state) {
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTING:
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_AUTHENTICATING:
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_OBTAINING_IPADDR:
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTED:
                return true;
            default:
                return false;
        }
    }

    /** @see WifiNetworkStateChangedListener */
    @Override
    public void onWifiNetworkStateChanged(int cmmRole, int state) {
        CircaSafe.run(TAG, "network state", () -> onNetworkState(cmmRole, state));
    }

    private void onNetworkState(int cmmRole, int state) {
        Slog.d(TAG, "onWifiNetworkStateChanged: cmmRole " + cmmRole
                + ", state " + wifiNetworkStateToString(state));
        if (cmmRole != WIFI_ROLE_CLIENT_PRIMARY) {
            return;
        }

        int prev = currentWifiNetworkState;
        currentWifiNetworkState = state;

        // Restart the countdown when the network goes away, and cancel it on connecting; the
        // in-between states (scanning, idle) of a disconnected adapter leave a running countdown alone.
        if (isConnectedOrConnectingNetworkState(state)
                || isConnectedOrConnectingNetworkState(prev)) {
            update();
        }
    }

    @Override
    protected void registerStateListener() {
        var filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        var receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                CircaSafe.run(TAG, "Wi-Fi state", () -> {
                    currentWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    Slog.d(TAG, "WIFI_STATE_CHANGED: " + wifiStateToString(currentWifiState));

                    update();
                });
            }
        };
        context.registerReceiver(receiver, filter, null, handler);
        currentWifiState = wifiManager.getWifiState();

        wifiManager.addWifiNetworkStateChangedListener(new HandlerExecutor(handler), this);
        currentWifiNetworkState = wifiManager.getCurrentNetwork() != null
                ? WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTED
                : WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_DISCONNECTED;
    }

    static String wifiStateToString(int state) {
        return switch (state) {
            case WifiManager.WIFI_STATE_DISABLING -> "DISABLING";
            case WifiManager.WIFI_STATE_DISABLED -> "DISABLED";
            case WifiManager.WIFI_STATE_ENABLING -> "ENABLING";
            case WifiManager.WIFI_STATE_ENABLED -> "ENABLED";
            case WifiManager.WIFI_STATE_UNKNOWN -> "UNKNOWN";
            default -> Integer.toString(state);
        };
    }

    static String wifiNetworkStateToString(int state) {
        return switch (state) {
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_IDLE -> "IDLE";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_SCANNING -> "SCANNING";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTING -> "CONNECTING";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_AUTHENTICATING ->
                    "AUTHENTICATING";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_OBTAINING_IPADDR ->
                    "OBTAINING_IPADDR";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_CONNECTED -> "CONNECTED";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_DISCONNECTED -> "DISCONNECTED";
            case WifiNetworkStateChangedListener.WIFI_NETWORK_STATUS_FAILED -> "FAILED";
            default -> Integer.toString(state);
        };
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
