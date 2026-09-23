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

package com.kiroos.phone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.TelecomManager;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Places calls through Telecom (it asks the in-call screen for a SIM when several can call)
 * and handles the codes the dialer answers itself: *#06# and *#*#code#*#*.
 */
final class CallStarter {

    private static final Pattern SECRET_CODE = Pattern.compile("^\\*#\\*#(\\d+)#\\*#\\*$");

    private CallStarter() {
    }

    /** Strips formatting, keeping the dialable characters (digits + * # , ;). */
    static String dialable(String typed) {
        if (typed == null) {
            return "";
        }
        return PhoneNumberUtils.stripSeparators(
                PhoneNumberUtils.convertKeypadLettersToDigits(typed.trim()));
    }

    /**
     * Handles a code typed on the keypad without pressing call.
     * @return true if it was a special code (the keypad then clears)
     */
    static boolean handleSpecialCode(Activity activity, String typed) {
        final String s = dialable(typed);
        if ("*#06#".equals(s)) {
            showImei(activity);
            return true;
        }
        final Matcher m = SECRET_CODE.matcher(s);
        if (m.matches()) {
            final Intent i = new Intent("android.provider.Telephony.SECRET_CODE",
                    Uri.parse("android_secret_code://" + m.group(1)));
            i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            activity.sendBroadcast(i);
            return true;
        }
        return false;
    }

    static boolean call(Context context, String typed) {
        final String number = dialable(typed);
        if (TextUtils.isEmpty(number)) {
            return false;
        }
        final Uri uri = PhoneNumberUtils.isUriNumber(number)
                ? Uri.fromParts(PhoneAccount.SCHEME_SIP, number, null)
                : Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null);
        return place(context, uri);
    }

    /**
     * Calls the voicemail. Without a voicemail number on the SIM (or with no SIM) Telecom
     * would start a call to nothing that ends at once: explain it instead and offer the
     * call settings, where the number is set.
     */
    static boolean callVoicemail(final Context context) {
        final TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String number = null;
        try {
            number = tm.getVoiceMailNumber();
        } catch (SecurityException e) {
            // no phone permission: Telecom will tell
        }
        if (TextUtils.isEmpty(number)) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.voicemail)
                    .setMessage(tm.getSimState() == TelephonyManager.SIM_STATE_READY
                            ? R.string.no_voicemail_number : R.string.no_sim_voicemail)
                    .setPositiveButton(R.string.call_settings,
                            new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            try {
                                context.startActivity(new Intent(
                                        TelecomManager.ACTION_SHOW_CALL_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (android.content.ActivityNotFoundException e) {
                                // no call settings screen
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return false;
        }
        return place(context, Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null));
    }

    private static boolean place(Context context, Uri uri) {
        final TelecomManager telecom =
                (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        try {
            telecom.placeCall(uri, new Bundle());
            return true;
        } catch (SecurityException e) {
            Toast.makeText(context, R.string.no_call_permission, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static void showImei(Activity activity) {
        final TelephonyManager tm =
                (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        final StringBuilder b = new StringBuilder();
        final int slots = Math.max(1, tm.getPhoneCount());
        for (int i = 0; i < slots; i++) {
            String id;
            try {
                id = tm.getDeviceId(i);
            } catch (SecurityException e) {
                id = null;
            }
            if (b.length() > 0) {
                b.append('\n');
            }
            if (slots > 1) {
                b.append(activity.getString(R.string.sim_n, i + 1)).append(": ");
            }
            b.append(TextUtils.isEmpty(id) ? activity.getString(R.string.unavailable) : id);
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.imei)
                .setMessage(b.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
