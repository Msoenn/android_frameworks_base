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

package com.android.systemui.circa.shade

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.RemoteException
import android.os.UserHandle
import android.service.notification.NotificationStats
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.NotifCollection
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.Executor
import javax.inject.Inject

/** One card of the notification stream, reduced to what the card draws. */
data class CircaNotification(
    val key: String,
    val packageName: String,
    val appName: CharSequence,
    val title: CharSequence?,
    val text: CharSequence?,
    val postTimeMillis: Long,
    val clearable: Boolean,
    val icon: Drawable?,
    val entry: NotificationEntry,
    /** The buttons the card draws, at most [MAX_ACTIONS] (see [CircaNotifications.actionsOf]). */
    val actions: List<CircaAction> = emptyList(),
    /** `CATEGORY_CALL`: its actions (Accept / Reject) work on a secure keyguard without unlocking. */
    val isCall: Boolean = false,
)

/**
 * One notification action as a card button. [index] is its position in the list it came from
 * (the WearableExtender's actions when the app set any, else `Notification.actions`), which is
 * what [CircaNotifications.sendReply] looks it up by again.
 */
data class CircaAction(
    val index: Int,
    val title: CharSequence,
    val intent: PendingIntent,
    val action: Notification.Action,
) {
    /** The RemoteInputs the reply screen fills: free-form text or a list of choices. */
    val replyInputs: List<RemoteInput> =
        action.remoteInputs.orEmpty().filter { it.allowFreeFormInput || !it.choices.isNullOrEmpty() }

    val isReply: Boolean
        get() = replyInputs.isNotEmpty()
}

/** Wear shows at most three action buttons per card (decision 2026-10-19: no "More"). */
const val MAX_ACTIONS = 3

/**
 * The notifications page of the tray, fed by SystemUI's own notification pipeline: the list the
 * phone shade would render (after SystemUI's filtering, grouping and ranking), flattened to one
 * card per notification (group summaries are dropped when the group has children).
 * Dismissal goes through [NotifCollection] and taps through [ActivityStarter], like the shade's.
 */
