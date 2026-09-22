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

package com.kiroos.home;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The vertical app drawer: all apps by label, filtered by the search box. Matching ignores
 * case and accents; apps whose label starts with the query (or one of its words) come first.
 * Rows are recycled and icons come from the shared IconFactory cache, so scrolling allocates
 * almost nothing.
 */
final class DrawerAdapter extends BaseAdapter {

    private final LayoutInflater mInflater;
    private final IconFactory mIcons;
    private List<AppInfo> mAll = Collections.emptyList();
    private List<AppInfo> mShown = Collections.emptyList();
    private String mQuery = "";

    DrawerAdapter(Context context, IconFactory icons) {
        mInflater = LayoutInflater.from(context);
        mIcons = icons;
    }

    void setApps(List<AppInfo> apps) {
        mAll = apps;
        apply();
    }

    void filter(String query) {
        final String q = normalize(query == null ? "" : query.trim());
        if (q.equals(mQuery)) {
            return; // setApps() already applied the current query
        }
        mQuery = q;
        apply();
    }

    private void apply() {
        if (mQuery.isEmpty()) {
            mShown = mAll;
        } else {
            final List<AppInfo> prefix = new ArrayList<>();
            final List<AppInfo> inner = new ArrayList<>();
            for (AppInfo a : mAll) {
                final int at = a.searchKey.indexOf(mQuery);
                if (at == 0 || (at > 0 && !Character.isLetterOrDigit(a.searchKey.charAt(at - 1)))) {
                    prefix.add(a);
                } else if (at > 0) {
                    inner.add(a);
                }
            }
            prefix.addAll(inner);
            mShown = prefix;
        }
        notifyDataSetChanged();
    }

    static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    @Override
    public int getCount() {
        return mShown.size();
    }

    @Override
    public AppInfo getItem(int position) {
        return mShown.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final View v = convertView != null ? convertView
                : mInflater.inflate(R.layout.item_app, parent, false);
        Holder h = (Holder) v.getTag();
        if (h == null) {
            h = new Holder();
            h.icon = (ImageView) v.findViewById(R.id.app_icon);
            h.label = (TextView) v.findViewById(R.id.app_label);
            mIcons.lift(h.icon);
            v.setTag(h);
        }
        final AppInfo app = mShown.get(position);
        h.icon.setImageDrawable(mIcons.icon(app));
        h.label.setText(app.label);
        v.setContentDescription(app.label);
        return v;
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
    }
}
