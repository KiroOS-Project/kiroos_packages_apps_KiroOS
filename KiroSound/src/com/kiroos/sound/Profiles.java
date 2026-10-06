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

package com.kiroos.sound;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One sound profile per output (speaker, headphones, Bluetooth): on/off, a preset or custom
 * band levels, bass and surround. Kept in device protected storage, so the persistent process
 * applies them before the first unlock too.
 */
final class Profiles {

    static final int SPEAKER = 0;
    static final int HEADPHONES = 1;
    static final int BLUETOOTH = 2;

    /** the five bands of the platform equalizer (NXP bundle): 60, 230, 910, 3600, 14000 Hz */
    static final int BANDS = 5;

    static final String CUSTOM = "custom";
    static final String[] PRESETS = {
        "flat", "bass", "warm", "rock", "pop", "vocal", "treble",
    };
    /** millibels per band; kept within +-6 dB so a preset can't clip the speaker */
    private static final short[][] LEVELS = {
        { 0, 0, 0, 0, 0 },                  // flat
        { 600, 400, 0, 0, 0 },              // bass
        { 200, 300, 0, -100, -200 },        // warm
        { 500, 300, -100, 300, 500 },       // rock
        { -100, 200, 400, 200, -100 },      // pop
        { -200, 0, 400, 300, 0 },           // vocal
        { 0, 0, 0, 400, 600 },              // treble
    };

    private final SharedPreferences mPrefs;

    Profiles(Context context) {
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("sound", Context.MODE_PRIVATE);
    }

    private static String key(int output, String what) {
        return "o" + output + "_" + what;
    }

    boolean enabled(int output) {
        return mPrefs.getBoolean(key(output, "on"), true);
    }

    void setEnabled(int output, boolean on) {
        mPrefs.edit().putBoolean(key(output, "on"), on).apply();
    }

    String preset(int output) {
        return mPrefs.getString(key(output, "preset"), PRESETS[0]);
    }

    void setPreset(int output, String preset) {
        mPrefs.edit().putString(key(output, "preset"), preset).apply();
    }

    private static int presetIndex(String preset) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equals(preset)) {
                return i;
            }
        }
        return -1;
    }

    /** the level of a band in millibels, from the preset or the custom curve */
    short level(int output, int band) {
        final int p = presetIndex(preset(output));
        if (p >= 0) {
            return LEVELS[p][band];
        }
        return (short) mPrefs.getInt(key(output, "b" + band), 0);
    }

    /** moving a band makes the profile custom, starting from the curve it had */
    void setLevel(int output, int band, short level) {
        final SharedPreferences.Editor e = mPrefs.edit();
        if (presetIndex(preset(output)) >= 0) {
            for (int b = 0; b < BANDS; b++) {
                e.putInt(key(output, "b" + b), level(output, b));
            }
            e.putString(key(output, "preset"), CUSTOM);
        }
        e.putInt(key(output, "b" + band), level).apply();
    }

    boolean isFlat(int output) {
        for (int b = 0; b < BANDS; b++) {
            if (level(output, b) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 0-1000 */
    int bass(int output) {
        return mPrefs.getInt(key(output, "bass"), 0);
    }

    void setBass(int output, int strength) {
        mPrefs.edit().putInt(key(output, "bass"), strength).apply();
    }

    /** 0-1000, headphones and Bluetooth only */
    int surround(int output) {
        return output == SPEAKER ? 0 : mPrefs.getInt(key(output, "surround"), 0);
    }

    void setSurround(int output, int strength) {
        mPrefs.edit().putInt(key(output, "surround"), strength).apply();
    }

    void reset(int output) {
        final SharedPreferences.Editor e = mPrefs.edit();
        for (String what : new String[] { "on", "preset", "bass", "surround" }) {
            e.remove(key(output, what));
        }
        for (int b = 0; b < BANDS; b++) {
            e.remove(key(output, "b" + b));
        }
        e.apply();
    }
}
