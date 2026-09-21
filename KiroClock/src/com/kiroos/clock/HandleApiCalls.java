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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.text.format.DateFormat;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * The public AlarmClock API (voice assistants, other apps, the Kiro Home clock):
 * SET_ALARM, SHOW_ALARMS, SET_TIMER, SHOW_TIMERS, DISMISS_ALARM, SNOOZE_ALARM.
 */
public class HandleApiCalls extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();
        final String action = intent != null ? intent.getAction() : null;
        if (AlarmClock.ACTION_SET_ALARM.equals(action)) {
            setAlarm(intent);
        } else if (AlarmClock.ACTION_SET_TIMER.equals(action)) {
            setTimer(intent);
        } else if ("android.intent.action.SHOW_TIMERS".equals(action)) { // API 26 constant
            open(ClockActivity.TAB_TIMER);
        } else if (AlarmClock.ACTION_DISMISS_ALARM.equals(action)) {
            startService(new Intent(this, AlarmService.class).setAction(AlarmService.ACTION_DISMISS));
        } else if (AlarmClock.ACTION_SNOOZE_ALARM.equals(action)) {
            startService(new Intent(this, AlarmService.class).setAction(AlarmService.ACTION_SNOOZE));
        } else {
            open(ClockActivity.TAB_ALARMS);
        }
        finish();
    }

    private void open(int tab) {
        startActivity(new Intent(this, ClockActivity.class).putExtra(ClockActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private void setAlarm(Intent intent) {
        if (!intent.hasExtra(AlarmClock.EXTRA_HOUR)) {
            // no time given: let the user pick it
            startActivity(new Intent(this, ClockActivity.class)
                    .putExtra(ClockActivity.EXTRA_TAB, ClockActivity.TAB_ALARMS)
                    .putExtra(ClockActivity.EXTRA_NEW_ALARM, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        final Alarm a = new Alarm();
        a.hour = Math.max(0, Math.min(23, intent.getIntExtra(AlarmClock.EXTRA_HOUR, 7)));
        a.minute = Math.max(0, Math.min(59, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)));
        final String label = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE);
        a.label = label != null ? label : "";
        a.vibrate = intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true);
        final String tone = intent.getStringExtra(AlarmClock.EXTRA_RINGTONE);
        if (tone != null) {
            a.ringtone = AlarmClock.VALUE_RINGTONE_SILENT.equals(tone) ? "" : tone;
        }
        final ArrayList<Integer> days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS);
        if (days != null) {
            for (int d : days) {
                // Calendar.SUNDAY = 1 … SATURDAY = 7 → Monday = bit 0
                a.days |= 1 << ((d + 5) % 7);
            }
        }
        a.save(this);
        Scheduler.reschedule(this);
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        Toast.makeText(this, getString(R.string.alarm_set,
                DateFormat.getTimeFormat(this).format(c.getTime())), Toast.LENGTH_LONG).show();
        if (!intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
            open(ClockActivity.TAB_ALARMS);
        }
    }

    private void setTimer(Intent intent) {
        final int seconds = intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0);
        if (seconds <= 0) {
            open(ClockActivity.TAB_TIMER);
            return;
        }
        Scheduler.startTimer(this, seconds * 1000L);
        if (!intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)) {
            open(ClockActivity.TAB_TIMER);
        }
    }
}
