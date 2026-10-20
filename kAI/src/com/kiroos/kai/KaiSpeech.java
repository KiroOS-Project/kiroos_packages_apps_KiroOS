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

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * kAI's own voice (Beta 5): Piper neural voices run on the phone by sherpa-onnx, Paola for
 * Italian and Kristin for English (in /system/usr/share/kai/voice). Offline, no limit.
 *
 * Two threads: one turns each sentence into audio while the other plays the one before, so a
 * long answer is read without pauses between sentences. Each sentence's id is reported once it
 * has really been heard (the track has played its last sample), or dropped by stop().
 */
final class KaiSpeech {

    interface Listener {
        /** on the player thread: the sentence with this id has been said (or was dropped) */
        void onDone(String id);
    }

    private static final String TAG = "kAI";
    /** LocalEngine.SYSTEM_DIR + "/voice", spelled out so the voice test can build alone */
    static final String DIR = "/system/usr/share/kai/voice";
    /** the four big cores of the Exynos 7420 */
    private static final int THREADS = 4;

    private static final class Item {
        final String text;
        final String voice;
        final String id;
        final int epoch;
        float[] pcm;
        int rate;

        Item(String text, String voice, String id, int epoch) {
            this.text = text;
            this.voice = voice;
            this.id = id;
            this.epoch = epoch;
        }
    }

    private final Listener mListener;
    private final LinkedBlockingQueue<Item> mToSay = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Item> mToPlay = new LinkedBlockingQueue<>();
    /** bumped by stop(): what was queued before is thrown away */
    private volatile int mEpoch;
    private volatile String mWanted;
    private Thread mMaker;
    private Thread mPlayer;

    /** the loaded voice (one at a time, about 100 MB), shared by every kAI screen */
    private static OfflineTts sTts;
    private static String sVoice;

    KaiSpeech(Listener l) {
        mListener = l;
    }

    /** the voice for a language, or null (another language: the system's voice reads it) */
    static String voiceFor(Locale lang) {
        final String v = "it".equals(lang.getLanguage()) ? "paola"
                : "en".equals(lang.getLanguage()) ? "kristin" : null;
        return v != null && new File(DIR + "/" + v + "/model.onnx").canRead() ? v : null;
    }

    static boolean supports(Locale lang) {
        return voiceFor(lang) != null;
    }

