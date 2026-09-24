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

package com.kiroos.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.FaceDetector;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Kiro Camera 1.1 photo modes, computed on the phone after the shot. Full-resolution work is
 * done in strips (BitmapRegionDecoder), so a 16 MP photo never needs more than the finished
 * picture plus a few strips in memory.
 *
 *  Night:    a burst of frames is aligned (coarse search at 1/8, refined at 1/2) and averaged,
 *            which removes most of the sensor noise; then the shadows are lifted gently.
 *  Portrait: the face (or, without one, the middle of the frame) and the shoulders under it
 *            are the subject; the rest is blurred progressively, more the farther it is.
 */
final class PhotoModes {

    private static final String TAG = "KiroCamera";
    private static final int STRIP = 256;

    private PhotoModes() {
    }

    // ------------------------------------------------------------------ night

    static boolean night(List<byte[]> frames, File out, int exifOrientation) {
        try {
            final int n = frames.size();
            final BitmapRegionDecoder[] dec = new BitmapRegionDecoder[n];
            for (int i = 0; i < n; i++) {
                dec[i] = BitmapRegionDecoder.newInstance(frames.get(i), 0, frames.get(i).length, false);
            }
            final int w = dec[0].getWidth(), h = dec[0].getHeight();
            // alignment of every frame to the first
            final int[] dx = new int[n], dy = new int[n];
            final int[] ref8 = luma(decodeSmall(frames.get(0), 8));
            final Bitmap small0 = decodeSmall(frames.get(0), 8);
            final int w8 = small0.getWidth(), h8 = small0.getHeight();
            small0.recycle();
            for (int i = 1; i < n; i++) {
                final int[] cur = luma(decodeSmall(frames.get(i), 8));
                final int[] c = bestShift(ref8, cur, w8, h8, 12, 0, 0, w8, h8);
                // refine at 1/2 in a central window
                final int cw = Math.min(w, 1600), ch = Math.min(h, 1600);
                final Rect win = new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
                final int[] a = luma(region(dec[0], win, 2));
                final Rect moved = new Rect(win);
                moved.offset(c[0] * 8, c[1] * 8);
                if (moved.left < 0 || moved.top < 0 || moved.right > w || moved.bottom > h) {
                    dx[i] = c[0] * 8;
                    dy[i] = c[1] * 8;
                    continue;
                }
                final int[] b = luma(region(dec[i], moved, 2));
                final int[] f = bestShift(a, b, cw / 2, ch / 2, 6, 0, 0, cw / 2, ch / 2);
                dx[i] = c[0] * 8 + f[0] * 2;
                dy[i] = c[1] * 8 + f[1] * 2;
                Log.i(TAG, "night frame " + i + " offset " + dx[i] + "," + dy[i]);
            }
            // tone: shadows lifted, highlights kept
            final int[] tone = new int[256];
            for (int v = 0; v < 256; v++) {
                final double x = v / 255.0;
                final double y = Math.pow(x, 0.86) * (1.0 - 0.06 * x);
                tone[v] = (int) Math.max(0, Math.min(255, Math.round(y * 255 * 1.03)));
            }
            final Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final int[] acc = new int[w * STRIP * 3];
            final int[] px = new int[w * STRIP];
            for (int top = 0; top < h; top += STRIP) {
                final int rows = Math.min(STRIP, h - top);
                java.util.Arrays.fill(acc, 0, w * rows * 3, 0);
                final int[] count = new int[w * rows];
                for (int i = 0; i < n; i++) {
                    // this strip of frame 0, as it appears in frame i
                    final Rect src = new Rect(dx[i], top + dy[i], w + dx[i], top + rows + dy[i]);
                    final Rect clip = new Rect(src);
                    if (!clip.intersect(0, 0, w, h)) {
                        continue;
                    }
                    final Bitmap part = region(dec[i], clip, 1);
                    final int pw = part.getWidth(), ph = part.getHeight();
                    part.getPixels(px, 0, pw, 0, 0, pw, ph);
                    part.recycle();
                    final int ox = clip.left - src.left, oy = clip.top - src.top;
                    for (int y = 0; y < ph; y++) {
                        final int row = (y + oy) * w + ox;
                        for (int x = 0; x < pw; x++) {
                            final int c = px[y * pw + x];
                            final int k = row + x;
                            acc[k * 3] += (c >> 16) & 0xFF;
                            acc[k * 3 + 1] += (c >> 8) & 0xFF;
                            acc[k * 3 + 2] += c & 0xFF;
                            count[k]++;
                        }
                    }
                }
                for (int k = 0; k < w * rows; k++) {
                    final int c = Math.max(1, count[k]);
                    px[k] = 0xFF000000 | tone[acc[k * 3] / c] << 16 | tone[acc[k * 3 + 1] / c] << 8
                            | tone[acc[k * 3 + 2] / c];
                }
                result.setPixels(px, 0, w, 0, top, w, rows);
            }
            for (BitmapRegionDecoder d : dec) {
                d.recycle();
            }
            return write(result, out, exifOrientation);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "night mode failed", e);
            return false;
        }
    }

    /** the shift (dx, dy) of b that best matches a, within +-range, on a subsampled grid */
    private static int[] bestShift(int[] a, int[] b, int w, int h, int range, int x0, int y0,
            int x1, int y1) {
        long best = Long.MAX_VALUE;
        int bx = 0, by = 0;
        final int m = range + 2;
        for (int sy = -range; sy <= range; sy++) {
            for (int sx = -range; sx <= range; sx++) {
                long sad = 0;
                for (int y = y0 + m; y < y1 - m; y += 2) {
                    for (int x = x0 + m; x < x1 - m; x += 2) {
                        sad += Math.abs(a[y * w + x] - b[(y + sy) * w + x + sx]);
                    }
                }
                if (sad < best) {
                    best = sad;
                    bx = sx;
                    by = sy;
                }
            }
        }
        return new int[] {bx, by};
    }

    // ------------------------------------------------------------------ portrait

    static boolean portrait(byte[] jpeg, File out, int exifOrientation, int rotation) {
        try {
            final BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
            final int w = dec.getWidth(), h = dec.getHeight();
            final int scale = 8;
            // the small picture, upright, for the face and the blurred background
            final Bitmap small = decodeSmall(jpeg, scale);
            final Matrix rot = new Matrix();
            rot.postRotate(rotation);
            final Bitmap upright = Bitmap.createBitmap(small, 0, 0, small.getWidth(),
                    small.getHeight(), rot, true);
            final int uw = upright.getWidth() & ~1, uh = upright.getHeight();
            final int[] up = new int[uw * uh];
            upright.getPixels(up, 0, uw, 0, 0, uw, uh);
            // subject: head ellipse + shoulders, in upright small coordinates
            float fx = uw / 2f, fy = uh * 0.42f, eyes = uw * 0.09f;
            final Bitmap face565 = upright.copy(Bitmap.Config.RGB_565, false);
            final Bitmap even = Bitmap.createBitmap(face565, 0, 0, uw, uh);
            final FaceDetector.Face[] faces = new FaceDetector.Face[3];
            final int found = new FaceDetector(uw, uh, faces.length).findFaces(even, faces);
            float bestConf = 0;
            for (int i = 0; i < found; i++) {
                if (faces[i] != null && faces[i].confidence() > bestConf) {
                    final PointF p = new PointF();
                    faces[i].getMidPoint(p);
                    fx = p.x;
                    fy = p.y;
                    eyes = faces[i].eyesDistance();
                    bestConf = faces[i].confidence();
                }
            }
            Log.i(TAG, "portrait: " + found + " face(s), subject at " + fx + "," + fy);
            // blur weight per small pixel: 0 on the subject, rising with the distance from it
            final float[] weight = new float[uw * uh];
            final float headRx = eyes * 1.7f, headRy = eyes * 2.3f;
            final float feather = Math.max(uw, uh) * 0.07f;
            for (int y = 0; y < uh; y++) {
                for (int x = 0; x < uw; x++) {
                    final float ex = (x - fx) / headRx, ey = (y - fy) / headRy;
                    float d = (float) (Math.sqrt(ex * ex + ey * ey) - 1) * Math.min(headRx, headRy);
                    // shoulders and body: a widening shape below the chin
                    if (y > fy + headRy * 0.6f) {
                        final float t = (y - (fy + headRy * 0.6f)) / (uh - fy);
                        final float half = headRx * (1.3f + 2.2f * Math.min(1f, t * 2.5f));
                        d = Math.min(d, Math.abs(x - fx) - half);
                    }
                    final float t = Math.max(0f, Math.min(1f, d / feather));
                    // progressive: the blur keeps growing well past the edge of the subject
                    final float far = Math.max(0f, Math.min(1f, d / (feather * 6f)));
                    weight[y * uw + x] = t * t * (3 - 2 * t) * (0.55f + 0.45f * far);
                }
            }
            final int[] blur = boxBlur(boxBlur(up, uw, uh, 3), uw, uh, 3);
            // full resolution composite, strip by strip, in the sensor's orientation
            final Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final int[] px = new int[w * STRIP];
            final Matrix toUpright = new Matrix();
            toUpright.postScale(1f / scale, 1f / scale);
            toUpright.postRotate(rotation);
            final float[] m = new float[9];
            // map (x, y) of the sensor image to the upright small image
            final Matrix full = new Matrix();
            full.postScale((float) small.getWidth() / w, (float) small.getHeight() / h);
            full.postConcat(rotationMatrix(rotation, small.getWidth(), small.getHeight()));
            full.getValues(m);
            for (int top = 0; top < h; top += STRIP) {
                final int rows = Math.min(STRIP, h - top);
                final Bitmap part = region(dec, new Rect(0, top, w, top + rows), 1);
                part.getPixels(px, 0, w, 0, 0, w, rows);
                part.recycle();
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < w; x++) {
                        final float sx = m[0] * x + m[1] * (y + top) + m[2];
                        final float sy = m[3] * x + m[4] * (y + top) + m[5];
                        final int ix = Math.max(0, Math.min(uw - 1, (int) sx));
                        final int iy = Math.max(0, Math.min(uh - 1, (int) sy));
                        final float a = weight[iy * uw + ix];
                        if (a <= 0.01f) {
                            continue;
                        }
                        final int b = bilinear(blur, uw, uh, sx, sy);
                        final int c = px[y * w + x];
                        px[y * w + x] = mix(c, b, a);
                    }
                }
                result.setPixels(px, 0, w, 0, top, w, rows);
            }
            dec.recycle();
            small.recycle();
            upright.recycle();
            return write(result, out, exifOrientation);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "portrait mode failed", e);
            return false;
        }
    }

    /** maps the sensor-oriented small image onto the upright one */
    private static Matrix rotationMatrix(int rotation, int w, int h) {
        final Matrix m = new Matrix();
        m.postRotate(rotation);
        switch (rotation) {
            case 90:
                m.postTranslate(h, 0);
                break;
            case 180:
                m.postTranslate(w, h);
                break;
            case 270:
                m.postTranslate(0, w);
                break;
            default:
                break;
        }
        return m;
    }

    private static int mix(int a, int b, float t) {
        final int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        final int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        final int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static int bilinear(int[] p, int w, int h, float x, float y) {
        x = Math.max(0, Math.min(w - 1.001f, x - 0.5f));
        y = Math.max(0, Math.min(h - 1.001f, y - 0.5f));
        final int x0 = (int) x, y0 = (int) y;
        final float fx = x - x0, fy = y - y0;
        final int a = p[y0 * w + x0], b = p[y0 * w + x0 + 1];
        final int c = p[(y0 + 1) * w + x0], d = p[(y0 + 1) * w + x0 + 1];
        return mix(mix(a, b, fx), mix(c, d, fx), fy);
    }

    private static int[] boxBlur(int[] src, int w, int h, int r) {
        final int[] tmp = new int[w * h], out = new int[w * h];
        for (int pass = 0; pass < 2; pass++) {
            final int[] in = pass == 0 ? src : tmp;
            final int[] dst = pass == 0 ? tmp : out;
            final boolean horiz = pass == 0;
            final int len = horiz ? w : h, lines = horiz ? h : w;
            for (int l = 0; l < lines; l++) {
                int sr = 0, sg = 0, sb = 0;
                for (int i = -r; i <= r; i++) {
                    final int c = in[idx(Math.max(0, Math.min(len - 1, i)), l, w, horiz)];
                    sr += (c >> 16) & 0xFF;
                    sg += (c >> 8) & 0xFF;
                    sb += c & 0xFF;
                }
                final int n = 2 * r + 1;
                for (int i = 0; i < len; i++) {
                    dst[idx(i, l, w, horiz)] = 0xFF000000 | (sr / n) << 16 | (sg / n) << 8 | sb / n;
                    final int add = in[idx(Math.min(len - 1, i + r + 1), l, w, horiz)];
                    final int rem = in[idx(Math.max(0, i - r), l, w, horiz)];
                    sr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                    sg += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                    sb += (add & 0xFF) - (rem & 0xFF);
                }
            }
        }
        return out;
    }

    private static int idx(int i, int line, int w, boolean horiz) {
        return horiz ? line * w + i : i * w + line;
    }

    // ------------------------------------------------------------------ helpers

    private static Bitmap decodeSmall(byte[] jpeg, int sample) {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
    }

    private static Bitmap region(BitmapRegionDecoder d, Rect r, int sample) {
        final BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return d.decodeRegion(r, o);
    }

    private static int[] luma(Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        final int[] p = new int[w * h];
        b.getPixels(p, 0, w, 0, 0, w, h);
        b.recycle();
        for (int i = 0; i < p.length; i++) {
            final int c = p[i];
            p[i] = (((c >> 16) & 0xFF) * 77 + ((c >> 8) & 0xFF) * 150 + (c & 0xFF) * 29) >> 8;
        }
        return p;
    }

    private static boolean write(Bitmap b, File out, int exifOrientation) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.JPEG, 96, os);
        }
        b.recycle();
        final ExifInterface exif = new ExifInterface(out.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
        exif.saveAttributes();
        return true;
    }
}
