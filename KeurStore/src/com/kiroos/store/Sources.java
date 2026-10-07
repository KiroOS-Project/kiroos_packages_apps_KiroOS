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
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The latest release of an app at its official source, and whether the installed one is
 * older. F-Droid and Signal say which version they have; for the other direct downloads the
 * file's fingerprint (ETag, Last-Modified, size) at install time is compared with today's.
 */
final class Sources {

    static final class Release {
        String versionName;
        long versionCode = -1;
        /** download URLs to try in order (F-Droid: one per processor type of the version) */
        final List<String> urls = new ArrayList<>();
        /** the file's fingerprint, for sources without a version */
        String fingerprint;
    }

    private Sources() {
    }

    /** Blocking (network). */
    static Release latest(Catalog.App app) throws Exception {
        final Release r = new Release();
        if (app.fdroid()) {
            final JSONObject o = new JSONObject(Installer.get(
                    "https://f-droid.org/api/v1/packages/" + app.pkg));
            final long suggested = o.getLong("suggestedVersionCode");
            final JSONArray p = o.getJSONArray("packages");
            for (int i = 0; i < p.length(); i++) {
                if (p.getJSONObject(i).getLong("versionCode") == suggested) {
                    r.versionName = p.getJSONObject(i).optString("versionName");
                }
            }
            r.versionCode = suggested;
            r.urls.add(apk(app.pkg, suggested));
            // the same version built for other processors (VLC, games): tried if needed
            for (int i = 0; i < p.length(); i++) {
                final JSONObject v = p.getJSONObject(i);
                final long code = v.getLong("versionCode");
                if (code != suggested && r.versionName != null
                        && r.versionName.equals(v.optString("versionName"))
                        && !r.urls.contains(apk(app.pkg, code))) {
                    r.urls.add(apk(app.pkg, code));
                }
            }
        } else if (app.source.equals("signal")) {
            final JSONObject o = new JSONObject(Installer.get(
                    "https://updates.signal.org/android/latest.json"));
            r.versionName = o.optString("versionName", null);
            r.versionCode = o.optLong("versionCode", -1);
            r.urls.add(o.getString("url"));
        } else if (app.source.equals("whatsapp")) {
            final String url = "https://www.whatsapp.com/android/current/WhatsApp.apk";
            r.urls.add(url);
            r.fingerprint = fingerprint(url);
        } else if (app.source.startsWith("apk:")) {
            final String url = app.source.substring(4);
            r.urls.add(url);
            r.fingerprint = fingerprint(url);
        } else if (app.source.startsWith("kiroos:")) {
            // a KiroOS component: its manifest sits next to the OTA manifest
            final String ota = android.os.SystemProperties.get("ro.kiroos.ota.url", "");
            if (!ota.startsWith("http")) {
                throw new IllegalStateException("no KiroOS update server");
            }
            final URL manifest = new URL(new URL(ota), app.source.substring(7));
            final JSONObject o = new JSONObject(Installer.get(manifest.toString()));
            r.versionName = o.optString("versionName", null);
            r.versionCode = o.getLong("versionCode");
            r.urls.add(new URL(manifest, o.getString("url")).toString());
        } else {
            throw new IllegalArgumentException(app.source);
        }
        return r;
    }

    private static String apk(String pkg, long code) {
        return "https://f-droid.org/repo/" + pkg + "_" + code + ".apk";
    }

    /**
     * ETag, Last-Modified and size of the file behind a download link. Not the final URL:
     * Telegram's carries a new token every time, so an update was seen every day.
     */
    static String fingerprint(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("HEAD");
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            for (int i = 0; i < 5 && c.getResponseCode() / 100 == 3; i++) {
                final String next = c.getHeaderField("Location");
                c.disconnect();
                c = (HttpURLConnection) new URL(new URL(url), next).openConnection();
                c.setRequestMethod("HEAD");
            }
            return c.getHeaderField("ETag") + "|" + c.getHeaderField("Last-Modified") + "|"
                    + c.getContentLengthLong();
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("store_installs", Context.MODE_PRIVATE);
    }

    /** remembers what was installed (for sources that give no version) */
    static void installed(Context c, Catalog.App app, Release r) {
        if (r.fingerprint != null) {
            prefs(c).edit().putString(app.pkg, r.fingerprint).apply();
        }
    }

    /** true when the source has a newer build than the one on the phone */
    static boolean hasUpdate(Context c, Catalog.App app, PackageInfo installed, Release r) {
        if (installed == null || r == null || !installed.packageName.equals(app.pkg)) {
            // another package of the app (from Google Play, signed by it): not ours to update
            return false;
        }
        if (r.versionCode > 0) {
            return installed.versionCode < r.versionCode;
        }
        if (r.fingerprint != null) {
            final String had = prefs(c).getString(app.pkg, null);
            if (had == null) {
                // installed before KeurStore knew it: this is the build it has
                prefs(c).edit().putString(app.pkg, r.fingerprint).apply();
                return false;
            }
            return !had.equals(r.fingerprint);
        }
        return false;
    }
}
