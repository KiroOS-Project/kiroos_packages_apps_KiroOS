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

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Step 2: Wi-Fi. Lists nearby networks (strongest first) and connects with a password. */
class WifiPage extends SetupPage {

    private static final long RESCAN_MS = 10000;

    private final WifiManager mWifi;
    private final Handler mHandler = new Handler();
    private ViewGroup mList;
    private TextView mStatus;
    private boolean mShown;

    private final Runnable mRescan = new Runnable() {
        @Override
        public void run() {
            mWifi.startScan();
            mHandler.postDelayed(this, RESCAN_MS);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    WifiPage(SetupActivity activity) {
        super(activity);
        mWifi = (WifiManager) activity.getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_wifi;
    }

    @Override
    int titleRes() {
        return R.string.wifi_title;
    }

    @Override
    int summaryRes() {
        return R.string.wifi_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mStatus = (TextView) inflater.inflate(R.layout.status_line, content, false);
        content.addView(mStatus);
        mList = new android.widget.LinearLayout(mActivity);
        ((android.widget.LinearLayout) mList).setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(mList);
    }

    @Override
    CharSequence nextLabel() {
        return mActivity.getText(isConnected() ? R.string.next : R.string.skip);
    }

    @Override
    void onShown() {
        if (mShown) {
            return;
        }
        mShown = true;
        if (!mWifi.isWifiEnabled()) {
            mWifi.setWifiEnabled(true);
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mActivity.registerReceiver(mReceiver, filter);
        mHandler.post(mRescan);
        refresh();
    }

    @Override
    void onHidden() {
        if (!mShown) {
            return;
        }
        mShown = false;
        mHandler.removeCallbacks(mRescan);
        mActivity.unregisterReceiver(mReceiver);
    }

    private boolean isConnected() {
        final ConnectivityManager cm = (ConnectivityManager)
                mActivity.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo ni = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return ni != null && ni.isConnected();
    }

    private static String unquote(String ssid) {
        if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private void refresh() {
        if (mList == null) {
            return;
        }
        final WifiInfo info = mWifi.getConnectionInfo();
        final String connected = isConnected() && info != null ? unquote(info.getSSID()) : null;
        if (!mWifi.isWifiEnabled()) {
            mStatus.setText(R.string.wifi_turning_on);
        } else if (connected != null) {
            mStatus.setText(mActivity.getString(R.string.wifi_connected_to, connected));
        } else {
            mStatus.setText(R.string.wifi_choose);
        }

        // one row per SSID, keeping the strongest access point
        final Map<String, ScanResult> best = new HashMap<>();
        final List<ScanResult> results = mWifi.getScanResults();
        if (results != null) {
            for (ScanResult r : results) {
                if (TextUtils.isEmpty(r.SSID)) {
                    continue;
                }
                final ScanResult prev = best.get(r.SSID);
                if (prev == null || prev.level < r.level) {
                    best.put(r.SSID, r);
                }
            }
        }
        final List<ScanResult> sorted = new ArrayList<>(best.values());
        Collections.sort(sorted, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult a, ScanResult b) {
                return Integer.compare(b.level, a.level);
            }
        });

        mList.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(mActivity);
        final int max = Math.min(sorted.size(), 12);
        for (int i = 0; i < max; i++) {
            final ScanResult r = sorted.get(i);
            final View row = inflater.inflate(R.layout.row_network, mList, false);
            ((TextView) row.findViewById(R.id.row_title)).setText(r.SSID);
            final boolean secure = isSecure(r);
            final boolean isConnected = r.SSID.equals(connected);
            ((TextView) row.findViewById(R.id.row_summary)).setText(isConnected
                    ? mActivity.getText(R.string.wifi_state_connected)
                    : mActivity.getText(secure ? R.string.wifi_secured : R.string.wifi_open));
            final int bars = WifiManager.calculateSignalLevel(r.level, 4);
            ((ImageView) row.findViewById(R.id.row_icon)).setImageLevel(bars);
            ((ImageView) row.findViewById(R.id.row_icon)).setAlpha(0.4f + 0.2f * bars);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isConnected) {
                        connect(r);
                    }
                }
            });
            mList.addView(row);
        }
        mActivity.refreshButtons();
    }

    private static boolean isSecure(ScanResult r) {
        final String caps = r.capabilities;
        return caps.contains("WEP") || caps.contains("PSK") || caps.contains("EAP");
    }

    private void connect(final ScanResult r) {
        if (!isSecure(r)) {
            final WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + r.SSID + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            join(config);
            return;
        }
        final EditText password = new EditText(mActivity);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint(R.string.wifi_password);
        password.setSingleLine(true);
        final int pad = Math.round(mActivity.getResources().getDisplayMetrics().density * 24);
        final android.widget.FrameLayout wrap = new android.widget.FrameLayout(mActivity);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(password);
        new AlertDialog.Builder(mActivity)
                .setTitle(r.SSID)
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.wifi_connect, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final WifiConfiguration config = new WifiConfiguration();
                        config.SSID = "\"" + r.SSID + "\"";
                        final String key = password.getText().toString();
                        if (r.capabilities.contains("WEP")) {
                            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                            config.wepKeys[0] = "\"" + key + "\"";
                            config.wepTxKeyIndex = 0;
                        } else {
                            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                            config.preSharedKey = "\"" + key + "\"";
                        }
                        join(config);
                    }
                })
                .show();
    }

    private void join(WifiConfiguration config) {
        final int id = mWifi.addNetwork(config);
        if (id >= 0) {
            mWifi.enableNetwork(id, true);
            mWifi.saveConfiguration();
            mWifi.reconnect();
            mStatus.setText(mActivity.getString(R.string.wifi_connecting, unquote(config.SSID)));
        } else {
            mStatus.setText(R.string.wifi_failed);
        }
    }
}
