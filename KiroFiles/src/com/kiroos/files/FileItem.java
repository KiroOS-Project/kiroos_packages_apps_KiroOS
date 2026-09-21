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

import java.io.File;
import java.util.Comparator;
import java.util.Locale;

/** A file or folder in a listing. */
final class FileItem {

    final File file;
    boolean directory;
    long size;
    long modified;
    /** listed through su (the app itself cannot read it) */
    boolean viaRoot;

    FileItem(File f) {
        file = f;
        directory = f.isDirectory();
        size = directory ? 0 : f.length();
        modified = f.lastModified();
    }

    String name() {
        return file.getName();
    }

    String extension() {
        final String n = name();
        final int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    int icon() {
        if (directory) {
            return R.drawable.ic_folder;
        }
        final String mime = FilesProvider.mimeOf(name());
        if (mime.startsWith("image/")) {
            return R.drawable.ic_image;
        }
        if (mime.startsWith("video/")) {
            return R.drawable.ic_movie;
        }
        if (mime.startsWith("audio/")) {
            return R.drawable.ic_music;
        }
        if ("apk".equals(extension())) {
            return R.drawable.ic_android;
        }
        return R.drawable.ic_file;
    }

    static final int SORT_NAME = 0;
    static final int SORT_DATE = 1;
    static final int SORT_SIZE = 2;

    /** folders first, then by the chosen key */
    static Comparator<FileItem> comparator(final int sort) {
        return new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                switch (sort) {
                    case SORT_DATE:
                        return Long.compare(b.modified, a.modified);
                    case SORT_SIZE:
                        return Long.compare(b.size, a.size);
                    default:
                        return a.name().compareToIgnoreCase(b.name());
                }
            }
        };
    }
}
