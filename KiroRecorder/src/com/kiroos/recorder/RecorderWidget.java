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
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;

/**
 * Recorder on the home (KiroOS Beta 5 widget), in two sizes.
 * Small (2 x 2): one big red button that records at once, with no app in between; while it
 * records the card turns a deep red, the button stops and the time runs under it.
 * Medium (4 x 2): the same button, and next to it the last recording (its name, length and a
 * play button that opens it playing in the app); while recording: the time, pause and stop.
 * RecorderService pushes every state change (show()).
 */
public class RecorderWidget extends AppWidgetProvider {

    /** narrower than this (dp) the widget is the small one */
    private static final int SMALL_MAX_WIDTH = 200;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null) return;
        final Last last = last();
        for (int id : ids) {
            manager.updateAppWidget(id, views(context, manager, id, last));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
            Bundle options) {
        // the home changed its size: small or medium
        manager.updateAppWidget(id, views(context, manager, id, last()));
    }

    static void show(Context context) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new ComponentName(context, RecorderWidget.class));
        if (ids == null || ids.length == 0) return;
        new RecorderWidget().onUpdate(context, m, ids);
    }

    /** the most recent recording: its file, name and length */
    private static final class Last {
        File file;
        String name;
        String length;
    }

    private static Last last() {
        final File[] files = RecorderService.directory().listFiles();
        if (files == null) return null;
        File newest = null;
        for (File f : files) {
            if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                newest = f;
            }
        }
        if (newest == null) return null;
        final Last l = new Last();
        l.file = newest;
        final String n = newest.getName();
        final int dot = n.lastIndexOf('.');
        l.name = dot > 0 ? n.substring(0, dot) : n;
        final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(newest.getAbsolutePath());
            final String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            final long s = d != null ? Long.parseLong(d) / 1000 : 0;
            l.length = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                    : String.format("%02d:%02d", s / 60, s % 60);
        } catch (RuntimeException e) {
            l.length = "";
        } finally {
            mmr.release();
        }
        return l;
    }

    private static boolean small(AppWidgetManager m, int id) {
        final Bundle o = m.getAppWidgetOptions(id);
        final int w = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        return w > 0 && w < SMALL_MAX_WIDTH;
    }

    private static RemoteViews views(Context c, AppWidgetManager m, int id, Last last) {
        final boolean small = small(m, id);
        final RemoteViews v = new RemoteViews(c.getPackageName(),
                small ? R.layout.widget_recorder_small : R.layout.widget_recorder);
        final boolean recording = RecorderService.isRecording();
        final boolean paused = RecorderService.isPaused();
        final PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, RecorderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.rec_root, open);
        v.setInt(R.id.rec_root, "setBackgroundResource", recording
                ? R.drawable.widget_recorder_bg_rec : R.drawable.widget_recorder_bg);
        final long base = SystemClock.elapsedRealtime() - RecorderService.elapsed();
        if (recording) {
            v.setImageViewResource(R.id.rec_main, R.drawable.widget_rec_square);
            v.setContentDescription(R.id.rec_main, c.getString(R.string.stop));
            v.setOnClickPendingIntent(R.id.rec_main, service(c, RecorderService.ACTION_STOP));
            v.setChronometer(R.id.rec_time, base, null, !paused);
            if (small) {
                v.setViewVisibility(R.id.rec_label, View.GONE);
                v.setViewVisibility(R.id.rec_time, View.VISIBLE);
            } else {
                v.setViewVisibility(R.id.rec_last, View.GONE);
                v.setViewVisibility(R.id.rec_live, View.VISIBLE);
                v.setTextViewText(R.id.rec_label, c.getString(paused ? R.string.paused
                        : R.string.recording));
                v.setImageViewResource(R.id.rec_pause, paused ? R.drawable.ic_play
                        : R.drawable.ic_pause);
                v.setContentDescription(R.id.rec_pause, c.getString(paused ? R.string.resume
                        : R.string.pause));
                v.setOnClickPendingIntent(R.id.rec_pause, service(c, paused
                        ? RecorderService.ACTION_RESUME : RecorderService.ACTION_PAUSE));
            }
        } else {
            v.setImageViewResource(R.id.rec_main, R.drawable.widget_rec_dot);
            v.setContentDescription(R.id.rec_main, c.getString(R.string.record));
            v.setChronometer(R.id.rec_time, SystemClock.elapsedRealtime(), null, false);
            // without the microphone permission the app asks for it first
            final boolean allowed = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            v.setOnClickPendingIntent(R.id.rec_main, allowed
                    ? service(c, RecorderService.ACTION_START) : open);
            if (small) {
                v.setViewVisibility(R.id.rec_label, View.VISIBLE);
                v.setViewVisibility(R.id.rec_time, View.GONE);
            } else {
                v.setViewVisibility(R.id.rec_live, View.GONE);
                v.setViewVisibility(R.id.rec_last, View.VISIBLE);
                if (last != null) {
                    v.setTextViewText(R.id.rec_name, last.name);
                    v.setTextViewText(R.id.rec_duration, last.length);
                    v.setViewVisibility(R.id.rec_play_row, View.VISIBLE);
                    final PendingIntent play = PendingIntent.getActivity(c, 1,
                            new Intent(c, RecorderActivity.class)
                                    .putExtra(RecorderActivity.EXTRA_PLAY, last.file.getAbsolutePath())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            PendingIntent.FLAG_UPDATE_CURRENT);
                    v.setOnClickPendingIntent(R.id.rec_play, play);
                } else {
                    v.setTextViewText(R.id.rec_name, c.getString(R.string.no_recordings));
                    v.setTextViewText(R.id.rec_duration, "");
                    v.setViewVisibility(R.id.rec_play_row, View.GONE);
                }
            }
        }
        return v;
    }

    private static PendingIntent service(Context c, String action) {
        return PendingIntent.getService(c, action.hashCode(),
                new Intent(c, RecorderService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
