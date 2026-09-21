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

import android.os.Build;
import android.os.SystemProperties;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

/** Last step: summary of the device, then "Start" hands over to Kiro Home. */
class DonePage extends SetupPage {

    DonePage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_done;
    }

    @Override
    int titleRes() {
        return R.string.done_title;
    }

    @Override
    int summaryRes() {
        return R.string.done_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        final TextView device = (TextView) inflater.inflate(R.layout.status_line, content, false);
        final String version = SystemProperties.get("ro.kiroos.version",
                SystemProperties.get("ro.cm.version", ""));
        device.setText(mActivity.getString(R.string.done_device, Build.MODEL, version));
        content.addView(device);
    }

    @Override
    CharSequence nextLabel() {
        return mActivity.getText(R.string.start);
    }
}
