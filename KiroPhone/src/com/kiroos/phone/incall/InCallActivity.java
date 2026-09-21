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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.kiroos.phone.DialpadView;
import com.kiroos.phone.PhoneActivity;
import com.kiroos.phone.R;
import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * The call screen: who, state and timer; answer / decline / reply for incoming calls;
 * mute, keypad, audio route, hold, add call, merge / swap and hang up while talking.
 * The proximity sensor turns the screen off at the ear.
 */
public class InCallActivity extends Activity implements CallList.PostDialListener,
        CallerInfoCache.Callback, DialpadView.Listener {

    static final String EXTRA_ANSWER = "com.kiroos.phone.ANSWER";
    static final String EXTRA_DIALPAD = "com.kiroos.phone.DIALPAD";

    private static final long ENDED_LINGER_MS = 1600;

    private final Handler mHandler = new Handler();
    private CallList mCalls;
    private TelecomManager mTelecom;
    private PowerManager.WakeLock mProximity;

    private TextView mAccount;
    private TextView mStatus;
    private TextView mLetter;
    private ImageView mPhoto;
    private TextView mName;
    private TextView mNumber;
    private View mSecondaryBar;
    private TextView mSecondaryText;
    private View mControls;
    private View mIncoming;
    private View mDtmfPanel;
    private TextView mDtmfDigits;
    private TextView mDtmfName;
    private View mMute;
    private View mKeypad;
    private View mSpeaker;
    private View mHold;
    private View mAdd;
    private View mMerge;

    private Call mShown;
    private boolean mEnded;
    private AlertDialog mDialog;
    private Call mDialogCall;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable mFinish = new Runnable() {
        @Override
        public void run() {
            if (mCalls.primary() == null) {
                finishAndRemoveTask();
            }
        }
    };

    public static Intent intent(Context context, boolean dialpad) {
        return new Intent(context, InCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_DIALPAD, dialpad);
    }

    static void show(Context context, boolean dialpad) {
        context.startActivity(intent(context, dialpad));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);
        setContentView(R.layout.activity_incall);
        mCalls = CallList.get();
        mTelecom = (TelecomManager) getSystemService(TELECOM_SERVICE);
        final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            mProximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "KiroPhone:ear");
            mProximity.setReferenceCounted(false);
        }

        mAccount = (TextView) findViewById(R.id.account_label);
        mStatus = (TextView) findViewById(R.id.call_status);
        mLetter = (TextView) findViewById(R.id.avatar_letter);
        mPhoto = (ImageView) findViewById(R.id.avatar_photo);
        mPhoto.setClipToOutline(true);
        mName = (TextView) findViewById(R.id.caller_name);
        mNumber = (TextView) findViewById(R.id.caller_number);
        mSecondaryBar = findViewById(R.id.secondary_bar);
        mSecondaryText = (TextView) findViewById(R.id.secondary_text);
        mControls = findViewById(R.id.controls);
        mIncoming = findViewById(R.id.incoming);
        mDtmfPanel = findViewById(R.id.dtmf_panel);
        mDtmfDigits = (TextView) findViewById(R.id.dtmf_digits);
        mDtmfName = (TextView) findViewById(R.id.dtmf_name);

        mMute = setupToggle(R.id.btn_mute, R.drawable.ic_mic_off, R.string.mute);
        mKeypad = setupToggle(R.id.btn_keypad, R.drawable.ic_dialpad, R.string.keypad);
        mSpeaker = setupToggle(R.id.btn_speaker, R.drawable.ic_speaker, R.string.speaker);
        mHold = setupToggle(R.id.btn_hold, R.drawable.ic_pause, R.string.hold);
        mAdd = setupToggle(R.id.btn_add, R.drawable.ic_add_call, R.string.add_call);
        mMerge = setupToggle(R.id.btn_merge, R.drawable.ic_merge, R.string.merge);

        mMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final CallAudioState s = mCalls.audioState();
                mCalls.setMuted(s == null || !s.isMuted());
            }
        });
        mKeypad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDtmf(true);
            }
        });
        mSpeaker.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAudioRouteClicked(v);
            }
        });
        mHold.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Call c = liveCall();
                if (c == null) {
                    return;
                }
                if (c.getState() == Call.STATE_HOLDING) {
                    c.unhold();
                } else {
                    c.hold();
                }
            }
        });
        mAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(InCallActivity.this, PhoneActivity.class)
                        .setAction(Intent.ACTION_DIAL)
                        .putExtra(PhoneActivity.EXTRA_ADD_CALL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        mMerge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMergeOrSwap();
            }
        });
        mSecondaryBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Call other = mCalls.secondary(mShown);
                if (other != null && other.getState() == Call.STATE_HOLDING) {
                    other.unhold(); // Telecom holds the active call first
                }
            }
        });

        final View.OnClickListener hangUp = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Call c = mCalls.primary();
                if (c != null) {
                    c.disconnect();
                }
            }
        };
        findViewById(R.id.btn_end).setOnClickListener(hangUp);
        findViewById(R.id.dtmf_end).setOnClickListener(hangUp);
        findViewById(R.id.dtmf_hide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDtmf(false);
            }
        });
        findViewById(R.id.btn_answer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                answer();
            }
        });
        findViewById(R.id.btn_decline).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Call c = mCalls.incoming();
                if (c != null) {
                    c.reject(false, null);
                }
            }
        });
        findViewById(R.id.btn_reply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReplyChooser();
            }
        });
        KiroTouch.apply(findViewById(R.id.btn_end), findViewById(R.id.btn_answer),
                findViewById(R.id.btn_decline), findViewById(R.id.btn_reply),
                findViewById(R.id.dtmf_end));

        ((DialpadView) findViewById(R.id.dtmf_pad)).setListener(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) {
            intent.removeExtra(EXTRA_ANSWER);
            answer();
        }
        if (intent.getBooleanExtra(EXTRA_DIALPAD, false)) {
            showDtmf(true);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mCalls.addListener(this);
        update();
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mCalls.removeListener(this);
        CallerInfoCache.forget(this);
        mHandler.removeCallbacks(mTick);
        setProximity(false);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        dismissDialog();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDtmfPanel.getVisibility() == View.VISIBLE) {
            showDtmf(false);
            return;
        }
        if (mCalls.incoming() != null) {
            return; // answer or decline first
        }
        moveTaskToBack(true);
    }

    // --- CallList ---------------------------------------------------------------------

    @Override
    public void onCallsChanged() {
        update();
    }

    @Override
    public void onAudioStateChanged(CallAudioState state) {
        updateButtons();
        updateProximity();
    }

    @Override
    public void onCallerInfo(String number, CallerInfoCache.Info info) {
        update();
    }

    @Override
    public void onPostDialWait(final Call call, final String remaining) {
        dismissDialog();
        mDialog = new AlertDialog.Builder(this)
                .setMessage(getString(R.string.send_tones, remaining))
                .setPositiveButton(R.string.send, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        call.postDialContinue(true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        call.postDialContinue(false);
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void update() {
        final Call call = mCalls.primary();
        if (call == null) {
            // the last call went away: keep "call ended" on screen for a moment
            if (!mEnded) {
                mEnded = true;
                mStatus.setText(endedText(mShown));
                mControls.setVisibility(View.GONE);
                mIncoming.setVisibility(View.GONE);
                mDtmfPanel.setVisibility(View.GONE);
                mSecondaryBar.setVisibility(View.GONE);
                setProximity(false);
                mHandler.postDelayed(mFinish, ENDED_LINGER_MS);
            }
            return;
        }
        mEnded = false;
        mHandler.removeCallbacks(mFinish);
        mShown = call;

        final String number = CallerInfoCache.numberOf(call);
        final CallerInfoCache.Info info = CallerInfoCache.get(this, number, this);
        final String name = CallerInfoCache.displayName(this, call, info);
        mName.setText(name);
        mDtmfName.setText(name);
        if (info != null && !TextUtils.isEmpty(info.name) && !TextUtils.isEmpty(number)) {
            final String formatted = CallerInfoCache.formatNumber(this, number);
            mNumber.setText(TextUtils.isEmpty(info.label) ? formatted
                    : getString(R.string.label_number, info.label, formatted));
            mNumber.setVisibility(View.VISIBLE);
        } else {
            mNumber.setVisibility(View.GONE);
        }
        if (info != null && info.photo != null) {
            mPhoto.setImageBitmap(info.photo);
            mPhoto.setVisibility(View.VISIBLE);
        } else {
            mPhoto.setVisibility(View.GONE);
            mLetter.setText(initialOf(name, number));
        }
        updateAccountLabel(call);

        final int state = call.getState();
        final boolean ringing = state == Call.STATE_RINGING;
        mIncoming.setVisibility(ringing ? View.VISIBLE : View.GONE);
        mControls.setVisibility(ringing ? View.GONE : View.VISIBLE);
        if (ringing && mDtmfPanel.getVisibility() == View.VISIBLE) {
            mDtmfPanel.setVisibility(View.GONE);
        }
        if (ringing) {
            // pulse the answer button to draw the eye
            final View answer = findViewById(R.id.btn_answer);
            if (answer.getAnimation() == null && answer.getTag() == null) {
                answer.setTag(Boolean.TRUE);
                pulse(answer);
            }
        }

        final Call other = mCalls.secondary(call);
        if (other != null && !ringing) {
            final CallerInfoCache.Info oi = CallerInfoCache.get(this,
                    CallerInfoCache.numberOf(other), this);
            mSecondaryText.setText(getString(other.getState() == Call.STATE_HOLDING
                    ? R.string.on_hold_named : R.string.other_call_named,
                    CallerInfoCache.displayName(this, other, oi)));
            mSecondaryBar.setVisibility(View.VISIBLE);
        } else {
            mSecondaryBar.setVisibility(View.GONE);
        }

        if (state == Call.STATE_SELECT_PHONE_ACCOUNT) {
            showAccountPicker(call);
        } else if (mDialogCall == call) {
            dismissDialog();
        }

        updateStatus();
        updateButtons();
        updateProximity();
    }

    private void updateStatus() {
        final Call call = mShown;
        if (call == null || mEnded) {
            return;
        }
        final int state = call.getState();
        if (state == Call.STATE_ACTIVE && call.getDetails() != null
                && call.getDetails().getConnectTimeMillis() > 0) {
            final long secs = Math.max(0,
                    (System.currentTimeMillis() - call.getDetails().getConnectTimeMillis()) / 1000);
            mStatus.setText(DateUtils.formatElapsedTime(secs));
        } else if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            mStatus.setText(endedText(call));
        } else {
            mStatus.setText(InCallNotifier.stateText(state));
        }
    }

    private String endedText(Call call) {
        if (call != null && call.getDetails() != null) {
            final DisconnectCause cause = call.getDetails().getDisconnectCause();
            if (cause != null && !TextUtils.isEmpty(cause.getLabel())
                    && cause.getCode() != DisconnectCause.LOCAL
                    && cause.getCode() != DisconnectCause.REMOTE) {
                return cause.getLabel().toString();
            }
        }
        return getString(R.string.call_ended);
    }

    private void updateAccountLabel(Call call) {
        final PhoneAccountHandle handle = call.getDetails() != null
                ? call.getDetails().getAccountHandle() : null;
        CharSequence label = null;
        try {
            if (handle != null && mTelecom.getCallCapablePhoneAccounts().size() > 1) {
                final PhoneAccount account = mTelecom.getPhoneAccount(handle);
                label = account != null ? account.getLabel() : null;
            }
        } catch (SecurityException e) {
            label = null;
        }
        mAccount.setText(label);
        mAccount.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
    }

    private void updateButtons() {
        final Call call = liveCall();
        final CallAudioState audio = mCalls.audioState();
        setToggle(mMute, audio != null && audio.isMuted(), call != null);
        setToggle(mKeypad, mDtmfPanel.getVisibility() == View.VISIBLE, call != null);

        final int route = audio != null ? audio.getRoute() : CallAudioState.ROUTE_EARPIECE;
        final boolean bt = audio != null
                && (audio.getSupportedRouteMask() & CallAudioState.ROUTE_BLUETOOTH) != 0;
        setToggle(mSpeaker, route == CallAudioState.ROUTE_SPEAKER
                || route == CallAudioState.ROUTE_BLUETOOTH, audio != null);
        setToggleVisual(mSpeaker, route == CallAudioState.ROUTE_BLUETOOTH
                ? R.drawable.ic_bluetooth : R.drawable.ic_speaker,
                bt ? R.string.audio : R.string.speaker);

        final boolean holdable = call != null
                && (call.getDetails().can(Call.Details.CAPABILITY_SUPPORT_HOLD)
                || call.getDetails().can(Call.Details.CAPABILITY_HOLD)
                || call.getState() == Call.STATE_HOLDING);
        setToggle(mHold, call != null && call.getState() == Call.STATE_HOLDING, holdable);
        setToggle(mAdd, false, call != null && mCalls.canAddCall());

        final Call primary = mShown;
        final boolean canMerge = primary != null
                && (!primary.getConferenceableCalls().isEmpty()
                || primary.getDetails().can(Call.Details.CAPABILITY_MERGE_CONFERENCE));
        final boolean canSwap = primary != null && mCalls.secondary(primary) != null;
        setToggleVisual(mMerge, canMerge ? R.drawable.ic_merge : R.drawable.ic_swap,
                canMerge ? R.string.merge : R.string.swap);
        setToggle(mMerge, false, canMerge || canSwap);
    }

    /** The talking or held call the controls act on. */
    private Call liveCall() {
        Call c = mCalls.firstWithState(Call.STATE_ACTIVE);
        if (c == null) {
            c = mCalls.firstWithState(Call.STATE_HOLDING);
        }
        if (c == null) {
            c = mCalls.firstWithState(Call.STATE_DIALING, Call.STATE_CONNECTING);
        }
        return c;
    }

    // --- actions ------------------------------------------------------------------------

    private void answer() {
        final Call c = mCalls.incoming();
        if (c != null) {
            c.answer(VideoProfile.STATE_AUDIO_ONLY);
        }
    }

    private void onMergeOrSwap() {
        final Call primary = mShown;
        if (primary == null) {
            return;
        }
        final List<Call> conferenceable = primary.getConferenceableCalls();
        if (!conferenceable.isEmpty()) {
            primary.conference(conferenceable.get(0));
        } else if (primary.getDetails().can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
            primary.mergeConference();
        } else if (primary.getDetails().can(Call.Details.CAPABILITY_SWAP_CONFERENCE)) {
            primary.swapConference();
        } else {
            final Call other = mCalls.secondary(primary);
            if (other != null && other.getState() == Call.STATE_HOLDING) {
                other.unhold();
            }
        }
    }

    private void onAudioRouteClicked(View anchor) {
        final CallAudioState audio = mCalls.audioState();
        if (audio == null) {
            return;
        }
        final int mask = audio.getSupportedRouteMask();
        if ((mask & CallAudioState.ROUTE_BLUETOOTH) == 0) {
            // earpiece/wired <-> speaker
            mCalls.setAudioRoute(audio.getRoute() == CallAudioState.ROUTE_SPEAKER
                    ? CallAudioState.ROUTE_WIRED_OR_EARPIECE : CallAudioState.ROUTE_SPEAKER);
            return;
        }
        final PopupMenu menu = new PopupMenu(this, anchor);
        if ((mask & CallAudioState.ROUTE_BLUETOOTH) != 0) {
            menu.getMenu().add(0, CallAudioState.ROUTE_BLUETOOTH, 0, R.string.route_bluetooth);
        }
        if ((mask & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            menu.getMenu().add(0, CallAudioState.ROUTE_WIRED_HEADSET, 0, R.string.route_headset);
        } else if ((mask & CallAudioState.ROUTE_EARPIECE) != 0) {
            menu.getMenu().add(0, CallAudioState.ROUTE_EARPIECE, 0, R.string.route_earpiece);
        }
        menu.getMenu().add(0, CallAudioState.ROUTE_SPEAKER, 0, R.string.speaker);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                mCalls.setAudioRoute(item.getItemId());
                return true;
            }
        });
        menu.show();
    }

    private void showReplyChooser() {
        final Call call = mCalls.incoming();
        if (call == null) {
            return;
        }
        final List<String> canned = call.getCannedTextResponses();
        final List<String> items = new ArrayList<>();
        if (canned != null) {
            items.addAll(canned);
        } else {
            items.add(getString(R.string.reply_cant_talk));
            items.add(getString(R.string.reply_call_back));
            items.add(getString(R.string.reply_on_my_way));
        }
        items.add(getString(R.string.reply_custom));
        dismissDialog();
        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reply_message)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == items.size() - 1) {
                            showCustomReply(call);
                        } else {
                            call.reject(true, items.get(which));
                        }
                    }
                })
                .show();
    }

    private void showCustomReply(final Call call) {
        final EditText field = new EditText(this);
        field.setHint(R.string.reply_hint);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        dismissDialog();
        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reply_message)
                .setView(field, pad, pad / 2, pad, 0)
                .setPositiveButton(R.string.send, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String text = field.getText().toString().trim();
                        call.reject(!text.isEmpty(), text.isEmpty() ? null : text);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAccountPicker(final Call call) {
        if (mDialog != null && mDialog.isShowing() && mDialogCall == call) {
            return;
        }
        // Telecom puts the choices in the intent extras of a call waiting for an account
        final Bundle extras = call.getDetails().getIntentExtras();
        final List<PhoneAccountHandle> handles = extras != null
                ? extras.<PhoneAccountHandle>getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS)
                : null;
        final List<PhoneAccountHandle> accounts = handles != null && !handles.isEmpty()
                ? handles : mTelecom.getCallCapablePhoneAccounts();
        final String[] labels = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            final PhoneAccount a = mTelecom.getPhoneAccount(accounts.get(i));
            labels[i] = a != null && a.getLabel() != null ? a.getLabel().toString()
                    : getString(R.string.sim_n, i + 1);
        }
        dismissDialog();
        mDialogCall = call;
        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.call_with)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mDialogCall = null;
                        call.phoneAccountSelected(accounts.get(which), false);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        mDialogCall = null;
                        call.disconnect();
                    }
                })
                .show();
    }

    private void dismissDialog() {
        if (mDialog != null) {
            mDialog.setOnCancelListener(null);
            mDialog.dismiss();
            mDialog = null;
        }
        mDialogCall = null;
    }

    private void showDtmf(boolean show) {
        if (show && liveCall() == null) {
            return;
        }
        mDtmfPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            mDtmfDigits.setText("");
        }
        updateButtons();
        updateProximity();
    }

    // --- DTMF keypad ----------------------------------------------------------------------

    @Override
    public void onKeyPressed(char key) {
        final Call c = mCalls.firstWithState(Call.STATE_ACTIVE);
        mDtmfDigits.append(String.valueOf(key));
        if (c != null) {
            c.playDtmfTone(key);
        }
    }

    @Override
    public void onKeyReleased(char key) {
        final Call c = mCalls.firstWithState(Call.STATE_ACTIVE);
        if (c != null) {
            c.stopDtmfTone();
        }
    }

    @Override
    public boolean onKeyLongPressed(char key) {
        return false;
    }

    // --- proximity ------------------------------------------------------------------------

    private void updateProximity() {
        final CallAudioState audio = mCalls.audioState();
        final int route = audio != null ? audio.getRoute() : CallAudioState.ROUTE_EARPIECE;
        final Call live = liveCall();
        final boolean atEar = live != null && mCalls.incoming() == null
                && route == CallAudioState.ROUTE_EARPIECE
                && mDtmfPanel.getVisibility() != View.VISIBLE;
        setProximity(atEar);
    }

    private void setProximity(boolean on) {
        if (mProximity == null) {
            return;
        }
        if (on && !mProximity.isHeld()) {
            mProximity.acquire();
        } else if (!on && mProximity.isHeld()) {
            mProximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        }
    }

    // --- toggles ------------------------------------------------------------------------

    private View setupToggle(int id, int icon, int label) {
        final View v = findViewById(id);
        setToggleVisual(v, icon, label);
        setToggle(v, false, true);
        KiroTouch.apply(v);
        return v;
    }

    private void setToggleVisual(View v, int icon, int label) {
        ((ImageView) v.findViewById(R.id.toggle_icon)).setImageResource(icon);
        ((TextView) v.findViewById(R.id.toggle_label)).setText(label);
        v.setContentDescription(getText(label));
    }

    private void setToggle(View v, boolean on, boolean enabled) {
        final ImageView icon = (ImageView) v.findViewById(R.id.toggle_icon);
        if (!Boolean.valueOf(on).equals(v.getTag()) || icon.getBackground() == null) {
            icon.setBackground(SquircleDrawable.button(
                    getColor(on ? R.color.kiro_accent : R.color.kiro_surface_raised),
                    getColor(R.color.kiro_ripple)));
            v.setTag(on);
        }
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(on ? R.color.kiro_on_accent : R.color.kiro_text)));
        v.setSelected(on);
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    private static String initialOf(String name, String number) {
        if (!TextUtils.isEmpty(name) && Character.isLetter(name.charAt(0))) {
            return name.substring(0, 1).toUpperCase();
        }
        return "#";
    }

    private void pulse(final View v) {
        v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(520).setInterpolator(KiroTouch.EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(520)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mCalls.incoming() != null && !isFinishing()) {
                                            pulse(v);
                                        } else {
                                            v.setTag(null);
                                        }
                                    }
                                }).start();
                    }
                }).start();
    }
}
