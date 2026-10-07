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

package com.kiroos.applock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The private vault (Vault.java): after the fingerprint or the screen lock, the encrypted photos,
 * videos and files, with a viewer, "Save a copy" and delete. Files come in from the picker (the
 * originals can then be removed) or shared from any app. The window is FLAG_SECURE: no
 * screenshots, no picture in Recents; the vault locks when the screen goes off or the user leaves.
 */
public class VaultActivity extends Activity implements Auth.Callback {

    private static final String TAG = "KiroVault";
    private static final int REQ_ADD = 1, REQ_EXPORT = 2;

    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final Handler mMain = new Handler();
    private Vault mVault;
    private Auth mAuth;
    private View mAuthPanel, mContent, mViewer, mNoLock, mLost;
    private GridView mGrid;
    private TextView mEmpty, mProgress, mViewerName;
    private ImageView mImage;
    private VideoView mVideo;
    private final List<Vault.Item> mShown = new ArrayList<>();
    private final Set<String> mThumbRequested = new HashSet<>();
    private final LruCache<String, Bitmap> mThumbs = new LruCache<String, Bitmap>(12 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** a picker of ours is on top: leaving for it does not lock the vault */
    private boolean mInPicker;
    private boolean mOpening;
    /** the UI's own view of it: the vault itself locks on the IO thread, after pending work */
    private boolean mUnlocked;
    private List<Uri> mPendingImport;
    private Vault.Item mViewing, mExporting;
    private File mVideoFile;

    private final BroadcastReceiver mScreenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lock();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_vault);
        mAuthPanel = findViewById(R.id.root);
        mContent = findViewById(R.id.content);
        mViewer = findViewById(R.id.viewer);
        mNoLock = findViewById(R.id.nolock_panel);
        mLost = findViewById(R.id.lost_panel);
        mGrid = (GridView) findViewById(R.id.grid);
        mEmpty = (TextView) findViewById(R.id.empty);
        mProgress = (TextView) findViewById(R.id.progress);
        mViewerName = (TextView) findViewById(R.id.viewer_name);
        mImage = (ImageView) findViewById(R.id.image);
        mVideo = (VideoView) findViewById(R.id.video);
        mVault = new Vault(this);
        mAuth = new Auth(this, mAuthPanel, this);

