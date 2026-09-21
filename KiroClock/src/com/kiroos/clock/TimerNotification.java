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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.format.DateUtils;

/** The running (or paused) timer in the notification shade, with its controls. */
final class TimerNotification {

    private static final int ID = 200;

    private TimerNotification() {
    }

    static void show(Context context) {
        final long end = Scheduler.prefs(context).getLong("timer_end", 0);
        final long paused = Scheduler.prefs(context).getLong("timer_paused", 0);
        if (end == 0 && paused == 0) {
            cancel(context);
            return;
        }
        final Notification.Builder b = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_hourglass)
                .setColor(context.getColor(R.color.kiro_accent))
                .setContentTitle(context.getString(R.string.timer))
                .setOngoing(true)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(PendingIntent.getActivity(context, 2,
                        new Intent(context, ClockActivity.class)
                                .putExtra(ClockActivity.EXTRA_TAB, ClockActivity.TAB_TIMER),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        if (end > 0) {
            b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(end)
                    .setShowWhen(true)
                    .addAction(action(context, AlarmReceiver.ACTION_TIMER_PAUSE, R.string.pause));
        } else {
            b.setContentText(context.getString(R.string.timer_paused,
                    DateUtils.formatElapsedTime(paused / 1000)))
                    .setShowWhen(false)
                    .addAction(action(context, AlarmReceiver.ACTION_TIMER_RESUME, R.string.resume));
        }
        b.addAction(action(context, AlarmReceiver.ACTION_TIMER_RESET, R.string.reset));
        context.getSystemService(NotificationManager.class).notify(ID, b.build());
    }

    private static Notification.Action action(Context context, String action, int label) {
        return new Notification.Action.Builder(null, context.getString(label),
                PendingIntent.getBroadcast(context, action.hashCode(),
                        new Intent(action).setClass(context, AlarmReceiver.class),
                        PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ID);
    }
}
