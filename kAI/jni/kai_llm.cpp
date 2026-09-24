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
 * kAI 1.1 on-device engine: llama.cpp inside the app (JNI), no server process.
 *
 * One Engine per loaded model. generate() takes the whole chat, formats it with the
 * model's own chat template, reuses the part of the KV cache that matches the previous
 * turn (only the new text is read), then samples tokens and hands each complete piece of
 * UTF-8 text to Java as it comes. The thread count can change while it runs (the Thermal
 * Guardian lowers it when the SoC gets hot) and a stop flag ends it between two tokens.
 */
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "kAI-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string tmpl;                    // the model's chat template (empty: chatml)
    std::vector<llama_token> cached;     // tokens currently in the KV cache
    std::atomic<bool> stop{false};
    std::atomic<int> threads{4};
    int applied_threads = 0;
    int n_batch = 256;
    std::mutex lock;                     // one generation at a time
};

std::once_flag g_backend;

/* length of the complete UTF-8 prefix of s (a piece can end in the middle of a character) */
size_t complete_utf8(const std::string &s) {
    size_t n = s.size(), i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {          // a lead byte (or ASCII)
            int need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? n : i - 1;
        }
        i--;
        back++;
    }
    return n;
}

std::string format_chat(Engine *e, const std::vector<std::string> &roles,
                        const std::vector<std::string> &contents) {
    std::vector<llama_chat_message> msgs;
    size_t total = 0;
    for (size_t i = 0; i < roles.size(); i++) {
        msgs.push_back({roles[i].c_str(), contents[i].c_str()});
        total += contents[i].size() + 64;
    }
    const char *t = e->tmpl.empty() ? "chatml" : e->tmpl.c_str();
    std::vector<char> buf(total * 2 + 1024);
    int n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    if (n < 0) {                          // a template llama.cpp does not know: chatml
        n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(t, msgs.data(), msgs.size(), true, buf.data(), (int) buf.size());
    }
    return n > 0 ? std::string(buf.data(), n) : std::string();
}

void apply_threads(Engine *e) {
    int t = e->threads.load();
    if (t != e->applied_threads) {
        llama_set_n_threads(e->ctx, t, t);
        e->applied_threads = t;
        LOGI("threads -> %d", t);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kiroos_kai_NativeLlm_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint n_ctx, jint n_threads) {
    std::call_once(g_backend, [] { llama_backend_init(); });
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // weights stay in the page cache, not the heap
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGW("model load failed");
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 256;
    cp.n_ubatch = 256;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGW("context init failed");
        llama_model_free(model);
        return 0;
    }
    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->threads = n_threads;
    e->applied_threads = n_threads;
    const char *t = llama_model_chat_template(model, nullptr);
    if (t) e->tmpl = t;
    LOGI("loaded, ctx %u, threads %d", llama_n_ctx(ctx), n_threads);
    return (jlong) e;
}

