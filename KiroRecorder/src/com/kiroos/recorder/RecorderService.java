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

package com.kiroos.recorder;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records in the foreground (keeps going with the screen off): AAC 44.1 kHz stereo 128 kbps
 * in Music/Recordings, with pause / resume and a notification timer.
 */
public class RecorderService extends Service {

    private static final String TAG = "KiroRecorder";
    static final String ACTION_START = "com.kiroos.recorder.START";
    static final String ACTION_PAUSE = "com.kiroos.recorder.PAUSE";
    static final String ACTION_RESUME = "com.kiroos.recorder.RESUME";
    static final String ACTION_STOP = "com.kiroos.recorder.STOP";
    private static final int ID = 1;

    interface Listener {
        void onRecorderStateChanged();
    }

    static volatile Listener sListener;
    private static RecorderService sInstance;

    private MediaRecorder mRecorder;
    private File mFile;
    private boolean mPaused;
    /** elapsedRealtime when recording (re)started, and time recorded before that */
    private long mStartedAt;
    private long mRecordedBefore;

    static boolean isRecording() {
        return sInstance != null && sInstance.mRecorder != null;
    }

    static boolean isPaused() {
        return sInstance != null && sInstance.mPaused;
    }

    static long elapsed() {
        final RecorderService s = sInstance;
        if (s == null || s.mRecorder == null) {
            return 0;
        }
        return s.mRecordedBefore + (s.mPaused ? 0 : SystemClock.elapsedRealtime() - s.mStartedAt);
    }

    /** 0…32767 peak since the last call */
    static int amplitude() {
        final RecorderService s = sInstance;
        try {
            return s != null && s.mRecorder != null && !s.mPaused ? s.mRecorder.getMaxAmplitude() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static File directory() {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "Recordings");
        dir.mkdirs();
        return dir;
    }

    static void send(Context context, String action) {
        context.startService(new Intent(context, RecorderService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            start();
        } else if (ACTION_PAUSE.equals(action) && mRecorder != null && !mPaused) {
            mRecorder.pause();
            mRecordedBefore += SystemClock.elapsedRealtime() - mStartedAt;
            mPaused = true;
            updateNotification();
        } else if (ACTION_RESUME.equals(action) && mRecorder != null && mPaused) {
            mRecorder.resume();
            mStartedAt = SystemClock.elapsedRealtime();
            mPaused = false;
            updateNotification();
        } else if (ACTION_STOP.equals(action)) {
            stop();
        }
        notifyListener();
        return START_NOT_STICKY;
    }

    private void start() {
        if (mRecorder != null) {
            return;
        }
        final String name = getString(R.string.file_prefix) + " "
                + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date()) + ".m4a";
        mFile = new File(directory(), name);
        mRecorder = new MediaRecorder();
        try {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            // 1.2: High (stereo 128 kbps, the default) or Voice (mono 64 kbps, half the size)
            final boolean voice = RecorderActivity.QUALITY_VOICE.equals(
                    getSharedPreferences(RecorderActivity.PREFS, MODE_PRIVATE)
                            .getString(RecorderActivity.PREF_QUALITY, null));
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioChannels(voice ? 1 : 2);
            mRecorder.setAudioEncodingBitRate(voice ? 64000 : 128000);
            mRecorder.setOutputFile(mFile.getAbsolutePath());
            mRecorder.prepare();
            mRecorder.start();
        } catch (Exception e) {
            Log.w(TAG, "cannot record", e);
            mRecorder.release();
            mRecorder = null;
            mFile.delete();
            stopSelf();
            return;
        }
        mPaused = false;
        mRecordedBefore = 0;
        mStartedAt = SystemClock.elapsedRealtime();
        startForeground(ID, notification());
    }

    private void stop() {
        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (RuntimeException e) {
                // stopped right after start: no valid audio
                mFile.delete();
            }
            mRecorder.release();
            mRecorder = null;
            if (mFile.exists()) {
                MediaScannerConnection.scanFile(this, new String[] {mFile.getAbsolutePath()},
                        new String[] {"audio/mp4"}, null);
            }
        }
        stopForeground(true);
        stopSelf();
    }

    private void notifyListener() {
        final Listener l = sListener;
        if (l != null) {
            l.onRecorderStateChanged();
        }
    }

    private void updateNotification() {
        startForeground(ID, notification());
    }

    private Notification notification() {
        final Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_mic)
                .setColor(getColor(R.color.kiro_negative))
                .setContentTitle(getString(mPaused ? R.string.paused : R.string.recording))
                .setOngoing(true)
                .setLocalOnly(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, RecorderActivity.class), PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null,
                        getString(mPaused ? R.string.resume : R.string.pause),
                        service(mPaused ? ACTION_RESUME : ACTION_PAUSE)).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop),
                        service(ACTION_STOP)).build());
        if (!mPaused) {
            b.setUsesChronometer(true).setShowWhen(true)
                    .setWhen(System.currentTimeMillis() - elapsed());
        } else {
            b.setShowWhen(false);
        }
        return b.build();
    }

    private PendingIntent service(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, RecorderService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        if (mRecorder != null) {
            stop();
        }
        sInstance = null;
        notifyListener();
        super.onDestroy();
    }
}
