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

package com.kiroos.gallery;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

/**
 * Full screen video player: immersive, landscape when the video is wide, remembers the
 * position across rotation / interruptions.
 */
public class VideoActivity extends Activity {

    private VideoView mVideo;
    private int mPosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_video);
        mVideo = (VideoView) findViewById(R.id.video);
        final Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        final MediaController controller = new MediaController(this);
        controller.setAnchorView(mVideo);
        mVideo.setMediaController(controller);
        mVideo.setVideoURI(uri);
        mVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                if (mp.getVideoWidth() > mp.getVideoHeight()) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
                mVideo.seekTo(mPosition);
                mVideo.start();
                controller.show(2500);
            }
        });
        mVideo.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                finish();
            }
        });
        mVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(VideoActivity.this, R.string.cannot_play, Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
        });
        if (savedInstanceState != null) {
            mPosition = savedInstanceState.getInt("position", 0);
        }
        immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideo != null) {
            mPosition = mVideo.getCurrentPosition();
            mVideo.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (mVideo != null && mPosition > 0) {
            mVideo.seekTo(mPosition);
            mVideo.start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("position", mPosition);
    }
}
