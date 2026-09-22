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

package com.kiroos.gallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full screen photos and videos: swipe between them, pinch or double-tap to zoom, tap to
 * hide the bars. Share, edit, set as, details, delete.
 */
public class ViewerActivity extends Activity implements SwipePager.Adapter {

    static final String EXTRA_BUCKET = "bucket";
    static final String EXTRA_FILTER = "filter";
    static final String EXTRA_POSITION = "position";

    /** the last three full-size pictures (current and neighbours) */
    private final LruCache<Uri, Bitmap> mFull = new LruCache<>(3);
    private List<Media.Item> mItems = new ArrayList<>();
    /** set when opened with a uri that is not in the MediaStore (e.g. from another app) */
    private Uri mExternal;
    private SwipePager mPager;
    private View mTop;
    private View mBottom;
    private TextView mTitle;
    private boolean mBarsVisible = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        mPager = (SwipePager) findViewById(R.id.pager);
        mTop = findViewById(R.id.top_bar);
        mBottom = findViewById(R.id.bottom_bar);
        mTitle = (TextView) findViewById(R.id.title);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // both bars clear of the system bars: the top one below the status bar, the buttons
        // above the navigation bar (with fitsSystemWindows on both, the top bar consumed the
        // insets and the buttons sat under the navigation keys)
        final int topPad = mTop.getPaddingTop();
        final int bottomPad = mBottom.getPaddingBottom();
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public android.view.WindowInsets onApplyWindowInsets(View v,
                            android.view.WindowInsets insets) {
                        mTop.setPadding(mTop.getPaddingLeft(),
                                topPad + insets.getSystemWindowInsetTop(),
                                mTop.getPaddingRight(), mTop.getPaddingBottom());
                        mBottom.setPadding(insets.getSystemWindowInsetLeft(),
                                mBottom.getPaddingTop(), insets.getSystemWindowInsetRight(),
                                bottomPad + insets.getSystemWindowInsetBottom());
                        return insets.consumeSystemWindowInsets();
                    }
                });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Media.Item i = current();
                if (i != null) {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                            .setType(i.mime != null ? i.mime : "image/*")
                            .putExtra(Intent.EXTRA_STREAM, uriOf(i))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            getString(R.string.share)));
                }
            }
        });
        findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Media.Item i = current();
                if (i == null) {
                    return;
                }
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_EDIT)
                            .setDataAndType(uriOf(i), i.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                            getString(R.string.edit)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(ViewerActivity.this, R.string.no_editor, Toast.LENGTH_SHORT).show();
                }
            }
        });
        findViewById(R.id.btn_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInfo();
            }
        });
        findViewById(R.id.btn_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        findViewById(R.id.btn_set_as).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Media.Item i = current();
                if (i != null && !i.video) {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_ATTACH_DATA)
                            .setDataAndType(uriOf(i), i.mime)
                            .putExtra("mimeType", i.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            getString(R.string.set_as)));
                }
            }
        });
        load();
    }

    private void load() {
        final Intent intent = getIntent();
        final Uri data = intent.getData();
        final long bucket = intent.getLongExtra(EXTRA_BUCKET, 0);
        final int filter = intent.getIntExtra(EXTRA_FILTER, Media.FILTER_ALL);
        final int position = intent.getIntExtra(EXTRA_POSITION, 0);
        new AsyncTask<Void, Void, Integer>() {
            private List<Media.Item> mLoaded;

            @Override
            protected Integer doInBackground(Void... params) {
                if (data == null) {
                    mLoaded = Media.query(ViewerActivity.this, bucket, filter);
                    return position;
                }
                // a MediaStore item: open its album so the neighbours can be swiped to
                long id = -1;
                if ("media".equals(data.getAuthority())) {
                    try {
                        id = ContentUris.parseId(data);
                    } catch (RuntimeException e) {
                        id = -1;
                    }
                }
                if (id >= 0) {
                    final List<Media.Item> all = Media.query(ViewerActivity.this, 0,
                            Media.FILTER_ALL);
                    for (Media.Item i : all) {
                        if (i.id == id) {
                            mLoaded = Media.query(ViewerActivity.this, i.bucketId, Media.FILTER_ALL);
                            for (int k = 0; k < mLoaded.size(); k++) {
                                if (mLoaded.get(k).id == id) {
                                    return k;
                                }
                            }
                        }
                    }
                }
                // anything else (file manager, e-mail attachment…): just that picture
                final Media.Item single = new Media.Item();
                single.id = -1;
                single.mime = getContentResolver().getType(data);
                if (single.mime == null) {
                    single.mime = intent.getType();
                }
                single.video = single.mime != null && single.mime.startsWith("video/");
                single.name = data.getLastPathSegment();
                mLoaded = new ArrayList<>();
                mLoaded.add(single);
                mExternal = data;
                return 0;
            }

            @Override
            protected void onPostExecute(Integer pos) {
                mItems = mLoaded;
                if (mItems.isEmpty()) {
                    finish();
                    return;
                }
                final boolean external = mExternal != null;
                findViewById(R.id.btn_delete).setVisibility(external ? View.GONE : View.VISIBLE);
                findViewById(R.id.btn_info).setVisibility(external ? View.GONE : View.VISIBLE);
                mPager.setAdapter(ViewerActivity.this, pos);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private Media.Item current() {
        final int p = mPager.position();
        return p >= 0 && p < mItems.size() ? mItems.get(p) : null;
    }

    private Uri uriOf(Media.Item i) {
        return mExternal != null ? mExternal : i.uri();
    }

    // --- pager --------------------------------------------------------------------------------

    @Override
    public int count() {
        return mItems.size();
    }

    @Override
    public View create(Context context) {
        final FrameLayout page = new FrameLayout(context);
        final ZoomImageView image = new ZoomImageView(context);
        image.setId(R.id.page_image);
        image.setOnTapListener(new ZoomImageView.OnTapListener() {
            @Override
            public void onTap() {
                toggleBars();
            }
        });
        page.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final ImageView play = new ImageView(context);
        play.setId(R.id.page_play);
        play.setImageResource(R.drawable.ic_play);
        play.setBackgroundResource(R.drawable.play_circle);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setContentDescription(getString(R.string.play));
        final int side = Math.round(72 * getResources().getDisplayMetrics().density);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(side, side);
        lp.gravity = android.view.Gravity.CENTER;
        page.addView(play, lp);
        return page;
    }

    @Override
    public void bind(View page, final int position) {
        final Media.Item item = mItems.get(position);
        final ZoomImageView image = (ZoomImageView) page.findViewById(R.id.page_image);
        final View play = page.findViewById(R.id.page_play);
        play.setVisibility(item.video ? View.VISIBLE : View.GONE);
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uriOf(item), item.mime != null ? item.mime : "video/*")
                        .setClass(ViewerActivity.this, VideoActivity.class));
            }
        });
        image.resetZoom();
        final Uri uri = uriOf(item);
        image.setTag(uri);
        final Bitmap cached = mFull.get(uri);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        // show the grid thumbnail first, then the sharp picture
        if (item.id >= 0) {
            Thumbnails.get(this).load(item, image);
        } else {
            image.setImageDrawable(null);
        }
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                return item.video ? videoFrame(uri) : decodeFull(uri, item.orientation, item.id < 0);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                if (b == null) {
                    return;
                }
                mFull.put(uri, b);
                if (uri.equals(image.getTag())) {
                    image.setTag(R.id.thumb_item, null); // stop a late thumbnail
                    image.setImageBitmap(b);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onPageSelected(int position) {
        final Media.Item i = mItems.get(position);
        mTitle.setText(i.date > 0 ? DateUtils.formatDateTime(this, i.date,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR
                        | DateUtils.FORMAT_ABBREV_MONTH) : i.name);
        findViewById(R.id.btn_set_as).setVisibility(i.video ? View.GONE : View.VISIBLE);
    }

    /** picture at up to twice the screen size (sharp when zoomed), upright */
    private Bitmap decodeFull(Uri uri, int orientation, boolean readExif) {
        try {
            final DisplayMetrics dm = getResources().getDisplayMetrics();
            final int target = Math.max(dm.widthPixels, dm.heightPixels) * 2;
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) {
                sample *= 2;
            }
            final BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, d);
            }
            if (b == null) {
                return null;
            }
            if (readExif) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    final int e = new ExifInterface(in).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    orientation = e == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                            : e == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                            : e == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                } catch (Exception ex) {
                    orientation = 0;
                }
            }
            if (orientation != 0) {
                final Matrix m = new Matrix();
                m.postRotate(orientation);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private Bitmap videoFrame(Uri uri) {
        final android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            return r.getFrameAtTime(0);
        } catch (RuntimeException e) {
            return null;
        } finally {
            r.release();
        }
    }

    // --- chrome & actions ------------------------------------------------------------------------

    private void toggleBars() {
        mBarsVisible = !mBarsVisible;
        final float a = mBarsVisible ? 1f : 0f;
        mTop.animate().alpha(a).setDuration(180).start();
        mBottom.animate().alpha(a).setDuration(180).start();
        mTop.setClickable(mBarsVisible);
        mBottom.setClickable(mBarsVisible);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!mBarsVisible) {
            flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        for (int i = 0; i < ((ViewGroup) mTop).getChildCount(); i++) {
            ((ViewGroup) mTop).getChildAt(i).setEnabled(mBarsVisible);
        }
        for (int i = 0; i < ((ViewGroup) mBottom).getChildCount(); i++) {
            ((ViewGroup) mBottom).getChildAt(i).setEnabled(mBarsVisible);
        }
    }

    private void showInfo() {
        final Media.Item i = current();
        if (i == null) {
            return;
        }
        final StringBuilder b = new StringBuilder();
        b.append(getString(R.string.info_name, i.name)).append('\n');
        b.append(getString(R.string.info_date, DateUtils.formatDateTime(this, i.date,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR)))
                .append('\n');
        if (i.width > 0) {
            b.append(getString(R.string.info_size, i.width, i.height,
                    Formatter.formatFileSize(this, i.size))).append('\n');
        }
        if (i.video) {
            b.append(getString(R.string.info_length, DateUtils.formatElapsedTime(i.duration / 1000)))
                    .append('\n');
        }
        b.append(getString(R.string.info_path, i.path));
        new AlertDialog.Builder(this)
                .setTitle(R.string.details)
                .setMessage(b.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void confirmDelete() {
        final Media.Item i = current();
        if (i == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(i.video ? R.string.delete_video : R.string.delete_photo)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            getContentResolver().delete(i.uri(), null, null);
                            // MediaStore removes the file for images; make sure for videos too
                            if (i.path != null) {
                                new java.io.File(i.path).delete();
                            }
                        } catch (RuntimeException e) {
                            Toast.makeText(ViewerActivity.this, R.string.delete_failed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        mItems.remove(i);
                        if (mItems.isEmpty()) {
                            finish();
                        } else {
                            mPager.refresh();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        mFull.evictAll();
        Thumbnails.get(this).trim();
    }
}
