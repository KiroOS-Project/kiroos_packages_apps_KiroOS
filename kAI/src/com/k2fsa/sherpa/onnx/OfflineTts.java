// Copyright (c)  2023  Xiaomi Corporation (sherpa-onnx, Apache-2.0)
// Java port of sherpa-onnx v1.13.8 kotlin-api/Tts.kt for kAI (Beta 5 voice): the native
// library (libsherpa-onnx-jni) reads these classes' fields by name, so every config class
// and field of the Kotlin API is kept as it is, even those kAI does not use.
package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineTts {
    static {
        System.loadLibrary("sherpa-onnx-jni");
    }

    private long ptr;
    public OfflineTtsConfig config;

    public OfflineTts(OfflineTtsConfig config) {
        this.config = config;
        ptr = newFromFile(config);
        if (ptr == 0) {
            throw new IllegalArgumentException(
                    "Invalid OfflineTtsConfig: failed to create native OfflineTts");
        }
    }

    public int sampleRate() {
        return getSampleRate(ptr);
    }

    public int numSpeakers() {
        return getNumSpeakers(ptr);
    }

    public GeneratedAudio generate(String text, int sid, float speed) {
        return generateImpl(ptr, text, sid, speed);
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    private native long newFromAsset(AssetManager assetManager, OfflineTtsConfig config);

    private native long newFromFile(OfflineTtsConfig config);

    private native void delete(long ptr);

    private native int getSampleRate(long ptr);

    private native int getNumSpeakers(long ptr);

    private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
}
