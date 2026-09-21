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

import android.app.IntentService;
import android.content.Intent;
import android.net.Uri;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/** "Reply with a message" from the incoming call screen (RESPOND_VIA_MESSAGE). */
public class HeadlessSmsSendService extends IntentService {

    public HeadlessSmsSendService() {
        super("KiroHeadlessSms");
        setIntentRedelivery(true);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null
                || !TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            return;
        }
        final String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        final Uri data = intent.getData();
        if (TextUtils.isEmpty(text) || data == null) {
            return;
        }
        final List<String> to = recipients(data);
        if (!to.isEmpty()) {
            Sender.send(this, to, text, null);
        }
    }

    /** "smsto:123;456" → [123, 456] */
    static List<String> recipients(Uri uri) {
        final List<String> out = new ArrayList<>();
        String part = uri.getSchemeSpecificPart();
        if (part == null) {
            return out;
        }
        final int q = part.indexOf('?');
        if (q >= 0) {
            part = part.substring(0, q);
        }
        for (String r : part.split("[,;]")) {
            final String t = r.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
