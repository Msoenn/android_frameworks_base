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

import android.app.Notification
import android.content.Context
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
)

/**
 * The notification end of the tray, fed by SystemUI's own notification pipeline: the list the
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

    private val appNames = HashMap<String, CharSequence>()

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
            appName = appName(sbn.packageName, sbn.user),
            title = title,
            text = text,
            postTimeMillis = sbn.postTime,
            clearable = entry.isClearable,
            icon = n.smallIcon?.loadDrawable(context),
            entry = entry,
        )
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
