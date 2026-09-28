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

package com.kiroos.store;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.kiroos.ui.SquircleDrawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App pictures: the installed app's own icon, else the picture from its official source
 * (F-Droid, the developer's site), downloaded once and kept in the cache, rounded like the
 * home's icons. A coloured letter shows until it arrives.
 */
final class Icons {

    private final Context mContext;
    private final PackageManager mPm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mPool = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> mMemory = new LruCache<String, Bitmap>(12 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final File mDir;

    Icons(Context c) {
        mContext = c.getApplicationContext();
        mPm = c.getPackageManager();
        mDir = new File(c.getCacheDir(), "icons");
        mDir.mkdirs();
    }

    void load(final Catalog.App app, final ImageView into) {
        into.setTag(app.pkg);
        final PackageInfo have = app.installed(mPm);
        if (have != null) {
            into.setImageDrawable(have.applicationInfo.loadIcon(mPm));
            return;
        }
        final Bitmap mem = mMemory.get(app.pkg);
        if (mem != null) {
            into.setImageDrawable(new Rounded(mem));
            return;
        }
        into.setImageDrawable(placeholder(app));
        final String url = app.iconUrl();
        if (url == null) {
            return;
        }
        mPool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = fetch(app.pkg, url);
                if (b == null) {
                    return;
                }
                mMemory.put(app.pkg, b);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (app.pkg.equals(into.getTag())) {
                            into.setImageDrawable(new Rounded(b));
                            into.setAlpha(0f);
                            into.animate().alpha(1f).setDuration(180).start();
                        }
                    }
                });
            }
        });
    }

    private Bitmap fetch(String pkg, String url) {
        final File f = new File(mDir, pkg + ".png");
        if (!f.canRead() || f.length() == 0) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "KeurStore/2.0 (KiroOS)");
                if (c.getResponseCode() != 200) {
                    return null;
                }
                final File tmp = new File(mDir, pkg + ".part");
                try (InputStream in = c.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    final byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                tmp.renameTo(f);
            } catch (Exception e) {
                return null;
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        // decoded near the size it is shown at (72 dp at most)
        final int want = Math.round(72 * mContext.getResources().getDisplayMetrics().density);
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= want && o.outHeight / (sample * 2) >= want) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** a letter on a squircle in a colour of its own */
    static Drawable placeholder(Catalog.App app) {
        final int[] colors = {0xFF3987E5, 0xFF7B5CFF, 0xFFFF8A3D, 0xFF34C759, 0xFFFF5B50,
                0xFF2FC7B5, 0xFFD45CF0};
        final int color = colors[Math.abs(app.pkg.hashCode()) % colors.length];
        final String letter = app.name.substring(0, 1).toUpperCase(Locale.ROOT);
        return new SquircleDrawable(color) {
            private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                super.draw(canvas);
                mText.setColor(0xFFFFFFFF);
                mText.setTextAlign(Paint.Align.CENTER);
                mText.setTextSize(getBounds().height() * 0.46f);
                mText.setFakeBoldText(true);
                canvas.drawText(letter, getBounds().exactCenterX(),
                        getBounds().exactCenterY() - (mText.ascent() + mText.descent()) / 2,
                        mText);
            }
        };
    }

    /** the picture in a rounded square, like the icons on the home */
    static final class Rounded extends Drawable {
        private final Bitmap mBitmap;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final BitmapShader mShader;
        private final Matrix mMatrix = new Matrix();
        private final RectF mRect = new RectF();

        Rounded(Bitmap b) {
            mBitmap = b;
            mShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mPaint.setShader(mShader);
        }

        @Override
        public void draw(Canvas canvas) {
            mRect.set(getBounds());
            mMatrix.setRectToRect(new RectF(0, 0, mBitmap.getWidth(), mBitmap.getHeight()),
                    mRect, Matrix.ScaleToFit.FILL);
            mShader.setLocalMatrix(mMatrix);
            final float r = mRect.width() * 0.23f;
            canvas.drawRoundRect(mRect, r, r, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return mBitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return mBitmap.getHeight();
        }
    }
}
