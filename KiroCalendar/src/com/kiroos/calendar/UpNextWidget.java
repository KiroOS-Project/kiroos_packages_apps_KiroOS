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

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.List;

/**
 * "Up next" on the home (KiroOS widget): today's date and the next three events of today and
 * tomorrow, with their calendar colour. Updated when events change, at midnight and every 30
 * minutes; a tap opens the upcoming list.
 */
public class UpNextWidget extends AppWidgetProvider {

    private static final int[] ROW = {R.id.upnext_row1, R.id.upnext_row2, R.id.upnext_row3};
    private static final int[] DOT = {R.id.upnext_dot1, R.id.upnext_dot2, R.id.upnext_dot3};
    private static final int[] TITLE = {R.id.upnext_title1, R.id.upnext_title2, R.id.upnext_title3};
    private static final int[] TIME = {R.id.upnext_time1, R.id.upnext_time2, R.id.upnext_time3};

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        final String a = intent.getAction();
        // events added or edited, a new day, the clock or time zone changed
        if (Intent.ACTION_PROVIDER_CHANGED.equals(a) || Intent.ACTION_DATE_CHANGED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)) {
            final AppWidgetManager m = AppWidgetManager.getInstance(context);
            onUpdate(context, m, m.getAppWidgetIds(new ComponentName(context, UpNextWidget.class)));
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_upnext);
        final long now = System.currentTimeMillis();
        v.setTextViewText(R.id.upnext_date, DateUtils.formatDateTime(context, now,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_NO_YEAR));
        final boolean allowed = context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
        int shown = 0;
        if (allowed) {
            final Calendar end = Calendar.getInstance();
            end.add(Calendar.DAY_OF_YEAR, 2);
            end.set(Calendar.HOUR_OF_DAY, 0);
            end.set(Calendar.MINUTE, 0);
            end.set(Calendar.SECOND, 0);
            final List<EventStore.Instance> list = EventStore.instances(context,
                    now - DateUtils.DAY_IN_MILLIS, end.getTimeInMillis());
            for (EventStore.Instance i : list) {
                if (shown == ROW.length) break;
                if (i.end <= now) continue; // over already
                v.setViewVisibility(ROW[shown], View.VISIBLE);
                v.setTextViewText(TITLE[shown], i.title == null || i.title.isEmpty()
                        ? context.getString(R.string.widget_untitled) : i.title);
                v.setTextViewText(TIME[shown], when(context, i, now));
                v.setInt(DOT[shown], "setColorFilter", 0xff000000 | i.color);
                shown++;
            }
        }
        for (int k = shown; k < ROW.length; k++) {
            v.setViewVisibility(ROW[k], View.GONE);
        }
        v.setViewVisibility(R.id.upnext_empty, shown == 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.upnext_empty, context.getString(allowed
                ? R.string.widget_upnext_empty : R.string.widget_upnext_permission));
        final Intent open = new Intent(context, allowed ? UpcomingActivity.class
                : CalendarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.upnext_root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(ids, v);
    }

    /** "Now", "14:30", "Tomorrow 9:00", "All day", "Tomorrow, all day" */
    private static String when(Context c, EventStore.Instance i, long now) {
        final Calendar today = Calendar.getInstance();
        final Calendar day = Calendar.getInstance();
        day.setTimeInMillis(Math.max(i.begin, now));
        final boolean tomorrow = day.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR);
        if (i.allDay) {
            return c.getString(tomorrow ? R.string.widget_tomorrow_all_day : R.string.widget_all_day);
        }
        if (i.begin <= now) return c.getString(R.string.widget_now);
        final String time = DateFormat.getTimeFormat(c).format(new java.util.Date(i.begin));
        return tomorrow ? c.getString(R.string.widget_tomorrow_at, time) : time;
    }
}
