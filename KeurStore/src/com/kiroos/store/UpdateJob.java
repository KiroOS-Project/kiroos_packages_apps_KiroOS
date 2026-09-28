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
import android.content.pm.PackageInfo;
import android.util.Log;

/**
 * Silent background updates of the catalogue apps on the phone, once a day on Wi-Fi while
 * charging: the ones KeurStore can update (same package, a newer build at the official
 * source). Android accepts an update only if it is signed like the installed app.
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
        for (Catalog.App app : Catalog.all(Catalog.load(c, true))) {
            if (!app.direct()) {
                continue;
            }
            final PackageInfo have = app.installed(c.getPackageManager());
            if (have == null) {
                continue;
            }
            try {
                final Sources.Release r = Sources.latest(app);
                if (Sources.hasUpdate(c, app, have, r)) {
                    Log.i(TAG, "updating " + app.pkg);
                    Installer.install(c, app, null);
                }
            } catch (Exception e) {
                Log.i(TAG, "update check of " + app.pkg + " failed: " + e.getMessage());
            }
        }
    }

    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            schedule(c);
        }
    }
}
