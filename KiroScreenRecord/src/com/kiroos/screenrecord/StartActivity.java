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

package com.kiroos.screenrecord;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * The options before a recording (quality, sound, touches), then Android's screen capture
 * consent; the recording itself runs in RecordService.
 */
public class StartActivity extends Activity {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int REQUEST_CAPTURE = 2;

    private RecordSettings mSettings;
    private ViewGroup mQuality;
    private ViewGroup mSound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RecordService.sActive) {
            // a recording runs: opening the options stops it, like the tile
            startService(new Intent(this, RecordService.class)
                    .setAction(RecordService.ACTION_STOP));
            finish();
            return;
        }
        setContentView(R.layout.activity_start);
        mSettings = RecordSettings.load(this);
        mQuality = (ViewGroup) findViewById(R.id.quality);
        mSound = (ViewGroup) findViewById(R.id.sound);
        chips(mQuality, new int[] { RecordSettings.QUALITY_HIGH, RecordSettings.QUALITY_LOW });
        chips(mSound, new int[] { ScreenRecorder.AUDIO_NONE, ScreenRecorder.AUDIO_MIC,
                ScreenRecorder.AUDIO_DEVICE });
        final Switch touches = (Switch) findViewById(R.id.touches);
        touches.setChecked(mSettings.touches);
        touches.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean on) {
                mSettings.touches = on;
            }
        });
        findViewById(R.id.cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSettings.save(StartActivity.this);
                askPermissions();
            }
        });
        refresh();
    }

    /** the chips of a row carry their value as tag, in layout order */
    private void chips(ViewGroup row, int[] values) {
        for (int i = 0; i < row.getChildCount() && i < values.length; i++) {
            final View chip = row.getChildAt(i);
            chip.setTag(values[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final int value = (Integer) v.getTag();
                    if (v.getParent() == mQuality) {
                        mSettings.quality = value;
                    } else {
                        mSettings.audio = value;
                    }
                    refresh();
                }
            });
        }
    }

    private void refresh() {
        select(mQuality, mSettings.quality);
        select(mSound, mSettings.audio);
    }

    private void select(ViewGroup row, int value) {
        for (int i = 0; i < row.getChildCount(); i++) {
            final TextView chip = (TextView) row.getChildAt(i);
            final boolean sel = Integer.valueOf(value).equals(chip.getTag());
            chip.setSelected(sel);
            chip.setTextColor(getColor(sel ? com.kiroos.ui.R.color.kiro_on_accent
                    : com.kiroos.ui.R.color.kiro_text));
        }
    }

    private void askPermissions() {
        final ArrayList<String> need = new ArrayList<>();
        // both: Android 7 gives a process the writable storage view only with READ and WRITE
        for (String p : new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE }) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (mSettings.audio != ScreenRecorder.AUDIO_NONE
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (need.isEmpty()) {
            askCapture();
        } else {
            requestPermissions(need.toArray(new String[need.size()]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        for (int i = 0; i < permissions.length; i++) {
            if (results[i] == PackageManager.PERMISSION_GRANTED) continue;
            Toast.makeText(this, Manifest.permission.RECORD_AUDIO.equals(permissions[i])
                    ? R.string.need_mic : R.string.need_storage, Toast.LENGTH_LONG).show();
            return;
        }
        askCapture();
    }

    private void askCapture() {
        startActivityForResult(getSystemService(MediaProjectionManager.class)
                .createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode == RESULT_OK && data != null) {
            startService(RecordService.startIntent(this, resultCode, data));
        }
        finish();
    }
}
