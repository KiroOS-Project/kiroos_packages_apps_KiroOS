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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.List;

/**
 * Keeps one AlarmManager "alarm clock" slot armed for the soonest alarm (it wakes the phone
 * from deep sleep and shows the alarm icon in the status bar), plus the timer's end.
 */
final class Scheduler {

    static final String ACTION_ALARM = "com.kiroos.clock.ALARM";
    static final String ACTION_TIMER = "com.kiroos.clock.TIMER";
    static final String EXTRA_ID = "id";

    private Scheduler() {
    }

    /** Arms the next alarm; call after any change to the alarms, a boot or a time change. */
    static void reschedule(Context context) {
        final long now = System.currentTimeMillis();
        final List<Alarm> alarms = Alarm.all(context);
        Alarm next = null;
        long nextTime = Long.MAX_VALUE;
        for (Alarm a : alarms) {
            final long t = a.nextTime(now);
            if (t > 0 && t < nextTime) {
                nextTime = t;
                next = a;
            }
        }
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        final PendingIntent fire = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_ALARM).setClass(context, AlarmReceiver.class)
                        .putExtra(EXTRA_ID, next != null ? next.id : -1),
                PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == null) {
            am.cancel(fire);
            return;
        }
        final PendingIntent show = PendingIntent.getActivity(context, 0,
                new Intent(context, ClockActivity.class).putExtra(ClockActivity.EXTRA_TAB, 0),
                PendingIntent.FLAG_UPDATE_CURRENT);
        am.setAlarmClock(new AlarmManager.AlarmClockInfo(nextTime, show), fire);
    }

    // --- timer ----------------------------------------------------------------------------

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("clock", Context.MODE_PRIVATE);
    }

    static PendingIntent timerIntent(Context context) {
        return PendingIntent.getBroadcast(context, 1,
                new Intent(ACTION_TIMER).setClass(context, AlarmReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Starts (or resumes) the timer with {@code remaining} ms left. */
    static void startTimer(Context context, long remaining) {
        final long end = System.currentTimeMillis() + remaining;
        prefs(context).edit().putLong("timer_end", end).putLong("timer_paused", 0).apply();
        context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, end, timerIntent(context));
        TimerNotification.show(context);
    }

    static void pauseTimer(Context context) {
        final long left = prefs(context).getLong("timer_end", 0) - System.currentTimeMillis();
        prefs(context).edit().putLong("timer_end", 0).putLong("timer_paused", Math.max(1, left))
                .apply();
        context.getSystemService(AlarmManager.class).cancel(timerIntent(context));
        TimerNotification.show(context);
    }

    static void resetTimer(Context context) {
        prefs(context).edit().putLong("timer_end", 0).putLong("timer_paused", 0).apply();
        context.getSystemService(AlarmManager.class).cancel(timerIntent(context));
        TimerNotification.cancel(context);
    }
}
