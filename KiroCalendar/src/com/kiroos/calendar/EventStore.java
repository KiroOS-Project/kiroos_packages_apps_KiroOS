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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.provider.CalendarContract.Reminders;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Calendars, event instances and reminders through the platform CalendarProvider. */
final class EventStore {

    static final class Instance {
        long eventId;
        long begin;
        long end;
        boolean allDay;
        String title;
        String location;
        int color;
    }

    static final class Cal {
        long id;
        String name;
        String account;
        int color;
    }

    static final class Event {
        long id = -1;
        long calendarId = -1;
        String title = "";
        String location = "";
        String description = "";
        long start;
        long end;
        boolean allDay;
        String rrule;
        String calendarName;
        int color;
        /** minutes before, -1 = none */
        int reminder = 15;
    }

    private static final String LOCAL_ACCOUNT = "KiroOS";

    private EventStore() {
    }

    /** Every occurrence (repeats expanded) between two times, earliest first. */
    static List<Instance> instances(Context context, long from, long to) {
        final List<Instance> out = new ArrayList<>();
        final Uri.Builder b = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        try (Cursor c = context.getContentResolver().query(b.build(), new String[] {
                Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY,
                Instances.TITLE, Instances.EVENT_LOCATION, Instances.DISPLAY_COLOR},
                Instances.VISIBLE + "=1", null, Instances.BEGIN + " ASC")) {
            while (c != null && c.moveToNext()) {
                final Instance i = new Instance();
                i.eventId = c.getLong(0);
                i.begin = c.getLong(1);
                i.end = c.getLong(2);
                i.allDay = c.getInt(3) != 0;
                i.title = c.getString(4);
                i.location = c.getString(5);
                i.color = c.getInt(6);
                if (i.allDay) {
                    // all-day events are stored in UTC: show them on their local day
                    final int offset = TimeZone.getDefault().getOffset(i.begin);
                    i.begin -= offset;
                    i.end -= offset;
                }
                out.add(i);
            }
        } catch (RuntimeException e) {
            // no calendar permission yet
        }
        return out;
    }

