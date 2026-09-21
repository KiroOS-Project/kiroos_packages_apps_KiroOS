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

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;

import java.util.List;

/** Reply / Mark as read / dismissed, from the message notifications. */
public class NotificationActions extends BroadcastReceiver {

    static final String ACTION_REPLY = "com.kiroos.messages.REPLY";
    static final String ACTION_READ = "com.kiroos.messages.READ";
    static final String ACTION_SEEN = "com.kiroos.messages.SEEN";
    private static final String EXTRA_THREAD = "thread";

    static PendingIntent intent(Context context, String action, long thread) {
        final Intent i = new Intent(action).setClass(context, NotificationActions.class)
                .putExtra(EXTRA_THREAD, thread);
        return PendingIntent.getBroadcast(context, (action + thread).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final long thread = intent.getLongExtra(EXTRA_THREAD, -1);
        if (thread < 0) {
            return;
        }
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(context, intent, thread);
                } finally {
                    pending.finish();
                }
            }
        }, "KiroMessagesAction").start();
    }

    private static void handle(Context context, Intent intent, long thread) {
        final String action = intent.getAction();
        if (ACTION_REPLY.equals(action)) {
            final Bundle input = RemoteInput.getResultsFromIntent(intent);
            final CharSequence text = input != null ? input.getCharSequence(Notifier.KEY_REPLY)
                    : null;
            if (text != null && text.length() > 0) {
                final List<String> to = MessageStore.addressesOf(context, thread);
                Sender.send(context, to, text.toString(), null);
            }
            MessageStore.markThreadRead(context, thread);
            Notifier.cancel(context, thread);
        } else if (ACTION_READ.equals(action)) {
            MessageStore.markThreadRead(context, thread);
            Notifier.cancel(context, thread);
        } else if (ACTION_SEEN.equals(action)) {
            // swiped away: seen, still unread in the list
            final ContentValues v = new ContentValues();
            v.put(Telephony.Sms.SEEN, 1);
            try {
                context.getContentResolver().update(Telephony.Sms.CONTENT_URI, v,
                        "thread_id=" + thread + " AND seen=0", null);
            } catch (RuntimeException e) {
                // ignore
            }
        }
    }
}