@SysUISingleton
class CircaNotifications
@Inject
constructor(
    @Application private val context: Context,
    private val notifPipeline: NotifPipeline,
    private val notifCollection: NotifCollection,
    private val barService: IStatusBarService,
    private val activityStarter: ActivityStarter,
    private val keyguardStateController: KeyguardStateController,
    @Background private val bgExecutor: Executor,
) {
    val items = mutableStateOf<List<CircaNotification>>(emptyList())

    /** Package labels, per package: the fallback header name. */
    private val appNames = HashMap<String, CharSequence>()

    /**
     * Header names per notification key, with the [Notification] they were read from (a repost carries
     * a new one): a notification may name another app through `EXTRA_SUBSTITUTE_APP_NAME` (WatchLink's
     * phone notifications, "Messages", "Gmail"), so the name is not a property of the package.
     */
    private val headerNames = HashMap<String, Pair<Notification, CharSequence>>()

    fun init() {
        notifPipeline.addOnAfterRenderListListener { entries -> update(entries) }
    }

    /** Notification content is hidden while the device is locked with a secure method. */
    fun isRedacted(): Boolean =
        keyguardStateController.isShowing &&
            keyguardStateController.isMethodSecure &&
            !keyguardStateController.isUnlocked

    private fun update(entries: List<PipelineEntry>) {
        val flat = ArrayList<NotificationEntry>()
        for (e in entries) {
            when (e) {
                is GroupEntry -> {
                    val children = e.children
                    if (children.isNotEmpty()) flat.addAll(children) else e.summary?.let(flat::add)
                }
                is NotificationEntry -> flat.add(e)
                else -> {}
            }
        }
        headerNames.keys.retainAll(flat.mapTo(HashSet()) { it.key })
        items.value = flat.map(::toItem)
    }

    internal fun toItem(entry: NotificationEntry): CircaNotification {
        val sbn = entry.sbn
        val n = sbn.notification
        val extras = n.extras
        val title =
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val text =
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        return CircaNotification(
            key = entry.key,
            packageName = sbn.packageName,
            appName = headerName(entry.key, n, sbn.packageName, sbn.user),
            title = title,
            text = text,
            postTimeMillis = sbn.postTime,
            clearable = entry.isClearable,
            icon = n.smallIcon?.loadDrawable(context),
            entry = entry,
            actions = actionsOf(n).take(MAX_ACTIONS),
            isCall = n.category == Notification.CATEGORY_CALL,
        )
    }

    /**
     * The actions the card offers, by Wear's rule: the WearableExtender's actions when the app set
     * any (they are meant for a watch), else the notification's own. Contextual (assistant)
     * actions and actions without an intent or title are skipped; [CircaAction.index] stays the
     * position in the source list.
     */
    internal fun actionsOf(n: Notification): List<CircaAction> {
        val wear = Notification.WearableExtender(n).actions
        val source: List<Notification.Action> =
            if (wear.isNotEmpty()) wear else n.actions?.toList().orEmpty()
        val out = ArrayList<CircaAction>()
        source.forEachIndexed { i, a ->
            val intent = a.actionIntent ?: return@forEachIndexed
            if (a.isContextual || a.title.isNullOrBlank()) return@forEachIndexed
            out.add(CircaAction(i, a.title, intent, a))
        }
        return out
    }

    /** The current card for [key], or null once the notification is gone. */
    fun find(key: String): CircaNotification? = notifCollection.getEntry(key)?.let(::toItem)

    /**
     * An action button: report the click, then fire the action. On a secure keyguard only a
     * call's actions are shown (and sent without unlocking, decision 2026-10-19); any other
     * action goes through the keyguard dismissal like a card tap. Unlocked, an action that does
     * not open an activity is sent in place, so the tray stays open and shows the card's update.
     * Returns true when the caller should close the tray (an activity or the bouncer comes up).
     * A reply action does not come here: it opens [CircaReplyActivity] ([openReply]).
     */
    fun invokeAction(item: CircaNotification, action: CircaAction): Boolean {
        reportActionClick(item, action)
        val locked = isRedacted()
        if ((locked && item.isCall) || (!locked && !action.intent.isActivity)) {
            sendInPlace(action.intent, null)
            return action.intent.isActivity
        }
        activityStarter.startPendingIntentDismissingKeyguard(action.intent)
        return true
    }

    /** The reply screen for [action], behind the keyguard dismissal (it never shows over a lock). */
    fun openReply(item: CircaNotification, action: CircaAction) {
        val intent =
            Intent(context, CircaReplyActivity::class.java)
                .putExtra(CircaReplyActivity.EXTRA_KEY, item.key)
                .putExtra(CircaReplyActivity.EXTRA_INDEX, action.index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activityStarter.startActivity(intent, /* dismissShade= */ true)
    }

    /**
     * Sends [text] through the reply action at [index] of notification [key], as SystemUI's inline
     * reply does (RemoteInputViewController): the results go into a fill-in intent for the
     * action's PendingIntent. False when the notification or its action is gone. Main thread.
     */
    fun sendReply(key: String, index: Int, title: CharSequence?, text: CharSequence, fromChoice: Boolean): Boolean {
        val item = find(key) ?: return false
        val action =
            actionsOf(item.entry.sbn.notification).firstOrNull {
                it.index == index && it.isReply && (title == null || it.title.toString() == title.toString())
            } ?: return false
        val inputs = action.replyInputs
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, text)
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs.toTypedArray(), fillIn, results)
        RemoteInput.setResultsSource(
            fillIn,
            if (fromChoice) RemoteInput.SOURCE_CHOICE else RemoteInput.SOURCE_FREE_FORM_INPUT,
        )
        reportActionClick(item, action)
        // Not onNotificationDirectReplied: with the lifetime-extension refactor it marks the record
        // FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY, and the action click's scheduled cancel then
        // removes the card 200 ms later unless the app reposts in that window. A mirrored phone
        // notification is updated or removed by the phone (GB's notify / notify-, often seconds
        // later), and WatchLink's "Not sent" repost must stay (decision 2026-10-19: no optimistic
        // removal), so the card is left to the app.
        return sendInPlace(action.intent, fillIn)
    }

    private fun reportActionClick(item: CircaNotification, action: CircaAction) {
        val visibility = visibilityOf(item)
        bgExecutor.execute {
            try {
                barService.onNotificationActionClick(
                    item.key,
                    action.index,
                    action.action,
                    visibility,
                    false,
                )
            } catch (e: RemoteException) {
                Log.w(TAG, "onNotificationActionClick failed", e)
            }
        }
    }

    /** PendingIntent.send with SystemUI's background-activity-start grant, as the shade's own. */
    private fun sendInPlace(intent: PendingIntent, fillIn: Intent?): Boolean {
        val options =
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
        return try {
            intent.send(context, 0, fillIn, null, null, null, options.toBundle())
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "action intent cancelled", e)
            false
        }
    }

    /**
     * The name the notification's header shows, as AOSP's shade does
     * (`Notification.loadHeaderAppName`): the substitute app name when the poster may set one
     * (NotificationManagerService strips `EXTRA_SUBSTITUTE_APP_NAME` from apps without
     * SUBSTITUTE_NOTIFICATION_APP_NAME), else the app's label; the package label if neither resolves.
     */
    private fun headerName(key: String, n: Notification, pkg: String, user: UserHandle): CharSequence {
        headerNames[key]?.let { (from, name) -> if (from === n) return name }
        val name: CharSequence = n.loadHeaderAppName(context)?.takeIf { it.isNotEmpty() } ?: appName(pkg, user)
        headerNames[key] = n to name
        return name
    }

    private fun appName(pkg: String, user: UserHandle): CharSequence =
        appNames.getOrPut(pkg) {
            try {
                val pm = context.packageManager
                val info =
                    pm.getApplicationInfoAsUser(
                        pkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        user.identifier,
                    )
                pm.getApplicationLabel(info)
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
        }

    /** Tap: report the click (auto-cancel happens in NotificationManagerService), then launch. */
    fun open(item: CircaNotification) {
        val visibility = visibilityOf(item)
        bgExecutor.execute {
            try {
                barService.onNotificationClick(item.key, visibility)
            } catch (e: RemoteException) {
                Log.w(TAG, "onNotificationClick failed", e)
            }
        }
        val intent = item.entry.sbn.notification.contentIntent ?: return
        activityStarter.startPendingIntentDismissingKeyguard(intent)
    }

    /** Swipe away: the shade's own dismissal path. Must run on the main thread. */
    fun dismiss(item: CircaNotification) {
        if (!item.clearable) return
        notifCollection.dismissNotification(
            item.entry,
            DismissedByUserStats(
                NotificationStats.DISMISSAL_SHADE,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                visibilityOf(item),
            ),
        )
    }

    /**
     * "Clear all": the phone shade's own clear-all path (NotificationStackScrollLayoutController):
     * system server clears every clearable notification of the user; ongoing ones stay. Main thread.
     */
    fun dismissAll() {
        notifCollection.dismissAllNotifications(ActivityManager.getCurrentUser())
    }

    private fun visibilityOf(item: CircaNotification): NotificationVisibility {
        val list = items.value
        return NotificationVisibility.obtain(
            item.key,
            list.indexOfFirst { it.key == item.key },
            list.size,
            true,
            NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA,
        )
    }

    private companion object {
        const val TAG = "CircaShade"
    }
}
