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

import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.hardware.fingerprint.FingerprintManager;
import android.os.UserHandle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

/**
 * Protect your phone: the screen lock (PIN, pattern or password, in Settings' screens) and
 * the fingerprint. FingerprintManager tells whether the sensor is there and how many fingers
 * are enrolled; enrolment runs in Settings' setup flow, which first asks for the screen lock
 * the fingerprint falls back to (Gatekeeper needs it to authorize enrolment).
 */
class SecurityPage extends SetupPage {

    private static final String ACTION_FINGERPRINT_SETUP = "android.settings.FINGERPRINT_SETUP";

    private final FingerprintManager mFingerprint;
    private final LockPatternUtils mLock;
    private TextView mLockStatus;
    private Button mLockButton;
    private TextView mFingerHeading;
    private TextView mFingerStatus;
    private Button mFingerButton;

    SecurityPage(SetupActivity activity) {
        super(activity);
        mFingerprint = (FingerprintManager) activity.getSystemService(Context.FINGERPRINT_SERVICE);
        mLock = new LockPatternUtils(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_lock;
    }

    @Override
    int titleRes() {
        return R.string.security_title;
    }

    @Override
    int summaryRes() {
        return R.string.security_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mLockStatus = addStatus(content);
        mLockButton = addButton(content, R.string.lock_set, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD), mLockStatus);
            }
        });
        mFingerHeading = addHeading(content, R.string.fingerprint_title);
        mFingerStatus = addStatus(content);
        mFingerButton = addButton(content, R.string.fingerprint_add, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(new Intent(ACTION_FINGERPRINT_SETUP), mFingerStatus);
            }
        });
    }

    private void open(Intent intent, TextView status) {
        try {
            mActivity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            status.setText(R.string.fingerprint_unavailable);
        }
    }

    @Override
    CharSequence nextLabel() {
        return mActivity.getText(secure() ? R.string.next : R.string.skip);
    }

    private boolean secure() {
        return mLock.isSecure(UserHandle.myUserId());
    }

    private boolean fingerprintSupported() {
        return mFingerprint != null && mFingerprint.isHardwareDetected();
    }

    private boolean enrolled() {
        return fingerprintSupported() && mFingerprint.hasEnrolledFingerprints();
    }

    @Override
    void onShown() {
        if (mLockStatus == null) {
            return;
        }
        if (secure()) {
            final int quality = mLock.getKeyguardStoredPasswordQuality(UserHandle.myUserId());
            final int kind;
            if (quality == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING) {
                kind = R.string.lock_pattern;
            } else if (quality == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    || quality == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX) {
                kind = R.string.lock_pin;
            } else {
                kind = R.string.lock_password;
            }
            mLockStatus.setText(kind);
            mLockButton.setText(R.string.lock_change);
        } else {
            mLockStatus.setText(R.string.lock_none);
            mLockButton.setText(R.string.lock_set);
        }

        final int fingerVisibility = fingerprintSupported() ? View.VISIBLE : View.GONE;
        mFingerHeading.setVisibility(fingerVisibility);
        mFingerStatus.setVisibility(fingerVisibility);
        mFingerButton.setVisibility(fingerVisibility);
        if (enrolled()) {
            final int count = mFingerprint.getEnrolledFingerprints().size();
            mFingerStatus.setText(mActivity.getResources().getQuantityString(
                    R.plurals.fingerprint_enrolled, count, count));
            mFingerButton.setText(R.string.fingerprint_add_another);
        } else if (fingerprintSupported()) {
            mFingerStatus.setText(R.string.fingerprint_none);
            mFingerButton.setText(R.string.fingerprint_add);
        }
        mActivity.refreshButtons();
    }
}
