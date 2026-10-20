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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * kAI's engine (KiroOS Developer Beta 5): the cloud. The on-device models of Beta 3-4.1 (1.25 GB
 * of the ROM, a few words per second on the S6) are gone; kAI now asks the kAI cloud service, a
 * Cloudflare Worker running large free models (output/../kai-cloud), and gets the answer as it is
 * written. There is no model to pick and no key on the phone.
 *
 * Each phone has an anonymous id (a random UUID made on first use, kept in kAI's own data): the
 * service allows 300 requests in 5 hours per id, then answers "limit" with the time it resets.
 *
 * The class keeps the name and the static calls of the on-device engine, so the chat, the voice
 * overlay and the widgets did not change: there is one "model" (kAI), always available, never
 * loading.
 */
final class LocalEngine {

    private static final String TAG = "kAI";
    // kept for BridgeClient.local(): the engine needs no local address
    static final String HOST = "127.0.0.1";
    static final int PORT = 11435;

    /** the speech model for offline voice (the only model still on the phone) */
    static final String SYSTEM_DIR = "/system/usr/share/kai";
    static final String DATA_DIR = "/data/kiroos/models";

    /** the kAI cloud service; Settings.Global kiroos_kai_url can point elsewhere (testing) */
    private static final String DEFAULT_URL = "https://kiroos-kai.kai-cloud.workers.dev";

    /** what onError gets when the 5-hour limit is reached: this prefix, then the reset time */
    static final String LIMIT_PREFIX = "limit:";

    static final class Model {
        final String id;
        final String name;

        Model(String id, String name) {
            this.id = id;
            this.name = name;
        }

        boolean installed() {
            return true;
        }
    }

    static final Model CLOUD = new Model("cloud", "kAI");
    /** the voice talks with the same model now */
    static final Model LIVE = CLOUD;
    static final Model[] MODELS = {CLOUD};

    private static Context sApp;
    private static volatile HttpURLConnection sActive;
    private static volatile boolean sCancelled;
    /** what the service said last: requests left, and when the 5 hours end (Unix s, 0 = n/a) */
    static volatile int sLeft = -1;
    static volatile long sReset;

    /** the overlay (hold Power) is showing (Beta 4.1: no Edge Glow while kAI writes at all) */
    static volatile boolean sOverlayShowing;

    private LocalEngine() {
    }

    static void init(Context app) {
        sApp = app.getApplicationContext();
    }

    // --- what the rest of kAI calls (one model, always ready) ---------------------------------

    static Model find(String id) {
        return CLOUD;
    }

    static void select(String id) {
    }

    static Model selected() {
        return CLOUD;
    }

    static String modelName() {
        return CLOUD.name;
    }

    static boolean available() {
        return true;
    }

    static boolean available(Model m) {
        return true;
    }

    static boolean ensureRunning() {
        return true;
    }

    static boolean ensureRunning(Model want) {
        return true;
    }

    static boolean loading() {
        return false;
    }

    static void stop() {
        Voice.release();
    }

    static void releaseLater() {
    }

    static void keep() {
    }

    /** ends the answer being written */
    static void cancel() {
        sCancelled = true;
        final HttpURLConnection c = sActive;
        if (c != null) {
            c.disconnect();
        }
    }

    // --- the service ---------------------------------------------------------------------------

    private static String base() {
        String url = null;
        if (sApp != null) {
            url = android.provider.Settings.Global.getString(sApp.getContentResolver(),
                    "kiroos_kai_url");
        }
        return url != null && url.startsWith("https://") ? url : DEFAULT_URL;
    }

    /** this phone's anonymous id for the service (made once, kept in kAI's data) */
    static synchronized String installId() {
        final SharedPreferences p = sApp.getSharedPreferences("kai_cloud", Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("id", id).apply();
        }
        return id;
    }

    private static HttpURLConnection open(String path, String method) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setRequestProperty("X-Kiro-Install", installId());
        c.setRequestProperty("X-Kiro-Build", android.os.Build.DISPLAY);
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void quota(JSONObject o) {
        if (o.has("left")) sLeft = o.optInt("left", -1);
        if (o.has("reset")) sReset = o.optLong("reset", 0);
    }

    /** an HTTP error of the service as the message onError gets */
    private static String errorOf(HttpURLConnection c, int code) {
        try {
            final JSONObject o = new JSONObject(readAll(c.getErrorStream()));
            if ("limit".equals(o.optString("error"))) {
                sLeft = 0;
                sReset = o.optLong("reset", 0);
                return LIMIT_PREFIX + sReset;
            }
            if ("busy".equals(o.optString("error"))) return "busy";
        } catch (Exception e) {
            // no readable body
        }
        return "HTTP " + code;
    }

    /** the requests left and the reset time, without using one (blocking); false if offline */
    static boolean refreshStatus() {
        HttpURLConnection c = null;
        try {
            c = open("/v1/status", "GET");
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return false;
            quota(new JSONObject(readAll(c.getInputStream())));
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** the same, with a given model (there is one: kept for the voice overlay) */
    static void generate(Model model, List<BridgeClient.Message> history,
            BridgeClient.Stream stream) {
        generate(history, stream);
    }

    /** Writes the answer, streaming it. Blocking: call on a worker thread. */
    static void generate(List<BridgeClient.Message> history, BridgeClient.Stream stream) {
        sCancelled = false;
        HttpURLConnection c = null;
        try {
            final JSONArray msgs = new JSONArray();
            for (BridgeClient.Message m : history) {
                msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            final byte[] body = new JSONObject().put("messages", msgs).toString()
                    .getBytes(StandardCharsets.UTF_8);
            c = open("/v1/chat", "POST");
            sActive = c;
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            final int code = c.getResponseCode();
            if (code != 200) {
                stream.onError(errorOf(c, code));
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while (!sCancelled && (line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    final JSONObject o = new JSONObject(line.substring(5).trim());
                    if (o.has("error")) {
                        stream.onError(o.optString("error"));
                        return;
                    }
                    final String d = o.optString("d", "");
                    if (!d.isEmpty()) stream.onDelta(d);
                    if (o.optBoolean("done")) {
                        quota(o);
                        break;
                    }
                }
            }
            stream.onDone();
        } catch (Exception e) {
            if (sCancelled) {
                stream.onDone();
            } else {
                Log.w(TAG, "cloud: " + e);
                stream.onError("offline");
            }
        } finally {
            if (c != null) c.disconnect();
            sActive = null;
        }
    }

    /**
     * Speech to text in the cloud (Whisper, far better than the small model on the phone).
     * 16 kHz mono samples; null when it cannot (offline, the limit, an error): the phone's own
     * model does it then.
     */
    static String transcribe(float[] audio, String lang) {
        HttpURLConnection c = null;
        try {
            final byte[] wav = wav(audio, 16000);
            c = open("/v1/transcribe", "POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/wav");
            if (lang != null && !"auto".equals(lang)) c.setRequestProperty("X-Kai-Language", lang);
            c.setFixedLengthStreamingMode(wav.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(wav);
            }
            if (c.getResponseCode() != 200) return null;
            final JSONObject o = new JSONObject(readAll(c.getInputStream()));
            quota(o);
            return o.optString("text", "").trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 16-bit mono WAV of the samples */
    private static byte[] wav(float[] audio, int rate) {
        final int n = audio.length;
        final byte[] b = new byte[44 + n * 2];
        final java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 2)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2)
                .putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 2);
        for (float v : audio) {
            final int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            bb.putShort((short) s);
        }
        return b;
    }

    /** "riprova tra 4 h 12 min" style: the time until the 5 hours end, from now */
    static String untilReset(Context c, long reset) {
        final long s = Math.max(60, reset - System.currentTimeMillis() / 1000);
        final long h = s / 3600;
        final long m = (s % 3600 + 59) / 60;
        if (h == 0) return c.getResources().getQuantityString(R.plurals.kai_minutes, (int) m, m);
        if (m == 0 || m == 60) {
            final long hh = m == 60 ? h + 1 : h;
            return c.getResources().getQuantityString(R.plurals.kai_hours, (int) hh, hh);
        }
        return c.getString(R.string.kai_hours_minutes,
                c.getResources().getQuantityString(R.plurals.kai_hours, (int) h, h),
                c.getResources().getQuantityString(R.plurals.kai_minutes, (int) m, m));
    }

    /** the text an error of the service shows in the chat */
    static String errorText(Context c, String message) {
        if (message != null && message.startsWith(LIMIT_PREFIX)) {
            long reset = 0;
            try {
                reset = Long.parseLong(message.substring(LIMIT_PREFIX.length()));
            } catch (NumberFormatException e) {
                // unknown: 5 hours
            }
            if (reset <= 0) reset = System.currentTimeMillis() / 1000 + 5 * 3600;
            return c.getString(R.string.kai_limit, untilReset(c, reset));
        }
        if ("offline".equals(message)) return c.getString(R.string.kai_offline);
        if ("busy".equals(message)) return c.getString(R.string.kai_busy);
        return message;
    }
}
