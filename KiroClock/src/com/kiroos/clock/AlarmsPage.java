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
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.text.DateFormatSymbols;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * The alarms list, and the alarm editor (time, days, label, sound, snooze length,
 * vibration). Long press a repeating alarm to skip its next ring.
 */
final class AlarmsPage {

    static final int REQUEST_RINGTONE = 10;
    private static final int[] SNOOZE_CHOICES = {5, 10, 15, 20, 30};

    private final Activity mActivity;
    private final Adapter mAdapter = new Adapter();
    private final TextView mEmpty;
    private Alarm mEditing;
    private TextView mEditingSound;

    AlarmsPage(Activity activity, View root) {
        mActivity = activity;
        final ListView list = (ListView) root.findViewById(R.id.alarms_list);
        mEmpty = (TextView) root.findViewById(R.id.alarms_empty);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                edit(mAdapter.getItem(position));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                return skipMenu(mAdapter.getItem(position));
            }
        });
        final ImageButton add = (ImageButton) root.findViewById(R.id.btn_add_alarm);
        add.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newAlarm();
            }
        });
        KiroTouch.apply(add);
        refresh();
    }

    void refresh() {
        final List<Alarm> alarms = Alarm.all(mActivity);
        mAdapter.set(alarms);
        mEmpty.setVisibility(alarms.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Pick a time, then the new alarm is on and its editor opens. */
    void newAlarm() {
        final Calendar now = Calendar.getInstance();
        new TimePickerDialog(mActivity, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hour, int minute) {
                final Alarm a = new Alarm();
                a.hour = hour;
                a.minute = minute;
                a.days = 0;
                a.save(mActivity);
                changed(a);
                edit(a);
            }
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(mActivity)).show();
    }

    private void changed(Alarm a) {
        Scheduler.reschedule(mActivity);
        refresh();
        if (a.enabled) {
            final long t = a.nextTime(System.currentTimeMillis());
            if (t > 0) {
                Toast.makeText(mActivity, untilText(t), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** "Alarm in 7 h 12 min" */
    private String untilText(long t) {
        final long mins = Math.max(1, (t - System.currentTimeMillis() + 59999) / 60000);
        final long h = mins / 60;
        final long m = mins % 60;
        if (h >= 24) {
            return mActivity.getString(R.string.alarm_in_days, h / 24, h % 24);
        }
        return h > 0 ? mActivity.getString(R.string.alarm_in_hours, h, m)
                : mActivity.getString(R.string.alarm_in_minutes, m);
    }

    /** "Wed 07:00" */
    private String whenText(long t) {
        return DateUtils.formatDateTime(mActivity, t, DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_ABBREV_WEEKDAY) + " " + DateFormat.getTimeFormat(mActivity).format(t);
    }

    /**
     * Long press on a repeating alarm (1.2): skip its next ring, e.g. a holiday tomorrow, or
     * bring it back. Returns false (no menu) for one-time or switched-off alarms.
     */
    private boolean skipMenu(final Alarm a) {
        final long now = System.currentTimeMillis();
        if (!a.enabled || a.days == 0) {
            return false;
        }
        final boolean skipped = a.skipUntil > now;
        final String label;
        if (skipped) {
            label = mActivity.getString(R.string.undo_skip);
        } else {
            label = mActivity.getString(R.string.skip_next, whenText(a.nextOccurrence(now)));
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(format(a.hour, a.minute))
                .setItems(new String[] {label}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (skipped) {
                            a.skipUntil = 0;
                        } else {
                            a.skipUntil = a.nextOccurrence(System.currentTimeMillis());
                        }
                        a.save(mActivity);
                        changed(a);
                    }
                })
                .show();
        return true;
    }

    // --- editor ---------------------------------------------------------------------------

    private void edit(final Alarm alarm) {
        final View v = LayoutInflater.from(mActivity).inflate(R.layout.dialog_alarm, null);
        final TextView time = (TextView) v.findViewById(R.id.edit_time);
        final EditText label = (EditText) v.findViewById(R.id.edit_label);
        final Switch vibrate = (Switch) v.findViewById(R.id.edit_vibrate);
        final TextView sound = (TextView) v.findViewById(R.id.edit_sound);
        final TextView snooze = (TextView) v.findViewById(R.id.edit_snooze);
        final LinearLayout days = (LinearLayout) v.findViewById(R.id.edit_days);

        // 1.2: the snooze length of this alarm
        final int[] snoozeChoice = {alarm.snoozeMinutes};
        snooze.setText(mActivity.getString(R.string.snooze_length, alarm.snoozeMinutes));
        snooze.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final String[] names = new String[SNOOZE_CHOICES.length];
                int checked = -1;
                for (int i = 0; i < names.length; i++) {
                    names[i] = mActivity.getResources().getQuantityString(R.plurals.minutes_n,
                            SNOOZE_CHOICES[i], SNOOZE_CHOICES[i]);
                    if (SNOOZE_CHOICES[i] == snoozeChoice[0]) {
                        checked = i;
                    }
                }
                new AlertDialog.Builder(mActivity)
                        .setTitle(R.string.snooze_title)
                        .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                snoozeChoice[0] = SNOOZE_CHOICES[which];
                                snooze.setText(mActivity.getString(R.string.snooze_length,
                                        snoozeChoice[0]));
                                d.dismiss();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });

        time.setText(format(alarm.hour, alarm.minute));
        time.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                new TimePickerDialog(mActivity, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker p, int hour, int minute) {
                        alarm.hour = hour;
                        alarm.minute = minute;
                        time.setText(format(hour, minute));
                    }
                }, alarm.hour, alarm.minute, DateFormat.is24HourFormat(mActivity)).show();
            }
        });
        label.setText(alarm.label);
        vibrate.setChecked(alarm.vibrate);

        // Monday first, as in Italy and most of Europe
        final String[] names = new DateFormatSymbols().getShortWeekdays();
        final int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            final TextView d = (TextView) days.getChildAt(i);
            d.setText(names[order[i]].substring(0, 1).toUpperCase());
            d.setContentDescription(names[order[i]]);
            d.setSelected((alarm.days & bit) != 0);
            d.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    alarm.days ^= bit;
                    view.setSelected((alarm.days & bit) != 0);
                }
            });
        }
        mEditing = alarm;
        mEditingSound = sound;
        sound.setText(soundName(alarm.ringtone));
        sound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                final Intent pick = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,
                                mActivity.getString(R.string.sound));
                if (alarm.ringtone != null) {
                    pick.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            alarm.ringtone.isEmpty() ? null : Uri.parse(alarm.ringtone));
                }
                mActivity.startActivityForResult(pick, REQUEST_RINGTONE);
            }
        });

        new AlertDialog.Builder(mActivity)
                .setView(v)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        alarm.label = label.getText().toString().trim();
                        alarm.vibrate = vibrate.isChecked();
                        alarm.snoozeMinutes = snoozeChoice[0];
                        alarm.enabled = true;
                        alarm.snoozeUntil = 0;
                        alarm.skipUntil = 0;
                        alarm.save(mActivity);
                        changed(alarm);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        alarm.delete(mActivity);
                        Scheduler.reschedule(mActivity);
                        refresh();
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface d) {
                        mEditing = null;
                        mEditingSound = null;
                    }
                })
                .show();
    }

    void onRingtonePicked(Intent data) {
        if (mEditing == null || data == null) {
            return;
        }
        final Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) {
            mEditing.ringtone = "";
        } else if (RingtoneManager.isDefault(uri)) {
            mEditing.ringtone = null;
        } else {
            mEditing.ringtone = uri.toString();
        }
        if (mEditingSound != null) {
            mEditingSound.setText(soundName(mEditing.ringtone));
        }
    }

    private String soundName(String ringtone) {
        if (ringtone == null) {
            return mActivity.getString(R.string.default_sound);
        }
        if (ringtone.isEmpty()) {
            return mActivity.getString(R.string.silent);
        }
        final Ringtone r = RingtoneManager.getRingtone(mActivity, Uri.parse(ringtone));
        return r != null ? r.getTitle(mActivity) : mActivity.getString(R.string.default_sound);
    }

    private String format(int hour, int minute) {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return DateFormat.getTimeFormat(mActivity).format(c.getTime());
    }

    private String daysText(Alarm a) {
        if (a.days == 0) {
            return mActivity.getString(R.string.once);
        }
        if (a.days == 0x7F) {
            return mActivity.getString(R.string.every_day);
        }
        if (a.days == 0x1F) {
            return mActivity.getString(R.string.weekdays);
        }
        if (a.days == 0x60) {
            return mActivity.getString(R.string.weekend);
        }
        final String[] names = new DateFormatSymbols().getShortWeekdays();
        final int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((a.days & (1 << i)) != 0) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(names[order[i]]);
            }
        }
        return b.toString();
    }

    private final class Adapter extends BaseAdapter {
        private List<Alarm> mAlarms = Collections.emptyList();

        void set(List<Alarm> alarms) {
            mAlarms = alarms;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mAlarms.size();
        }

        @Override
        public Alarm getItem(int position) {
            return mAlarms.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mAlarms.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : LayoutInflater.from(mActivity).inflate(R.layout.item_alarm, parent, false);
            final Alarm a = mAlarms.get(position);
            final TextView time = (TextView) v.findViewById(R.id.alarm_time);
            time.setText(format(a.hour, a.minute));
            time.setTextColor(mActivity.getColor(a.enabled ? R.color.kiro_text
                    : R.color.kiro_text_tertiary));
            String summary = daysText(a);
            if (!TextUtils.isEmpty(a.label)) {
                summary = a.label + " · " + summary;
            }
            final long now = System.currentTimeMillis();
            if (a.snoozeUntil > now) {
                final Calendar c = Calendar.getInstance();
                c.setTimeInMillis(a.snoozeUntil);
                summary = mActivity.getString(R.string.snoozed_until,
                        DateFormat.getTimeFormat(mActivity).format(c.getTime()));
            } else if (a.enabled && a.skipUntil > now) {
                summary = mActivity.getString(R.string.skipped_next, whenText(a.nextOccurrence(now)));
            }
            ((TextView) v.findViewById(R.id.alarm_summary)).setText(summary);
            final Switch sw = (Switch) v.findViewById(R.id.alarm_switch);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(a.enabled);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    a.enabled = on;
                    a.snoozeUntil = 0;
                    a.skipUntil = 0;
                    a.save(mActivity);
                    changed(a);
                }
            });
            return v;
        }
    }
}
