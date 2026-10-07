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

package com.kiroos.atlas;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * While navigating, Atlas is a foreground app: its notification brings the map back, and the
 * system keeps it running (and speaking) when another app is on screen.
 */
public class NavService extends Service {

    private static final String EXTRA_TO = "to";

    static void start(Context c, String to) {
        c.startService(new Intent(c, NavService.class).putExtra(EXTRA_TO, to));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, NavService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String to = intent != null ? intent.getStringExtra(EXTRA_TO) : null;
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, AtlasActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_nav)
                .setContentTitle(getString(R.string.navigating_to, to != null ? to : ""))
                .setContentText(getString(R.string.nav_tap))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(getColor(R.color.kiro_accent))
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(1, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
