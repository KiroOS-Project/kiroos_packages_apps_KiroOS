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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.SystemProperties;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads an app from its official source and installs (or updates) it silently through a
 * PackageInstaller session. The package itself is verified by Android as usual (its own
 * developer signature; an update must match the installed one). Once installed, init
 * compiles it ahead of time ("optimised for KiroOS").
 */
final class Installer {

    interface Progress {
        void onProgress(int percent);
    }

    static final String ACTION_DONE = "com.kiroos.store.INSTALLED";
    private static final String TAG = "KeurStore";

    private Installer() {
    }

    /** the real download URL of a direct source */
    static String resolve(String source) throws Exception {
        if (source.startsWith("apk:")) {
            return source.substring(4);
        }
        if (source.equals("signal")) {
            // Signal's official website APK feed
            final JSONObject o = new JSONObject(get("https://updates.signal.org/android/latest.json"));
            return o.getString("url");
        }
        throw new IllegalArgumentException(source);
    }

    /** Blocking. Returns true when the session was committed (the result arrives in Result). */
    static boolean install(Context c, Catalog.App app, Progress p) {
        final File apk = new File(c.getCacheDir(), app.pkg + ".apk");
        try {
            final String url = resolve(app.source);
            download(url, apk, p);
            final PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.pkg);
            final int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id);
                 InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                final byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                s.fsync(out);
                final Intent done = new Intent(c, Result.class).putExtra("pkg", app.pkg);
                s.commit(PendingIntent.getBroadcast(c, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "install of " + app.pkg + " failed", e);
            return false;
        } finally {
            apk.delete();
        }
    }

    private static void download(String url, File to, Progress p) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        // https -> https redirects are followed by HttpURLConnection only on the same scheme
        for (int i = 0; i < 5 && c.getResponseCode() / 100 == 3; i++) {
            final String next = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(new URL(url), next).openConnection();
        }
        if (c.getResponseCode() != 200) {
            throw new java.io.IOException("HTTP " + c.getResponseCode());
        }
        final long total = c.getContentLengthLong();
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            final byte[] buf = new byte[65536];
            long got = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                final int pct = total > 0 ? (int) (got * 100 / total) : -1;
                if (p != null && pct != last) {
                    last = pct;
                    p.onProgress(pct);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    static String get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            return Catalog.read(in);
        } finally {
            c.disconnect();
        }
    }

    /** the result of an install session: success -> compile it (init, kiroos_dexopt.sh) */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            final String pkg = intent.getStringExtra("pkg");
            Log.i(TAG, pkg + ": install status " + status + " "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            if (status == PackageInstaller.STATUS_SUCCESS && pkg != null) {
                SystemProperties.set("sys.kiroos.guard.optimize", pkg);
            }
            c.sendBroadcast(new Intent(ACTION_DONE).setPackage(c.getPackageName())
                    .putExtra("pkg", pkg).putExtra("ok", status == PackageInstaller.STATUS_SUCCESS));
        }
    }
}
