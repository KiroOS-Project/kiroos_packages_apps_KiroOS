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
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** Step 3: mobile network. SIM and operator status, mobile data and roaming switches. */
class MobilePage extends SetupPage {

    private final TelephonyManager mTelephony;
    private TextView mStatus;
    private Switch mData;
    private Switch mRoaming;
    private boolean mListening;

    private final PhoneStateListener mListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            refresh();
        }
    };

    MobilePage(SetupActivity activity) {
        super(activity);
        mTelephony = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_sim;
    }

    @Override
    int titleRes() {
        return R.string.mobile_title;
    }

    @Override
    int summaryRes() {
        return R.string.mobile_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mStatus = (TextView) inflater.inflate(R.layout.status_line, content, false);
        content.addView(mStatus);

        final View dataRow = inflater.inflate(R.layout.row_switch, content, false);
        ((TextView) dataRow.findViewById(R.id.row_title)).setText(R.string.mobile_data);
        ((TextView) dataRow.findViewById(R.id.row_summary)).setText(R.string.mobile_data_summary);
        mData = (Switch) dataRow.findViewById(R.id.row_switch);
        content.addView(dataRow);

        final View roamingRow = inflater.inflate(R.layout.row_switch, content, false);
        ((TextView) roamingRow.findViewById(R.id.row_title)).setText(R.string.mobile_roaming);
        ((TextView) roamingRow.findViewById(R.id.row_summary)).setText(R.string.mobile_roaming_summary);
        mRoaming = (Switch) roamingRow.findViewById(R.id.row_switch);
        content.addView(roamingRow);

        mData.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                mTelephony.setDataEnabled(checked);
            }
        });
        mRoaming.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.Global.putInt(mActivity.getContentResolver(),
                        Settings.Global.DATA_ROAMING, checked ? 1 : 0);
            }
        });
    }

    @Override
    void onShown() {
        if (!mListening) {
            mTelephony.listen(mListener, PhoneStateListener.LISTEN_SERVICE_STATE);
            mListening = true;
        }
        refresh();
    }

    @Override
    void onHidden() {
        if (mListening) {
            mTelephony.listen(mListener, PhoneStateListener.LISTEN_NONE);
            mListening = false;
        }
    }

    private void refresh() {
        if (mStatus == null) {
            return;
        }
        final int sim = mTelephony.getSimState();
        final boolean ready = sim == TelephonyManager.SIM_STATE_READY;
        if (sim == TelephonyManager.SIM_STATE_ABSENT) {
            mStatus.setText(R.string.mobile_no_sim);
        } else if (sim == TelephonyManager.SIM_STATE_PIN_REQUIRED
                || sim == TelephonyManager.SIM_STATE_PUK_REQUIRED) {
            mStatus.setText(R.string.mobile_sim_locked);
        } else if (ready) {
            final StringBuilder names = new StringBuilder();
            final List<SubscriptionInfo> subs = SubscriptionManager.from(mActivity)
                    .getActiveSubscriptionInfoList();
            if (subs != null) {
                for (SubscriptionInfo s : subs) {
                    if (names.length() > 0) {
                        names.append(" · ");
                    }
                    names.append(s.getCarrierName());
                }
            }
            if (names.length() == 0) {
                names.append(mTelephony.getNetworkOperatorName());
            }
            mStatus.setText(mActivity.getString(R.string.mobile_operator, names.toString()));
        } else {
            mStatus.setText(R.string.mobile_searching);
        }
        mData.setEnabled(ready);
        mRoaming.setEnabled(ready);
        mData.setChecked(mTelephony.getDataEnabled());
        mRoaming.setChecked(Settings.Global.getInt(mActivity.getContentResolver(),
                Settings.Global.DATA_ROAMING, 0) == 1);
    }
}
