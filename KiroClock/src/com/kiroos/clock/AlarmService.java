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

package com.kiroos.clock;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.Calendar;

/**
 * Rings an alarm or the finished timer: alarm sound with a 20 s volume ramp, vibration,
 * a full-screen notification (AlarmActivity over the lock screen), snooze / dismiss.
 * Stops by itself after 10 minutes.
 */
public class AlarmService extends Service {

    private static final String TAG = "KiroClock";
    static final String ACTION_RING_ALARM = "com.kiroos.clock.RING_ALARM";
    static final String ACTION_RING_TIMER = "com.kiroos.clock.RING_TIMER";
    static final String ACTION_SNOOZE = "com.kiroos.clock.SNOOZE";
    static final String ACTION_DISMISS = "com.kiroos.clock.DISMISS";
    static final String ACTION_TIMER_ADD_MINUTE = "com.kiroos.clock.TIMER_ADD_MINUTE";
    /** broadcast (to this app) when the ringing stops, so AlarmActivity closes */
    static final String ACTION_DONE = "com.kiroos.clock.RING_DONE";

    static final int SNOOZE_MINUTES = 10;
    private static final long AUTO_SILENCE_MS = 10 * 60 * 1000;
    private static final long RAMP_MS = 20 * 1000;
    private static final int NOTIFICATION_ID = 100;
    private static final int MISSED_ID = 101;

    /** what is ringing now: the alarm id, or -2 for the timer, -1 nothing */
    static volatile long sRinging = -1;

    private final Handler mHandler = new Handler();
    private MediaPlayer mPlayer;
    private Vibrator mVibrator;
    private PowerManager.WakeLock mWakeLock;
    private long mRampStart;
    private float mMaxVolume = 1f;

    private final Runnable mRamp = new Runnable() {
        @Override
        public void run() {
            if (mPlayer == null) {
                return;
            }
            final float f = Math.min(1f, 0.15f + (System.currentTimeMillis() - mRampStart)
                    / (float) RAMP_MS);
            final float v = f * mMaxVolume;
            mPlayer.setVolume(v, v);
            if (f < 1f) {
                mHandler.postDelayed(this, 500);
            }
        }
    };

