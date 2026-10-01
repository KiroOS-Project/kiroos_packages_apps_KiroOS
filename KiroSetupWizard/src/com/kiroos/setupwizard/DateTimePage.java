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

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.text.TimeZoneNames;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Date and time: network time and time zone (on by default), or the date, the time and the
 * zone set by hand; 24-hour format. The same settings as Settings > Date and time.
 */
class DateTimePage extends SetupPage {

    /** the zones offered by hand, one per region and offset (the network sets any zone) */
    private static final String[] ZONES = {
            "Pacific/Honolulu", "America/Anchorage", "America/Los_Angeles", "America/Denver",
            "America/Chicago", "America/Mexico_City", "America/New_York", "America/Bogota",
            "America/Caracas", "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
            "Atlantic/Azores", "Atlantic/Reykjavik", "Europe/London", "Europe/Lisbon",
            "Africa/Casablanca", "Europe/Rome", "Europe/Madrid", "Europe/Paris", "Europe/Berlin",
            "Europe/Zurich", "Europe/Warsaw", "Africa/Lagos", "Europe/Athens",
            "Europe/Bucharest", "Europe/Kiev", "Africa/Cairo", "Asia/Jerusalem",
            "Africa/Johannesburg", "Europe/Istanbul", "Europe/Moscow", "Asia/Riyadh",
            "Asia/Tehran", "Asia/Dubai", "Asia/Karachi", "Asia/Kolkata", "Asia/Dhaka",
            "Asia/Bangkok", "Asia/Jakarta", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore",
            "Asia/Manila", "Australia/Perth", "Asia/Seoul", "Asia/Tokyo", "Australia/Sydney",
            "Pacific/Auckland",
    };

    private TextView mNow;
    private Switch mAutoTime;
    private Switch mAutoZone;
    private Switch m24h;
    private View mZoneRow;
    private View mDateRow;
    private View mTimeRow;
    private boolean mListening;

