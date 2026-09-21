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

package com.kiroos.pulse;

import android.content.SharedPreferences;

/**
 * 10-band graphic equalizer (31 Hz … 16 kHz, ±12 dB) as peaking biquads (RBJ cookbook,
 * one octave wide), plus a pre-amp. Runs on the decoded PCM before it reaches AudioTrack,
 * so it works the same on every device and sample rate.
 */
final class Equalizer10 {

    static final int[] FREQUENCIES = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final int BANDS = FREQUENCIES.length;
    static final float MAX_DB = 12f;

    /** name resource → gains in dB */
    static final Object[][] PRESETS = {
            {R.string.preset_flat, new float[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0}},
            {R.string.preset_bass, new float[] {6, 5, 4, 2, 0, 0, 0, 0, 0, 0}},
            {R.string.preset_vocal, new float[] {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1}},
            {R.string.preset_treble, new float[] {0, 0, 0, 0, 0, 1, 2, 4, 5, 6}},
            {R.string.preset_rock, new float[] {5, 4, 2, 0, -1, -1, 1, 3, 4, 5}},
            {R.string.preset_pop, new float[] {-1, 0, 2, 3, 4, 3, 1, 0, -1, -1}},
            {R.string.preset_classical, new float[] {4, 3, 2, 1, 0, 0, 0, 1, 2, 3}},
            {R.string.preset_electronic, new float[] {5, 4, 1, 0, -2, 1, 0, 2, 4, 5}},
    };

    private static Equalizer10 sShared;

    /** One equalizer per process: the player and the equalizer screen share it. */
    static synchronized Equalizer10 shared(SharedPreferences prefs) {
        if (sShared == null) {
            sShared = new Equalizer10();
            sShared.load(prefs);
        }
        return sShared;
    }

    private final float[] mGains = new float[BANDS];
    private float mPreampDb;
    private boolean mEnabled;
    private int mSampleRate;
    private int mChannels;
    /** per band: b0 b1 b2 a1 a2 (normalised) */
    private final float[][] mCoef = new float[BANDS][5];
    /** per band and channel: x1 x2 y1 y2 */
    private float[][][] mState = new float[BANDS][2][4];
    private volatile boolean mDirty = true;
    private float mPreamp = 1f;

    synchronized void load(SharedPreferences p) {
        mEnabled = p.getBoolean("eq_on", false);
        mPreampDb = p.getFloat("eq_preamp", 0f);
        for (int i = 0; i < BANDS; i++) {
            mGains[i] = p.getFloat("eq_" + i, 0f);
        }
        mDirty = true;
    }

    synchronized void save(SharedPreferences p) {
        final SharedPreferences.Editor e = p.edit().putBoolean("eq_on", mEnabled)
                .putFloat("eq_preamp", mPreampDb);
        for (int i = 0; i < BANDS; i++) {
            e.putFloat("eq_" + i, mGains[i]);
        }
        e.apply();
    }

    synchronized void setEnabled(boolean on) {
        mEnabled = on;
        mDirty = true;
    }

    synchronized boolean isEnabled() {
        return mEnabled;
    }

    synchronized void setGain(int band, float db) {
        mGains[band] = Math.max(-MAX_DB, Math.min(MAX_DB, db));
        mDirty = true;
    }

    synchronized float gain(int band) {
        return mGains[band];
    }

    synchronized void setPreamp(float db) {
        mPreampDb = Math.max(-12f, Math.min(6f, db));
        mDirty = true;
    }

    synchronized float preamp() {
        return mPreampDb;
    }

    synchronized void setGains(float[] gains) {
        System.arraycopy(gains, 0, mGains, 0, BANDS);
        mDirty = true;
    }

    /** New stream format: coefficients depend on the sample rate. */
    synchronized void configure(int sampleRate, int channels) {
        mSampleRate = sampleRate;
        mChannels = Math.max(1, Math.min(2, channels));
        mState = new float[BANDS][2][4];
        mDirty = true;
    }

    private void recompute() {
        mPreamp = (float) Math.pow(10, mPreampDb / 20.0);
        for (int b = 0; b < BANDS; b++) {
            final double f = Math.min(FREQUENCIES[b], mSampleRate * 0.45);
            final double a = Math.pow(10, mGains[b] / 40.0);
            final double w0 = 2 * Math.PI * f / mSampleRate;
            final double q = 1.41; // about one octave
            final double alpha = Math.sin(w0) / (2 * q);
            final double cos = Math.cos(w0);
            final double a0 = 1 + alpha / a;
            mCoef[b][0] = (float) ((1 + alpha * a) / a0);
            mCoef[b][1] = (float) ((-2 * cos) / a0);
            mCoef[b][2] = (float) ((1 - alpha * a) / a0);
            mCoef[b][3] = (float) ((-2 * cos) / a0);
            mCoef[b][4] = (float) ((1 - alpha / a) / a0);
        }
        mDirty = false;
    }

    /** Filters interleaved 16-bit PCM in place. */
    void process(short[] pcm, int length) {
        final boolean enabled;
        synchronized (this) {
            enabled = mEnabled && mSampleRate > 0;
            if (enabled && mDirty) {
                recompute();
            }
        }
        if (!enabled) {
            return;
        }
        final int ch = mChannels;
        final float pre = mPreamp;
        // skip flat bands: most presets touch only a few
        final boolean[] active = new boolean[BANDS];
        for (int b = 0; b < BANDS; b++) {
            active[b] = Math.abs(mGains[b]) > 0.05f;
        }
        for (int i = 0; i < length; i++) {
            final int c = i % ch;
            float x = pcm[i] * pre;
            for (int b = 0; b < BANDS; b++) {
                if (!active[b]) {
                    continue;
                }
                final float[] k = mCoef[b];
                final float[] s = mState[b][c];
                final float y = k[0] * x + k[1] * s[0] + k[2] * s[1] - k[3] * s[2] - k[4] * s[3];
                s[1] = s[0];
                s[0] = x;
                s[3] = s[2];
                s[2] = y;
                x = y;
            }
            // clamp instead of wrapping around (the pre-amp gives headroom for boosts)
            if (x > 32767f) {
                x = 32767f;
            } else if (x < -32768f) {
                x = -32768f;
            }
            pcm[i] = (short) x;
        }
    }
}
