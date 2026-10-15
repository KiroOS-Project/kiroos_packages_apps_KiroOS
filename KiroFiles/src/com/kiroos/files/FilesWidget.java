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

package com.kiroos.files;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;
import android.widget.RemoteViews;

/**
 * Files on the home (KiroOS Beta 4 widget): how full the storage is, and shortcuts to
 * Downloads, Images and Documents. Refreshed every 30 minutes and whenever Files closes.
 */
public class FilesWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_files);
        try {
            final StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            final long total = s.getTotalBytes();
            final long free = s.getAvailableBytes();
            v.setTextViewText(R.id.files_free, context.getString(R.string.widget_files_free,
                    Formatter.formatShortFileSize(context, free),
                    Formatter.formatShortFileSize(context, total)));
            v.setProgressBar(R.id.files_bar, 1000,
                    total > 0 ? (int) ((total - free) * 1000 / total) : 0, false);
        } catch (IllegalArgumentException e) {
            // storage not mounted yet
        }
        v.setOnClickPendingIntent(R.id.files_root, open(context, 0, null));
        v.setOnClickPendingIntent(R.id.files_downloads, open(context, 1, "downloads"));
        v.setOnClickPendingIntent(R.id.files_images, open(context, 2, "images"));
        v.setOnClickPendingIntent(R.id.files_documents, open(context, 3, "documents"));
        manager.updateAppWidget(ids, v);
    }

    private static PendingIntent open(Context c, int code, String what) {
        final Intent i = new Intent(c, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (what != null) i.putExtra(FilesActivity.EXTRA_OPEN, what);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** after files were added or deleted in the app */
    static void refresh(Context context) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new android.content.ComponentName(context,
                FilesWidget.class));
        new FilesWidget().onUpdate(context, m, ids);
    }
}
