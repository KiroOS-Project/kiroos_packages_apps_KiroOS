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

package com.kiroos.calendar;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * New or edited event: title, all day, start / end, repeat, reminder, calendar, place,
 * notes. Accepts the standard INSERT extras from other apps.
 */
public class EditEventActivity extends Activity {

    private static final int[] REMINDERS = {-1, 0, 5, 10, 15, 30, 60, 120, 1440, 10080};
    private static final String[] RRULES = {null, "FREQ=DAILY", "FREQ=WEEKLY", "FREQ=MONTHLY",
            "FREQ=YEARLY"};

    private EventStore.Event mEvent = new EventStore.Event();
    private final Calendar mStart = Calendar.getInstance();
    private final Calendar mEnd = Calendar.getInstance();
    private List<EventStore.Cal> mCalendars = new ArrayList<>();

    private EditText mTitle;
    private EditText mLocation;
    private EditText mDescription;
    private Switch mAllDay;
    private TextView mStartDate;
    private TextView mStartTime;
    private TextView mEndDate;
    private TextView mEndTime;
    private Spinner mRepeat;
    private Spinner mReminder;
    private Spinner mCalendar;

    static String reminderText(Context c, int minutes) {
        if (minutes < 0) {
            return c.getString(R.string.no_reminder);
        }
        if (minutes == 0) {
            return c.getString(R.string.at_start);
        }
        if (minutes % 10080 == 0) {
            return c.getString(R.string.weeks_before, minutes / 10080);
        }
        if (minutes % 1440 == 0) {
            return c.getString(R.string.days_before, minutes / 1440);
        }
        if (minutes % 60 == 0) {
            return c.getString(R.string.hours_before, minutes / 60);
        }
        return c.getString(R.string.minutes_before, minutes);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_event);
        mTitle = (EditText) findViewById(R.id.title);
        mLocation = (EditText) findViewById(R.id.location);
        mDescription = (EditText) findViewById(R.id.description);
        mAllDay = (Switch) findViewById(R.id.all_day);
        mStartDate = (TextView) findViewById(R.id.start_date);
        mStartTime = (TextView) findViewById(R.id.start_time);
        mEndDate = (TextView) findViewById(R.id.end_date);
        mEndTime = (TextView) findViewById(R.id.end_time);
        mRepeat = (Spinner) findViewById(R.id.repeat);
        mReminder = (Spinner) findViewById(R.id.reminder);
        mCalendar = (Spinner) findViewById(R.id.calendar);