    static List<Cal> writableCalendars(Context context) {
        final List<Cal> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(Calendars.CONTENT_URI, new String[] {
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_COLOR},
                Calendars.CALENDAR_ACCESS_LEVEL + ">=" + Calendars.CAL_ACCESS_CONTRIBUTOR
                        + " AND " + Calendars.VISIBLE + "=1", null, Calendars._ID)) {
            while (c != null && c.moveToNext()) {
                final Cal cal = new Cal();
                cal.id = c.getLong(0);
                cal.name = c.getString(1);
                cal.account = c.getString(2);
                cal.color = c.getInt(3);
                out.add(cal);
            }
        } catch (RuntimeException e) {
            // no permission
        }
        return out;
    }

    /** A calendar on the phone itself for people without an online account. */
    static long ensureLocalCalendar(Context context, String displayName, int color) {
        final List<Cal> cals = writableCalendars(context);
        if (!cals.isEmpty()) {
            return cals.get(0).id;
        }
        final Uri uri = Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        final ContentValues v = new ContentValues();
        v.put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT);
        v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(Calendars.NAME, LOCAL_ACCOUNT);
        v.put(Calendars.CALENDAR_DISPLAY_NAME, displayName);
        v.put(Calendars.CALENDAR_COLOR, color);
        v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
        v.put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT);
        v.put(Calendars.VISIBLE, 1);
        v.put(Calendars.SYNC_EVENTS, 1);
        v.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());
        try {
            final Uri created = context.getContentResolver().insert(uri, v);
            return created != null ? ContentUris.parseId(created) : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static Event load(Context context, long id) {
        try (Cursor c = context.getContentResolver().query(
                ContentUris.withAppendedId(Events.CONTENT_URI, id), new String[] {
                        Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.EVENT_LOCATION,
                        Events.DESCRIPTION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
                        Events.RRULE, Events.DURATION, Events.CALENDAR_DISPLAY_NAME,
                        Events.DISPLAY_COLOR}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            final Event e = new Event();
            e.id = c.getLong(0);
            e.calendarId = c.getLong(1);
            e.title = nz(c.getString(2));
            e.location = nz(c.getString(3));
            e.description = nz(c.getString(4));
            e.start = c.getLong(5);
            e.end = c.isNull(6) ? e.start + durationMs(c.getString(9)) : c.getLong(6);
            e.allDay = c.getInt(7) != 0;
            e.rrule = c.getString(8);
            e.calendarName = c.getString(10);
            e.color = c.getInt(11);
            if (e.allDay) {
                final int offset = TimeZone.getDefault().getOffset(e.start);
                e.start -= offset;
                e.end -= offset;
            }
            e.reminder = -1;
            try (Cursor r = context.getContentResolver().query(Reminders.CONTENT_URI,
                    new String[] {Reminders.MINUTES}, Reminders.EVENT_ID + "=?",
                    new String[] {String.valueOf(id)}, Reminders.MINUTES + " ASC")) {
                if (r != null && r.moveToFirst()) {
                    e.reminder = r.getInt(0);
                }
            }
            return e;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** RFC 2445 duration "P3600S" / "PT1H" / "P1D" in ms (repeating events have no DTEND). */
    static long durationMs(String d) {
        if (d == null) {
            return 3600000;
        }
        try {
            final java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
                    .matcher(d);
            if (!m.matches()) {
                return 3600000;
            }
            long ms = 0;
            final long[] unit = {7L * 86400000, 86400000, 3600000, 60000, 1000};
            for (int i = 0; i < 5; i++) {
                if (m.group(i + 1) != null) {
                    ms += Long.parseLong(m.group(i + 1)) * unit[i];
                }
            }
            return ms;
        } catch (RuntimeException e) {
            return 3600000;
        }
    }

    /** Inserts or updates the event and its (single) reminder. */
    static long save(Context context, Event e) {
        final ContentValues v = new ContentValues();
        v.put(Events.CALENDAR_ID, e.calendarId);
        v.put(Events.TITLE, e.title);
        v.put(Events.EVENT_LOCATION, e.location);
        v.put(Events.DESCRIPTION, e.description);
        v.put(Events.ALL_DAY, e.allDay ? 1 : 0);
        if (e.allDay) {
            // all-day: UTC midnight to UTC midnight
            final int offset = TimeZone.getDefault().getOffset(e.start);
            v.put(Events.DTSTART, e.start + offset);
            v.put(Events.EVENT_TIMEZONE, "UTC");
            final long endUtc = e.end + TimeZone.getDefault().getOffset(e.end);
            if (e.rrule == null) {
                v.put(Events.DTEND, endUtc);
            } else {
                v.put(Events.DURATION, "P" + Math.max(1, (endUtc - (e.start + offset)) / 86400000) + "D");
            }
        } else {
            v.put(Events.DTSTART, e.start);
            v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            if (e.rrule == null) {
                v.put(Events.DTEND, e.end);
            } else {
                v.put(Events.DURATION, "P" + Math.max(60, (e.end - e.start) / 1000) + "S");
            }
        }
        if (e.rrule == null) {
            v.putNull(Events.RRULE);
            if (e.id >= 0) {
                v.putNull(Events.DURATION);
            }
        } else {
            v.put(Events.RRULE, e.rrule);
            if (e.id >= 0) {
                v.putNull(Events.DTEND);
            }
        }
        v.put(Events.HAS_ALARM, e.reminder >= 0 ? 1 : 0);
        long id = e.id;
        if (id < 0) {
            final Uri uri = context.getContentResolver().insert(Events.CONTENT_URI, v);
            id = uri != null ? ContentUris.parseId(uri) : -1;
        } else {
            context.getContentResolver().update(
                    ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null);
            context.getContentResolver().delete(Reminders.CONTENT_URI,
                    Reminders.EVENT_ID + "=?", new String[] {String.valueOf(id)});
        }
        if (id >= 0 && e.reminder >= 0) {
            final ContentValues r = new ContentValues();
            r.put(Reminders.EVENT_ID, id);
            r.put(Reminders.MINUTES, e.reminder);
            r.put(Reminders.METHOD, Reminders.METHOD_ALERT);
            context.getContentResolver().insert(Reminders.CONTENT_URI, r);
        }
        return id;
    }

    static void delete(Context context, long id) {
        context.getContentResolver().delete(ContentUris.withAppendedId(Events.CONTENT_URI, id),
                null, null);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
