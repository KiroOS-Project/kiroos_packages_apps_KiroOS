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

package com.kiroos.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * Kiro Phone 1.2: speed dial. Keys 2 to 9 each hold a contact's number: hold the key on an
 * empty keypad to call it (a free key offers to take a contact). More > Speed dial lists
 * them to assign, change or remove. Kept in the app's preferences.
 */
final class SpeedDial {

    /** onActivityResult request codes: REQUEST_BASE + the key */
    static final int REQUEST_BASE = 100;

    private SpeedDial() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("speed_dial", Context.MODE_PRIVATE);
    }

    static String number(Context context, int key) {
        return prefs(context).getString("n" + key, null);
    }

    static String name(Context context, int key) {
        return prefs(context).getString("c" + key, null);
    }

    static void set(Context context, int key, String name, String number) {
        prefs(context).edit().putString("n" + key, number).putString("c" + key, name).apply();
    }

    static void clear(Context context, int key) {
        prefs(context).edit().remove("n" + key).remove("c" + key).apply();
    }

    /** a contact's phone number for the key, from the contacts picker */
    static void pick(Activity activity, int key) {
        try {
            activity.startActivityForResult(new Intent(Intent.ACTION_PICK, Phone.CONTENT_URI),
                    REQUEST_BASE + key);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, R.string.no_contacts_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** the picker's result (a phone data row) */
    static void onPicked(Activity activity, int key, Uri data) {
        if (data == null) {
            return;
        }
        try (Cursor c = activity.getContentResolver().query(data,
                new String[] {Phone.DISPLAY_NAME, Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst() && !TextUtils.isEmpty(c.getString(1))) {
                final String name = TextUtils.isEmpty(c.getString(0)) ? c.getString(1) : c.getString(0);
                set(activity, key, name, c.getString(1));
                Toast.makeText(activity, activity.getString(R.string.speed_dial_set, key, name),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** a free key was held: offer to give it a contact */
    static void offer(final Activity activity, final int key) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.speed_dial)
                .setMessage(activity.getString(R.string.speed_dial_free, key))
                .setPositiveButton(R.string.assign, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        pick(activity, key);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** More > Speed dial: keys 2 to 9 and their contacts */
    static void showManager(final Activity activity) {
        final String[] rows = new String[8];
        for (int key = 2; key <= 9; key++) {
            final String name = name(activity, key);
            rows[key - 2] = activity.getString(R.string.speed_dial_row, key,
                    name != null ? name : activity.getString(R.string.speed_dial_none));
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.speed_dial)
                .setItems(rows, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final int key = which + 2;
                        if (number(activity, key) == null) {
                            pick(activity, key);
                            return;
                        }
                        new AlertDialog.Builder(activity)
                                .setTitle(activity.getString(R.string.speed_dial_row, key,
                                        name(activity, key)))
                                .setItems(new String[] {activity.getString(R.string.change),
                                        activity.getString(R.string.remove)},
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d2, int w) {
                                                if (w == 0) {
                                                    pick(activity, key);
                                                } else {
                                                    clear(activity, key);
                                                }
                                            }
                                        })
                                .show();
                    }
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
