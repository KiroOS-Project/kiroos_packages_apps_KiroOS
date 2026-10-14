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
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * App widgets on the home, stacked under the clock at their natural height, with rounded
 * corners (22 dp, like the KiroOS widget redesign) and a long-press menu to remove them.
 */
final class WidgetStack {

    private static final int HOST_ID = 0x4B49; // "KI"
    private static final String KEY_WIDGETS = "widgets";
    private static final int MAX_WIDGETS = 3;

    private final HomeActivity mActivity;
    private final LinearLayout mStack;
    private final SharedPreferences mPrefs;
    private final AppWidgetManager mManager;
    private final AppWidgetHost mHost;

    WidgetStack(HomeActivity activity, LinearLayout stack, SharedPreferences prefs) {
        mActivity = activity;
        mStack = stack;
        mPrefs = prefs;
        mManager = AppWidgetManager.getInstance(activity);
        mHost = new AppWidgetHost(activity, HOST_ID);
        for (int id : ids()) {
            addView(id);
        }
        // Home 2.0: when a widget comes or goes, the others glide to their new place (the
        // widget itself animates on its own, see add / remove)
        final android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.setAnimator(android.animation.LayoutTransition.APPEARING, null);
        lt.setAnimator(android.animation.LayoutTransition.DISAPPEARING, null);
        lt.setDuration(280);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING, EASE);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING, EASE);
        mStack.setLayoutTransition(lt);
    }

    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

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
        mHost.deleteAppWidgetId(id);
    }

    void add(int id) {
        final List<Integer> ids = ids();
        if (ids.size() >= MAX_WIDGETS) {
            // keep the home tidy: the oldest widget makes room
            remove(ids.get(0));
            ids.remove(0);
        }
        ids.add(id);
        save(ids);
        final View v = addView(id);
        if (v != null) {
            // a new widget grows in from a little smaller, with a small bounce
            v.setAlpha(0f);
            v.setScaleX(0.86f);
            v.setScaleY(0.86f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(380)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                    .start();
        }
    }

    void remove(int id) {
        final List<Integer> ids = ids();
        ids.remove(Integer.valueOf(id));
        save(ids);
        for (int i = 0; i < mStack.getChildCount(); i++) {
            final View v = mStack.getChildAt(i);
            if (v instanceof AppWidgetHostView && ((AppWidgetHostView) v).getAppWidgetId() == id) {
                // it shrinks and fades, then leaves its place (the others glide up)
                v.setEnabled(false);
                v.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f).setDuration(200)
                        .setInterpolator(EASE).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                mStack.removeView(v);
                            }
                        }).start();
                break;
            }
        }
        mHost.deleteAppWidgetId(id);
    }

    private View addView(final int id) {
        final AppWidgetProviderInfo info = mManager.getAppWidgetInfo(id);
        if (info == null) {
            return null;
        }
        final AppWidgetHostView view = mHost.createView(mActivity.getApplicationContext(), id, info);
        view.setAppWidget(id, info);
        final float density = mActivity.getResources().getDisplayMetrics().density;
        final int minHeight = Math.max(info.minHeight, Math.round(72 * density));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, minHeight);
        lp.bottomMargin = Math.round(12 * density);
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 22 * density);
            }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                mActivity.onWidgetLongPress(view, id);
                return true;
            }
        });
        view.setLongClickable(true);
        mStack.addView(view, lp);
        return view;
    }

    private List<Integer> ids() {
        final List<Integer> ids = new ArrayList<>();
        final String s = mPrefs.getString(KEY_WIDGETS, "");
        if (!TextUtils.isEmpty(s)) {
            for (String part : s.split(",")) {
                try {
                    ids.add(Integer.parseInt(part));
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
        return ids;
    }

    private void save(List<Integer> ids) {
        mPrefs.edit().putString(KEY_WIDGETS, TextUtils.join(",", ids)).apply();
    }
}
