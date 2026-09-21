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

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/** One step of the setup. The view is inflated once and reused when going back. */
abstract class SetupPage {

    protected final SetupActivity mActivity;
    private View mView;

    SetupPage(SetupActivity activity) {
        mActivity = activity;
    }

    final View getView(ViewGroup parent) {
        if (mView == null) {
            mView = LayoutInflater.from(mActivity).inflate(R.layout.page_base, parent, false);
            ((ImageView) mView.findViewById(R.id.page_icon)).setImageResource(iconRes());
            ((TextView) mView.findViewById(R.id.page_title)).setText(titleRes());
            ((TextView) mView.findViewById(R.id.page_summary)).setText(summaryRes());
            final ViewGroup content = (ViewGroup) mView.findViewById(R.id.page_content);
            onCreateContent(LayoutInflater.from(mActivity), content);
        }
        return mView;
    }

    abstract int iconRes();

    abstract int titleRes();

    abstract int summaryRes();

    /** Adds the page's own views below the title. */
    abstract void onCreateContent(LayoutInflater inflater, ViewGroup content);

    /** Label of the Next button while this page is shown. */
    CharSequence nextLabel() {
        return mActivity.getText(R.string.next);
    }

    void onShown() {
    }

    void onHidden() {
    }

    /** The user pressed Next on this page. */
    void onLeave() {
    }
}
