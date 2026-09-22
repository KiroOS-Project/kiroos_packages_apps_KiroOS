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

package com.kiroos.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Kiro Camera: photos in JPEG at quality 100 and, on the rear camera, RAW (DNG) next to the
 * JPEG; video up to 4K. Tap to focus, pinch to zoom, flash, grid, volume keys as shutter.
 * Also answers IMAGE_CAPTURE / VIDEO_CAPTURE from other apps.
 */
public class CameraActivity extends Activity {

    private static final String TAG = "KiroCamera";

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_CAPTURING = 4;

    private static final int FLASH_OFF = 0;
    private static final int FLASH_AUTO = 1;
    private static final int FLASH_ON = 2;

    private static final String[] PERMISSIONS = {Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private CameraManager mManager;
    private SharedPreferences mPrefs;
    private PreviewView mPreview;
    private View mFlashOverlay;
    private View mGrid;
    private ImageView mThumb;
    private ImageButton mShutter;
    private ImageButton mFlashButton;
    private TextView mRawButton;
    private TextView mModePhoto;
    private TextView mModeVideo;
    private TextView mRecTime;
    private TextView mZoomText;
    private TextView m4kButton;

    private HandlerThread mThread;
    private Handler mCamHandler;
    private final Handler mMain = new Handler();
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private CaptureRequest.Builder mPreviewRequest;
    private ImageReader mJpegReader;
    private ImageReader mRawReader;
    private CameraCharacteristics mChars;
    private String mCameraId;
    private boolean mFront;
    private boolean mVideoMode;
    private int mFlash = FLASH_AUTO;
    private boolean mRawEnabled;
    private boolean mRawSupported;
    private boolean mFlashSupported;
    /** openCamera() called, onOpened/onError not yet */
    private volatile boolean mOpening;
    /** bumped by closeCamera(): opens that complete after it are closed at once */
    private volatile int mOpenGeneration;
    private int mState = STATE_PREVIEW;
    private long mCaptureStart;
    private int mDeviceRotation;
    private float mZoom = 1f;
    private Rect mActiveArray;
    private float mMaxZoom = 1f;
    private Size mPreviewSize;

    private MediaRecorder mRecorder;
    private File mVideoFile;
    private boolean mRecording;
    private long mRecordStart;
    private ParcelFileDescriptor mVideoPfd;
    /** orientation hint the current recorder was prepared with */
    private int mRecorderOrientation;
    /** start recording once the rebuilt session is configured */
    private volatile boolean mStartWhenReady;

    private MediaActionSound mSound;
    private OrientationEventListener mOrientation;
    private ScaleGestureDetector mScale;
    /** pending RAW frame + its result, written together */
    private Image mPendingRaw;
    private TotalCaptureResult mPendingResult;
    private int mPendingOrientation;

    /** type of what the thumbnail opens ("image/*" or "video/*") */
    private String mThumbMime = "image/*";

    /** IMAGE_CAPTURE / VIDEO_CAPTURE from another app */
    private boolean mCaptureIntent;
    private Uri mCaptureOutput;

    // --- lifecycle ------------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final String action = getIntent().getAction();
        if (MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE.equals(action)) {
            // from the lock screen: shoot without unlocking
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        setContentView(R.layout.activity_camera);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // controls clear of the status bar (top) and the navigation bar (bottom); the
        // preview itself stays edge to edge
        final View topBar = findViewById(R.id.top_bar);
        final View bottomBar = findViewById(R.id.bottom_bar);
        final int topPad = topBar.getPaddingTop();
        final int bottomPad = bottomBar.getPaddingBottom();
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        topBar.setPadding(topBar.getPaddingLeft(),
                                topPad + insets.getSystemWindowInsetTop(),
                                topBar.getPaddingRight(), topBar.getPaddingBottom());
                        bottomBar.setPadding(bottomBar.getPaddingLeft(),
                                bottomBar.getPaddingTop(), bottomBar.getPaddingRight(),
                                bottomPad + insets.getSystemWindowInsetBottom());
                        return insets.consumeSystemWindowInsets();
                    }
                });
        mManager = getSystemService(CameraManager.class);
        mPrefs = getSharedPreferences("camera", MODE_PRIVATE);
        mPreview = (PreviewView) findViewById(R.id.preview);
        mFlashOverlay = findViewById(R.id.flash_overlay);
        mThumb = (ImageView) findViewById(R.id.thumb);
        mThumb.setClipToOutline(true);
        mShutter = (ImageButton) findViewById(R.id.shutter);
        mFlashButton = (ImageButton) findViewById(R.id.btn_flash);
        mRawButton = (TextView) findViewById(R.id.btn_raw);
        mModePhoto = (TextView) findViewById(R.id.mode_photo);
        mModeVideo = (TextView) findViewById(R.id.mode_video);
        mRecTime = (TextView) findViewById(R.id.rec_time);
        mZoomText = (TextView) findViewById(R.id.zoom_text);
        m4kButton = (TextView) findViewById(R.id.btn_4k);

        mGrid = new PreviewView.Grid(this);
        ((FrameLayout) findViewById(R.id.preview_box)).addView(mGrid,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        mGrid.setVisibility(mPrefs.getBoolean("grid", false) ? View.VISIBLE : View.GONE);

        mFront = mPrefs.getBoolean("front", false);
        mFlash = mPrefs.getInt("flash", FLASH_AUTO);
        mRawEnabled = mPrefs.getBoolean("raw", false);
        mVideoMode = MediaStore.ACTION_VIDEO_CAPTURE.equals(action)
                || MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(action);
        mCaptureIntent = MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || MediaStore.ACTION_VIDEO_CAPTURE.equals(action);
        mCaptureOutput = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);

        mSound = new MediaActionSound();
        mSound.load(MediaActionSound.SHUTTER_CLICK);
        mOrientation = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (degrees == ORIENTATION_UNKNOWN) {
                    return;
                }
                final int r = ((degrees + 45) / 90 * 90) % 360;
                if (r != mDeviceRotation) {
                    mDeviceRotation = r;
                    rotateIcons(-r);
                }
            }
        };
        mScale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setZoom(mZoom * d.getScaleFactor());
                        return true;
                    }
                });
        // zoom only with two fingers: "double tap and drag" zoomed by accident when two
        // focus taps came close together
        mScale.setQuickScaleEnabled(false);
        setupControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOrientation.enable();
        startThread();
        if (!hasPermissions()) {
            requestPermissions(PERMISSIONS, 1);
            return;
        }
        loadLastThumb();
        if (mPreview.isAvailable()) {
            openCamera();
        } else {
            mPreview.setSurfaceTextureListener(mTextureListener);
        }
    }

    @Override
    protected void onPause() {
        if (mRecording) {
            stopRecording(false);
        }
        closeCamera();
        stopThread();
        mOrientation.disable();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mSound.release();
        super.onDestroy();
    }

    private boolean hasPermissions() {
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (mPreview.isAvailable()) {
            openCamera();
        } else {
            mPreview.setSurfaceTextureListener(mTextureListener);
        }
    }

    private void startThread() {
        mThread = new HandlerThread("KiroCamera");
        mThread.start();
        mCamHandler = new Handler(mThread.getLooper());
    }

    private void stopThread() {
        if (mThread != null) {
            mThread.quitSafely();
            try {
                mThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        mThread = null;
        mCamHandler = null;
    }

    private final TextureView.SurfaceTextureListener mTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
                    configureTransform(w, h);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture t) {
                }
            };

    // --- controls -------------------------------------------------------------------------------

    private void setupControls() {
        mShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mVideoMode) {
                    if (mRecording) {
                        stopRecording(true);
                    } else {
                        startRecording();
                    }
                } else {
                    takePicture();
                }
            }
        });
        KiroTouch.apply(mShutter);
        findViewById(R.id.btn_switch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRecording) {
                    return;
                }
                mFront = !mFront;
                mPrefs.edit().putBoolean("front", mFront).apply();
                v.animate().rotationBy(180).setDuration(250).start();
                reopen();
            }
        });
        mFlashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mFlash = (mFlash + 1) % 3;
                mPrefs.edit().putInt("flash", mFlash).apply();
                updateControls();
                updatePreview();
            }
        });
        mRawButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRawEnabled = !mRawEnabled;
                mPrefs.edit().putBoolean("raw", mRawEnabled).apply();
                Toast.makeText(CameraActivity.this, mRawEnabled ? R.string.raw_on : R.string.raw_off,
                        Toast.LENGTH_SHORT).show();
                reopen();
            }
        });
        m4kButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mRecording) {
                    return;
                }
                mPrefs.edit().putBoolean("uhd", !mPrefs.getBoolean("uhd", false)).apply();
                updateControls();
                // the recorder is prepared with the resolution: rebuild it
                reopen();
            }
        });
        findViewById(R.id.btn_grid).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean on = !mPrefs.getBoolean("grid", false);
                mPrefs.edit().putBoolean("grid", on).apply();
                mGrid.setVisibility(on ? View.VISIBLE : View.GONE);
            }
        });
        mModePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mVideoMode && !mRecording && !mCaptureIntent) {
                    mVideoMode = false;
                    updateControls();
                    reopen();
                }
            }
        });
        mModeVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mVideoMode && !mCaptureIntent) {
                    mVideoMode = true;
                    updateControls();
                    reopen();
                }
            }
        });
        mThumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final Object uri = mThumb.getTag();
                if (uri instanceof Uri) {
                    try {
                        startActivity(new Intent("com.android.camera.action.REVIEW")
                                .setDataAndType((Uri) uri, mThumbMime));
                    } catch (android.content.ActivityNotFoundException e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW)
                                    .setDataAndType((Uri) uri, mThumbMime));
                        } catch (android.content.ActivityNotFoundException e2) {
                            // no viewer installed
                        }
                    }
                }
            }
        });
        mPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                mScale.onTouchEvent(e);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getPointerCount() == 1
                        && !mScale.isInProgress() && e.getEventTime() - e.getDownTime() < 300) {
                    focusAt(e.getX(), e.getY());
                }
                return true;
            }
        });
        if (mCaptureIntent) {
            findViewById(R.id.modes).setVisibility(View.INVISIBLE);
            mThumb.setVisibility(View.INVISIBLE);
        }
        updateControls();
    }

    private void updateControls() {
        mModePhoto.setSelected(!mVideoMode);
        mModeVideo.setSelected(mVideoMode);
        mShutter.setImageResource(mVideoMode ? (mRecording ? R.drawable.shutter_stop
                : R.drawable.shutter_video) : R.drawable.shutter_photo);
        mFlashButton.setImageResource(mFlash == FLASH_OFF ? R.drawable.ic_flash_off
                : mFlash == FLASH_ON ? R.drawable.ic_flash_on : R.drawable.ic_flash_auto);
        mFlashButton.setVisibility(mVideoMode || !mFlashSupported ? View.INVISIBLE : View.VISIBLE);
        mRawButton.setVisibility(!mVideoMode && mRawSupported ? View.VISIBLE : View.GONE);
        mRawButton.setSelected(mRawEnabled);
        mRawButton.setTextColor(getColor(mRawEnabled ? R.color.kiro_on_accent : R.color.kiro_text));
        m4kButton.setVisibility(mVideoMode && supports4k() ? View.VISIBLE : View.GONE);
        m4kButton.setSelected(mPrefs.getBoolean("uhd", false));
        m4kButton.setTextColor(getColor(mPrefs.getBoolean("uhd", false) ? R.color.kiro_on_accent
                : R.color.kiro_text));
        mRecTime.setVisibility(mRecording ? View.VISIBLE : View.GONE);
    }

    private void rotateIcons(int degrees) {
        for (int id : new int[] {R.id.btn_switch, R.id.btn_flash, R.id.thumb, R.id.btn_grid}) {
            findViewById(id).animate().rotation(degrees).setDuration(200).start();
        }
    }

    private boolean supports4k() {
        try {
            return CamcorderProfile.hasProfile(Integer.parseInt(mCameraId),
                    CamcorderProfile.QUALITY_2160P);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_CAMERA) && event.getRepeatCount() == 0) {
            mShutter.performClick();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // --- camera ---------------------------------------------------------------------------------

    /**
     * New camera or new streams (photo ↔ video, RAW, 4K, orientation of the recorder): the
     * device is closed and reopened. The Exynos HAL3 of the S6 keeps its scaler (GSC) set up
     * for the first session, so a second session on an open device gives a green video
     * viewfinder on the front camera.
     */
    private void reopen() {
        closeCamera();
        updateControls();
        openCamera();
    }

    private String findCamera(boolean front) throws CameraAccessException {
        for (String id : mManager.getCameraIdList()) {
            final Integer facing = mManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && (facing == CameraCharacteristics.LENS_FACING_FRONT) == front) {
                return id;
            }
        }
        return mManager.getCameraIdList()[0];
    }

    private void openCamera() {
        if (mCamHandler == null || mDevice != null || mOpening) {
            return;
        }
        try {
            mCameraId = findCamera(mFront);
            mChars = mManager.getCameraCharacteristics(mCameraId);
            final int[] caps = mChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            mRawSupported = false;
            if (caps != null) {
                for (int c : caps) {
                    if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                        mRawSupported = true;
                    }
                }
            }
            final Boolean flash = mChars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            mFlashSupported = flash != null && flash;
            mActiveArray = mChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float maxZoom = mChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            mMaxZoom = maxZoom != null ? Math.min(8f, maxZoom) : 1f;
            mZoom = 1f;
            mZoomText.setVisibility(View.GONE);
            prepareOutputs();
            mOpening = true;
            final int generation = mOpenGeneration;
            mManager.openCamera(mCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    if (generation != mOpenGeneration) {
                        // closed (paused, switched camera or mode) while it was opening
                        device.close();
                        return;
                    }
                    mOpening = false;
                    mDevice = device;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    if (generation == mOpenGeneration) {
                        mOpening = false;
                        mDevice = null;
                    }
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    if (generation != mOpenGeneration) {
                        return;
                    }
                    mOpening = false;
                    mDevice = null;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(CameraActivity.this, R.string.camera_error,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }, mCamHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            mOpening = false;
            Log.w(TAG, "cannot open camera", e);
            Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show();
        }
    }

    /** The streams of the current mode: JPEG (+ RAW) readers for photos, the preview size. */
    private void prepareOutputs() {
        final StreamConfigurationMap map = mChars.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (!mVideoMode) {
            final Size jpeg = largest(map.getOutputSizes(ImageFormat.JPEG), 4, 3);
            mJpegReader = ImageReader.newInstance(jpeg.getWidth(), jpeg.getHeight(),
                    ImageFormat.JPEG, 2);
            mJpegReader.setOnImageAvailableListener(mOnJpeg, mCamHandler);
            if (mRawSupported && mRawEnabled) {
                final Size raw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR), 0, 0);
                mRawReader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(),
                        ImageFormat.RAW_SENSOR, 2);
                mRawReader.setOnImageAvailableListener(mOnRaw, mCamHandler);
            }
            mPreviewSize = choosePreview(map.getOutputSizes(SurfaceTexture.class), 4, 3, 1920);
        } else {
            // the encoder already takes a full-size stream: a 720p viewfinder keeps the
            // Exynos scaler (GSC) within its limits on both cameras
            mPreviewSize = choosePreview(map.getOutputSizes(SurfaceTexture.class), 16, 9, 1280);
        }
        // sensor is landscape; the phone is held in portrait
        final Size size = mPreviewSize;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mPreview.setAspectRatio(size.getHeight(), size.getWidth());
                configureTransform(mPreview.getWidth(), mPreview.getHeight());
                updateControls();
            }
        });
    }

    /** Session, readers and recorder; the device stays open. */
    private void closeSession() {
        if (mSession != null) {
            mSession.close();
            mSession = null;
        }
        releaseRecorder();
        if (mJpegReader != null) {
            mJpegReader.close();
            mJpegReader = null;
        }
        if (mRawReader != null) {
            mRawReader.close();
            mRawReader = null;
        }
        if (mPendingRaw != null) {
            mPendingRaw.close();
            mPendingRaw = null;
        }
        mState = STATE_PREVIEW;
    }

    private void closeCamera() {
        // an open still in flight belongs to the old generation and closes itself
        mOpenGeneration++;
        mOpening = false;
        closeSession();
        if (mDevice != null) {
            mDevice.close();
            mDevice = null;
        }
    }

    /** largest size with the given ratio (0 = any), by pixel count */
    private static Size largest(Size[] sizes, int rw, int rh) {
        final List<Size> list = new ArrayList<>();
        for (Size s : sizes) {
            if (rw == 0 || s.getWidth() * rh == s.getHeight() * rw) {
                list.add(s);
            }
        }
        if (list.isEmpty()) {
            list.addAll(Arrays.asList(sizes));
        }
        return Collections.max(list, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Long.signum((long) a.getWidth() * a.getHeight()
                        - (long) b.getWidth() * b.getHeight());
            }
        });
    }

    /** preview: the ratio of the photo / video, at most maxW wide (smooth, no waste) */
    private static Size choosePreview(Size[] sizes, int rw, int rh, int maxW) {
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() * rh == s.getHeight() * rw && s.getWidth() <= maxW
                    && (best == null || s.getWidth() > best.getWidth())) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    private void configureTransform(int viewW, int viewH) {
        if (mPreviewSize == null || viewW == 0) {
            return;
        }
        final Matrix m = new Matrix();
        final int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            final RectF view = new RectF(0, 0, viewW, viewH);
            final RectF buf = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
            buf.offset(view.centerX() - buf.centerX(), view.centerY() - buf.centerY());
            m.setRectToRect(view, buf, Matrix.ScaleToFit.FILL);
            final float scale = Math.max((float) viewH / mPreviewSize.getHeight(),
                    (float) viewW / mPreviewSize.getWidth());
            m.postScale(scale, scale, view.centerX(), view.centerY());
            m.postRotate(90 * (rotation - 2), view.centerX(), view.centerY());
        }
        mPreview.setTransform(m);
    }

    private void startSession() {
        try {
            final SurfaceTexture texture = mPreview.getSurfaceTexture();
            if (texture == null || mDevice == null) {
                return;
            }
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            final Surface preview = new Surface(texture);
            final List<Surface> outputs = new ArrayList<>();
            outputs.add(preview);
            mPreviewRequest = mDevice.createCaptureRequest(mVideoMode
                    ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequest.addTarget(preview);
            if (mVideoMode) {
                prepareRecorder();
                if (mRecorder != null) {
                    outputs.add(mRecorder.getSurface());
                    mPreviewRequest.addTarget(mRecorder.getSurface());
                }
            } else {
                outputs.add(mJpegReader.getSurface());
                if (mRawReader != null) {
                    outputs.add(mRawReader.getSurface());
                }
            }
            mDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (mDevice == null) {
                        return;
                    }
                    mSession = session;
                    updatePreview();
                    if (mStartWhenReady) {
                        mStartWhenReady = false;
                        mMain.post(new Runnable() {
                            @Override
                            public void run() {
                                startRecording();
                            }
                        });
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.w(TAG, "session configuration failed");
                }
            }, mCamHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "cannot start preview", e);
        }
    }

    private void applyCommon(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE, mVideoMode
                ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (mVideoMode || mFlash == FLASH_OFF || !mFlashSupported) {
            // (the front camera has no flash: an auto/always flash AE mode is not valid there)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
        } else if (mFlash == FLASH_ON) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
        if (mActiveArray != null && mZoom > 1f) {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(mZoom));
        }
    }

    private Rect cropFor(float zoom) {
        final int w = (int) (mActiveArray.width() / zoom);
        final int h = (int) (mActiveArray.height() / zoom);
        final int x = (mActiveArray.width() - w) / 2;
        final int y = (mActiveArray.height() - h) / 2;
        return new Rect(x, y, x + w, y + h);
    }

    private void updatePreview() {
        if (mSession == null || mPreviewRequest == null) {
            return;
        }
        try {
            applyCommon(mPreviewRequest);
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            mSession.setRepeatingRequest(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "preview request failed", e);
        }
    }

    private void setZoom(float zoom) {
        mZoom = Math.max(1f, Math.min(mMaxZoom, zoom));
        mZoomText.setText(String.format(java.util.Locale.US, "%.1f×", mZoom));
        mZoomText.setVisibility(mZoom > 1.01f ? View.VISIBLE : View.GONE);
        updatePreview();
    }

    /** tap to focus and meter on a point, back to continuous focus after 3 s */
    private void focusAt(float x, float y) {
        if (mSession == null || mActiveArray == null || mPreviewRequest == null) {
            return;
        }
        final Integer sensorOrientation = mChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // view (portrait) → sensor (landscape) coordinates
        float nx = x / mPreview.getWidth();
        float ny = y / mPreview.getHeight();
        float sx;
        float sy;
        if (sensorOrientation != null && sensorOrientation == 270) {
            sx = 1 - ny;
            sy = nx;
        } else {
            sx = ny;
            sy = 1 - nx;
        }
        final Rect crop = mZoom > 1f ? cropFor(mZoom) : mActiveArray;
        final int cx = crop.left + (int) (sx * crop.width());
        final int cy = crop.top + (int) (sy * crop.height());
        final int half = Math.min(crop.width(), crop.height()) / 12;
        final MeteringRectangle region = new MeteringRectangle(
                Math.max(0, cx - half), Math.max(0, cy - half), half * 2, half * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);
        try {
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] {region});
            mPreviewRequest.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] {region});
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mSession.capture(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            mSession.setRepeatingRequest(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "focus failed", e);
        }
        showFocusRing(x, y);
        mMain.removeCallbacks(mResumeContinuous);
        mMain.postDelayed(mResumeContinuous, 3000);
    }

    private final Runnable mResumeContinuous = new Runnable() {
        @Override
        public void run() {
            if (mPreviewRequest != null) {
                mPreviewRequest.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                mPreviewRequest.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                updatePreview();
            }
        }
    };

    private void showFocusRing(float x, float y) {
        final View ring = findViewById(R.id.focus_ring);
        ring.setX(mPreview.getX() + x - ring.getWidth() / 2f);
        ring.setY(mPreview.getY() + y - ring.getHeight() / 2f);
        ring.setScaleX(1.4f);
        ring.setScaleY(1.4f);
        ring.setAlpha(1f);
        ring.setVisibility(View.VISIBLE);
        ring.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction(new Runnable() {
            @Override
            public void run() {
                ring.animate().alpha(0f).setStartDelay(800).setDuration(300).start();
            }
        }).start();
    }

    // --- still capture ---------------------------------------------------------------------------

    private void takePicture() {
        if (mSession == null || mState != STATE_PREVIEW) {
            return;
        }
        try {
            mCaptureStart = SystemClock.elapsedRealtime();
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            mState = STATE_WAITING_LOCK;
            mSession.capture(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException e) {
            mState = STATE_PREVIEW;
            Log.w(TAG, "focus lock failed", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult r) {
                    final Integer af = r.get(CaptureResult.CONTROL_AF_STATE);
                    final Integer ae = r.get(CaptureResult.CONTROL_AE_STATE);
                    final boolean timeout = SystemClock.elapsedRealtime() - mCaptureStart > 1500;
                    switch (mState) {
                        case STATE_WAITING_LOCK:
                            if (af == null || timeout
                                    || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                    || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                                    || af == CaptureResult.CONTROL_AF_STATE_INACTIVE) {
                                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                                        || timeout) {
                                    captureStill();
                                } else {
                                    runPrecapture();
                                }
                            }
                            break;
                        case STATE_WAITING_PRECAPTURE:
                            if (ae == null || timeout
                                    || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                    || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                mState = STATE_WAITING_NON_PRECAPTURE;
                            }
                            break;
                        case STATE_WAITING_NON_PRECAPTURE:
                            if (ae == null || timeout
                                    || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                captureStill();
                            }
                            break;
                        default:
                            break;
                    }
                }

                @Override
                public void onCaptureProgressed(CameraCaptureSession s, CaptureRequest q,
                        CaptureResult r) {
                    process(r);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest q,
                        TotalCaptureResult r) {
                    process(r);
                }
            };

    private void runPrecapture() {
        try {
            mPreviewRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mState = STATE_WAITING_PRECAPTURE;
            mSession.capture(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
            mPreviewRequest.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException e) {
            captureStill();
        }
    }

    private int jpegOrientation() {
        final Integer sensor = mChars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        final int s = sensor != null ? sensor : 90;
        return mFront ? (s - mDeviceRotation + 360) % 360 : (s + mDeviceRotation) % 360;
    }

    private void captureStill() {
        if (mState == STATE_CAPTURING || mDevice == null || mSession == null) {
            return;
        }
        mState = STATE_CAPTURING;
        try {
            final CaptureRequest.Builder b = mDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mJpegReader.getSurface());
            if (mRawReader != null) {
                b.addTarget(mRawReader.getSurface());
            }
            applyCommon(b);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            mPendingOrientation = jpegOrientation();
            mSession.stopRepeating();
            mSession.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession s, CaptureRequest q,
                        long timestamp, long frame) {
                    mSound.play(MediaActionSound.SHUTTER_CLICK);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            mFlashOverlay.setAlpha(0.8f);
                            mFlashOverlay.setVisibility(View.VISIBLE);
                            mFlashOverlay.animate().alpha(0f).setDuration(180).start();
                        }
                    });
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest q,
                        TotalCaptureResult r) {
                    synchronized (CameraActivity.this) {
                        mPendingResult = r;
                        writeRawIfReady();
                    }
                    unlockFocus();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest q,
                        android.hardware.camera2.CaptureFailure f) {
                    unlockFocus();
                }
            }, mCamHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "capture failed", e);
            unlockFocus();
        }
    }

    private void unlockFocus() {
        try {
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mSession.capture(mPreviewRequest.build(), mCaptureCallback, mCamHandler);
            mPreviewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
            // session closed
        }
        mState = STATE_PREVIEW;
        updatePreview();
    }

    private final ImageReader.OnImageAvailableListener mOnJpeg =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    final Image image = reader.acquireNextImage();
                    if (image == null) {
                        return;
                    }
                    final ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    final byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    image.close();
                    saveJpeg(bytes);
                }
            };

    private final ImageReader.OnImageAvailableListener mOnRaw =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    final Image image = reader.acquireNextImage();
                    if (image == null) {
                        return;
                    }
                    synchronized (CameraActivity.this) {
                        if (mPendingRaw != null) {
                            mPendingRaw.close();
                        }
                        mPendingRaw = image;
                        writeRawIfReady();
                    }
                }
            };

    /** RAW needs both the frame and its capture result (sensor metadata) for the DNG */
    private void writeRawIfReady() {
        if (mPendingRaw == null || mPendingResult == null) {
            return;
        }
        final Image raw = mPendingRaw;
        final TotalCaptureResult result = mPendingResult;
        mPendingRaw = null;
        mPendingResult = null;
        final File file = Storage.newFile("IMG", ".dng");
        try (OutputStream out = new FileOutputStream(file);
             DngCreator dng = new DngCreator(mChars, result)) {
            dng.setOrientation(exifOrientation(mPendingOrientation));
            dng.writeImage(out, raw);
            Storage.scan(this, file, "image/x-adobe-dng", null);
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "cannot write DNG", e);
            file.delete();
        } finally {
            raw.close();
        }
    }

    private static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private void saveJpeg(byte[] bytes) {
        if (mCaptureIntent) {
            deliverCapture(bytes);
            return;
        }
        final File file = Storage.newFile("IMG", ".jpg");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "cannot save photo", e);
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(CameraActivity.this, R.string.save_failed, Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;
        final Bitmap thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        Storage.scan(this, file, "image/jpeg", new Storage.Scanned() {
            @Override
            public void onScanned(final Uri uri) {
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        showThumb(thumb, uri, mPendingOrientation, "image/*");
                    }
                });
            }
        });
    }

    private void showThumb(Bitmap thumb, Uri uri, int rotation, String mime) {
        if (thumb == null) {
            return;
        }
        final Matrix m = new Matrix();
        m.postRotate(rotation);
        final Bitmap upright = Bitmap.createBitmap(thumb, 0, 0, thumb.getWidth(),
                thumb.getHeight(), m, true);
        mThumb.setImageBitmap(upright);
        mThumb.setTag(uri);
        mThumbMime = mime;
        mThumb.setScaleX(0.6f);
        mThumb.setScaleY(0.6f);
        mThumb.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(KiroTouch.EASE)
                .start();
    }

    /**
     * The newest photo or video of DCIM/Camera in the thumbnail, so it is never an empty
     * circle when the camera opens (runs off the main thread; also sweeps empty clips).
     */
    private void loadLastThumb() {
        if (mCaptureIntent || mThumb.getTag() != null) {
            return;
        }
        final android.content.ContentResolver cr = getContentResolver();
        new android.os.AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... params) {
                Storage.deleteEmptyClips();
                final Uri files = MediaStore.Files.getContentUri("external");
                try (android.database.Cursor c = cr.query(files,
                        new String[] {MediaStore.Files.FileColumns._ID,
                                MediaStore.Files.FileColumns.MEDIA_TYPE,
                                MediaStore.Images.ImageColumns.ORIENTATION},
                        "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                                + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?) AND "
                                + MediaStore.Files.FileColumns.DATA + " LIKE ? AND "
                                + MediaStore.Files.FileColumns.SIZE + ">0",
                        new String[] {
                                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
                                Storage.dir().getAbsolutePath() + "/%"},
                        MediaStore.Files.FileColumns.DATE_ADDED + " DESC")) {
                    if (c == null || !c.moveToFirst()) {
                        return null;
                    }
                    final long id = c.getLong(0);
                    final boolean video =
                            c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                    final Bitmap b = video
                            ? MediaStore.Video.Thumbnails.getThumbnail(cr, id,
                                    MediaStore.Video.Thumbnails.MINI_KIND, null)
                            : MediaStore.Images.Thumbnails.getThumbnail(cr, id,
                                    MediaStore.Images.Thumbnails.MINI_KIND, null);
                    final Uri uri = android.content.ContentUris.withAppendedId(video
                            ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    return new Object[] {b, uri, video ? 0 : c.getInt(2),
                            video ? "video/*" : "image/*"};
                } catch (RuntimeException e) {
                    Log.w(TAG, "no last photo", e);
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Object[] r) {
                if (r != null && !isDestroyed() && mThumb.getTag() == null) {
                    showThumb((Bitmap) r[0], (Uri) r[1], (Integer) r[2], (String) r[3]);
                }
            }
        }.executeOnExecutor(android.os.AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** IMAGE_CAPTURE: write to the caller's uri, or return a small bitmap */
    private void deliverCapture(byte[] bytes) {
        if (mCaptureOutput != null) {
            try (OutputStream out = getContentResolver().openOutputStream(mCaptureOutput)) {
                out.write(bytes);
                setResult(RESULT_OK, new Intent().setData(mCaptureOutput));
            } catch (IOException | SecurityException | NullPointerException e) {
                Log.w(TAG, "cannot write to " + mCaptureOutput, e);
                setResult(RESULT_CANCELED);
            }
        } else {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            final Bitmap small = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            setResult(RESULT_OK, new Intent("inline-data").putExtra("data", small));
        }
        finish();
    }

    // --- video ---------------------------------------------------------------------------------

    private void prepareRecorder() {
        releaseRecorder();
        final int cam = Integer.parseInt(mCameraId);
        int quality = CamcorderProfile.QUALITY_1080P;
        if (mPrefs.getBoolean("uhd", false) && supports4k()) {
            quality = CamcorderProfile.QUALITY_2160P;
        }
        if (!CamcorderProfile.hasProfile(cam, quality)) {
            quality = CamcorderProfile.QUALITY_HIGH;
        }
        final CamcorderProfile p = CamcorderProfile.get(cam, quality);
        mRecorder = new MediaRecorder();
        try {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            mRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mRecorder.setProfile(p);
            if (mCaptureIntent && mCaptureOutput != null) {
                mVideoPfd = getContentResolver().openFileDescriptor(mCaptureOutput, "rw");
                mRecorder.setOutputFile(mVideoPfd.getFileDescriptor());
            } else {
                mVideoFile = Storage.newFile("VID", ".mp4");
                mRecorder.setOutputFile(mVideoFile.getAbsolutePath());
            }
            mRecorderOrientation = jpegOrientation();
            mRecorder.setOrientationHint(mRecorderOrientation);
            mRecorder.prepare();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "recorder setup failed", e);
            releaseRecorder();
        }
    }

    private void startRecording() {
        if (mSession == null) {
            return; // still starting
        }
        if (mRecorder == null) {
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_LONG).show();
            return;
        }
        if (mRecorderOrientation != jpegOrientation()) {
            // the phone was turned since the recorder was prepared: its orientation hint
            // is fixed at prepare(), so rebuild it and start as soon as the session is ready
            mStartWhenReady = true;
            reopen();
            return;
        }
        try {
            mRecorder.start();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.video_error, Toast.LENGTH_LONG).show();
            return;
        }
        mRecording = true;
        mRecordStart = SystemClock.elapsedRealtime();
        mSound.play(MediaActionSound.START_VIDEO_RECORDING);
        updateControls();
        mMain.post(mRecTick);
    }

    private final Runnable mRecTick = new Runnable() {
        @Override
        public void run() {
            if (!mRecording) {
                return;
            }
            mRecTime.setText("● " + android.text.format.DateUtils.formatElapsedTime(
                    (SystemClock.elapsedRealtime() - mRecordStart) / 1000));
            mMain.postDelayed(this, 500);
        }
    };

    private void stopRecording(boolean restart) {
        mRecording = false;
        try {
            mRecorder.stop();
        } catch (RuntimeException e) {
            // stopped right after starting: no valid video
            if (mVideoFile != null) {
                mVideoFile.delete();
                mVideoFile = null;
            }
        }
        mSound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        releaseRecorder();
        updateControls();
        if (mCaptureIntent) {
            setResult(RESULT_OK, new Intent().setData(mCaptureOutput));
            finish();
            return;
        }
        if (mVideoFile != null) {
            final File f = mVideoFile;
            Storage.scan(this, f, "video/mp4", new Storage.Scanned() {
                @Override
                public void onScanned(final Uri uri) {
                    final Bitmap frame = android.media.ThumbnailUtils.createVideoThumbnail(
                            f.getAbsolutePath(), MediaStore.Video.Thumbnails.MINI_KIND);
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            showThumb(frame, uri, 0, "video/*");
                        }
                    });
                }
            });
        }
        // a fresh recorder for the next clip (reopened, see reopen()); not when pausing
        mVideoFile = null;
        if (restart) {
            reopen();
        }
    }

    private void releaseRecorder() {
        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
            mRecorder = null;
        }
        // prepare() creates the output file: if nothing was recorded, remove the empty clip
        if (!mRecording && mVideoFile != null && mVideoFile.length() == 0) {
            mVideoFile.delete();
            mVideoFile = null;
        }
        if (mVideoPfd != null) {
            try {
                mVideoPfd.close();
            } catch (IOException e) {
                // ignore
            }
            mVideoPfd = null;
        }
    }
}
