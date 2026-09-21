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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Call;

/** Decline and Hang up buttons of the call notification. */
public class NotificationActions extends BroadcastReceiver {

    static final String ACTION_DECLINE = "com.kiroos.phone.DECLINE";
    static final String ACTION_HANG_UP = "com.kiroos.phone.HANG_UP";

    static PendingIntent intent(Context context, String action) {
        return PendingIntent.getBroadcast(context, action.hashCode(),
                new Intent(action).setClass(context, NotificationActions.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final CallList list = CallList.get();
        if (ACTION_DECLINE.equals(intent.getAction())) {
            final Call ringing = list.incoming();
            if (ringing != null) {
                ringing.reject(false, null);
            }
        } else if (ACTION_HANG_UP.equals(intent.getAction())) {
            final Call call = list.primary();
            if (call != null) {
                call.disconnect();
            }
        }
    }
}
