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

/** JNI bridge to libkai_whisper.so (whisper.cpp, see jni/kai_whisper.cpp). */
final class NativeWhisper {

    static {
        System.loadLibrary("kai_whisper");
    }

    private NativeWhisper() {
    }

    static native long nativeLoad(String path);

    static native void nativeFree(long handle);

    /** 16 kHz mono float PCM -> UTF-8 text; lang "it", "en" or "auto" */
    static native byte[] nativeTranscribe(long handle, float[] pcm, String lang, int threads);
}
