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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Alarm and timer expiry, the timer notification buttons, and the events after which the
 * alarms must be armed again (boot, time or time zone change, app update).
 */
public class AlarmReceiver extends BroadcastReceiver {

    static final String ACTION_TIMER_PAUSE = "com.kiroos.clock.TIMER_PAUSE";
    static final String ACTION_TIMER_RESUME = "com.kiroos.clock.TIMER_RESUME";
    static final String ACTION_TIMER_RESET = "com.kiroos.clock.TIMER_RESET";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case Scheduler.ACTION_ALARM:
                context.startService(new Intent(context, AlarmService.class)
                        .setAction(AlarmService.ACTION_RING_ALARM)
                        .putExtra(Scheduler.EXTRA_ID, intent.getLongExtra(Scheduler.EXTRA_ID, -1)));
                break;
            case Scheduler.ACTION_TIMER:
                Scheduler.prefs(context).edit().putLong("timer_end", 0).apply();
                TimerNotification.cancel(context);
                context.startService(new Intent(context, AlarmService.class)
                        .setAction(AlarmService.ACTION_RING_TIMER));
                break;
            case ACTION_TIMER_PAUSE:
                Scheduler.pauseTimer(context);
                break;
            case ACTION_TIMER_RESUME:
                Scheduler.startTimer(context, Scheduler.prefs(context).getLong("timer_paused", 0));
                break;
            case ACTION_TIMER_RESET:
                Scheduler.resetTimer(context);
                break;
            case UpcomingNotification.ACTION_UPCOMING:
                // two hours before: rescheduling posts the notification
                Scheduler.reschedule(context);
                break;
            case UpcomingNotification.ACTION_DISMISS_NOW:
                UpcomingNotification.dismissNow(context,
                        intent.getLongExtra(Scheduler.EXTRA_ID, -1),
                        intent.getLongExtra(UpcomingNotification.EXTRA_TIME, -1));
                break;
            default:
                // BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED,
                // MY_PACKAGE_REPLACED
                Scheduler.reschedule(context);
                rearmTimer(context);
                break;
        }
    }

    /** After a reboot the timer's AlarmManager entry is gone: arm it again, or ring now. */
    private static void rearmTimer(Context context) {
        final SharedPreferences p = Scheduler.prefs(context);
        final long end = p.getLong("timer_end", 0);
        if (end == 0) {
            if (p.getLong("timer_paused", 0) > 0) {
                TimerNotification.show(context);
            }
            return;
        }
        final long left = end - System.currentTimeMillis();
        if (left <= 0) {
            onReceiveTimerNow(context);
        } else {
            Scheduler.startTimer(context, left);
        }
    }

    private static void onReceiveTimerNow(Context context) {
        Scheduler.prefs(context).edit().putLong("timer_end", 0).apply();
        context.startService(new Intent(context, AlarmService.class)
                .setAction(AlarmService.ACTION_RING_TIMER));
    }
}
