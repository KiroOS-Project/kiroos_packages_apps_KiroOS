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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

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

    // building blocks shared by the pages

    /** A secondary line of text (status, notes). */
    final TextView addStatus(ViewGroup content) {
        final TextView t = (TextView) LayoutInflater.from(mActivity)
                .inflate(R.layout.status_line, content, false);
        content.addView(t);
        return t;
    }

    /** A small heading between groups of rows. */
    final TextView addHeading(ViewGroup content, int text) {
        final TextView t = (TextView) LayoutInflater.from(mActivity)
                .inflate(R.layout.section_heading, content, false);
        t.setText(text);
        content.addView(t);
        return t;
    }

    /** A card with a title, a summary and a switch; tapping the card flips the switch. */
    final Switch addSwitch(ViewGroup content, int title, int summary) {
        final View row = LayoutInflater.from(mActivity).inflate(R.layout.row_switch, content, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_summary)).setText(summary);
        final Switch s = (Switch) row.findViewById(R.id.row_switch);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (s.isEnabled()) {
                    s.toggle();
                }
            }
        });
        content.addView(row);
        return s;
    }

    /** A card that opens something: title, a value or summary below, a chevron. */
    final View addAction(ViewGroup content, int title, View.OnClickListener listener) {
        final View row = LayoutInflater.from(mActivity).inflate(R.layout.row_action, content, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        row.setOnClickListener(listener);
        content.addView(row);
        return row;
    }

    static void setActionValue(View row, CharSequence value) {
        ((TextView) row.findViewById(R.id.row_summary)).setText(value);
    }

    /** A full-width accent button. */
    final Button addButton(ViewGroup content, int text, View.OnClickListener listener) {
        final Button b = (Button) LayoutInflater.from(mActivity)
                .inflate(R.layout.accent_button, content, false);
        b.setText(text);
        b.setOnClickListener(listener);
        KiroTouch.apply(b);
        content.addView(b);
        return b;
    }
}
