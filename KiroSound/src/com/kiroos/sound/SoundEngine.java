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

import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The effects of Kiro Sound: the KiroOS Equalizer (ten bands and the bass) and the virtualizer
 * on the output mix
 * (audio session 0), which every music app plays through, so nothing is applied twice (the old
 * Settings equalizer added per-app copies on top of the global one). They live in Kiro Sound's
 * persistent process, started by Android at boot and never reclaimed.
 *
 * The profile follows the output: speaker, wired headphones or Bluetooth. An effect is only
 * enabled when it changes something (a flat curve leaves the music untouched, and compressed
 * offload playback stays available). If audioserver restarts, the effects are created again.
 */
final class SoundEngine {

    private static final String TAG = "KiroSound";
    /** above apps that ask for the same effects on the output mix */
    private static final int PRIORITY = 1000;
    private static final long HEALTH_MS = 60 * 1000;
    private static final long RETRY_MS = 3000;

    interface Listener {
        void onSoundChanged();
    }

    private static SoundEngine sInstance;

    static synchronized SoundEngine get(Context context) {
        if (sInstance == null) {
            sInstance = new SoundEngine(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private final AudioManager mAudio;
    final Profiles profiles;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<Listener>();

    private Equalizer mEq;
    private Virtualizer mVirtualizer;
    private int mOutput = Profiles.SPEAKER;
    private boolean mStarted;

    private SoundEngine(Context context) {
        mContext = context;
        mAudio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        profiles = new Profiles(context);
    }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        final IntentFilter routes = new IntentFilter();
        routes.addAction(AudioManager.ACTION_HEADSET_PLUG);
        routes.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        routes.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // the route settles a moment after the broadcast
                mHandler.removeCallbacks(mRouteCheck);
                mHandler.postDelayed(mRouteCheck, 400);
            }
        }, routes);
        mOutput = currentOutput();
        apply();
        mHandler.postDelayed(mHealth, HEALTH_MS);
    }

    private final Runnable mRouteCheck = new Runnable() {
        @Override
        public void run() {
            final int output = currentOutput();
            if (output != mOutput) {
                Log.i(TAG, "output " + mOutput + " -> " + output);
                mOutput = output;
                apply();
            }
        }
    };

    /** audioserver restarts drop every effect: check now and then, create them again */
    private final Runnable mHealth = new Runnable() {
        @Override
        public void run() {
            if (!alive()) {
                Log.w(TAG, "effects lost (audioserver restart?), creating them again");
                release();
                apply();
            }
            mHandler.postDelayed(this, HEALTH_MS);
        }
    };

    private final Runnable mRetry = new Runnable() {
        @Override
        public void run() {
            apply();
        }
    };

    @SuppressWarnings("deprecation")
    int currentOutput() {
        if (mAudio.isBluetoothA2dpOn()) {
            return Profiles.BLUETOOTH;
        }
        if (mAudio.isWiredHeadsetOn()) {
            return Profiles.HEADPHONES;
        }
        return Profiles.SPEAKER;
    }

    int output() {
        return mOutput;
    }

    /** the band centres in Hz, from the platform equalizer (0 when it isn't available) */
    int[] bandCenters() {
        final int[] hz = Profiles.CENTERS.clone();
        try {
            ensureEffects();
            if (mEq != null) {
                final int n = Math.min(Profiles.BANDS, mEq.getNumberOfBands());
                for (short b = 0; b < n; b++) {
                    hz[b] = mEq.getCenterFreq(b) / 1000;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "band centres", e);
        }
        return hz;
    }

    synchronized void apply() {
        mHandler.removeCallbacks(mRetry);
        final int out = mOutput;
        final boolean on = profiles.enabled(out);
        try {
            ensureEffects();
            if (mEq != null) {
                final short[] range = mEq.getBandLevelRange();
                final int n = Math.min(Profiles.BANDS, mEq.getNumberOfBands());
                for (short b = 0; b < n; b++) {
                    short level = profiles.level(out, b);
                    level = (short) Math.max(range[0], Math.min(range[1], level));
                    mEq.setBandLevel(b, level);
                }
                // the bass lives in the KiroOS Equalizer too, tuned to the output
                final int bass = profiles.bass(out);
                final boolean kiroBass = setKiroParam(PARAM_BASS_MODE,
                        out == Profiles.SPEAKER ? 0 : 1) && setKiroParam(PARAM_BASS, bass);
                mEq.setEnabled(on && (!profiles.isFlat(out) || (kiroBass && bass > 0)));
            }
            if (mVirtualizer != null) {
                final int strength = profiles.surround(out);
                if (mVirtualizer.getStrengthSupported()) {
                    mVirtualizer.setStrength((short) strength);
                }
                mVirtualizer.setEnabled(on && strength > 0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "apply failed, trying again shortly", e);
            release();
            mHandler.postDelayed(mRetry, RETRY_MS);
        }
        for (Listener l : new ArrayList<Listener>(mListeners)) {
            l.onSoundChanged();
        }
    }

    /*
     * The KiroOS Equalizer's own parameters (KiroEqualizer.c). The NXP bass boost is gone: it
     * raised bass the speaker cannot play and lowered the volume to make room, so it only
     * made the phone quieter. AudioEffect.setParameter(int, int) is hidden: reflection.
     */
    private static final int PARAM_BASS = 0x4B01;
    private static final int PARAM_BASS_MODE = 0x4B02;
    private static java.lang.reflect.Method sSetParameter;

    private boolean setKiroParam(int param, int value) {
        try {
            if (sSetParameter == null) {
                sSetParameter = AudioEffect.class.getMethod("setParameter", int.class, int.class);
            }
            final Object r = sSetParameter.invoke(mEq, param, value);
            return r instanceof Integer && (Integer) r == AudioEffect.SUCCESS;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "KiroOS Equalizer parameter " + param, e);
            return false;
        }
    }

    private void ensureEffects() {
        if (mEq == null) {
            mEq = create(new EffectFactory<Equalizer>() {
                @Override
                public Equalizer make() {
                    return new Equalizer(PRIORITY, 0);
                }
            });
        }
        if (mVirtualizer == null) {
            mVirtualizer = create(new EffectFactory<Virtualizer>() {
                @Override
                public Virtualizer make() {
                    return new Virtualizer(PRIORITY, 0);
                }
            });
        }
    }

    private interface EffectFactory<T extends AudioEffect> {
        T make();
    }

    private <T extends AudioEffect> T create(EffectFactory<T> factory) {
        try {
            return factory.make();
        } catch (RuntimeException e) {
            // one effect missing (no virtualizer on a model, say) must not take the others down
            Log.w(TAG, "effect not available", e);
            return null;
        }
    }

    private boolean alive() {
        try {
            if (mEq != null) {
                mEq.getEnabled();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void release() {
        for (AudioEffect e : new AudioEffect[] { mEq, mVirtualizer }) {
            if (e != null) {
                try {
                    e.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
        mEq = null;
        mVirtualizer = null;
    }

    void addListener(Listener l) {
        mListeners.add(l);
    }

    void removeListener(Listener l) {
        mListeners.remove(l);
    }
}
