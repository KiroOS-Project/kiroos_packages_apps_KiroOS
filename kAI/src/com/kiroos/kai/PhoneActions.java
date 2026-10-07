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
            "\\b(accend[iae]\\w*|attiv[ai]\\w*|abilit[ai]\\w*|apri|turn on|switch on|enable|on)\\b");
    private static final Pattern OFF = Pattern.compile(
            "\\b(spegn[iae]\\w*|disattiv[ai]\\w*|disabilit[ai]\\w*|chiudi|turn off|switch off|disable|off)\\b");

    /** "portami al Colosseo", "navigate to Milan": Atlas with directions */
    private static final Pattern GO = Pattern.compile(
            "^(?:portami|accompagnami|naviga|vai|andiamo|indicazioni|percorso|navigate|"
            + "directions|take me|drive|go|get me)"
            + "(?: (?:a|al|alla|allo|ai|alle|in|nel|nella|verso|per|fino a|fino al|fino alla|"
            + "da|dal|dalla|to|towards|for)(?= )| (?:all'|dall'|nell'|fino all'))+ *(.+?)[?.!]*$");
    /** "dov'è il Pantheon", "where is Rome": Atlas showing the place */
    private static final Pattern WHERE = Pattern.compile(
            "^(?:dov'è|dov'e|dove è|dove e|dove si trova|dove sta|where is|where's|"
            + "trova sulla mappa|mostra sulla mappa|show on the map) +(.+?)"
            + "(?: sulla mappa| on the map)?[?.!]*$");
    /** "mostrami Roma sulla mappa", "show me Paris on the map": only with the map named
     *  ("show me how to..." is a question for the model) */
    private static final Pattern SHOW = Pattern.compile(
            "^(?:show me|find|mostrami|trova) +(.+?) (?:sulla mappa|on the map|in atlas|su atlas)[?.!]*$");
    private static final Pattern ARTICLE = Pattern.compile(
            "^(?:(?:il|lo|la|i|gli|le|the) +|l')");      // not the "La" of "Lazio"

    private static boolean sTorch;

    private PhoneActions() {
    }

    static String handle(Context c, String text) {
        final String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.length() > 60) {
            return null;                   // a real question, not a command
        }
        final String map = maps(c, text.trim(), t);
        if (map != null) {
            return map;
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

    /** a place to show or to go to, opened in Atlas (geo: link); null when not such a request */
    private static String maps(Context c, String text, String lower) {
        boolean go = true;
        java.util.regex.Matcher m = GO.matcher(lower);
        if (!m.matches()) {
            go = false;
            m = WHERE.matcher(lower);
            if (!m.matches()) {
                m = SHOW.matcher(lower);
                if (!m.matches()) {
                    return null;
                }
            }
        }
        // the place as typed (its capitals), without a leading article
        String place = text.length() == lower.length()
                ? text.substring(m.start(1), m.end(1)).trim()
                : lower.substring(m.start(1), m.end(1)).trim();
        final java.util.regex.Matcher a = ARTICLE.matcher(place.toLowerCase(Locale.ROOT));
        if (a.lookingAt()) {
            place = place.substring(a.end());
        }
        if (place.isEmpty()) {
            return null;
        }
        final Intent i = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(place)))
                .setPackage("com.kiroos.atlas")
                .putExtra("com.kiroos.atlas.extra.DIRECTIONS", go)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(c.getPackageManager()) == null) {
            return null;                   // no Atlas: the model answers instead
        }
        c.startActivity(i);
        return c.getString(go ? R.string.act_maps_go : R.string.act_maps_show, place);
    }
}
