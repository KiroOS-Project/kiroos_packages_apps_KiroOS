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
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.BlockedNumberContract;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.phone.incall.CallerInfoCache;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Call history: consecutive calls with the same number on the same day are grouped, with
 * their direction icon and a count. Tap to call back; the info button opens the contact
 * (or adds it); long press for message, copy, block and delete.
 */
final class RecentsPage {

    private static final int LIMIT = 500;

    private static final String[] PROJECTION = {
            Calls._ID, Calls.NUMBER, Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.CACHED_NAME,
            Calls.CACHED_NUMBER_TYPE, Calls.CACHED_NUMBER_LABEL, Calls.CACHED_LOOKUP_URI,
            Calls.CACHED_PHOTO_URI, Calls.NUMBER_PRESENTATION,
    };

    /** A run of calls with the same number on the same day. */
    static final class Group {
        final List<Long> ids = new ArrayList<>();
        String number;
        String name;
        String label;
        String lookupUri;
        String photoUri;
        long date;
        int type;
        int presentation;
        int count;
    }

    private final Activity mActivity;
    private final ListView mList;
    private final View mEmpty;
    private final View mChipAll;
    private final View mChipMissed;
    private final Adapter mAdapter = new Adapter();
    private boolean mMissedOnly;
    private boolean mLoading;
    private boolean mDirty;
    private boolean mVisible;

    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    RecentsPage(Activity activity, View root) {
        mActivity = activity;
        mList = (ListView) root.findViewById(R.id.recents_list);
        mEmpty = root.findViewById(R.id.recents_empty);
        mChipAll = root.findViewById(R.id.chip_all);
        mChipMissed = root.findViewById(R.id.chip_missed);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Group g = mAdapter.getItem(position);
                if (!TextUtils.isEmpty(g.number) && g.presentation == Calls.PRESENTATION_ALLOWED) {
                    CallStarter.call(mActivity, g.number);
                }
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showMenu(mAdapter.getItem(position), view);
                return true;
            }
        });
        mChipAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMissedOnly(false);
            }
        });
        mChipMissed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMissedOnly(true);
            }
        });
        updateChips();
    }

    void start() {
        try {
            mActivity.getContentResolver().registerContentObserver(Calls.CONTENT_URI, true,
                    mObserver);
        } catch (SecurityException e) {
            // no call log permission yet
        }
        load();
    }

    void stop() {
        mActivity.getContentResolver().unregisterContentObserver(mObserver);
    }

    /** The page became visible or hidden: seen missed calls stop being "new". */
    void setVisible(boolean visible) {
        mVisible = visible;
        if (visible) {
            markMissedRead();
        }
    }

    private void setMissedOnly(boolean missed) {
        if (mMissedOnly != missed) {
            mMissedOnly = missed;
            updateChips();
            load();
        }
    }

    private void updateChips() {
        mChipAll.setSelected(!mMissedOnly);
        mChipMissed.setSelected(mMissedOnly);
    }

    void load() {
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        final Context app = mActivity.getApplicationContext();
        final boolean missedOnly = mMissedOnly;
        new AsyncTask<Void, Void, List<Group>>() {
            @Override
            protected List<Group> doInBackground(Void... params) {
                return query(app, missedOnly);
            }

            @Override
            protected void onPostExecute(List<Group> groups) {
                mLoading = false;
                mAdapter.set(groups);
                mEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                if (mDirty) {
                    mDirty = false;
                    load();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static List<Group> query(Context context, boolean missedOnly) {
        final List<Group> out = new ArrayList<>();
        final String where = missedOnly ? Calls.TYPE + "=" + Calls.MISSED_TYPE : null;
        final Uri uri = Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, String.valueOf(LIMIT)).build();
        try (Cursor c = context.getContentResolver().query(uri, PROJECTION, where, null,
                Calls.DEFAULT_SORT_ORDER)) {
            if (c == null) {
                return out;
            }
            Group g = null;
            final Calendar a = Calendar.getInstance();
            final Calendar b = Calendar.getInstance();
            while (c.moveToNext()) {
                final String number = c.getString(1);
                final long date = c.getLong(2);
                final int type = c.getInt(4);
                final int presentation = c.getInt(10);
                boolean same = false;
                if (g != null && TextUtils.equals(g.number, number)
                        && g.presentation == presentation
                        && isMissedLike(g.type) == isMissedLike(type)) {
                    a.setTimeInMillis(g.date);
                    b.setTimeInMillis(date);
                    same = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
                }
                if (!same) {
                    g = new Group();
                    g.number = number;
                    g.date = date;
                    g.type = type;
                    g.presentation = presentation;
                    g.name = c.getString(5);
                    final CharSequence label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.getResources(), c.getInt(6), c.getString(7));
                    g.label = TextUtils.isEmpty(g.name) || label == null ? null : label.toString();
                    g.lookupUri = c.getString(8);
                    g.photoUri = c.getString(9);
                    out.add(g);
                }
                g.ids.add(c.getLong(0));
                g.count++;
            }
        } catch (RuntimeException e) {
            // no permission yet
        }
        return out;
    }

    private static boolean isMissedLike(int type) {
        return type == Calls.MISSED_TYPE || type == Calls.REJECTED_TYPE
                || type == Calls.BLOCKED_TYPE;
    }

    private void markMissedRead() {
        final Context app = mActivity.getApplicationContext();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final ContentValues v = new ContentValues();
                v.put(Calls.NEW, 0);
                v.put(Calls.IS_READ, 1);
                try {
                    app.getContentResolver().update(Calls.CONTENT_URI, v,
                            Calls.TYPE + "=" + Calls.MISSED_TYPE + " AND (" + Calls.NEW + "=1 OR "
                                    + Calls.IS_READ + "=0)", null);
                } catch (RuntimeException e) {
                    // no permission
                }
            }
        });
        try {
            ((TelecomManager) mActivity.getSystemService(Context.TELECOM_SERVICE))
                    .cancelMissedCallsNotification();
        } catch (SecurityException e) {
            // only the default dialer may do this
        }
    }

    private String titleOf(Group g) {
        if (g.presentation == Calls.PRESENTATION_RESTRICTED) {
            return mActivity.getString(R.string.private_number);
        }
        if (g.presentation == Calls.PRESENTATION_PAYPHONE) {
            return mActivity.getString(R.string.payphone);
        }
        if (g.presentation == Calls.PRESENTATION_UNKNOWN || TextUtils.isEmpty(g.number)) {
            return mActivity.getString(R.string.unknown_caller);
        }
        if (!TextUtils.isEmpty(g.name)) {
            return g.name;
        }
        return CallerInfoCache.formatNumber(mActivity, g.number);
    }

    private static int typeIcon(int type) {
        switch (type) {
            case Calls.INCOMING_TYPE:
            case Calls.ANSWERED_EXTERNALLY_TYPE:
                return R.drawable.ic_type_incoming;
            case Calls.OUTGOING_TYPE:
                return R.drawable.ic_type_outgoing;
            case Calls.MISSED_TYPE:
                return R.drawable.ic_type_missed;
            case Calls.VOICEMAIL_TYPE:
                return R.drawable.ic_type_voicemail;
            default:
                return R.drawable.ic_type_blocked;
        }
    }

    // --- actions --------------------------------------------------------------------------

    private void showMenu(final Group g, View anchor) {
        final boolean hasNumber = !TextUtils.isEmpty(g.number)
                && g.presentation == Calls.PRESENTATION_ALLOWED;
        final PopupMenu popup = new PopupMenu(mActivity, anchor);
        final Menu m = popup.getMenu();
        if (hasNumber) {
            m.add(0, 1, 0, R.string.call);
            m.add(0, 2, 0, R.string.send_message);
            m.add(0, 3, 0, R.string.copy_number);
            m.add(0, 4, 0, R.string.edit_before_call);
            if (TextUtils.isEmpty(g.lookupUri)) {
                m.add(0, 5, 0, R.string.add_contact);
            }
            if (BlockedNumberContract.canCurrentUserBlockNumbers(mActivity)) {
                m.add(0, 6, 0, R.string.block_number);
            }
        }
        m.add(0, 7, 0, R.string.delete_from_history);
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        CallStarter.call(mActivity, g.number);
                        return true;
                    case 2:
                        sendMessage(g.number);
                        return true;
                    case 3:
                        final ClipboardManager cm = (ClipboardManager)
                                mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText(null, g.number));
                        Toast.makeText(mActivity, R.string.copied, Toast.LENGTH_SHORT).show();
                        return true;
                    case 4:
                        ((PhoneActivity) mActivity).editNumber(g.number);
                        return true;
                    case 5:
                        KeypadPage.addContact(mActivity, g.number);
                        return true;
                    case 6:
                        block(g.number);
                        return true;
                    case 7:
                        delete(g);
                        return true;
                    default:
                        return false;
                }
            }
        });
        popup.show();
    }

    private void sendMessage(String number) {
        try {
            mActivity.startActivity(new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("smsto", number, null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(mActivity, R.string.no_messages_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void block(String number) {
        final ContentValues v = new ContentValues();
        v.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number);
        try {
            mActivity.getContentResolver().insert(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI, v);
            Toast.makeText(mActivity, mActivity.getString(R.string.number_blocked,
                    CallerInfoCache.formatNumber(mActivity, number)), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(mActivity, R.string.block_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void delete(Group g) {
        final StringBuilder where = new StringBuilder(Calls._ID + " IN (");
        for (int i = 0; i < g.ids.size(); i++) {
            where.append(i == 0 ? "" : ",").append(g.ids.get(i));
        }
        where.append(')');
        final Context app = mActivity.getApplicationContext();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    app.getContentResolver().delete(Calls.CONTENT_URI, where.toString(), null);
                } catch (RuntimeException e) {
                    // no permission
                }
            }
        });
    }

    private void openContactOrAdd(Group g) {
        if (!TextUtils.isEmpty(g.lookupUri)) {
            try {
                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(g.lookupUri)));
                return;
            } catch (ActivityNotFoundException e) {
                // fall through to add
            }
        }
        if (!TextUtils.isEmpty(g.number) && g.presentation == Calls.PRESENTATION_ALLOWED) {
            KeypadPage.addContact(mActivity, g.number);
        }
    }

    // --- adapter --------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        private List<Group> mGroups = Collections.emptyList();

        void set(List<Group> groups) {
            mGroups = groups;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mGroups.size();
        }

        @Override
        public Group getItem(int position) {
            return mGroups.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mGroups.get(position).ids.get(0);
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : LayoutInflater.from(mActivity).inflate(R.layout.item_row, parent, false);
            final Group g = mGroups.get(position);
            final String title = titleOf(g);
            final TextView titleView = (TextView) v.findViewById(R.id.row_title);
            titleView.setText(g.count > 1
                    ? mActivity.getString(R.string.title_count, title, g.count) : title);
            titleView.setTextColor(mActivity.getColor(g.type == Calls.MISSED_TYPE
                    ? R.color.kiro_negative : R.color.kiro_text));
            Avatars.bind(mActivity, (TextView) v.findViewById(R.id.row_letter),
                    (ImageView) v.findViewById(R.id.row_photo),
                    TextUtils.isEmpty(g.name) ? null : g.name, g.photoUri);

            final ImageView type = (ImageView) v.findViewById(R.id.row_type);
            type.setImageResource(typeIcon(g.type));
            type.setVisibility(View.VISIBLE);
            final CharSequence when = DateUtils.getRelativeTimeSpanString(g.date,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE);
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(TextUtils.isEmpty(g.label)
                    ? when : mActivity.getString(R.string.label_number, g.label, when));

            final ImageView action = (ImageView) v.findViewById(R.id.row_action);
            action.setImageResource(R.drawable.ic_info);
            action.setContentDescription(mActivity.getString(R.string.contact_details));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openContactOrAdd(g);
                }
            });
            return v;
        }
    }
}
