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

import static com.android.server.circa.CircaWifiLinkPolicy.MIN_RETRY_MS;
import static com.android.server.circa.CircaWifiLinkPolicy.SCREEN_OFF_QUIET_MS;
import static com.android.server.circa.CircaWifiLinkPolicy.SCREEN_ON;
import static com.android.server.circa.CircaWifiLinkPolicy.adbWifiOn;
import static com.android.server.circa.CircaWifiLinkPolicy.anyLeLink;
import static com.android.server.circa.CircaWifiLinkPolicy.shouldArm;
import static com.android.server.circa.CircaWifiLinkPolicy.waitBeforeOffMs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CircaWifiLinkPolicyTest {
    /** BluetoothDevice.ERROR, the "no connection" handle. */
    private static final int NO_HANDLE = Integer.MIN_VALUE;

    @Test
    public void arms_onlyWithWifiOnAndLinkUpAndNoAdbWifi() {
        assertTrue(shouldArm(true, true, false));
        assertFalse(shouldArm(false, true, false));
        assertFalse(shouldArm(true, false, false));
        assertFalse(shouldArm(true, true, true));
    }

    @Test
    public void link_anyBondedDeviceWithAnLeHandle() {
        assertFalse(anyLeLink(new int[0], NO_HANDLE));
        assertFalse(anyLeLink(new int[] {NO_HANDLE, NO_HANDLE}, NO_HANDLE));
        assertTrue(anyLeLink(new int[] {NO_HANDLE, 64}, NO_HANDLE));
        assertTrue(anyLeLink(new int[] {0}, NO_HANDLE));
    }

    @Test
    public void adbWifi_onlyOneIsOn() {
        assertFalse(adbWifiOn(null));
        assertFalse(adbWifiOn("0"));
        assertFalse(adbWifiOn("true"));
        assertTrue(adbWifiOn("1"));
        assertTrue(adbWifiOn(" 1 "));
    }

    @Test
    public void wait_screenOnWaitsTheFullQuietTime() {
        assertEquals(SCREEN_OFF_QUIET_MS, waitBeforeOffMs(true, SCREEN_ON, 1_000_000));
        // Interactive wins over a stale screen-off time.
        assertEquals(SCREEN_OFF_QUIET_MS, waitBeforeOffMs(true, 0, 1_000_000));
        // Not interactive but no screen-off seen yet: treat as just turned off.
        assertEquals(SCREEN_OFF_QUIET_MS, waitBeforeOffMs(false, SCREEN_ON, 1_000_000));
    }

    @Test
    public void wait_screenOffBrieflyWaitsTheRest() {
        long now = 1_000_000;
        assertEquals(SCREEN_OFF_QUIET_MS - 30_000, waitBeforeOffMs(false, now - 30_000, now));
        assertEquals(MIN_RETRY_MS, waitBeforeOffMs(false, now - SCREEN_OFF_QUIET_MS + 1, now));
        // A screen-off time in the future (clock oddity) counts as just now.
        assertEquals(SCREEN_OFF_QUIET_MS, waitBeforeOffMs(false, now + 5_000, now));
    }

    @Test
    public void wait_screenOffLongEnoughTurnsOffNow() {
        long now = 1_000_000;
        assertEquals(0, waitBeforeOffMs(false, now - SCREEN_OFF_QUIET_MS, now));
        assertEquals(0, waitBeforeOffMs(false, 0, now));
    }
}
