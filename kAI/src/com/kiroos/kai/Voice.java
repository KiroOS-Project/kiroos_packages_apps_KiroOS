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

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * kAI voice: speech to text with the cloud's Whisper (whisper.cpp on the phone when offline)
 * and answers read aloud by kAI's own neural voice (KaiSpeech: Italian and English, on the
 * phone); other languages by the system text-to-speech.
 *
 * listen() records 16 kHz mono until the speaker stops (about a second of silence after
 * some speech), stop() is pressed, or 20 seconds pass; then the audio is transcribed.
 */
final class Voice {

    interface Listener {
        void onLevel(float level);           // 0..1, for the microphone button

        void onTranscribing();

        void onText(String text);            // empty when nothing was understood

        void onError(String message);
    }

    private static final String TAG = "kAI";
    private static final String MODEL = "whisper-base-q5_1.bin";
    private static final int RATE = 16000;
    private static final int MAX_SECONDS = 20;

    private static long sWhisper;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mRecording;
    private TextToSpeech mTts;
    private boolean mTtsReady;

    Voice(Context context) {
        mContext = context.getApplicationContext();
    }

    static boolean available() {
        return new File(LocalEngine.DATA_DIR, MODEL).canRead()
                || new File(LocalEngine.SYSTEM_DIR, MODEL).canRead();
    }

    boolean recording() {
        return mRecording;
    }

    void stop() {
        mRecording = false;
    }

