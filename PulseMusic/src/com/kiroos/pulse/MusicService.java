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

package com.kiroos.pulse;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Background playback: the queue, shuffle / repeat, the MediaSession (headset buttons,
 * lock screen, Bluetooth), the media notification, audio focus and "becoming noisy".
 */
public class MusicService extends Service implements Engine.Listener, Engine.PcmTap {

    interface Listener {
        void onStateChanged();
    }

    /** Live waveform for the visualizer (last block of mono samples, -1…1). */
    interface WaveListener {
        void onWave(float[] wave);
    }

    static final int REPEAT_OFF = 0;
    static final int REPEAT_ALL = 1;
    static final int REPEAT_ONE = 2;

    private static final String ACTION_TOGGLE = "com.kiroos.pulse.TOGGLE";
    private static final String ACTION_NEXT = "com.kiroos.pulse.NEXT";
    private static final String ACTION_PREV = "com.kiroos.pulse.PREV";
    private static final String ACTION_CLOSE = "com.kiroos.pulse.CLOSE";
    private static final int NOTIFICATION = 1;
    private static final int WAVE_POINTS = 256;

    private static MusicService sInstance;

    private final Handler mHandler = new Handler();
    /** static: screens register before the service exists (it starts on the first play) */
    private static final List<Listener> sListeners = new ArrayList<>();
    private volatile WaveListener mWaveListener;
    private long mLastWave;

    private Equalizer10 mEq;
    private Engine mEngine;
    private MediaSession mSession;
    private AudioManager mAudio;
    private PowerManager.WakeLock mWakeLock;
    private SharedPreferences mPrefs;

    private List<Library.Track> mQueue = new ArrayList<>();
    private List<Integer> mOrder = new ArrayList<>();
    private int mPos = -1;
    private boolean mShuffle;
    private int mRepeat = REPEAT_OFF;
    private boolean mPlaying;
    private boolean mPreparing;
    private String mFormat = "";

    static MusicService get() {
        return sInstance;
    }

    static void start(Context context) {
        context.startService(new Intent(context, MusicService.class));
    }

    /** queue waiting for the service to be created */
    private static List<Library.Track> sPendingQueue;
    private static int sPendingIndex;

    /** Plays a queue, starting the service first when it isn't running. */
    static void play(Context context, List<Library.Track> tracks, int index) {
        final MusicService s = sInstance;
        if (s != null) {
            s.playQueue(tracks, index);
            return;
        }
        sPendingQueue = new ArrayList<>(tracks);
        sPendingIndex = index;
        start(context);
    }

