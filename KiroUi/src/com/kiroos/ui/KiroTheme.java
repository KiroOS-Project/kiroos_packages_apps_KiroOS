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

package com.kiroos.ui;

import android.content.Context;
import android.content.res.Configuration;

/**
 * KiroOS theme helpers. The suite follows the system theme (Settings > Display > Theme):
 * dark = night resources (the default), light = the -notnight palette of KiroUi.
 */
public final class KiroTheme {

    private KiroTheme() {
    }

    /**
     * For screens that are always dark whatever the theme (the camera viewfinder, the photo
     * viewer, the home over the wallpaper, the first-boot setup): call it from
     * Activity.attachBaseContext().
     */
    public static Context dark(Context base) {
        final Configuration c = new Configuration(base.getResources().getConfiguration());
        if ((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            return base;
        }
        c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        return base.createConfigurationContext(c);
    }
}
