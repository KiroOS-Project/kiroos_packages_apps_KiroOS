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

package com.kiroos.surge;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/**
 * Surge on the home (KiroOS Beta 4 widget): a search bar; a tap opens a new Surge tab with the
 * address field focused and the keyboard up.
 */
public class SurgeWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        if (ids == null || ids.length == 0) return;
        final RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_surge);
        final Intent search = new Intent(context, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_SEARCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(R.id.surge_bar, PendingIntent.getActivity(context, 0, search,
                PendingIntent.FLAG_UPDATE_CURRENT));
        manager.updateAppWidget(ids, v);
    }
}
