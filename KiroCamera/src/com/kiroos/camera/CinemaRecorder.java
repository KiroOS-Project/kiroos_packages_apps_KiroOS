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

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Kiro Camera Cinema mode: the camera's 16:9 stream is cropped to 2.35:1 (1920 x 816, the
 * cinemascope frame) on the GPU and drawn into the recorder's input surface, so the file
 * itself is 21:9 (no black bars), encoded at up to 60 Mbps.
 *
 * The camera renders into input() (a SurfaceTexture); every frame is drawn, cropped, into the
 * MediaRecorder surface with its original timestamp.
 */
final class CinemaRecorder implements SurfaceTexture.OnFrameAvailableListener {

    static final int WIDTH = 1920;
    static final int HEIGHT = 816;

    private static final String VERTEX =
            "attribute vec4 aPos;\n"
            + "attribute vec4 aTex;\n"
            + "uniform mat4 uTex;\n"
            + "varying vec2 vTex;\n"
            + "void main() {\n"
            + "  gl_Position = aPos;\n"
            + "  vTex = (uTex * aTex).xy;\n"
            + "}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTex;\n"
            + "uniform samplerExternalOES uCam;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(uCam, vTex);\n"
            + "}\n";

    private final HandlerThread mThread = new HandlerThread("KiroCinema");
    private Handler mHandler;
    private EGLDisplay mDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext mContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface mSurface = EGL14.EGL_NO_SURFACE;
    private SurfaceTexture mInput;
    private Surface mInputSurface;
    private int mProgram;
    private int mTex;
    private final float[] mTexMatrix = new float[16];
    private FloatBuffer mQuad;
    private volatile boolean mReleased;

    /**
     * @param output the MediaRecorder's surface (video size WIDTH x HEIGHT)
     * @param camW   the camera stream size (16:9, e.g. 1920 x 1080)
     */
    CinemaRecorder(final Surface output, final int camW, final int camH) {
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        final Object ready = new Object();
        synchronized (ready) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    setup(output, camW, camH);
                    synchronized (ready) {
                        ready.notifyAll();
                    }
                }
            });
            try {
                ready.wait(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** where the camera draws */
    Surface input() {
        return mInputSurface;
    }

    private void setup(Surface output, int camW, int camH) {
        mDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        final int[] v = new int[2];
        EGL14.eglInitialize(mDisplay, v, 0, v, 1);
        final int[] attrs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] num = new int[1];
        EGL14.eglChooseConfig(mDisplay, attrs, 0, configs, 0, 1, num, 0);
        mContext = EGL14.eglCreateContext(mDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        mSurface = EGL14.eglCreateWindowSurface(mDisplay, configs[0], output,
                new int[] {EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(mDisplay, mSurface, mSurface, mContext);

        mProgram = program(VERTEX, FRAGMENT);
        final int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        mTex = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        mInput = new SurfaceTexture(mTex);
        mInput.setDefaultBufferSize(camW, camH);
        mInput.setOnFrameAvailableListener(this, mHandler);
        mInputSurface = new Surface(mInput);

        // the middle 2.35:1 band of the 16:9 frame (texture coordinates, y up)
        final float band = (camW / 2.35f) / camH;
        final float y0 = (1f - band) / 2f, y1 = y0 + band;
        final float[] quad = {
            // x, y, u, v
            -1f, -1f, 0f, y0,
            1f, -1f, 1f, y0,
            -1f, 1f, 0f, y1,
            1f, 1f, 1f, y1,
        };
        mQuad = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mQuad.put(quad).position(0);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (mReleased) {
            return;
        }
        st.updateTexImage();
        st.getTransformMatrix(mTexMatrix);
        GLES20.glViewport(0, 0, WIDTH, HEIGHT);
        GLES20.glUseProgram(mProgram);
        final int aPos = GLES20.glGetAttribLocation(mProgram, "aPos");
        final int aTex = GLES20.glGetAttribLocation(mProgram, "aTex");
        mQuad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(aPos);
        mQuad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, mQuad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mProgram, "uTex"), 1, false,
                mTexMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(mDisplay, mSurface, st.getTimestamp());
        EGL14.eglSwapBuffers(mDisplay, mSurface);
    }

    void release() {
        mReleased = true;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mInput != null) {
                    mInput.release();
                }
                if (mInputSurface != null) {
                    mInputSurface.release();
                }
                if (mDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(mDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                    EGL14.eglDestroySurface(mDisplay, mSurface);
                    EGL14.eglDestroyContext(mDisplay, mContext);
                    EGL14.eglTerminate(mDisplay);
                }
                mThread.quitSafely();
            }
        });
    }

    private static int program(String vs, String fs) {
        final int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int shader(int type, String src) {
        final int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
