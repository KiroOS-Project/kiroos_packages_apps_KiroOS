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

package com.kiroos.kai;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * The kAI bar on the home (KiroOS widget, under the clock on a new phone): "Ask kAI…" opens
 * kAI, the microphone opens it listening.
 */
public class KaiBarWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_kai_bar);
        final Intent open = new Intent(context, KaiActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.kai_bar, PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT));
        // the same start as holding Power: kAI opens and listens
        final Intent listen = new Intent(context, KaiActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("from_power_key", true);
        v.setOnClickPendingIntent(R.id.kai_bar_mic, PendingIntent.getActivity(context, 1, listen,
                PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(ids, v);
    }
}
