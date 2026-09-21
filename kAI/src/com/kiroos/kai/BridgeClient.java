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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Talks to the kAI bridge on the PC (scripts/kai_bridge.py): the model runs there, the
 * phone only sends the conversation and shows the answer as it streams in.
 *
 * Protocol: GET /health → {"ok":true,"model":"…"}; POST /v1/chat with
 * {"model":"…","messages":[{"role","content"}…]} → newline-delimited JSON
 * {"delta":"…"} … {"done":true} (or {"error":"…"}).
 *
 * Over USB, "adb reverse tcp:8765 tcp:8765" makes 127.0.0.1:8765 on the phone reach the PC.
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

    private final String mBase;
    private volatile HttpURLConnection mActive;
    private volatile boolean mCancelled;

    BridgeClient(String host, int port) {
        mBase = "http://" + host + ":" + port;
    }

    /** "model name" when the bridge answers, else throws */
    String health() throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(mBase + "/health").openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(2500);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(),
                StandardCharsets.UTF_8))) {
            final JSONObject o = new JSONObject(r.readLine());
            return o.optString("model", "");
        } finally {
            c.disconnect();
        }
    }

    /** Blocking: call on a worker thread. */
    void chat(String model, List<Message> history, Stream stream) {
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
            c = (HttpURLConnection) new URL(mBase + "/v1/chat").openConnection();
            mActive = c;
            c.setConnectTimeout(4000);
            c.setReadTimeout(120000); // big local models can think for a while
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setChunkedStreamingMode(0);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) {
                stream.onError("HTTP " + c.getResponseCode());
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
                    if (o.has("delta")) {
                        stream.onDelta(o.getString("delta"));
                    }
                    if (o.optBoolean("done")) {
                        break;
                    }
                }
            }
            stream.onDone();
        } catch (JSONException e) {
            stream.onError("bad answer from the bridge");
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

    void cancel() {
        mCancelled = true;
        final HttpURLConnection c = mActive;
        if (c != null) {
            c.disconnect();
        }
    }
}
