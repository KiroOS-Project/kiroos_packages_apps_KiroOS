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

package com.kiroos.calendar;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.CalendarContract;
import android.provider.CalendarContract.CalendarAlerts;
import android.text.TextUtils;
import android.text.format.DateUtils;

/**
 * The CalendarProvider schedules reminders and broadcasts EVENT_REMINDER when one is due;
 * the calendar app shows it. Every due, scheduled alert becomes a notification and is marked
 * fired. Tapping it opens the event; "Dismiss" marks it dismissed.
 */
public class AlertReceiver extends BroadcastReceiver {

    static final String ACTION_DISMISS = "com.kiroos.calendar.DISMISS_ALERT";
    private static final String EXTRA_ALERT = "alert";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACTION_DISMISS.equals(intent.getAction())) {
                        final long alert = intent.getLongExtra(EXTRA_ALERT, -1);
                        setState(context, alert, CalendarAlerts.STATE_DISMISSED);
                        context.getSystemService(NotificationManager.class).cancel((int) alert);
                    } else {
                        showDue(context);
                    }
                } catch (RuntimeException e) {
                    // no calendar permission
                } finally {
                    pending.finish();
                }
            }
        }, "KiroCalendarAlert").start();
    }

    private static void showDue(Context context) {
        final long now = System.currentTimeMillis();
        try (Cursor c = context.getContentResolver().query(CalendarAlerts.CONTENT_URI,
                new String[] {CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN,
                        CalendarAlerts.END, CalendarAlerts.TITLE, CalendarAlerts.EVENT_LOCATION,
                        CalendarAlerts.ALL_DAY},
                CalendarAlerts.STATE + "=? AND " + CalendarAlerts.ALARM_TIME + "<=?",
                new String[] {String.valueOf(CalendarAlerts.STATE_SCHEDULED),
                        String.valueOf(now)}, CalendarAlerts.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                final long alertId = c.getLong(0);
                final long eventId = c.getLong(1);
                final long begin = c.getLong(2);
                final long end = c.getLong(3);
                final String title = c.getString(4);
                final String location = c.getString(5);
                final boolean allDay = c.getInt(6) != 0;
                notify(context, alertId, eventId, begin, end, title, location, allDay);
                setState(context, alertId, CalendarAlerts.STATE_FIRED);
            }
        }
    }

    private static void notify(Context context, long alertId, long eventId, long begin, long end,
            String title, String location, boolean allDay) {
        String when = allDay ? DateUtils.formatDateTime(context, begin,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_UTC)
                : DateUtils.formatDateRange(context, begin, end,
                DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_ABBREV_ALL);
        if (!TextUtils.isEmpty(location)) {
            when += " · " + location;
        }
        final Intent open = new Intent(Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                context, EventActivity.class)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Intent dismiss = new Intent(ACTION_DISMISS).setClass(context, AlertReceiver.class)
                .putExtra(EXTRA_ALERT, alertId);
        final Notification n = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_event)
                .setColor(context.getColor(R.color.kiro_accent))
                .setContentTitle(TextUtils.isEmpty(title) ? context.getString(R.string.no_title)
                        : title)
                .setContentText(when)
                .setCategory(Notification.CATEGORY_EVENT)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setAutoCancel(true)
                .setWhen(begin)
                .setShowWhen(true)
                .setContentIntent(PendingIntent.getActivity(context, (int) alertId, open,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(PendingIntent.getBroadcast(context, (int) alertId, dismiss,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null,
                        context.getString(R.string.dismiss),
                        PendingIntent.getBroadcast(context, (int) alertId + 1, dismiss,
                                PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .build();
        context.getSystemService(NotificationManager.class).notify((int) alertId, n);
    }

    private static void setState(Context context, long alertId, int state) {
        if (alertId < 0) {
            return;
        }
        final ContentValues v = new ContentValues();
        v.put(CalendarAlerts.STATE, state);
        context.getContentResolver().update(
                ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, alertId), v, null, null);
    }
}