        final String[] reminderLabels = new String[REMINDERS.length];
        for (int i = 0; i < REMINDERS.length; i++) {
            reminderLabels[i] = reminderText(this, REMINDERS[i]);
        }
        mReminder.setAdapter(spinnerAdapter(reminderLabels));
        mRepeat.setAdapter(spinnerAdapter(new String[] {getString(R.string.repeat_none),
                getString(R.string.repeat_daily), getString(R.string.repeat_weekly),
                getString(R.string.repeat_monthly), getString(R.string.repeat_yearly)}));

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        final TextView save = (TextView) findViewById(R.id.btn_save);
        save.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        mAllDay.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                updateTimes();
            }
        });
        mStartDate.setOnClickListener(pickDate(mStart, true));
        mEndDate.setOnClickListener(pickDate(mEnd, false));
        mStartTime.setOnClickListener(pickTime(mStart, true));
        mEndTime.setOnClickListener(pickTime(mEnd, false));

        final Intent intent = getIntent();
        if (Intent.ACTION_EDIT.equals(intent.getAction()) && intent.getData() != null) {
            loadExisting(ContentUris.parseId(intent.getData()));
        } else {
            fromExtras(intent);
            loadCalendars();
        }
    }

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        final ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void fromExtras(Intent intent) {
        final long now = System.currentTimeMillis();
        final long hour = 3600000;
        final long begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                (now / hour + 1) * hour);
        mStart.setTimeInMillis(begin);
        mEnd.setTimeInMillis(intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                begin + hour));
        mAllDay.setChecked(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false));
        final String title = intent.getStringExtra(CalendarContract.Events.TITLE);
        if (title != null) {
            mTitle.setText(title);
        }
        final String location = intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION);
        if (location != null) {
            mLocation.setText(location);
        }
        final String description = intent.getStringExtra(CalendarContract.Events.DESCRIPTION);
        if (description != null) {
            mDescription.setText(description);
        }
        final String rrule = intent.getStringExtra(CalendarContract.Events.RRULE);
        selectRepeat(rrule);
        selectReminder(15);
        updateTimes();
    }

    private void loadExisting(final long id) {
        new AsyncTask<Void, Void, EventStore.Event>() {
            @Override
            protected EventStore.Event doInBackground(Void... params) {
                return EventStore.load(EditEventActivity.this, id);
            }

            @Override
            protected void onPostExecute(EventStore.Event e) {
                if (e == null) {
                    Toast.makeText(EditEventActivity.this, R.string.event_missing,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                mEvent = e;
                mTitle.setText(e.title);
                mLocation.setText(e.location);
                mDescription.setText(e.description);
                mStart.setTimeInMillis(e.start);
                // all-day end is exclusive (next midnight): show the last day
                mEnd.setTimeInMillis(e.allDay ? Math.max(e.start, e.end - DateUtils.DAY_IN_MILLIS)
                        : e.end);
                mAllDay.setChecked(e.allDay);
                selectRepeat(e.rrule);
                selectReminder(e.reminder);
                updateTimes();
                loadCalendars();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void selectRepeat(String rrule) {
        int sel = 0;
        if (rrule != null) {
            for (int i = 1; i < RRULES.length; i++) {
                if (rrule.contains(RRULES[i])) {
                    sel = i;
                }
            }
        }
        mRepeat.setSelection(sel);
    }

    private void selectReminder(int minutes) {
        int sel = 0;
        for (int i = 0; i < REMINDERS.length; i++) {
            if (REMINDERS[i] == minutes) {
                sel = i;
            }
        }
        mReminder.setSelection(sel);
    }

    private void loadCalendars() {
        new AsyncTask<Void, Void, List<EventStore.Cal>>() {
            @Override
            protected List<EventStore.Cal> doInBackground(Void... params) {
                EventStore.ensureLocalCalendar(EditEventActivity.this,
                        getString(R.string.phone_calendar), getColor(R.color.kiro_accent));
                return EventStore.writableCalendars(EditEventActivity.this);
            }

            @Override
            protected void onPostExecute(List<EventStore.Cal> cals) {
                mCalendars = cals;
                final String[] names = new String[cals.size()];
                int sel = 0;
                for (int i = 0; i < cals.size(); i++) {
                    final EventStore.Cal c = cals.get(i);
                    names[i] = c.account != null && !c.account.equals(c.name)
                            ? c.name + " · " + c.account : c.name;
                    if (c.id == mEvent.calendarId) {
                        sel = i;
                    }
                }
                mCalendar.setAdapter(spinnerAdapter(names));
                mCalendar.setSelection(sel);
                mCalendar.setEnabled(mEvent.id < 0); // moving events between accounts isn't supported by providers
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private View.OnClickListener pickDate(final Calendar target, final boolean isStart) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new DatePickerDialog(EditEventActivity.this, new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker p, int y, int m, int d) {
                        final long length = mEnd.getTimeInMillis() - mStart.getTimeInMillis();
                        target.set(y, m, d);
                        if (isStart) {
                            // keep the duration when the start moves
                            mEnd.setTimeInMillis(mStart.getTimeInMillis() + Math.max(0, length));
                        } else if (mEnd.before(mStart)) {
                            mStart.setTimeInMillis(mEnd.getTimeInMillis() - 3600000);
                        }
                        updateTimes();
                    }
                }, target.get(Calendar.YEAR), target.get(Calendar.MONTH),
                        target.get(Calendar.DAY_OF_MONTH)).show();
            }
        };
    }

    private View.OnClickListener pickTime(final Calendar target, final boolean isStart) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TimePickerDialog(EditEventActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker p, int h, int m) {
                        final long length = mEnd.getTimeInMillis() - mStart.getTimeInMillis();
                        target.set(Calendar.HOUR_OF_DAY, h);
                        target.set(Calendar.MINUTE, m);
                        if (isStart) {
                            mEnd.setTimeInMillis(mStart.getTimeInMillis() + Math.max(0, length));
                        } else if (!mEnd.after(mStart)) {
                            mEnd.setTimeInMillis(mStart.getTimeInMillis() + 3600000);
                        }
                        updateTimes();
                    }
                }, target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE),
                        DateFormat.is24HourFormat(EditEventActivity.this)).show();
            }
        };
    }

    private void updateTimes() {
        final int dateFlags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_ABBREV_ALL;
        mStartDate.setText(DateUtils.formatDateTime(this, mStart.getTimeInMillis(), dateFlags));
        mEndDate.setText(DateUtils.formatDateTime(this, mEnd.getTimeInMillis(), dateFlags));
        mStartTime.setText(DateFormat.getTimeFormat(this).format(mStart.getTime()));
        mEndTime.setText(DateFormat.getTimeFormat(this).format(mEnd.getTime()));
        final int vis = mAllDay.isChecked() ? View.GONE : View.VISIBLE;
        mStartTime.setVisibility(vis);
        mEndTime.setVisibility(vis);
    }

    private void save() {
        if (mCalendars.isEmpty()) {
            Toast.makeText(this, R.string.no_calendar, Toast.LENGTH_LONG).show();
            return;
        }
        final EventStore.Event e = mEvent;
        e.title = mTitle.getText().toString().trim();
        e.location = mLocation.getText().toString().trim();
        e.description = mDescription.getText().toString().trim();
        e.allDay = mAllDay.isChecked();
        if (e.id < 0) {
            e.calendarId = mCalendars.get(Math.max(0, mCalendar.getSelectedItemPosition())).id;
        }
        if (e.allDay) {
            final Calendar s = (Calendar) mStart.clone();
            s.set(Calendar.HOUR_OF_DAY, 0);
            s.set(Calendar.MINUTE, 0);
            s.set(Calendar.SECOND, 0);
            s.set(Calendar.MILLISECOND, 0);
            final Calendar en = (Calendar) mEnd.clone();
            en.set(Calendar.HOUR_OF_DAY, 0);
            en.set(Calendar.MINUTE, 0);
            en.set(Calendar.SECOND, 0);
            en.set(Calendar.MILLISECOND, 0);
            en.add(Calendar.DAY_OF_MONTH, 1); // exclusive end
            e.start = s.getTimeInMillis();
            e.end = Math.max(en.getTimeInMillis(), e.start + DateUtils.DAY_IN_MILLIS);
        } else {
            e.start = mStart.getTimeInMillis();
            e.end = Math.max(mEnd.getTimeInMillis(), e.start);
        }
        e.rrule = RRULES[mRepeat.getSelectedItemPosition()];
        e.reminder = REMINDERS[mReminder.getSelectedItemPosition()];
        final Context app = getApplicationContext();
        new AsyncTask<Void, Void, Long>() {
            @Override
            protected Long doInBackground(Void... params) {
                try {
                    return EventStore.save(app, e);
                } catch (RuntimeException ex) {
                    return -1L;
                }
            }

            @Override
            protected void onPostExecute(Long id) {
                if (id < 0) {
                    Toast.makeText(app, R.string.save_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                setResult(RESULT_OK, new Intent().setData(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)));
                finish();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
