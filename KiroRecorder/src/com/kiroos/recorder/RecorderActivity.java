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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Kiro Recorder: record with a live level meter, then play, rename, share or delete.
 * 1.2: recording quality (High or Voice), and while a recording plays: 10 s back or
 * forward and the playback speed (0.75x to 2x, remembered).
 */
public class RecorderActivity extends Activity implements RecorderService.Listener {

    static final String PREFS = "recorder";
    static final String PREF_QUALITY = "quality";
    static final String QUALITY_VOICE = "voice";
    private static final String PREF_SPEED = "speed";
    private static final float[] SPEEDS = {1f, 1.25f, 1.5f, 2f, 0.75f};

    private static final class Recording {
        File file;
        long duration;
    }

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private android.content.SharedPreferences mPrefs;
    private TextView mQuality;
    private float mSpeed = 1f;
    private TextView mTime;
    private WaveformView mWave;
    private ImageButton mRecord;
    private ImageButton mPause;
    private TextView mEmpty;
    private MediaPlayer mPlayer;
    private File mPlaying;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (RecorderService.isRecording()) {
                mTime.setText(DateUtils.formatElapsedTime(RecorderService.elapsed() / 1000));
                mWave.push(RecorderService.amplitude());
                mHandler.postDelayed(this, 50);
            }
        }
    };

    private final Runnable mProgress = new Runnable() {
        @Override
        public void run() {
            if (mPlayer != null) {
                mAdapter.notifyDataSetChanged();
                mHandler.postDelayed(this, 250);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recorder);
        mTime = (TextView) findViewById(R.id.time);
        mWave = (WaveformView) findViewById(R.id.wave);
        mRecord = (ImageButton) findViewById(R.id.btn_record);
        mPause = (ImageButton) findViewById(R.id.btn_pause);
        mEmpty = (TextView) findViewById(R.id.empty);
        final ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                togglePlay(mAdapter.getItem(position).file);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showMenu(mAdapter.getItem(position), view);
                return true;
            }
        });
        mRecord.setBackground(SquircleDrawable.button(getColor(R.color.kiro_negative),
                getColor(R.color.kiro_ripple)));
        mPause.setBackground(SquircleDrawable.button(getColor(R.color.kiro_surface_raised),
                getColor(R.color.kiro_ripple)));
        mRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (RecorderService.isRecording()) {
                    RecorderService.send(RecorderActivity.this, RecorderService.ACTION_STOP);
                } else if (hasPermissions()) {
                    stopPlayback();
                    RecorderService.send(RecorderActivity.this, RecorderService.ACTION_START);
                } else {
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                }
            }
        });
        mPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RecorderService.send(RecorderActivity.this, RecorderService.isPaused()
                        ? RecorderService.ACTION_RESUME : RecorderService.ACTION_PAUSE);
            }
        });
        KiroTouch.apply(mRecord, mPause);

        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mSpeed = mPrefs.getFloat(PREF_SPEED, 1f);
        mQuality = (TextView) findViewById(R.id.quality);
        mQuality.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (RecorderService.isRecording()) {
                    return; // the quality of a recording in progress can't change
                }
                final boolean voice = !isVoice();
                mPrefs.edit().putString(PREF_QUALITY, voice ? QUALITY_VOICE : "high").apply();
                updateQuality();
                Toast.makeText(RecorderActivity.this, voice ? R.string.quality_voice_info
                        : R.string.quality_high_info, Toast.LENGTH_SHORT).show();
            }
        });
        KiroTouch.apply(mQuality);
        updateQuality();
    }

    private boolean isVoice() {
        return QUALITY_VOICE.equals(mPrefs.getString(PREF_QUALITY, null));
    }

    private void updateQuality() {
        mQuality.setText(isVoice() ? R.string.quality_voice : R.string.quality_high);
        mQuality.setAlpha(RecorderService.isRecording() ? 0.4f : 1f);
    }

    private static String speedText(float s) {
        final String n = s == Math.rint(s) ? String.valueOf((int) s) : String.valueOf(s);
        return n + "×";
    }

    /** the speed applies while playing (setting it on a paused player would start it) */
    private void applySpeed() {
        if (mPlayer == null || !mPlayer.isPlaying()) {
            return;
        }
        try {
            mPlayer.setPlaybackParams(mPlayer.getPlaybackParams().setSpeed(mSpeed));
        } catch (RuntimeException e) {
            // a speed the decoder can't do: stays at the current one
        }
    }

    private void cycleSpeed() {
        int i = 0;
        while (i < SPEEDS.length && Math.abs(SPEEDS[i] - mSpeed) > 0.01f) {
            i++;
        }
        mSpeed = SPEEDS[(i + 1) % SPEEDS.length];
        mPrefs.edit().putFloat(PREF_SPEED, mSpeed).apply();
        applySpeed();
        mAdapter.notifyDataSetChanged();
    }

    private void seekBy(int ms) {
        if (mPlayer == null) {
            return;
        }
        final int to = Math.max(0, Math.min(mPlayer.getDuration() - 200,
                mPlayer.getCurrentPosition() + ms));
        mPlayer.seekTo(to);
        mAdapter.notifyDataSetChanged();
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (hasPermissions()) {
            RecorderService.send(this, RecorderService.ACTION_START);
        } else {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        RecorderService.sListener = this;
        onRecorderStateChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        RecorderService.sListener = null;
        mHandler.removeCallbacks(mTick);
        stopPlayback();
    }

    @Override
    public void onRecorderStateChanged() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                final boolean rec = RecorderService.isRecording();
                mRecord.setImageResource(rec ? R.drawable.ic_stop : R.drawable.ic_mic);
                mRecord.setContentDescription(getString(rec ? R.string.stop : R.string.record));
                mPause.setVisibility(rec ? View.VISIBLE : View.INVISIBLE);
                mPause.setImageResource(RecorderService.isPaused() ? R.drawable.ic_play
                        : R.drawable.ic_pause);
                mPause.setContentDescription(getString(RecorderService.isPaused()
                        ? R.string.resume : R.string.pause));
                mHandler.removeCallbacks(mTick);
                updateQuality();
                if (rec) {
                    mHandler.post(mTick);
                } else {
                    mTime.setText(DateUtils.formatElapsedTime(0));
                    mWave.clear();
                    load();
                }
            }
        });
    }

    private void load() {
        new AsyncTask<Void, Void, List<Recording>>() {
            @Override
            protected List<Recording> doInBackground(Void... params) {
                final List<Recording> out = new ArrayList<>();
                final File[] files = RecorderService.directory().listFiles();
                if (files == null) {
                    return out;
                }
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(b.lastModified(), a.lastModified());
                    }
                });
                final MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                for (File f : files) {
                    if (!f.isFile()) {
                        continue;
                    }
                    final Recording r = new Recording();
                    r.file = f;
                    try {
                        mmr.setDataSource(f.getAbsolutePath());
                        final String d = mmr.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_DURATION);
                        r.duration = d != null ? Long.parseLong(d) : 0;
                    } catch (RuntimeException e) {
                        r.duration = 0;
                    }
                    out.add(r);
                }
                mmr.release();
                return out;
            }

            @Override
            protected void onPostExecute(List<Recording> list) {
                mAdapter.set(list);
                mEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // --- playback -------------------------------------------------------------------------

    private void togglePlay(File f) {
        if (RecorderService.isRecording()) {
            return;
        }
        if (f.equals(mPlaying) && mPlayer != null) {
            if (mPlayer.isPlaying()) {
                mPlayer.pause();
            } else {
                mPlayer.start();
                applySpeed();
                mHandler.post(mProgress);
            }
            mAdapter.notifyDataSetChanged();
            return;
        }
        stopPlayback();
        try {
            mPlayer = new MediaPlayer();
            mPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mPlayer.setDataSource(f.getAbsolutePath());
            mPlayer.prepare();
            mPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopPlayback();
                }
            });
            mPlayer.start();
            mPlaying = f;
            applySpeed();
            mHandler.post(mProgress);
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, R.string.cannot_play, Toast.LENGTH_SHORT).show();
        }
        mAdapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        mHandler.removeCallbacks(mProgress);
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
        mPlaying = null;
        mAdapter.notifyDataSetChanged();
    }

    // --- actions --------------------------------------------------------------------------

    private void showMenu(final Recording r, View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.share);
        menu.getMenu().add(0, 2, 0, R.string.rename);
        menu.getMenu().add(0, 3, 0, R.string.delete);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        share(r.file);
                        return true;
                    case 2:
                        rename(r.file);
                        return true;
                    case 3:
                        delete(r.file);
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void share(File f) {
        MediaScannerConnection.scanFile(this, new String[] {f.getAbsolutePath()},
                new String[] {"audio/mp4"}, new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, final Uri uri) {
                        if (uri == null) {
                            return;
                        }
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                getString(R.string.share)));
                    }
                });
    }

    private void rename(final File f) {
        final EditText field = new EditText(this);
        final String base = f.getName().replaceFirst("\\.m4a$", "");
        field.setText(base);
        field.setSelectAllOnFocus(true);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        final android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String name = field.getText().toString().trim()
                                .replaceAll("[\\\\/:*?\"<>|]", "_");
                        if (name.isEmpty()) {
                            return;
                        }
                        final File to = new File(f.getParentFile(), name + ".m4a");
                        if (to.exists() || !f.renameTo(to)) {
                            Toast.makeText(RecorderActivity.this, R.string.rename_failed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        MediaScannerConnection.scanFile(RecorderActivity.this,
                                new String[] {f.getAbsolutePath(), to.getAbsolutePath()}, null, null);
                        load();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void delete(final File f) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, f.getName()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (f.equals(mPlaying)) {
                            stopPlayback();
                        }
                        f.delete();
                        MediaScannerConnection.scanFile(RecorderActivity.this,
                                new String[] {f.getAbsolutePath()}, null, null);
                        load();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private final class Adapter extends BaseAdapter {
        private List<Recording> mItems = Collections.emptyList();

        void set(List<Recording> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Recording getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    RecorderActivity.this).inflate(R.layout.item_recording, parent, false);
            final Recording r = mItems.get(position);
            final boolean current = r.file.equals(mPlaying) && mPlayer != null;
            ((TextView) v.findViewById(R.id.name)).setText(r.file.getName()
                    .replaceFirst("\\.m4a$", ""));
            ((TextView) v.findViewById(R.id.info)).setText(
                    DateUtils.formatElapsedTime(r.duration / 1000) + " · "
                            + Formatter.formatShortFileSize(RecorderActivity.this, r.file.length())
                            + " · " + DateUtils.getRelativeTimeSpanString(r.file.lastModified()));
            ((ImageView) v.findViewById(R.id.play)).setImageResource(
                    current && mPlayer.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
            final SeekBar bar = (SeekBar) v.findViewById(R.id.progress);
            bar.setVisibility(current ? View.VISIBLE : View.GONE);
            final View controls = v.findViewById(R.id.controls);
            controls.setVisibility(current ? View.VISIBLE : View.GONE);
            if (current) {
                final TextView speed = (TextView) v.findViewById(R.id.speed);
                speed.setText(speedText(mSpeed));
                speed.setContentDescription(getString(R.string.speed) + " " + speedText(mSpeed));
                speed.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        cycleSpeed();
                    }
                });
                v.findViewById(R.id.back10).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        seekBy(-10000);
                    }
                });
                v.findViewById(R.id.fwd10).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        seekBy(10000);
                    }
                });
                ((TextView) v.findViewById(R.id.position)).setText(
                        DateUtils.formatElapsedTime(mPlayer.getCurrentPosition() / 1000) + " / "
                                + DateUtils.formatElapsedTime(mPlayer.getDuration() / 1000));
            }
            if (current) {
                bar.setMax(Math.max(1, mPlayer.getDuration()));
                bar.setProgress(mPlayer.getCurrentPosition());
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                        if (fromUser && mPlayer != null) {
                            mPlayer.seekTo(progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar s) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar s) {
                    }
                });
            } else {
                bar.setOnSeekBarChangeListener(null);
            }
            return v;
        }
    }
}