    /** loads the voice ahead of time (while the user is still speaking) */
    void prepare(Locale lang) {
        final String v = voiceFor(lang);
        if (v == null) {
            return;
        }
        mWanted = v;
        start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                load(v);
            }
        }, "kAI-voice-load").start();
    }

    void speak(String text, Locale lang, String id) {
        final String v = voiceFor(lang);
        if (v == null) {
            mListener.onDone(id);
            return;
        }
        start();
        mToSay.add(new Item(text, v, id, mEpoch));
    }

    /** stops at once; nothing queued is said */
    void stop() {
        mEpoch++;
        mToSay.clear();
        mToPlay.clear();
        synchronized (this) {
            if (mTrack != null) {
                try {
                    mTrack.pause();
                    mTrack.flush();
                    mWritten = 0;
                } catch (IllegalStateException ignored) {
                    // released
                }
            }
        }
    }

    void shutdown() {
        stop();
        if (mMaker != null) {
            mMaker.interrupt();
            mPlayer.interrupt();
            mMaker = null;
            mPlayer = null;
        }
    }

    /** frees the voice model (a few minutes after kAI is left) */
    static synchronized void release() {
        if (sTts != null) {
            sTts.release();
            sTts = null;
            sVoice = null;
        }
    }

    private static synchronized OfflineTts load(String voice) {
        if (sTts != null && voice.equals(sVoice)) {
            return sTts;
        }
        release();
        final long t0 = System.currentTimeMillis();
        final OfflineTtsConfig c = new OfflineTtsConfig();
        c.model = OfflineTtsModelConfig.vits(DIR + "/" + voice + "/model.onnx",
                DIR + "/" + voice + "/tokens.txt", DIR + "/espeak-ng-data", THREADS);
        try {
            sTts = new OfflineTts(c);
            sVoice = voice;
            Log.i(TAG, "voice " + voice + " loaded in " + (System.currentTimeMillis() - t0)
                    + " ms");
        } catch (Throwable e) {
            Log.w(TAG, "voice " + voice + " did not load", e);
            sTts = null;
        }
        return sTts;
    }

    private synchronized void start() {
        if (mMaker != null) {
            return;
        }
        mMaker = new Thread(new Runnable() {
            @Override
            public void run() {
                make();
            }
        }, "kAI-voice-make");
        mPlayer = new Thread(new Runnable() {
            @Override
            public void run() {
                play();
            }
        }, "kAI-voice-play");
        mMaker.start();
        mPlayer.start();
    }

    /** sentence -> audio, in order */
    private void make() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final Item it = mToSay.take();
                if (it.epoch != mEpoch) {
                    mListener.onDone(it.id);
                    continue;
                }
                final OfflineTts tts = load(it.voice);
                if (tts != null) {
                    final long t0 = System.currentTimeMillis();
                    try {
                        final GeneratedAudio a;
                        synchronized (KaiSpeech.class) {
                            a = tts.generate(speakable(it.text), 0, 1.0f);
                        }
                        it.pcm = a.samples;
                        it.rate = a.sampleRate;
                        Log.i(TAG, "said " + it.text.length() + " chars as "
                                + (a.samples.length * 1000L / Math.max(1, a.sampleRate))
                                + " ms of voice in " + (System.currentTimeMillis() - t0) + " ms");
                    } catch (Throwable e) {
                        Log.w(TAG, "voice failed", e);
                    }
                }
                mToPlay.add(it);
            }
        } catch (InterruptedException e) {
            // shut down
        }
    }

    private AudioTrack mTrack;
    private int mTrackRate;
    /** frames written since the track was made or flushed (its head counts the same way) */
    private volatile long mWritten;

    /** audio -> speaker; each id is reported once its last sample has been played */
    private void play() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final Item it = mToPlay.take();
                if (it.epoch != mEpoch || it.pcm == null || it.pcm.length == 0) {
                    mListener.onDone(it.id);
                    continue;
                }
                final AudioTrack t = track(it.rate);
                t.play();
                int off = 0;
                while (off < it.pcm.length && it.epoch == mEpoch) {
                    final int n = t.write(it.pcm, off, Math.min(4096, it.pcm.length - off),
                            AudioTrack.WRITE_BLOCKING);
                    if (n <= 0) {
                        break;
                    }
                    off += n;
                    mWritten += n;
                }
                // heard once the head has passed this sentence's last sample; when the next
                // one is ready it is written right on (no gap between sentences)
                final long end = mWritten;
                while (it.epoch == mEpoch && mToPlay.isEmpty()
                        && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < end) {
                    Thread.sleep(15);
                }
                mListener.onDone(it.id);
                if (mToPlay.isEmpty() && mToSay.isEmpty()) {
                    // a little silence so the end of the last word is not cut
                    Thread.sleep(60);
                }
            }
        } catch (InterruptedException e) {
            // shut down
        } finally {
            synchronized (this) {
                if (mTrack != null) {
                    mTrack.release();
                    mTrack = null;
                }
            }
        }
    }

    private synchronized AudioTrack track(int rate) {
        if (mTrack != null && mTrackRate == rate) {
            return mTrack;
        }
        if (mTrack != null) {
            mTrack.release();
        }
        final int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT);
        mTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min, rate * 4 / 5))   // 200 ms
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        mTrackRate = rate;
        mWritten = 0;
        return mTrack;
    }

    /** what the voice should not try to read: emoji and symbols, spaced dashes as pauses */
    static String speakable(String s) {
        final StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            final int type = Character.getType(cp);
            if (type == Character.OTHER_SYMBOL || type == Character.SURROGATE
                    || (cp >= 0xFE00 && cp <= 0xFE0F) || cp == 0x200D) {
                continue;
            }
            b.appendCodePoint(cp);
        }
        return b.toString().replace(" – ", ", ").replace(" — ", ", ").replace(" - ", ", ")
                .replaceAll("\\s+", " ").trim();
    }
}
