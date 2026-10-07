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

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioSystem;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * One recording: the screen through a mirroring virtual display into the hardware H.264 encoder,
 * the sound (none, microphone or device audio) into AAC, both muxed into an MP4.
 *
 * The device audio is the remote submix: its output is connected while recording and
 * sys.kiroos.rec.local=1 makes the audio policy send it a copy of the media, so the phone keeps
 * playing (frameworks/av enginedefault Engine.cpp). With Bluetooth or USB audio the copy is not
 * possible and the sound goes to the recording only, as on stock Android 7.
 */
final class ScreenRecorder {

    static final int AUDIO_NONE = 0;
    static final int AUDIO_MIC = 1;
    static final int AUDIO_DEVICE = 2;

    private static final String TAG = "KiroScreenRecord";
    private static final String PROP_LOCAL = "sys.kiroos.rec.local";
    private static final long TIMEOUT_US = 10000;
    /** how long stop() waits for the encoders to hand back their last frames */
    private static final long STOP_TIMEOUT_MS = 3000;
    /** samples kept while the muxer waits for the second track (the first is a key frame) */
    private static final int MAX_PENDING = 240;

    private final MediaProjection mProjection;
    private final int mWidth;
    private final int mHeight;
    private final int mDpi;
    private final int mBitrate;
    private final int mAudio;
    private final File mFile;
    private int mFps;

    private MediaMuxer mMuxer;
    private MediaCodec mVideo;
    private Surface mSurface;
    private VirtualDisplay mDisplay;
    private MediaCodec mAudioCodec;
    private AudioRecord mRecord;
    private int mSampleRate;
    private int mChannels;
    private boolean mDeviceAudioRouted;

    private Thread mVideoThread;
    private Thread mAudioThread;
    private volatile boolean mStopping;
    private volatile long mStopAt;

    // muxer state, guarded by "this"
    private int mVideoTrack = -1;
    private int mAudioTrack = -1;
    private boolean mMuxerStarted;
    private boolean mWroteVideo;
    private final ArrayList<Pending> mPending = new ArrayList<>();

    private static final class Pending {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        Pending(boolean video, ByteBuffer src, MediaCodec.BufferInfo from) {
            this.video = video;
            data = ByteBuffer.allocate(from.size);
            src.position(from.offset);
            src.limit(from.offset + from.size);
            data.put(src);
            data.flip();
            info = new MediaCodec.BufferInfo();
            info.set(0, from.size, from.presentationTimeUs, from.flags);
        }
    }

    ScreenRecorder(MediaProjection projection, int width, int height, int dpi, int fps,
            int bitrate, int audio, File file) {
        mProjection = projection;
        mWidth = width;
        mHeight = height;
        mDpi = dpi;
        mFps = fps;
        mBitrate = bitrate;
        mAudio = audio;
        mFile = file;
    }

    File file() {
        return mFile;
    }