    private final Runnable mAutoSilence = new Runnable() {
        @Override
        public void run() {
            if (sRinging >= 0) {
                notifyMissed(sRinging);
            }
            dismiss();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_RING_ALARM.equals(action)) {
            ringAlarm(intent.getLongExtra(Scheduler.EXTRA_ID, -1));
        } else if (ACTION_RING_TIMER.equals(action)) {
            ringTimer();
        } else if (ACTION_SNOOZE.equals(action)) {
            snooze();
        } else if (ACTION_DISMISS.equals(action)) {
            dismiss();
        } else if (ACTION_TIMER_ADD_MINUTE.equals(action)) {
            stopRinging();
            Scheduler.startTimer(this, 60 * 1000);
            finishService();
        } else if (sRinging == -1) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void ringAlarm(long id) {
        final Alarm alarm = id >= 0 ? Alarm.get(this, id) : null;
        if (alarm == null) {
            Scheduler.reschedule(this);
            if (sRinging == -1) {
                stopSelf();
            }
            return;
        }
        if (sRinging >= 0 && sRinging != id) {
            // another alarm is ringing: it counts as missed, the new one takes over
            notifyMissed(sRinging);
        }
        stopRinging();
        sRinging = id;
        // a one-time alarm is spent; a repeating one moves to its next day
        alarm.snoozeUntil = 0;
        if (alarm.days == 0) {
            alarm.enabled = false;
        }
        alarm.save(this);
        Scheduler.reschedule(this);

        final String title = TextUtils.isEmpty(alarm.label) ? getString(R.string.alarm)
                : alarm.label;
        startForeground(NOTIFICATION_ID, ringingNotification(title, timeText(alarm), false));
        startSound(alarm.ringtone, alarm.vibrate);
        startActivity(AlarmActivity.intent(this, title, timeText(alarm), false));
    }

    private void ringTimer() {
        stopRinging();
        sRinging = -2;
        final String title = getString(R.string.timer_done);
        startForeground(NOTIFICATION_ID, ringingNotification(title, "", true));
        startSound(Scheduler.prefs(this).getString("timer_sound", null), true);
        startActivity(AlarmActivity.intent(this, title, "", true));
    }

    private void snooze() {
        final long id = sRinging;
        if (id >= 0) {
            final Alarm a = Alarm.get(this, id);
            if (a != null) {
                a.enabled = true;
                a.snoozeUntil = System.currentTimeMillis() + SNOOZE_MINUTES * 60 * 1000L;
                a.save(this);
                Scheduler.reschedule(this);
            }
        }
        finishService();
    }

    private void dismiss() {
        if (sRinging >= 0) {
            final Alarm a = Alarm.get(this, sRinging);
            if (a != null && a.snoozeUntil != 0) {
                a.snoozeUntil = 0;
                a.save(this);
            }
            Scheduler.reschedule(this);
        }
        finishService();
    }

    private void finishService() {
        stopRinging();
        sRinging = -1;
        sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()));
        stopForeground(true);
        stopSelf();
    }

    // --- sound & vibration ------------------------------------------------------------------

    private void startSound(String ringtone, boolean vibrate) {
        final PowerManager pm = getSystemService(PowerManager.class);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KiroClock:ring");
        mWakeLock.acquire(AUTO_SILENCE_MS + 5000);

        final TelephonyManager tm = getSystemService(TelephonyManager.class);
        final boolean inCall = tm != null && tm.getCallState() != TelephonyManager.CALL_STATE_IDLE;
        mMaxVolume = inCall ? 0.2f : 1f; // don't blast the ear during a call

        if (!"".equals(ringtone)) {
            Uri uri = ringtone != null ? Uri.parse(ringtone) : null;
            if (uri == null) {
                uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            }
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            try {
                mPlayer = new MediaPlayer();
                mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                mPlayer.setDataSource(this, uri);
                mPlayer.setLooping(true);
                mPlayer.prepare();
                mPlayer.setVolume(0.15f * mMaxVolume, 0.15f * mMaxVolume);
                getSystemService(AudioManager.class).requestAudioFocus(null,
                        AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                mPlayer.start();
                mRampStart = System.currentTimeMillis();
                mHandler.post(mRamp);
            } catch (Exception e) {
                Log.w(TAG, "cannot play " + uri + ", trying the fallback tone", e);
                releasePlayer();
                playFallback();
            }
        }
        if (vibrate && !inCall) {
            mVibrator = getSystemService(Vibrator.class);
            if (mVibrator != null && mVibrator.hasVibrator()) {
                mVibrator.vibrate(new long[] {0, 600, 400, 600, 1200}, 0,
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                                .build());
            }
        }
        mHandler.postDelayed(mAutoSilence, AUTO_SILENCE_MS);
    }

    /** the alarm sound file was removed (e.g. from the SD card): use a built-in one */
    private void playFallback() {
        final Uri fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        try {
            mPlayer = new MediaPlayer();
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).build());
            mPlayer.setDataSource(this, fallback);
            mPlayer.setLooping(true);
            mPlayer.prepare();
            mPlayer.start();
        } catch (Exception e) {
            Log.w(TAG, "no fallback alarm tone", e);
            releasePlayer();
        }
    }

    private void stopRinging() {
        mHandler.removeCallbacks(mRamp);
        mHandler.removeCallbacks(mAutoSilence);
        releasePlayer();
        if (mVibrator != null) {
            mVibrator.cancel();
            mVibrator = null;
        }
        getSystemService(AudioManager.class).abandonAudioFocus(null);
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mWakeLock = null;
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (IllegalStateException e) {
                // not started
            }
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        stopRinging();
        super.onDestroy();
    }

    // --- notifications ----------------------------------------------------------------------

    private Notification ringingNotification(String title, String text, boolean timer) {
        final PendingIntent full = PendingIntent.getActivity(this, 0,
                AlarmActivity.intent(this, title, text, timer), PendingIntent.FLAG_UPDATE_CURRENT);
        final Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(timer ? R.drawable.ic_hourglass : R.drawable.ic_alarm)
                .setColor(getColor(R.color.kiro_accent))
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setLocalOnly(true)
                .setContentIntent(full)
                .setFullScreenIntent(full, true);
        if (timer) {
            b.addAction(new Notification.Action.Builder(null, getString(R.string.add_minute),
                    service(ACTION_TIMER_ADD_MINUTE)).build());
            b.addAction(new Notification.Action.Builder(null, getString(R.string.stop),
                    service(ACTION_DISMISS)).build());
        } else {
            b.addAction(new Notification.Action.Builder(null, getString(R.string.snooze),
                    service(ACTION_SNOOZE)).build());
            b.addAction(new Notification.Action.Builder(null, getString(R.string.dismiss),
                    service(ACTION_DISMISS)).build());
        }
        return b.build();
    }

    private PendingIntent service(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, AlarmService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifyMissed(long id) {
        final Alarm a = Alarm.get(this, id);
        if (a == null) {
            return;
        }
        final Notification n = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_alarm)
                .setColor(getColor(R.color.kiro_accent))
                .setContentTitle(getString(R.string.missed_alarm))
                .setContentText(timeText(a) + (TextUtils.isEmpty(a.label) ? "" : " · " + a.label))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(MISSED_ID, n);
    }

    private String timeText(Alarm a) {
        final Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        return DateFormat.getTimeFormat(this).format(c.getTime());
    }
}
