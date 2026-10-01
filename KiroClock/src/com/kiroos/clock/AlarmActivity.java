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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

/** Full screen over the lock screen while an alarm or the timer rings. */
public class AlarmActivity extends Activity {

    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TIME = "time";
    private static final String EXTRA_TIMER = "timer";

    private boolean mTimer;

    private final BroadcastReceiver mDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    static Intent intent(Context context, String title, String time, boolean timer) {
        return new Intent(context, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TIME, time)
                .putExtra(EXTRA_TIMER, timer);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        setContentView(R.layout.activity_alarm);
        bind(getIntent());
        registerReceiver(mDone, new IntentFilter(AlarmService.ACTION_DONE));
        if (AlarmService.sRinging == -1) {
            finish(); // opened from an old notification
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        mTimer = intent.getBooleanExtra(EXTRA_TIMER, false);
        ((TextView) findViewById(R.id.title)).setText(intent.getStringExtra(EXTRA_TITLE));
        final TextView time = (TextView) findViewById(R.id.time);
        time.setText(intent.getStringExtra(EXTRA_TIME));
        time.setVisibility(mTimer ? View.GONE : View.VISIBLE);

        final TextView primary = (TextView) findViewById(R.id.btn_dismiss);
        final TextView secondary = (TextView) findViewById(R.id.btn_snooze);
        primary.setText(mTimer ? R.string.stop : R.string.dismiss);
        int snooze = AlarmService.SNOOZE_MINUTES;
        if (!mTimer && AlarmService.sRinging >= 0) {
            final Alarm ringing = Alarm.get(this, AlarmService.sRinging);
            if (ringing != null) {
                snooze = ringing.snoozeMinutes;
            }
        }
        secondary.setText(mTimer ? getString(R.string.add_minute)
                : getString(R.string.snooze_minutes, snooze));
        primary.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        secondary.setBackground(SquircleDrawable.button(getColor(R.color.kiro_surface_raised),
                getColor(R.color.kiro_ripple)));
        primary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(AlarmService.ACTION_DISMISS);
            }
        });
        secondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send(mTimer ? AlarmService.ACTION_TIMER_ADD_MINUTE : AlarmService.ACTION_SNOOZE);
            }
        });
        KiroTouch.apply(primary, secondary);
        KiroTouch.enter(time, findViewById(R.id.title));
    }

    private void send(String action) {
        startService(new Intent(this, AlarmService.class).setAction(action));
        finish();
    }

    /** volume keys snooze the alarm (stop the timer), like most clocks */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                send(mTimer ? AlarmService.ACTION_DISMISS : AlarmService.ACTION_SNOOZE);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // answer the alarm with a button
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mDone);
        super.onDestroy();
    }
}