        findViewById(R.id.set_lock).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD));
            }
        });
        findViewById(R.id.add).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick();
            }
        });
        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        findViewById(R.id.viewer_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeViewer();
            }
        });
        findViewById(R.id.viewer_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mViewing != null) export(mViewing);
            }
        });
        findViewById(R.id.viewer_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mViewing != null) confirmDelete(mViewing);
            }
        });
        mGrid.setAdapter(new Adapter());
        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                view(mShown.get(position));
            }
        });
        mGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                    long id) {
                actions(mShown.get(position));
                return true;
            }
        });
        registerReceiver(mScreenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        mPendingImport = shared(getIntent());
        new File(getCacheDir(), "vault_view").delete();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final List<Uri> uris = shared(intent);
        if (uris == null) return;
        if (mUnlocked) {
            importUris(uris, false);
        } else {
            mPendingImport = uris;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mInPicker = false;
        if (!mAuth.secure()) {
            show(mNoLock);
            return;
        }
        if (mUnlocked) {
            show(mViewing != null ? mViewer : mContent);
            return;
        }
        if (mOpening) return;
        if (Vault.recentlyAuthenticated()) {
            // the fingerprint was just given in App Lock
            onAuthenticated();
            return;
        }
        show(mAuthPanel);
        mAuth.start();
    }

    @Override
    protected void onPause() {
        mAuth.stop();
        if (mVideo.isPlaying()) mVideo.pause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mInPicker && !isChangingConfigurations()) lock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mScreenOff);
        lock();
        mIo.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (mViewer.getVisibility() == View.VISIBLE) {
            closeViewer();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mAuth.onActivityResult(requestCode, resultCode)) return;
        mInPicker = false;
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_ADD) {
            final List<Uri> uris = new ArrayList<>();
            final ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importUris(uris, true);
        } else if (requestCode == REQ_EXPORT && mExporting != null) {
            final Vault.Item it = mExporting;
            final Uri to = data.getData();
            mExporting = null;
            mIo.execute(new Runnable() {
                @Override
                public void run() {
                    boolean ok = false;
                    try (OutputStream out = getContentResolver().openOutputStream(to)) {
                        mVault.read(it, out);
                        ok = true;
                    } catch (Exception e) {
                        Log.w(TAG, "export: " + e);
                    }
                    toastLater(ok ? R.string.vault_saved : R.string.vault_file_failed);
                }
            });
        }
    }

    // ---- unlock

    @Override
    public void onAuthenticated() {
        if (!mAuth.secure() || mOpening) return;
        Vault.authenticated();
        mOpening = true;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                Exception error = null;
                try {
                    mVault.open();
                } catch (Exception e) {
                    error = e;
                    Log.w(TAG, "open: " + e);
                }
                final Exception err = error;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mOpening = false;
                        if (isDestroyed()) return;
                        if (err == null) {
                            mUnlocked = true;
                            show(mContent);
                            refresh();
                            if (mPendingImport != null) {
                                importUris(mPendingImport, false);
                                mPendingImport = null;
                            }
                        } else if (err instanceof Vault.LostKeyException) {
                            show(mLost);
                        } else if (err instanceof UserNotAuthenticatedException) {
                            // the proof expired on the way: ask again
                            show(mAuthPanel);
                            mAuth.start();
                        } else {
                            Toast.makeText(VaultActivity.this, R.string.vault_open_failed,
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    }
                });
            }
        });
    }

    private void lock() {
        mUnlocked = false;
        closeViewer();
        mShown.clear();
        mThumbs.evictAll();
        mThumbRequested.clear();
        ((BaseAdapter) mGrid.getAdapter()).notifyDataSetChanged();
        // after whatever the vault is still doing
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                mVault.lock();
            }
        });
    }

    private void show(View panel) {
        for (View v : new View[] {mAuthPanel, mContent, mViewer, mNoLock, mLost}) {
            v.setVisibility(v == panel ? View.VISIBLE : View.GONE);
        }
        if (panel == mViewer) mContent.setVisibility(View.VISIBLE);
    }

    private void refresh() {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                final List<Vault.Item> now = new ArrayList<>(mVault.items);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mShown.clear();
                        mShown.addAll(now);
                        ((BaseAdapter) mGrid.getAdapter()).notifyDataSetChanged();
                        mEmpty.setVisibility(mShown.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    // ---- adding

    private static List<Uri> shared(Intent intent) {
        if (intent == null) return null;
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            final ArrayList<Parcelable> list =
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
            }
        }
        return uris.isEmpty() ? null : uris;
    }

    private void pick() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        mInPicker = true;
        startActivityForResult(i, REQ_ADD);
    }

    /** @param offerRemove picked here (not shared): the originals can be removed after */
    private void importUris(final List<Uri> uris, final boolean offerRemove) {
        mProgress.setVisibility(View.VISIBLE);
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                final List<Uri> added = new ArrayList<>();
                String failed = null;
                for (int i = 0; i < uris.size(); i++) {
                    final int n = i + 1;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            mProgress.setText(getString(R.string.vault_adding, n, uris.size()));
                        }
                    });
                    final Uri uri = uris.get(i);
                    String name = uri.getLastPathSegment();
                    try {
                        final ContentResolver cr = getContentResolver();
                        try (Cursor c = cr.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                                null, null, null)) {
                            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                                name = c.getString(0);
                            }
                        }
                        final String mime = cr.getType(uri);
                        final byte[] thumb = thumbnail(uri, mime);
                        try (InputStream in = cr.openInputStream(uri)) {
                            mVault.add(in, name, mime, thumb);
                        }
                        added.add(uri);
                    } catch (Exception e) {
                        Log.w(TAG, "add: " + e);
                        failed = name;
                    }
                }
                final String f = failed;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mProgress.setVisibility(View.GONE);
                        refresh();
                        if (f != null) {
                            Toast.makeText(VaultActivity.this, getString(R.string.vault_add_failed,
                                    f), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(VaultActivity.this, getString(R.string.vault_added,
                                    added.size()), Toast.LENGTH_SHORT).show();
                        }
                        if (offerRemove && !added.isEmpty()) offerRemove(added);
                    }
                });
            }
        });
    }

    /** the vault has its copies: the originals can go (the user's choice, asked each time) */
    private void offerRemove(final List<Uri> originals) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.vault_remove_title)
                .setMessage(getString(R.string.vault_remove_text, originals.size()))
                .setNegativeButton(R.string.vault_keep, null)
                .setPositiveButton(R.string.vault_remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mIo.execute(new Runnable() {
                            @Override
                            public void run() {
                                int removed = 0;
                                for (Uri u : originals) {
                                    try {
                                        if (DocumentsContract.deleteDocument(getContentResolver(),
                                                u)) {
                                            removed++;
                                        }
                                    } catch (Exception e) {
                                        Log.w(TAG, "remove original: " + e);
                                    }
                                }
                                final int r = removed;
                                mMain.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(VaultActivity.this, getString(
                                                R.string.vault_removed, r, originals.size()),
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .show();
    }

    /** a small JPEG for the grid: photos and videos (a frame); null for other files */
    private byte[] thumbnail(Uri uri, String mime) {
        if (mime == null) return null;
        Bitmap b = null;
        try {
            if (mime.startsWith("image/")) {
                final ContentResolver cr = getContentResolver();
                final BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = cr.openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                o.inSampleSize = sample(o.outWidth, o.outHeight, 320);
                o.inJustDecodeBounds = false;
                try (InputStream in = cr.openInputStream(uri)) {
                    b = BitmapFactory.decodeStream(in, null, o);
                }
                int orientation = ExifInterface.ORIENTATION_NORMAL;
                try (InputStream in = cr.openInputStream(uri)) {
                    orientation = new ExifInterface(in).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                } catch (Exception e) {
                    // no EXIF
                }
                b = rotate(b, orientation);
            } else if (mime.startsWith("video/")) {
                final MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(this, uri);
                    final Bitmap frame = r.getFrameAtTime(1000000);
                    if (frame != null) {
                        final float s = 320f / Math.max(frame.getWidth(), frame.getHeight());
                        b = s < 1 ? Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth()
                                * s), Math.round(frame.getHeight() * s), true) : frame;
                    }
                } finally {
                    r.release();
                }
            }
            if (b == null) return null;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "thumbnail: " + e);
            return null;
        }
    }

    private static int sample(int w, int h, int target) {
        int s = 1;
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2;
        return s;
    }

    private static Bitmap rotate(Bitmap b, int orientation) {
        if (b == null) return null;
        final int deg = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (deg == 0) return b;
        final Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    // ---- viewing

    private void view(final Vault.Item it) {
        final boolean image = it.mime.startsWith("image/");
        final boolean video = it.mime.startsWith("video/");
        if (!image && !video) {
            actions(it);
            return;
        }
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setText(it.name);
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = null;
                File file = null;
                try {
                    if (image) {
                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        mVault.read(it, out);
                        final byte[] bytes = out.toByteArray();
                        final BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        o.inSampleSize = sample(o.outWidth, o.outHeight,
                                getResources().getDisplayMetrics().widthPixels);
                        o.inJustDecodeBounds = false;
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                        bitmap = rotate(bitmap, new ExifInterface(new ByteArrayInputStream(bytes))
                                .getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                        ExifInterface.ORIENTATION_NORMAL));
                    } else {
                        // the player needs a file: a private one in the cache, deleted on close
                        file = new File(getCacheDir(), "vault_view");
                        try (OutputStream out = new FileOutputStream(file)) {
                            mVault.read(it, out);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "view: " + e);
                    if (file != null) file.delete();
                    file = null;
                }
                final Bitmap b = bitmap;
                final File f = file;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mProgress.setVisibility(View.GONE);
                        if (!mUnlocked || isDestroyed()) {
                            if (f != null) f.delete();
                            return;
                        }
                        if (b == null && f == null) {
                            Toast.makeText(VaultActivity.this, R.string.vault_file_failed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        mViewing = it;
                        mViewerName.setText(it.name);
                        show(mViewer);
                        if (b != null) {
                            mVideo.setVisibility(View.GONE);
                            mImage.setVisibility(View.VISIBLE);
                            mImage.setImageBitmap(b);
                        } else {
                            mVideoFile = f;
                            mImage.setVisibility(View.GONE);
                            mVideo.setVisibility(View.VISIBLE);
                            final MediaController mc = new MediaController(VaultActivity.this);
                            mc.setAnchorView(mVideo);
                            mVideo.setMediaController(mc);
                            mVideo.setVideoPath(f.getPath());
                            mVideo.start();
                        }
                    }
                });
            }
        });
    }

    private void closeViewer() {
        mVideo.stopPlayback();
        mVideo.setVisibility(View.GONE);
        mImage.setImageDrawable(null);
        if (mVideoFile != null) {
            mVideoFile.delete();
            mVideoFile = null;
        }
        mViewing = null;
        if (mViewer.getVisibility() == View.VISIBLE) show(mContent);
    }

    private void actions(final Vault.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(new CharSequence[] {getString(R.string.vault_save_copy),
                        getString(R.string.vault_delete)}, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 0) export(it);
                                else confirmDelete(it);
                            }
                        })
                .show();
    }

    private void export(Vault.Item it) {
        mExporting = it;
        mInPicker = true;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(it.mime)
                .putExtra(Intent.EXTRA_TITLE, it.name), REQ_EXPORT);
    }

    private void confirmDelete(final Vault.Item it) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.vault_delete_confirm, it.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vault_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (it == mViewing) closeViewer();
                        mIo.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    mVault.delete(it);
                                } catch (Exception e) {
                                    Log.w(TAG, "delete: " + e);
                                }
                            }
                        });
                        mThumbs.remove(it.id);
                        refresh();
                    }
                })
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.vault_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.vault_reset, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mIo.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    mVault.reset();
                                } catch (Exception e) {
                                    Log.w(TAG, "reset: " + e);
                                }
                                mMain.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // a new, empty vault (the user just proved who they are)
                                        onAuthenticated();
                                    }
                                });
                            }
                        });
                    }
                })
                .show();
    }

    private void toastLater(final int text) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(VaultActivity.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- grid

    private void loadThumb(final Vault.Item it) {
        if (!it.thumb || !mThumbRequested.add(it.id)) return;
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    final byte[] jpeg = mVault.thumb(it);
                    if (jpeg != null) b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                } catch (Exception e) {
                    Log.w(TAG, "thumb: " + e);
                }
                final Bitmap bitmap = b;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bitmap == null || !mUnlocked) return;
                        mThumbs.put(it.id, bitmap);
                        ((BaseAdapter) mGrid.getAdapter()).notifyDataSetChanged();
                    }
                });
            }
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mShown.size();
        }

        @Override
        public Vault.Item getItem(int position) {
            return mShown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    parent.getContext()).inflate(R.layout.item_vault, parent, false);
            // square cells
            final int side = mGrid.getColumnWidth();
            if (side > 0 && v.getLayoutParams().height != side) {
                v.getLayoutParams().height = side;
            }
            final Vault.Item it = getItem(position);
            final ImageView thumb = (ImageView) v.findViewById(R.id.thumb);
            final TextView name = (TextView) v.findViewById(R.id.name);
            final View play = v.findViewById(R.id.play);
            final Bitmap b = mThumbs.get(it.id);
            if (b != null) {
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setImageBitmap(b);
            } else {
                thumb.setScaleType(ImageView.ScaleType.CENTER);
                thumb.setImageResource(R.drawable.ic_file);
                loadThumb(it);
            }
            name.setVisibility(b == null ? View.VISIBLE : View.GONE);
            name.setText(it.name);
            play.setVisibility(it.mime.startsWith("video/") ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
