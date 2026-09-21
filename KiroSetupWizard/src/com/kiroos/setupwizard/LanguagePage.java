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

import com.android.internal.app.LocalePicker;

import java.util.Locale;

/** Step 1: system language. KiroOS ships its changelogs and UI in these eight languages. */
class LanguagePage extends SetupPage {

    private static final Locale[] LOCALES = {
            Locale.ITALY,
            Locale.US,
            new Locale("es", "ES"),
            Locale.FRANCE,
            Locale.GERMANY,
            new Locale("pt", "BR"),
            new Locale("ru", "RU"),
            new Locale("tr", "TR"),
    };

    private ViewGroup mList;

    LanguagePage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_language;
    }

    @Override
    int titleRes() {
        return R.string.language_title;
    }

    @Override
    int summaryRes() {
        return R.string.language_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mList = content;
        final Locale current = mActivity.getResources().getConfiguration().getLocales().get(0);
        for (final Locale locale : LOCALES) {
            final View row = inflater.inflate(R.layout.row_choice, content, false);
            final String name = locale.getDisplayName(locale);
            ((TextView) row.findViewById(R.id.row_title)).setText(
                    Character.toUpperCase(name.charAt(0)) + name.substring(1));
            final boolean selected = locale.getLanguage().equals(current.getLanguage());
            ((ImageView) row.findViewById(R.id.row_check)).setVisibility(
                    selected ? View.VISIBLE : View.INVISIBLE);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(locale);
                }
            });
            content.addView(row);
        }
    }

    private void select(Locale locale) {
        final Locale current = mActivity.getResources().getConfiguration().getLocales().get(0);
        if (locale.equals(current)) {
            return;
        }
        // updates the system configuration; the activity is recreated in the new language
        // and comes back to this page
        LocalePicker.updateLocale(locale);
    }
}
