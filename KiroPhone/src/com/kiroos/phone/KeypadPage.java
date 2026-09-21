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
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

import java.util.List;
import java.util.Locale;

/**
 * The keypad page: typed number with live formatting, contact suggestions (T9), local DTMF
 * feedback tones, call / add contact / voicemail, and the long-press shortcuts
 * (0 = +, 1 = voicemail, * = pause, # = wait).
 */
final class KeypadPage implements DialpadView.Listener, ContactIndex.Listener {

    private static final int MAX_SUGGESTIONS = 3;
    private static final int TONE_MS = 150;

    private final Activity mActivity;
    private final ContactIndex mIndex;
    private final EditText mDigits;
    private final View mBackspace;
    private final View mAddContact;
    private final View mVoicemail;
    private final LinearLayout mSuggestions;
    private ToneGenerator mTones;

    KeypadPage(Activity activity, View root, ContactIndex index) {
        mActivity = activity;
        mIndex = index;
        mDigits = (EditText) root.findViewById(R.id.digits);
        mBackspace = root.findViewById(R.id.btn_backspace);
        mAddContact = root.findViewById(R.id.btn_add_contact);
        mVoicemail = root.findViewById(R.id.btn_voicemail);
        mSuggestions = (LinearLayout) root.findViewById(R.id.suggestions);

        mDigits.setShowSoftInputOnFocus(false);
        // the cursor only shows once the user taps into the number to edit it
        mDigits.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDigits.setCursorVisible(mDigits.length() > 0);
            }
        });
        String country = null;
        final TelephonyManager tm = (TelephonyManager) activity.getSystemService(
                Activity.TELEPHONY_SERVICE);
        if (tm != null) {
            country = tm.getSimCountryIso();
            if (TextUtils.isEmpty(country)) {
                country = tm.getNetworkCountryIso();
            }
        }
        if (TextUtils.isEmpty(country)) {
            country = Locale.getDefault().getCountry();
        }
        mDigits.addTextChangedListener(new PhoneNumberFormattingTextWatcher(
                country.toUpperCase(Locale.US)));
        mDigits.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onDigitsChanged();
            }
        });

        ((DialpadView) root.findViewById(R.id.dialpad)).setListener(this);
        mBackspace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backspace();
            }
        });
        mBackspace.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mDigits.getText().clear();
                return true;
            }
        });
        final View call = root.findViewById(R.id.btn_call);
        call.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onCallPressed();
            }
        });
        KiroTouch.apply(call);
        mAddContact.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addContact(mActivity, CallStarter.dialable(mDigits.getText().toString()));
            }
        });
        mVoicemail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CallStarter.callVoicemail(mActivity);
            }
        });
        mIndex.addListener(this);
        onDigitsChanged();
    }

    void onResume() {
        final boolean tones = Settings.System.getInt(mActivity.getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;
        if (tones && mTones == null) {
            try {
                mTones = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
            } catch (RuntimeException e) {
                mTones = null; // audio busy; dialing works without tones
            }
        } else if (!tones) {
            releaseTones();
        }
    }

    void onPause() {
        releaseTones();
    }

    private void releaseTones() {
        if (mTones != null) {
            mTones.release();
            mTones = null;
        }
    }

    void setNumber(String number) {
        mDigits.setText(number);
        mDigits.setSelection(mDigits.length());
    }

    void clear() {
        mDigits.getText().clear();
    }

    boolean isEmpty() {
        return mDigits.length() == 0;
    }

    @Override
    public void onContactsLoaded() {
        updateSuggestions();
    }

    // --- keys ---------------------------------------------------------------------------

    @Override
    public void onKeyPressed(char key) {
        playTone(key);
        insert(String.valueOf(key));
    }

    @Override
    public void onKeyReleased(char key) {
    }

    @Override
    public boolean onKeyLongPressed(char key) {
        switch (key) {
            case '0':
                replaceLast('0', "+");
                return true;
            case '*':
                replaceLast('*', ",");
                return true;
            case '#':
                replaceLast('#', ";");
                return true;
            case '1':
                if (CallStarter.dialable(mDigits.getText().toString()).equals("1")) {
                    mDigits.getText().clear();
                    CallStarter.callVoicemail(mActivity);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void insert(String s) {
        final Editable e = mDigits.getText();
        int start = mDigits.getSelectionStart();
        int end = mDigits.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = end = e.length();
        }
        e.replace(Math.min(start, end), Math.max(start, end), s);
        if (CallStarter.handleSpecialCode(mActivity, e.toString())) {
            e.clear();
        }
    }

    /** Long press: the key already typed its own character; swap it for the alternate. */
    private void replaceLast(char typed, String with) {
        final Editable e = mDigits.getText();
        int pos = mDigits.getSelectionStart();
        if (pos <= 0 || pos > e.length()) {
            pos = e.length();
        }
        if (pos > 0 && e.charAt(pos - 1) == typed) {
            e.replace(pos - 1, pos, with);
        } else {
            e.insert(pos, with);
        }
    }

    private void backspace() {
        final Editable e = mDigits.getText();
        final int start = mDigits.getSelectionStart();
        final int end = mDigits.getSelectionEnd();
        if (start >= 0 && end >= 0 && start != end) {
            e.delete(Math.min(start, end), Math.max(start, end));
        } else {
            final int pos = start >= 0 ? start : e.length();
            if (pos > 0) {
                e.delete(pos - 1, pos);
            }
        }
    }

    private void playTone(char key) {
        if (mTones == null) {
            return;
        }
        final int tone;
        switch (key) {
            case '*':
                tone = ToneGenerator.TONE_DTMF_S;
                break;
            case '#':
                tone = ToneGenerator.TONE_DTMF_P;
                break;
            default:
                tone = ToneGenerator.TONE_DTMF_0 + (key - '0');
                break;
        }
        final AudioManager am = (AudioManager) mActivity.getSystemService(Activity.AUDIO_SERVICE);
        final int mode = am.getRingerMode();
        if (mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE) {
            return;
        }
        mTones.startTone(tone, TONE_MS);
    }

    private void onCallPressed() {
        final String typed = mDigits.getText().toString();
        if (CallStarter.dialable(typed).isEmpty()) {
            // empty keypad: bring back the last dialed number, like on every phone
            final String last = CallLog.Calls.getLastOutgoingCall(mActivity);
            if (!TextUtils.isEmpty(last)) {
                setNumber(last);
            }
            return;
        }
        if (CallStarter.call(mActivity, typed)) {
            mDigits.getText().clear();
            if (mActivity instanceof PhoneActivity) {
                ((PhoneActivity) mActivity).onCallPlaced();
            }
        }
    }

    // --- suggestions ----------------------------------------------------------------------

    private void onDigitsChanged() {
        final boolean empty = mDigits.length() == 0;
        mBackspace.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        mAddContact.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        mVoicemail.setVisibility(empty ? View.VISIBLE : View.INVISIBLE);
        if (empty) {
            mDigits.setCursorVisible(false);
        }
        updateSuggestions();
    }

    private void updateSuggestions() {
        mSuggestions.removeAllViews();
        final String typed = mDigits.getText().toString();
        if (typed.isEmpty()) {
            return;
        }
        // as many rows as fit above the number (at least one on the smallest screens)
        final int rowHeight = mActivity.getResources().getDimensionPixelSize(
                R.dimen.suggestion_height);
        final int fit = Math.max(1, Math.min(MAX_SUGGESTIONS,
                mSuggestions.getHeight() / rowHeight));
        final List<ContactIndex.Entry> hits = mIndex.matchDialed(typed, fit);
        final LayoutInflater inflater = LayoutInflater.from(mActivity);
        for (final ContactIndex.Entry e : hits) {
            final View row = inflater.inflate(R.layout.item_row, mSuggestions, false);
            Avatars.bind(mActivity, (TextView) row.findViewById(R.id.row_letter),
                    (ImageView) row.findViewById(R.id.row_photo), e.name, e.photoUri);
            ((TextView) row.findViewById(R.id.row_title)).setText(e.name);
            final String number = com.kiroos.phone.incall.CallerInfoCache.formatNumber(
                    mActivity, e.number);
            ((TextView) row.findViewById(R.id.row_subtitle)).setText(
                    TextUtils.isEmpty(e.label) ? number
                            : mActivity.getString(R.string.label_number, e.label, number));
            final ImageView action = (ImageView) row.findViewById(R.id.row_action);
            action.setImageResource(R.drawable.ic_call);
            action.setContentDescription(mActivity.getString(R.string.call));
            final View.OnClickListener call = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (CallStarter.call(mActivity, e.number)) {
                        mDigits.getText().clear();
                    }
                }
            };
            action.setOnClickListener(call);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setNumber(e.number);
                }
            });
            mSuggestions.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
        }
    }

    static void addContact(Activity activity, String number) {
        final Intent i = new Intent(Intent.ACTION_INSERT_OR_EDIT)
                .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                .putExtra(ContactsContract.Intents.Insert.PHONE, number);
        try {
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            android.widget.Toast.makeText(activity, R.string.no_contacts_app,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
