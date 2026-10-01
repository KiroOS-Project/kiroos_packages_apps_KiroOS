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

package com.kiroos.calculator;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The unit converter's units. Every unit is a factor to its category's base unit (metre,
 * square metre, litre, kilogram, metre per second, second, byte, joule); temperatures are
 * converted through kelvin. Exact definitions (inch = 0.0254 m, pound = 0.45359237 kg, ...).
 */
final class Units {

    private Units() {
    }

    static final class Unit {
        final String symbol;
        final double factor;

        Unit(String symbol, double factor) {
            this.symbol = symbol;
            this.factor = factor;
        }
    }

    static final class Category {
        final int name;
        final Unit[] units;
        /** the units shown first: from, to */
        final int from;
        final int to;
        final boolean temperature;

        Category(int name, int from, int to, boolean temperature, Unit... units) {
            this.name = name;
            this.units = units;
            this.from = from;
            this.to = to;
            this.temperature = temperature;
        }
    }

    static final Category[] CATEGORIES = {
            new Category(R.string.cat_length, 3, 7, false,
                    new Unit("mm", 0.001), new Unit("cm", 0.01), new Unit("m", 1),
                    new Unit("km", 1000), new Unit("in", 0.0254), new Unit("ft", 0.3048),
                    new Unit("yd", 0.9144), new Unit("mi", 1609.344), new Unit("nmi", 1852)),
            new Category(R.string.cat_area, 1, 5, false,
                    new Unit("cm²", 1e-4), new Unit("m²", 1), new Unit("ha", 1e4),
                    new Unit("km²", 1e6), new Unit("in²", 0.00064516),
                    new Unit("ft²", 0.09290304), new Unit("ac", 4046.8564224),
                    new Unit("mi²", 2589988.110336)),
            new Category(R.string.cat_volume, 1, 7, false,
                    new Unit("ml", 0.001), new Unit("l", 1), new Unit("m³", 1000),
                    new Unit("tsp", 0.00492892159375), new Unit("tbsp", 0.01478676478125),
                    new Unit("fl oz", 0.0295735295625), new Unit("cup", 0.2365882365),
                    new Unit("gal", 3.785411784), new Unit("gal (UK)", 4.54609)),
            new Category(R.string.cat_mass, 2, 5, false,
                    new Unit("mg", 1e-6), new Unit("g", 0.001), new Unit("kg", 1),
                    new Unit("t", 1000), new Unit("oz", 0.028349523125),
                    new Unit("lb", 0.45359237), new Unit("st", 6.35029318)),
            new Category(R.string.cat_temperature, 0, 1, true,
                    new Unit("°C", 0), new Unit("°F", 0), new Unit("K", 0)),
            new Category(R.string.cat_speed, 1, 2, false,
                    new Unit("m/s", 1), new Unit("km/h", 1 / 3.6), new Unit("mph", 0.44704),
                    new Unit("kn", 1852 / 3600.0), new Unit("ft/s", 0.3048)),
            new Category(R.string.cat_time, 3, 2, false,
                    new Unit("ms", 0.001), new Unit("s", 1), new Unit("min", 60),
                    new Unit("h", 3600), new Unit("d", 86400), new Unit("wk", 604800),
                    new Unit("yr", 31557600)),
            new Category(R.string.cat_data, 4, 8, false,
                    new Unit("bit", 0.125), new Unit("B", 1), new Unit("kB", 1e3),
                    new Unit("MB", 1e6), new Unit("GB", 1e9), new Unit("TB", 1e12),
                    new Unit("KiB", 1024), new Unit("MiB", 1048576),
                    new Unit("GiB", 1073741824.0)),
            new Category(R.string.cat_energy, 3, 1, false,
                    new Unit("J", 1), new Unit("kJ", 1000), new Unit("cal", 4.184),
                    new Unit("kcal", 4184), new Unit("Wh", 3600), new Unit("kWh", 3.6e6)),
    };

    /** value in unit a of category c, in unit b */
    static double convert(Category c, int a, int b, double value) {
        if (c.temperature) {
            return fromKelvin(b, toKelvin(a, value));
        }
        return value * c.units[a].factor / c.units[b].factor;
    }

    private static double toKelvin(int unit, double v) {
        switch (unit) {
            case 0:
                return v + 273.15;
            case 1:
                return (v - 32) * 5 / 9 + 273.15;
            default:
                return v;
        }
    }

    private static double fromKelvin(int unit, double k) {
        switch (unit) {
            case 0:
                return k - 273.15;
            case 1:
                return (k - 273.15) * 9 / 5 + 32;
            default:
                return k;
        }
    }

    /** 10 significant digits, no trailing zeros, E notation only far from 1 */
    static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "—";
        }
        if (v == 0) {
            return "0";
        }
        final BigDecimal d = new BigDecimal(v).round(new MathContext(10));
        final double abs = Math.abs(v);
        String s;
        if (abs >= 1e15 || abs < 1e-7) {
            s = String.format(java.util.Locale.US, "%.6e", d.doubleValue())
                    .replaceAll("\\.?0+e", "e").replace("e+0", "e").replace("e+", "e")
                    .replace("e-0", "e-");
        } else {
            s = d.stripTrailingZeros().toPlainString();
        }
        return s.replace('-', '−');
    }
}
