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

package com.kiroos.gallery;

import android.Manifest;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Gallery on the home (KiroOS Beta 4 widget): the four latest photos; a tap on one opens it,
 * a tap elsewhere opens the Gallery. A job watching the media store refreshes the widget as
 * soon as a photo is taken or deleted (and again on each update request).
 */
public class GalleryWidget extends AppWidgetProvider {

    private static final int[] PHOTO = {
        R.id.gallery_photo1, R.id.gallery_photo2, R.id.gallery_photo3, R.id.gallery_photo4,
        R.id.gallery_photo5, R.id.gallery_photo6, R.id.gallery_photo7, R.id.gallery_photo8,
    };

    /** Beta 4.1: the home changed the widget's size (small 1 photo, medium 4, large 8) */
    @Override
    public void onAppWidgetOptionsChanged(final Context context, AppWidgetManager manager,
            int id, android.os.Bundle options) {
        onUpdate(context, manager, new int[] {id});
    }

    /** how many photos a widget of this size shows */
    private static int photosFor(AppWidgetManager m, int id) {
        final android.os.Bundle o = m.getAppWidgetOptions(id);
        final int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        final int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        return w < 200 ? 1 : h >= 280 ? 8 : 4;
    }
    private static final int JOB_ID = 4242;
    private static final int THUMB = 220;   // px: small enough for the widget's binder call

    @Override
    public void onUpdate(final Context context, final AppWidgetManager manager, final int[] ids) {
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    refresh(context);
                } finally {
                    pending.finish();
                }
            }
        }, "gallery-widget").start();
    }

    @Override
    public void onDisabled(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    static void refresh(Context context) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new ComponentName(context, GalleryWidget.class));
        if (ids == null || ids.length == 0) return;
        final boolean allowed = context.checkSelfPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        // the photos once (up to 8), then each widget shows as many as its size takes
        final Bitmap[] thumbs = new Bitmap[PHOTO.length];
        final Intent[] opens = new Intent[PHOTO.length];
        int found = 0;
        if (allowed) {
            try (Cursor c = context.getContentResolver().query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[] { MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE },
                    null, null,
                    MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media._ID
                            + " DESC LIMIT " + (PHOTO.length + 4))) {
                while (c != null && c.moveToNext() && found < PHOTO.length) {
                    final long id = c.getLong(0);
                    final Bitmap thumb = thumbnail(context, id);
                    if (thumb == null) continue;
                    final Uri uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    thumbs[found] = thumb;
                    opens[found] = new Intent(Intent.ACTION_VIEW)
                            .setClass(context, ViewerActivity.class)
                            .setDataAndType(uri, c.getString(1) != null ? c.getString(1) : "image/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    found++;
                }
            } catch (RuntimeException e) {
                // media store busy (scanning): the next change refreshes it
            }
        }
        for (int wid : ids) {
            m.updateAppWidget(wid, views(context, photosFor(m, wid), allowed, thumbs, opens, found));
        }
        watch(context);
    }

    private static RemoteViews views(Context context, int slots, boolean allowed,
            Bitmap[] thumbs, Intent[] opens, int found) {
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_gallery);
        final int shown = Math.min(slots, found);
        for (int k = 0; k < PHOTO.length; k++) {
            if (k < shown) {
                v.setImageViewBitmap(PHOTO[k], thumbs[k]);
                v.setViewVisibility(PHOTO[k], View.VISIBLE);
                v.setOnClickPendingIntent(PHOTO[k], PendingIntent.getActivity(context, k + 1,
                        opens[k], PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                // keep the grid even: empty slots of the size shown stay as placeholders
                v.setImageViewResource(PHOTO[k], 0);
                v.setViewVisibility(PHOTO[k], k < slots && shown > 0 ? View.VISIBLE : View.GONE);
            }
        }
        v.setViewVisibility(R.id.gallery_row2, slots > 4 && shown > 0 ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.gallery_grid, shown == 0 ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.gallery_empty, shown == 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.gallery_empty, context.getString(allowed
                ? R.string.widget_gallery_empty : R.string.widget_gallery_permission));
        v.setOnClickPendingIntent(R.id.gallery_root, PendingIntent.getActivity(context, 0,
                new Intent(context, GalleryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    private static Bitmap thumbnail(Context context, long id) {
        final Bitmap mini = MediaStore.Images.Thumbnails.getThumbnail(
                context.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
        if (mini == null) return null;
        // centre square, scaled down
        final int s = Math.min(mini.getWidth(), mini.getHeight());
        final Bitmap square = Bitmap.createBitmap(mini, (mini.getWidth() - s) / 2,
                (mini.getHeight() - s) / 2, s, s);
        final Bitmap out = Bitmap.createScaledBitmap(square, THUMB, THUMB, true);
        if (square != mini) square.recycle();
        if (out != mini) mini.recycle();
        return out;
    }

    /** a one-shot job that fires when the photos change; it schedules itself again */
    private static void watch(Context context) {
        final JobScheduler js = context.getSystemService(JobScheduler.class);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, Watcher.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(1500)
                .setTriggerContentMaxDelay(10000)
                .build());
    }

    public static class Watcher extends JobService {
        @Override
        public boolean onStartJob(final JobParameters params) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    refresh(getApplicationContext());
                    jobFinished(params, false);
                }
            }, "gallery-widget-job").start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return false;
        }
    }
}
