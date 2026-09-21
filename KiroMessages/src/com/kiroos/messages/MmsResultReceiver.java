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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.RetrieveConf;

/** Results from the platform MmsService: an MMS was sent, or downloaded. */
public class MmsResultReceiver extends BroadcastReceiver {

    private static final String TAG = "KiroMessages";
    private static final String ACTION_SENT = "com.kiroos.messages.MMS_SENT";
    private static final String ACTION_DOWNLOADED = "com.kiroos.messages.MMS_DOWNLOADED";
    private static final String EXTRA_ROW = "row";
    private static final String EXTRA_FILE = "file";
    private static final String EXTRA_SUB = "sub";

    static PendingIntent sentIntent(Context context, Uri row, String file) {
        final Intent i = new Intent(ACTION_SENT, row, context, MmsResultReceiver.class)
                .putExtra(EXTRA_ROW, row).putExtra(EXTRA_FILE, file);
        return PendingIntent.getBroadcast(context, file.hashCode(), i,
                PendingIntent.FLAG_ONE_SHOT);
    }

    static PendingIntent downloadedIntent(Context context, String file, int subId) {
        final Intent i = new Intent(ACTION_DOWNLOADED, MmsFileProvider.uri(file), context,
                MmsResultReceiver.class).putExtra(EXTRA_FILE, file).putExtra(EXTRA_SUB, subId);
        return PendingIntent.getBroadcast(context, file.hashCode(), i,
                PendingIntent.FLAG_ONE_SHOT);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        final PendingResult pending = goAsync();
        final int result = getResultCode();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACTION_SENT.equals(intent.getAction())) {
                        onSent(context, intent, result);
                    } else if (ACTION_DOWNLOADED.equals(intent.getAction())) {
                        onDownloaded(context, intent, result);
                    }
                } finally {
                    pending.finish();
                }
            }
        }, "KiroMmsResult").start();
    }

    private static void onSent(Context context, Intent intent, int result) {
        final Uri row = intent.getParcelableExtra(EXTRA_ROW);
        final String file = intent.getStringExtra(EXTRA_FILE);
        MmsFileProvider.delete(context, file);
        if (row == null) {
            return;
        }
        if (result == Activity.RESULT_OK) {
            try {
                PduPersister.getPduPersister(context).move(row, Telephony.Mms.Sent.CONTENT_URI);
            } catch (Exception e) {
                Log.w(TAG, "cannot move sent MMS", e);
            }
        } else {
            Log.w(TAG, "MMS not sent, result " + result);
            // stays in the outbox: shown as "not sent" with a retry option
        }
    }

    private static void onDownloaded(Context context, Intent intent, int result) {
        final String file = intent.getStringExtra(EXTRA_FILE);
        try {
            if (result != Activity.RESULT_OK) {
                Log.w(TAG, "MMS download failed, result " + result);
                Notifier.notifyDownloadFailed(context);
                return;
            }
            final byte[] data = MmsFileProvider.read(context, file);
            final GenericPdu pdu = new PduParser(data, true).parse();
            if (!(pdu instanceof RetrieveConf)
                    || pdu.getMessageType() != PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) {
                Log.w(TAG, "unexpected MMS pdu");
                return;
            }
            final Uri row = PduPersister.getPduPersister(context).persist(pdu,
                    Telephony.Mms.Inbox.CONTENT_URI, true, true, null);
            final ContentValues v = new ContentValues();
            v.put(Telephony.Mms.READ, 0);
            v.put(Telephony.Mms.SEEN, 0);
            v.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
            v.put(Telephony.Mms.SUBSCRIPTION_ID, intent.getIntExtra(EXTRA_SUB, -1));
            context.getContentResolver().update(row, v, null, null);
            Notifier.notifyNew(context);
        } catch (Exception e) {
            Log.w(TAG, "cannot store downloaded MMS", e);
        } finally {
            MmsFileProvider.delete(context, file);
        }
    }
}
