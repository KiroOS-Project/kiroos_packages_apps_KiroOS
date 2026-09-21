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
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** One event: when, where, notes, calendar and reminder; edit or delete. */
public class EventActivity extends Activity {

    private long mId = -1;
    private EventStore.Event mEvent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event);
        final Uri data = getIntent().getData();
        if (data != null) {
            try {
                mId = ContentUris.parseId(data);
            } catch (RuntimeException e) {
                mId = -1;
            }
        }
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_EDIT, ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, mId), EventActivity.this,
                        EditEventActivity.class));
            }
        });
        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        new AsyncTask<Void, Void, EventStore.Event>() {
            @Override
            protected EventStore.Event doInBackground(Void... params) {
                return mId >= 0 ? EventStore.load(EventActivity.this, mId) : null;
            }

            @Override
            protected void onPostExecute(EventStore.Event e) {
                if (e == null) {
                    Toast.makeText(EventActivity.this, R.string.event_missing,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                mEvent = e;
                bind();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void bind() {
        final EventStore.Event e = mEvent;
        ((TextView) findViewById(R.id.title)).setText(TextUtils.isEmpty(e.title)
                ? getString(R.string.no_title) : e.title);
        // the occurrence that was tapped, for repeating events
        final long begin = getIntent().getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start);
        final long end = getIntent().getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_SHOW_YEAR;
        if (!e.allDay) {
            flags |= DateUtils.FORMAT_SHOW_TIME;
        }
        ((TextView) findViewById(R.id.when)).setText(DateUtils.formatDateRange(this, begin,
                e.allDay ? Math.max(begin, end - 1) : end, flags));
        setRow(R.id.row_repeat, R.id.repeat, e.rrule == null ? null : repeatText(e.rrule));
        setRow(R.id.row_location, R.id.location, e.location);
        setRow(R.id.row_description, R.id.description, e.description);
        setRow(R.id.row_calendar, R.id.calendar, e.calendarName);
        setRow(R.id.row_reminder, R.id.reminder, e.reminder < 0 ? null
                : EditEventActivity.reminderText(this, e.reminder));
        findViewById(R.id.row_location).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("geo:0,0?q=" + Uri.encode(mEvent.location))));
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(EventActivity.this, R.string.no_maps, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private String repeatText(String rrule) {
        if (rrule.contains("FREQ=DAILY")) {
            return getString(R.string.repeat_daily);
        }
        if (rrule.contains("FREQ=WEEKLY")) {
            return getString(R.string.repeat_weekly);
        }
        if (rrule.contains("FREQ=MONTHLY")) {
            return getString(R.string.repeat_monthly);
        }
        if (rrule.contains("FREQ=YEARLY")) {
            return getString(R.string.repeat_yearly);
        }
        return rrule;
    }

    private void setRow(int row, int text, String value) {
        findViewById(row).setVisibility(TextUtils.isEmpty(value) ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(text)).setText(value);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(mEvent != null && mEvent.rrule != null ? R.string.delete_series
                        : R.string.delete_event)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        EventStore.delete(EventActivity.this, mId);
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
