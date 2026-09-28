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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The kAI chat on disk (files/conversation.json), shared by the app and the overlay. */
final class ChatStore {

    private ChatStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "conversation.json");
    }

    static synchronized void save(Context c, List<BridgeClient.Message> messages) {
        try {
            final JSONArray a = new JSONArray();
            for (BridgeClient.Message m : messages) {
                a.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            try (FileOutputStream out = new FileOutputStream(file(c))) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // not fatal: the chat just won't survive a restart
        }
    }

    static synchronized List<BridgeClient.Message> load(Context c) {
        final List<BridgeClient.Message> out = new ArrayList<>();
        final File f = file(c);
        if (!f.exists()) {
            return out;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                final int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            final JSONArray a = new JSONArray(new String(data, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                out.add(new BridgeClient.Message(o.getString("role"), o.getString("content")));
            }
        } catch (Exception e) {
            out.clear();
        }
        return out;
    }

    /** adds a question and its answer at the end of the chat */
    static synchronized void append(Context c, String question, String answer) {
        final List<BridgeClient.Message> all = load(c);
        all.add(new BridgeClient.Message("user", question));
        all.add(new BridgeClient.Message("assistant", answer));
        save(c, all);
    }
}
