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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * An alarm and its storage (SQLite in device-protected storage, so alarms ring after a
 * reboot even before the phone is unlocked).
 */
final class Alarm {

    long id = -1;
    int hour = 7;
    int minute = 0;
    /** bit 0 = Monday … bit 6 = Sunday; 0 = once */
    int days;
    boolean enabled = true;
    boolean vibrate = true;
    String label = "";
    /** ringtone uri, "" = silent, null = default alarm sound */
    String ringtone;
    /** snoozed until this time (ms), 0 if not snoozed */
    long snoozeUntil;

    /** Next time this alarm rings after {@code now}, or -1 if disabled. */
    long nextTime(long now) {
        if (!enabled) {
            return -1;
        }
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (days != 0) {
            for (int i = 0; i < 7 && (days & (1 << dayIndex(c))) == 0; i++) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        long t = c.getTimeInMillis();
        if (snoozeUntil > now && snoozeUntil < t) {
            t = snoozeUntil;
        }
        return t;
    }

    /** Monday = 0 … Sunday = 6 */
    static int dayIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    // --- storage --------------------------------------------------------------------------

    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) {
            super(context.createDeviceProtectedStorageContext(), "alarms.db", null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE alarms (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "hour INTEGER, minute INTEGER, days INTEGER, enabled INTEGER, "
                    + "vibrate INTEGER, label TEXT, ringtone TEXT, snooze INTEGER DEFAULT 0)");
            // a first alarm, off, as a starting point
            db.execSQL("INSERT INTO alarms (hour, minute, days, enabled, vibrate, label) "
                    + "VALUES (7, 0, 31, 0, 1, '')");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }

    private static Db sDb;

    private static synchronized SQLiteDatabase db(Context context) {
        if (sDb == null) {
            sDb = new Db(context.getApplicationContext());
        }
        return sDb.getWritableDatabase();
    }

    static List<Alarm> all(Context context) {
        final List<Alarm> out = new ArrayList<>();
        try (Cursor c = db(context).query("alarms", null, null, null, null, null,
                "hour, minute")) {
            while (c.moveToNext()) {
                out.add(from(c));
            }
        }
        return out;
    }

    static Alarm get(Context context, long id) {
        try (Cursor c = db(context).query("alarms", null, "_id=" + id, null, null, null, null)) {
            return c.moveToFirst() ? from(c) : null;
        }
    }

    private static Alarm from(Cursor c) {
        final Alarm a = new Alarm();
        a.id = c.getLong(c.getColumnIndexOrThrow("_id"));
        a.hour = c.getInt(c.getColumnIndexOrThrow("hour"));
        a.minute = c.getInt(c.getColumnIndexOrThrow("minute"));
        a.days = c.getInt(c.getColumnIndexOrThrow("days"));
        a.enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) != 0;
        a.vibrate = c.getInt(c.getColumnIndexOrThrow("vibrate")) != 0;
        a.label = c.getString(c.getColumnIndexOrThrow("label"));
        a.ringtone = c.getString(c.getColumnIndexOrThrow("ringtone"));
        a.snoozeUntil = c.getLong(c.getColumnIndexOrThrow("snooze"));
        return a;
    }

    void save(Context context) {
        final ContentValues v = new ContentValues();
        v.put("hour", hour);
        v.put("minute", minute);
        v.put("days", days);
        v.put("enabled", enabled ? 1 : 0);
        v.put("vibrate", vibrate ? 1 : 0);
        v.put("label", label != null ? label : "");
        v.put("ringtone", ringtone);
        v.put("snooze", snoozeUntil);
        if (id < 0) {
            id = db(context).insert("alarms", null, v);
        } else {
            db(context).update("alarms", v, "_id=" + id, null);
        }
    }

    void delete(Context context) {
        db(context).delete("alarms", "_id=" + id, null);
    }
}
