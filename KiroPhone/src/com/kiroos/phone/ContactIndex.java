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

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every phone number of every contact, kept in memory (a few hundred rows) so the keypad can
 * match names by their T9 digits and numbers by substring, and the Contacts tab can list and
 * filter without new queries. Reloads when the contacts change.
 */
final class ContactIndex {

    interface Listener {
        void onContactsLoaded();
    }

    /** One phone number of a contact. */
    static final class Entry {
        final long contactId;
        final String lookupKey;
        final String name;
        final String number;
        final String label;
        final String photoUri;
        final boolean starred;
        final boolean primary;
        /** lower case, accents removed */
        final String searchName;
        /** name in T9 digits, words separated by spaces */
        final String t9;
        /** number digits only */
        final String digits;

        Entry(long contactId, String lookupKey, String name, String number, String label,
                String photoUri, boolean starred, boolean primary) {
            this.contactId = contactId;
            this.lookupKey = lookupKey;
            this.name = name;
            this.number = number;
            this.label = label;
            this.photoUri = photoUri;
            this.starred = starred;
            this.primary = primary;
            this.searchName = normalize(name);
            this.t9 = toT9(searchName);
            this.digits = digitsOf(number);
        }

        Uri contactUri() {
            return ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
        }
    }

    /** A contact with all its numbers (the Contacts tab). */
    static final class Person {
        final Entry first;
        final List<Entry> numbers = new ArrayList<>();

        Person(Entry first) {
            this.first = first;
        }

        /** the default number if the contact has one set, else the first */
        Entry preferred() {
            for (Entry e : numbers) {
                if (e.primary) {
                    return e;
                }
            }
            return numbers.get(0);
        }
    }

    private static final String[] PROJECTION = {
            Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER,
            Phone.TYPE, Phone.LABEL, Phone.PHOTO_THUMBNAIL_URI, Phone.STARRED,
            Phone.IS_SUPER_PRIMARY,
    };

    private final Context mContext;
    private final List<Listener> mListeners = new ArrayList<>();
    private List<Entry> mEntries = Collections.emptyList();
    private List<Person> mPeople = Collections.emptyList();
    private boolean mLoading;
    private boolean mDirty;
    private boolean mLoaded;

    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    ContactIndex(Context context) {
        mContext = context.getApplicationContext();
    }

    void start() {
        try {
            mContext.getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, mObserver);
        } catch (SecurityException e) {
            // no contacts permission yet; load() is retried when it is granted
        }
        load();
    }

    void stop() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    void addListener(Listener l) {
        mListeners.add(l);
    }

    boolean isLoaded() {
        return mLoaded;
    }

    List<Person> people() {
        return mPeople;
    }

    void load() {
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        new AsyncTask<Void, Void, List<Entry>>() {
            @Override
            protected List<Entry> doInBackground(Void... params) {
                final List<Entry> out = new ArrayList<>();
                try (Cursor c = mContext.getContentResolver().query(Phone.CONTENT_URI, PROJECTION,
                        null, null, Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
                    if (c == null) {
                        return out;
                    }
                    while (c.moveToNext()) {
                        final String name = c.getString(2);
                        final String number = c.getString(3);
                        if (number == null) {
                            continue;
                        }
                        final CharSequence label = Phone.getTypeLabel(mContext.getResources(),
                                c.getInt(4), c.getString(5));
                        out.add(new Entry(c.getLong(0), c.getString(1),
                                name != null ? name : number, number,
                                label != null ? label.toString() : "", c.getString(6),
                                c.getInt(7) != 0, c.getInt(8) != 0));
                    }
                } catch (RuntimeException e) {
                    // permission missing or provider unavailable: empty index
                }
                return out;
            }

            @Override
            protected void onPostExecute(List<Entry> entries) {
                mEntries = entries;
                mPeople = group(entries);
                mLoading = false;
                mLoaded = true;
                for (Listener l : new ArrayList<>(mListeners)) {
                    l.onContactsLoaded();
                }
                if (mDirty) {
                    mDirty = false;
                    load();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static List<Person> group(List<Entry> entries) {
        final Map<Long, Person> byId = new LinkedHashMap<>();
        final List<Person> starred = new ArrayList<>();
        for (Entry e : entries) {
            Person p = byId.get(e.contactId);
            if (p == null) {
                p = new Person(e);
                byId.put(e.contactId, p);
            }
            // skip the same number stored twice (e.g. from two accounts)
            boolean dup = false;
            for (Entry o : p.numbers) {
                if (PhoneNumberUtils.compare(o.number, e.number)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                p.numbers.add(e);
            }
        }
        final List<Person> rest = new ArrayList<>();
        for (Person p : byId.values()) {
            (p.first.starred ? starred : rest).add(p);
        }
        starred.addAll(rest);
        return starred;
    }

    /** Contacts whose name or number matches the query (plain text search). */
    List<Person> search(String query) {
        final String q = normalize(query.trim());
        if (q.isEmpty()) {
            return mPeople;
        }
        final String qDigits = digitsOf(q);
        final List<Person> prefix = new ArrayList<>();
        final List<Person> other = new ArrayList<>();
        for (Person p : mPeople) {
            final String n = p.first.searchName;
            final int at = n.indexOf(q);
            if (at == 0 || (at > 0 && n.charAt(at - 1) == ' ')) {
                prefix.add(p);
            } else if (at > 0) {
                other.add(p);
            } else if (qDigits.length() >= 3) {
                for (Entry e : p.numbers) {
                    if (e.digits.contains(qDigits)) {
                        other.add(p);
                        break;
                    }
                }
            }
        }
        prefix.addAll(other);
        return prefix;
    }

    /** Keypad suggestions: T9 name prefix matches first, then number matches. */
    List<Entry> matchDialed(String dialed, int max) {
        final String d = digitsOf(dialed);
        final List<Entry> out = new ArrayList<>();
        if (d.isEmpty()) {
            return out;
        }
        final List<Entry> numberHits = new ArrayList<>();
        for (Entry e : mEntries) {
            if (out.size() >= max) {
                break;
            }
            if (t9Matches(e.t9, d)) {
                out.add(e);
            } else if (d.length() >= 3 && e.digits.contains(d) && numberHits.size() < max) {
                numberHits.add(e);
            }
        }
        for (Entry e : numberHits) {
            if (out.size() >= max) {
                break;
            }
            if (!out.contains(e)) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean t9Matches(String t9, String digits) {
        if (t9.replace(" ", "").startsWith(digits)) {
            return true;
        }
        int start = 0;
        while (start < t9.length()) {
            if (t9.startsWith(digits, start)) {
                return true;
            }
            final int space = t9.indexOf(' ', start);
            if (space < 0) {
                break;
            }
            start = space + 1;
        }
        return false;
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    static String digitsOf(String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static final String T9 = "22233344455566677778889999";

    static String toT9(String s) {
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                b.append(T9.charAt(c - 'a'));
            } else if (c >= '0' && c <= '9') {
                b.append(c);
            } else if (c == ' ' || c == '-' || c == '.' || c == '\'') {
                if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') {
                    b.append(' ');
                }
            }
        }
        return b.toString().trim();
    }
}
