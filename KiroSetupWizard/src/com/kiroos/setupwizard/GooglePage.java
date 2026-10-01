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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.backup.IBackupManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;

/**
 * Google account: the sign-in of Google Play services (the same screens as Settings >
 * Accounts > Add account > Google), then backup to Google Drive. Shown only when Play
 * services are in the ROM; without a connection the page says so and can be skipped.
 */
class GooglePage extends SetupPage {

    private static final String TAG = "KiroSetup";
    static final String GOOGLE = "com.google";
    private static final String GMS = "com.google.android.gms";
    private static final String GMS_TRANSPORT = "com.google.android.gms/.backup.BackupTransportService";

    private TextView mStatus;
    private Button mSignIn;
    private Switch mBackup;
    private boolean mBusy;

    GooglePage(SetupActivity activity) {
        super(activity);
    }

    /** Play services installed and enabled */
    static boolean available(Context context) {
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfo(GMS, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    int iconRes() {
        return R.drawable.ic_account;
    }

    @Override
    int titleRes() {
        return R.string.google_title;
    }

    @Override
    int summaryRes() {
        return R.string.google_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mStatus = addStatus(content);
        mSignIn = addButton(content, R.string.google_sign_in, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signIn();
            }
        });
        addHeading(content, R.string.google_services);
        mBackup = addSwitch(content, R.string.google_backup, R.string.google_backup_summary);
        mBackup.setChecked(backupEnabled());
        mBackup.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                setBackup(checked);
            }
        });
    }

    @Override
    CharSequence nextLabel() {
        return mActivity.getText(accounts().length > 0 ? R.string.next : R.string.skip);
    }

    @Override
    void onShown() {
        refresh();
    }

    private Account[] accounts() {
        return AccountManager.get(mActivity).getAccountsByType(GOOGLE);
    }

    private boolean online() {
        final ConnectivityManager cm = (ConnectivityManager)
                mActivity.getSystemService(Context.CONNECTIVITY_SERVICE);
        final NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private void refresh() {
        if (mStatus == null) {
            return;
        }
        final Account[] accounts = accounts();
        if (accounts.length > 0) {
            final StringBuilder names = new StringBuilder();
            for (Account a : accounts) {
                if (names.length() > 0) {
                    names.append('\n');
                }
                names.append(a.name);
            }
            mStatus.setText(mActivity.getString(R.string.google_signed_in, names));
            mSignIn.setText(R.string.google_add_another);
        } else if (!online()) {
            mStatus.setText(R.string.google_offline);
            mSignIn.setText(R.string.google_sign_in);
        } else {
            mStatus.setText(R.string.google_ready);
            mSignIn.setText(R.string.google_sign_in);
        }
        mSignIn.setEnabled(online() && !mBusy);
        mSignIn.setAlpha(mSignIn.isEnabled() ? 1f : 0.5f);
        mActivity.refreshButtons();
    }

    private void signIn() {
        if (mBusy) {
            return;
        }
        mBusy = true;
        refresh();
        final Bundle options = new Bundle();
        // the flags a setup wizard passes: Skip is allowed, no "copy from another phone"
        options.putBoolean("allowSkip", true);
        options.putBoolean("firstRun", true);
        options.putBoolean("suppress_device_to_device_setup", true);
        // with an activity, the account manager opens Google's screens itself and calls back
        // when they close (signed in, or Back)
        AccountManager.get(mActivity).addAccount(GOOGLE, null, null, options, mActivity,
                new AccountManagerCallback<Bundle>() {
                    @Override
                    public void run(AccountManagerFuture<Bundle> future) {
                        mBusy = false;
                        try {
                            future.getResult();
                        } catch (OperationCanceledException e) {
                            // Back on Google's screen: nothing to say
                        } catch (AuthenticatorException | IOException e) {
                            Log.w(TAG, "Google sign-in", e);
                            mStatus.setText(R.string.google_not_ready);
                            mSignIn.setEnabled(true);
                            mSignIn.setAlpha(1f);
                            return;
                        }
                        refresh();
                    }
                }, null);
    }

    private static IBackupManager backup() {
        return IBackupManager.Stub.asInterface(ServiceManager.getService(Context.BACKUP_SERVICE));
    }

    private static boolean backupEnabled() {
        try {
            final IBackupManager bm = backup();
            return bm != null && bm.isBackupEnabled();
        } catch (RemoteException | SecurityException e) {
            return false;
        }
    }

    /** backup on, through Google's transport when Play services provide it */
    private static void setBackup(boolean on) {
        try {
            final IBackupManager bm = backup();
            if (bm == null) {
                return;
            }
            if (on) {
                final String[] transports = bm.listAllTransports();
                if (transports != null) {
                    for (String t : transports) {
                        if (GMS_TRANSPORT.equals(t)) {
                            bm.selectBackupTransport(t);
                            break;
                        }
                    }
                }
            }
            bm.setBackupEnabled(on);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "backup", e);
        }
    }
}
