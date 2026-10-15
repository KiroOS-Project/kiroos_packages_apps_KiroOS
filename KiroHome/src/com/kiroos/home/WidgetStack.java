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

package com.kiroos.home;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The home's app widgets: their host and their views, with rounded corners (22 dp, like the
 * KiroOS widget redesign). Beta 4: where a widget sits is the home grid's business (HomeModel,
 * any page, any rows); this keeps one view per widget so a rebind of the grid reuses it.
 * Before Beta 4 widgets were stacked under the clock: that list ("widgets") is handed over
 * once to the grid (legacyIds).
 */
final class WidgetStack {

    private static final int HOST_ID = 0x4B49; // "KI"
    private static final String KEY_LEGACY = "widgets";

    private final HomeActivity mActivity;
    private final SharedPreferences mPrefs;
    private final AppWidgetManager mManager;
    private final AppWidgetHost mHost;
    private final SparseArray<AppWidgetHostView> mViews = new SparseArray<>();

    WidgetStack(HomeActivity activity, SharedPreferences prefs) {
        mActivity = activity;
        mPrefs = prefs;
        mManager = AppWidgetManager.getInstance(activity);
        // widget views that let a long press through (move / remove)
        mHost = new KiroWidgetView.Host(activity, HOST_ID);
    }

    AppWidgetHost host() {
        return mHost;
    }

    void startListening() {
        try {
            mHost.startListening();
        } catch (RuntimeException e) {
            // the widget service is not up yet during early boot; widgets refresh later
        }
    }

    void stopListening() {
        try {
            mHost.stopListening();
        } catch (RuntimeException e) {
            // ignore
        }
    }

    int allocateId() {
        return mHost.allocateAppWidgetId();
    }

    void deleteId(int id) {
        mViews.remove(id);
        mHost.deleteAppWidgetId(id);
    }

    boolean exists(int id) {
        return mManager.getAppWidgetInfo(id) != null;
    }

    /** how many grid rows a widget takes (its minimum height, plus the room around it) */
    int rowsFor(int id, int cellHeight) {
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null || cellHeight <= 0) return 1;
        final int inset = Math.round(12 * mActivity.getResources().getDisplayMetrics().density);
        final int rows = (info.minHeight + inset + cellHeight - 1) / cellHeight;
        return Math.max(1, Math.min(HomeModel.MAX_ROWS - 1, rows));
    }

    /** the view of a widget (made once), or null if the widget is gone */
    AppWidgetHostView view(final int id) {
        AppWidgetHostView view = mViews.get(id);
        if (view != null) {
            if (view.getParent() != null) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            return view;
        }
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null) {
            return null;
        }
        view = mHost.createView(mActivity.getApplicationContext(), id, info);
        view.setAppWidget(id, info);
        final float density = mActivity.getResources().getDisplayMetrics().density;
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 22 * density);
            }
        });
        final AppWidgetHostView v = view;
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View ignored) {
                mActivity.onWidgetLongPress(v, id);
                return true;
            }
        });
        view.setLongClickable(true);
        mViews.put(id, view);
        return view;
    }

    /** the kAI bar for a new phone's home (Kiro Home binds it, no prompt), or -1 */
    int seedKaiBar() {
        final android.content.ComponentName bar = new android.content.ComponentName(
                "com.kiroos.kai", "com.kiroos.kai.KaiBarWidget");
        int id = -1;
        try {
            id = allocateId();
            if (mManager.bindAppWidgetIdIfAllowed(id, bar)) {
                return id;
            }
        } catch (RuntimeException e) {
            // no kAI on this build, or the widget service not up: a plain home
        }
        if (id != -1) deleteId(id);
        return -1;
    }

    /** the widgets stacked under the clock before Beta 4, top first (read once) */
    List<Integer> takeLegacyIds() {
        final List<Integer> ids = new ArrayList<>();
        final String s = mPrefs.getString(KEY_LEGACY, "");
        if (!TextUtils.isEmpty(s)) {
            for (String part : s.split(",")) {
                try {
                    ids.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    // skip
                }
            }
            mPrefs.edit().remove(KEY_LEGACY).apply();
        }
        return ids;
    }
}
