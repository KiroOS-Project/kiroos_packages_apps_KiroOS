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
    /** 1.2: the occurrences up to this time (ms) are skipped ("Dismiss now", "Skip next") */
    long skipUntil;
    /** 1.2: how long Snooze waits */
    int snoozeMinutes = 10;

    /** Next time this alarm rings after {@code now} (a snooze included), or -1 if disabled. */
    long nextTime(long now) {
        long t = nextOccurrence(now);
        if (t > 0 && snoozeUntil > now && snoozeUntil < t) {
            t = snoozeUntil;
        }
        return t;
    }

    /** Next scheduled time after {@code now}, snooze left out (what "Skip next" passes over). */
    long nextOccurrence(long now) {
        if (!enabled) {
            return -1;
        }
        // a skipped occurrence is passed over: the next one after it
        final long base = Math.max(now, skipUntil);
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= base) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (days != 0) {
            for (int i = 0; i < 7 && (days & (1 << dayIndex(c))) == 0; i++) {
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
        }
        return c.getTimeInMillis();
    }

    /**
     * "Dismiss now" / "Skip next": a snoozed alarm stops being snoozed; otherwise its next
     * occurrence is passed over (a one-time alarm is turned off).
     */
    void skipNext(long now) {
        if (snoozeUntil > now) {
            snoozeUntil = 0;
            if (days == 0) {
                enabled = false;
            }
            return;
        }
        if (days == 0) {
            enabled = false;
        } else {
            skipUntil = nextOccurrence(now);
        }
    }

    /** Monday = 0 … Sunday = 6 */
    static int dayIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    // --- storage --------------------------------------------------------------------------

    private static final class Db extends SQLiteOpenHelper {
        Db(Context context) {
            super(context.createDeviceProtectedStorageContext(), "alarms.db", null, 2);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE alarms (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "hour INTEGER, minute INTEGER, days INTEGER, enabled INTEGER, "
                    + "vibrate INTEGER, label TEXT, ringtone TEXT, snooze INTEGER DEFAULT 0, "
                    + "skip INTEGER DEFAULT 0, snooze_min INTEGER DEFAULT 10)");
            // a first alarm, off, as a starting point
            db.execSQL("INSERT INTO alarms (hour, minute, days, enabled, vibrate, label) "
                    + "VALUES (7, 0, 31, 0, 1, '')");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                // 1.2: skipped occurrences and the snooze length; existing alarms keep 10 min
                db.execSQL("ALTER TABLE alarms ADD COLUMN skip INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE alarms ADD COLUMN snooze_min INTEGER DEFAULT 10");
            }
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
        a.skipUntil = c.getLong(c.getColumnIndexOrThrow("skip"));
        a.snoozeMinutes = Math.max(1, c.getInt(c.getColumnIndexOrThrow("snooze_min")));
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
        v.put("skip", skipUntil);
        v.put("snooze_min", snoozeMinutes);
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
