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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws one map tile from OpenMapTiles vector data, in the style of the system theme: Pure Black
 * AMOLED (black land, deep blue water, dark green parks, grey streets, amber motorways) or light
 * (warm grey land, white streets with a casing). Above zoom 14 the zoom 14 data is drawn larger
 * (crisp, not a scaled picture). The labels are not drawn here: they are collected in world
 * units for the map to place without overlaps.
 */
final class TileRenderer {

    /** road classes from the least to the most important (drawn in this order) */
    private static final String[] ROAD_ORDER = {
        "path", "track", "service", "rail", "transit", "ferry", "minor", "tertiary",
        "secondary", "primary", "trunk", "motorway",
    };
    /** width in dp at zoom 14, the first zoom it shows at, dashed, per ROAD_ORDER */
    private static final float[] ROAD_WIDTH = {1f, 1f, 1.6f, 1.2f, 1.2f, 1.2f, 2.8f, 3.6f, 4.2f, 5f,
            5.5f, 6f};
    private static final int[] ROAD_MIN_ZOOM = {14, 14, 14, 11, 11, 9, 12, 11, 9, 8, 6, 5};
    private static final boolean[] ROAD_DASHED = {true, true, false, true, true, true, false,
            false, false, false, false, false};
    private static final java.util.Set<String> MICROSTATES = new java.util.HashSet<>(
            java.util.Arrays.asList("VA", "SM", "MC", "LI", "AD"));
    private static final java.util.Set<String> NOISY_POI = new java.util.HashSet<>(
            java.util.Arrays.asList("bus", "information", "parking", "bicycle_parking",
                    "bicycle_rental", "atm", "toilets", "post", "waste_basket"));

    /** the colors of a map style */
    static final class Style {
        final int background, water, wood, grass, ice, sand, park, residential, industrial,
                building, runway, borderCountry, borderState;
        /** per ROAD_ORDER; casing 0 = none */
        final int[] road, casing;
        final int label, labelStrong, labelMuted, labelSuburb, labelWater, labelPeak, labelPoi,
                halo;
        final int route, routeCasing;

        Style(boolean dark) {
            if (dark) {
                background = 0xFF000000;
                water = 0xFF0A2342;
                wood = 0xFF0A1A0D;
                grass = 0xFF0B1C0F;
                ice = 0xFF1B1E23;
                sand = 0xFF1C1912;
                park = 0xFF0B1E10;
                residential = 0xFF0A0A0C;
                industrial = 0xFF0D0D10;
                building = 0xFF17171A;
                runway = 0xFF2A2A2E;
                borderCountry = 0xFF6E6E73;
                borderState = 0xFF38383C;
                road = new int[] {0xFF3C3C40, 0xFF3C3C40, 0xFF37373B, 0xFF4C4C50, 0xFF4C4C50,
                        0xFF1E4A7A, 0xFF46464A, 0xFF616166, 0xFF7E7E83, 0xFFA2A2A7, 0xFFE0943A,
                        0xFFF2A541};
                casing = new int[ROAD_ORDER.length];
                label = 0xFFE5E5EA;
                labelStrong = 0xFFFFFFFF;
                labelMuted = 0xFF98989D;
                labelSuburb = 0xFFAEAEB2;
                labelWater = 0xFF5E8FC7;
                labelPeak = 0xFFB59A72;
                labelPoi = 0xFFB0B0B5;
                halo = 0xFF000000;
                route = 0xFF3987E5;
                routeCasing = 0xFF0B2A4F;
            } else {
                background = 0xFFF3F2EE;
                water = 0xFFA9CDEE;
                wood = 0xFFCFE3C3;
                grass = 0xFFDCEBCF;
                ice = 0xFFFFFFFF;
                sand = 0xFFF1E9D2;
                park = 0xFFC9E4BF;
                residential = 0xFFEDECE7;
                industrial = 0xFFEAE7EE;
                building = 0xFFDEDBD5;
                runway = 0xFFD3D3D8;
                borderCountry = 0xFF8E8E96;
                borderState = 0xFFBDBDC4;
                road = new int[] {0xFFB4AC9F, 0xFFB4AC9F, 0xFFFFFFFF, 0xFF9C9CA4, 0xFF9C9CA4,
                        0xFF5B8FD0, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFE9A6, 0xFFFAD27E,
                        0xFFF6B656};
                casing = new int[] {0, 0, 0xFFD6D3CC, 0, 0, 0, 0xFFD6D3CC, 0xFFCFCBC2,
                        0xFFCFCBC2, 0xFFE0C677, 0xFFD9A84F, 0xFFD0902F};
                label = 0xFF3A3A3C;
                labelStrong = 0xFF111114;
                labelMuted = 0xFF6E6E73;
                labelSuburb = 0xFF5E5E66;
                labelWater = 0xFF3B6EA5;
                labelPeak = 0xFF7A5C33;
                labelPoi = 0xFF5E5E66;
                halo = 0xFFFFFFFF;
                route = 0xFF2F7BDA;
                routeCasing = 0xFF1B4F94;
            }
        }
    }

