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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Talks to the language model running on the PC: the model runs there, the phone only
 * sends the conversation and shows the answer as it streams in.
 *
 * Two servers are understood, detected by {@link #health()}:
 * <ul>
 * <li>Ollama itself (default, port 11434): GET /api/tags lists the models, POST /api/chat
 * streams {"message":{"content":"…"},"done":false} lines. Over USB,
 * "adb reverse tcp:11434 tcp:11434" is all the PC needs (scripts/kai_usb.cmd).</li>
 * <li>the KiroOS kAI bridge (scripts/kai_bridge.py): GET /health, POST /v1/chat streaming
 * {"delta":"…"} … {"done":true} lines.</li>
 * </ul>
 */
final class BridgeClient {

    interface Stream {
        void onDelta(String text);

        void onDone();

        void onError(String message);
    }

    static final class Message {
        final String role;
        String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Thrown by {@link #health()} when the server answers but has no model to use. */
    static final class NoModelException extends Exception {
        NoModelException() {
            super("no model");
        }
    }

    private final String mBase;
    private volatile HttpURLConnection mActive;
    private volatile boolean mCancelled;
    private boolean mOllama = true;
    /** the on-device llama.cpp server (OpenAI-style streaming) */
    private final boolean mLocal;

    BridgeClient(String host, int port) {
        this(host, port, false);
    }

    private BridgeClient(String host, int port, boolean local) {
        mBase = "http://" + host + ":" + port;
        mLocal = local;
    }

    /** the model on the phone (LocalEngine must be running) */
    static BridgeClient local() {
        return new BridgeClient(LocalEngine.HOST, LocalEngine.PORT, true);
    }

    boolean isLocal() {
        return mLocal;
    }

    /**
     * The model that will answer (the preferred one if the server has it, else the first
     * one it has). Throws when nothing answers, NoModelException when no model is installed.
     */
    String health(String preferred) throws Exception {
        try {
            final JSONArray models = new JSONObject(get("/api/tags")).optJSONArray("models");
            mOllama = true;
            if (models == null || models.length() == 0) {
                throw new NoModelException();
            }
            String first = null;
            for (int i = 0; i < models.length(); i++) {
                final String name = models.getJSONObject(i).optString("name", "");
                if (first == null) {
                    first = name;
                }
                if (!preferred.isEmpty() && (name.equals(preferred)
                        || name.equals(preferred + ":latest"))) {
                    return name;
                }
            }
            return first;
        } catch (FileNotFoundException e) {
            // HTTP 404: not Ollama, try the kAI bridge protocol
            mOllama = false;
            return new JSONObject(get("/health")).optString("model", preferred);
        }
    }

    private String get(String path) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(mBase + path).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(4000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Blocking: call on a worker thread, after {@link #health} picked the protocol. */
    void chat(String model, List<Message> history, Stream stream) {
        if (mLocal) {
            chatLocal(history, stream);
            return;
        }
        mCancelled = false;
        HttpURLConnection c = null;
        try {
            final JSONArray msgs = new JSONArray();
            for (Message m : history) {
                msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            final JSONObject body = new JSONObject().put("messages", msgs).put("stream", true);
            if (model != null && !model.isEmpty()) {
                body.put("model", model);
            }
            if (mOllama) {
                // keep the model loaded between questions (Ollama's default is 5 minutes)
                body.put("keep_alive", "30m");
            }
            c = (HttpURLConnection) new URL(mBase + (mOllama ? "/api/chat" : "/v1/chat"))
                    .openConnection();
            mActive = c;
            c.setConnectTimeout(4000);
            c.setReadTimeout(180000); // loading a model on the PC can take a while
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setChunkedStreamingMode(0);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = c.getResponseCode();
            if (code != 200) {
                stream.onError(errorOf(c, code));
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while (!mCancelled && (line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    final JSONObject o = new JSONObject(line);
                    if (o.has("error")) {
                        stream.onError(o.getString("error"));
                        return;
                    }
                    final String delta = mOllama
                            ? (o.has("message") ? o.getJSONObject("message").optString("content")
                                    : "")
                            : o.optString("delta");
                    if (!delta.isEmpty()) {
                        stream.onDelta(delta);
                    }
                    if (o.optBoolean("done")) {
                        break;
                    }
                }
            }
            stream.onDone();
        } catch (JSONException e) {
            stream.onError("bad answer from the PC");
        } catch (Exception e) {
            if (mCancelled) {
                stream.onDone();
            } else {
                stream.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            if (c != null) {
                c.disconnect();
            }
            mActive = null;
        }
    }

    /**
     * The on-device server: POST /v1/chat/completions with stream=true answers server-sent
     * events, "data: {choices:[{delta:{content}}]}" lines and a final "data: [DONE]".
     */
    private void chatLocal(List<Message> history, Stream stream) {
        mCancelled = false;
        HttpURLConnection c = null;
        try {
            final JSONArray msgs = new JSONArray();
            for (Message m : history) {
                msgs.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            final JSONObject body = new JSONObject().put("messages", msgs)
                    .put("stream", true)
                    // a phone-sized answer, and a small model repeats itself less when capped
                    .put("max_tokens", 512)
                    .put("temperature", 0.6);
            c = (HttpURLConnection) new URL(mBase + "/v1/chat/completions").openConnection();
            mActive = c;
            c.setConnectTimeout(4000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setChunkedStreamingMode(0);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = c.getResponseCode();
            if (code != 200) {
                stream.onError(errorOf(c, code));
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while (!mCancelled && (line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    final String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    final JSONObject o = new JSONObject(data);
                    final JSONArray choices = o.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    final JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    final String text = delta != null ? delta.optString("content", "") : "";
                    if (!text.isEmpty() && !"null".equals(text)) {
                        stream.onDelta(text);
                    }
                }
            }
            stream.onDone();
        } catch (JSONException e) {
            stream.onError("bad answer from the model");
        } catch (Exception e) {
            if (mCancelled) {
                stream.onDone();
            } else {
                stream.onError(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        } finally {
            if (c != null) {
                c.disconnect();
            }
            mActive = null;
        }
    }

    /** The server's own error message ({"error":"…"}) if it sent one, else "HTTP code". */
    private static String errorOf(HttpURLConnection c, int code) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getErrorStream(),
                StandardCharsets.UTF_8))) {
            final String err = new JSONObject(r.readLine()).optString("error", "");
            if (!err.isEmpty()) {
                return err;
            }
        } catch (Exception e) {
            // no readable body
        }
        return "HTTP " + code;
    }

    void cancel() {
        mCancelled = true;
        final HttpURLConnection c = mActive;
        if (c != null) {
            c.disconnect();
        }
    }
}
