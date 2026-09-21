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

package com.kiroos.setupwizard;

import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Step 5: device statistics. The KiroOS updater sends model, version, language, country and
 * carrier with a random id when it checks for updates (never IMEI or serial). The choice is
 * Settings.Global kiroos_share_stats, which the updater in Settings reads.
 */
class StatsPage extends SetupPage {

    static final String SHARE_STATS = "kiroos_share_stats";

    StatsPage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_stats;
    }

    @Override
    int titleRes() {
        return R.string.stats_title;
    }

    @Override
    int summaryRes() {
        return R.string.stats_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        final View row = inflater.inflate(R.layout.row_switch, content, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(R.string.stats_switch);
        ((TextView) row.findViewById(R.id.row_summary)).setText(R.string.stats_switch_summary);
        final Switch share = (Switch) row.findViewById(R.id.row_switch);
        share.setChecked(Settings.Global.getInt(mActivity.getContentResolver(), SHARE_STATS, 1) == 1);
        share.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.Global.putInt(mActivity.getContentResolver(), SHARE_STATS, checked ? 1 : 0);
            }
        });
        content.addView(row);
        final TextView note = (TextView) inflater.inflate(R.layout.status_line, content, false);
        note.setText(R.string.stats_note);
        content.addView(note);
    }

    @Override
    void onLeave() {
        // make the default explicit so the updater sees the user's choice
        if (Settings.Global.getString(mActivity.getContentResolver(), SHARE_STATS) == null) {
            Settings.Global.putInt(mActivity.getContentResolver(), SHARE_STATS, 1);
        }
    }
}
