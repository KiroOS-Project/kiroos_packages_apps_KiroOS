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

/*
 * kAI 1.1 speech recognition: whisper.cpp inside the app (JNI), offline.
 * load() keeps the model, transcribe() takes 16 kHz mono float PCM and returns UTF-8 text.
 */
#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

#define TAG "kAI-whisper"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kiroos_kai_NativeWhisper_nativeLoad(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "model load failed");
    }
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_kiroos_kai_NativeWhisper_nativeFree(JNIEnv *, jclass, jlong h) {
    if (h) whisper_free((whisper_context *) h);
}

/* lang: "it", "en" or "auto" */
JNIEXPORT jbyteArray JNICALL
Java_com_kiroos_kai_NativeWhisper_nativeTranscribe(JNIEnv *env, jclass, jlong h,
                                                   jfloatArray jpcm, jstring jlang, jint threads) {
    auto *ctx = (whisper_context *) h;
    if (!ctx) return nullptr;
    const jsize n = env->GetArrayLength(jpcm);
    jfloat *pcm = env->GetFloatArrayElements(jpcm, nullptr);
    const char *lang = env->GetStringUTFChars(jlang, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_timestamps = true;
    p.single_segment = false;
    p.print_special = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;

    std::string text;
    if (whisper_full(ctx, p, pcm, n) == 0) {
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) {
            text += whisper_full_get_segment_text(ctx, i);
        }
    }
    env->ReleaseStringUTFChars(jlang, lang);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);

    jbyteArray out = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(out, 0, (jsize) text.size(), (const jbyte *) text.data());
    return out;
}

}  // extern "C"
