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

package com.kiroos.surge;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Blocks requests to ad and tracker domains (and their subdomains) with an empty response
 * before WebView fetches them. The bundled list can be extended with one domain per line in
 * files/adhosts_user.txt.
 */
final class AdBlocker {

    private static AdBlocker sInstance;
    private final Set<String> mHosts = new HashSet<>();

    static synchronized AdBlocker get(Context context) {
        if (sInstance == null) {
            sInstance = new AdBlocker(context.getApplicationContext());
        }
        return sInstance;
    }

    private AdBlocker(Context context) {
        try (InputStream in = context.getAssets().open("adhosts.txt")) {
            read(in);
        } catch (Exception e) {
            // empty list: nothing blocked
        }
        final File user = new File(context.getFilesDir(), "adhosts_user.txt");
        if (user.exists()) {
            try (InputStream in = new FileInputStream(user)) {
                read(in);
            } catch (Exception e) {
                // ignore a broken user list
            }
        }
    }

    private void read(InputStream in) throws java.io.IOException {
        final BufferedReader r = new BufferedReader(new InputStreamReader(in));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim().toLowerCase();
            if (!line.isEmpty() && !line.startsWith("#")) {
                // "domain/path" entries match the domain
                final int slash = line.indexOf('/');
                mHosts.add(slash > 0 ? line.substring(0, slash) : line);
            }
        }
    }

    /** true if the host or one of its parent domains is listed */
    boolean isAd(Uri url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        while (true) {
            if (mHosts.contains(host)) {
                return true;
            }
            final int dot = host.indexOf('.');
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) {
                return false; // stop at the registrable domain
            }
            host = host.substring(dot + 1);
        }
    }

    static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8",
                new ByteArrayInputStream(new byte[0]));
    }
}
