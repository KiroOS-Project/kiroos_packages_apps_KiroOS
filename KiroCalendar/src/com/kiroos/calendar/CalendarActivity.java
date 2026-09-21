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

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** Month grid with event dots and the agenda of the selected day. */
public class CalendarActivity extends Activity implements MonthView.Listener {

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private MonthView mMonthView;
    private TextView mMonthTitle;
    private TextView mDayTitle;
    private TextView mEmpty;
    private final Calendar mSelected = Calendar.getInstance();
    private List<EventStore.Instance> mMonthInstances = Collections.emptyList();

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);
        mMonthView = (MonthView) findViewById(R.id.month);
        mMonthTitle = (TextView) findViewById(R.id.month_title);
        mDayTitle = (TextView) findViewById(R.id.day_title);
        mEmpty = (TextView) findViewById(R.id.empty);
        mMonthView.setListener(this);
        final ListView list = (ListView) findViewById(R.id.agenda);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final EventStore.Instance i = mAdapter.getItem(position);
                startActivity(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, i.eventId), CalendarActivity.this,
                        EventActivity.class)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, i.begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, i.end));
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMonthSwipe(-1);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMonthSwipe(1);
            }
        });
        findViewById(R.id.btn_today).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSelected.setTimeInMillis(System.currentTimeMillis());
                showMonth();
            }
        });
        final ImageButton add = (ImageButton) findViewById(R.id.btn_add);
        add.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // new event on the selected day at the next full hour
                final Calendar start = (Calendar) mSelected.clone();
                final Calendar now = Calendar.getInstance();
                start.set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY) + 1);
                start.set(Calendar.MINUTE, 0);
                start.set(Calendar.SECOND, 0);
                startActivity(new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI,
                        CalendarActivity.this, EditEventActivity.class)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.getTimeInMillis())
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                                start.getTimeInMillis() + 3600000));
            }
        });
        KiroTouch.apply(add);
        handleIntent(getIntent());
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR}, 0);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** content://com.android.calendar/time/<ms> opens that day */
    private void handleIntent(Intent intent) {
        final Uri data = intent.getData();
        if (data != null && "time".equals(data.getPathSegments().isEmpty() ? null
                : data.getPathSegments().get(0)) && data.getPathSegments().size() > 1) {
            try {
                mSelected.setTimeInMillis(Long.parseLong(data.getPathSegments().get(1)));
            } catch (NumberFormatException e) {
                // today
            }
        }
        showMonth();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        onStop();
        onStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            getContentResolver().registerContentObserver(CalendarContract.CONTENT_URI, true,
                    mObserver);
        } catch (SecurityException e) {
            // no permission yet
        }
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void onDaySelected(int year, int month, int day) {
        mSelected.set(year, month, day);
        showDay();
    }

    @Override
    public void onMonthSwipe(int delta) {
        mSelected.set(Calendar.DAY_OF_MONTH, 1);
        mSelected.add(Calendar.MONTH, delta);
        showMonth();
        mMonthView.setTranslationX(delta * 60 * getResources().getDisplayMetrics().density);
        mMonthView.setAlpha(0f);
        mMonthView.animate().translationX(0).alpha(1f).setDuration(220)
                .setInterpolator(KiroTouch.EASE).start();
    }

    private void showMonth() {
        mMonthTitle.setText(DateUtils.formatDateTime(this, mSelected.getTimeInMillis(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_MONTH_DAY
                        | DateUtils.FORMAT_SHOW_YEAR));
        mMonthView.setMonth(mSelected.get(Calendar.YEAR), mSelected.get(Calendar.MONTH),
                mSelected.get(Calendar.DAY_OF_MONTH));
        load();
    }

    private void load() {
        final Calendar from = (Calendar) mSelected.clone();
        from.set(Calendar.DAY_OF_MONTH, 1);
        from.set(Calendar.HOUR_OF_DAY, 0);
        from.set(Calendar.MINUTE, 0);
        from.set(Calendar.SECOND, 0);
        from.set(Calendar.MILLISECOND, 0);
        final Calendar to = (Calendar) from.clone();
        to.add(Calendar.MONTH, 1);
        final int year = from.get(Calendar.YEAR);
        final int month = from.get(Calendar.MONTH);
        // a day of margin each side: all-day events are stored in UTC
        final long start = from.getTimeInMillis() - DateUtils.DAY_IN_MILLIS;
        final long end = to.getTimeInMillis() + DateUtils.DAY_IN_MILLIS;
        new AsyncTask<Void, Void, List<EventStore.Instance>>() {
            @Override
            protected List<EventStore.Instance> doInBackground(Void... params) {
                return EventStore.instances(CalendarActivity.this, start, end);
            }

            @Override
            protected void onPostExecute(List<EventStore.Instance> list) {
                if (mSelected.get(Calendar.YEAR) != year || mSelected.get(Calendar.MONTH) != month) {
                    return; // the user moved on
                }
                mMonthInstances = list;
                mMonthView.setDots(MonthView.dotsFor(list, year, month));
                showDay();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showDay() {
        mDayTitle.setText(DateUtils.formatDateTime(this, mSelected.getTimeInMillis(),
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY));
        final Calendar dayStart = (Calendar) mSelected.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        final long s = dayStart.getTimeInMillis();
        final long e = s + DateUtils.DAY_IN_MILLIS;
        final List<EventStore.Instance> day = new ArrayList<>();
        for (EventStore.Instance i : mMonthInstances) {
            if (i.begin < e && (i.end > s || i.begin >= s)) {
                day.add(i);
            }
        }
        // all-day first
        Collections.sort(day, new java.util.Comparator<EventStore.Instance>() {
            @Override
            public int compare(EventStore.Instance a, EventStore.Instance b) {
                if (a.allDay != b.allDay) {
                    return a.allDay ? -1 : 1;
                }
                return Long.compare(a.begin, b.begin);
            }
        });
        mAdapter.set(day);
        mEmpty.setVisibility(day.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class Adapter extends BaseAdapter {
        private List<EventStore.Instance> mItems = Collections.emptyList();

        void set(List<EventStore.Instance> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public EventStore.Instance getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    CalendarActivity.this).inflate(R.layout.item_event, parent, false);
            final EventStore.Instance i = mItems.get(position);
            final GradientDrawable bar = new GradientDrawable();
            bar.setColor(i.color | 0xFF000000);
            bar.setCornerRadius(2 * getResources().getDisplayMetrics().density);
            v.findViewById(R.id.color).setBackground(bar);
            ((TextView) v.findViewById(R.id.title)).setText(TextUtils.isEmpty(i.title)
                    ? getString(R.string.no_title) : i.title);
            String when = i.allDay ? getString(R.string.all_day)
                    : DateFormat.getTimeFormat(CalendarActivity.this).format(i.begin) + " – "
                    + DateFormat.getTimeFormat(CalendarActivity.this).format(i.end);
            if (!TextUtils.isEmpty(i.location)) {
                when += " · " + i.location;
            }
            ((TextView) v.findViewById(R.id.when)).setText(when);
            return v;
        }
    }
}
