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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SectionIndexer;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All contacts (favourites first) with search and fast scroll. Also the picker other apps
 * open with PICK / GET_CONTENT for a contact, a phone number or an e-mail address.
 * 1.2: More > Merge duplicates (DuplicatesActivity).
 */
public class ContactsActivity extends Activity {

    private static final int MODE_BROWSE = 0;
    private static final int MODE_PICK_CONTACT = 1;
    private static final int MODE_PICK_PHONE = 2;
    private static final int MODE_PICK_EMAIL = 3;
    private static final int REQUEST_IMPORT = 1;

    static final class Row {
        long id;
        Uri uri;
        String name;
        String detail;
        String photo;
        boolean starred;
    }

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private EditText mSearch;
    private TextView mEmpty;
    private int mMode = MODE_BROWSE;
    private boolean mLoading;
    private boolean mDirty;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contacts);
        mSearch = (EditText) findViewById(R.id.search);
        mEmpty = (TextView) findViewById(R.id.empty);
        mMode = modeFor(getIntent());
        final TextView title = (TextView) findViewById(R.id.title);
        title.setText(mMode == MODE_BROWSE ? R.string.app_name : R.string.choose);

        final ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Row r = mAdapter.getItem(position);
                if (mMode == MODE_BROWSE) {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.uri, ContactsActivity.this,
                            ContactDetailActivity.class));
                } else {
                    setResult(RESULT_OK, new Intent().setData(r.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    finish();
                }
            }
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                load();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        final ImageButton add = (ImageButton) findViewById(R.id.btn_add);
        final View more = findViewById(R.id.btn_more);
        if (mMode == MODE_BROWSE) {
            add.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                    getColor(R.color.kiro_ripple)));
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Intent.ACTION_INSERT, Contacts.CONTENT_URI,
                            ContactsActivity.this, ContactEditActivity.class));
                }
            });
            KiroTouch.apply(add);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMenu(v);
                }
            });
        } else {
            add.setVisibility(View.GONE);
            more.setVisibility(View.GONE);
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS}, 0);
        }
    }

    private static int modeFor(Intent intent) {
        final String action = intent.getAction();
        if (!Intent.ACTION_PICK.equals(action) && !Intent.ACTION_GET_CONTENT.equals(action)) {
            return MODE_BROWSE;
        }
        String type = intent.getType();
        final Uri data = intent.getData();
        if (type == null && data != null) {
            if (data.toString().startsWith(Phone.CONTENT_URI.toString())) {
                type = Phone.CONTENT_TYPE;
            } else if (data.toString().startsWith(Email.CONTENT_URI.toString())) {
                type = Email.CONTENT_TYPE;
            }
        }
        if (Phone.CONTENT_TYPE.equals(type) || Phone.CONTENT_ITEM_TYPE.equals(type)) {
            return MODE_PICK_PHONE;
        }
        if (Email.CONTENT_TYPE.equals(type) || Email.CONTENT_ITEM_TYPE.equals(type)) {
            return MODE_PICK_EMAIL;
        }
        return MODE_PICK_CONTACT;
    }

    @Override
    protected void onStart() {
        super.onStart();
        watch();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // just granted: start watching and load (never call the lifecycle methods by hand)
        watch();
    }

    /** (re)registers the observer on the contacts provider and loads the list */
    private void watch() {
        getContentResolver().unregisterContentObserver(mObserver);
        try {
            getContentResolver().registerContentObserver(Contacts.CONTENT_URI, true, mObserver);
        } catch (SecurityException e) {
            // no permission yet
        }
        load();
    }

    private void load() {
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        final String q = mSearch.getText().toString().trim();
        final int mode = mMode;
        new AsyncTask<Void, Void, List<Row>>() {
            @Override
            protected List<Row> doInBackground(Void... params) {
                try {
                    return query(mode, q);
                } catch (RuntimeException e) {
                    return new ArrayList<>();
                }
            }

            @Override
            protected void onPostExecute(List<Row> rows) {
                mLoading = false;
                mAdapter.set(rows, q.isEmpty() && mode == MODE_BROWSE);
                mEmpty.setText(q.isEmpty() ? R.string.no_contacts : R.string.no_results);
                mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (mDirty) {
                    mDirty = false;
                    load();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private List<Row> query(int mode, String q) {
        final List<Row> rows = new ArrayList<>();
        if (mode == MODE_PICK_PHONE || mode == MODE_PICK_EMAIL) {
            final boolean phone = mode == MODE_PICK_PHONE;
            final Uri base = phone ? Phone.CONTENT_URI : Email.CONTENT_URI;
            final Uri uri = q.isEmpty() ? base : Uri.withAppendedPath(
                    phone ? Phone.CONTENT_FILTER_URI : Email.CONTENT_FILTER_URI, Uri.encode(q));
            try (Cursor c = getContentResolver().query(uri, new String[] {
                    ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Data.DATA1, ContactsContract.Data.PHOTO_THUMBNAIL_URI},
                    null, null, ContactsContract.Data.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED")) {
                while (c != null && c.moveToNext()) {
                    final Row r = new Row();
                    r.id = c.getLong(0);
                    r.uri = Uri.withAppendedPath(base, String.valueOf(r.id));
                    r.name = c.getString(1);
                    r.detail = c.getString(2);
                    r.photo = c.getString(3);
                    rows.add(r);
                }
            }
            return rows;
        }
        final Uri uri = q.isEmpty() ? Contacts.CONTENT_URI
                : Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(q));
        try (Cursor c = getContentResolver().query(uri, new String[] {
                Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY,
                Contacts.PHOTO_THUMBNAIL_URI, Contacts.STARRED},
                null, null, Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED")) {
            final List<Row> starred = new ArrayList<>();
            while (c != null && c.moveToNext()) {
                final Row r = new Row();
                r.id = c.getLong(0);
                r.uri = Contacts.getLookupUri(r.id, c.getString(1));
                r.name = c.getString(2);
                r.photo = c.getString(3);
                r.starred = c.getInt(4) != 0;
                if (TextUtils.isEmpty(r.name)) {
                    r.name = getString(R.string.no_name);
                }
                rows.add(r);
                if (r.starred && q.isEmpty() && mode == MODE_BROWSE) {
                    final Row s = new Row();
                    s.id = r.id;
                    s.uri = r.uri;
                    s.name = r.name;
                    s.photo = r.photo;
                    s.starred = true;
                    starred.add(s);
                }
            }
            rows.addAll(0, starred);
        }
        return rows;
    }

    // --- menu: import / export --------------------------------------------------------------

    private void showMenu(View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.import_vcf);
        menu.getMenu().add(0, 2, 0, R.string.export_vcf);
        menu.getMenu().add(0, 4, 0, R.string.duplicates);
        menu.getMenu().add(0, 3, 0, R.string.accounts);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                                .setType("text/*").addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(Intent.EXTRA_MIME_TYPES,
                                        new String[] {"text/x-vcard", "text/vcard"}),
                                REQUEST_IMPORT);
                        return true;
                    case 2:
                        VCardIO.exportAll(ContactsActivity.this);
                        return true;
                    case 3:
                        startActivity(new Intent(android.provider.Settings.ACTION_SYNC_SETTINGS));
                        return true;
                    case 4:
                        startActivity(new Intent(ContactsActivity.this, DuplicatesActivity.class));
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            VCardIO.importFrom(this, data.getData());
        }
    }

    // --- list -------------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter implements SectionIndexer {
        private List<Row> mRows = Collections.emptyList();
        private String[] mSections = new String[0];
        private int[] mStarts = new int[0];
        private int[] mSectionOf = new int[0];
        private int mStarredCount;

        void set(List<Row> rows, boolean sections) {
            mRows = rows;
            mStarredCount = 0;
            final List<String> names = new ArrayList<>();
            final List<Integer> starts = new ArrayList<>();
            mSectionOf = new int[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                final Row r = rows.get(i);
                final boolean inStarred = sections && r.starred && i == mStarredCount
                        && (i == 0 || rows.get(i - 1).starred);
                if (inStarred) {
                    mStarredCount++;
                }
                final String s = inStarred ? "★" : Avatars.initial(r.name);
                if (names.isEmpty() || !names.get(names.size() - 1).equals(s)) {
                    names.add(s);
                    starts.add(i);
                }
                mSectionOf[i] = names.size() - 1;
            }
            mSections = names.toArray(new String[0]);
            mStarts = new int[starts.size()];
            for (int i = 0; i < mStarts.length; i++) {
                mStarts[i] = starts.get(i);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Row getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public Object[] getSections() {
            return mSections;
        }

        @Override
        public int getPositionForSection(int section) {
            return mStarts.length == 0 ? 0
                    : mStarts[Math.max(0, Math.min(section, mStarts.length - 1))];
        }

        @Override
        public int getSectionForPosition(int position) {
            return mSectionOf.length == 0 ? 0
                    : mSectionOf[Math.max(0, Math.min(position, mSectionOf.length - 1))];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    ContactsActivity.this).inflate(R.layout.item_contact, parent, false);
            final Row r = mRows.get(position);
            final TextView header = (TextView) v.findViewById(R.id.header);
            final int section = getSectionForPosition(position);
            if (mStarts.length > 0 && mStarts[section] == position) {
                header.setText(mSections[section].equals("★")
                        ? getString(R.string.favourites) : mSections[section]);
                header.setVisibility(View.VISIBLE);
            } else {
                header.setVisibility(View.GONE);
            }
            Avatars.bind(ContactsActivity.this, (TextView) v.findViewById(R.id.letter),
                    (ImageView) v.findViewById(R.id.photo), r.name, r.photo);
            ((TextView) v.findViewById(R.id.name)).setText(r.name);
            final TextView detail = (TextView) v.findViewById(R.id.detail);
            detail.setText(r.detail);
            detail.setVisibility(r.detail != null ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
