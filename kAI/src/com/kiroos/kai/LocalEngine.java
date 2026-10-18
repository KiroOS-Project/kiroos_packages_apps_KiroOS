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

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * kAI 1.1 on-device models, run inside the app by llama.cpp (libkai_llm.so, see NativeLlm).
 *
 * Three models ("Keng 1.5"), all 4-bit (Q4_K_M) GGUF files shipped in the ROM under
 * /system/usr/share/kai and linked from /data/kiroos/models (a file placed there with the same
 * name takes precedence). The chosen one is loaded when kAI opens (memory-mapped: a few
 * seconds) and freed a few minutes after kAI goes to the background.
 *
 * Thermal Guardian: while a model writes, the battery temperature (what the hand feels; the
 * SoC die itself runs 60-70 C under load, which is normal for the Exynos 7420) is read every
 * second; at 46 C the model drops from 4 to 2 threads (the answer goes on, slower, while the
 * phone cools) and goes back to 4 below 42 C.
 */
final class LocalEngine {

    private static final String TAG = "kAI";
    // kept for BridgeClient.local(): the in-process engine needs no address
    static final String HOST = "127.0.0.1";
    static final int PORT = 11435;

    static final String SYSTEM_DIR = "/system/usr/share/kai";
    static final String DATA_DIR = "/data/kiroos/models";
    private static final int THREADS = 4;           // the four Cortex-A57
    private static final int HOT_MC = 46000;
    private static final int COOL_MC = 42000;

    static final class Model {
        final String id;
        final String name;
        final String file;
        final int ctx;
        final float temperature;
        final int maxTokens;
        /** Qwen3: asked to answer straight away ("/no_think"); its empty think block is dropped */
        final boolean noThink;

        Model(String id, String name, String file, int ctx, float temperature, int maxTokens) {
            this(id, name, file, ctx, temperature, maxTokens, false);
        }

        Model(String id, String name, String file, int ctx, float temperature, int maxTokens,
                boolean noThink) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.ctx = ctx;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.noThink = noThink;
        }

        File path() {
            final File data = new File(DATA_DIR, file);
            return data.canRead() ? data : new File(SYSTEM_DIR, file);
        }

