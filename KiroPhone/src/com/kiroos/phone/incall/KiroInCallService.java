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

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * Telecom binds this while there are calls. It feeds the CallList, shows the in-call screen
 * for outgoing calls and keeps the call notifications in sync. Ringing and vibration are
 * done by Telecom itself (no IN_CALL_SERVICE_RINGING meta-data).
 */
public class KiroInCallService extends InCallService {

    private InCallNotifier mNotifier;

    @Override
    public void onCreate() {
        super.onCreate();
        CallList.get().setService(this);
        mNotifier = new InCallNotifier(this);
        CallList.get().addListener(mNotifier);
    }

    @Override
    public void onDestroy() {
        CallList.get().removeListener(mNotifier);
        mNotifier.cancelAll();
        CallList.get().setService(null);
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        CallList.get().onCallAdded(call);
        if (call.getState() != Call.STATE_RINGING) {
            // outgoing (or account selection): straight to the call screen.
            // Incoming calls go through the notification's full-screen intent, which shows
            // the screen when the phone is locked or off and a heads-up otherwise.
            InCallActivity.show(this, false);
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        CallerInfoCache.invalidate(CallerInfoCache.numberOf(call));
        CallList.get().onCallRemoved(call);
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState audioState) {
        CallList.get().onAudioStateChanged(audioState);
    }

    @Override
    public void onBringToForeground(boolean showDialpad) {
        InCallActivity.show(this, showDialpad);
    }

    @Override
    public void onCanAddCallChanged(boolean canAddCall) {
        CallList.get().onCanAddCallChanged(canAddCall);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        CallList.get().notifyChanged();
        return super.onUnbind(intent);
    }
}
