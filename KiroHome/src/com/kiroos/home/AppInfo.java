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

import android.content.ComponentName;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;

import java.text.Collator;
import java.util.Comparator;

/** One launchable activity. */
final class AppInfo {

    final ComponentName component;
    final UserHandle user;
    final String label;
    /** lower case label without accents, for the drawer search */
    final String searchKey;
    final LauncherActivityInfo info;

    AppInfo(LauncherActivityInfo info) {
        this.info = info;
        this.component = info.getComponentName();
        this.user = info.getUser();
        final CharSequence l = info.getLabel();
        this.label = l != null ? l.toString().trim() : component.getPackageName();
        this.searchKey = java.text.Normalizer.normalize(label.toLowerCase(),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** "package/class" (user 0), how favorites are stored. */
    String key() {
        return component.flattenToShortString();
    }

    static Comparator<AppInfo> byLabel() {
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        return new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                return collator.compare(a.label, b.label);
            }
        };
    }
}
