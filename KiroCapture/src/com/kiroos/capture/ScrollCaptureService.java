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

package com.kiroos.capture;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The scroll capture: scrolls the app under the closed notification shade a page at a time,
 * takes a screenshot after each scroll and joins them (Stitcher) until the page ends. The long
 * screenshot replaces the one it started from.
 */
public class ScrollCaptureService extends Service {

    static final String EXTRA_ORIGINAL = "original";
    private static final String ACTION_DELETE = "com.kiroos.capture.DELETE";
    private static final String EXTRA_URI = "uri";

    private static final String TAG = "KiroCapture";
    private static final int NOTIFY = 1;
    /** the shade closes and the app redraws before the first page */
    private static final long START_DELAY_MS = 700;
    /** the content settles, the scroll bar and the edge glow fade, before each screenshot */
    private static final long SETTLE_MS = 800;
    /** debug.kiroos.capture=1 keeps every page in the app's cache, for tuning the stitching */
    private static final String PROP_DEBUG = "debug.kiroos.capture";
    private static final int MAX_PAGES = 10;
    private static final int MAX_HEIGHT = 16000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private NotificationManager mNotifications;
    private volatile boolean mRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifications = getSystemService(NotificationManager.class);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DELETE.equals(intent.getAction())) {
            mNotifications.cancel(NOTIFY);
            final Uri uri = intent.getParcelableExtra(EXTRA_URI);
            if (uri != null && getContentResolver().delete(uri, null, null) > 0) {
                Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
            }
            if (!mRunning) stopSelf();
            return START_NOT_STICKY;
        }
        if (mRunning) return START_NOT_STICKY;
        mRunning = true;
        final Uri original = intent != null
                ? (Uri) intent.getParcelableExtra(EXTRA_ORIGINAL) : null;
        new Thread(new Runnable() {
            @Override
            public void run() {
                capture(original);
            }
        }, "KiroCapture").start();
        return START_NOT_STICKY;
    }

    private void capture(Uri original) {
        Stitcher stitcher = null;
        try {
            SystemClock.sleep(START_DELAY_MS);
            final Display display = getSystemService(WindowManager.class).getDefaultDisplay();
            if (display.getRotation() != Surface.ROTATION_0) {
                toast(R.string.portrait_only);
                return;
            }
            final DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            final int w = dm.widthPixels;
            final int h = dm.heightPixels;
            final int statusBar = statusBarHeight();
            final ComponentName app = topActivity();
            stitcher = new Stitcher(w, h, statusBar, MAX_HEIGHT);
            final boolean debug = SystemProperties.getBoolean(PROP_DEBUG, false);
            Bitmap page = screenshot(w, h);
            if (page == null) {
                toast(R.string.failed);
                return;
            }
            if (debug) dump(page, 1);
            stitcher.add(page);
            for (int i = 1; i < MAX_PAGES; i++) {
                if (app != null && !app.equals(topActivity())) {
                    Log.i(TAG, "the app changed, stopping");
                    break;
                }
                scroll(w, h, statusBar);
                SystemClock.sleep(SETTLE_MS);
                page = screenshot(w, h);
                if (page != null && debug) dump(page, i + 1);
                if (page == null || !stitcher.add(page)) break;
            }
            if (stitcher.pages() < 2) {
                toast(R.string.no_scroll);
                return;
            }
            notifySaving();
            final Bitmap result = stitcher.result();
            stitcher = null;
            final Uri uri = save(result);
            final Bitmap thumb = thumbnail(result, w, h);
            result.recycle();
            if (uri == null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mNotifications.cancel(NOTIFY);
                    }
                });
                toast(R.string.failed);
                return;
            }
            // the long screenshot takes the place of the one it started from
            if (original != null) {
                try {
                    getContentResolver().delete(original, null, null);
                } catch (RuntimeException e) {
                    Log.w(TAG, "original screenshot", e);
                }
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mNotifications.notify(NOTIFY, savedNotification(uri, thumb));
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.e(TAG, "capture", e);
            toast(R.string.failed);
        } finally {
            if (stitcher != null) stitcher.release();
            mRunning = false;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!mRunning) stopSelf();
                }
            });
        }
    }

    private void dump(Bitmap page, int n) {
        try (OutputStream out = new FileOutputStream(new File(getCacheDir(), "page_" + n + ".png"))) {
            page.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "debug page " + n, e);
        }
    }

    private static Bitmap screenshot(int w, int h) {
        final Bitmap b = SurfaceControl.screenshot(w, h);
        if (b == null) Log.w(TAG, "screenshot failed");
        return b;
    }

    /**
     * A slow drag up by about 45% of the screen that stops before lifting the finger, so the
     * list moves exactly with it and does not fling.
     */
    private static void scroll(int w, int h, int statusBar) {
        final float x = w / 2f;
        final float y0 = statusBar + (h - statusBar) * 0.72f;
        final float y1 = statusBar + (h - statusBar) * 0.27f;
        final long down = SystemClock.uptimeMillis();
        inject(down, down, MotionEvent.ACTION_DOWN, x, y0);
        final int steps = 20;
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(20);
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    x, y0 + (y1 - y0) * i / steps);
        }
        // still for a moment: the velocity drops to zero
        for (int i = 0; i < 6; i++) {
            SystemClock.sleep(20);
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y1);
        }
        inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y1);
    }

    private static void inject(long down, long time, int action, float x, float y) {
        final MotionEvent e = MotionEvent.obtain(down, time, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        InputManager.getInstance().injectInputEvent(e,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
        e.recycle();
    }

    private ComponentName topActivity() {
        final List<ActivityManager.RunningTaskInfo> tasks =
                getSystemService(ActivityManager.class).getRunningTasks(1);
        return tasks == null || tasks.isEmpty() ? null : tasks.get(0).topActivity;
    }

    private int statusBarHeight() {
        final int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** Pictures/Screenshots, named like the system's screenshots, and in the media store */
    private Uri save(Bitmap image) {
        final long now = System.currentTimeMillis();
        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Screenshots");
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "cannot create " + dir);
        final String name = "Screenshot_"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(now))
                + "_long.png";
        final File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.e(TAG, "save", e);
            file.delete();
            return null;
        }
        final ContentValues v = new ContentValues();
        v.put(MediaStore.Images.ImageColumns.DATA, file.getPath());
        v.put(MediaStore.Images.ImageColumns.TITLE, name);
        v.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, name);
        v.put(MediaStore.Images.ImageColumns.DATE_TAKEN, now);
        v.put(MediaStore.Images.ImageColumns.DATE_ADDED, now / 1000);
        v.put(MediaStore.Images.ImageColumns.DATE_MODIFIED, now / 1000);
        v.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.ImageColumns.WIDTH, image.getWidth());
        v.put(MediaStore.Images.ImageColumns.HEIGHT, image.getHeight());
        v.put(MediaStore.Images.ImageColumns.SIZE, file.length());
        try {
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (RuntimeException e) {
            Log.e(TAG, "media store", e);
            return null;
        }
    }

    /** the top of the long screenshot, in the shape of the notification's picture */
    private static Bitmap thumbnail(Bitmap image, int w, int h) {
        final int tw = 540;
        final int th = Math.min(image.getHeight(), w / 2) * tw / w;
        final Bitmap top = Bitmap.createBitmap(image, 0, 0, w, Math.min(image.getHeight(), w / 2));
        final Bitmap t = Bitmap.createScaledBitmap(top, tw, Math.max(1, th), true);
        if (t != top) top.recycle();
        return t;
    }

    private void toast(final int text) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ScrollCaptureService.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- notifications

    private Notification.Builder base() {
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setColor(getColor(com.kiroos.ui.R.color.kiro_accent));
    }

    private void notifySaving() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mNotifications.notify(NOTIFY, base()
                        .setContentTitle(getString(R.string.saving))
                        .setProgress(0, 0, true)
                        .setOngoing(true)
                        .build());
            }
        });
    }

    private Notification savedNotification(Uri uri, Bitmap thumb) {
        final Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/png")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Intent share = Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null);
        final Intent delete = new Intent(this, ScrollCaptureService.class)
                .setAction(ACTION_DELETE)
                .putExtra(EXTRA_URI, uri);
        final Notification.Builder b = base()
                .setContentTitle(getString(R.string.saved))
                .setContentText(getString(R.string.saved_text))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(this, 1, view,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_share),
                        getString(R.string.share), PendingIntent.getActivity(this, 2, share,
                                PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_delete),
                        getString(R.string.delete), PendingIntent.getService(this, 3, delete,
                                PendingIntent.FLAG_UPDATE_CURRENT)).build());
        if (thumb != null) {
            b.setLargeIcon(thumb).setStyle(new Notification.BigPictureStyle()
                    .bigPicture(thumb).bigLargeIcon((Bitmap) null));
        }
        return b.build();
    }
}
