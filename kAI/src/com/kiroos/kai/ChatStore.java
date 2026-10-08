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

    // ---- the history: earlier chats, files/history/<time>.json, newest first, at most 50

    static final int HISTORY_MAX = 50;

    /** one earlier chat: its file, when it was put away, its first question */
    static final class Past {
        final File file;
        final long time;
        final String title;

        Past(File file, long time, String title) {
            this.file = file;
            this.time = time;
            this.title = title;
        }
    }

    private static File historyDir(Context c) {
        final File d = new File(c.getFilesDir(), "history");
        d.mkdirs();
        return d;
    }

    /** a finished chat goes into the history (nothing happens for an empty one) */
    static synchronized void archive(Context c, List<BridgeClient.Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        try {
            final JSONArray a = new JSONArray();
            for (BridgeClient.Message m : messages) {
                a.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            final File f = new File(historyDir(c), System.currentTimeMillis() + ".json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return;
        }
        final List<Past> all = history(c);
        for (int i = HISTORY_MAX; i < all.size(); i++) {
            all.get(i).file.delete();
        }
    }

    /** the earlier chats, newest first */
    static synchronized List<Past> history(Context c) {
        final List<Past> out = new ArrayList<>();
        final File[] files = historyDir(c).listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            final String name = f.getName();
            if (!name.endsWith(".json")) {
                continue;
            }
            long time;
            try {
                time = Long.parseLong(name.substring(0, name.length() - 5));
            } catch (NumberFormatException e) {
                continue;
            }
            String title = "";
            for (BridgeClient.Message m : read(f)) {
                if ("user".equals(m.role)) {
                    title = m.content.trim().replace('\n', ' ');
                    break;
                }
            }
            out.add(new Past(f, time, title));
        }
        java.util.Collections.sort(out, new java.util.Comparator<Past>() {
            @Override
            public int compare(Past a, Past b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    static synchronized List<BridgeClient.Message> open(Past p) {
        return read(p.file);
    }

    static synchronized void delete(Past p) {
        p.file.delete();
    }

    static synchronized void clearHistory(Context c) {
        final File[] files = historyDir(c).listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static List<BridgeClient.Message> read(File f) {
        final List<BridgeClient.Message> out = new ArrayList<>();
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
