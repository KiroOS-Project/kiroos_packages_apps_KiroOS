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
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Downloads an app from its official source and installs (or updates) it right here, silently,
 * through a PackageInstaller session. The package is verified by Android as usual (its own
 * developer signature; an update must match the installed one). F-Droid builds made for other
 * processors are tried in turn when Android says one does not fit this phone. Once installed,
 * init compiles the app ahead of time ("optimised for KiroOS").
 */
final class Installer {

    interface Progress {
        /** 0-100 while downloading, -1 while Android installs it */
        void onProgress(int percent);
    }

    /** what happened, for the UI */
    static final int OK = 0;
    static final int FAILED = 1;
    static final int INCOMPATIBLE = 2;
    static final int NO_NETWORK = 3;

    static final String ACTION_DONE = "com.kiroos.store.INSTALLED";
    private static final String TAG = "KeurStore";

    /** installs waiting for Android's answer, by session id */
    private static final SparseArray<Waiter> sWaiting = new SparseArray<>();

    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        int status = PackageInstaller.STATUS_FAILURE;
    }

    private Installer() {
    }

    /** Blocking. The result is also broadcast (ACTION_DONE) for the screens that show it. */
    static int install(Context c, Catalog.App app, Progress p) {
        int result;
        Sources.Release r = null;
        try {
            r = Sources.latest(app);
            result = FAILED;
            for (String url : r.urls) {
                result = installOne(c, app, url, p);
                if (result != INCOMPATIBLE) {
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.w(TAG, "install of " + app.pkg + ": " + e);
            result = NO_NETWORK;
        } catch (Exception e) {
            Log.w(TAG, "install of " + app.pkg + " failed", e);
            result = FAILED;
        }
        if (result == OK) {
            Sources.installed(c, app, r);
            SystemProperties.set("sys.kiroos.guard.optimize", app.pkg);
        }
        c.sendBroadcast(new Intent(ACTION_DONE).setPackage(c.getPackageName())
                .putExtra("pkg", app.pkg).putExtra("result", result));
        return result;
    }

    private static int installOne(Context c, Catalog.App app, String url, Progress p)
            throws Exception {
        final File apk = new File(c.getCacheDir(), app.pkg + ".apk");
        try {
            download(url, apk, p);
            if (p != null) {
                p.onProgress(-1);
            }
            final PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            final PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.pkg);
            params.setSize(apk.length());
            final int id = pi.createSession(params);
            final Waiter w = new Waiter();
            synchronized (sWaiting) {
                sWaiting.put(id, w);
            }
            try (PackageInstaller.Session s = pi.openSession(id);
                 InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                final byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                s.fsync(out);
                out.close();
                final Intent done = new Intent(c, Result.class).putExtra("pkg", app.pkg);
                s.commit(PendingIntent.getBroadcast(c, id, done,
                        PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender());
            }
            // Android answers in a few seconds; big games may take longer
            w.latch.await(3, TimeUnit.MINUTES);
            synchronized (sWaiting) {
                sWaiting.remove(id);
            }
            Log.i(TAG, app.pkg + " from " + url + ": status " + w.status);
            if (w.status == PackageInstaller.STATUS_SUCCESS) {
                return OK;
            }
            return w.status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                    ? INCOMPATIBLE : FAILED;
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
            int n;
            int last = -2;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                final int pct = total > 0 ? (int) (got * 100 / total) : 0;
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
        c.setRequestProperty("User-Agent", "KeurStore/2.0 (KiroOS)");
        try (InputStream in = c.getInputStream()) {
            return Catalog.read(in);
        } finally {
            c.disconnect();
        }
    }

    /** Android's answer for an install session: wakes up the install waiting for it */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            final int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            Log.i(TAG, intent.getStringExtra("pkg") + ": install status " + status + " "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            final Waiter w;
            synchronized (sWaiting) {
                w = sWaiting.get(id);
            }
            if (w != null) {
                w.status = status;
                w.latch.countDown();
            }
        }
    }
}