JNIEXPORT void JNICALL
Java_com_kiroos_kai_NativeLlm_nativeFree(JNIEnv *, jclass, jlong h) {
    auto *e = (Engine *) h;
    if (!e) return;
    e->stop = true;
    std::lock_guard<std::mutex> g(e->lock);
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_kiroos_kai_NativeLlm_nativeSetThreads(JNIEnv *, jclass, jlong h, jint n) {
    auto *e = (Engine *) h;
    if (e && n > 0) e->threads = n;       // taken before the next token
}

JNIEXPORT void JNICALL
Java_com_kiroos_kai_NativeLlm_nativeStop(JNIEnv *, jclass, jlong h) {
    auto *e = (Engine *) h;
    if (e) e->stop = true;
}

JNIEXPORT jint JNICALL
Java_com_kiroos_kai_NativeLlm_nativeContextSize(JNIEnv *, jclass, jlong h) {
    auto *e = (Engine *) h;
    return e ? (jint) llama_n_ctx(e->ctx) : 0;
}

/*
 * Returns the number of tokens generated, or
 *  -1 prompt could not be formatted/tokenized, -2 prompt too long for the context,
 *  -3 decode failed.
 * callback.onPiece(byte[]) returns false to stop.
 */
JNIEXPORT jint JNICALL
Java_com_kiroos_kai_NativeLlm_nativeGenerate(JNIEnv *env, jclass, jlong h, jobjectArray jroles,
                                             jobjectArray jcontents, jint max_tokens, jfloat temp,
                                             jobject callback) {
    auto *e = (Engine *) h;
    if (!e) return -1;
    std::lock_guard<std::mutex> g(e->lock);
    e->stop = false;

    /* roles are ASCII; contents come as real UTF-8 bytes (Java's "modified UTF-8" would
       mangle emoji and other characters outside the BMP) */
    std::vector<std::string> roles, contents;
    const jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; i++) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jc = (jbyteArray) env->GetObjectArrayElement(jcontents, i);
        const char *r = env->GetStringUTFChars(jr, nullptr);
        roles.emplace_back(r);
        env->ReleaseStringUTFChars(jr, r);
        const jsize len = env->GetArrayLength(jc);
        std::string c((size_t) len, '\0');
        env->GetByteArrayRegion(jc, 0, len, (jbyte *) &c[0]);
        contents.push_back(std::move(c));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);
    }
    const std::string prompt = format_chat(e, roles, contents);
    if (prompt.empty()) return -1;

    std::vector<llama_token> toks(prompt.size() + 16);
    int nt = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(),
                            (int) toks.size(), true, true);
    if (nt < 0) {
        toks.resize(-nt);
        nt = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), toks.data(),
                            (int) toks.size(), true, true);
    }
    if (nt <= 0) return -1;
    toks.resize(nt);
    const int n_ctx = (int) llama_n_ctx(e->ctx);
    if (nt + 32 > n_ctx) return -2;
    if (nt + max_tokens > n_ctx) max_tokens = n_ctx - nt;

    /* reuse the cache: keep the common prefix with what is already evaluated */
    llama_memory_t mem = llama_get_memory(e->ctx);
    size_t keep = 0;
    while (keep < e->cached.size() && keep < toks.size() && e->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size()) keep--;              // always evaluate at least the last token
    if (keep < e->cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
    }
    e->cached.assign(toks.begin(), toks.begin() + keep);

    apply_threads(e);
    for (size_t i = keep; i < toks.size(); i += e->n_batch) {
        if (e->stop) return 0;
        int len = (int) std::min<size_t>(e->n_batch, toks.size() - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, len);
        if (llama_decode(e->ctx, b) != 0) {
            llama_memory_clear(mem, true);
            e->cached.clear();
            return -3;
        }
        e->cached.insert(e->cached.end(), toks.begin() + i, toks.begin() + i + len);
        apply_threads(e);
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(e->vocab), 64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cls = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cls, "onPiece", "([B)Z");
    std::string pending;
    int produced = 0;
    char piece[256];
    for (; produced < max_tokens && !e->stop; produced++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        int len = llama_token_to_piece(e->vocab, t, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);
        size_t ok = complete_utf8(pending);
        if (ok > 0) {
            jbyteArray arr = env->NewByteArray((jsize) ok);
            env->SetByteArrayRegion(arr, 0, (jsize) ok, (const jbyte *) pending.data());
            jboolean go = env->CallBooleanMethod(callback, onPiece, arr);
            env->DeleteLocalRef(arr);
            pending.erase(0, ok);
            if (!go) break;
        }
        apply_threads(e);
        llama_token one = t;
        llama_batch b = llama_batch_get_one(&one, 1);
        if (llama_decode(e->ctx, b) != 0) {
            produced = -3;
            break;
        }
        e->cached.push_back(t);
    }
    llama_sampler_free(smpl);
    return produced;
}

}  // extern "C"
