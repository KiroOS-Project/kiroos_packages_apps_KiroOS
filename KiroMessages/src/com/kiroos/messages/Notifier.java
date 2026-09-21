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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.Telephony;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One notification per conversation with unread messages (the last few in a messaging
 * style), with inline Reply and Mark as read.
 */
final class Notifier {

    static final String KEY_REPLY = "reply";
    private static final int ID_FAILED = -1;
    private static final int ID_DOWNLOAD = -2;
    private static final int ID_FLASH = -3;
    private static final int MAX_LINES = 6;

    /** the conversation on screen: its messages are not notified */
    static volatile long sOpenThread = -1;

    private Notifier() {
    }

    private static final class Unread {
        final List<String[]> lines = new ArrayList<>(); // {address, text}
        long date;
    }

    /** Rebuilds the notifications from the unread messages in the provider. */
    static void notifyNew(Context context) {
        final Map<Long, Unread> byThread = new LinkedHashMap<>();
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                new String[] {Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY, Telephony.Sms.DATE},
                Telephony.Sms.READ + "=0", null, Telephony.Sms.DATE + " ASC")) {
            while (c != null && c.moveToNext()) {
                add(byThread, c.getLong(0), c.getString(1), c.getString(2), c.getLong(3));
            }
        } catch (RuntimeException e) {
            return;
        }
        try (Cursor c = context.getContentResolver().query(Telephony.Mms.Inbox.CONTENT_URI,
                new String[] {Telephony.Mms.THREAD_ID, Telephony.Mms._ID, Telephony.Mms.DATE},
                Telephony.Mms.READ + "=0", null, Telephony.Mms.DATE + " ASC")) {
            while (c != null && c.moveToNext()) {
                final MessageStore.Message m = new MessageStore.Message();
                m.id = c.getLong(1);
                final List<MessageStore.Message> one = MessageStore.messages(context, c.getLong(0));
                String address = null;
                String text = context.getString(R.string.picture);
                for (MessageStore.Message x : one) {
                    if (x.mms && x.id == m.id) {
                        address = x.address;
                        if (!TextUtils.isEmpty(x.body)) {
                            text = x.body;
                        }
                    }
                }
                add(byThread, c.getLong(0), address, text, c.getLong(2) * 1000L);
            }
        } catch (RuntimeException e) {
            // ignore MMS part errors
        }

        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final String group = "kiro_messages";
        for (Map.Entry<Long, Unread> e : byThread.entrySet()) {
            final long thread = e.getKey();
            if (thread == sOpenThread) {
                continue;
            }
            final Unread u = e.getValue();
            final String title = Names.title(context,
                    java.util.Collections.singletonList(u.lines.get(u.lines.size() - 1)[0]));
            final Notification.MessagingStyle style = new Notification.MessagingStyle(
                    context.getString(R.string.me));
            final int from = Math.max(0, u.lines.size() - MAX_LINES);
            for (int i = from; i < u.lines.size(); i++) {
                final String[] l = u.lines.get(i);
                final String sender = Names.lookup(context, l[0]);
                style.addMessage(l[1], u.date, sender != null ? sender : Names.format(l[0]));
            }
            final RemoteInput input = new RemoteInput.Builder(KEY_REPLY)
                    .setLabel(context.getString(R.string.reply)).build();
            final Notification n = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.ic_chat)
                    .setColor(context.getColor(R.color.kiro_accent))
                    .setContentTitle(title)
                    .setContentText(u.lines.get(u.lines.size() - 1)[1])
                    .setStyle(style)
                    .setWhen(u.date)
                    .setShowWhen(true)
                    .setNumber(u.lines.size())
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setAutoCancel(true)
                    .setGroup(group)
                    .setContentIntent(PendingIntent.getActivity(context, (int) thread,
                            ConversationActivity.intent(context, thread),
                            PendingIntent.FLAG_UPDATE_CURRENT))
                    .setDeleteIntent(NotificationActions.intent(context,
                            NotificationActions.ACTION_SEEN, thread))
                    .addAction(new Notification.Action.Builder(null,
                            context.getString(R.string.reply),
                            NotificationActions.intent(context, NotificationActions.ACTION_REPLY,
                                    thread)).addRemoteInput(input).build())
                    .addAction(new Notification.Action.Builder(null,
                            context.getString(R.string.mark_read),
                            NotificationActions.intent(context, NotificationActions.ACTION_READ,
                                    thread)).build())
                    .build();
            nm.notify((int) thread, n);
        }
    }

    private static void add(Map<Long, Unread> map, long thread, String address, String text,
            long date) {
        Unread u = map.get(thread);
        if (u == null) {
            u = new Unread();
            map.put(thread, u);
        }
        u.lines.add(new String[] {address, text != null ? text : ""});
        u.date = Math.max(u.date, date);
    }

    static void cancel(Context context, long thread) {
        context.getSystemService(NotificationManager.class).cancel((int) thread);
    }

    static void notifySendFailed(Context context) {
        simple(context, ID_FAILED, context.getString(R.string.send_failed_title),
                context.getString(R.string.send_failed_text));
    }

    static void notifyDownloadFailed(Context context) {
        simple(context, ID_DOWNLOAD, context.getString(R.string.download_failed_title),
                context.getString(R.string.download_failed_text));
    }

    static void notifyFlash(Context context, String address, String text) {
        final String name = Names.lookup(context, address);
        simple(context, ID_FLASH, name != null ? name : Names.format(address), text);
    }

    private static void simple(Context context, int id, String title, String text) {
        final Notification n = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_chat)
                .setColor(context.getColor(R.color.kiro_accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, id,
                        new Intent(context, ConversationListActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        context.getSystemService(NotificationManager.class).notify(id, n);
    }
}
