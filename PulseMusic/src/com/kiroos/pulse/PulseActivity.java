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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pulse Music library: Songs, Albums, Artists and Streams (web radios / direct links the
 * user adds), with a mini player at the bottom.
 */
public class PulseActivity extends Activity implements MusicService.Listener {

    private static final int TAB_SONGS = 0;
    private static final int TAB_ALBUMS = 1;
    private static final int TAB_ARTISTS = 2;
    private static final int TAB_STREAMS = 3;
    private static final String SEP = "";

    private final RowAdapter mAdapter = new RowAdapter();
    private List<Library.Track> mSongs = Collections.emptyList();
    private TextView[] mTabs;
    private int mTab;
    /** album / artist opened from its tab */
    private String mFilterTitle;
    private List<Library.Track> mFiltered;
    private TextView mTitle;
    private TextView mEmpty;
    private View mStreamBox;
    private View mMini;
    private SharedPreferences mPrefs;

    /** a list row: a track, a group (album / artist) or a saved stream */
    private static final class Row {
        Library.Track track;
        Library.Group group;
        String[] stream; // {name, url}
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pulse);
        mPrefs = getSharedPreferences("pulse", MODE_PRIVATE);
        mTitle = (TextView) findViewById(R.id.title);
        mEmpty = (TextView) findViewById(R.id.empty);
        mStreamBox = findViewById(R.id.stream_box);
        mMini = findViewById(R.id.mini_player);
        mTabs = new TextView[] {(TextView) findViewById(R.id.tab_songs),
                (TextView) findViewById(R.id.tab_albums), (TextView) findViewById(R.id.tab_artists),
                (TextView) findViewById(R.id.tab_streams)};
        for (int i = 0; i < mTabs.length; i++) {
            final int tab = i;
            mTabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mFilterTitle = null;
                    showTab(tab);
                }
            });
        }
        final ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(mAdapter.getItem(position), position);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final Row r = mAdapter.getItem(position);
                if (r.stream != null) {
                    confirmRemoveStream(r.stream);
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.btn_eq).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PulseActivity.this, EqualizerActivity.class));
            }
        });
        final View playUrl = findViewById(R.id.stream_play);
        playUrl.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        playUrl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playUrl();
            }
        });
        mMini.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PulseActivity.this, NowPlayingActivity.class));
            }
        });
        findViewById(R.id.mini_toggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MusicService s = MusicService.get();
                if (s != null) {
                    s.toggle();
                }
            }
        });
        KiroTouch.apply(playUrl);
        showTab(mPrefs.getInt("tab", TAB_SONGS));
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        MusicService.addListener(this);
        load();
        onStateChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        MusicService.removeListener(this);
    }

    @Override
    public void onBackPressed() {
        if (mFilterTitle != null) {
            mFilterTitle = null;
            showTab(mTab);
            return;
        }
        super.onBackPressed();
    }

    private void load() {
        new AsyncTask<Void, Void, List<Library.Track>>() {
            @Override
            protected List<Library.Track> doInBackground(Void... params) {
                return Library.songs(PulseActivity.this, null, null);
            }

            @Override
            protected void onPostExecute(List<Library.Track> songs) {
                mSongs = songs;
                if (mFilterTitle == null) {
                    showTab(mTab);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showTab(int tab) {
        mTab = tab;
        mPrefs.edit().putInt("tab", tab).apply();
        for (int i = 0; i < mTabs.length; i++) {
            mTabs[i].setSelected(i == tab);
        }
        mTitle.setText(R.string.app_name);
        mStreamBox.setVisibility(tab == TAB_STREAMS ? View.VISIBLE : View.GONE);
        final List<Row> rows = new ArrayList<>();
        switch (tab) {
            case TAB_ALBUMS:
                for (Library.Group g : Library.albums(mSongs)) {
                    final Row r = new Row();
                    r.group = g;
                    rows.add(r);
                }
                break;
            case TAB_ARTISTS:
                for (Library.Group g : Library.artists(mSongs)) {
                    final Row r = new Row();
                    r.group = g;
                    rows.add(r);
                }
                break;
            case TAB_STREAMS:
                for (String[] s : streams()) {
                    final Row r = new Row();
                    r.stream = s;
                    rows.add(r);
                }
                break;
            default:
                for (Library.Track t : mSongs) {
                    final Row r = new Row();
                    r.track = t;
                    rows.add(r);
                }
                break;
        }
        mAdapter.set(rows);
        mEmpty.setText(tab == TAB_STREAMS ? R.string.no_streams : R.string.no_music);
        mEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onRowClick(Row r, int position) {
        if (r.track != null) {
            final List<Library.Track> queue = mFilterTitle != null ? mFiltered : mSongs;
            MusicService.play(this, queue, queue.indexOf(r.track));
            startActivity(new Intent(this, NowPlayingActivity.class));
        } else if (r.group != null) {
            final List<Library.Track> list = new ArrayList<>();
            for (Library.Track t : mSongs) {
                if (mTab == TAB_ALBUMS ? t.albumId == r.group.id
                        : t.artist.equalsIgnoreCase(r.group.name)) {
                    list.add(t);
                }
            }
            openGroup(r.group.name, list);
        } else if (r.stream != null) {
            MusicService.play(this, Collections.singletonList(
                    Library.Track.stream(r.stream[1], r.stream[0])), 0);
            startActivity(new Intent(this, NowPlayingActivity.class));
        }
    }

    private void openGroup(String title, List<Library.Track> tracks) {
        mFilterTitle = title;
        mFiltered = tracks;
        mTitle.setText(title);
        final List<Row> rows = new ArrayList<>();
        for (Library.Track t : tracks) {
            final Row r = new Row();
            r.track = t;
            rows.add(r);
        }
        mAdapter.set(rows);
        KiroTouch.enter(findViewById(R.id.list));
    }

    // --- streams --------------------------------------------------------------------------------

    private List<String[]> streams() {
        final List<String[]> out = new ArrayList<>();
        final String s = mPrefs.getString("streams", "");
        if (!s.isEmpty()) {
            final List<String> parts = Arrays.asList(s.split(SEP));
            for (int i = 0; i + 1 < parts.size(); i += 2) {
                out.add(new String[] {parts.get(i), parts.get(i + 1)});
            }
        }
        return out;
    }

    private void saveStreams(List<String[]> streams) {
        final List<String> flat = new ArrayList<>();
        for (String[] s : streams) {
            flat.add(s[0]);
            flat.add(s[1]);
        }
        mPrefs.edit().putString("streams", TextUtils.join(SEP, flat)).apply();
    }

    private void playUrl() {
        final EditText field = (EditText) findViewById(R.id.stream_url);
        String url = field.getText().toString().trim();
        if (url.isEmpty()) {
            return;
        }
        if (!url.contains("://")) {
            url = "http://" + url;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.bad_url, Toast.LENGTH_SHORT).show();
            return;
        }
        final String finalUrl = url;
        final EditText name = new EditText(this);
        name.setHint(R.string.stream_name);
        name.setText(android.net.Uri.parse(url).getHost());
        final LinearLayout box = new LinearLayout(this);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_stream)
                .setView(box)
                .setPositiveButton(R.string.save_and_play, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String n = name.getText().toString().trim();
                        final List<String[]> list = streams();
                        list.add(new String[] {n.isEmpty() ? finalUrl : n, finalUrl});
                        saveStreams(list);
                        field.setText("");
                        showTab(TAB_STREAMS);
                        start(n, finalUrl);
                    }
                })
                .setNeutralButton(R.string.just_play, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        start(name.getText().toString().trim(), finalUrl);
                    }
                })
                .show();
    }

    private void start(String name, String url) {
        MusicService.play(this, Collections.singletonList(
                Library.Track.stream(url, name.isEmpty() ? url : name)), 0);
        startActivity(new Intent(this, NowPlayingActivity.class));
    }

    private void confirmRemoveStream(final String[] stream) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.remove_stream, stream[0]))
                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final List<String[]> list = streams();
                        for (int i = 0; i < list.size(); i++) {
                            if (list.get(i)[1].equals(stream[1])) {
                                list.remove(i);
                                break;
                            }
                        }
                        saveStreams(list);
                        showTab(TAB_STREAMS);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- mini player ----------------------------------------------------------------------------

    @Override
    public void onStateChanged() {
        final MusicService s = MusicService.get();
        final Library.Track t = s != null ? s.current() : null;
        if (t == null) {
            mMini.setVisibility(View.GONE);
            return;
        }
        mMini.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.mini_title)).setText(t.title);
        ((TextView) findViewById(R.id.mini_artist)).setText(t.artist);
        ((ImageButton) findViewById(R.id.mini_toggle)).setImageResource(
                s.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        final ImageView art = (ImageView) findViewById(R.id.mini_art);
        final Bitmap b = Library.art(this, t.albumId, 128);
        if (b != null) {
            art.setImageBitmap(b);
        } else {
            art.setImageResource(t.stream ? R.drawable.ic_globe : R.drawable.ic_music);
        }
        mAdapter.notifyDataSetChanged();
    }

    // --- list -----------------------------------------------------------------------------------

    private final class RowAdapter extends BaseAdapter {
        private List<Row> mRows = Collections.emptyList();

        void set(List<Row> rows) {
            mRows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Row getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    PulseActivity.this).inflate(R.layout.item_track, parent, false);
            final Row r = mRows.get(position);
            final TextView title = (TextView) v.findViewById(R.id.title);
            final TextView sub = (TextView) v.findViewById(R.id.subtitle);
            final ImageView art = (ImageView) v.findViewById(R.id.art);
            art.setClipToOutline(true);
            long albumId = 0;
            if (r.track != null) {
                title.setText(r.track.title);
                sub.setText(r.track.artist + (r.track.duration > 0 ? " · "
                        + DateUtils.formatElapsedTime(r.track.duration / 1000) : ""));
                albumId = r.track.albumId;
                final MusicService s = MusicService.get();
                final boolean playing = s != null && s.current() != null && !s.current().stream
                        && s.current().id == r.track.id;
                title.setTextColor(getColor(playing ? R.color.kiro_accent : R.color.kiro_text));
            } else if (r.group != null) {
                title.setText(r.group.name.isEmpty() ? getString(R.string.unknown) : r.group.name);
                sub.setText(getResources().getQuantityString(R.plurals.songs, r.group.count,
                        r.group.count) + (r.group.detail != null && !r.group.detail.isEmpty()
                        ? " · " + r.group.detail : ""));
                albumId = r.group.artAlbumId;
                title.setTextColor(getColor(R.color.kiro_text));
            } else {
                title.setText(r.stream[0]);
                sub.setText(r.stream[1]);
                title.setTextColor(getColor(R.color.kiro_text));
            }
            final Bitmap b = albumId > 0 ? Library.art(PulseActivity.this, albumId, 128) : null;
            if (b != null) {
                art.setImageBitmap(b);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                art.setImageResource(r.stream != null ? R.drawable.ic_globe : R.drawable.ic_music);
                art.setScaleType(ImageView.ScaleType.CENTER);
            }
            return v;
        }
    }
}
