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
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import android.widget.NumberPicker;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.Locale;

/**
 * The timer: pick hours / minutes / seconds, then a live countdown with pause, resume and
 * reset. It keeps running (and rings) with the app closed, see Scheduler.
 */
final class TimerPage {

    private final Activity mActivity;
    private final Handler mHandler = new Handler();
    private final View mSetup;
    private final View mRunning;
    private final NumberPicker mHours;
    private final NumberPicker mMinutes;
    private final NumberPicker mSeconds;
    private final TextView mRemaining;
    private final TextView mPauseResume;
    private boolean mVisible;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            if (mVisible) {
                mHandler.postDelayed(this, 200);
            }
        }
    };

    TimerPage(Activity activity, View root) {
        mActivity = activity;
        mSetup = root.findViewById(R.id.timer_setup);
        mRunning = root.findViewById(R.id.timer_running);
        mHours = (NumberPicker) root.findViewById(R.id.timer_hours);
        mMinutes = (NumberPicker) root.findViewById(R.id.timer_minutes);
        mSeconds = (NumberPicker) root.findViewById(R.id.timer_seconds);
        mRemaining = (TextView) root.findViewById(R.id.timer_remaining);
        mPauseResume = (TextView) root.findViewById(R.id.timer_pause);
        final NumberPicker.Formatter twoDigits = new NumberPicker.Formatter() {
            @Override
            public String format(int value) {
                return String.format(Locale.US, "%02d", value);
            }
        };
        mHours.setMaxValue(23);
        mMinutes.setMaxValue(59);
        mSeconds.setMaxValue(59);
        for (NumberPicker p : new NumberPicker[] {mHours, mMinutes, mSeconds}) {
            p.setMinValue(0);
            p.setFormatter(twoDigits);
            p.setDescendantFocusability(NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        }
        final SharedPreferences prefs = Scheduler.prefs(activity);
        final long last = prefs.getLong("timer_last", 5 * 60 * 1000);
        mHours.setValue((int) (last / 3600000));
        mMinutes.setValue((int) (last / 60000 % 60));
        mSeconds.setValue((int) (last / 1000 % 60));

        final TextView start = (TextView) root.findViewById(R.id.timer_start);
        start.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final long ms = (mHours.getValue() * 3600L + mMinutes.getValue() * 60L
                        + mSeconds.getValue()) * 1000L;
                if (ms <= 0) {
                    return;
                }
                prefs.edit().putLong("timer_last", ms).apply();
                Scheduler.startTimer(mActivity, ms);
                update();
            }
        });
        mPauseResume.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        mPauseResume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (prefs.getLong("timer_end", 0) > 0) {
                    Scheduler.pauseTimer(mActivity);
                } else {
                    Scheduler.startTimer(mActivity, prefs.getLong("timer_paused", 0));
                }
                update();
            }
        });
        final TextView reset = (TextView) root.findViewById(R.id.timer_reset);
        reset.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_surface_raised),
                activity.getColor(R.color.kiro_ripple)));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Scheduler.resetTimer(mActivity);
                update();
            }
        });
        final TextView plus = (TextView) root.findViewById(R.id.timer_plus);
        plus.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_surface_raised),
                activity.getColor(R.color.kiro_ripple)));
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final long end = prefs.getLong("timer_end", 0);
                if (end > 0) {
                    Scheduler.startTimer(mActivity, end - System.currentTimeMillis() + 60000);
                } else {
                    prefs.edit().putLong("timer_paused",
                            prefs.getLong("timer_paused", 0) + 60000).apply();
                    TimerNotification.show(mActivity);
                }
                update();
            }
        });
        KiroTouch.apply(start, mPauseResume, reset, plus);
    }

    void setVisible(boolean visible) {
        mVisible = visible;
        mHandler.removeCallbacks(mTick);
        if (visible) {
            mHandler.post(mTick);
        }
    }

    private void update() {
        final SharedPreferences prefs = Scheduler.prefs(mActivity);
        final long end = prefs.getLong("timer_end", 0);
        final long paused = prefs.getLong("timer_paused", 0);
        final boolean active = end > 0 || paused > 0;
        mSetup.setVisibility(active ? View.GONE : View.VISIBLE);
        mRunning.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) {
            return;
        }
        final long left = end > 0 ? Math.max(0, end - System.currentTimeMillis()) : paused;
        final long secs = (left + 999) / 1000;
        mRemaining.setText(secs >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60)
                : String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60));
        mRemaining.setAlpha(end > 0 ? 1f : 0.5f);
        mPauseResume.setText(end > 0 ? R.string.pause : R.string.resume);
    }
}