    static final class Label {
        /** world units */
        double x, y;
        String text;
        /** lower is more important */
        int priority;
        /** text size in px */
        float size;
        int color;
    }

    static final class Result {
        Bitmap bitmap;
        final List<Label> labels = new ArrayList<>();
    }

    private final float mDensity;
    private final int mSize;
    private final Style mStyle;
    private final String mLang = Locale.getDefault().getLanguage();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRoadText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRoadHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PathMeasure mMeasure = new PathMeasure();
    private final List<RectF> mTaken = new ArrayList<>();
    private final DashPathEffect mDash;
    private final Path mPath = new Path();

    TileRenderer(float density, int size, Style style) {
        mDensity = density;
        mSize = size;
        mStyle = style;
        mFill.setStyle(Paint.Style.FILL);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mDash = new DashPathEffect(new float[] {4 * density, 3 * density}, 0);
        mRoadText.setTextSize(11 * density);
        mRoadText.setColor(style.label);
        mRoadHalo.set(mRoadText);
        mRoadHalo.setStyle(Paint.Style.STROKE);
        mRoadHalo.setStrokeWidth(3 * density);
        mRoadHalo.setColor(style.halo);
    }

    /**
     * Tile (z, x, y) from the data of tile (dataZ, x >> (z - dataZ), y >> (z - dataZ)).
     */
    Result render(Mvt data, int z, int x, int y, int dataZ) {
        final Result out = new Result();
        final Bitmap bmp = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.RGB_565);
        final Canvas c = new Canvas(bmp);
        c.drawColor(mStyle.background);
        final int sub = z - dataZ;
        final int dataX = x >> sub, dataY = y >> sub;
        final float ox = (x - (dataX << sub)) * mSize, oy = (y - (dataY << sub)) * mSize;

        fills(c, data.layers.get("water"), sub, ox, oy, z, "water");
        fills(c, data.layers.get("landcover"), sub, ox, oy, z, "landcover");
        fills(c, data.layers.get("landuse"), sub, ox, oy, z, "landuse");
        fills(c, data.layers.get("park"), sub, ox, oy, z, "park");
        waterways(c, data.layers.get("waterway"), sub, ox, oy, z);
        if (z >= 13) fills(c, data.layers.get("building"), sub, ox, oy, z, "building");
        aeroways(c, data.layers.get("aeroway"), sub, ox, oy, z);
        roads(c, data.layers.get("transportation"), sub, ox, oy, z);
        borders(c, data.layers.get("boundary"), sub, ox, oy, z);
        roadNames(c, data.layers.get("transportation_name"), sub, ox, oy, z);