    void listen(final Locale lang, final Listener l) {
        if (mRecording) {
            return;
        }
        mRecording = true;
        // the answer will be read aloud: the voice loads while the user is still talking
        if (KaiSpeech.supports(lang)) {
            speech().prepare(lang);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final float[] audio = record(l);
                mRecording = false;
                if (audio == null) {
                    post(l, null, "mic");
                    return;
                }
                if (audio.length < RATE / 2) {
                    post(l, "", null);
                    return;
                }
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onTranscribing();
                    }
                });
                final String text = transcribe(audio, lang);
                post(l, text, text == null ? "whisper" : null);
            }
        }, "kAI-voice").start();
    }

    private void post(final Listener l, final String text, final String error) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (error != null) {
                    l.onError(error);
                } else {
                    l.onText(text);
                }
            }
        });
    }

    private float[] record(final Listener l) {
        final int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        final short[] all = new short[RATE * MAX_SECONDS];
        final short[] buf = new short[RATE / 20];     // 50 ms
        int n = 0;
        int silentMs = 0;
        boolean spoke = false;
        float noise = 0.004f;
        rec.startRecording();
        try {
            while (mRecording && n + buf.length <= all.length) {
                final int r = rec.read(buf, 0, buf.length);
                if (r <= 0) {
                    break;
                }
                System.arraycopy(buf, 0, all, n, r);
                n += r;
                double e = 0;
                for (int i = 0; i < r; i++) {
                    final float v = buf[i] / 32768f;
                    e += v * v;
                }
                final float rms = (float) Math.sqrt(e / r);
                final float level = Math.min(1f, rms * 12f);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        l.onLevel(level);
                    }
                });
                // speech is well above the room noise; once spoken, a second of quiet ends it
                if (rms > noise * 3f && rms > 0.012f) {
                    spoke = true;
                    silentMs = 0;
                } else {
                    if (!spoke) {
                        noise = noise * 0.95f + rms * 0.05f;
                    }
                    silentMs += 50;
                    if (spoke && silentMs >= 1100) {
                        break;
                    }
                    if (!spoke && silentMs >= 6000) {
                        break;                   // nobody spoke
                    }
                }
            }
        } finally {
            rec.stop();
            rec.release();
        }
        if (!spoke) {
            return new float[0];
        }
        final float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = all[i] / 32768f;
        }
        return out;
    }

    private static synchronized String transcribe(float[] audio, Locale lang) {
        // Beta 5: the cloud's Whisper first (far better, in Italian too); the small model on the
        // phone when offline or past the limit
        final String code0 = "it".equals(lang.getLanguage()) ? "it"
                : "en".equals(lang.getLanguage()) ? "en" : "auto";
        final long tc = System.currentTimeMillis();
        final String cloud = LocalEngine.transcribe(audio, code0);
        if (cloud != null) {
            Log.i(TAG, "transcribed " + audio.length / RATE + " s in the cloud in "
                    + (System.currentTimeMillis() - tc) + " ms");
            return cloud;
        }
        if (sWhisper == 0) {
            File f = new File(LocalEngine.DATA_DIR, MODEL);
            if (!f.canRead()) {
                f = new File(LocalEngine.SYSTEM_DIR, MODEL);
            }
            final long t0 = System.currentTimeMillis();
            sWhisper = NativeWhisper.nativeLoad(f.getPath());
            Log.i(TAG, "whisper loaded in " + (System.currentTimeMillis() - t0) + " ms");
            if (sWhisper == 0) {
                return null;
            }
        }
        final String code = "it".equals(lang.getLanguage()) ? "it"
                : "en".equals(lang.getLanguage()) ? "en" : "auto";
        final long t0 = System.currentTimeMillis();
        final byte[] utf8 = NativeWhisper.nativeTranscribe(sWhisper, audio, code, 4);
        Log.i(TAG, "transcribed " + audio.length / RATE + " s in "
                + (System.currentTimeMillis() - t0) + " ms");
        return utf8 == null ? null : new String(utf8, StandardCharsets.UTF_8).trim();
    }

    /** frees the speech model (with the language model, a few minutes after kAI is left) */
    static synchronized void release() {
        if (sWhisper != 0) {
            NativeWhisper.nativeFree(sWhisper);
            sWhisper = 0;
        }
        KaiSpeech.release();
    }

    // ------------------------------------------------------------------ text to speech

    private static String clean(String text) {
        return text.replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[*_#`>|]", "")
                .replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1")
                .replaceAll("🔎.*$", "").trim();
    }

    // --- Live: the answer is read a sentence at a time while it is being written ------------

    /** sentences queued / read for the answer being spoken; mEnded once its last one is in */
    private int mQueued;
    private int mSpokenCount;
    private boolean mEnded;
    private Runnable mOnSpoken;
    private boolean mListening;

    /**
     * Queues one sentence of an answer that is still being written. With last = true, when
     * everything queued has been said, onSpoken runs (on the main thread).
     */
    void speakChunk(final String text, final Locale lang, final boolean last,
            final Runnable onSpoken) {
        if (mQueued == mSpokenCount && !mEnded && mOnSpoken == null) {
            // a new answer
            mQueued = 0;
            mSpokenCount = 0;
        }
        final String clean = clean(text);
        if (last) {
            mEnded = true;
            mOnSpoken = onSpoken;
        }
        if (clean.isEmpty()) {
            checkSpoken();
            return;
        }
        final String id = "kai-live-" + mGen + "-" + (++mQueued);
        if (KaiSpeech.supports(lang)) {
            speech().speak(clean, lang, id);
            return;
        }
        if (mTtsReady) {
            listenToTts();
            queue(clean, lang, id);
            return;
        }
        // Beta 5: the cloud writes a whole answer in about a second, before the engine has
        // started: every sentence waits here (only the first one used to be read, "Ciao")
        mPending.add(new String[] {clean, id});
        mPendingLang = lang;
        startTts();
    }

    /** sentences that came while the engine was starting, in order */
    private final java.util.List<String[]> mPending = new java.util.ArrayList<>();
    private Locale mPendingLang;
    /** a whole answer asked for (not Live) while the engine was starting */
    private String mPendingSay;

    /** starts the engine once; what waited for it is said when it is ready */
    private void startTts() {
        if (mTts != null) {
            return;
        }
        mTts = new TextToSpeech(mContext, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mTtsReady = status == TextToSpeech.SUCCESS;
                        if (mTtsReady) {
                            listenToTts();
                            if (mPendingSay != null) {
                                say(mPendingSay, mPendingLang);
                            }
                            for (String[] p : mPending) {
                                queue(p[0], mPendingLang, p[1]);
                            }
                        } else {
                            mSpokenCount = mQueued;
                        }
                        mPendingSay = null;
                        mPending.clear();
                        checkSpoken();
                    }
                });
            }
        });
    }

    private void queue(String text, Locale lang, String id) {
        mTts.setLanguage(lang);
        mTts.speak(text.length() > 3500 ? text.substring(0, 3500) : text,
                TextToSpeech.QUEUE_ADD, new android.os.Bundle(), id);
    }

    private void listenToTts() {
        if (mListening) {
            return;
        }
        mListening = true;
        mTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(final String id) {
                said(id);
            }

            @Override
            public void onError(String id) {
                onDone(id);
            }
        });
    }

    /** any thread: a sentence of the current Live answer has been said (others are ignored) */
    private void said(final String id) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (id.startsWith("kai-live-" + mGen + "-")) {
                    mSpokenCount++;
                    checkSpoken();
                }
            }
        });
    }

    /** kAI's own voice (Italian and English), made on first use */
    private KaiSpeech speech() {
        if (mSpeech == null) {
            mSpeech = new KaiSpeech(new KaiSpeech.Listener() {
                @Override
                public void onDone(String id) {
                    said(id);
                }
            });
        }
        return mSpeech;
    }

    private KaiSpeech mSpeech;
    /** bumped when speech is cut off: late "said" reports of the old answer are ignored */
    private int mGen;

    private void checkSpoken() {
        if (mEnded && mSpokenCount >= mQueued) {
            mEnded = false;
            mQueued = 0;
            mSpokenCount = 0;
            final Runnable r = mOnSpoken;
            mOnSpoken = null;
            if (r != null) {
                r.run();
            }
        }
    }
    void speak(final String text, final Locale lang) {
        final String clean = clean(text);
        if (clean.isEmpty()) {
            return;
        }
        if (KaiSpeech.supports(lang)) {
            speech().stop();
            speech().speak(clean, lang, "kai");
            return;
        }
        if (mTtsReady) {
            say(clean, lang);
            return;
        }
        // still starting: the latest answer is said once it is ready
        mPendingSay = clean;
        mPendingLang = lang;
        startTts();
    }

    private void say(String text, Locale lang) {
        mTts.setLanguage(lang);
        mTts.speak(text.length() > 3500 ? text.substring(0, 3500) : text,
                TextToSpeech.QUEUE_FLUSH, null, "kai");
    }

    void silence() {
        mOnSpoken = null;
        mEnded = false;
        mQueued = 0;
        mSpokenCount = 0;
        mPending.clear();
        mPendingSay = null;
        mGen++;
        if (mSpeech != null) {
            mSpeech.stop();
        }
        if (mTts != null) {
            mTts.stop();
        }
    }

    void shutdown() {
        if (mSpeech != null) {
            mSpeech.shutdown();
            mSpeech = null;
        }
        if (mTts != null) {
            mTts.shutdown();
            mTts = null;
            mTtsReady = false;
            mListening = false;
        }
    }
}
