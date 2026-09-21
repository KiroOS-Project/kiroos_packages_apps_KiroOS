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

package com.kiroos.setupwizard;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.fingerprint.FingerprintManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

/**
 * Step 4: fingerprint. FingerprintManager tells whether the sensor is there and how many
 * fingers are enrolled; enrolment itself runs in Settings' setup flow, which first asks for
 * the screen lock the fingerprint falls back to (Gatekeeper needs it to authorize enrolment).
 */
class FingerprintPage extends SetupPage {

    private static final String ACTION_FINGERPRINT_SETUP = "android.settings.FINGERPRINT_SETUP";

    private final FingerprintManager mFingerprint;
    private TextView mStatus;
    private Button mAdd;

    FingerprintPage(SetupActivity activity) {
        super(activity);
        mFingerprint = (FingerprintManager) activity.getSystemService(Context.FINGERPRINT_SERVICE);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_fingerprint;
    }

    @Override
    int titleRes() {
        return R.string.fingerprint_title;
    }

    @Override
    int summaryRes() {
        return R.string.fingerprint_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mStatus = (TextView) inflater.inflate(R.layout.status_line, content, false);
        content.addView(mStatus);
        mAdd = (Button) inflater.inflate(R.layout.accent_button, content, false);
        mAdd.setText(R.string.fingerprint_add);
        KiroTouch.apply(mAdd);
        mAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    mActivity.startActivity(new Intent(ACTION_FINGERPRINT_SETUP));
                } catch (ActivityNotFoundException e) {
                    mStatus.setText(R.string.fingerprint_unavailable);
                }
            }
        });
        content.addView(mAdd);
    }

    @Override
    CharSequence nextLabel() {
        return mActivity.getText(enrolled() ? R.string.next : R.string.skip);
    }

    private boolean supported() {
        return mFingerprint != null && mFingerprint.isHardwareDetected();
    }

    private boolean enrolled() {
        return supported() && mFingerprint.hasEnrolledFingerprints();
    }

    @Override
    void onShown() {
        if (mStatus == null) {
            return;
        }
        if (!supported()) {
            mStatus.setText(R.string.fingerprint_unavailable);
            mAdd.setVisibility(View.GONE);
        } else if (enrolled()) {
            final int count = mFingerprint.getEnrolledFingerprints().size();
            mStatus.setText(mActivity.getResources().getQuantityString(
                    R.plurals.fingerprint_enrolled, count, count));
            mAdd.setText(R.string.fingerprint_add_another);
        } else {
            mStatus.setText(R.string.fingerprint_none);
            mAdd.setText(R.string.fingerprint_add);
        }
        mActivity.refreshButtons();
    }
}
