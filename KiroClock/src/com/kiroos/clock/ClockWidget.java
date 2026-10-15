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
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * Clock on the home (KiroOS Beta 4 widget): the time and date (TextClocks, so they tick by
 * themselves) and the next alarm, refreshed whenever the next alarm changes. A tap opens Clock.
 */
public class ClockWidget extends AppWidgetProvider {

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        final String a = intent.getAction();
        if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(a)
                || Intent.ACTION_TIME_CHANGED.equals(a)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(a)
                || Intent.ACTION_LOCALE_CHANGED.equals(a)) {
            final AppWidgetManager m = AppWidgetManager.getInstance(context);
            onUpdate(context, m, m.getAppWidgetIds(new ComponentName(context, ClockWidget.class)));
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        final AlarmManager.AlarmClockInfo next = am != null ? am.getNextAlarmClock() : null;
        if (next != null) {
            final Calendar c = Calendar.getInstance();
            c.setTimeInMillis(next.getTriggerTime());
            final String pattern = DateFormat.getBestDateTimePattern(
                    context.getResources().getConfiguration().getLocales().get(0),
                    DateFormat.is24HourFormat(context) ? "EEE Hm" : "EEE hma");
            v.setTextViewText(R.id.clock_alarm, "⏰  " + DateFormat.format(pattern, c));
            v.setViewVisibility(R.id.clock_alarm, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.clock_alarm, View.GONE);
        }
        final Intent open = new Intent(context, ClockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.clock_root, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(ids, v);
    }
}
