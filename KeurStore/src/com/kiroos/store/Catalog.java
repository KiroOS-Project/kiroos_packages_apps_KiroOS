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

package com.kiroos.store;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The KeurStore catalogue: a curated list of apps with their official source. Shipped in the
 * app (res/raw/catalog.json) and refreshed from the KiroOS update server
 * (<server>/store/catalog.json) when it answers.
 *
 * Sources: "play" (Google Play page), "apk:<url>" (the developer's own download, installed
 * and kept up to date by KeurStore), "signal" (Signal's official APK feed), "system" (a
 * KiroOS app already in the ROM).
 */
final class Catalog {

    static final class App {
        final String pkg;
        final String name;
        final String desc;
        final String source;

        App(String pkg, String name, String desc, String source) {
            this.pkg = pkg;
            this.name = name;
            this.desc = desc;
            this.source = source;
        }

        boolean direct() {
            return source.startsWith("apk:") || source.equals("signal");
        }
    }

    private static final String TAG = "KeurStore";
    private static final String CACHE = "catalog.json";

    private Catalog() {
    }

    /** section id -> apps, in the catalogue's order */
    static Map<String, List<App>> load(Context c, boolean italian) {
        String json = null;
        final File cached = new File(c.getFilesDir(), CACHE);
        if (cached.canRead()) {
            try (InputStream in = new FileInputStream(cached)) {
                json = read(in);
            } catch (Exception e) {
                json = null;
            }
        }
        if (json == null) {
            try (InputStream in = c.getResources().openRawResource(R.raw.catalog)) {
                json = read(in);
            } catch (Exception e) {
                return new LinkedHashMap<>();
            }
        }
        return parse(json, italian);
    }

    /** Blocking: the latest catalogue from the update server, kept for next time. */
    static boolean refresh(Context c) {
        final String ota = SystemProperties.get("ro.kiroos.ota.url", "");
        if (!ota.startsWith("http")) {
            return false;
        }
        final String url = ota.substring(0, ota.lastIndexOf('/') + 1) + "store/catalog.json";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            final String json;
            try (InputStream in = conn.getInputStream()) {
                json = read(in);
            }
            parse(json, true);   // valid before it replaces the old one
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), CACHE))) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, "catalogue not refreshed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Map<String, List<App>> parse(String json, boolean italian) {
        final Map<String, List<App>> out = new LinkedHashMap<>();
        try {
            final JSONArray sections = new JSONObject(json).getJSONArray("sections");
            for (int i = 0; i < sections.length(); i++) {
                final JSONObject s = sections.getJSONObject(i);
                final JSONArray apps = s.getJSONArray("apps");
                final List<App> list = new ArrayList<>();
                for (int j = 0; j < apps.length(); j++) {
                    final JSONObject a = apps.getJSONObject(j);
                    list.add(new App(a.getString("pkg"), a.getString("name"),
                            italian ? a.optString("desc") : a.optString("desc_en", a.optString("desc")),
                            a.optString("source", "play")));
                }
                out.put(s.getString("id"), list);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("bad catalogue", e);
        }
        return out;
    }

    static String read(InputStream in) throws java.io.IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