    private final BroadcastReceiver mClock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    DateTimePage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_schedule;
    }

    @Override
    int titleRes() {
        return R.string.datetime_title;
    }

    @Override
    int summaryRes() {
        return R.string.datetime_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mNow = (TextView) inflater.inflate(R.layout.big_value, content, false);
        content.addView(mNow);

        mAutoTime = addSwitch(content, R.string.time_auto, R.string.time_auto_summary);
        mAutoZone = addSwitch(content, R.string.zone_auto, R.string.zone_auto_summary);
        mZoneRow = addAction(content, R.string.zone_title, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickZone();
            }
        });
        mDateRow = addAction(content, R.string.date_set, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });
        mTimeRow = addAction(content, R.string.time_set, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime();
            }
        });
        m24h = addSwitch(content, R.string.time_24h, R.string.time_24h_summary);

        mAutoTime.setChecked(global(Settings.Global.AUTO_TIME, 1) == 1);
        mAutoZone.setChecked(global(Settings.Global.AUTO_TIME_ZONE, 1) == 1);
        m24h.setChecked(DateFormat.is24HourFormat(mActivity));
        mAutoTime.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.Global.putInt(mActivity.getContentResolver(),
                        Settings.Global.AUTO_TIME, checked ? 1 : 0);
                refresh();
            }
        });
        mAutoZone.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                Settings.Global.putInt(mActivity.getContentResolver(),
                        Settings.Global.AUTO_TIME_ZONE, checked ? 1 : 0);
                refresh();
            }
        });
        m24h.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                set24Hour(checked);
            }
        });
    }

    @Override
    void onShown() {
        if (!mListening) {
            final IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mActivity.registerReceiver(mClock, f);
            mListening = true;
        }
        refresh();
    }

    @Override
    void onHidden() {
        if (mListening) {
            mActivity.unregisterReceiver(mClock);
            mListening = false;
        }
    }

    private int global(String name, int def) {
        return Settings.Global.getInt(mActivity.getContentResolver(), name, def);
    }

    private void refresh() {
        if (mNow == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        mNow.setText(DateUtils.formatDateTime(mActivity, now, DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR)
                + "  ·  " + DateFormat.getTimeFormat(mActivity).format(now));
        final boolean autoTime = mAutoTime.isChecked();
        final boolean autoZone = mAutoZone.isChecked();
        setEnabled(mDateRow, !autoTime);
        setEnabled(mTimeRow, !autoTime);
        setEnabled(mZoneRow, !autoZone);
        setActionValue(mZoneRow, zoneLabel(TimeZone.getDefault(), now));
        setActionValue(mDateRow, DateUtils.formatDateTime(mActivity, now,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        setActionValue(mTimeRow, DateFormat.getTimeFormat(mActivity).format(now));
    }

    private static void setEnabled(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    /** "Rome · GMT+02:00" in the phone's language */
    private String zoneLabel(TimeZone zone, long now) {
        String city = null;
        try {
            city = TimeZoneNames.getInstance(locale()).getExemplarLocationName(zone.getID());
        } catch (RuntimeException e) {
            // no ICU name: the id's last part below
        }
        if (city == null || city.isEmpty()) {
            final String id = zone.getID();
            city = id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
        }
        return city + "  ·  " + gmt(zone.getOffset(now));
    }

    private Locale locale() {
        return mActivity.getResources().getConfiguration().getLocales().get(0);
    }

    private static String gmt(int offsetMs) {
        final int minutes = Math.abs(offsetMs) / 60000;
        return String.format(Locale.US, "GMT%s%02d:%02d", offsetMs < 0 ? "-" : "+",
                minutes / 60, minutes % 60);
    }

    private void pickZone() {
        final long now = System.currentTimeMillis();
        final List<TimeZone> zones = new ArrayList<>();
        for (String id : ZONES) {
            final TimeZone z = TimeZone.getTimeZone(id);
            if (z.getID().equals(id)) {
                zones.add(z);
            }
        }
        Collections.sort(zones, new Comparator<TimeZone>() {
            @Override
            public int compare(TimeZone a, TimeZone b) {
                return Integer.compare(a.getOffset(now), b.getOffset(now));
            }
        });
        final CharSequence[] labels = new CharSequence[zones.size()];
        int checked = -1;
        final String current = TimeZone.getDefault().getID();
        for (int i = 0; i < zones.size(); i++) {
            labels[i] = zoneLabel(zones.get(i), now);
            if (zones.get(i).getID().equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.zone_title)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        alarms().setTimeZone(zones.get(which).getID());
                        dialog.dismiss();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickDate() {
        final Calendar c = Calendar.getInstance();
        new DatePickerDialog(mActivity, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int day) {
                final Calendar n = Calendar.getInstance();
                n.set(year, month, day);
                setTime(n.getTimeInMillis());
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        final Calendar c = Calendar.getInstance();
        new TimePickerDialog(mActivity, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hour, int minute) {
                final Calendar n = Calendar.getInstance();
                n.set(Calendar.HOUR_OF_DAY, hour);
                n.set(Calendar.MINUTE, minute);
                n.set(Calendar.SECOND, 0);
                n.set(Calendar.MILLISECOND, 0);
                setTime(n.getTimeInMillis());
            }
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(mActivity)).show();
    }

    private AlarmManager alarms() {
        return (AlarmManager) mActivity.getSystemService(Context.ALARM_SERVICE);
    }

    private void setTime(long millis) {
        // the clock only goes back to 2007 at the earliest, as in Settings
        if (millis / 1000 < Integer.MAX_VALUE && millis > 1167609600000L) {
            alarms().setTime(millis);
        }
        refresh();
    }

    /** as Settings does: the setting, then ACTION_TIME_CHANGED so clocks redraw */
    private void set24Hour(boolean is24) {
        Settings.System.putString(mActivity.getContentResolver(), Settings.System.TIME_12_24,
                is24 ? "24" : "12");
        final Intent changed = new Intent(Intent.ACTION_TIME_CHANGED);
        changed.putExtra(Intent.EXTRA_TIME_PREF_24_HOUR_FORMAT, is24);
        mActivity.sendBroadcast(changed);
        refresh();
    }
}
