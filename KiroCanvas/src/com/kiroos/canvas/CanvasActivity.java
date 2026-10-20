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

package com.kiroos.canvas;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

/**
 * Canvas: the KiroOS wallpapers, a picture from the gallery, and the lock screen blur (a
 * blurred copy of the home wallpaper on the lock screen only, previewed live while the slider
 * moves).
 */
public class CanvasActivity extends Activity {

    private static final String TAG = "KiroCanvas";
    private static final int REQUEST_PICK = 1;
    private static final int[] NAMES = {
        R.string.w_silk_midnight, R.string.w_silk_graphite, R.string.w_silk_dawn,
        R.string.w_aurora, R.string.w_nebula, R.string.w_waves, R.string.w_mesh,
        R.string.w_orbit, R.string.w_dunes, R.string.w_glass, R.string.w_midnight,
    };

    private final Handler mHandler = new Handler();
    private WallpaperManager mWallpaper;
    private SharedPreferences mPrefs;
    private Point mScreen;
    private ImageView mLockPreview;
    private SeekBar mBlur;
    private Bitmap mHomeSmall;
    private int mPreviewSeq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWallpaper = WallpaperManager.getInstance(this);
        Recipes.init(this);
        mPrefs = getSharedPreferences("canvas", MODE_PRIVATE);
        mScreen = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(mScreen);
        setContentView(R.layout.activity_canvas);

