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

import static com.android.server.circa.CircaUsbPolicy.Action.DISABLE;
import static com.android.server.circa.CircaUsbPolicy.Action.ENABLE;
import static com.android.server.circa.CircaUsbPolicy.Action.KEEP;
import static com.android.server.circa.CircaUsbPolicy.MODE_CHARGING_ONLY;
import static com.android.server.circa.CircaUsbPolicy.MODE_CHARGING_ONLY_WHEN_LOCKED;
import static com.android.server.circa.CircaUsbPolicy.MODE_ON;
import static com.android.server.circa.CircaUsbPolicy.decide;
import static com.android.server.circa.CircaUsbPolicy.shouldApply;
import static com.android.server.circa.CircaUsbPolicy.statusMismatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CircaUsbPolicyTest {

    private static final int WHEN_LOCKED = MODE_CHARGING_ONLY_WHEN_LOCKED;

    @Test
    public void parseMode_keepsTheOriginalToggleValues() {
        assertEquals(MODE_ON, CircaUsbPolicy.parseMode(null, MODE_ON));
        assertEquals(MODE_ON, CircaUsbPolicy.parseMode("1", MODE_ON));
        assertEquals(MODE_CHARGING_ONLY, CircaUsbPolicy.parseMode("0", MODE_ON));
        assertEquals(WHEN_LOCKED, CircaUsbPolicy.parseMode(" 2 ", MODE_ON));
        assertEquals(MODE_ON, CircaUsbPolicy.parseMode("7", MODE_ON));
        assertEquals(MODE_ON, CircaUsbPolicy.parseMode("on", MODE_ON));
    }

    @Test
    public void on_alwaysEnables() {
        for (boolean locked : new boolean[] {false, true}) {
            for (boolean afu : new boolean[] {false, true}) {
                for (boolean conn : new boolean[] {false, true}) {
                    for (boolean dis : new boolean[] {false, true}) {
                        assertEquals(ENABLE, decide(MODE_ON, locked, afu, conn, dis));
                    }
                }
            }
        }
    }

    @Test
    public void chargingOnly_alwaysDisables() {
        assertEquals(DISABLE, decide(MODE_CHARGING_ONLY, false, true, true, false));
        assertEquals(DISABLE, decide(MODE_CHARGING_ONLY, true, true, false, true));
    }

    @Test
    public void whenLocked_unlockedEnables() {
        assertEquals(ENABLE, decide(WHEN_LOCKED, false, true, true, true));
        assertEquals(ENABLE, decide(WHEN_LOCKED, false, true, false, false));
    }

    @Test
    public void whenLocked_beforeFirstUnlockDisablesEvenWithACable() {
        assertEquals(DISABLE, decide(WHEN_LOCKED, true, false, true, false));
        assertEquals(DISABLE, decide(WHEN_LOCKED, true, false, false, false));
    }

    /** Plugged in while unlocked, then locked, unplugged, plugged in again, unlocked. */
    @Test
    public void whenLocked_sequence_existingConnectionSurvivesUntilUnplugged() {
        boolean disabledByUs = false;
        // locking with the cable in: the connection keeps its data
        assertEquals(KEEP, decide(WHEN_LOCKED, true, true, true, disabledByUs));
        // unplugged while locked: data off
        assertEquals(DISABLE, decide(WHEN_LOCKED, true, true, false, disabledByUs));
        disabledByUs = true;
        // a new cable while locked gets no data
        assertEquals(DISABLE, decide(WHEN_LOCKED, true, true, true, disabledByUs));
        // unlock: data back
        assertEquals(ENABLE, decide(WHEN_LOCKED, false, true, true, disabledByUs));
    }

    @Test
    public void whenLocked_lockingWithoutACableDisables() {
        assertEquals(DISABLE, decide(WHEN_LOCKED, true, true, false, false));
    }

    @Test
    public void apply_firstRunAlwaysSendsTheRequest() {
        assertTrue(shouldApply(ENABLE, false, true, false));
        assertTrue(shouldApply(DISABLE, true, true, false));
    }

    @Test
    public void apply_unchangedConfirmedStateSendsNothing() {
        assertFalse(shouldApply(ENABLE, false, false, false));
        assertFalse(shouldApply(DISABLE, true, false, false));
        assertFalse(shouldApply(KEEP, false, true, true));
    }

    /** "Charging only" is applied again for whatever was just plugged in. */
    @Test
    public void apply_disableIsResentOnEveryPortChange() {
        assertTrue(shouldApply(DISABLE, true, false, true));
        assertFalse(shouldApply(ENABLE, false, false, true));
    }

    /** A failed enable leaves the request pending, so the next evaluation sends it again. */
    @Test
    public void apply_failedEnableIsRetried() {
        assertTrue(shouldApply(ENABLE, false, true, false));
        assertTrue(shouldApply(ENABLE, false, true, true));
    }

    @Test
    public void statusMismatch_comparesTheRequestWithDisabledForce() {
        assertTrue(statusMismatch(true, true));    // want data, port still force-disabled
        assertFalse(statusMismatch(true, false));
        assertTrue(statusMismatch(false, false));  // want charging only, port not force-disabled
        assertFalse(statusMismatch(false, true));
    }
}