        labels(out.labels, data.layers.get("place"), dataZ, dataX, dataY, z, x, y, "place");
        labels(out.labels, data.layers.get("water_name"), dataZ, dataX, dataY, z, x, y, "water");
        if (z >= 12) {
            labels(out.labels, data.layers.get("mountain_peak"), dataZ, dataX, dataY, z, x, y,
                    "peak");
        }
        if (z >= 16) {
            labels(out.labels, data.layers.get("poi"), dataZ, dataX, dataY, z, x, y, "poi");
        }
        out.bitmap = bmp;
        return out;
    }

    // ---- geometry

    /**
     * The feature as a path in tile pixels, without the points closer than half a pixel to the
     * last one kept. At low zoom a tile holds thousands of points per pixel (a zoom 6 tile is
     * 400 KB): the picture is the same, the path far smaller, and Skia's convexity check, which
     * spun for minutes on runs of zero-length segments, has none left.
     */
    private void path(Mvt.Feature f, float scale, float ox, float oy, boolean close) {
        mPath.reset();
        final float min = 0.5f;
        for (int[] part : f.parts) {
            if (part.length < 4) continue;
            float lx = part[0] * scale - ox, ly = part[1] * scale - oy;
            final float sx = lx, sy = ly;
            int kept = 1;
            for (int i = 2; i + 1 < part.length; i += 2) {
                final float x = part[i] * scale - ox, y = part[i + 1] * scale - oy;
                if (Math.abs(x - lx) < min && Math.abs(y - ly) < min) continue;
                if (kept == 1) mPath.moveTo(sx, sy);
                mPath.lineTo(x, y);
                lx = x;
                ly = y;
                kept++;
            }
            if (kept > 1 && close) mPath.close();
        }
    }

    private static float scale(Mvt.Layer l, int sub, int size) {
        return (float) size * (1 << sub) / l.extent;
    }

    private void fills(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z, String kind) {
        if (l == null) return;
        final float s = scale(l, sub, mSize);
        final boolean debug = android.util.Log.isLoggable("Atlas", android.util.Log.DEBUG);
        final long t0 = android.os.SystemClock.uptimeMillis();
        mPath.setFillType(Path.FillType.EVEN_ODD);
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.POLYGON) continue;
            final int color = fillColor(kind, f.str("class"), z);
            if (color == 0) continue;
            mFill.setColor(color);
            path(f, s, ox, oy, true);
            if (debug) {
                int points = 0;
                for (int[] p : f.parts) points += p.length / 2;
                if (points > 2000) {
                    android.util.Log.d("Atlas", "z" + z + " " + kind + " " + f.str("class")
                            + ": " + f.parts.size() + " rings, " + points + " points");
                }
            }
            c.drawPath(mPath, mFill);
        }
        mPath.setFillType(Path.FillType.WINDING);
        if (debug) {
            android.util.Log.d("Atlas", "z" + z + " " + kind + ": " + l.features.size()
                    + " features in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        }
    }

    private int fillColor(String kind, String cls, int z) {
        final Style st = mStyle;
        switch (kind) {
            case "water":
                return st.water;
            case "park":
                // protected areas can be huge and mostly sea (the Pelagos sanctuary is a
                // triangle over the Ligurian Sea): only from zoom 8, and those only from 11
                if (z < 8 || ("protected_area".equals(cls) && z < 11)) return 0;
                return st.park;
            case "building":
                return st.building;
            case "landcover":
                if (cls == null) return 0;
                switch (cls) {
                    case "wood": case "forest": return st.wood;
                    case "grass": case "wetland": return st.grass;
                    case "ice": case "glacier": return st.ice;
                    case "sand": return st.sand;
                    default: return 0;
                }
            case "landuse":
                if (cls == null || z < 11) return 0;
                switch (cls) {
                    case "residential": case "suburb": case "neighbourhood": return st.residential;
                    case "industrial": case "commercial": case "retail": case "railway":
                        return st.industrial;
                    case "cemetery": case "pitch": case "stadium": case "playground":
                        return st.park;
                    default: return 0;
                }
            default:
                return 0;
        }
    }

    /** line width in px at zoom z for a width of w dp at zoom 14 */
    private float width(float w, int z) {
        return Math.max(0.6f * mDensity, w * mDensity * (float) Math.pow(2, (z - 14) * 0.6));
    }

    private void waterways(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z) {
        if (l == null) return;
        final float s = scale(l, sub, mSize);
        mLine.setColor(mStyle.water);
        mLine.setPathEffect(null);
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.LINE) continue;
            final String cls = f.str("class");
            final float w = "river".equals(cls) ? 4f : "canal".equals(cls) ? 3f : 1.5f;
            if (!"river".equals(cls) && z < 12) continue;
            mLine.setStrokeWidth(width(w, z));
            path(f, s, ox, oy, false);
            c.drawPath(mPath, mLine);
        }
    }

    private void aeroways(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z) {
        if (l == null || z < 11) return;
        final float s = scale(l, sub, mSize);
        mLine.setColor(mStyle.runway);
        mLine.setPathEffect(null);
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.LINE) continue;
            mLine.setStrokeWidth(width("runway".equals(f.str("class")) ? 10f : 4f, z));
            path(f, s, ox, oy, false);
            c.drawPath(mPath, mLine);
        }
    }

    /** all the casings first, then the roads, so crossings join cleanly */
    private void roads(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z) {
        if (l == null) return;
        final float s = scale(l, sub, mSize);
        for (int pass = 0; pass < 2; pass++) {
            final boolean casing = pass == 0;
            for (int i = 0; i < ROAD_ORDER.length; i++) {
                if (z < ROAD_MIN_ZOOM[i]) continue;
                final int color = casing ? mStyle.casing[i] : mStyle.road[i];
                if (color == 0) continue;
                final float w = width(ROAD_WIDTH[i], z);
                mLine.setColor(color);
                mLine.setStrokeWidth(casing ? w + Math.max(1.2f * mDensity, w * 0.35f) : w);
                mLine.setPathEffect(ROAD_DASHED[i] ? mDash : null);
                final String cls = ROAD_ORDER[i];
                for (Mvt.Feature f : l.features) {
                    if (f.type != Mvt.LINE || !cls.equals(f.str("class"))) continue;
                    path(f, s, ox, oy, false);
                    c.drawPath(mPath, mLine);
                }
            }
        }
        mLine.setPathEffect(null);
    }

    private void borders(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z) {
        if (l == null) return;
        final float s = scale(l, sub, mSize);
        mLine.setPathEffect(mDash);
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.LINE || f.num("maritime", 0) == 1) continue;
            final long level = f.num("admin_level", 10);
            if (level <= 2) {
                mLine.setColor(mStyle.borderCountry);
                mLine.setStrokeWidth(1.2f * mDensity);
            } else if (level <= 4 && z >= 5) {
                mLine.setColor(mStyle.borderState);
                mLine.setStrokeWidth(0.9f * mDensity);
            } else {
                continue;
            }
            path(f, s, ox, oy, false);
            c.drawPath(mPath, mLine);
        }
        mLine.setPathEffect(null);
    }

    /**
     * Street names along their street, drawn into the tile: main roads from zoom 13, the others
     * from 15. A name shows only where it fits whole inside the tile (no half names at tile edges)
     * and clear of the names already drawn; one per street and tile.
     */
    private void roadNames(Canvas c, Mvt.Layer l, int sub, float ox, float oy, int z) {
        if (l == null || z < 13) return;
        final float s = scale(l, sub, mSize);
        final float pad = 4 * mDensity, half = mRoadText.getTextSize();
        final float[] pos = new float[2];
        mTaken.clear();
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.LINE) continue;
            final String cls = f.str("class");
            final boolean major = "motorway".equals(cls) || "trunk".equals(cls)
                    || "primary".equals(cls) || "secondary".equals(cls);
            if (z < (major ? 13 : 15)) continue;
            String text = name(f);
            if (text == null || text.isEmpty()) text = f.str("ref");
            if (text == null || text.isEmpty()) continue;
            final float w = mRoadText.measureText(text);
            for (int[] part : f.parts) {
                if (part.length < 4) continue;
                // drawn left to right, so the name is not upside down
                final boolean reverse = part[part.length - 2] < part[0];
                mPath.reset();
                for (int i = 0; i + 1 < part.length; i += 2) {
                    final int j = reverse ? part.length - 2 - i : i;
                    final float x = part[j] * s - ox, y = part[j + 1] * s - oy;
                    if (i == 0) mPath.moveTo(x, y);
                    else mPath.lineTo(x, y);
                }
                mMeasure.setPath(mPath, false);
                final float len = mMeasure.getLength();
                if (len < w + 2 * pad) continue;
                final float start = (len - w) / 2;
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                boolean inside = true;
                for (int k = 0; k <= 4 && inside; k++) {
                    mMeasure.getPosTan(start + w * k / 4, pos, null);
                    inside = pos[0] > pad && pos[1] > pad && pos[0] < mSize - pad
                            && pos[1] < mSize - pad;
                    minX = Math.min(minX, pos[0]);
                    maxX = Math.max(maxX, pos[0]);
                    minY = Math.min(minY, pos[1]);
                    maxY = Math.max(maxY, pos[1]);
                }
                if (!inside) continue;
                final RectF box = new RectF(minX - half, minY - half, maxX + half, maxY + half);
                boolean free = true;
                for (RectF t : mTaken) {
                    if (RectF.intersects(t, box)) {
                        free = false;
                        break;
                    }
                }
                if (!free) continue;
                mTaken.add(box);
                // centered on the road line
                final float dy = mRoadText.getTextSize() * 0.35f;
                c.drawTextOnPath(text, mPath, start, dy, mRoadHalo);
                c.drawTextOnPath(text, mPath, start, dy, mRoadText);
                break;
            }
        }
    }

    // ---- labels

    private String name(Mvt.Feature f) {
        String n = f.str("name:" + mLang);
        if (n == null && "en".equals(mLang)) n = f.str("name_en");
        if (n == null) n = f.str("name");
        return n;
    }

    private void labels(List<Label> out, Mvt.Layer l, int dataZ, int dataX, int dataY, int z,
            int x, int y, String kind) {
        if (l == null) return;
        final double tiles = 1 << dataZ;
        final double minX = x / (double) (1 << z), maxX = (x + 1) / (double) (1 << z);
        final double minY = y / (double) (1 << z), maxY = (y + 1) / (double) (1 << z);
        for (Mvt.Feature f : l.features) {
            if (f.type != Mvt.POINT || f.parts.isEmpty()) continue;
            final String text = name(f);
            if (text == null || text.isEmpty()) continue;
            final int[] p = f.parts.get(0);
            final double wx = (dataX + p[0] / (double) l.extent) / tiles;
            final double wy = (dataY + p[1] / (double) l.extent) / tiles;
            // each label belongs to the one tile it falls in (no copies from the buffer)
            if (wx < minX || wx >= maxX || wy < minY || wy >= maxY) continue;
            final Label lb = new Label();
            lb.x = wx;
            lb.y = wy;
            lb.text = text;
            lb.color = mStyle.label;
            if (!style(lb, f, kind, z)) continue;
            out.add(lb);
        }
    }

    /** priority, size and color of a label; false when it does not show at this zoom */
    private boolean style(Label lb, Mvt.Feature f, String kind, int z) {
        final Style st = mStyle;
        final long rank = f.num("rank", 10);
        switch (kind) {
            case "place": {
                final String cls = f.str("class");
                if (cls == null) return false;
                switch (cls) {
                    case "continent": return false;
                    case "country":
                        // the microstates share rank 3 with Slovenia and Croatia, and a name as
                        // big as Italy's on a dot: they show as cities when zoomed in
                        if (z > 7 || z < rank || MICROSTATES.contains(f.str("iso_a2"))) {
                            return false;
                        }
                        lb.priority = (int) rank; lb.size = rank <= 2 ? 15 : 13.5f;
                        lb.color = st.labelStrong; break;
                    case "state": case "province":
                        if (z < 5 || z > 9) return false;
                        lb.priority = 10; lb.size = 12; lb.color = st.labelMuted; break;
                    case "city":
                        if (z < 4 + Math.min(rank, 6)) return false;
                        lb.priority = 20 + (int) rank; lb.size = 15; lb.color = st.labelStrong;
                        break;
                    case "town":
                        if (z < 9) return false;
                        lb.priority = 40 + (int) rank; lb.size = 13.5f; break;
                    case "village":
                        if (z < 11) return false;
                        lb.priority = 60 + (int) rank; lb.size = 12.5f; break;
                    case "suburb": case "quarter":
                        if (z < 12) return false;
                        lb.priority = 70; lb.size = 12.5f; lb.color = st.labelSuburb; break;
                    case "hamlet": case "neighbourhood": case "isolated_dwelling":
                        if (z < 14) return false;
                        lb.priority = 80; lb.size = 11.5f; lb.color = st.labelMuted; break;
                    default: return false;
                }
                break;
            }
            case "water":
                if (z < 6) return false;
                // "lake" is every named water, fountains included (Rome's z14 tile names two
                // dozen): the low zoom data has only the big ones, from 16 they are landmarks
                if ("lake".equals(f.str("class")) && z >= 13 && z < 16) return false;
                lb.priority = z >= 16 ? 88 : 50; lb.size = 12; lb.color = st.labelWater; break;
            case "peak":
                lb.priority = 85; lb.size = 11; lb.color = st.labelPeak; break;
            case "poi":
                // bus stops ("Cavour/Ricci"), info boards and car parks crowd out the places
                // the most important places first: rank up to 8 at zoom 16, 14 at 17, 20 at 18
                if (rank > (z - 16) * 6 + 8 || NOISY_POI.contains(f.str("class"))) return false;
                lb.priority = 90 + (int) rank; lb.size = 11; lb.color = st.labelPoi; break;
            default:
                return false;
        }
        lb.size *= mDensity;
        return true;
    }
}
