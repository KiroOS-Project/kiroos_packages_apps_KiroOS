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
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;

/** SMS_DELIVER: joins the parts, stores the message in the inbox and notifies. */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        final SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) {
            return;
        }
        final StringBuilder body = new StringBuilder();
        for (SmsMessage p : parts) {
            if (p != null && p.getMessageBody() != null) {
                body.append(p.getMessageBody());
            }
        }
        final String address = parts[0].getDisplayOriginatingAddress();
        final long sent = parts[0].getTimestampMillis();
        final int sub = intent.getIntExtra("subscription",
                SubscriptionManager.getDefaultSmsSubscriptionId());
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (parts[0].getMessageClass() == SmsMessage.MessageClass.CLASS_0) {
                        // flash SMS: shown, not stored
                        Notifier.notifyFlash(context, address, body.toString());
                    } else {
                        MessageStore.insertIncomingSms(context, address, body.toString(), sent, sub);
                        Notifier.notifyNew(context);
                    }
                } finally {
                    pending.finish();
                }
            }
        }, "KiroSmsReceive").start();
    }
}
