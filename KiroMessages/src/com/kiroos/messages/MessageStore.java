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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes the Telephony provider (SMS, MMS, threads). As the default SMS app,
 * Kiro Messages stores every received and sent message itself.
 */
final class MessageStore {

    /** A conversation in the list. */
    static final class Thread {
        long id;
        long date;
        String snippet;
        boolean unread;
        boolean hasAttachment;
        List<String> addresses = new ArrayList<>();
    }

    /** One SMS or MMS in a conversation. */
    static final class Message {
        long id;
        boolean mms;
        boolean incoming;
        long date;
        String body;
        String address;
        /** 0 sent/received, 1 sending, 2 failed */
        int status;
        final List<Uri> images = new ArrayList<>();
    }

    static final int STATUS_OK = 0;
    static final int STATUS_SENDING = 1;
    static final int STATUS_FAILED = 2;

    private static final Uri THREADS = Uri.parse("content://mms-sms/conversations?simple=true");
    private static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    private static final int THREAD_MESSAGE_LIMIT = 400;

    private MessageStore() {
    }

    // --- conversations ------------------------------------------------------------------

    static List<Thread> threads(Context context) {
        final List<Thread> out = new ArrayList<>();
        final Map<Long, String> canonical = canonicalAddresses(context);
        try (Cursor c = context.getContentResolver().query(THREADS, new String[] {
                Telephony.Threads._ID, Telephony.Threads.DATE, Telephony.Threads.SNIPPET,
                Telephony.Threads.READ, Telephony.Threads.RECIPIENT_IDS,
                Telephony.Threads.HAS_ATTACHMENT, Telephony.Threads.MESSAGE_COUNT,
        }, null, null, Telephony.Threads.DATE + " DESC")) {
            if (c == null) {
                return out;
            }
            while (c.moveToNext()) {
                if (c.getInt(6) == 0) {
                    continue; // empty thread left behind by a deleted draft
                }
                final Thread t = new Thread();
                t.id = c.getLong(0);
                t.date = c.getLong(1);
                t.snippet = c.getString(2);
                t.unread = c.getInt(3) == 0;
                t.hasAttachment = c.getInt(5) != 0;
                final String ids = c.getString(4);
                if (!TextUtils.isEmpty(ids)) {
                    for (String id : ids.split(" ")) {
                        try {
                            final String a = canonical.get(Long.parseLong(id.trim()));
                            if (a != null) {
                                t.addresses.add(a);
                            }
                        } catch (NumberFormatException e) {
                            // skip
                        }
                    }
                }
                out.add(t);
            }
        } catch (RuntimeException e) {
            // no SMS permission (not the default app yet)
        }
        return out;
    }

