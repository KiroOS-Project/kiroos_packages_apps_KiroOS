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

/**
 * JNI bridge to libkai_llm.so: llama.cpp running inside the kAI process (see jni/kai_llm.cpp).
 * A handle is one loaded model with its context; generate() streams pieces of UTF-8 text.
 */
final class NativeLlm {

    static {
        System.loadLibrary("kai_llm");
    }

    interface Callback {
        /** a piece of UTF-8 text; return false to stop */
        boolean onPiece(byte[] utf8);
    }

    static final int ERR_PROMPT = -1;
    static final int ERR_TOO_LONG = -2;
    static final int ERR_DECODE = -3;

    private NativeLlm() {
    }

    static native long nativeLoad(String path, int nCtx, int nThreads);

    static native void nativeFree(long handle);

    static native void nativeSetThreads(long handle, int nThreads);

    static native void nativeStop(long handle);

    static native int nativeContextSize(long handle);

    static native int nativeGenerate(long handle, String[] roles, byte[][] contents, int maxTokens,
            float temperature, Callback callback);
}
