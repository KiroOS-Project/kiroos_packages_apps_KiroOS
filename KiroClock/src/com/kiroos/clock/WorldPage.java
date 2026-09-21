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
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** Local time plus the cities the user added, with their offset from here. */
final class WorldPage {

    private final Activity mActivity;
    private final SharedPreferences mPrefs;
    private final Adapter mAdapter = new Adapter();

    WorldPage(Activity activity, View root) {
        mActivity = activity;
        mPrefs = activity.getSharedPreferences("world", Activity.MODE_PRIVATE);
        final ListView list = (ListView) root.findViewById(R.id.world_list);
        list.setAdapter(mAdapter);
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final String zone = mAdapter.getItem(position);
                new AlertDialog.Builder(mActivity)
                        .setMessage(mActivity.getString(R.string.remove_city, cityOf(zone)))
                        .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                final List<String> z = zones();
                                z.remove(zone);
                                save(z);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            }
        });
        final ImageButton add = (ImageButton) root.findViewById(R.id.btn_add_city);
        add.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickCity();
            }
        });
        KiroTouch.apply(add);
        mAdapter.set(zones());
    }

    private List<String> zones() {
        final String s = mPrefs.getString("zones", "");
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private void save(List<String> zones) {
        mPrefs.edit().putString("zones", TextUtils.join(",", zones)).apply();
        mAdapter.set(zones);
    }

    static String cityOf(String zoneId) {
        final int slash = zoneId.lastIndexOf('/');
        return (slash >= 0 ? zoneId.substring(slash + 1) : zoneId).replace('_', ' ');
    }

    private void pickCity() {
        final List<String> all = new ArrayList<>();
        for (String id : TimeZone.getAvailableIDs()) {
            // real places only (Europe/Rome, America/New_York…), not "Etc/GMT+3"
            if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/")
                    && Character.isUpperCase(id.charAt(0))) {
                all.add(id);
            }
        }
        Collections.sort(all, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return cityOf(a).compareToIgnoreCase(cityOf(b));
            }
        });
        final LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = Math.round(16 * mActivity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText search = new EditText(mActivity);
        search.setHint(R.string.search_city);
        search.setSingleLine(true);
        box.addView(search);
        final ListView list = new ListView(mActivity);
        final List<String> shown = new ArrayList<>(all);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_list_item_2, android.R.id.text1, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final View v = super.getView(position, convertView, parent);
                final String id = shown.get(position);
                ((TextView) v.findViewById(android.R.id.text1)).setText(cityOf(id));
                ((TextView) v.findViewById(android.R.id.text2)).setText(
                        id.substring(0, id.indexOf('/')).replace('_', ' ') + " · " + offsetText(id));
                return v;
            }
        };
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(360 * mActivity.getResources().getDisplayMetrics().density)));
        final AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.add_city)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                final String q = s.toString().toLowerCase().trim();
                shown.clear();
                for (String id : all) {
                    if (q.isEmpty() || cityOf(id).toLowerCase().contains(q)
                            || id.toLowerCase().contains(q)) {
                        shown.add(id);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final List<String> z = zones();
                final String picked = shown.get(position);
                if (!z.contains(picked)) {
                    z.add(picked);
                    save(z);
                }
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    /** "+1 h", "−5 h 30 min", "same time" relative to the phone's zone */
    private String offsetText(String zoneId) {
        final long now = System.currentTimeMillis();
        final int diff = TimeZone.getTimeZone(zoneId).getOffset(now)
                - TimeZone.getDefault().getOffset(now);
        if (diff == 0) {
            return mActivity.getString(R.string.same_time);
        }
        final int mins = Math.abs(diff) / 60000;
        final String sign = diff > 0 ? "+" : "−";
        return mins % 60 == 0 ? mActivity.getString(R.string.offset_h, sign, mins / 60)
                : mActivity.getString(R.string.offset_h_min, sign, mins / 60, mins % 60);
    }

    private final class Adapter extends BaseAdapter {
        private List<String> mZones = Collections.emptyList();

        void set(List<String> zones) {
            mZones = zones;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mZones.size();
        }

        @Override
        public String getItem(int position) {
            return mZones.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : LayoutInflater.from(mActivity).inflate(R.layout.item_zone, parent, false);
            final String zone = mZones.get(position);
            ((TextView) v.findViewById(R.id.zone_city)).setText(cityOf(zone));
            ((TextView) v.findViewById(R.id.zone_offset)).setText(offsetText(zone));
            ((TextClock) v.findViewById(R.id.zone_time)).setTimeZone(zone);
            return v;
        }
    }
}
