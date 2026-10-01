/*
 * Copyright (C) 2026 KiroOS
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

package com.kiroos.clock;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.text.format.DateFormat;

/**
 * Kiro Clock 1.2: "Upcoming alarm" in the shade from two hours before the next alarm, with
 * Dismiss now (that ring is skipped, a repeating alarm keeps its next days). Kept in step by
 * Scheduler.reschedule: posted when the alarm is near, removed when it rang or changed.
 */
final class UpcomingNotification {

    static final String ACTION_UPCOMING = "com.kiroos.clock.UPCOMING";
    static final String ACTION_DISMISS_NOW = "com.kiroos.clock.DISMISS_NOW";
    static final String EXTRA_TIME = "time";
    static final long BEFORE_MS = 2 * 60 * 60 * 1000L;
    private static final int ID = 102;

    private UpcomingNotification() {
    }

    /** next: the soonest alarm (null: none), ringing at time */
    static void update(Context context, Alarm next, long time) {
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        final PendingIntent later = PendingIntent.getBroadcast(context, 2,
                new Intent(ACTION_UPCOMING).setClass(context, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        final long now = System.currentTimeMillis();
        if (next == null) {
            am.cancel(later);
            cancel(context);
            return;
        }
        if (time - now <= BEFORE_MS) {
            am.cancel(later);
            show(context, next, time);
        } else {
            cancel(context);
            // not exact and not waking the phone: it shows when the phone is next awake
            am.setWindow(AlarmManager.RTC, time - BEFORE_MS, 5 * 60 * 1000L, later);
        }
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ID);
    }

    private static void show(Context context, Alarm a, long time) {
        final PendingIntent dismiss = PendingIntent.getBroadcast(context, 3,
                new Intent(ACTION_DISMISS_NOW).setClass(context, AlarmReceiver.class)
                        .putExtra(Scheduler.EXTRA_ID, a.id).putExtra(EXTRA_TIME, time),
                PendingIntent.FLAG_UPDATE_CURRENT);
        final PendingIntent open = PendingIntent.getActivity(context, 4,
                new Intent(context, ClockActivity.class).putExtra(ClockActivity.EXTRA_TAB, 0),
                PendingIntent.FLAG_UPDATE_CURRENT);
        String text = DateFormat.getTimeFormat(context).format(time);
        if (a.snoozeUntil == time) {
            text = context.getString(R.string.snoozed_until, text);
        }
        if (!TextUtils.isEmpty(a.label)) {
            text += " · " + a.label;
        }
        final Notification n = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_alarm)
                .setColor(context.getColor(R.color.kiro_accent))
                .setContentTitle(context.getString(R.string.upcoming_alarm))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.dismiss_now), dismiss).build())
                .build();
        context.getSystemService(NotificationManager.class).notify(ID, n);
    }

    /** the notification's button: skip that ring if it is still the one shown */
    static void dismissNow(Context context, long id, long time) {
        final Alarm a = Alarm.get(context, id);
        final long now = System.currentTimeMillis();
        if (a != null && a.nextTime(now) == time) {
            a.skipNext(now);
            a.save(context);
        }
        Scheduler.reschedule(context);
    }
}