    private final AudioManager.OnAudioFocusChangeListener mFocus =
            new AudioManager.OnAudioFocusChangeListener() {
                private boolean mResumeOnGain;

                @Override
                public void onAudioFocusChange(int change) {
                    switch (change) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            mEngine.setVolume(1f);
                            if (mResumeOnGain) {
                                mResumeOnGain = false;
                                play();
                            }
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            mEngine.setVolume(0.25f);
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            mResumeOnGain = mPlaying;
                            pause();
                            break;
                        default:
                            mResumeOnGain = false;
                            pause();
                            break;
                    }
                }
            };

    private final BroadcastReceiver mNoisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            pause(); // headphones unplugged / Bluetooth gone: don't blast the speaker
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mPrefs = getSharedPreferences("pulse", MODE_PRIVATE);
        mShuffle = mPrefs.getBoolean("shuffle", false);
        mRepeat = mPrefs.getInt("repeat", REPEAT_OFF);
        mEq = Equalizer10.shared(mPrefs);
        mEngine = new Engine(this, mEq);
        mEngine.setTap(this);
        mAudio = getSystemService(AudioManager.class);
        mWakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pulse:playback");
        mWakeLock.setReferenceCounted(false);
        mSession = new MediaSession(this, "Pulse");
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next(true);
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo(pos);
            }

            @Override
            public void onStop() {
                close();
            }
        });
        mSession.setSessionActivity(PendingIntent.getActivity(this, 0,
                new Intent(this, NowPlayingActivity.class), PendingIntent.FLAG_UPDATE_CURRENT));
        registerReceiver(mNoisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
        if (sPendingQueue != null) {
            final List<Library.Track> q = sPendingQueue;
            sPendingQueue = null;
            playQueue(q, sPendingIndex);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_TOGGLE.equals(action)) {
            toggle();
        } else if (ACTION_NEXT.equals(action)) {
            next(true);
        } else if (ACTION_PREV.equals(action)) {
            previous();
        } else if (ACTION_CLOSE.equals(action)) {
            close();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mEngine.stop();
        mSession.release();
        unregisterReceiver(mNoisy);
        mAudio.abandonAudioFocus(mFocus);
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        sInstance = null;
        super.onDestroy();
    }

    // --- listeners ------------------------------------------------------------------------------

    static void addListener(Listener l) {
        if (!sListeners.contains(l)) {
            sListeners.add(l);
        }
    }

    static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    void setWaveListener(WaveListener l) {
        mWaveListener = l;
    }

    private void changed() {
        for (Listener l : new ArrayList<>(sListeners)) {
            l.onStateChanged();
        }
        updateSession();
    }

    // --- state ----------------------------------------------------------------------------------

    Equalizer10 equalizer() {
        return mEq;
    }

    void saveEqualizer() {
        mEq.save(mPrefs);
    }

    Library.Track current() {
        return mPos >= 0 && mPos < mOrder.size() ? mQueue.get(mOrder.get(mPos)) : null;
    }

    boolean isPlaying() {
        return mPlaying;
    }

    boolean isPreparing() {
        return mPreparing;
    }

    boolean shuffle() {
        return mShuffle;
    }

    int repeat() {
        return mRepeat;
    }

    long position() {
        return mEngine.positionMs();
    }

    long duration() {
        final Library.Track t = current();
        final long d = mEngine.durationMs();
        return d > 0 ? d : t != null ? t.duration : 0;
    }

    /** "FLAC · 96 kHz · stereo" */
    String format() {
        return mFormat;
    }

    // --- control ------------------------------------------------------------------------------

    void playQueue(List<Library.Track> tracks, int index) {
        mQueue = new ArrayList<>(tracks);
        buildOrder(index);
        startCurrent(0);
    }

    private void buildOrder(int first) {
        mOrder = new ArrayList<>();
        for (int i = 0; i < mQueue.size(); i++) {
            mOrder.add(i);
        }
        if (mShuffle) {
            Collections.shuffle(mOrder, new Random(SystemClock.uptimeMillis()));
            // the chosen song plays first
            mOrder.remove(Integer.valueOf(first));
            mOrder.add(0, first);
            mPos = 0;
        } else {
            mPos = first;
        }
    }

    private void startCurrent(long startMs) {
        final Library.Track t = current();
        if (t == null) {
            return;
        }
        if (mAudio.requestAudioFocus(mFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }
        mPreparing = true;
        mPlaying = true;
        mFormat = "";
        mWakeLock.acquire();
        mSession.setActive(true);
        if (t.stream) {
            // playlists (.m3u/.pls) are resolved off the main thread
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String real = Library.resolveStream(t.uri.toString());
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (t == current()) {
                                mEngine.play(android.net.Uri.parse(real), MusicService.this, 0);
                            }
                        }
                    });
                }
            }, "PulseResolve").start();
        } else {
            mEngine.play(t.uri, this, startMs);
        }
        changed();
        startForeground(NOTIFICATION, notification(t));
    }

    void toggle() {
        if (mPlaying) {
            pause();
        } else {
            play();
        }
    }

    void play() {
        if (current() == null) {
            return;
        }
        if (mAudio.requestAudioFocus(mFocus, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }
        mPlaying = true;
        mEngine.resume();
        mWakeLock.acquire();
        changed();
        startForeground(NOTIFICATION, notification(current()));
    }

    void pause() {
        if (!mPlaying) {
            return;
        }
        mPlaying = false;
        mEngine.pause();
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        changed();
        // keep the notification (to resume) but let the system reclaim the service
        stopForeground(false);
        final Library.Track t = current();
        if (t != null) {
            getSystemService(android.app.NotificationManager.class).notify(NOTIFICATION,
                    notification(t));
        }
    }

    void seekTo(long ms) {
        mEngine.seekTo(ms);
        updateSession();
    }

    /** @param user skipped by the user (repeat-one does not hold it back) */
    void next(boolean user) {
        if (mQueue.isEmpty()) {
            return;
        }
        if (!user && mRepeat == REPEAT_ONE) {
            startCurrent(0);
            return;
        }
        if (mPos + 1 < mOrder.size()) {
            mPos++;
        } else if (mRepeat != REPEAT_OFF || user) {
            mPos = 0;
        } else {
            // end of the queue
            mPlaying = false;
            mEngine.stop();
            changed();
            stopForeground(false);
            return;
        }
        startCurrent(0);
    }

    void previous() {
        if (mQueue.isEmpty()) {
            return;
        }
        if (mEngine.positionMs() > 3000 || mPos == 0) {
            seekTo(0); // like every player: first press restarts the song
            return;
        }
        mPos--;
        startCurrent(0);
    }

    void setShuffle(boolean on) {
        final Library.Track cur = current();
        mShuffle = on;
        mPrefs.edit().putBoolean("shuffle", on).apply();
        if (cur != null) {
            buildOrder(mQueue.indexOf(cur));
        }
        changed();
    }

    void cycleRepeat() {
        mRepeat = (mRepeat + 1) % 3;
        mPrefs.edit().putInt("repeat", mRepeat).apply();
        changed();
    }

    void close() {
        mPlaying = false;
        mEngine.stop();
        mSession.setActive(false);
        stopForeground(true);
        changed();
        stopSelf();
    }

    // --- engine callbacks (decoder thread) ----------------------------------------------------

    @Override
    public void onPrepared(final long durationMs, final int sampleRate, final int channels,
            final String mime) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPreparing = false;
                final String codec = mime.substring(mime.indexOf('/') + 1).toUpperCase()
                        .replace("MPEG", "MP3").replace("MP4A-LATM", "AAC");
                mFormat = codec + " · " + (sampleRate % 1000 == 0 ? sampleRate / 1000
                        : String.format(java.util.Locale.US, "%.1f", sampleRate / 1000f))
                        + " kHz · " + (channels == 1 ? getString(R.string.mono)
                        : getString(R.string.stereo));
                if (!mPlaying) {
                    mEngine.pause();
                }
                changed();
            }
        });
    }

    @Override
    public void onCompletion() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                next(false);
            }
        });
    }

    @Override
    public void onError(final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mPreparing = false;
                Toast.makeText(MusicService.this, getString(R.string.play_error, message),
                        Toast.LENGTH_LONG).show();
                final Library.Track t = current();
                if (t != null && !t.stream && mPos + 1 < mOrder.size()) {
                    next(true); // skip an unreadable file
                } else {
                    mPlaying = false;
                    changed();
                }
            }
        });
    }

    @Override
    public void onPcm(short[] pcm, int length, int channels) {
        final WaveListener l = mWaveListener;
        final long now = SystemClock.uptimeMillis();
        if (l == null || now - mLastWave < 33) {
            return; // ~30 fps is enough for the eye
        }
        mLastWave = now;
        final int frames = length / channels;
        final int step = Math.max(1, frames / WAVE_POINTS);
        final float[] wave = new float[WAVE_POINTS];
        for (int i = 0; i < WAVE_POINTS; i++) {
            final int f = Math.min(frames - 1, i * step);
            float v = 0;
            for (int c = 0; c < channels; c++) {
                v += pcm[f * channels + c];
            }
            wave[i] = v / channels / 32768f;
        }
        l.onWave(wave);
    }

    // --- session & notification ---------------------------------------------------------------

    private void updateSession() {
        final Library.Track t = current();
        final long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_STOP;
        mSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(mPreparing ? PlaybackState.STATE_BUFFERING : mPlaying
                                ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        mEngine.positionMs(), mPlaying ? 1f : 0f)
                .build());
        if (t != null) {
            final Bitmap art = Library.art(this, t.albumId, 512);
            final MediaMetadata.Builder m = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
            if (art != null) {
                m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            }
            mSession.setMetadata(m.build());
        }
    }

    private Notification notification(Library.Track t) {
        final Bitmap art = Library.art(this, t.albumId, 256);
        final Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_music)
                .setColor(getColor(R.color.kiro_accent))
                .setContentTitle(t.title)
                .setContentText(t.artist)
                .setSubText(t.stream ? getString(R.string.stream) : t.album)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setShowWhen(false)
                .setOngoing(mPlaying)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, NowPlayingActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(service(ACTION_CLOSE))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_previous),
                        getString(R.string.previous), service(ACTION_PREV)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, mPlaying ? R.drawable.ic_pause
                                : R.drawable.ic_play),
                        getString(mPlaying ? R.string.pause : R.string.play),
                        service(ACTION_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_next),
                        getString(R.string.next), service(ACTION_NEXT)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) {
            b.setLargeIcon(art);
        }
        return b.build();
    }

    private PendingIntent service(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, MusicService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