    void start() throws IOException {
        boolean ok = false;
        try {
            mMuxer = new MediaMuxer(mFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            prepareVideo();
            if (mAudio != AUDIO_NONE) prepareAudio();
            mDisplay = mProjection.createVirtualDisplay("KiroOS screen recording", mWidth, mHeight,
                    mDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mSurface, null, null);
            mVideoThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainVideo();
                }
            }, "KiroScreenRecord video");
            mVideoThread.start();
            if (mAudioCodec != null) {
                mAudioThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        recordAudio();
                    }
                }, "KiroScreenRecord audio");
                mAudioThread.start();
            }
            ok = true;
        } catch (RuntimeException e) {
            throw new IOException("the recorder could not start", e);
        } finally {
            if (!ok) release();
        }
    }

    /**
     * Ends the recording and waits for the file to be complete.
     * @return false if nothing usable was recorded (the file is deleted)
     */
    boolean stop() {
        if (mDisplay != null) {
            mDisplay.release();
            mDisplay = null;
        }
        mStopAt = System.currentTimeMillis();
        mStopping = true;
        try {
            if (mVideo != null) mVideo.signalEndOfInputStream();
        } catch (RuntimeException e) {
            Log.w(TAG, "end of the video stream", e);
        }
        join(mVideoThread);
        join(mAudioThread);
        boolean ok;
        synchronized (this) {
            ok = mMuxerStarted && mWroteVideo;
            if (mMuxerStarted) {
                try {
                    mMuxer.stop();
                } catch (RuntimeException e) {
                    Log.w(TAG, "muxer stop", e);
                    ok = false;
                }
            }
        }
        release();
        if (!ok) mFile.delete();
        return ok;
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(STOP_TIMEOUT_MS + 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void release() {
        if (mDisplay != null) {
            mDisplay.release();
            mDisplay = null;
        }
        if (mRecord != null) {
            try {
                mRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            mRecord.release();
            mRecord = null;
        }
        routeDeviceAudio(false);
        if (mVideo != null) {
            try {
                mVideo.stop();
            } catch (IllegalStateException ignored) {
            }
            mVideo.release();
            mVideo = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mAudioCodec != null) {
            try {
                mAudioCodec.stop();
            } catch (IllegalStateException ignored) {
            }
            mAudioCodec.release();
            mAudioCodec = null;
        }
        synchronized (this) {
            if (mMuxer != null) {
                mMuxer.release();
                mMuxer = null;
            }
        }
    }

    // ---- video

    private void prepareVideo() throws IOException {
        try {
            mVideo = createVideoEncoder(mFps);
        } catch (RuntimeException e) {
            // an encoder that refuses 60 fps at this size still records at 30
            if (mFps <= 30) throw e;
            Log.w(TAG, mWidth + "x" + mHeight + " at " + mFps + " fps refused, 30 fps", e);
            mFps = 30;
            mVideo = createVideoEncoder(mFps);
        }
        mSurface = mVideo.createInputSurface();
        mVideo.start();
    }

    private MediaCodec createVideoEncoder(int fps) throws IOException {
        final MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mWidth, mHeight);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, mBitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_CAPTURE_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        // a still screen keeps the frame rate: players and editors expect a constant one
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000L / fps);
        final MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
        return codec;
    }

    private void drainVideo() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                if (mStopping && System.currentTimeMillis() - mStopAt > STOP_TIMEOUT_MS) {
                    Log.w(TAG, "the video encoder did not end its stream");
                    break;
                }
                final int index = mVideo.dequeueOutputBuffer(info, TIMEOUT_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, mVideo.getOutputFormat());
                } else if (index >= 0) {
                    final boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        writeSample(true, mVideo.getOutputBuffer(index), info);
                    }
                    mVideo.releaseOutputBuffer(index, false);
                    if (end) break;
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "video", e);
        }
    }

    // ---- audio

    private void prepareAudio() throws IOException {
        final boolean device = mAudio == AUDIO_DEVICE;
        // the remote submix is 48 kHz stereo (audio_policy.conf); the microphone is mono
        mSampleRate = device ? 48000 : 44100;
        mChannels = device ? 2 : 1;
        final int channelMask = device ? AudioFormat.CHANNEL_IN_STEREO
                : AudioFormat.CHANNEL_IN_MONO;
        final int min = AudioRecord.getMinBufferSize(mSampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("no audio input for " + mSampleRate + " Hz");
        if (device) routeDeviceAudio(true);
        mRecord = new AudioRecord(
                device ? MediaRecorder.AudioSource.REMOTE_SUBMIX : MediaRecorder.AudioSource.MIC,
                mSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min * 2, mSampleRate * mChannels * 2 / 4));
        if (mRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("the audio input did not open");
        }
        final MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                mSampleRate, mChannels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, device ? 192000 : 96000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        mAudioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        mAudioCodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mAudioCodec.start();
    }

    private void routeDeviceAudio(boolean on) {
        if (on == mDeviceAudioRouted) return;
        mDeviceAudioRouted = on;
        if (on) {
            // the property first: the routing is computed when the output appears
            setLocal(true);
            final int r = AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX,
                    AudioSystem.DEVICE_STATE_AVAILABLE, "0", "KiroOS screen recording");
            if (r != AudioSystem.SUCCESS) Log.w(TAG, "remote submix output: " + r);
        } else {
            AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX,
                    AudioSystem.DEVICE_STATE_UNAVAILABLE, "0", "KiroOS screen recording");
            setLocal(false);
        }
    }

    /**
     * Without the property the recording still works, the phone just goes quiet while it runs
     * (stock Android 7), so a failure here is only logged.
     */
    private static void setLocal(boolean on) {
        try {
            SystemProperties.set(PROP_LOCAL, on ? "1" : "0");
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot set " + PROP_LOCAL, e);
        }
    }

    /** a recording that died with its process left the device audio routed: undo it */
    static void cleanUpDeviceAudio() {
        if (!"1".equals(SystemProperties.get(PROP_LOCAL))) return;
        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX,
                AudioSystem.DEVICE_STATE_UNAVAILABLE, "0", "KiroOS screen recording");
        setLocal(false);
    }

    private void recordAudio() {
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final int bytesPerFrame = 2 * mChannels;
        boolean inputEnded = false;
        try {
            mRecord.startRecording();
            final long startUs = System.nanoTime() / 1000;
            long frames = 0;
            while (true) {
                if (!inputEnded) {
                    final int in = mAudioCodec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        final ByteBuffer buf = mAudioCodec.getInputBuffer(in);
                        buf.clear();
                        int n = mStopping ? 0 : mRecord.read(buf, buf.remaining());
                        if (n < 0) {
                            Log.w(TAG, "audio read " + n);
                            n = 0;
                        }
                        final long pts = startUs + frames * 1000000L / mSampleRate;
                        frames += n / bytesPerFrame;
                        inputEnded = mStopping;
                        mAudioCodec.queueInputBuffer(in, 0, n, pts,
                                inputEnded ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    }
                }
                final int out = mAudioCodec.dequeueOutputBuffer(info, inputEnded ? TIMEOUT_US : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(false, mAudioCodec.getOutputFormat());
                } else if (out >= 0) {
                    final boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        writeSample(false, mAudioCodec.getOutputBuffer(out), info);
                    }
                    mAudioCodec.releaseOutputBuffer(out, false);
                    if (end) break;
                }
                if (inputEnded && System.currentTimeMillis() - mStopAt > STOP_TIMEOUT_MS) {
                    Log.w(TAG, "the audio encoder did not end its stream");
                    break;
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "audio", e);
        }
    }

    // ---- muxer

    private synchronized void addTrack(boolean video, MediaFormat format) {
        if (mMuxerStarted || mMuxer == null) return;
        if (video) {
            mVideoTrack = mMuxer.addTrack(format);
        } else {
            mAudioTrack = mMuxer.addTrack(format);
        }
        if (mVideoTrack >= 0 && (mAudioCodec == null || mAudioTrack >= 0)) {
            mMuxer.start();
            mMuxerStarted = true;
            for (Pending p : mPending) {
                mMuxer.writeSampleData(p.video ? mVideoTrack : mAudioTrack, p.data, p.info);
                if (p.video) mWroteVideo = true;
            }
            mPending.clear();
        }
    }

    private synchronized void writeSample(boolean video, ByteBuffer data,
            MediaCodec.BufferInfo info) {
        if (mMuxer == null) return;
        if (!mMuxerStarted) {
            if (mPending.size() < MAX_PENDING) mPending.add(new Pending(video, data, info));
            return;
        }
        data.position(info.offset);
        data.limit(info.offset + info.size);
        mMuxer.writeSampleData(video ? mVideoTrack : mAudioTrack, data, info);
        if (video) mWroteVideo = true;
    }
}