        final LinearLayout grid = (LinearLayout) findViewById(R.id.grid);
        LinearLayout row = null;
        for (int i = 0; i <= Recipes.COUNT; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(i < Recipes.COUNT ? tile(i) : galleryTile(),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        // the last row keeps three slots wide
        while (row.getChildCount() < 3) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }

        findViewById(R.id.lock_frame).setClipToOutline(true);
        mLockPreview = (ImageView) findViewById(R.id.lock_preview);
        mBlur = (SeekBar) findViewById(R.id.blur);
        mBlur.setProgress(mPrefs.getInt("blur", 60));
        mBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                mHandler.removeCallbacks(mPreview);
                mHandler.postDelayed(mPreview, 40);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mPrefs.edit().putInt("blur", bar.getProgress()).apply();
            }
        });
        findViewById(R.id.apply_lock).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyLockBlur();
            }
        });
        findViewById(R.id.reset_lock).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetLock();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHomeWallpaper();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // --- the wallpaper tiles --------------------------------------------------------------------

    private View tileFrame(View picture, int label) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(5), dp(5), dp(5), dp(10));
        picture.setBackgroundResource(R.drawable.tile_bg);
        picture.setForeground(getDrawable(R.drawable.tile_border));
        picture.setClipToOutline(true);
        box.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(176)));
        final TextView name = new TextView(this);
        name.setText(label);
        name.setTextAppearance(com.kiroos.ui.R.style.Kiro_Caption);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(6), 0, 0);
        box.addView(name);
        return box;
    }

    private View tile(final int recipe) {
        final ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... v) {
                return Recipes.draw(recipe, 216, 384);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                picture.setImageBitmap(b);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        final View box = tileFrame(picture, NAMES[recipe]);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askWhere(new Source() {
                    @Override
                    public Bitmap make() {
                        return Recipes.draw(recipe, mScreen.x, mScreen.y);
                    }
                });
            }
        });
        return box;
    }

    private View galleryTile() {
        final ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER);
        picture.setImageResource(R.drawable.ic_gallery);
        final View box = tileFrame(picture, R.string.from_gallery);
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                try {
                    startActivityForResult(pick, REQUEST_PICK);
                } catch (android.content.ActivityNotFoundException e) {
                    toast(R.string.no_gallery);
                }
            }
        });
        return box;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        askWhere(new Source() {
            @Override
            public Bitmap make() {
                return loadScaled(uri);
            }
        });
    }

    /** a picture from the gallery, decoded near the screen's size and cropped to fill it */
    private Bitmap loadScaled(Uri uri) {
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= mScreen.x && o.outHeight / (sample * 2) >= mScreen.y) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            final Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            return b == null ? null : fill(b, mScreen.x, mScreen.y);
        } catch (Exception e) {
            Log.w(TAG, "picture", e);
            return null;
        }
    }

    /** scales and centres a picture so it covers w x h exactly */
    private static Bitmap fill(Bitmap src, int w, int h) {
        final float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
        new Canvas(out).drawBitmap(src, m, new android.graphics.Paint(
                android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private interface Source {
        Bitmap make();
    }

    /** home screen, lock screen or both */
    private void askWhere(final Source source) {
        final CharSequence[] where = {
            getText(R.string.set_home), getText(R.string.set_lock), getText(R.string.set_both),
        };
        new AlertDialog.Builder(this, com.kiroos.ui.R.style.Theme_Kiro_Dialog)
                .setTitle(R.string.set_on)
                .setItems(where, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final int flags = which == 0 ? WallpaperManager.FLAG_SYSTEM
                                : which == 1 ? WallpaperManager.FLAG_LOCK
                                : WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
                        setWallpaper(source, flags);
                    }
                })
                .show();
    }

    private void setWallpaper(final Source source, final int flags) {
        toast(R.string.working);
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    final Bitmap b = source.make();
                    if (b == null) {
                        return false;
                    }
                    mWallpaper.setBitmap(b, null, true, flags);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "set wallpaper", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                toast(ok ? R.string.done : R.string.failed);
                loadHomeWallpaper();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // --- the lock screen blur ---------------------------------------------------------------

    /** the home wallpaper, small, for the live preview */
    private void loadHomeWallpaper() {
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... v) {
                final Bitmap home = homeWallpaper();
                return home == null ? null : fill(home, 360, 640);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                mHomeSmall = b;
                mHandler.post(mPreview);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Bitmap homeWallpaper() {
        try {
            final Drawable d = mWallpaper.getDrawable();
            if (d instanceof BitmapDrawable) {
                return ((BitmapDrawable) d).getBitmap();
            }
            if (d != null && d.getIntrinsicWidth() > 0) {
                final Bitmap b = Bitmap.createBitmap(d.getIntrinsicWidth(),
                        d.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, b.getWidth(), b.getHeight());
                d.draw(new Canvas(b));
                return b;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "home wallpaper", e);
        }
        return null;
    }

    private final Runnable mPreview = new Runnable() {
        @Override
        public void run() {
            final Bitmap home = mHomeSmall;
            if (home == null) {
                return;
            }
            final int seq = ++mPreviewSeq;
            final int amount = mBlur.getProgress();
            new AsyncTask<Void, Void, Bitmap>() {
                @Override
                protected Bitmap doInBackground(Void... v) {
                    return Blur.apply(CanvasActivity.this, home, amount);
                }

                @Override
                protected void onPostExecute(Bitmap b) {
                    if (seq == mPreviewSeq) {
                        mLockPreview.setImageBitmap(b);
                    }
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    };

    private void applyLockBlur() {
        final int amount = mBlur.getProgress();
        mPrefs.edit().putInt("blur", amount).apply();
        toast(R.string.working);
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                try {
                    final Bitmap home = homeWallpaper();
                    if (home == null) {
                        return false;
                    }
                    final Bitmap full = fill(home, mScreen.x, mScreen.y);
                    mWallpaper.setBitmap(Blur.apply(CanvasActivity.this, full, amount), null,
                            true, WallpaperManager.FLAG_LOCK);
                    return true;
                } catch (Exception e) {
                    Log.w(TAG, "lock blur", e);
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                toast(ok ? R.string.lock_done : R.string.failed);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** the lock screen shows the home wallpaper again */
    private void resetLock() {
        try {
            mWallpaper.clear(WallpaperManager.FLAG_LOCK);
            toast(R.string.lock_done);
        } catch (Exception e) {
            Log.w(TAG, "reset lock", e);
            toast(R.string.failed);
        }
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
