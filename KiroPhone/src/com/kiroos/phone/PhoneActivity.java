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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.phone.incall.CallList;
import com.kiroos.phone.incall.InCallActivity;
import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.List;

/**
 * Kiro Phone: Keypad, Recents and Contacts. Handles DIAL / tel: / call log intents.
 * 1.2: speed dial (SpeedDial).
 */
public class PhoneActivity extends Activity {

    /** set by the in-call screen's "Add call" */
    public static final String EXTRA_ADD_CALL = "com.kiroos.phone.ADD_CALL";

    private static final int TAB_KEYPAD = 0;
    private static final int TAB_RECENTS = 1;
    private static final int TAB_CONTACTS = 2;

    private static final int REQUEST_PERMISSIONS = 1;
    private static final String[] PERMISSIONS = {
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_PHONE_STATE,
    };

    private SharedPreferences mPrefs;
    private ContactIndex mIndex;
    private KeypadPage mKeypad;
    private RecentsPage mRecents;
    private ContactsPage mContacts;
    private View[] mPages;
    private View[] mTabs;
    private TextView mTitle;
    private int mTab = -1;
    private boolean mAddCall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);
        mPrefs = getSharedPreferences("phone", MODE_PRIVATE);
        mTitle = (TextView) findViewById(R.id.page_title);

        mIndex = new ContactIndex(this);
        mKeypad = new KeypadPage(this, findViewById(R.id.page_keypad), mIndex);
        mRecents = new RecentsPage(this, findViewById(R.id.page_recents));
        mContacts = new ContactsPage(this, findViewById(R.id.page_contacts), mIndex);
        mPages = new View[] {
                findViewById(R.id.page_keypad), findViewById(R.id.page_recents),
                findViewById(R.id.page_contacts),
        };
        mTabs = new View[] {
                findViewById(R.id.tab_keypad), findViewById(R.id.tab_recents),
                findViewById(R.id.tab_contacts),
        };
        final int[] icons = {R.drawable.ic_dialpad, R.drawable.ic_history, R.drawable.ic_person};
        final int[] labels = {R.string.tab_keypad, R.string.tab_recents, R.string.tab_contacts};
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            ((ImageView) mTabs[i].findViewById(R.id.tab_icon)).setImageResource(icons[i]);
            ((TextView) mTabs[i].findViewById(R.id.tab_label)).setText(labels[i]);
            mTabs[i].setContentDescription(getText(labels[i]));
            mTabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(tab, true);
                }
            });
        }
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflow(v);
            }
        });

        mIndex.start();
        mRecents.start();
        if (!handleIntent(getIntent())) {
            showTab(mPrefs.getInt("tab", TAB_KEYPAD), false);
        }
        requestMissingPermissions();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mKeypad.onResume();
        if (mTab == TAB_RECENTS) {
            mRecents.setVisible(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mKeypad.onPause();
        mRecents.setVisible(false);
    }

    @Override
    protected void onDestroy() {
        mIndex.stop();
        mRecents.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mTab == TAB_KEYPAD && !mKeypad.isEmpty()) {
            mKeypad.clear();
            return;
        }
        super.onBackPressed();
    }

    /** @return true if the intent chose the page to show */
    private boolean handleIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        mAddCall = intent.getBooleanExtra(EXTRA_ADD_CALL, false);
        final String action = intent.getAction();
        final Uri data = intent.getData();
        final boolean callLogType = CallLog.Calls.CONTENT_TYPE.equals(intent.getType())
                || (data != null && "content".equals(data.getScheme())
                && CallLog.Calls.CONTENT_TYPE.equals(getContentResolver().getType(data)));
        if (Intent.ACTION_VIEW.equals(action) && callLogType) {
            showTab(TAB_RECENTS, false);
            return true;
        }
        if (Intent.ACTION_DIAL.equals(action) || Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_CALL_BUTTON.equals(action)) {
            if (data != null) {
                final String scheme = data.getScheme();
                if (PhoneAccount.SCHEME_TEL.equals(scheme)) {
                    mKeypad.setNumber(data.getSchemeSpecificPart());
                } else if ("content".equals(scheme)) {
                    loadNumberFrom(data);
                }
            }
            showTab(TAB_KEYPAD, false);
            return true;
        }
        if (mAddCall) {
            showTab(TAB_KEYPAD, false);
            return true;
        }
        return false;
    }

    /** DIAL with a contact or phone data row: put its number on the keypad. */
    private void loadNumberFrom(final Uri uri) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                try (Cursor c = getContentResolver().query(uri,
                        new String[] {ContactsContract.CommonDataKinds.Phone.NUMBER},
                        null, null, null)) {
                    return c != null && c.moveToFirst() ? c.getString(0) : null;
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String number) {
                if (!TextUtils.isEmpty(number)) {
                    mKeypad.setNumber(number);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showTab(int tab, boolean animate) {
        if (tab < 0 || tab >= mPages.length) {
            tab = TAB_KEYPAD;
        }
        if (tab == mTab) {
            return;
        }
        mTab = tab;
        mPrefs.edit().putInt("tab", tab).apply();
        for (int i = 0; i < mPages.length; i++) {
            final boolean on = i == tab;
            mPages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            final int color = getColor(on ? R.color.kiro_accent : R.color.kiro_text_secondary);
            ((ImageView) mTabs[i].findViewById(R.id.tab_icon)).setImageTintList(
                    android.content.res.ColorStateList.valueOf(color));
            ((TextView) mTabs[i].findViewById(R.id.tab_label)).setTextColor(color);
            mTabs[i].setSelected(on);
        }
        final int[] titles = {R.string.tab_keypad, R.string.tab_recents, R.string.tab_contacts};
        mTitle.setText(titles[tab]);
        mRecents.setVisible(tab == TAB_RECENTS && isResumed());
        if (animate) {
            KiroTouch.enter(mPages[tab]);
        }
    }

    /** Recents → "edit number before call". */
    void editNumber(String number) {
        mKeypad.setNumber(number);
        showTab(TAB_KEYPAD, true);
    }

    /** Called when the keypad placed a call; after "Add call", go back to the call screen. */
    void onCallPlaced() {
        if (mAddCall) {
            mAddCall = false;
            finish();
        }
    }

    // --- overflow menu --------------------------------------------------------------------

    private void showOverflow(View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        if (!CallList.get().isEmpty()) {
            menu.getMenu().add(0, 1, 0, R.string.return_to_call);
        }
        menu.getMenu().add(0, 6, 0, R.string.speed_dial);
        menu.getMenu().add(0, 2, 0, R.string.call_settings);
        menu.getMenu().add(0, 3, 0, R.string.calling_accounts);
        menu.getMenu().add(0, 4, 0, R.string.blocked_numbers);
        menu.getMenu().add(0, 5, 0, R.string.clear_history);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                final TelecomManager telecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
                switch (item.getItemId()) {
                    case 1:
                        startActivity(InCallActivity.intent(PhoneActivity.this, false));
                        return true;
                    case 2:
                        safeStart(new Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS));
                        return true;
                    case 3:
                        safeStart(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));
                        return true;
                    case 4:
                        safeStart(telecom.createManageBlockedNumbersIntent());
                        return true;
                    case 5:
                        confirmClearHistory();
                        return true;
                    case 6:
                        SpeedDial.showManager(PhoneActivity.this);
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void safeStart(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearHistory() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_history)
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final android.content.Context app = getApplicationContext();
                        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    app.getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                                            null, null);
                                } catch (RuntimeException e) {
                                    // no permission
                                }
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        final int key = requestCode - SpeedDial.REQUEST_BASE;
        if (key >= 2 && key <= 9 && resultCode == RESULT_OK && data != null) {
            SpeedDial.onPicked(this, key, data.getData());
        }
    }

    // --- permissions ----------------------------------------------------------------------

    private void requestMissingPermissions() {
        final List<String> missing = new ArrayList<>();
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_PERMISSIONS) {
            mIndex.stop();
            mIndex.start();
            mRecents.stop();
            mRecents.start();
        }
    }
}
