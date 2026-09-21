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
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Stopwatch with laps. Its state is stored (based on elapsedRealtime), so it keeps counting
 * when the app is closed.
 */
final class StopwatchPage {

    private final Activity mActivity;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler();
    private final TextView mTime;
    private final TextView mStartStop;
    private final TextView mLapReset;
    private final List<String> mLapRows = new ArrayList<>();
    private final ArrayAdapter<String> mLaps;
    private boolean mVisible;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            mTime.setText(format(elapsed()));
            if (mVisible && running()) {
                mHandler.postDelayed(this, 30);
            }
        }
    };

    StopwatchPage(Activity activity, View root) {
        mActivity = activity;
        mPrefs = activity.getSharedPreferences("stopwatch", Activity.MODE_PRIVATE);
        mTime = (TextView) root.findViewById(R.id.sw_time);
        mStartStop = (TextView) root.findViewById(R.id.sw_start);
        mLapReset = (TextView) root.findViewById(R.id.sw_lap);
        mLaps = new ArrayAdapter<>(activity, R.layout.item_lap, R.id.lap_text, mLapRows);
        ((ListView) root.findViewById(R.id.sw_laps)).setAdapter(mLaps);
        mStartStop.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        mLapReset.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_surface_raised),
                activity.getColor(R.color.kiro_ripple)));
        mStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running()) {
                    mPrefs.edit().putLong("accum", elapsed()).putLong("start", 0).apply();
                } else {
                    mPrefs.edit().putLong("start", SystemClock.elapsedRealtime()).apply();
                }
                refresh();
            }
        });
        mLapReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running()) {
                    final List<Long> laps = laps();
                    laps.add(0, elapsed());
                    mPrefs.edit().putString("laps", TextUtils.join(",", laps)).apply();
                } else {
                    mPrefs.edit().clear().apply();
                }
                refresh();
            }
        });
        KiroTouch.apply(mStartStop, mLapReset);
        refresh();
    }

    private boolean running() {
        return mPrefs.getLong("start", 0) > 0;
    }

    private long elapsed() {
        final long start = mPrefs.getLong("start", 0);
        return mPrefs.getLong("accum", 0)
                + (start > 0 ? SystemClock.elapsedRealtime() - start : 0);
    }

    private List<Long> laps() {
        final List<Long> out = new ArrayList<>();
        final String s = mPrefs.getString("laps", "");
        if (!s.isEmpty()) {
            for (String p : Arrays.asList(s.split(","))) {
                out.add(Long.parseLong(p));
            }
        }
        return out;
    }

    void setVisible(boolean visible) {
        mVisible = visible;
        refresh();
    }

    private void refresh() {
        mStartStop.setText(running() ? R.string.pause : elapsed() > 0 ? R.string.resume
                : R.string.start);
        mLapReset.setText(running() ? R.string.lap : R.string.reset);
        mLapReset.setEnabled(running() || elapsed() > 0);
        mLapReset.setAlpha(mLapReset.isEnabled() ? 1f : 0.4f);
        final List<Long> laps = laps();
        mLapRows.clear();
        for (int i = 0; i < laps.size(); i++) {
            final long total = laps.get(i);
            final long split = total - (i + 1 < laps.size() ? laps.get(i + 1) : 0);
            mLapRows.add(mActivity.getString(R.string.lap_row, laps.size() - i, format(split),
                    format(total)));
        }
        mLaps.notifyDataSetChanged();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    static String format(long ms) {
        final long cs = ms / 10 % 100;
        final long s = ms / 1000 % 60;
        final long m = ms / 60000 % 60;
        final long h = ms / 3600000;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
                : String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }
}