    private static Map<Long, String> canonicalAddresses(Context context) {
        final Map<Long, String> map = new HashMap<>();
        try (Cursor c = context.getContentResolver().query(CANONICAL,
                new String[] {"_id", "address"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                map.put(c.getLong(0), c.getString(1));
            }
        } catch (RuntimeException e) {
            // ignore
        }
        return map;
    }

    static List<String> addressesOf(Context context, long threadId) {
        for (Thread t : threads(context)) {
            if (t.id == threadId) {
                return t.addresses;
            }
        }
        return Collections.emptyList();
    }

    static long threadIdFor(Context context, List<String> addresses) {
        final Set<String> set = new HashSet<>(addresses);
        return Telephony.Threads.getOrCreateThreadId(context, set);
    }

    // --- messages -----------------------------------------------------------------------

    static List<Message> messages(Context context, long threadId) {
        final List<Message> out = new ArrayList<>();
        final String where = "thread_id=" + threadId;
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[] {Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.BODY,
                        Telephony.Sms.TYPE, Telephony.Sms.ADDRESS},
                where, null, Telephony.Sms.DATE + " DESC LIMIT " + THREAD_MESSAGE_LIMIT)) {
            while (c != null && c.moveToNext()) {
                final Message m = new Message();
                m.id = c.getLong(0);
                m.date = c.getLong(1);
                m.body = c.getString(2);
                final int type = c.getInt(3);
                m.incoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX;
                m.status = type == Telephony.Sms.MESSAGE_TYPE_FAILED ? STATUS_FAILED
                        : type == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                        || type == Telephony.Sms.MESSAGE_TYPE_QUEUED ? STATUS_SENDING : STATUS_OK;
                m.address = c.getString(4);
                out.add(m);
            }
        } catch (RuntimeException e) {
            // no permission
        }
        try (Cursor c = context.getContentResolver().query(Telephony.Mms.CONTENT_URI,
                new String[] {Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
                        Telephony.Mms.MESSAGE_TYPE},
                where, null, Telephony.Mms.DATE + " DESC LIMIT " + THREAD_MESSAGE_LIMIT)) {
            while (c != null && c.moveToNext()) {
                final Message m = new Message();
                m.mms = true;
                m.id = c.getLong(0);
                m.date = c.getLong(1) * 1000L; // MMS dates are in seconds
                final int box = c.getInt(2);
                m.incoming = box == Telephony.Mms.MESSAGE_BOX_INBOX;
                m.status = box == Telephony.Mms.MESSAGE_BOX_OUTBOX ? STATUS_FAILED : STATUS_OK;
                loadMmsParts(context, m);
                out.add(m);
            }
        } catch (RuntimeException e) {
            // no permission
        }
        Collections.sort(out, new Comparator<Message>() {
            @Override
            public int compare(Message a, Message b) {
                return Long.compare(a.date, b.date);
            }
        });
        if (out.size() > THREAD_MESSAGE_LIMIT) {
            return new ArrayList<>(out.subList(out.size() - THREAD_MESSAGE_LIMIT, out.size()));
        }
        return out;
    }

    private static void loadMmsParts(Context context, Message m) {
        final Uri parts = Uri.parse("content://mms/" + m.id + "/part");
        final StringBuilder text = new StringBuilder();
        try (Cursor c = context.getContentResolver().query(parts,
                new String[] {"_id", "ct", "text"}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                final String ct = c.getString(1);
                if ("text/plain".equals(ct)) {
                    final String t = c.getString(2);
                    if (!TextUtils.isEmpty(t)) {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(t);
                    }
                } else if (ct != null && ct.startsWith("image/")) {
                    m.images.add(Uri.parse("content://mms/part/" + c.getLong(0)));
                }
            }
        } catch (RuntimeException e) {
            // ignore
        }
        m.body = text.toString();
        // sender of an incoming MMS
        if (m.incoming) {
            try (Cursor c = context.getContentResolver().query(
                    Uri.parse("content://mms/" + m.id + "/addr"), new String[] {"address"},
                    "type=137", null, null)) { // PduHeaders.FROM
                if (c != null && c.moveToFirst()) {
                    m.address = c.getString(0);
                }
            } catch (RuntimeException e) {
                // ignore
            }
        }
    }

    /** Messages whose text contains the query, newest first (search). */
    static List<Message> search(Context context, String query, int limit) {
        final List<Message> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                new String[] {Telephony.Sms._ID, Telephony.Sms.DATE, Telephony.Sms.BODY,
                        Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.THREAD_ID},
                Telephony.Sms.BODY + " LIKE ?", new String[] {"%" + query + "%"},
                Telephony.Sms.DATE + " DESC LIMIT " + limit)) {
            while (c != null && c.moveToNext()) {
                final Message m = new Message();
                m.id = c.getLong(5); // thread id, used to open the result
                m.date = c.getLong(1);
                m.body = c.getString(2);
                m.incoming = c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX;
                m.address = c.getString(4);
                out.add(m);
            }
        } catch (RuntimeException e) {
            // no permission
        }
        return out;
    }

    // --- writes -------------------------------------------------------------------------

    static Uri insertIncomingSms(Context context, String address, String body, long sent,
            int subId) {
        final ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.DATE_SENT, sent);
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        return context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, v);
    }

    static Uri insertOutgoingSms(Context context, String address, String body, long threadId,
            int subId) {
        final ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        v.put(Telephony.Sms.THREAD_ID, threadId);
        v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        return context.getContentResolver().insert(Telephony.Sms.Outbox.CONTENT_URI, v);
    }

    static void setSmsType(Context context, Uri uri, int type) {
        final ContentValues v = new ContentValues();
        v.put(Telephony.Sms.TYPE, type);
        context.getContentResolver().update(uri, v, null, null);
    }

    static void markThreadRead(Context context, long threadId) {
        final ContentValues v = new ContentValues();
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        try {
            context.getContentResolver().update(Telephony.Sms.CONTENT_URI, v,
                    "thread_id=" + threadId + " AND (read=0 OR seen=0)", null);
            final ContentValues mv = new ContentValues();
            mv.put(Telephony.Mms.READ, 1);
            mv.put(Telephony.Mms.SEEN, 1);
            context.getContentResolver().update(Telephony.Mms.CONTENT_URI, mv,
                    "thread_id=" + threadId + " AND (read=0 OR seen=0)", null);
        } catch (RuntimeException e) {
            // not the default SMS app
        }
    }

    /** 1.2: the conversation shows as unread again (its last received SMS is unread) */
    static void markThreadUnread(Context context, long threadId) {
        try (android.database.Cursor c = context.getContentResolver().query(
                Telephony.Sms.CONTENT_URI, new String[] {Telephony.Sms._ID},
                "thread_id=" + threadId + " AND type=" + Telephony.Sms.MESSAGE_TYPE_INBOX, null,
                Telephony.Sms.DATE + " DESC LIMIT 1")) {
            if (c != null && c.moveToFirst()) {
                final ContentValues v = new ContentValues();
                v.put(Telephony.Sms.READ, 0);
                context.getContentResolver().update(android.content.ContentUris.withAppendedId(
                        Telephony.Sms.CONTENT_URI, c.getLong(0)), v, null, null);
            }
        } catch (RuntimeException e) {
            // not the default SMS app
        }
    }

    static void deleteThread(Context context, long threadId) {
        context.getContentResolver().delete(
                Uri.parse("content://mms-sms/conversations/" + threadId), null, null);
    }

    static void deleteMessage(Context context, Message m) {
        final Uri uri = Uri.parse((m.mms ? "content://mms/" : "content://sms/") + m.id);
        context.getContentResolver().delete(uri, null, null);
    }
}
