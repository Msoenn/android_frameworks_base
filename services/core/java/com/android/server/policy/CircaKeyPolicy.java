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

package com.android.server.policy;

import android.app.WindowConfiguration;
import android.content.Intent;

/**
 * Circa watch button policy helpers used by {@link PhoneWindowManager}.
 *
 * <p>The Pixel Watch 2 has two buttons (docs/watch-ui/circa/buttons.md): the <b>crown</b>, whose
 * press is {@code KEYCODE_POWER} (PMIC power key), and the <b>side button</b> next to it,
 * {@code KEYCODE_STEM_PRIMARY} (scancode 114 on petc_qpnp_pon, mapped by the device keylayout).
 * The behaviours are selected by framework-res values that default to stock Android:
 * <ul>
 *   <li>{@code config_shortPressOnPowerBehavior = 101} ({@link
 *       #SHORT_PRESS_POWER_CIRCA_APP_LIST}): crown short press while the screen is on: home on
 *       top -> the home app's app list, anywhere else -> home; a PIN keyguard that nothing
 *       occludes -> the bouncer.
 *   <li>{@code config_shortPressOnStemPrimaryBehavior = 100} ({@link
 *       #SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS}): side button short press toggles SystemUI's
 *       notifications screen (also over a PIN keyguard, where it is redacted).
 *   <li>{@code config_longPressOnStemPrimaryBehavior = 100} ({@link
 *       #LONG_PRESS_PRIMARY_CIRCA_EXERCISE_OR_GLOBAL_ACTIONS}): side button long press starts or
 *       stops an exercise ({@link #ACTION_EXERCISE_LONG_PRESS}) when a system app handles it,
 *       otherwise the power menu; 2 ({@link #LONG_PRESS_PRIMARY_GLOBAL_ACTIONS}) is always the
 *       power menu. The crown's long press is the power key's stock power menu.
 *   <li>Older values, kept for builds that still select them: stem short press 3 ({@link
 *       #SHORT_PRESS_PRIMARY_CIRCA}, the app-list press on the stem key), power short press 100
 *       ({@link #SHORT_PRESS_POWER_CIRCA_NOTIFICATIONS}, the notifications press on the power key;
 *       a PIN keyguard sleeps), and {@code config_circaPowerShortPressOpensRecents} (power short
 *       press opens the launcher's Recents unless a Circa power behaviour is selected).
 * </ul>
 *
 * <p>A press that wakes the screen only wakes: the power key's short press is never handled when
 * the gesture began non-interactive (AOSP), and {@link #stemShortPressOnlyWakes} does the same for
 * the Circa stem behaviours. While theater mode is on only the power key (the crown) wakes the
 * screen; the side button and the crown's rotation do not.
 *
 * <p>Both launcher-facing intents are sent explicitly to the package of the current HOME role
 * holder, so there is never a chooser even when several launchers are installed.
 */
final class CircaKeyPolicy {
    /** Stem short press: app list when on home, home otherwise (before 2026-10-04's swap). */
    static final int SHORT_PRESS_PRIMARY_CIRCA = 3;

    /**
     * Stem (side button) short press: toggle the notifications screen (IStatusBarService
     * .togglePanel, which the Circa shade routes to its notifications page). Far above AOSP's
     * SHORT_PRESS_PRIMARY_* values (0..2).
     */
    static final int SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS = 100;

    /** Stem (side button) long press: show the global actions (power) menu. */
    static final int LONG_PRESS_PRIMARY_GLOBAL_ACTIONS = 2;

    /**
     * Stem (side button) long press: start the exercise app's activity for
     * {@link #ACTION_EXERCISE_LONG_PRESS} when a preinstalled (system) app declares one, otherwise
     * the global actions (power) menu. Far above AOSP's LONG_PRESS_PRIMARY_* values (0..2).
     */
    static final int LONG_PRESS_PRIMARY_CIRCA_EXERCISE_OR_GLOBAL_ACTIONS = 100;

    /**
     * Side-button long press contract for an exercise app: an activity (category DEFAULT) with
     * this action in a system app is started with {@code FLAG_ACTIVITY_NEW_TASK}; it decides
     * itself whether that starts or stops an exercise. Only system apps are considered, so an
     * installed app cannot take over the button.
     */
    static final String ACTION_EXERCISE_LONG_PRESS = "org.circa.action.EXERCISE_LONG_PRESS";

    /**
     * {@code Settings.Global} key written by Circa Settings (Buttons > Side button long press):
     * {@code "list"} (default) or {@code "last"} start the exercise app as above; {@code "power"}
     * skips it and shows the power menu. The exercise app reads the other two values itself.
     */
    static final String SETTING_EXERCISE_LONG_PRESS = "circa_exercise_long_press";

    /** {@link #SETTING_EXERCISE_LONG_PRESS} value that turns the exercise long press off. */
    static final String EXERCISE_LONG_PRESS_POWER = "power";

    /**
     * Power short press ({@code config_shortPressOnPowerBehavior}, or the
     * {@code Settings.Global.POWER_BUTTON_SHORT_PRESS} override): toggle the notifications screen;
     * a PIN keyguard sleeps. The power-key design before the 2026-10-04 swap. Far above AOSP's
     * SHORT_PRESS_POWER_* values (0..9) so an upstream addition cannot collide.
     */
    static final int SHORT_PRESS_POWER_CIRCA_NOTIFICATIONS = 100;

