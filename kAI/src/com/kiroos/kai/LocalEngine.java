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

        Model(String id, String name, String file, int ctx, float temperature, int maxTokens) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.ctx = ctx;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
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

    static final Model[] MODELS = {
        // SmolLM2 135M: instant, arithmetic, definitions, phone settings
        new Model("lite", "Keng 1.5 Lite", "smollm2-135m-instruct-q4_k_m.gguf", 2048, 0.2f, 384),
        // Qwen2.5 0.5B: everyday assistant
        new Model("base", "Keng 1.5 Base", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 4096, 0.4f, 512),
        // Llama 3.2 1B: code and step-by-step reasoning; the context is capped so the KV cache
        // cannot push a 3 GB phone out of memory
        new Model("pro", "Keng 1.5 Pro", "llama-3.2-1b-instruct-q4_k_m.gguf",
                BIG_RAM ? 2048 : 1024, 0.5f, 640),
    };

    private static volatile Model sSelected = MODELS[1];
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
        return MODELS[1];
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
        return library() && sSelected.installed();
    }

    /** Loads the chosen model if it is not the one in memory. Blocking: a worker thread. */
    static synchronized boolean ensureRunning() {
        final Model want = sSelected;
        if (sHandle != 0 && sLoaded == want) {
            return true;
        }
        if (!available()) {
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
        final long h;
        final Model m;
        synchronized (LocalEngine.class) {
            if (!ensureRunning()) {
                stream.onError("model");
                return;
            }
            h = sHandle;
            m = sLoaded;
            sActive++;
        }
        final List<BridgeClient.Message> msgs = new ArrayList<>(history);
        final Guardian guardian = new Guardian(h);
        guardian.start();
        edgeGlow("kai_start");
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
            edgeGlow("kai_stop");
            synchronized (LocalEngine.class) {
                sActive--;
            }
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

    /** the overlay (hold Power) is showing: its aura is the light, no Edge Glow as well */
    static volatile boolean sOverlayShowing;

    /** Kiro Edge Glow (SystemUI): the screen edge glows while kAI writes */
    private static void edgeGlow(String mode) {
        if (sApp != null && !sOverlayShowing) {
            sApp.sendBroadcast(new android.content.Intent("com.kiroos.intent.EDGE_GLOW")
                    .setPackage("com.android.systemui").putExtra("mode", mode));
        }
    }

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
