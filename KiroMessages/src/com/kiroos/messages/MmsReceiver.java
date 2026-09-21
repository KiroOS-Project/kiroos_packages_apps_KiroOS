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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;

/**
 * WAP_PUSH_DELIVER: an MMS is waiting on the carrier's server. The platform MmsService
 * downloads it into our file; MmsResultReceiver stores it.
 */
public class MmsReceiver extends BroadcastReceiver {

    private static final String TAG = "KiroMessages";

    @Override
    public void onReceive(Context context, Intent intent) {
        final byte[] data = intent.getByteArrayExtra("data");
        if (data == null) {
            return;
        }
        final GenericPdu pdu = new PduParser(data, true).parse();
        if (pdu == null || pdu.getMessageType() != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND) {
            return; // delivery / read reports are not shown
        }
        final byte[] location = ((NotificationInd) pdu).getContentLocation();
        if (location == null) {
            return;
        }
        final int sub = intent.getIntExtra("subscription",
                SubscriptionManager.getDefaultSmsSubscriptionId());
        final String file = MmsFileProvider.newFileName();
        try {
            final SmsManager sms = sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    ? SmsManager.getSmsManagerForSubscriptionId(sub) : SmsManager.getDefault();
            sms.downloadMultimediaMessage(context, new String(location),
                    MmsFileProvider.uri(file), null,
                    MmsResultReceiver.downloadedIntent(context, file, sub));
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start MMS download", e);
        }
    }
}
