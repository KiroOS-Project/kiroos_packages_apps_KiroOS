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

import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.ArrayList;
import java.util.List;

/**
 * The calls Telecom handed to the in-call service, shared by the in-call screen and the
 * notifications. Everything runs on the main thread (Telecom calls the service there).
 */
public final class CallList {

    public interface Listener {
        /** any call was added, removed or changed state/details */
        void onCallsChanged();

        void onAudioStateChanged(CallAudioState state);
    }

    private static final CallList INSTANCE = new CallList();

    private final List<Call> mCalls = new ArrayList<>();
    private final List<Listener> mListeners = new ArrayList<>();
    private InCallService mService;
    private CallAudioState mAudioState;
    private boolean mCanAddCall = true;

    private final Call.Callback mCallback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int state) {
            notifyChanged();
        }

        @Override
        public void onDetailsChanged(Call call, Call.Details details) {
            notifyChanged();
        }

        @Override
        public void onChildrenChanged(Call call, List<Call> children) {
            notifyChanged();
        }

        @Override
        public void onParentChanged(Call call, Call parent) {
            notifyChanged();
        }

        @Override
        public void onConferenceableCallsChanged(Call call, List<Call> calls) {
            notifyChanged();
        }

        @Override
        public void onCannedTextResponsesLoaded(Call call, List<String> responses) {
            notifyChanged();
        }

        @Override
        public void onPostDialWait(Call call, String remaining) {
            for (Listener l : new ArrayList<>(mListeners)) {
                if (l instanceof PostDialListener) {
                    ((PostDialListener) l).onPostDialWait(call, remaining);
                }
            }
        }

        @Override
        public void onCallDestroyed(Call call) {
            call.unregisterCallback(this);
        }
    };

    /** Listeners that also want post-dial "wait" prompts (the in-call screen). */
    public interface PostDialListener extends Listener {
        void onPostDialWait(Call call, String remaining);
    }

    private CallList() {
    }

    public static CallList get() {
        return INSTANCE;
    }

    void setService(InCallService service) {
        mService = service;
    }

    InCallService service() {
        return mService;
    }

    void onCallAdded(Call call) {
        mCalls.add(call);
        call.registerCallback(mCallback);
        notifyChanged();
    }

    void onCallRemoved(Call call) {
        mCalls.remove(call);
        call.unregisterCallback(mCallback);
        notifyChanged();
    }

    void onAudioStateChanged(CallAudioState state) {
        mAudioState = state;
        for (Listener l : new ArrayList<>(mListeners)) {
            l.onAudioStateChanged(state);
        }
    }

    void onCanAddCallChanged(boolean canAddCall) {
        mCanAddCall = canAddCall;
        notifyChanged();
    }

    public void addListener(Listener l) {
        if (!mListeners.contains(l)) {
            mListeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        mListeners.remove(l);
    }

    public void notifyChanged() {
        for (Listener l : new ArrayList<>(mListeners)) {
            l.onCallsChanged();
        }
    }

    public List<Call> calls() {
        return new ArrayList<>(mCalls);
    }

    public boolean isEmpty() {
        return mCalls.isEmpty();
    }

    public CallAudioState audioState() {
        return mAudioState;
    }

    public boolean canAddCall() {
        return mCanAddCall;
    }

    /** Top-level calls only (conference children are shown through their parent). */
    public List<Call> topLevel() {
        final List<Call> out = new ArrayList<>();
        for (Call c : mCalls) {
            if (c.getParent() == null) {
                out.add(c);
            }
        }
        return out;
    }

    public Call firstWithState(int... states) {
        for (Call c : topLevel()) {
            for (int s : states) {
                if (c.getState() == s) {
                    return c;
                }
            }
        }
        return null;
    }

    public Call incoming() {
        return firstWithState(Call.STATE_RINGING);
    }

    /**
     * The call the in-call screen is about: ringing first, then one being set up, then the
     * active one, then a held one, then whatever is left (disconnecting).
     */
    public Call primary() {
        Call c = firstWithState(Call.STATE_RINGING);
        if (c == null) {
            c = firstWithState(Call.STATE_SELECT_PHONE_ACCOUNT, Call.STATE_CONNECTING,
                    Call.STATE_DIALING, Call.STATE_NEW);
        }
        if (c == null) {
            c = firstWithState(Call.STATE_ACTIVE);
        }
        if (c == null) {
            c = firstWithState(Call.STATE_HOLDING);
        }
        if (c == null) {
            c = firstWithState(Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED);
        }
        return c;
    }

    /** The other live call, shown in the "on hold" bar. */
    public Call secondary(Call primary) {
        for (Call c : topLevel()) {
            if (c != primary && c.getState() != Call.STATE_DISCONNECTED
                    && c.getState() != Call.STATE_DISCONNECTING) {
                return c;
            }
        }
        return null;
    }

    /** True while a call is being set up or talking (not only ringing or ended). */
    public boolean hasLiveCall() {
        return firstWithState(Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING,
                Call.STATE_CONNECTING, Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT) != null;
    }

    // --- audio helpers (through the bound service) --------------------------------------

    public void setMuted(boolean muted) {
        if (mService != null) {
            mService.setMuted(muted);
        }
    }

    public void setAudioRoute(int route) {
        if (mService != null) {
            mService.setAudioRoute(route);
        }
    }
}
