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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;

import java.util.Set;

/**
 * Turns Wi-Fi off, connected or not, once the phone link has been up for
 * {@code circa_wifi_off_when_linked_ms} (default 10 min) and the screen has then stayed off for
 * {@link CircaWifiLinkPolicy#SCREEN_OFF_QUIET_MS}, as stock Wear OS does. The phone reaches the
 * watch over Bluetooth LE, so Wi-Fi only costs battery while the link is up.
 *
 * <p>"Linked" means a bonded device holds an LE connection to the watch (the phone app connects to
 * WatchLink's GATT server). It is read from the Bluetooth stack itself whenever the protected ACL,
 * bond or adapter-state broadcasts say something changed, and once at start, so no app can fake it
 * and a link that came up before this action started still counts.
 *
 * <p>Turning Wi-Fi on restarts the countdown, so Wi-Fi turned on by hand stays on for at least the
 * full delay. Nothing happens while Wi-Fi debugging is on. Wi-Fi is not turned back on when the
 * link drops: Circa has no other way to reach the phone, and {@link CircaWifiAutoOff} would turn
 * an unconnected Wi-Fi off again.
 */
final class CircaWifiLinkedOff extends DelayedConditionalAction {
    private static final String TAG = "CircaWifiLinkedOff";

    private final WifiManager wifiManager;
    private final PowerManager powerManager;
    private final BluetoothManager bluetoothManager;

    private boolean wifiEnabled;
    private boolean linked;
    private boolean adbWifi;
    private long screenOffSince = CircaWifiLinkPolicy.SCREEN_ON;
    /** The last {@link #shouldScheduleAlarm()} result, so unrelated events don't restart the countdown. */
    private boolean armed;

    private CircaWifiLinkedOff(Context context, Handler handler) {
        super(context, handler, CircaSettings.WIFI_OFF_WHEN_LINKED_MS,
                CircaSettings.WIFI_OFF_WHEN_LINKED_DEFAULT_MS,
                CircaSettings.WIFI_OFF_WHEN_LINKED_MIN_MS);
        wifiManager = context.getSystemService(WifiManager.class);
        powerManager = context.getSystemService(PowerManager.class);
        bluetoothManager = context.getSystemService(BluetoothManager.class);
    }

    static void maybeInit(Context context, Handler handler) {
        PackageManager pm = context.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            new CircaWifiLinkedOff(context, handler).init();
        }
    }

    @Override
    protected boolean shouldScheduleAlarm() {
        armed = CircaWifiLinkPolicy.shouldArm(wifiEnabled, linked, adbWifi);
        Slog.d(TAG, "shouldScheduleAlarm: " + armed + "; wifiEnabled: " + wifiEnabled
                + ", linked: " + linked + ", adbWifi: " + adbWifi);
        return armed;
    }

    /** Restarts the countdown only when the outcome changes. */
    private void updateIfChanged() {
        if (CircaWifiLinkPolicy.shouldArm(wifiEnabled, linked, adbWifi) != armed) {
            update();
        }
    }

    @Override
    protected void alarmTriggered() {
        // The broadcasts can lag: ask the stacks again before turning anything off.
        wifiEnabled = wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        linked = readLinked();
        adbWifi = readAdbWifi();
        if (!CircaWifiLinkPolicy.shouldArm(wifiEnabled, linked, adbWifi)) {
            Slog.i(TAG, "conditions no longer hold, not turning Wi-Fi off");
            update();
            return;
        }
        long wait = CircaWifiLinkPolicy.waitBeforeOffMs(powerManager.isInteractive(),
                screenOffSince, SystemClock.elapsedRealtime());
        if (wait > 0) {
            Slog.d(TAG, "screen on or recently on, checking again in " + wait + " ms");
            retryIn(wait);
            return;
        }
        Slog.i(TAG, "phone linked for " + getDelayDurationMillis()
                + " ms and screen off, setWifiEnabled(false)");
        // setWifiEnabled() is not deprecated for system apps and components
        // noinspection deprecation
        wifiManager.setWifiEnabled(false);
    }

    /**
     * Whether a bonded device has an LE connection, from the Bluetooth stack. Binder calls into
     * the Bluetooth process: runs on the background handler, never on the system_server main
     * thread. False while Bluetooth is off or unreachable.
     */
    private boolean readLinked() {
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON) {
            return false;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            return false;
        }
        int[] handles = new int[bonded.size()];
        int i = 0;
        for (BluetoothDevice d : bonded) {
            handles[i++] = d.getConnectionHandle(BluetoothDevice.TRANSPORT_LE);
        }
        return CircaWifiLinkPolicy.anyLeLink(handles, BluetoothDevice.ERROR);
    }

    private boolean readAdbWifi() {
        return CircaWifiLinkPolicy.adbWifiOn(Settings.Global.getString(
                context.getContentResolver(), Settings.Global.ADB_WIFI_ENABLED));
    }

    private void refreshLinked(String why) {
        boolean now = readLinked();
        if (now != linked) {
            Slog.i(TAG, "phone link " + (now ? "up" : "down") + " (" + why + ")");
            linked = now;
        }
        updateIfChanged();
    }

    @Override
    protected void registerStateListener() {
        var wifiFilter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                CircaSafe.run(TAG, "Wi-Fi state", () -> {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    boolean was = wifiEnabled;
                    wifiEnabled = state == WifiManager.WIFI_STATE_ENABLED;
                    Slog.d(TAG, "WIFI_STATE_CHANGED: " + CircaWifiAutoOff.wifiStateToString(state));
                    if (wifiEnabled && !was) {
                        // Wi-Fi was just turned on (by hand, as only system components can):
                        // the full delay starts again.
                        update();
                    } else {
                        updateIfChanged();
                    }
                });
            }
        }, wifiFilter, null, handler);

        var btFilter = new IntentFilter();
        btFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        btFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        btFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        btFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                CircaSafe.run(TAG, "Bluetooth", () -> {
                    String action = intent.getAction();
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                            && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                                    != BluetoothAdapter.STATE_ON) {
                        if (linked) {
                            Slog.i(TAG, "phone link down (Bluetooth not on)");
                        }
                        linked = false;
                        updateIfChanged();
                        return;
                    }
                    // ACL, bond or Bluetooth on: read the whole state again, which also seeds
                    // links that came up while Bluetooth was starting.
                    refreshLinked(action);
                });
            }
        }, btFilter, null, handler);

        var screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                CircaSafe.run(TAG, "screen", () -> {
                    screenOffSince = Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                            ? SystemClock.elapsedRealtime() : CircaWifiLinkPolicy.SCREEN_ON;
                });
            }
        }, screenFilter, null, handler);

        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_WIFI_ENABLED), false,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        CircaSafe.run(TAG, "adb Wi-Fi", () -> {
                            adbWifi = readAdbWifi();
                            updateIfChanged();
                        });
                    }
                });

        // Seed the state: the link may already be up, and no ACL broadcast will say so.
        wifiEnabled = wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
        adbWifi = readAdbWifi();
        screenOffSince = powerManager.isInteractive()
                ? CircaWifiLinkPolicy.SCREEN_ON : SystemClock.elapsedRealtime();
        try {
            linked = readLinked();
        } catch (RuntimeException e) {
            // Bluetooth not reachable yet: its STATE_ON broadcast reads the state again.
            Slog.w(TAG, "could not read the phone link at start", e);
            linked = false;
        }
        Slog.i(TAG, "start: wifiEnabled " + wifiEnabled + ", linked " + linked
                + ", adbWifi " + adbWifi);
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
