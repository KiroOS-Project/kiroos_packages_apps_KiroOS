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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

/**
 * The player: MediaExtractor + MediaCodec decode any supported file or http(s) stream
 * (FLAC, MP3, AAC, Vorbis, Opus, WAV…) to PCM at its native sample rate; the PCM goes
 * through the 10-band equalizer, is copied to the visualizer and written to AudioTrack.
 * One thread per track; all control calls are thread safe.
 */
final class Engine {

    private static final String TAG = "PulseEngine";
    private static final long TIMEOUT_US = 10000;

    interface Listener {
        void onPrepared(long durationMs, int sampleRate, int channels, String mime);

        void onCompletion();

        void onError(String message);
    }

    /** Receives the PCM that is played (for the visualizer); called on the audio thread. */
    interface PcmTap {
        void onPcm(short[] pcm, int length, int channels);
    }

    private final Context mContext;
    private final Equalizer10 mEq;
    private final Object mLock = new Object();
    private Thread mThread;
    private volatile boolean mStop;
    private volatile boolean mPaused;
    private volatile long mSeekToUs = -1;
    private volatile float mVolume = 1f;
    private volatile PcmTap mTap;
    private volatile long mPositionUs;
    private volatile long mDurationUs;

    Engine(Context context, Equalizer10 eq) {
        mContext = context.getApplicationContext();
        mEq = eq;
    }

    void setTap(PcmTap tap) {
        mTap = tap;
    }

    /** Starts decoding a new source (a content/file uri or an http(s) url). */
    void play(final Uri uri, final Listener listener, final long startMs) {
        stop();
        mStop = false;
        mPaused = false;
        mSeekToUs = startMs > 0 ? startMs * 1000 : -1;
        mPositionUs = Math.max(0, startMs * 1000);
        mDurationUs = 0;
        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                try {
                    decode(uri, listener);
                } catch (Exception e) {
                    Log.w(TAG, "playback failed: " + uri, e);
                    if (!mStop) {
                        listener.onError(e.getMessage() != null ? e.getMessage() : e.toString());
                    }
                }
            }
        }, "PulseDecoder");
        mThread.start();
    }

    void pause() {
        mPaused = true;
    }

    void resume() {
        synchronized (mLock) {
            mPaused = false;
            mLock.notifyAll();
        }
    }

    boolean isPaused() {
        return mPaused;
    }

    void seekTo(long ms) {
        mSeekToUs = Math.max(0, ms * 1000);
        mPositionUs = mSeekToUs;
        synchronized (mLock) {
            mLock.notifyAll();
        }
    }

    /** 0…1, used for ducking under navigation prompts */
    void setVolume(float v) {
        mVolume = v;
    }

    long positionMs() {
        return mPositionUs / 1000;
    }

    long durationMs() {
        return mDurationUs / 1000;
    }

    void stop() {
        mStop = true;
        synchronized (mLock) {
            mLock.notifyAll();
        }
        final Thread t = mThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mThread = null;
    }

    private void decode(Uri uri, Listener listener) throws Exception {
        final MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            final String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                final HashMap<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "PulseMusic/1.0 (KiroOS)");
                headers.put("Icy-MetaData", "0");
                extractor.setDataSource(uri.toString(), headers);
            } else {
                extractor.setDataSource(mContext, uri, null);
            }
            int index = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                final MediaFormat f = extractor.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    index = i;
                    format = f;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("no audio track");
            }
            extractor.selectTrack(index);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            mDurationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            track = createTrack(rate, channels);
            mEq.configure(rate, channels);
            listener.onPrepared(mDurationUs / 1000, rate, channels, mime);

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            short[] pcm = new short[8192];
            long headAtBase = 0;
            long baseUs = 0;
            track.play();

            while (!mStop && !outputDone) {
                // pause: stop the track and wait
                if (mPaused) {
                    track.pause();
                    synchronized (mLock) {
                        while (mPaused && !mStop && mSeekToUs < 0) {
                            mLock.wait();
                        }
                    }
                    if (mStop) {
                        break;
                    }
                    if (!mPaused) {
                        track.play();
                    }
                }
                final long seek = mSeekToUs;
                if (seek >= 0) {
                    mSeekToUs = -1;
                    extractor.seekTo(seek, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    track.pause();
                    track.flush();
                    headAtBase = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    baseUs = Math.max(0, extractor.getSampleTime() >= 0 ? extractor.getSampleTime()
                            : seek);
                    inputDone = false;
                    if (!mPaused) {
                        track.play();
                    }
                }
                if (!inputDone) {
                    final int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        final ByteBuffer buf = codec.getInputBuffer(in);
                        final int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                final int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out >= 0) {
                    if (info.size > 0) {
                        final ByteBuffer buf = codec.getOutputBuffer(out);
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        final int samples = info.size / 2;
                        if (pcm.length < samples) {
                            pcm = new short[samples];
                        }
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, samples);
                        mEq.process(pcm, samples);
                        final float vol = mVolume;
                        if (vol < 0.999f) {
                            for (int i = 0; i < samples; i++) {
                                pcm[i] = (short) (pcm[i] * vol);
                            }
                        }
                        final PcmTap tap = mTap;
                        if (tap != null) {
                            tap.onPcm(pcm, samples, channels);
                        }
                        int written = 0;
                        while (written < samples && !mStop && mSeekToUs < 0) {
                            final int w = track.write(pcm, written, samples - written);
                            if (w < 0) {
                                throw new IllegalStateException("AudioTrack write " + w);
                            }
                            written += w;
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat f = codec.getOutputFormat();
                    final int r = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    final int c = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (r != rate || c != channels) {
                        track.release();
                        rate = r;
                        channels = c;
                        track = createTrack(rate, channels);
                        mEq.configure(rate, channels);
                        headAtBase = 0;
                        track.play();
                    }
                }
                final long head = (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) - headAtBase;
                mPositionUs = baseUs + head * 1000000L / rate;
            }
            if (outputDone && !mStop) {
                // let the last buffer play out
                final long end = System.currentTimeMillis() + 3000;
                long last = -1;
                while (!mStop && System.currentTimeMillis() < end) {
                    final long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    if (head == last) {
                        break;
                    }
                    last = head;
                    Thread.sleep(120);
                }
                if (!mStop) {
                    listener.onCompletion();
                }
            }
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException e) {
                    // not playing
                }
                track.release();
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException e) {
                    // not started
                }
                codec.release();
            }
            extractor.release();
        }
    }

    private static AudioTrack createTrack(int rate, int channels) {
        final int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        final int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min * 4, rate * channels))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }
}
