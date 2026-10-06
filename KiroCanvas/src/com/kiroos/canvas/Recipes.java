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

package com.kiroos.canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * The KiroOS wallpapers, drawn at whatever size is asked (the phone's screen for the real one,
 * small for the thumbnails), always on true black: the S6's AMOLED panel switches its black
 * pixels off. Same recipe, same picture at every size.
 */
final class Recipes {

    static final int COUNT = 8;

    private static final int BLUE = 0xFF3987E5;
    private static final int CYAN = 0xFF2BC4D9;
    private static final int VIOLET = 0xFF7B4DFF;
    private static final int MAGENTA = 0xFFD9468F;
    private static final int ORANGE = 0xFFFF8A3D;
    private static final int ROSE = 0xFFFF5A6E;

    private Recipes() {
    }

    static Bitmap draw(int recipe, int w, int h) {
        final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        final float u = Math.min(w, h) / 1440f;   // 1 unit = 1 px of the 1440 px wide screen
        switch (recipe) {
            case 0: aurora(c, w, h, u); break;
            case 1: nebula(c, w, h, u); break;
            case 2: waves(c, w, h, u); break;
            case 3: mesh(c, w, h); break;
            case 4: orbit(c, w, h, u); break;
            case 5: dunes(c, w, h, u); break;
            case 6: glass(c, w, h, u); break;
            default: midnight(c, w, h); break;
        }
        return b;
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static void glow(Canvas c, float x, float y, float r, int color, int a) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(x, y, r, alpha(color, a), alpha(color, 0),
                Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
    }

    /** soft bands of light falling from the top */
    private static void aurora(Canvas c, int w, int h, float u) {
        glow(c, w * 0.2f, h * 0.05f, 1300 * u, CYAN, 150);
        glow(c, w * 0.75f, h * 0.12f, 1200 * u, BLUE, 170);
        glow(c, w * 0.5f, h * 0.3f, 900 * u, VIOLET, 110);
        final Paint fade = new Paint();
        fade.setShader(new LinearGradient(0, h * 0.25f, 0, h * 0.75f, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fade);
    }

    /** clouds of color and a few stars */
    private static void nebula(Canvas c, int w, int h, float u) {
        glow(c, w * 0.3f, h * 0.35f, 900 * u, VIOLET, 140);
        glow(c, w * 0.75f, h * 0.55f, 800 * u, MAGENTA, 110);
        glow(c, w * 0.45f, h * 0.7f, 700 * u, BLUE, 120);
        final Random r = new Random(7);
        final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 160; i++) {
            star.setColor(alpha(Color.WHITE, 60 + r.nextInt(170)));
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, (1 + r.nextFloat() * 2.2f) * u,
                    star);
        }
    }

    /** layered waves rising from the bottom */
    private static void waves(Canvas c, int w, int h, float u) {
        final int[] colors = { VIOLET, BLUE, CYAN };
        for (int layer = 0; layer < 3; layer++) {
            final float base = h * (0.62f + layer * 0.1f);
            final float amp = (90 - layer * 20) * u;
            final Path p = new Path();
            p.moveTo(0, h);
            for (int x = 0; x <= w; x += Math.max(2, w / 180)) {
                final double t = x / (double) w * Math.PI * 2;
                p.lineTo(x, (float) (base + amp * Math.sin(t * 1.3 + layer * 1.7)));
            }
            p.lineTo(w, h);
            p.close();
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(new LinearGradient(0, base - amp, 0, h,
                    alpha(colors[layer], 150 - layer * 30), 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(p, fill);
        }
    }

    /** a smooth four-corner gradient, dark at the top */
    private static void mesh(Canvas c, int w, int h) {
        final Bitmap corners = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        corners.setPixel(0, 0, 0xFF0B1730);
        corners.setPixel(1, 0, 0xFF1A0C2E);
        corners.setPixel(0, 1, 0xFF0E4C7A);
        corners.setPixel(1, 1, 0xFF4A1B6B);
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(Bitmap.createScaledBitmap(corners, 64, 64, true), null,
                new android.graphics.Rect(0, 0, w, h), p);
        final Paint top = new Paint();
        top.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0xFF000000, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, top);
    }

    /** the KiroOS O: thin glowing rings */
    private static void orbit(Canvas c, int w, int h, float u) {
        final float cx = w * 0.5f;
        final float cy = h * 0.42f;
        glow(c, cx, cy, 700 * u, BLUE, 70);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 6; i++) {
            ring.setStrokeWidth((2 + i % 2) * u);
            ring.setColor(alpha(i % 2 == 0 ? BLUE : CYAN, 170 - i * 22));
            c.drawCircle(cx, cy, (220 + i * 95) * u, ring);
        }
    }

    /** warm dunes at sunset */
    private static void dunes(Canvas c, int w, int h, float u) {
        glow(c, w * 0.5f, h * 0.48f, 900 * u, ORANGE, 120);
        final int[] colors = { ROSE, ORANGE, MAGENTA };
        for (int layer = 0; layer < 3; layer++) {
            final float base = h * (0.6f + layer * 0.11f);
            final Path p = new Path();
            p.moveTo(0, h);
            p.lineTo(0, base);
            p.cubicTo(w * 0.3f, base - 160 * u, w * 0.55f, base + 120 * u, w, base - 60 * u);
            p.lineTo(w, h);
            p.close();
            final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(new LinearGradient(0, base - 160 * u, 0, h,
                    alpha(colors[layer], 140 - layer * 25), 0xFF000000, Shader.TileMode.CLAMP));
            c.drawPath(p, fill);
        }
    }

    /** soft frosted shapes */
    private static void glass(Canvas c, int w, int h, float u) {
        glow(c, w * 0.25f, h * 0.3f, 520 * u, CYAN, 150);
        glow(c, w * 0.7f, h * 0.45f, 600 * u, VIOLET, 150);
        glow(c, w * 0.4f, h * 0.68f, 480 * u, BLUE, 150);
        final Paint sheet = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheet.setColor(0x14FFFFFF);
        c.drawRoundRect(w * 0.12f, h * 0.22f, w * 0.88f, h * 0.78f, 90 * u, 90 * u, sheet);
    }

    /** true black with a faint vignette of blue at the bottom */
    private static void midnight(Canvas c, int w, int h) {
        final Paint p = new Paint();
        p.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0x00000000, 0xFF07142A,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
    }
}
