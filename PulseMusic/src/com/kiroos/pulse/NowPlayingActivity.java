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

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.Collections;

/**
 * The playing song: cover, live waveform, position, controls, shuffle / repeat, the sleep
 * timer (1.2) and the equalizer. Also opens audio files from other apps (VIEW audio/*).
 */
public class NowPlayingActivity extends Activity implements MusicService.Listener {

    private final Handler mHandler = new Handler();
    private ImageView mArt;
    private TextView mTitle;
    private TextView mArtist;
    private TextView mFormat;
    private TextView mElapsed;
    private TextView mTotal;
    private SeekBar mSeek;
    private ImageButton mPlay;
    private ImageButton mShuffle;
    private ImageButton mRepeat;
    private ImageButton mSleep;
    private WaveView mWave;
    private boolean mTracking;
    private long mShownAlbum = -2;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updatePosition();
            mHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_now_playing);
        mArt = (ImageView) findViewById(R.id.art);
        mArt.setClipToOutline(true);
        mArt.setBackground(new SquircleDrawable(getColor(R.color.kiro_surface_raised)));
        mTitle = (TextView) findViewById(R.id.title);
        mArtist = (TextView) findViewById(R.id.artist);
        mFormat = (TextView) findViewById(R.id.format);
        mElapsed = (TextView) findViewById(R.id.elapsed);
        mTotal = (TextView) findViewById(R.id.total);
        mSeek = (SeekBar) findViewById(R.id.seek);
        mPlay = (ImageButton) findViewById(R.id.btn_play);
        mShuffle = (ImageButton) findViewById(R.id.btn_shuffle);
        mRepeat = (ImageButton) findViewById(R.id.btn_repeat);
        mWave = (WaveView) findViewById(R.id.wave);
        mTitle.setSelected(true); // marquee

        mPlay.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        mPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.toggle();
                }
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.next(true);
                }
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.previous();
                }
            }
        });
        mShuffle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.setShuffle(!s.shuffle());
                }
            }
        });
        mRepeat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.cycleRepeat();
                }
            }
        });
        mSleep = (ImageButton) findViewById(R.id.btn_sleep);
        mSleep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSleepTimer();
            }
        });
        findViewById(R.id.btn_eq).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(NowPlayingActivity.this, EqualizerActivity.class));
            }
        });
        findViewById(R.id.btn_down).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    mElapsed.setText(DateUtils.formatElapsedTime(progress / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                mTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mTracking = false;
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.seekTo(bar.getProgress());
                }
            }
        });
        KiroTouch.apply(mPlay, findViewById(R.id.btn_next), findViewById(R.id.btn_prev));
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** an audio file opened from Files, a download, an e-mail… */
    private void handleIntent(Intent intent) {
        final Uri data = intent.getData();
        if (data == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return;
        }
        final Library.Track t = new Library.Track();
        t.uri = data;
        t.id = -1;
        t.title = displayName(data);
        t.artist = "";
        t.album = "";
        final String scheme = data.getScheme();
        t.stream = "http".equals(scheme) || "https".equals(scheme);
        MusicService.play(this, Collections.singletonList(t), 0);
    }

    private String displayName(Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor c = getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getString(0) != null) {
                    return c.getString(0);
                }
            } catch (RuntimeException e) {
                // fall back to the path
            }
        }
        final String last = uri.getLastPathSegment();
        return last != null ? last : uri.toString();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MusicService.addListener(this);
        final MusicService s = MusicService.get();
        if (s != null) {
            s.setWaveListener(mWave);
        }
        onStateChanged();
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MusicService.removeListener(this);
        final MusicService s = MusicService.get();
        if (s != null) {
            s.setWaveListener(null);
        }
        mHandler.removeCallbacks(mTick);
    }

    @Override
    public void onStateChanged() {
        final MusicService s = MusicService.get();
        if (s == null) {
            return;
        }
        s.setWaveListener(mWave);
        final Library.Track t = s.current();
        if (t == null) {
            return;
        }
        mTitle.setText(t.title);
        mArtist.setText(t.stream ? getString(R.string.stream) + " · " + t.artist : t.artist);
        mFormat.setText(s.isPreparing() ? getString(R.string.buffering) : s.format());
        mPlay.setImageResource(s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        mPlay.setContentDescription(getString(s.isPlaying() ? R.string.pause : R.string.play));
        mShuffle.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(
                s.shuffle() ? R.color.kiro_accent : R.color.kiro_text_secondary)));
        mRepeat.setImageResource(s.repeat() == MusicService.REPEAT_ONE ? R.drawable.ic_repeat_one
                : R.drawable.ic_repeat);
        mRepeat.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(
                s.repeat() != MusicService.REPEAT_OFF ? R.color.kiro_accent
                        : R.color.kiro_text_secondary)));
        final long albumKey = t.stream ? -1 : t.albumId;
        if (albumKey != mShownAlbum) {
            mShownAlbum = albumKey;
            final Bitmap art = t.stream ? null : Library.art(this, t.albumId, 1024);
            if (art != null) {
                mArt.setImageBitmap(art);
                mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                mArt.setImageResource(t.stream ? R.drawable.ic_globe : R.drawable.ic_music);
                mArt.setScaleType(ImageView.ScaleType.CENTER);
            }
            KiroTouch.enter(mArt);
        }
        final boolean seekable = !t.stream;
        mSeek.setEnabled(seekable);
        updatePosition();
        updateSleep();
    }

    // --- sleep timer (1.2) --------------------------------------------------------------------

    private void updateSleep() {
        final MusicService s = MusicService.get();
        final long left = s != null ? s.sleepRemaining() : 0;
        mSleep.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(
                left != 0 ? R.color.kiro_accent : R.color.kiro_text)));
        mSleep.setContentDescription(left == 0 ? getString(R.string.sleep_timer)
                : left < 0 ? getString(R.string.sleep_left_song)
                : getString(R.string.sleep_left, minutesLeft(left)));
    }

    private static int minutesLeft(long ms) {
        return (int) Math.max(1, (ms + 59999) / 60000);
    }

    private void showSleepTimer() {
        final MusicService s = MusicService.get();
        if (s == null) {
            return;
        }
        final int[] minutes = {15, 30, 45, 60, -1};
        final long left = s.sleepRemaining();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (int m : minutes) {
            labels.add(m > 0 ? getString(R.string.minutes_short, m) : getString(R.string.sleep_end_of_song));
        }
        if (left != 0) {
            labels.add(getString(R.string.sleep_off));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(left == 0 ? getString(R.string.sleep_timer)
                        : left < 0 ? getString(R.string.sleep_left_song)
                        : getString(R.string.sleep_left, minutesLeft(left)))
                .setItems(labels.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                final MusicService svc = MusicService.get();
                                if (svc == null) {
                                    return;
                                }
                                if (which >= minutes.length) {
                                    svc.setSleepTimer(0);
                                    return;
                                }
                                final int m = minutes[which];
                                svc.setSleepTimer(m);
                                android.widget.Toast.makeText(NowPlayingActivity.this,
                                        m > 0 ? getString(R.string.sleep_set, m)
                                                : getString(R.string.sleep_set_song),
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        })
                .show();
    }

    private void updatePosition() {
        final MusicService s = MusicService.get();
        if (s == null || mTracking) {
            return;
        }
        final long pos = s.position();
        final long dur = s.duration();
        mSeek.setMax((int) Math.max(1, dur));
        mSeek.setProgress((int) Math.min(pos, dur));
        mElapsed.setText(DateUtils.formatElapsedTime(pos / 1000));
        mTotal.setText(dur > 0 ? DateUtils.formatElapsedTime(dur / 1000) : getString(R.string.live));
    }
}
