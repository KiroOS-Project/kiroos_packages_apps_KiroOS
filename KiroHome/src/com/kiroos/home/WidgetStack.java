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
        if (id == HomeModel.CLOCK_ID) return; // the home's own clock, no app widget
        mViews.remove(id);
        mSizes.remove(id);
        mHost.deleteAppWidgetId(id);
    }

    boolean exists(int id) {
        return id == HomeModel.CLOCK_ID || mManager.getAppWidgetInfo(id) != null;
    }

    // --- Beta 4.1: sizes ---------------------------------------------------------------------

    /** the size each widget's app was last told, "w x h" in dp, so it is told once per change */
    private final SparseArray<String> mSizes = new SparseArray<>();

    /** room around a widget inside its cells (Workspace.widgetInset, both sides) */
    private int inset() {
        return Math.round(12 * mActivity.getResources().getDisplayMetrics().density);
    }

    /**
     * The sizes a widget can take, smallest first, each {rows, cols, name: 0 small, 1 medium,
     * 2 large}: small 2 x 2, medium 4 x 2 (full width), large 4 x 4, as far as the widget's
     * app allows (its minimum size, and whether it can be resized). A widget that cannot be
     * resized keeps only its own size.
     */
    java.util.List<int[]> sizes(int id, int cellWidth, int cellHeight) {
        final java.util.List<int[]> out = new ArrayList<>();
        if (id == HomeModel.CLOCK_ID) {
            out.add(new int[] {2, 2, 0});
            out.add(new int[] {2, HomeModel.COLUMNS, 1});
            out.add(new int[] {4, HomeModel.COLUMNS, 2});
            return out;
        }
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null || cellWidth <= 0 || cellHeight <= 0) return out;
        final int[] own = defaultSize(id, cellWidth, cellHeight);
        final boolean h = (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
        final boolean v = (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
        final int minW = h && info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
        final int minH = v && info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
        final int[][] wanted = {{2, 2}, {2, HomeModel.COLUMNS}, {4, HomeModel.COLUMNS}};
        for (int i = 0; i < wanted.length; i++) {
            final int rows = wanted[i][0];
            final int cols = wanted[i][1];
            final boolean rowsOk = rows == own[0] || (v && minH + inset() <= rows * cellHeight);
            final boolean colsOk = cols == own[1] || (h && minW + inset() <= cols * cellWidth);
            if (rowsOk && colsOk) out.add(new int[] {rows, cols, i});
        }
        // its own size is always one of them (a 4 x 1 bar stays a bar)
        boolean hasOwn = false;
        for (int[] s : out) hasOwn |= s[0] == own[0] && s[1] == own[1];
        if (!hasOwn) {
            final int area = own[0] * own[1];
            final int name = area <= 4 ? 0 : area <= 8 ? 1 : 2;
            out.add(new int[] {own[0], own[1], name});
            java.util.Collections.sort(out, new java.util.Comparator<int[]>() {
                @Override
                public int compare(int[] a, int[] b) {
                    return a[0] * a[1] - b[0] * b[1];
                }
            });
        }
        return out;
    }

    /** the size a new widget gets: {rows, cols}, from its app's own minimum size */
    int[] defaultSize(int id, int cellWidth, int cellHeight) {
        if (id == HomeModel.CLOCK_ID) return new int[] {2, HomeModel.COLUMNS};
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        final int rows = rowsFor(id, cellHeight);
        if (info == null || cellWidth <= 0) return new int[] {rows, HomeModel.COLUMNS};
        // narrow widgets (up to two cells) are half the width, the others full width
        final int cols = info.minWidth + inset() <= 2 * cellWidth ? 2 : HomeModel.COLUMNS;
        return new int[] {rows, cols};
    }

    /** tells a widget's app the size it has on the home, in dp (once per change) */
    void tellSize(int id, int widthPx, int heightPx) {
        if (id == HomeModel.CLOCK_ID) return;
        final AppWidgetHostView v = mViews.get(id);
        if (v == null) return;
        final float density = mActivity.getResources().getDisplayMetrics().density;
        final int w = Math.round((widthPx - inset()) / density);
        final int h = Math.round((heightPx - inset()) / density);
        final String s = w + "x" + h;
        if (s.equals(mSizes.get(id))) return;
        mSizes.put(id, s);
        try {
            v.updateAppWidgetSize(null, w, h, w, h);
        } catch (RuntimeException e) {
            // the widget service is not up yet
        }
    }

    /** a widget of the given app bound for a new phone's home (no prompt), or -1 */
    int seed(android.content.ComponentName provider) {
        int id = -1;
        try {
            id = allocateId();
            if (mManager.bindAppWidgetIdIfAllowed(id, provider)) {
                return id;
            }
        } catch (RuntimeException e) {
            // no such app on this build, or the widget service not up: left out
        }
        if (id != -1) deleteId(id);
        return -1;
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
    View view(final int id) {
        if (id == HomeModel.CLOCK_ID) {
            // the home's own clock (Beta 4.1): one view, moved between binds
            final View clock = mActivity.clockView();
            if (clock.getParent() != null) {
                ((ViewGroup) clock.getParent()).removeView(clock);
            }
            return clock;
        }
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
