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
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

/** Kiro Clock: Alarms, World clock, Timer, Stopwatch. */
public class ClockActivity extends Activity {

    static final String EXTRA_TAB = "tab";
    static final String EXTRA_NEW_ALARM = "new_alarm";
    static final int TAB_ALARMS = 0;
    static final int TAB_WORLD = 1;
    static final int TAB_TIMER = 2;
    static final int TAB_STOPWATCH = 3;

    private AlarmsPage mAlarms;
    private TimerPage mTimer;
    private StopwatchPage mStopwatch;
    private View[] mPages;
    private View[] mTabs;
    private TextView mTitle;
    private int mTab = -1;
    private boolean mResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clock);
        mTitle = (TextView) findViewById(R.id.page_title);
        mAlarms = new AlarmsPage(this, findViewById(R.id.page_alarms));
        new WorldPage(this, findViewById(R.id.page_world));
        mTimer = new TimerPage(this, findViewById(R.id.page_timer));
        mStopwatch = new StopwatchPage(this, findViewById(R.id.page_stopwatch));
        mPages = new View[] {findViewById(R.id.page_alarms), findViewById(R.id.page_world),
                findViewById(R.id.page_timer), findViewById(R.id.page_stopwatch)};
        mTabs = new View[] {findViewById(R.id.tab_alarms), findViewById(R.id.tab_world),
                findViewById(R.id.tab_timer), findViewById(R.id.tab_stopwatch)};
        final int[] icons = {R.drawable.ic_alarm, R.drawable.ic_world, R.drawable.ic_hourglass,
                R.drawable.ic_stopwatch};
        final int[] labels = {R.string.tab_alarms, R.string.tab_world, R.string.tab_timer,
                R.string.tab_stopwatch};
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            ((ImageView) mTabs[i].findViewById(R.id.tab_icon)).setImageResource(icons[i]);
            ((TextView) mTabs[i].findViewById(R.id.tab_label)).setText(labels[i]);
            mTabs[i].setContentDescription(getText(labels[i]));
            mTabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(tab);
                }
            });
        }
        // first start: arm whatever is stored (the app may have been restored or updated)
        Scheduler.reschedule(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        final int tab = intent.getIntExtra(EXTRA_TAB,
                getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", TAB_ALARMS));
        showTab(tab);
        if (intent.getBooleanExtra(EXTRA_NEW_ALARM, false)) {
            intent.removeExtra(EXTRA_NEW_ALARM);
            mAlarms.newAlarm();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mAlarms.refresh();
        mTimer.setVisible(mTab == TAB_TIMER);
        mStopwatch.setVisible(mTab == TAB_STOPWATCH);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        mTimer.setVisible(false);
        mStopwatch.setVisible(false);
    }

    private void showTab(int tab) {
        if (tab < 0 || tab >= mPages.length) {
            tab = TAB_ALARMS;
        }
        final boolean changed = tab != mTab;
        mTab = tab;
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", tab).apply();
        final int[] titles = {R.string.tab_alarms, R.string.tab_world, R.string.tab_timer,
                R.string.tab_stopwatch};
        mTitle.setText(titles[tab]);
        for (int i = 0; i < mPages.length; i++) {
            final boolean on = i == tab;
            mPages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            final int color = getColor(on ? R.color.kiro_accent : R.color.kiro_text_secondary);
            ((ImageView) mTabs[i].findViewById(R.id.tab_icon))
                    .setImageTintList(ColorStateList.valueOf(color));
            ((TextView) mTabs[i].findViewById(R.id.tab_label)).setTextColor(color);
        }
        mTimer.setVisible(tab == TAB_TIMER && mResumed);
        mStopwatch.setVisible(tab == TAB_STOPWATCH && mResumed);
        if (changed) {
            KiroTouch.enter(mPages[tab]);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == AlarmsPage.REQUEST_RINGTONE && resultCode == RESULT_OK) {
            mAlarms.onRingtonePicked(data);
        }
    }
}