        boolean installed() {
            return path().canRead();
        }
    }

    /** 4 GB phones (S6 edge+) can give Pro a longer memory */
    private static final boolean BIG_RAM = totalRamKb() > 3500L * 1024;

    /**
     * Keng 1.5 Flash-Lite Live: Qwen3 0.6B without its thinking step, low temperature. The
     * everyday model and the voice of kAI (hold Power): on the PC it reads a question twice as
     * fast as Pro and writes 1.8x as fast, with short, correct answers in Italian and English.
     */
    static final Model LIVE = new Model("live", "Keng 1.5 Flash-Lite Live",
            "qwen3-0.6b-q4_k_m.gguf", 2048, 0.2f, 320, true);

    /** the models of the app's model sheet; the voice (LIVE) is not one of them */
    static final Model[] MODELS = {
        // SmolLM2 135M: instant, arithmetic, definitions, phone settings
        new Model("lite", "Keng 1.5 Flash Lite", "smollm2-135m-instruct-q4_k_m.gguf", 2048, 0.2f,
                384),
        // Qwen3 0.6B, the same file as Flash-Lite Live, set for chatting (longer answers)
        new Model("base", "Keng 1.5 Base", "qwen3-0.6b-q4_k_m.gguf", 2048, 0.4f, 512, true),
        // Llama 3.2 1B: code and step-by-step reasoning; the context is capped so the KV cache
        // cannot push a 3 GB phone out of memory
        new Model("pro", "Keng 1.5 Pro", "llama-3.2-1b-instruct-q4_k_m.gguf",
                BIG_RAM ? 2048 : 1024, 0.5f, 640),
    };

    // Base runs Qwen3 0.6B (it replaced Qwen2.5 0.5B in the ROM: faster and more accurate,
    // and the OTA zip has to stay under 2 GiB)
    private static final Model DEFAULT = MODELS[1];
    private static volatile Model sSelected = DEFAULT;
    private static Model sLoaded;
    private static long sHandle;
    private static volatile boolean sLoading;
    private static Boolean sLibrary;
    /** answers being written: the model is never freed under one (guarded by the class) */
    private static int sActive;
    private static final android.os.Handler sMain =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable sRelease = new Runnable() {
        @Override
        public void run() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    stop();
                }
            }, "kAI-release").start();
        }
    };

    private LocalEngine() {
    }

    static Model find(String id) {
        for (Model m : MODELS) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return DEFAULT;
    }

    static void select(String id) {
        sSelected = find(id);
    }

    static Model selected() {
        return sSelected;
    }

    /** the name shown in the status line */
    static String modelName() {
        return sSelected.name;
    }

    private static synchronized boolean library() {
        if (sLibrary == null) {
            try {
                NativeLlm.nativeContextSize(0);
                sLibrary = true;
            } catch (Throwable t) {
                Log.w(TAG, "on-device engine missing", t);
                sLibrary = false;
            }
        }
        return sLibrary;
    }

    /** true when this build ships the engine and the chosen model */
    static boolean available() {
        return available(sSelected);
    }

    static boolean available(Model m) {
        return library() && m.installed();
    }

    /** Loads the chosen model if it is not the one in memory. Blocking: a worker thread. */
    static boolean ensureRunning() {
        return ensureRunning(sSelected);
    }

    /** Loads the given model if it is not the one in memory. Blocking: a worker thread. */
    static synchronized boolean ensureRunning(Model want) {
        if (sHandle != 0 && sLoaded != null && (sLoaded == want
                || (sLoaded.file.equals(want.file) && sLoaded.ctx == want.ctx))) {
            return true;
        }
        if (!available(want)) {
            return false;
        }
        free();
        sLoading = true;
        try {
            final long t0 = System.currentTimeMillis();
            sHandle = NativeLlm.nativeLoad(want.path().getPath(), want.ctx, THREADS);
            sLoaded = sHandle != 0 ? want : null;
            Log.i(TAG, want.name + (sHandle != 0 ? " loaded in " : " failed after ")
                    + (System.currentTimeMillis() - t0) + " ms");
            return sHandle != 0;
        } finally {
            sLoading = false;
        }
    }

    /** true while a model is being loaded */
    static boolean loading() {
        return sLoading;
    }

    /** frees the model's memory (not while an answer is being written) */
    static synchronized void stop() {
        if (sActive > 0) {
            return;
        }
        free();
        Voice.release();
    }

    /** the model's memory goes back a few minutes after kAI was last used */
    static void releaseLater() {
        sMain.removeCallbacks(sRelease);
        sMain.postDelayed(sRelease, 3 * 60 * 1000);
    }

    /** kAI is in use again: keep the model */
    static void keep() {
        sMain.removeCallbacks(sRelease);
    }

    private static void free() {
        if (sHandle != 0) {
            NativeLlm.nativeFree(sHandle);
            sHandle = 0;
            sLoaded = null;
        }
    }

    /** ends the answer being written (between two tokens) */
    static void cancel() {
        final long h = sHandle;
        if (h != 0) {
            NativeLlm.nativeStop(h);
        }
    }

    /**
     * Writes the answer to the chat, streaming it. The oldest turns are dropped when the chat
     * does not fit the model's context.
     */
    static void generate(List<BridgeClient.Message> history, final BridgeClient.Stream stream) {
        generate(sSelected, history, stream);
    }

    /** the same, with a given model (the voice overlay always talks with Flash-Lite Live) */
    static void generate(Model model, List<BridgeClient.Message> history,
            final BridgeClient.Stream out) {
        final long h;
        final Model m;
        synchronized (LocalEngine.class) {
            if (!ensureRunning(model)) {
                out.onError("model");
                return;
            }
            h = sHandle;
            m = model;
            sActive++;
        }
        final List<BridgeClient.Message> msgs = new ArrayList<>(history);
        final BridgeClient.Stream stream = m.noThink ? new NoThink(out) : out;
        if (m.noThink) {
            // Qwen3's own switch, on the last question: answer at once, no reasoning first
            for (int i = msgs.size() - 1; i >= 0; i--) {
                final BridgeClient.Message q = msgs.get(i);
                if ("user".equals(q.role)) {
                    msgs.set(i, new BridgeClient.Message("user", q.content + " /no_think"));
                    break;
                }
            }
        }
        final Guardian guardian = new Guardian(h);
        guardian.start();
        try {
            int r;
            for (;;) {
                final String[] roles = new String[msgs.size()];
                final byte[][] contents = new byte[msgs.size()][];
                for (int i = 0; i < msgs.size(); i++) {
                    roles[i] = msgs.get(i).role;
                    contents[i] = msgs.get(i).content.getBytes(StandardCharsets.UTF_8);
                }
                r = NativeLlm.nativeGenerate(h, roles, contents, m.maxTokens, m.temperature,
                        new NativeLlm.Callback() {
                            @Override
                            public boolean onPiece(byte[] utf8) {
                                stream.onDelta(new String(utf8, StandardCharsets.UTF_8));
                                return true;
                            }
                        });
                // too long for the context: drop the oldest turn after the instructions
                if (r == NativeLlm.ERR_TOO_LONG && msgs.size() > 2) {
                    msgs.remove(msgs.get(0).role.equals("system") ? 1 : 0);
                    continue;
                }
                break;
            }
            if (r < 0) {
                Log.w(TAG, m.name + " failed: " + r);
                stream.onError(r == NativeLlm.ERR_TOO_LONG ? "too long" : "engine " + r);
            } else {
                stream.onDone();
            }
        } finally {
            guardian.finish();
            synchronized (LocalEngine.class) {
                sActive--;
            }
        }
    }

    /**
     * Drops the (empty) think block Qwen3 writes before its answer, and the blank lines after
     * it, so the chat and the voice get the answer only.
     */
    private static final class NoThink implements BridgeClient.Stream {
        private final BridgeClient.Stream mOut;
        private final StringBuilder mHead = new StringBuilder();
        private boolean mPassing;

        NoThink(BridgeClient.Stream out) {
            mOut = out;
        }

        @Override
        public void onDelta(String delta) {
            if (mPassing) {
                mOut.onDelta(delta);
                return;
            }
            mHead.append(delta);
            final String h = mHead.toString();
            final String lead = h.replaceFirst("^\\s+", "");
            if (lead.isEmpty() || "<think>".startsWith(lead.length() > 7 ? "~" : lead)) {
                return;                                  // could still be the think tag
            }
            String rest;
            if (lead.startsWith("<think>")) {
                final int end = lead.indexOf("</think>");
                if (end < 0) {
                    return;                              // still inside the block
                }
                rest = lead.substring(end + "</think>".length());
            } else {
                rest = lead;
            }
            rest = rest.replaceFirst("^\\s+", "");
            if (rest.isEmpty()) {
                return;
            }
            mPassing = true;
            mOut.onDelta(rest);
        }

        @Override
        public void onDone() {
            if (!mPassing) {
                // a short answer that never got past the check above
                final String lead = mHead.toString().trim();
                if (!lead.isEmpty() && !lead.startsWith("<think>")) {
                    mOut.onDelta(lead);
                }
            }
            mOut.onDone();
        }

        @Override
        public void onError(String message) {
            mOut.onError(message);
        }
    }

    /** Kiro Thermal Guardian, kAI side: fewer threads while the SoC is hot */
    private static final class Guardian extends Thread {
        private final long mHandle;
        private volatile boolean mRunning = true;
        private int mThreads = THREADS;

        Guardian(long handle) {
            super("kAI-thermal");
            mHandle = handle;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (mRunning) {
                final int t = readTemp();
                if (t >= HOT_MC && mThreads != 2) {
                    mThreads = 2;
                    NativeLlm.nativeSetThreads(mHandle, 2);
                    Log.i(TAG, "thermal: battery " + t / 1000 + " C, 2 threads");
                } else if (t > 0 && t <= COOL_MC && mThreads != THREADS) {
                    mThreads = THREADS;
                    NativeLlm.nativeSetThreads(mHandle, THREADS);
                    Log.i(TAG, "thermal: battery " + t / 1000 + " C, " + THREADS + " threads");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        void finish() {
            mRunning = false;
            interrupt();
            // the next answer starts at full speed unless it is still hot
            if (mThreads != THREADS && readTemp() <= COOL_MC) {
                NativeLlm.nativeSetThreads(mHandle, THREADS);
            }
        }
    }

    private static android.content.Context sApp;

    /** the overlay (hold Power) is showing (Beta 4.1: no Edge Glow while kAI writes at all) */
    static volatile boolean sOverlayShowing;

    static void init(android.content.Context app) {
        sApp = app;
    }

    /**
     * battery temperature in milli-degrees C (-1 if unknown), from the sticky battery
     * broadcast: apps may not read the thermal sysfs nodes
     */
    static int readTemp() {
        if (sApp == null) {
            return -1;
        }
        final android.content.Intent b = sApp.registerReceiver(null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (b == null) {
            return -1;
        }
        final int tenths = b.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -10);
        return tenths < 0 ? -1 : tenths * 100;
    }

    private static long totalRamKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            final String l = r.readLine();   // MemTotal:  2765344 kB
            return Long.parseLong(l.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
