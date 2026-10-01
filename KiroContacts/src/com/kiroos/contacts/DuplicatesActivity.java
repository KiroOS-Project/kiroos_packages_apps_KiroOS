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

package com.kiroos.contacts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.DialogInterface;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.AggregationExceptions;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.RawContacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kiro Contacts 1.2: duplicates. Contacts with the same name (ignoring case and accents) or
 * the same phone number (its last 9 digits) are grouped; Merge joins a group into one contact
 * the way the system does it (AggregationExceptions, keep together): nothing is deleted, the
 * accounts keep their own copies and the contact can still be separated.
 */
public class DuplicatesActivity extends Activity {

    private static final String TAG = "KiroContacts";

    static final class Person {
        long id;
        String name;
        final Set<String> numbers = new LinkedHashSet<>();
    }

    private final Adapter mAdapter = new Adapter();
    private TextView mEmpty;
    private Button mMergeAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duplicates);
        mEmpty = (TextView) findViewById(R.id.empty);
        mMergeAll = (Button) findViewById(R.id.btn_merge_all);
        ((ListView) findViewById(R.id.list)).setAdapter(mAdapter);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mMergeAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final List<List<Person>> all = new ArrayList<>(mAdapter.mGroups);
                new AlertDialog.Builder(DuplicatesActivity.this)
                        .setMessage(getResources().getQuantityString(R.plurals.merge_all_confirm,
                                all.size(), all.size()))
                        .setPositiveButton(R.string.merge, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                merge(all);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        KiroTouch.apply(mMergeAll);
        load();
    }

    private void load() {
        new AsyncTask<Void, Void, List<List<Person>>>() {
            @Override
            protected List<List<Person>> doInBackground(Void... params) {
                try {
                    return findGroups();
                } catch (RuntimeException e) {
                    Log.w(TAG, "duplicates", e);
                    return new ArrayList<>();
                }
            }

            @Override
            protected void onPostExecute(List<List<Person>> groups) {
                if (isDestroyed()) {
                    return;
                }
                mAdapter.set(groups);
                mEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                mMergeAll.setVisibility(groups.size() > 1 ? View.VISIBLE : View.GONE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** "Mario  Rossì" and "mario rossi" are the same name */
    private static String nameKey(String name) {
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        final String s = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return s.isEmpty() ? null : s;
    }

    /** +39 333 123 4567 and 3331234567 are the same number: the last 9 digits */
    private static String numberKey(String number) {
        final String n = PhoneNumberUtils.normalizeNumber(number);
        if (n == null) {
            return null;
        }
        final String digits = n.replaceAll("[^0-9]", "");
        if (digits.length() < 6) {
            return null; // short codes and service numbers are shared on purpose
        }
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }

    private List<List<Person>> findGroups() {
        final Map<Long, Person> people = new LinkedHashMap<>();
        try (Cursor c = getContentResolver().query(Contacts.CONTENT_URI, new String[] {
                Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY}, null, null,
                Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED")) {
            while (c != null && c.moveToNext()) {
                final Person p = new Person();
                p.id = c.getLong(0);
                p.name = c.getString(1);
                people.put(p.id, p);
            }
        }
        try (Cursor c = getContentResolver().query(Phone.CONTENT_URI, new String[] {
                Phone.CONTACT_ID, Phone.NUMBER}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                final Person p = people.get(c.getLong(0));
                if (p != null && !TextUtils.isEmpty(c.getString(1))) {
                    p.numbers.add(c.getString(1));
                }
            }
        }

        // union-find over contacts sharing a name or a number
        final Map<Long, Long> parent = new HashMap<>();
        for (Long id : people.keySet()) {
            parent.put(id, id);
        }
        final Map<String, Long> seen = new HashMap<>();
        for (Person p : people.values()) {
            final List<String> keys = new ArrayList<>();
            final String nk = nameKey(p.name);
            if (nk != null) {
                keys.add("n:" + nk);
            }
            for (String number : p.numbers) {
                final String k = numberKey(number);
                if (k != null) {
                    keys.add("p:" + k);
                }
            }
            for (String k : keys) {
                final Long other = seen.get(k);
                if (other == null) {
                    seen.put(k, p.id);
                } else {
                    union(parent, other, p.id);
                }
            }
        }
        final Map<Long, List<Person>> byRoot = new LinkedHashMap<>();
        for (Person p : people.values()) {
            final long root = find(parent, p.id);
            List<Person> g = byRoot.get(root);
            if (g == null) {
                g = new ArrayList<>();
                byRoot.put(root, g);
            }
            g.add(p);
        }
        final List<List<Person>> groups = new ArrayList<>();
        for (List<Person> g : byRoot.values()) {
            if (g.size() > 1) {
                groups.add(g);
            }
        }
        return groups;
    }

    private static long find(Map<Long, Long> parent, long x) {
        long r = x;
        while (parent.get(r) != r) {
            r = parent.get(r);
        }
        // path compression
        long y = x;
        while (parent.get(y) != r) {
            final long next = parent.get(y);
            parent.put(y, r);
            y = next;
        }
        return r;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        final long ra = find(parent, a);
        final long rb = find(parent, b);
        if (ra != rb) {
            parent.put(rb, ra);
        }
    }

    /** joins every raw contact of each group to the group's first one */
    private void merge(final List<List<Person>> groups) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                try {
                    final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                    for (List<Person> g : groups) {
                        final List<Long> raws = rawIds(g);
                        for (int i = 1; i < raws.size(); i++) {
                            ops.add(ContentProviderOperation
                                    .newUpdate(AggregationExceptions.CONTENT_URI)
                                    .withValue(AggregationExceptions.TYPE,
                                            AggregationExceptions.TYPE_KEEP_TOGETHER)
                                    .withValue(AggregationExceptions.RAW_CONTACT_ID1, raws.get(0))
                                    .withValue(AggregationExceptions.RAW_CONTACT_ID2, raws.get(i))
                                    .build());
                        }
                    }
                    if (!ops.isEmpty()) {
                        getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
                    }
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "merge", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (isDestroyed()) {
                    return;
                }
                Toast.makeText(DuplicatesActivity.this, ok ? R.string.merged : R.string.merge_failed,
                        Toast.LENGTH_SHORT).show();
                load();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private List<Long> rawIds(List<Person> group) {
        final StringBuilder in = new StringBuilder();
        for (Person p : group) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(p.id);
        }
        final List<Long> out = new ArrayList<>();
        try (Cursor c = getContentResolver().query(RawContacts.CONTENT_URI,
                new String[] {RawContacts._ID}, RawContacts.CONTACT_ID + " IN (" + in + ") AND "
                        + RawContacts.DELETED + "=0", null, RawContacts._ID)) {
            while (c != null && c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    private final class Adapter extends BaseAdapter {
        private List<List<Person>> mGroups = Collections.emptyList();

        void set(List<List<Person>> groups) {
            mGroups = groups;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mGroups.size();
        }

        @Override
        public List<Person> getItem(int position) {
            return mGroups.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    DuplicatesActivity.this).inflate(R.layout.item_duplicate, parent, false);
            final List<Person> g = mGroups.get(position);
            final StringBuilder names = new StringBuilder();
            final Set<String> numbers = new LinkedHashSet<>();
            for (Person p : g) {
                if (names.length() > 0) {
                    names.append('\n');
                }
                names.append(TextUtils.isEmpty(p.name) ? getString(R.string.no_name) : p.name);
                numbers.addAll(p.numbers);
            }
            ((TextView) v.findViewById(R.id.names)).setText(names);
            final TextView nums = (TextView) v.findViewById(R.id.numbers);
            nums.setText(TextUtils.join(" · ", numbers));
            nums.setVisibility(numbers.isEmpty() ? View.GONE : View.VISIBLE);
            final Button merge = (Button) v.findViewById(R.id.btn_merge);
            merge.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    merge(Collections.singletonList(g));
                }
            });
            KiroTouch.apply(merge);
            return v;
        }
    }
}
