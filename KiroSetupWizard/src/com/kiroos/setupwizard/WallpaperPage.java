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

package com.kiroos.setupwizard;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

import java.io.InputStream;

/**
 * Wallpaper (KiroOS Developer Beta 5, the page before the last): the three silks of KiroOS,
 * side by side; the one picked becomes the wallpaper of the home and the lock screen when Next is
 * pressed. Midnight, the system's default, is picked to begin with. The pictures are Canvas's own
 * (com.kiroos.canvas res/drawable-nodpi/silk_*.jpg): nothing is copied twice in the ROM.
 */
class WallpaperPage extends SetupPage {

    private static final String TAG = "KiroSetup";
    private static final String CANVAS = "com.kiroos.canvas";
    private static final String[] NAMES = {"silk_midnight", "silk_graphite", "silk_dawn"};
    private static final String[] LABELS = {"Midnight", "Graphite", "Dawn"};

    private final ImageView[] mPictures = new ImageView[NAMES.length];
    private final FrameLayout[] mFrames = new FrameLayout[NAMES.length];
    private int mChosen = 0;
    private int mApplied = 0;   // the default is Midnight already

    WallpaperPage(SetupActivity activity) {
        super(activity);
    }

    /** only when Canvas, which holds the pictures, is installed */
    static boolean available(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(CANVAS, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    int iconRes() {
        return R.drawable.ic_palette;
    }

    @Override
    int titleRes() {
        return R.string.wallpaper_title;
    }

    @Override
    int summaryRes() {
        return R.string.wallpaper_summary;
    }

    private Resources canvasRes() throws Exception {
        return mActivity.getPackageManager().getResourcesForApplication(CANVAS);
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        final float dp = mActivity.getResources().getDisplayMetrics().density;
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < NAMES.length; i++) {
            final int index = i;
            final LinearLayout col = new LinearLayout(mActivity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final FrameLayout frame = new FrameLayout(mActivity);
            frame.setPadding(Math.round(3 * dp), Math.round(3 * dp), Math.round(3 * dp),
                    Math.round(3 * dp));
            final ImageView pic = new ImageView(mActivity);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final GradientDrawable clip = new GradientDrawable();
            clip.setColor(0xFF101012);
            clip.setCornerRadius(14 * dp);
            pic.setBackground(clip);
            pic.setClipToOutline(true);
            frame.addView(pic, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            col.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(230 * dp)));
            final TextView name = new TextView(mActivity);
            name.setText(LABELS[i]);
            name.setTextAppearance(R.style.Kiro_Body);
            name.setGravity(Gravity.CENTER);
            final LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = Math.round(10 * dp);
            col.addView(name, nlp);
            col.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mChosen = index;
                    refresh();
                }
            });
            KiroTouch.apply(col);
            mPictures[i] = pic;
            mFrames[i] = frame;
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(i == 0 ? 0 : Math.round(5 * dp));
            lp.setMarginEnd(i == NAMES.length - 1 ? 0 : Math.round(5 * dp));
            row.addView(col, lp);
            loadThumb(i);
        }
        content.addView(row);
        refresh();
    }

    /** a small copy of the picture for its tile (off the main thread) */
    private void loadThumb(final int i) {
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... v) {
                try {
                    final Resources r = canvasRes();
                    final int id = r.getIdentifier(NAMES[i], "drawable", CANVAS);
                    final BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inSampleSize = 4;   // 360 x 640: sharp on a ~110 dp wide tile
                    o.inScaled = false;
                    return BitmapFactory.decodeResource(r, id, o);
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                if (b != null) mPictures[i].setImageBitmap(b);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void refresh() {
        final float dp = mActivity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mFrames.length; i++) {
            if (mFrames[i] == null) continue;
            final GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(17 * dp);
            ring.setColor(0x00000000);
            if (i == mChosen) {
                ring.setStroke(Math.round(2.5f * dp), mActivity.getColor(R.color.kiro_accent));
            }
            mFrames[i].setBackground(ring);
        }
    }

    /** Next: the silk picked becomes the wallpaper (home and lock screen), in the background */
    @Override
    void onLeave() {
        if (mChosen == mApplied) return;
        final int chosen = mChosen;
        mApplied = chosen;
        final Context app = mActivity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Resources r = app.getPackageManager().getResourcesForApplication(CANVAS);
                    final int id = r.getIdentifier(NAMES[chosen], "drawable", CANVAS);
                    try (InputStream in = r.openRawResource(id)) {
                        WallpaperManager.getInstance(app).setStream(in, null, true,
                                WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                    }
                    Log.i(TAG, "wallpaper: " + NAMES[chosen]);
                } catch (Exception e) {
                    Log.w(TAG, "wallpaper not set", e);
                }
            }
        }, "setup-wallpaper").start();
    }
}
