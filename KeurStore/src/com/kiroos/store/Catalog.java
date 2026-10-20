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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
 * The KeurStore catalogue: a curated list of apps, each with its official source. Shipped in
 * the app (res/raw/catalog.json) and refreshed from the KiroOS update server
 * (<server>/store/catalog.json) when it answers with a catalogue at least as new.
 *
 * Sources, all installed and updated right here:
 *   "apk:<url>"  the developer's own download (Telegram)
 *   "signal"     Signal's official APK feed (with its version)
 *   "whatsapp"   WhatsApp's official APK
 *   "fdroid"     F-Droid, for open-source apps (its API gives the version)
 *   "system"     a KiroOS app already in the ROM
 *   "kiroos:<file>" a KiroOS component in the ROM that is also updated apart from it
 *                (KOptimize WebView): <file> next to the OTA manifest, with versionCode,
 *                versionName and url
 * Google Play apps cannot be installed outside Google Play: a catalogue entry with a "play"
 * source (older catalogues) is left out.
 */
final class Catalog {

    static final class App {
        final String pkg;
        /** other packages of the same app (Telegram from Google Play is another package) */
        final String[] also;
        final String name;
        final String desc;
        final String source;
        final String icon;
        final String site;
        final String section;

        App(String pkg, String[] also, String name, String desc, String source, String icon,
                String site, String section) {
            this.pkg = pkg;
            this.also = also;
            this.name = name;
            this.desc = desc;
            this.source = source;
            this.icon = icon;
            this.site = site;
            this.section = section;
        }

        /** the app's own colour (the store page and the cards), 0 when the catalogue has none */
        int color;
        /** a card's two lines on Discover, for the featured apps */
        String eyebrow;
        String headline;

        boolean direct() {
            return source.startsWith("apk:") || source.equals("signal")
                    || source.equals("whatsapp") || source.equals("fdroid")
                    || source.startsWith("kiroos:");
        }

        /** KeurStore can bring a newer build of it (a KiroOS app: when one is published) */
        boolean updatable() {
            return direct() || system();
        }

        boolean fdroid() {
            return source.equals("fdroid");
        }

        boolean system() {
            return source.equals("system");
        }

        /** where its picture comes from until it is installed */
        String iconUrl() {
            if (icon != null && !icon.isEmpty()) {
                return icon;
            }
            return fdroid() ? "https://f-droid.org/repo/" + pkg + "/en-US/icon.png" : null;
        }

        /** the installed package of this app (this one or one of the others), or null */
        PackageInfo installed(PackageManager pm) {
            final PackageInfo p = info(pm, pkg);
            if (p != null) {
                return p;
            }
            if (also != null) {
                for (String a : also) {
                    final PackageInfo q = info(pm, a);
                    if (q != null) {
                        return q;
                    }
                }
            }
            return null;
        }

        private static PackageInfo info(PackageManager pm, String pkg) {
            try {
                return pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
    }

    private static final String TAG = "KeurStore";
    private static final String CACHE = "catalog.json";

    private Catalog() {
    }

    /** section id -> apps, in the catalogue's order */
    static Map<String, List<App>> load(Context c, boolean italian) {
        String bundled;
        try (InputStream in = c.getResources().openRawResource(R.raw.catalog)) {
            bundled = read(in);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
        String json = bundled;
        final File cached = new File(c.getFilesDir(), CACHE);
        if (cached.canRead()) {
            try (InputStream in = new FileInputStream(cached)) {
                final String fresh = read(in);
                // an older server catalogue never replaces the one in this app
                if (version(fresh) >= version(bundled)) {
                    json = fresh;
                }
            } catch (Exception e) {
                json = bundled;
            }
        }
        try {
            return parse(json, italian);
        } catch (IllegalArgumentException e) {
            return parse(bundled, italian);
        }
    }

    /** every app, in order */
    static List<App> all(Map<String, List<App>> cat) {
        final List<App> out = new ArrayList<>();
        for (List<App> l : cat.values()) {
            out.addAll(l);
        }
        return out;
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

    private static int version(String json) {
        try {
            return new JSONObject(json).optInt("version", 1);
        } catch (Exception e) {
            return 0;
        }
    }

    private static Map<String, List<App>> parse(String json, boolean italian) {
        final Map<String, List<App>> out = new LinkedHashMap<>();
        try {
            final JSONArray sections = new JSONObject(json).getJSONArray("sections");
            for (int i = 0; i < sections.length(); i++) {
                final JSONObject s = sections.getJSONObject(i);
                final String section = s.getString("id");
                final JSONArray apps = s.getJSONArray("apps");
                final List<App> list = new ArrayList<>();
                for (int j = 0; j < apps.length(); j++) {
                    final JSONObject a = apps.getJSONObject(j);
                    final String source = a.optString("source", "play");
                    if (source.equals("play")) {
                        continue;                 // not installable outside Google Play
                    }
                    final JSONArray alsoJ = a.optJSONArray("also");
                    String[] also = null;
                    if (alsoJ != null) {
                        also = new String[alsoJ.length()];
                        for (int k = 0; k < also.length; k++) {
                            also[k] = alsoJ.getString(k);
                        }
                    }
                    final App app = new App(a.getString("pkg"), also, a.getString("name"),
                            italian ? a.optString("desc")
                                    : a.optString("desc_en", a.optString("desc")),
                            source, a.optString("icon", null), a.optString("site", null),
                            section);
                    try {
                        app.color = android.graphics.Color.parseColor(a.optString("color", ""));
                    } catch (IllegalArgumentException e) {
                        app.color = 0;
                    }
                    app.eyebrow = italian ? a.optString("eyebrow", null)
                            : a.optString("eyebrow_en", a.optString("eyebrow", null));
                    app.headline = italian ? a.optString("headline", null)
                            : a.optString("headline_en", a.optString("headline", null));
                    list.add(app);
                }
                out.put(section, list);
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
