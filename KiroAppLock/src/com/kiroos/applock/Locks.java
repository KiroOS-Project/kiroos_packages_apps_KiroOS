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

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The locked packages, in Settings.Secure where the activity manager reads them
 * (frameworks/base ActivityStartInterceptor, KiroOS App lock).
 */
final class Locks {

    static final String KEY = "kiroos_applock_packages";
    /** the extra and option key of the one-time unlock token */
    static final String TOKEN = "com.kiroos.applock.TOKEN";

    private Locks() {
    }

    static Set<String> get(Context context) {
        final String v = Settings.Secure.getString(context.getContentResolver(), KEY);
        final Set<String> s = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(v)) s.addAll(Arrays.asList(v.split(",")));
        return s;
    }

    static void set(Context context, String pkg, boolean locked) {
        final Set<String> s = get(context);
        if (locked) {
            s.add(pkg);
        } else {
            s.remove(pkg);
        }
        Settings.Secure.putString(context.getContentResolver(), KEY, TextUtils.join(",", s));
    }
}
