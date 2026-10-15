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

package com.kiroos.recorder;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Recorder on the home (KiroOS Beta 4 widget): one tap on the red button records, with no
 * app in between; while it records the time runs on the widget, with pause and stop.
 * RecorderService pushes every state change (show()).
 */
public class RecorderWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        manager.updateAppWidget(ids, views(context));
    }

    static void show(Context context) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new ComponentName(context, RecorderWidget.class));
        if (ids == null || ids.length == 0) return;
        m.updateAppWidget(ids, views(context));
    }

    private static RemoteViews views(Context c) {
        final RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_recorder);
        final boolean recording = RecorderService.isRecording();
        final boolean paused = RecorderService.isPaused();
        final PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, RecorderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.rec_root, open);
        if (recording) {
            v.setTextViewText(R.id.rec_label, c.getString(paused ? R.string.paused : R.string.recording));
            v.setChronometer(R.id.rec_time, SystemClock.elapsedRealtime() - RecorderService.elapsed(),
                    null, !paused);
            v.setImageViewResource(R.id.rec_main, R.drawable.ic_stop);
            v.setContentDescription(R.id.rec_main, c.getString(R.string.stop));
            v.setOnClickPendingIntent(R.id.rec_main, service(c, RecorderService.ACTION_STOP));
            v.setViewVisibility(R.id.rec_pause, View.VISIBLE);
            v.setImageViewResource(R.id.rec_pause, paused ? R.drawable.ic_play : R.drawable.ic_pause);
            v.setContentDescription(R.id.rec_pause, c.getString(paused ? R.string.resume : R.string.pause));
            v.setOnClickPendingIntent(R.id.rec_pause, service(c, paused
                    ? RecorderService.ACTION_RESUME : RecorderService.ACTION_PAUSE));
        } else {
            v.setTextViewText(R.id.rec_label, c.getString(R.string.app_name));
            v.setChronometer(R.id.rec_time, SystemClock.elapsedRealtime(), null, false);
            v.setImageViewResource(R.id.rec_main, R.drawable.ic_mic);
            v.setContentDescription(R.id.rec_main, c.getString(R.string.record));
            v.setViewVisibility(R.id.rec_pause, View.GONE);
            // without the microphone permission the app asks for it first
            final boolean allowed = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            v.setOnClickPendingIntent(R.id.rec_main, allowed
                    ? service(c, RecorderService.ACTION_START) : open);
        }
        return v;
    }

    private static PendingIntent service(Context c, String action) {
        return PendingIntent.getService(c, action.hashCode(),
                new Intent(c, RecorderService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
