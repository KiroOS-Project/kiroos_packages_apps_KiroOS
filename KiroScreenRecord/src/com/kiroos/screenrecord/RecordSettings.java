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

package com.kiroos.screenrecord;

import android.content.Context;
import android.content.SharedPreferences;

/** The recorder's choices, kept between recordings. */
final class RecordSettings {

    static final int QUALITY_HIGH = 0; // 1080p, 60 fps
    static final int QUALITY_LOW = 1;  // 720p, 30 fps

    private static final String PREFS = "recorder";

    int quality = QUALITY_HIGH;
    int audio = ScreenRecorder.AUDIO_NONE;
    boolean touches;

    static RecordSettings load(Context context) {
        final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final RecordSettings s = new RecordSettings();
        s.quality = p.getInt("quality", QUALITY_HIGH);
        s.audio = p.getInt("audio", ScreenRecorder.AUDIO_NONE);
        s.touches = p.getBoolean("touches", false);
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("quality", quality)
                .putInt("audio", audio)
                .putBoolean("touches", touches)
                .apply();
    }

    /** the short side of the video in pixels */
    int shortSide() {
        return quality == QUALITY_LOW ? 720 : 1080;
    }

    int fps() {
        return quality == QUALITY_LOW ? 30 : 60;
    }

    /** H.264 bit rate for screen content: text and UI edges stay sharp */
    int bitrate() {
        return quality == QUALITY_LOW ? 6000000 : 16000000;
    }
}
