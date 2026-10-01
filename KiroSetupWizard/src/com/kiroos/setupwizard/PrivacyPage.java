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
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;

/**
 * Location and privacy: location services (Settings > Location, high accuracy or off) and
 * the KiroOS device statistics. The statistics are sent by the updater in Settings with a
 * random id when it checks for updates (never IMEI or serial): Settings.Global
 * kiroos_share_stats.
 */
class PrivacyPage extends SetupPage {

    static final String SHARE_STATS = "kiroos_share_stats";

    private Switch mLocation;

    PrivacyPage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_location;
    }

    @Override
    int titleRes() {
        return R.string.privacy_title;
    }

    @Override
    int summaryRes() {
        return R.string.privacy_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mLocation = addSwitch(content, R.string.location_switch, R.string.location_switch_summary);
        mLocation.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                // Settings.Secure turns LOCATION_MODE into the providers (gps, network)
                Settings.Secure.putInt(mActivity.getContentResolver(), Settings.Secure.LOCATION_MODE,
                        checked ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
                                : Settings.Secure.LOCATION_MODE_OFF);
            }
        });

        addHeading(content, R.string.stats_title);
        final Switch share = addSwitch(content, R.string.stats_switch, R.string.stats_switch_summary);
        share.setChecked(Settings.Global.getInt(mActivity.getContentResolver(), SHARE_STATS, 1) == 1);
        share.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.Global.putInt(mActivity.getContentResolver(), SHARE_STATS, checked ? 1 : 0);
            }
        });
        addStatus(content).setText(R.string.stats_note);
    }

    @Override
    void onShown() {
        if (mLocation != null) {
            // the switch follows the system (Google's location consent may change it)
            mLocation.setChecked(Settings.Secure.getInt(mActivity.getContentResolver(),
                    Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                    != Settings.Secure.LOCATION_MODE_OFF);
        }
    }

    @Override
    void onLeave() {
        // make the default explicit so the updater sees the user's choice
        if (Settings.Global.getString(mActivity.getContentResolver(), SHARE_STATS) == null) {
            Settings.Global.putInt(mActivity.getContentResolver(), SHARE_STATS, 1);
        }
    }
}
