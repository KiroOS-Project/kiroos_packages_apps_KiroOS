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

package com.kiroos.files;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional root access through "su" (when the user installed a root manager and enabled it
 * in Files): list folders the app can't read and change system files. Paths are always
 * single-quoted for the shell.
 */
final class Root {

    private Root() {
    }

    static String quote(String path) {
        return "'" + path.replace("'", "'\\''") + "'";
    }

    /** Runs commands as root; returns stdout lines, or null if su is missing or denied. */
    static List<String> run(String... commands) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(false).start();
            try (DataOutputStream in = new DataOutputStream(p.getOutputStream())) {
                for (String c : commands) {
                    in.writeBytes(c + "\n");
                }
                in.writeBytes("exit\n");
                in.flush();
            }
            final List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            return p.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    static boolean available() {
        final List<String> out = run("id");
        return out != null && !out.isEmpty() && out.get(0).contains("uid=0");
    }

    /** "name/" for folders, "name" for files (ls -p), without . and .. */
    static List<FileItem> list(String dir) {
        final List<String> lines = run("ls -a -p " + quote(dir));
        final List<FileItem> out = new ArrayList<>();
        if (lines == null) {
            return null;
        }
        for (String l : lines) {
            if (l.isEmpty() || l.equals("./") || l.equals("../")) {
                continue;
            }
            final boolean isDir = l.endsWith("/");
            final String name = isDir ? l.substring(0, l.length() - 1) : l;
            final FileItem f = new FileItem(new File(dir, name));
            f.directory = isDir;
            f.viaRoot = true;
            out.add(f);
        }
        return out;
    }

    static boolean delete(String path) {
        return run("rm -rf " + quote(path)) != null;
    }

    static boolean move(String from, String to) {
        return run("mv " + quote(from) + " " + quote(to)) != null;
    }

    static boolean copy(String from, String to) {
        return run("cp -a " + quote(from) + " " + quote(to)) != null;
    }

    static boolean mkdir(String path) {
        return run("mkdir -p " + quote(path)) != null;
    }

    /** Copies a root-only file somewhere the app can read (to open or share it). */
    static boolean export(String path, File target, int uid) {
        return run("cp " + quote(path) + " " + quote(target.getAbsolutePath()),
                "chown " + uid + ":" + uid + " " + quote(target.getAbsolutePath()),
                "chmod 644 " + quote(target.getAbsolutePath())) != null;
    }
}