    /**
     * Power (crown) short press: the app-list press ({@link #stemShortPressAction}), the same one
     * {@link #SHORT_PRESS_PRIMARY_CIRCA} gives the stem key.
     */
    static final int SHORT_PRESS_POWER_CIRCA_APP_LIST = 101;

    /**
     * Action of the intent sent to the home app to show its Recents page. Handled by an activity
     * (category DEFAULT) of the home app; delivered with {@code FLAG_ACTIVITY_NEW_TASK |
     * FLAG_ACTIVITY_RESET_TASK_IF_NEEDED}.
     */
    static final String ACTION_SHOW_RECENTS = "org.circa.intent.action.SHOW_RECENTS";

    private CircaKeyPolicy() {}

    /**
     * @return whether a showing keyguard should block the power key's Recents / notifications. Only a keyguard
     *         that actually has a credential (a PIN) blocks: Circa's launcher shows its watch face
     *         over an insecure keyguard with {@code showWhenLocked}, so there is nothing to unlock
     *         and the button must work. Mirrors AOSP's own test in
     *         {@code SHORT_PRESS_POWER_GO_TO_SLEEP}.
     */
    static boolean keyguardBlocksCircaButton(boolean keyguardOn, boolean keyguardSecure) {
        return keyguardOn && keyguardSecure;
    }

    /**
     * @return whether a stem (side button) press must be ignored right now: theater mode is on and
     *         the display is not awake, so only the power key (the crown) may wake the device (stock
     *         Wear). Without this the side button's short press would act on a dark screen even
     *         though it is no longer allowed to wake the panel.
     */
    static boolean stemSuppressedByTheaterMode(boolean theaterModeOn, boolean displayAwake) {
        return theaterModeOn && !displayAwake;
    }

    /** @return whether {@code behavior} is one of the Circa stem short-press behaviours. */
    static boolean isCircaStemShortPress(int behavior) {
        return behavior == SHORT_PRESS_PRIMARY_CIRCA
                || behavior == SHORT_PRESS_PRIMARY_CIRCA_NOTIFICATIONS;
    }

    /** @return whether {@code behavior} is one of the Circa power short-press behaviours. */
    static boolean isCircaPowerShortPress(int behavior) {
        return behavior == SHORT_PRESS_POWER_CIRCA_NOTIFICATIONS
                || behavior == SHORT_PRESS_POWER_CIRCA_APP_LIST;
    }

    /**
     * @return whether a stem short press must only wake the screen: a Circa stem behaviour and the
     *         press began while the device was not interactive (off or ambient). The same rule AOSP
     *         applies to the power key's short press (shouldHandleShortPressPowerAction).
     */
    static boolean stemShortPressOnlyWakes(int behavior, boolean beganFromNonInteractive) {
        return isCircaStemShortPress(behavior) && beganFromNonInteractive;
    }

    /** The action to take for an app-list press (crown short press). */
    enum StemAction { GO_HOME, SHOW_APP_LIST }

    /** @return what an app-list press should do, given the type of the focused root task. */
    static StemAction stemShortPressAction(int focusedActivityType) {
        return focusedActivityType == WindowConfiguration.ACTIVITY_TYPE_HOME
                ? StemAction.SHOW_APP_LIST : StemAction.GO_HOME;
    }

    /**
     * @return whether the power key's short press should open Recents (rather than the configured
     *         behaviour)
     */
    static boolean powerShortPressOpensRecents(boolean configEnabled, boolean interactive,
            boolean keyguardShowing) {
        return configEnabled && interactive && !keyguardShowing;
    }

    /**
     * @return whether a power short press with {@link #SHORT_PRESS_POWER_CIRCA_NOTIFICATIONS}
     *         toggles the notifications screen; otherwise (a PIN keyguard is showing) it sleeps.
     *         Only called for a short press that began and ended with the screen on, so the press
     *         that wakes the watch from ambient or off never gets here.
     */
    static boolean powerShortPressOpensNotifications(int behavior, boolean interactive,
            boolean keyguardBlocks) {
        return behavior == SHORT_PRESS_POWER_CIRCA_NOTIFICATIONS && interactive && !keyguardBlocks;
    }

    /**
     * @return whether the side-button long press should go to the exercise app, given the value of
     *         {@link #SETTING_EXERCISE_LONG_PRESS} (null when unset): everything except
     *         {@link #EXERCISE_LONG_PRESS_POWER} does, so an unset or unknown value keeps the default.
     */
    static boolean exerciseLongPressEnabled(String settingValue) {
        return settingValue == null
                || !EXERCISE_LONG_PRESS_POWER.equalsIgnoreCase(settingValue.trim());
    }

    /** Builds the side-button long-press intent for the exercise app (not yet resolved). */
    static Intent buildExerciseLongPressIntent() {
        return new Intent(ACTION_EXERCISE_LONG_PRESS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Builds the intent that opens the home app's app list ({@code ACTION_ALL_APPS}). */
    static Intent buildAppListIntent(String homePackage) {
        return addTarget(new Intent(Intent.ACTION_ALL_APPS), homePackage);
    }

    /** Builds the intent that opens the home app's Recents page. */
    static Intent buildRecentsIntent(String homePackage) {
        return addTarget(new Intent(ACTION_SHOW_RECENTS), homePackage);
    }

    private static Intent addTarget(Intent intent, String homePackage) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // "android" is the ResolverActivity: no default home is set, so do not pin a package.
        if (homePackage != null && !"android".equals(homePackage)) {
            intent.setPackage(homePackage);
        }
        return intent;
    }
}
