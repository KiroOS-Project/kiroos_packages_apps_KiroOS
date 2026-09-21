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
import android.content.DialogInterface;
import android.content.Intent;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Contacts with a phone number, favourites first, with search and fast scroll. Tap calls
 * (asking which number when there are several); long press opens the contact.
 */
final class ContactsPage implements ContactIndex.Listener {

    private final Activity mActivity;
    private final ContactIndex mIndex;
    private final ListView mList;
    private final View mEmpty;
    private final EditText mSearch;
    private final Adapter mAdapter = new Adapter();

    ContactsPage(Activity activity, View root, ContactIndex index) {
        mActivity = activity;
        mIndex = index;
        mList = (ListView) root.findViewById(R.id.contacts_list);
        mEmpty = root.findViewById(R.id.contacts_empty);
        mSearch = (EditText) root.findViewById(R.id.contacts_search);
        mList.setAdapter(mAdapter);
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                callPerson(mAdapter.getItem(position));
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                openContact(mAdapter.getItem(position).first);
                return true;
            }
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        final ImageButton add = (ImageButton) root.findViewById(R.id.btn_new_contact);
        add.setBackground(SquircleDrawable.button(activity.getColor(R.color.kiro_accent),
                activity.getColor(R.color.kiro_ripple)));
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    mActivity.startActivity(new Intent(Intent.ACTION_INSERT,
                            ContactsContract.Contacts.CONTENT_URI));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(mActivity, R.string.no_contacts_app, Toast.LENGTH_SHORT).show();
                }
            }
        });
        KiroTouch.apply(add);
        mIndex.addListener(this);
        refresh();
    }

    @Override
    public void onContactsLoaded() {
        refresh();
    }

    private void refresh() {
        final List<ContactIndex.Person> people = mIndex.search(mSearch.getText().toString());
        mAdapter.set(people, mSearch.length() == 0);
        mEmpty.setVisibility(mIndex.isLoaded() && people.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void callPerson(final ContactIndex.Person p) {
        if (p.numbers.size() == 1 || hasDefault(p)) {
            CallStarter.call(mActivity, p.preferred().number);
            return;
        }
        final String[] items = new String[p.numbers.size()];
        for (int i = 0; i < items.length; i++) {
            final ContactIndex.Entry e = p.numbers.get(i);
            items[i] = TextUtils.isEmpty(e.label) ? e.number
                    : mActivity.getString(R.string.label_number, e.label, e.number);
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(p.first.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        CallStarter.call(mActivity, p.numbers.get(which).number);
                    }
                })
                .show();
    }

    private static boolean hasDefault(ContactIndex.Person p) {
        for (ContactIndex.Entry e : p.numbers) {
            if (e.primary) {
                return true;
            }
        }
        return false;
    }

    private void openContact(ContactIndex.Entry e) {
        try {
            mActivity.startActivity(new Intent(Intent.ACTION_VIEW, e.contactUri()));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(mActivity, R.string.no_contacts_app, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Adapter extends BaseAdapter implements SectionIndexer {
        private List<ContactIndex.Person> mPeople = Collections.emptyList();
        private String[] mSections = new String[0];
        private int[] mSectionStart = new int[0];
        private int[] mSectionOf = new int[0];

        void set(List<ContactIndex.Person> people, boolean withFavourites) {
            mPeople = people;
            // sections for fast scroll: a star for favourites, then initials
            final List<String> sections = new ArrayList<>();
            final List<Integer> starts = new ArrayList<>();
            mSectionOf = new int[people.size()];
            for (int i = 0; i < people.size(); i++) {
                final ContactIndex.Person p = people.get(i);
                final String s = withFavourites && p.first.starred ? "★"
                        : Avatars.initial(p.first.name);
                if (sections.isEmpty() || !sections.get(sections.size() - 1).equals(s)) {
                    sections.add(s);
                    starts.add(i);
                }
                mSectionOf[i] = sections.size() - 1;
            }
            mSections = sections.toArray(new String[0]);
            mSectionStart = new int[starts.size()];
            for (int i = 0; i < starts.size(); i++) {
                mSectionStart[i] = starts.get(i);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mPeople.size();
        }

        @Override
        public ContactIndex.Person getItem(int position) {
            return mPeople.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mPeople.get(position).first.contactId;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public Object[] getSections() {
            return mSections;
        }

        @Override
        public int getPositionForSection(int section) {
            if (mSectionStart.length == 0) {
                return 0;
            }
            return mSectionStart[Math.max(0, Math.min(section, mSectionStart.length - 1))];
        }

        @Override
        public int getSectionForPosition(int position) {
            if (mSectionOf.length == 0) {
                return 0;
            }
            return mSectionOf[Math.max(0, Math.min(position, mSectionOf.length - 1))];
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView
                    : LayoutInflater.from(mActivity).inflate(R.layout.item_row, parent, false);
            final ContactIndex.Person p = mPeople.get(position);
            final ContactIndex.Entry e = p.preferred();
            final String number = com.kiroos.phone.incall.CallerInfoCache.formatNumber(
                    mActivity, e.number);
            Avatars.bind(mActivity, (TextView) v.findViewById(R.id.row_letter),
                    (ImageView) v.findViewById(R.id.row_photo), p.first.name, p.first.photoUri);
            ((TextView) v.findViewById(R.id.row_title)).setText(p.first.name);
            final String numbers = p.numbers.size() > 1
                    ? mActivity.getString(R.string.more_numbers, number, p.numbers.size() - 1)
                    : number;
            ((TextView) v.findViewById(R.id.row_subtitle)).setText(TextUtils.isEmpty(e.label)
                    ? numbers : mActivity.getString(R.string.label_number, e.label, numbers));
            final ImageView action = (ImageView) v.findViewById(R.id.row_action);
            action.setImageResource(R.drawable.ic_message);
            action.setContentDescription(mActivity.getString(R.string.send_message));
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    try {
                        mActivity.startActivity(new Intent(Intent.ACTION_SENDTO,
                                android.net.Uri.fromParts("smsto", e.number, null)));
                    } catch (ActivityNotFoundException ex) {
                        Toast.makeText(mActivity, R.string.no_messages_app,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return v;
        }
    }
}
