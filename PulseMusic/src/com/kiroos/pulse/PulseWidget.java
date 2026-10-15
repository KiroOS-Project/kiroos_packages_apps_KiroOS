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

package com.kiroos.pulse;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/**
 * Pulse on the home (KiroOS Beta 4 widget): the song playing (album art, title, artist) with
 * previous / play-pause / next. MusicService pushes every change (show()); the last song is
 * kept, so after a reboot the widget still shows what played last and play opens Pulse.
 */
public class PulseWidget extends AppWidgetProvider {

    private static final String PREFS = "widget";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        manager.updateAppWidget(ids, views(context, p.getString("title", null),
                p.getString("artist", null), null, false, false));
    }

    /** called by MusicService whenever the song or the playing state changes */
    static void show(Context context, Library.Track t, boolean playing, boolean serviceAlive) {
        final AppWidgetManager m = AppWidgetManager.getInstance(context);
        final int[] ids = m.getAppWidgetIds(new ComponentName(context, PulseWidget.class));
        if (ids == null || ids.length == 0) return;
        Bitmap art = null;
        String title = null, artist = null;
        if (t != null) {
            title = t.title;
            artist = t.artist;
            art = Library.art(context, t.albumId, 240);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("title", title).putString("artist", artist).apply();
        }
        m.updateAppWidget(ids, views(context, title, artist, art, playing, serviceAlive));
    }

    private static RemoteViews views(Context c, String title, String artist, Bitmap art,
            boolean playing, boolean serviceAlive) {
        final RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_pulse);
        v.setTextViewText(R.id.pulse_title, title != null ? title : c.getString(R.string.app_name));
        v.setTextViewText(R.id.pulse_artist, artist != null ? artist
                : c.getString(R.string.widget_pulse_idle));
        if (art != null) {
            v.setImageViewBitmap(R.id.pulse_art, art);
        } else {
            v.setImageViewResource(R.id.pulse_art, R.drawable.ic_music);
        }
        v.setImageViewResource(R.id.pulse_toggle, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setContentDescription(R.id.pulse_toggle, c.getString(playing ? R.string.pause : R.string.play));
        final PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, serviceAlive ? NowPlayingActivity.class : PulseActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.pulse_root, open);
        v.setOnClickPendingIntent(R.id.pulse_art, open);
        if (serviceAlive) {
            v.setOnClickPendingIntent(R.id.pulse_toggle, service(c, MusicService.ACTION_TOGGLE));
            v.setOnClickPendingIntent(R.id.pulse_next, service(c, MusicService.ACTION_NEXT));
            v.setOnClickPendingIntent(R.id.pulse_prev, service(c, MusicService.ACTION_PREV));
        } else {
            // nothing queued: the buttons open Pulse to pick something
            v.setOnClickPendingIntent(R.id.pulse_toggle, open);
            v.setOnClickPendingIntent(R.id.pulse_next, open);
            v.setOnClickPendingIntent(R.id.pulse_prev, open);
        }
        return v;
    }

    private static PendingIntent service(Context c, String action) {
        return PendingIntent.getService(c, action.hashCode(),
                new Intent(c, MusicService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
