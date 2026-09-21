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

package com.kiroos.phone.incall;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.PhoneLookup;
import android.telecom.Call;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import com.kiroos.phone.R;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is calling: the contact name, number label and photo for a number, looked up in the
 * background once per number and cached for the life of the process.
 */
public final class CallerInfoCache {

    public static final class Info {
        public final String number;
        public final String name;
        public final String label;
        public final Bitmap photo;
        public final Uri contactUri;

        Info(String number, String name, String label, Bitmap photo, Uri contactUri) {
            this.number = number;
            this.name = name;
            this.label = label;
            this.photo = photo;
            this.contactUri = contactUri;
        }
    }

    public interface Callback {
        void onCallerInfo(String number, Info info);
    }

    private static final Map<String, Info> CACHE = new HashMap<>();
    private static final Set<String> PENDING = new HashSet<>();
    private static final Map<String, Set<Callback>> WAITING = new HashMap<>();

    private CallerInfoCache() {
    }

    /** The dialed or calling number of a call, or "" for private / unknown callers. */
    public static String numberOf(Call call) {
        final Call.Details d = call.getDetails();
        if (d == null || d.getHandle() == null
                || d.getHandlePresentation() != TelecomManager.PRESENTATION_ALLOWED) {
            return "";
        }
        final String n = d.getHandle().getSchemeSpecificPart();
        return n != null ? n : "";
    }

    /** Name to show for the call: contact, network-provided name, number or "private". */
    public static String displayName(Context context, Call call, Info info) {
        if (call.getChildren() != null && !call.getChildren().isEmpty()) {
            return context.getString(R.string.conference_call);
        }
        if (info != null && !TextUtils.isEmpty(info.name)) {
            return info.name;
        }
        final Call.Details d = call.getDetails();
        if (d != null && d.getCallerDisplayNamePresentation() == TelecomManager.PRESENTATION_ALLOWED
                && !TextUtils.isEmpty(d.getCallerDisplayName())) {
            return d.getCallerDisplayName();
        }
        final String number = numberOf(call);
        if (!TextUtils.isEmpty(number)) {
            return formatNumber(context, number);
        }
        if (d != null && d.getHandlePresentation() == TelecomManager.PRESENTATION_PAYPHONE) {
            return context.getString(R.string.payphone);
        }
        if (d != null && d.getHandlePresentation() == TelecomManager.PRESENTATION_UNKNOWN) {
            return context.getString(R.string.unknown_caller);
        }
        return context.getString(R.string.private_number);
    }

    public static String formatNumber(Context context, String number) {
        final String country = Locale.getDefault().getCountry();
        final String formatted = PhoneNumberUtils.formatNumber(number, country);
        return formatted != null ? formatted : number;
    }

    /** Cached info, or null; when missing a lookup starts and the callback gets the result. */
    public static Info get(Context context, final String number, Callback callback) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }
        if (CACHE.containsKey(number)) {
            return CACHE.get(number);
        }
        if (callback != null) {
            Set<Callback> set = WAITING.get(number);
            if (set == null) {
                set = new HashSet<>();
                WAITING.put(number, set);
            }
            set.add(callback);
        }
        if (PENDING.add(number)) {
            final Context app = context.getApplicationContext();
            new AsyncTask<Void, Void, Info>() {
                @Override
                protected Info doInBackground(Void... params) {
                    return lookup(app, number);
                }

                @Override
                protected void onPostExecute(Info info) {
                    PENDING.remove(number);
                    CACHE.put(number, info);
                    final Set<Callback> set = WAITING.remove(number);
                    if (set != null) {
                        for (Callback cb : set) {
                            cb.onCallerInfo(number, info);
                        }
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        return null;
    }

    public static void forget(Callback callback) {
        for (Set<Callback> set : WAITING.values()) {
            set.remove(callback);
        }
    }

    /** Contacts may change during a call (e.g. "add contact"); drop the cached entry. */
    public static void invalidate(String number) {
        CACHE.remove(number);
    }

    private static Info lookup(Context context, String number) {
        final Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        final String[] projection = {
                PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME,
                PhoneLookup.TYPE, PhoneLookup.LABEL, PhoneLookup.PHOTO_URI,
        };
        try (Cursor c = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return new Info(number, null, null, null, null);
            }
            final String name = c.getString(2);
            final CharSequence label = Phone.getTypeLabel(context.getResources(), c.getInt(3),
                    c.getString(4));
            final String photoUri = c.getString(5);
            final Uri contactUri = android.provider.ContactsContract.Contacts.getLookupUri(
                    c.getLong(0), c.getString(1));
            return new Info(number, name, label != null ? label.toString() : null,
                    loadPhoto(context, photoUri), contactUri);
        } catch (RuntimeException e) {
            // contacts not readable yet (locked user, missing permission)
            return new Info(number, null, null, null, null);
        }
    }

    private static Bitmap loadPhoto(Context context, String photoUri) {
        if (photoUri == null) {
            return null;
        }
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(photoUri))) {
            return in != null ? BitmapFactory.decodeStream(in) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
