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

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The on-device model: llama.cpp's server (shipped as /system/bin/kai_llama) with a small
 * instruction model in /system/usr/share/kai. It runs as a child of the kAI process, bound
 * to 127.0.0.1, and only while needed: started when kAI opens, stopped a few minutes after
 * the last answer (the model's memory goes back to the system). Android kills it together
 * with kAI's process group, so it never outlives the app.
 */
final class LocalEngine {

    private static final String TAG = "kAI";
    static final String BINARY = "/system/bin/kai_llama";
    static final String MODEL = "/system/usr/share/kai/qwen2.5-0.5b-instruct-q4_k_m.gguf";
    static final String MODEL_NAME = "Qwen2.5 0.5B";
    static final String HOST = "127.0.0.1";
    static final int PORT = 11435;
    /** big cores of the Exynos 7420 (4x Cortex-A57) */
    private static final int THREADS = 4;

    private static Process sProcess;

    private LocalEngine() {
    }

    /** true when this build ships the engine and the model */
    static boolean available() {
        return new File(BINARY).canExecute() && new File(MODEL).canRead();
    }

    /**
     * Starts the server if it is not running and waits until the model is loaded
     * (a few seconds from /system). Blocking: call it on a worker thread.
     */
    static synchronized boolean ensureRunning() {
        if (sProcess != null && alive(sProcess) && healthy()) {
            return true;
        }
        stop();
        try {
            sProcess = new ProcessBuilder(BINARY,
                    "-m", MODEL,
                    "--host", HOST, "--port", String.valueOf(PORT),
                    "-t", String.valueOf(THREADS),
                    "-c", "4096",
                    "--no-webui",
                    "--log-disable")
                    .redirectErrorStream(true)
                    .start();
            drain(sProcess);
        } catch (Exception e) {
            Log.w(TAG, "cannot start the on-device model", e);
            sProcess = null;
            return false;
        }
        // loading ~400 MB of weights: up to half a minute on a cold cache
        for (int i = 0; i < 120; i++) {
            if (!alive(sProcess)) {
                Log.w(TAG, "on-device model exited");
                sProcess = null;
                return false;
            }
            if (healthy()) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        stop();
        return false;
    }

    static synchronized void stop() {
        if (sProcess != null) {
            sProcess.destroy();
            sProcess = null;
        }
    }

    /**
     * Reads and drops the server's output (N has no ProcessBuilder.redirectOutput): a full
     * pipe would block the server on its next log line.
     */
    private static void drain(final Process p) {
        final Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final byte[] buf = new byte[4096];
                try (java.io.InputStream in = p.getInputStream()) {
                    while (in.read(buf) >= 0) {
                        // discard
                    }
                } catch (java.io.IOException e) {
                    // the server exited
                }
            }
        }, "kAI-engine-log");
        t.setDaemon(true);
        t.start();
    }

    private static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** GET /health answers 200 once the model is loaded (503 while loading) */
    private static boolean healthy() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + HOST + ":" + PORT + "/health")
                    .openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(1000);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
