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
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.fingerprint.FingerprintManager;
import android.os.CancellationSignal;
import android.view.View;
import android.view.animation.CycleInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The fingerprint, with the screen lock (PIN, pattern, password) as the other way in; drives the
 * fingerprint glyph, the status line and the "Use screen lock" button of a layout.
 */
final class Auth {

    interface Callback {
        void onAuthenticated();
    }

    private static final int REQUEST_CREDENTIAL = 0x4b41;

    private final Activity mActivity;
    private final Callback mCallback;
    private final KeyguardManager mKeyguard;
    private final FingerprintManager mFingerprint;
    private final ImageView mGlyph;
    private final TextView mStatus;
    private final View mUseLock;
    private CancellationSignal mCancel;
    private boolean mCredentialShown;
    private boolean mAutoCredential = true;

    Auth(Activity activity, View root, Callback callback) {
        mActivity = activity;
        mCallback = callback;
        mKeyguard = activity.getSystemService(KeyguardManager.class);
        mFingerprint = activity.getSystemService(FingerprintManager.class);
        mGlyph = (ImageView) root.findViewById(R.id.fingerprint);
        mStatus = (TextView) root.findViewById(R.id.status);
        mUseLock = root.findViewById(R.id.use_lock);
        mUseLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCredential();
            }
        });
    }

    /** a lock that needs no proof (no secure screen lock) would lock nothing */
    boolean secure() {
        return mKeyguard.isKeyguardSecure();
    }

    private boolean fingerprintReady() {
        return mFingerprint != null && mFingerprint.isHardwareDetected()
                && mFingerprint.hasEnrolledFingerprints();
    }

    /** from onResume */
    void start() {
        if (!secure()) {
            mCallback.onAuthenticated();
            return;
        }
        if (mCredentialShown) return;
        if (!fingerprintReady()) {
            mGlyph.setVisibility(View.GONE);
            mStatus.setText(R.string.use_lock_hint);
            // once by itself; after a cancel the button is there
            if (mAutoCredential) {
                mAutoCredential = false;
                showCredential();
            }
            return;
        }
        mGlyph.setVisibility(View.VISIBLE);
        mStatus.setText(R.string.touch_sensor);
        mCancel = new CancellationSignal();
        mFingerprint.authenticate(null, mCancel, 0, new FingerprintManager.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                mCancel = null;
                mCallback.onAuthenticated();
            }

            @Override
            public void onAuthenticationFailed() {
                mStatus.setText(R.string.not_recognized);
                shake();
            }

            @Override
            public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                if (helpString != null) mStatus.setText(helpString);
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                if (errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED) return;
                // too many tries, or the sensor is busy: the screen lock still works
                mStatus.setText(errString != null ? errString
                        : mActivity.getString(R.string.use_lock_hint));
                shake();
            }
        }, null);
    }

    /** from onPause */
    void stop() {
        if (mCancel != null) {
            mCancel.cancel();
            mCancel = null;
        }
    }

    private void showCredential() {
        final Intent i = mKeyguard.createConfirmDeviceCredentialIntent(
                mActivity.getString(R.string.use_lock), null);
        if (i == null) {
            mCallback.onAuthenticated();
            return;
        }
        stop();
        mCredentialShown = true;
        mActivity.startActivityForResult(i, REQUEST_CREDENTIAL);
    }

    /** @return true if the result was the screen lock's */
    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CREDENTIAL) return false;
        mCredentialShown = false;
        if (resultCode == Activity.RESULT_OK) mCallback.onAuthenticated();
        return true;
    }

    private void shake() {
        final View v = mGlyph.getVisibility() == View.VISIBLE ? mGlyph : mStatus;
        v.animate().cancel();
        v.setTranslationX(0);
        v.animate().translationX(v.getResources().getDisplayMetrics().density * 10)
                .setInterpolator(new CycleInterpolator(3)).setDuration(400).start();
    }
}
