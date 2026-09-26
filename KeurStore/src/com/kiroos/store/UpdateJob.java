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

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * Silent background updates of the apps KeurStore installed from their official download,
 * once a day on Wi-Fi while charging. An app is downloaded again only when the file on the
 * developer's server changed (ETag / Last-Modified / size); Android then accepts the update
 * only if it is signed like the installed app. Play apps are updated by Google Play.
 */
public class UpdateJob extends JobService {

    private static final String TAG = "KeurStore";
    private static final int JOB = 0x4b53;   // "KS"

    static void schedule(Context c) {
        final JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB) != null) {
            return;
        }
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
                .setPeriodic(24L * 60 * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresCharging(true)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                update(UpdateJob.this);
                jobFinished(params, false);
            }
        }, "KeurStore-update").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void update(Context c) {
        Catalog.refresh(c);
        final SharedPreferences prefs = c.getSharedPreferences("updates", MODE_PRIVATE);
        final Map<String, List<Catalog.App>> cat = Catalog.load(c, true);
        for (List<Catalog.App> apps : cat.values()) {
            for (Catalog.App app : apps) {
                if (!app.direct() || !installed(c, app.pkg)) {
                    continue;
                }
                try {
                    final String url = Installer.resolve(app.source);
                    final String tag = fingerprint(url);
                    final String last = prefs.getString(app.pkg, null);
                    if (tag.equals(last)) {
                        continue;
                    }
                    if (last == null) {
                        // first check after installing: this is the installed version
                        prefs.edit().putString(app.pkg, tag).apply();
                        continue;
                    }
                    Log.i(TAG, "updating " + app.pkg);
                    if (Installer.install(c, app, null)) {
                        prefs.edit().putString(app.pkg, tag).apply();
                    }
                } catch (Exception e) {
                    Log.i(TAG, "update check of " + app.pkg + " failed: " + e.getMessage());
                }
            }
        }
    }

    private static String fingerprint(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("HEAD");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        try {
            c.getResponseCode();
            return c.getHeaderField("ETag") + "|" + c.getHeaderField("Last-Modified") + "|"
                    + c.getContentLengthLong() + "|" + c.getURL();
        } finally {
            c.disconnect();
        }
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            schedule(c);
        }
    }
}
