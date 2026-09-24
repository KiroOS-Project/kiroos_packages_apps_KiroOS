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

package com.kiroos.kai;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.wifi.WifiManager;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Phone commands kAI carries out itself, instantly and without asking a model: the torch,
 * Wi-Fi, Bluetooth, and the battery profiles (opens their page). Italian and English.
 * Returns the reply to show, or null when the text is not such a command.
 */
final class PhoneActions {

    private static final Pattern ON = Pattern.compile(
            "\\b(accendi|attiva|abilita|apri|turn on|switch on|enable|on)\\b");
    private static final Pattern OFF = Pattern.compile(
            "\\b(spegni|disattiva|disabilita|chiudi|turn off|switch off|disable|off)\\b");

    private static boolean sTorch;

    private PhoneActions() {
    }

    static String handle(Context c, String text) {
        final String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.length() > 60) {
            return null;                   // a real question, not a command
        }
        final boolean on = ON.matcher(t).find();
        final boolean off = OFF.matcher(t).find();
        if (on == off && !t.contains("profil")) {
            return null;
        }
        try {
            if (t.contains("torcia") || t.contains("torch") || t.contains("flashlight")
                    || t.contains("luce") || t.contains("flash")) {
                final CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    final Boolean has = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (has != null && has) {
                        cm.setTorchMode(id, on);
                        sTorch = on;
                        return c.getString(on ? R.string.act_torch_on : R.string.act_torch_off);
                    }
                }
                return null;
            }
            if (t.contains("wi-fi") || t.contains("wifi") || t.contains("wi fi")) {
                final WifiManager wm = (WifiManager) c.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                wm.setWifiEnabled(on);
                return c.getString(on ? R.string.act_wifi_on : R.string.act_wifi_off);
            }
            if (t.contains("bluetooth")) {
                final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                if (bt == null) {
                    return null;
                }
                if (on) {
                    bt.enable();
                } else {
                    bt.disable();
                }
                return c.getString(on ? R.string.act_bt_on : R.string.act_bt_off);
            }
            if (t.contains("profil") && (t.contains("batteria") || t.contains("battery")
                    || t.contains("risparmio") || t.contains("prestazion") || t.contains("performance")
                    || t.contains("bilanciat") || t.contains("balanced") || t.contains("saving"))) {
                c.startActivity(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return c.getString(R.string.act_profiles);
            }
        } catch (Exception e) {
            return c.getString(R.string.act_failed);
        }
        return null;
    }
}
