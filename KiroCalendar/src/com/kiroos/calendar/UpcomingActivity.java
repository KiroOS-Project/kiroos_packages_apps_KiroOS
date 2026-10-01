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
import android.content.ContentUris;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.CalendarContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Kiro Calendar 1.2: Upcoming. Every event from today to a year from now, under the day it
 * falls on (Today, Tomorrow, then the date), and a search over titles, places and notes.
 * Follows the calendar provider live, like the month.
 */
public class UpcomingActivity extends Activity {

    private static final long RANGE = 366 * DateUtils.DAY_IN_MILLIS;

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private List<EventStore.Instance> mAll = Collections.emptyList();
    private EditText mSearch;
    private TextView mEmpty;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_upcoming);
        mSearch = (EditText) findViewById(R.id.search);
        mEmpty = (TextView) findViewById(R.id.empty);
        final ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Object o = mAdapter.getItem(position);
                if (!(o instanceof EventStore.Instance)) {
                    return;
                }
                final EventStore.Instance i = (EventStore.Instance) o;
                startActivity(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, i.eventId), UpcomingActivity.this,
                        EventActivity.class)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, i.begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, i.end));
            }
        });
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true,
                    mObserver);
        } catch (SecurityException e) {
            // no permission: the list stays empty
        }
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    private static long startOfToday() {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void load() {
        final long from = startOfToday();
        new AsyncTask<Void, Void, List<EventStore.Instance>>() {
            @Override
            protected List<EventStore.Instance> doInBackground(Void... params) {
                // a day of margin before: all-day events are stored in UTC
                final List<EventStore.Instance> list = EventStore.instances(UpcomingActivity.this,
                        from - DateUtils.DAY_IN_MILLIS, from + RANGE);
                final long now = System.currentTimeMillis();
                final List<EventStore.Instance> out = new ArrayList<>();
                for (EventStore.Instance i : list) {
                    // what is over is left out; what is on now stays
                    if (i.allDay ? i.end > from : i.end > now) {
                        out.add(i);
                    }
                }
                return out;
            }

            @Override
            protected void onPostExecute(List<EventStore.Instance> list) {
                if (isDestroyed()) {
                    return;
                }
                mAll = list;
                filter();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static boolean contains(String field, String q) {
        return field != null && field.toLowerCase(Locale.getDefault()).contains(q);
    }

    private void filter() {
        final String q = mSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
        final List<Object> rows = new ArrayList<>();
        long day = Long.MIN_VALUE;
        for (EventStore.Instance i : mAll) {
            if (!q.isEmpty() && !contains(i.title, q) && !contains(i.location, q)
                    && !contains(i.description, q)) {
                continue;
            }
            // an event that started before today is listed under today
            final long d = dayOf(Math.max(i.begin, startOfToday()));
            if (d != day) {
                rows.add(d);
                day = d;
            }
            rows.add(i);
        }
        mAdapter.set(rows);
        mEmpty.setText(q.isEmpty() ? R.string.no_upcoming : R.string.no_results);
        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static long dayOf(long t) {
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private String dayLabel(long day) {
        final long today = startOfToday();
        if (day == today) {
            return getString(R.string.today);
        }
        if (day == dayOf(today + DateUtils.DAY_IN_MILLIS + DateUtils.HOUR_IN_MILLIS)) {
            return getString(R.string.tomorrow);
        }
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(day);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY;
        if (c.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR)) {
            flags |= DateUtils.FORMAT_SHOW_YEAR;
        }
        return DateUtils.formatDateTime(this, day, flags);
    }

    private final class Adapter extends BaseAdapter {
        private List<Object> mRows = Collections.emptyList();

        void set(List<Object> rows) {
            mRows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position) instanceof Long ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) == 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Object o = mRows.get(position);
            final LayoutInflater inflater = LayoutInflater.from(UpcomingActivity.this);
            if (o instanceof Long) {
                final TextView h = (TextView) (convertView != null ? convertView
                        : inflater.inflate(R.layout.item_day_header, parent, false));
                h.setText(dayLabel((Long) o));
                return h;
            }
            final View v = convertView != null ? convertView
                    : inflater.inflate(R.layout.item_event, parent, false);
            final EventStore.Instance i = (EventStore.Instance) o;
            final GradientDrawable bar = new GradientDrawable();
            bar.setColor(i.color | 0xFF000000);
            bar.setCornerRadius(2 * getResources().getDisplayMetrics().density);
            v.findViewById(R.id.color).setBackground(bar);
            ((TextView) v.findViewById(R.id.title)).setText(TextUtils.isEmpty(i.title)
                    ? getString(R.string.no_title) : i.title);
            String when = i.allDay ? getString(R.string.all_day)
                    : DateFormat.getTimeFormat(UpcomingActivity.this).format(i.begin) + " – "
                    + DateFormat.getTimeFormat(UpcomingActivity.this).format(i.end);
            if (!TextUtils.isEmpty(i.location)) {
                when += " · " + i.location;
            }
            ((TextView) v.findViewById(R.id.when)).setText(when);
            return v;
        }
    }
}
