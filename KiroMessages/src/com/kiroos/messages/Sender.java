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
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;
import android.util.Log;

import com.google.android.mms.ContentType;
import com.google.android.mms.InvalidHeaderValueException;
import com.google.android.mms.pdu.CharacterSets;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.PduBody;
import com.google.android.mms.pdu.PduComposer;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPart;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.SendReq;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends SMS (split in parts when long) and MMS (text + one picture, or group messages),
 * storing them in the provider as outbox and moving them to sent / failed on the result.
 */
final class Sender {

    private static final String TAG = "KiroMessages";
    /** longest side of a picture sent by MMS; keeps it well under carrier size limits */
    private static final int MMS_IMAGE_SIDE = 1280;
    private static final int MMS_MAX_BYTES = 280 * 1024;

    private Sender() {
    }

    static int subId() {
        final int id = SubscriptionManager.getDefaultSmsSubscriptionId();
        return id != SubscriptionManager.INVALID_SUBSCRIPTION_ID ? id
                : SubscriptionManager.getDefaultSubscriptionId();
    }

    private static SmsManager smsManager(int subId) {
        return subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ? SmsManager.getSmsManagerForSubscriptionId(subId) : SmsManager.getDefault();
    }

    /**
     * Sends a message to the recipients: a plain SMS for one recipient without picture,
     * an MMS otherwise.
     */
    static void send(Context context, List<String> recipients, String text, Uri image) {
        if (recipients.isEmpty()) {
            return;
        }
        if (image == null && recipients.size() == 1) {
            sendSms(context, recipients.get(0), text);
        } else {
            sendMms(context, recipients, text, image);
        }
    }

    static void sendSms(Context context, String address, String text) {
        final int sub = subId();
        final long thread = MessageStore.threadIdFor(context,
                java.util.Collections.singletonList(address));
        final Uri row = MessageStore.insertOutgoingSms(context, address, text, thread, sub);
        final SmsManager sms = smsManager(sub);
        final ArrayList<String> parts = sms.divideMessage(text);
        final ArrayList<PendingIntent> sent = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            // only the last part reports the result of the whole message
            sent.add(i == parts.size() - 1 ? SentReceiver.intent(context, row) : null);
        }
        try {
            sms.sendMultipartTextMessage(address, null, parts, sent, null);
        } catch (RuntimeException e) {
            Log.w(TAG, "SMS send failed", e);
            if (row != null) {
                MessageStore.setSmsType(context, row, Telephony.Sms.MESSAGE_TYPE_FAILED);
            }
        }
    }

    static void sendMms(Context context, List<String> recipients, String text, Uri image) {
        final SendReq req = new SendReq();
        final EncodedStringValue[] to = new EncodedStringValue[recipients.size()];
        for (int i = 0; i < to.length; i++) {
            to[i] = new EncodedStringValue(recipients.get(i));
        }
        req.setTo(to);
        req.setDate(System.currentTimeMillis() / 1000L);
        req.setMessageClass(PduHeaders.MESSAGE_CLASS_PERSONAL_STR.getBytes());
        req.setExpiry(7 * 24 * 60 * 60);
        try {
            req.setPriority(PduHeaders.PRIORITY_NORMAL);
            req.setDeliveryReport(PduHeaders.VALUE_NO);
            req.setReadReport(PduHeaders.VALUE_NO);
        } catch (InvalidHeaderValueException e) {
            Log.w(TAG, "MMS header", e); // constants are valid; optional headers
        }

        final PduBody body = new PduBody();
        final StringBuilder smil = new StringBuilder(
                "<smil><head><layout><root-layout/><region id=\"Image\" top=\"0\" left=\"0\" "
                        + "height=\"80%\" width=\"100%\"/><region id=\"Text\" top=\"80%\" "
                        + "left=\"0\" height=\"20%\" width=\"100%\"/></layout></head><body><par dur=\"5000ms\">");
        long size = 0;
        if (image != null) {
            final byte[] jpeg = compressImage(context, image);
            if (jpeg != null) {
                final PduPart part = new PduPart();
                part.setContentType(ContentType.IMAGE_JPEG.getBytes());
                part.setContentLocation("image.jpg".getBytes());
                part.setContentId("<image>".getBytes());
                part.setName("image.jpg".getBytes());
                part.setData(jpeg);
                body.addPart(part);
                size += jpeg.length;
                smil.append("<img src=\"image.jpg\" region=\"Image\"/>");
            }
        }
        if (text != null && !text.isEmpty()) {
            final PduPart part = new PduPart();
            part.setContentType(ContentType.TEXT_PLAIN.getBytes());
            part.setCharset(CharacterSets.UTF_8);
            part.setContentLocation("text.txt".getBytes());
            part.setContentId("<text>".getBytes());
            final byte[] data = text.getBytes(StandardCharsets.UTF_8);
            part.setData(data);
            body.addPart(part);
            size += data.length;
            smil.append("<text src=\"text.txt\" region=\"Text\"/>");
        }
        smil.append("</par></body></smil>");
        final PduPart smilPart = new PduPart();
        smilPart.setContentType(ContentType.APP_SMIL.getBytes());
        smilPart.setContentLocation("smil.xml".getBytes());
        smilPart.setContentId("<smil>".getBytes());
        smilPart.setData(smil.toString().getBytes(StandardCharsets.UTF_8));
        body.addPart(0, smilPart);
        req.setBody(body);
        req.setMessageSize(size);

        Uri row = null;
        try {
            row = PduPersister.getPduPersister(context).persist(req,
                    Telephony.Mms.Outbox.CONTENT_URI, true, true, null);
            final byte[] pdu = new PduComposer(context, req).make();
            final String name = MmsFileProvider.newFileName();
            MmsFileProvider.write(context, name, pdu);
            final Uri content = MmsFileProvider.uri(name);
            final PendingIntent result = MmsResultReceiver.sentIntent(context, row, name);
            smsManager(subId()).sendMultimediaMessage(context, content, null, null, result);
        } catch (Exception e) {
            Log.w(TAG, "MMS send failed", e);
            // stays in the outbox, shown as not sent
        }
    }

    /** JPEG of the picture, scaled so the MMS stays within carrier limits. */
    private static byte[] compressImage(Context context, Uri uri) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MMS_IMAGE_SIDE) {
                sample *= 2;
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) {
                return null;
            }
            final float scale = Math.min(1f,
                    (float) MMS_IMAGE_SIDE / Math.max(bmp.getWidth(), bmp.getHeight()));
            if (scale < 1f) {
                final Bitmap s = Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale),
                        true);
                bmp.recycle();
                bmp = s;
            }
            int quality = 85;
            byte[] out;
            do {
                final ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                out = bos.toByteArray();
                quality -= 15;
            } while (out.length > MMS_MAX_BYTES && quality > 20);
            bmp.recycle();
            return out;
        } catch (Exception e) {
            Log.w(TAG, "cannot read picture " + uri, e);
            return null;
        }
    }
}
