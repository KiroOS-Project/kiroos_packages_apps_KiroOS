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

package com.kiroos.atlas;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Mapbox Vector Tile (protobuf, spec 2.1) decoded into layers of features with their tags and
 * geometry. Only what the renderer needs is kept: geometry as int arrays of tile coordinates
 * (0..extent), split into parts (rings / lines).
 */
final class Mvt {

    static final int POINT = 1, LINE = 2, POLYGON = 3;

    static final class Feature {
        int type;
        final Map<String, Object> tags = new HashMap<>();
        /** each part: x0, y0, x1, y1, ... in tile units */
        final List<int[]> parts = new ArrayList<>();

        String str(String key) {
            final Object v = tags.get(key);
            return v == null ? null : v.toString();
        }

        long num(String key, long def) {
            final Object v = tags.get(key);
            if (v instanceof Number) return ((Number) v).longValue();
            return def;
        }
    }

    static final class Layer {
        String name;
        int extent = 4096;
        final List<Feature> features = new ArrayList<>();
    }

    final Map<String, Layer> layers = new HashMap<>();

    static Mvt decode(byte[] data) {
        // tiles stored gzipped (a server that sends them without Content-Encoding)
        if (data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(data))) {
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                final byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                data = out.toByteArray();
            } catch (java.io.IOException e) {
                return new Mvt();
            }
        }
        final Mvt tile = new Mvt();
        final Reader r = new Reader(data, 0, data.length);
        while (r.more()) {
            final int key = (int) r.varint();
            if ((key >>> 3) == 3 && (key & 7) == 2) {
                final Layer l = layer(r.sub());
                if (l.name != null) tile.layers.put(l.name, l);
            } else {
                r.skip(key & 7);
            }
        }
        return tile;
    }

    private static Layer layer(Reader r) {
        final Layer layer = new Layer();
        final List<String> keys = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final List<Reader> features = new ArrayList<>();
        while (r.more()) {
            final int key = (int) r.varint();
            switch (key >>> 3) {
                case 1: layer.name = r.string(); break;
                case 2: features.add(r.sub()); break;
                case 3: keys.add(r.string()); break;
                case 4: values.add(value(r.sub())); break;
                case 5: layer.extent = (int) r.varint(); break;
                default: r.skip(key & 7); break;
            }
        }
        for (Reader fr : features) {
            final Feature f = feature(fr, keys, values);
            if (f != null) layer.features.add(f);
        }
        return layer;
    }

    private static Object value(Reader r) {
        Object v = null;
        while (r.more()) {
            final int key = (int) r.varint();
            switch (key >>> 3) {
                case 1: v = r.string(); break;
                case 2: v = (double) Float.intBitsToFloat(r.fixed32()); break;
                case 3: v = Double.longBitsToDouble(r.fixed64()); break;
                case 4: v = r.varint(); break;
                case 5: v = r.varint(); break;
                case 6: { final long n = r.varint(); v = (n >>> 1) ^ -(n & 1); break; }
                case 7: v = r.varint() != 0; break;
                default: r.skip(key & 7); break;
            }
        }
        return v;
    }

    private static Feature feature(Reader r, List<String> keys, List<Object> values) {
        final Feature f = new Feature();
        int[] geometry = null;
        int geometryLength = 0;
        while (r.more()) {
            final int key = (int) r.varint();
            switch (key >>> 3) {
                case 2: {
                    final Reader t = r.sub();
                    while (t.more()) {
                        final int k = (int) t.varint();
                        final int v = (int) t.varint();
                        if (k < keys.size() && v < values.size()) {
                            f.tags.put(keys.get(k), values.get(v));
                        }
                    }
                    break;
                }
                case 3: f.type = (int) r.varint(); break;
                case 4: {
                    final Reader g = r.sub();
                    geometry = new int[Math.max(8, g.remaining() * 2)];
                    while (g.more()) {
                        if (geometryLength == geometry.length) {
                            final int[] bigger = new int[geometry.length * 2];
                            System.arraycopy(geometry, 0, bigger, 0, geometryLength);
                            geometry = bigger;
                        }
                        geometry[geometryLength++] = (int) g.varint();
                    }
                    break;
                }
                default: r.skip(key & 7); break;
            }
        }
        if (geometry == null || f.type < POINT || f.type > POLYGON) return null;
        geometry(f, geometry, geometryLength);
        return f;
    }

    /** MoveTo / LineTo / ClosePath commands with zigzag deltas into absolute parts */
    private static void geometry(Feature f, int[] g, int n) {
        int i = 0, x = 0, y = 0;
        int[] part = null;
        int len = 0;
        while (i < n) {
            final int cmd = g[i] & 7, count = g[i] >>> 3;
            i++;
            if (cmd == 1 || cmd == 2) {
                for (int c = 0; c < count && i + 1 < n; c++) {
                    x += (g[i] >>> 1) ^ -(g[i] & 1);
                    y += (g[i + 1] >>> 1) ^ -(g[i + 1] & 1);
                    i += 2;
                    if (cmd == 1 && f.type != POINT) {
                        if (part != null && len > 0) f.parts.add(trim(part, len));
                        part = new int[16];
                        len = 0;
                    }
                    if (part == null) {
                        part = new int[16];
                        len = 0;
                    }
                    if (len + 2 > part.length) {
                        final int[] bigger = new int[part.length * 2];
                        System.arraycopy(part, 0, bigger, 0, len);
                        part = bigger;
                    }
                    part[len++] = x;
                    part[len++] = y;
                }
            } else if (cmd == 7) {
                // ClosePath: the renderer closes polygon rings itself
            } else {
                break;
            }
        }
        if (part != null && len > 0) f.parts.add(trim(part, len));
    }

    private static int[] trim(int[] a, int len) {
        final int[] out = new int[len];
        System.arraycopy(a, 0, out, 0, len);
        return out;
    }

    /** protobuf wire reader over a byte range */
    private static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int remaining() {
            return end - pos;
        }

        long varint() {
            long v = 0;
            int shift = 0;
            while (pos < end) {
                final int c = b[pos++] & 0xff;
                v |= (long) (c & 0x7f) << shift;
                if (c < 0x80) return v;
                shift += 7;
                if (shift > 63) break;
            }
            return v;
        }

        int fixed32() {
            final int v = (b[pos] & 0xff) | (b[pos + 1] & 0xff) << 8 | (b[pos + 2] & 0xff) << 16
                    | (b[pos + 3] & 0xff) << 24;
            pos += 4;
            return v;
        }

        long fixed64() {
            final long lo = fixed32() & 0xffffffffL;
            final long hi = fixed32() & 0xffffffffL;
            return lo | (hi << 32);
        }

        Reader sub() {
            final int len = (int) varint();
            final Reader r = new Reader(b, pos, Math.min(end, pos + len));
            pos = Math.min(end, pos + len);
            return r;
        }

        String string() {
            final int len = (int) varint();
            final String s = new String(b, pos, Math.min(len, end - pos), StandardCharsets.UTF_8);
            pos = Math.min(end, pos + len);
            return s;
        }

        void skip(int wireType) {
            switch (wireType) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: { final int len = (int) varint(); pos += len; break; }
                case 5: pos += 4; break;
                default: pos = end; break;
            }
        }
    }
}
