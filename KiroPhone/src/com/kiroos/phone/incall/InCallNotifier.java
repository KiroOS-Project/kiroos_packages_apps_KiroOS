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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;

import com.kiroos.phone.R;

import java.util.Objects;

/**
 * The call notification: a full-screen, heads-up "incoming call" with Answer / Decline, or
 * the ongoing call with a running timer and Hang up. Telecom posts missed-call notifications
 * itself.
 */
final class InCallNotifier implements CallList.Listener, CallerInfoCache.Callback {

    private static final int ID = 1;

    private final Context mContext;
    private final NotificationManager mNm;
    private String mShownKey;

    InCallNotifier(Context context) {
        mContext = context;
        mNm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    void cancelAll() {
        CallerInfoCache.forget(this);
        mNm.cancel(ID);
        mShownKey = null;
    }

    @Override
    public void onCallerInfo(String number, CallerInfoCache.Info info) {
        mShownKey = null;
        onCallsChanged();
    }

    @Override
    public void onAudioStateChanged(CallAudioState state) {
    }

    @Override
    public void onCallsChanged() {
        final CallList list = CallList.get();
        final Call incoming = list.incoming();
        final Call call = incoming != null ? incoming : list.primary();
        if (call == null || call.getState() == Call.STATE_DISCONNECTED) {
            if (mShownKey != null) {
                mNm.cancel(ID);
                mShownKey = null;
            }
            return;
        }
        final String number = CallerInfoCache.numberOf(call);
        final CallerInfoCache.Info info = CallerInfoCache.get(mContext, number, this);
        final String name = CallerInfoCache.displayName(mContext, call, info);
        final int state = call.getState();
        final long connect = call.getDetails() != null ? call.getDetails().getConnectTimeMillis() : 0;
        final String key = state + "|" + name + "|" + connect + "|" + (info != null);
        if (Objects.equals(key, mShownKey)) {
            return;
        }
        mShownKey = key;

        final Notification.Builder b = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setColor(mContext.getColor(R.color.kiro_accent))
                .setContentTitle(name)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(activityIntent(0, null));
        if (info != null && info.photo != null) {
            b.setLargeIcon(info.photo);
        }

        if (state == Call.STATE_RINGING) {
            b.setContentText(mContext.getString(R.string.incoming_call))
                    .setPriority(Notification.PRIORITY_MAX)
                    .setFullScreenIntent(activityIntent(1, null), true)
                    .addAction(new Notification.Action.Builder(null,
                            mContext.getString(R.string.decline),
                            NotificationActions.intent(mContext, NotificationActions.ACTION_DECLINE))
                            .build())
                    .addAction(new Notification.Action.Builder(null,
                            mContext.getString(R.string.answer),
                            activityIntent(2, InCallActivity.EXTRA_ANSWER)).build());
        } else {
            b.setPriority(Notification.PRIORITY_DEFAULT)
                    .setContentText(mContext.getString(stateText(state)))
                    .addAction(new Notification.Action.Builder(null,
                            mContext.getString(R.string.hang_up),
                            NotificationActions.intent(mContext, NotificationActions.ACTION_HANG_UP))
                            .build());
            if (state == Call.STATE_ACTIVE && connect > 0) {
                b.setUsesChronometer(true).setWhen(connect).setShowWhen(true);
            } else {
                b.setShowWhen(false);
            }
        }
        mNm.notify(ID, b.build());
    }

    static int stateText(int state) {
        switch (state) {
            case Call.STATE_RINGING:
                return R.string.incoming_call;
            case Call.STATE_DIALING:
            case Call.STATE_CONNECTING:
            case Call.STATE_NEW:
            case Call.STATE_SELECT_PHONE_ACCOUNT:
                return R.string.dialing;
            case Call.STATE_HOLDING:
                return R.string.on_hold;
            case Call.STATE_DISCONNECTING:
            case Call.STATE_DISCONNECTED:
                return R.string.call_ended;
            default:
                return R.string.ongoing_call;
        }
    }

    private PendingIntent activityIntent(int requestCode, String extra) {
        final Intent i = InCallActivity.intent(mContext, false);
        if (extra != null) {
            i.putExtra(extra, true);
        }
        return PendingIntent.getActivity(mContext, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
