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
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * KeurStore on the home (KiroOS Beta 4 widget): the apps with an update waiting, found in the
 * background (every 3 hours on any network) without opening the store. With updates it lists
 * them with an Update button; with none it stays a quiet line, "All apps are up to date".
 */
public class StoreWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";
    private static final int JOB = 0x4b57;   // "KW"

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        manager.updateAppWidget(ids, views(context));
        schedule(context);
    }

    @Override
    public void onEnabled(Context context) {
        // a first look right away, then every 3 hours
        checkSoon(context);
        schedule(context);
    }

    /** a look within a minute (the widget was added, or the store was just used) */
    static void checkSoon(Context c) {
        final int[] ids = AppWidgetManager.getInstance(c).getAppWidgetIds(
                new ComponentName(c, StoreWidget.class));
        if (ids == null || ids.length == 0) return;
        c.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB + 1, new ComponentName(c, Check.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setOverrideDeadline(60 * 1000)
                        .build());
    }

    @Override
    public void onDisabled(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB);
    }

    static void schedule(Context c) {
        final JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB) != null) return;
        js.schedule(new JobInfo.Builder(JOB, new ComponentName(c, Check.class))
                .setPeriodic(3L * 60 * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void show(Context context) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new ComponentName(context, StoreWidget.class));
        if (ids == null || ids.length == 0) return;
        m.updateAppWidget(ids, views(context));
    }

    private static RemoteViews views(Context c) {
        final SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final int count = p.getInt("count", 0);
        final String names = p.getString("names", "");
        final RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_store);
        if (count > 0) {
            v.setTextViewText(R.id.store_title, c.getResources().getQuantityString(
                    R.plurals.widget_store_updates, count, count));
            v.setTextViewText(R.id.store_apps, names);
            v.setViewVisibility(R.id.store_apps, View.VISIBLE);
            v.setViewVisibility(R.id.store_update, View.VISIBLE);
        } else {
            v.setTextViewText(R.id.store_title, c.getString(R.string.app_name));
            v.setTextViewText(R.id.store_apps, c.getString(R.string.widget_store_none));
            v.setViewVisibility(R.id.store_update, View.GONE);
        }
        final PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, StoreActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.store_root, open);
        v.setOnClickPendingIntent(R.id.store_update, open);
        return v;
    }

    /** blocking: which catalogue apps on the phone have a newer release */
    static void check(Context c) {
        Catalog.refresh(c);
        final boolean italian = "it".equals(c.getResources().getConfiguration().getLocales()
                .get(0).getLanguage());
        final List<String> names = new ArrayList<>();
        for (Catalog.App app : Catalog.all(Catalog.load(c, italian))) {
            final PackageInfo have = app.installed(c.getPackageManager());
            if (have == null) continue;
            try {
                if (Sources.hasUpdate(c, app, have, Sources.latest(app))) {
                    names.add(app.name);
                }
            } catch (Exception e) {
                // that source is unreachable now: not counted, next check
            }
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("count", names.size())
                .putString("names", TextUtils.join(", ", names))
                .apply();
        show(c);
    }

    public static class Check extends JobService {
        @Override
        public boolean onStartJob(final JobParameters params) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    check(Check.this);
                    jobFinished(params, false);
                }
            }, "KeurStore-widget").start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }
}
