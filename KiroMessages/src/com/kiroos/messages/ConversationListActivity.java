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

package com.kiroos.messages;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Telephony;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Conversations, newest first, with search over names and message text. */
public class ConversationListActivity extends Activity {

    private static final String[] PERMISSIONS = {
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE,
    };

    /** a row: a conversation, or a message found by the search */
    private static final class Row {
        long thread;
        String title;
        String snippet;
        long date;
        boolean unread;
    }

    private final Adapter mAdapter = new Adapter();
    private final Handler mHandler = new Handler();
    private EditText mSearch;
    private TextView mEmpty;
    private View mBanner;
    private boolean mLoading;
    private boolean mDirty;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            reload();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        mSearch = (EditText) findViewById(R.id.search);
        mEmpty = (TextView) findViewById(R.id.empty);
        mBanner = findViewById(R.id.default_banner);
        final ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                startActivity(ConversationActivity.intent(ConversationListActivity.this,
                        mAdapter.getItem(position).thread));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(mAdapter.getItem(position));
                return true;
            }
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                reload();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        final ImageButton add = (ImageButton) findViewById(R.id.btn_new);
        add.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ConversationListActivity.this,
                        ConversationActivity.class));
            }
        });
        KiroTouch.apply(add);
        mBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                        .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName()));
            }
        });
        requestMissingPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            getContentResolver().registerContentObserver(
                    android.net.Uri.parse("content://mms-sms/"), true, mObserver);
        } catch (SecurityException e) {
            // no permission yet
        }
        final boolean isDefault = getPackageName().equals(
                Telephony.Sms.getDefaultSmsPackage(this));
        mBanner.setVisibility(isDefault ? View.GONE : View.VISIBLE);
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    private void reload() {
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        final String query = mSearch.getText().toString().trim();
        new AsyncTask<Void, Void, List<Row>>() {
            @Override
            protected List<Row> doInBackground(Void... params) {
                return load(query);
            }

            @Override
            protected void onPostExecute(List<Row> rows) {
                mLoading = false;
                mAdapter.set(rows);
                mEmpty.setText(query.isEmpty() ? R.string.no_conversations : R.string.no_results);
                mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (mDirty) {
                    mDirty = false;
                    reload();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private List<Row> load(String query) {
        final List<Row> rows = new ArrayList<>();
        final String q = query.toLowerCase();
        for (MessageStore.Thread t : MessageStore.threads(this)) {
            final Row r = new Row();
            r.thread = t.id;
            r.title = Names.title(this, t.addresses);
            r.snippet = TextUtils.isEmpty(t.snippet) && t.hasAttachment
                    ? getString(R.string.picture) : t.snippet;
            r.date = t.date;
            r.unread = t.unread;
            if (q.isEmpty() || r.title.toLowerCase().contains(q)) {
                rows.add(r);
            }
        }
        if (!q.isEmpty()) {
            for (MessageStore.Message m : MessageStore.search(this, query, 100)) {
                final Row r = new Row();
                r.thread = m.id;
                final String name = Names.lookup(this, m.address);
                r.title = name != null ? name : Names.format(m.address);
                r.snippet = m.body;
                r.date = m.date;
                rows.add(r);
            }
        }
        return rows;
    }

    private void confirmDelete(final Row row) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_conversation)
                .setMessage(getString(R.string.delete_conversation_confirm, row.title))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    MessageStore.deleteThread(getApplicationContext(), row.thread);
                                } catch (RuntimeException e) {
                                    // not the default SMS app
                                }
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void requestMissingPermissions() {
        final List<String> missing = new ArrayList<>();
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        Names.clear();
        onStop();
        onStart();
    }

    private final class Adapter extends BaseAdapter {
        private List<Row> mRows = Collections.emptyList();

        void set(List<Row> rows) {
            mRows = rows;
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
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    ConversationListActivity.this).inflate(R.layout.item_thread, parent, false);
            final Row r = mRows.get(position);
            ((TextView) v.findViewById(R.id.avatar)).setText(Names.initial(r.title));
            final TextView title = (TextView) v.findViewById(R.id.title);
            title.setText(r.title);
            title.setTypeface(Typeface.create("sans-serif-medium",
                    r.unread ? Typeface.BOLD : Typeface.NORMAL));
            final TextView snippet = (TextView) v.findViewById(R.id.snippet);
            snippet.setText(r.snippet);
            snippet.setTextColor(getColor(r.unread ? R.color.kiro_text
                    : R.color.kiro_text_secondary));
            ((TextView) v.findViewById(R.id.date)).setText(DateUtils.getRelativeTimeSpanString(
                    r.date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE));
            v.findViewById(R.id.unread).setVisibility(r.unread ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
