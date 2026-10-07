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

package com.kiroos.screenrecord;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaScannerConnection;
import android.media.ThumbnailUtils;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs one recording: a 3 s countdown, then the recording in the foreground with a Stop action,
 * then the "saved" notification (view, share, delete).
 */
public class RecordService extends Service {

    static final String ACTION_START = "com.kiroos.screenrecord.START";
    static final String ACTION_STOP = "com.kiroos.screenrecord.STOP";
    static final String ACTION_DELETE = "com.kiroos.screenrecord.DELETE";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    private static final String EXTRA_URI = "uri";

    private static final String TAG = "KiroScreenRecord";
    private static final int NOTIFY_RECORDING = 1;
    private static final int NOTIFY_SAVED = 2;
    private static final int COUNTDOWN = 3;

    /** read by the tile: a recording or its countdown is running */
    static volatile boolean sActive;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private NotificationManager mNotifications;
    private MediaProjection mProjection;
    private ScreenRecorder mRecorder;
    private RecordSettings mSettings;
    private int mCountdown;
    private boolean mStopping;
    /** show_touches before the recording, -1 when this recording did not change it */
    private int mTouchesBefore = -1;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            mCountdown--;
            if (mCountdown > 0) {
                notifyCountdown();
                mHandler.postDelayed(this, 1000);
            } else {
                startRecording();
            }
        }
    };

    private final MediaProjection.Callback mProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            // another app took the screen capture, or the system ended it
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopRecording();
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifications = getSystemService(NotificationManager.class);
        if (!sActive) ScreenRecorder.cleanUpDeviceAudio();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            begin(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_DELETE.equals(action)) {
            delete((Uri) intent.getParcelableExtra(EXTRA_URI));
            if (!sActive) stopSelf();
        } else if (!sActive) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        if (sActive) return;
        final Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        final int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        if (data == null) {
            stopSelf();
            return;
        }
        mProjection = getSystemService(MediaProjectionManager.class).getMediaProjection(code, data);
        if (mProjection == null) {
            stopSelf();
            return;
        }
        mProjection.registerCallback(mProjectionCallback, mHandler);
        mSettings = RecordSettings.load(this);
        sActive = true;
        mStopping = false;
        mNotifications.cancel(NOTIFY_SAVED);
        mCountdown = COUNTDOWN;
        startForeground(NOTIFY_RECORDING, countdownNotification());
        mHandler.postDelayed(mTick, 1000);
        updateTile();
    }

    private void startRecording() {
        final DisplayMetrics dm = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        // the video keeps the screen's shape at the chosen size (1440x2560 -> 1080x1920)
        final int shortScreen = Math.min(dm.widthPixels, dm.heightPixels);
        final int longScreen = Math.max(dm.widthPixels, dm.heightPixels);
        final int shortSide = Math.min(mSettings.shortSide(), shortScreen);
        final int longSide = even(Math.round(longScreen * (float) shortSide / shortScreen));
        final boolean portrait = dm.heightPixels >= dm.widthPixels;
        final int width = portrait ? even(shortSide) : longSide;
        final int height = portrait ? longSide : even(shortSide);
        final int dpi = Math.round(dm.densityDpi * (float) shortSide / shortScreen);

        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), "Screen recordings");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir + ": storage "
                    + Environment.getExternalStorageState() + ", parent writable "
                    + dir.getParentFile().canWrite());
        }
        final String name = "Screen_recording_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        if (mSettings.touches) showTouches(true);
        mRecorder = new ScreenRecorder(mProjection, width, height, dpi, mSettings.fps(),
                mSettings.bitrate(), mSettings.audio, new File(dir, name));
        try {
            mRecorder.start();
        } catch (IOException e) {
            Log.e(TAG, "start", e);
            mRecorder = null;
            fail();
            return;
        }
        mNotifications.notify(NOTIFY_RECORDING, recordingNotification());
        updateTile();
    }

    private void stopRecording() {
        if (!sActive || mStopping) return;
        mStopping = true;
        mHandler.removeCallbacks(mTick);
        final ScreenRecorder recorder = mRecorder;
        mRecorder = null;
        if (recorder == null) {
            // still counting down
            finish();
            stopSelf();
            return;
        }
        // the encoders hand back their last frames in a moment: off the main thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = recorder.stop();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                        if (ok) {
                            saved(recorder.file());
                        } else {
                            failNotification();
                        }
                    }
                });
            }
        }, "KiroScreenRecord stop").start();
    }

    private void fail() {
        finish();
        failNotification();
    }

    private void finish() {
        showTouches(false);
        if (mProjection != null) {
            mProjection.unregisterCallback(mProjectionCallback);
            mProjection.stop();
            mProjection = null;
        }
        sActive = false;
        stopForeground(true);
        updateTile();
    }

    private void saved(final File file) {
        MediaScannerConnection.scanFile(this, new String[] { file.getPath() },
                new String[] { "video/mp4" }, new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, final Uri uri) {
                        final Bitmap thumb = ThumbnailUtils.createVideoThumbnail(path,
                                MediaStore.Images.Thumbnails.MINI_KIND);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (uri != null) mNotifications.notify(NOTIFY_SAVED,
                                        savedNotification(uri, thumb));
                                if (!sActive) stopSelf();
                            }
                        });
                    }
                });
    }

    private void delete(Uri uri) {
        mNotifications.cancel(NOTIFY_SAVED);
        if (uri == null) return;
        // the media provider removes the row and the file
        if (getContentResolver().delete(uri, null, null) > 0) {
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
        }
    }

    private void showTouches(boolean on) {
        try {
            if (on) {
                mTouchesBefore = android.provider.Settings.System.getInt(getContentResolver(),
                        android.provider.Settings.System.SHOW_TOUCHES, 0);
                android.provider.Settings.System.putInt(getContentResolver(),
                        android.provider.Settings.System.SHOW_TOUCHES, 1);
            } else if (mTouchesBefore >= 0) {
                android.provider.Settings.System.putInt(getContentResolver(),
                        android.provider.Settings.System.SHOW_TOUCHES, mTouchesBefore);
                mTouchesBefore = -1;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "show touches", e);
        }
    }

    private void updateTile() {
        TileService.requestListeningState(this,
                new ComponentName(this, RecordTileService.class));
    }

    private static int even(int v) {
        return v & ~1;
    }

    // ---- notifications

    private Notification.Builder base() {
        return new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_screenrecord)
                .setColor(getColor(com.kiroos.ui.R.color.kiro_accent));
    }

    private PendingIntent stopIntent() {
        return PendingIntent.getService(this, 0,
                new Intent(this, RecordService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification countdownNotification() {
        return base()
                .setContentTitle(getString(R.string.countdown, mCountdown))
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        getString(R.string.cancel), stopIntent()).build())
                .build();
    }

    private void notifyCountdown() {
        mNotifications.notify(NOTIFY_RECORDING, countdownNotification());
    }

    private Notification recordingNotification() {
        return base()
                .setContentTitle(getString(R.string.recording))
                .setContentText(getString(R.string.recording_text))
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(stopIntent())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        getString(R.string.stop), stopIntent()).build())
                .build();
    }

    private Notification savedNotification(Uri uri, Bitmap thumb) {
        final Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final Intent share = Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null);
        final Intent delete = new Intent(this, RecordService.class)
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

    private void failNotification() {
        mNotifications.notify(NOTIFY_SAVED, base()
                .setContentTitle(getString(R.string.error))
                .setContentText(getString(R.string.error_text))
                .setAutoCancel(true)
                .build());
        if (!sActive) stopSelf();
    }

    static Intent startIntent(Context context, int resultCode, Intent data) {
        return new Intent(context, RecordService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data);
    }
}
