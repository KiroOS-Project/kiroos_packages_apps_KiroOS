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

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Meet kAI: what the assistant does, and the power button shortcut (Settings > Buttons >
 * Hold for kAI; Settings.System kiroos_power_kai, read by PhoneWindowManager).
 */
class KaiPage extends SetupPage {

    static final String KAI = "com.kiroos.kai";
    private static final String POWER_KAI = "kiroos_power_kai";

    KaiPage(SetupActivity activity) {
        super(activity);
    }

    static boolean available(Context context) {
        try {
            context.getPackageManager().getPackageInfo(KAI, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    int iconRes() {
        return R.drawable.ic_kai;
    }

    @Override
    int titleRes() {
        return R.string.kai_title;
    }

    @Override
    int summaryRes() {
        return R.string.kai_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        point(inflater, content, R.drawable.ic_mic, R.string.kai_point_talk);
        point(inflater, content, R.drawable.ic_kai, R.string.kai_point_models);
        point(inflater, content, R.drawable.ic_bolt, R.string.kai_point_actions);
        final Switch power = addSwitch(content, R.string.kai_power, R.string.kai_power_summary);
        power.setChecked(Settings.System.getInt(mActivity.getContentResolver(), POWER_KAI, 1) == 1);
        power.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.System.putInt(mActivity.getContentResolver(), POWER_KAI, checked ? 1 : 0);
            }
        });
    }

    private void point(LayoutInflater inflater, ViewGroup content, int icon, int text) {
        final View row = inflater.inflate(R.layout.row_point, content, false);
        ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.row_title)).setText(text);
        content.addView(row);
    }
}
