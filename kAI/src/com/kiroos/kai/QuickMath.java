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

package com.kiroos.kai;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * Arithmetic kAI answers itself, exactly and at once: small language models get sums wrong
 * (Llama 3.2 1B read "17 per 23" as a division). Understands digits with + - x * / : ^ %,
 * parentheses, "per", "diviso", "più", "meno", "alla", "radice di", "percento di" and their
 * English forms. Returns the answer line, or null when the text is not a calculation.
 */
final class QuickMath {

    private QuickMath() {
    }

    static String answer(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        t = t.replaceAll("^(quanto fa|quanto vale|calcola|calculate|what is|what's|quanto è)\\s*", "");
        t = t.replaceAll("[?=!]+\\s*$", "").trim();
        if (!t.matches(".*\\d.*") || t.length() > 80) {
            return null;
        }
        // words to operators
        t = t.replaceAll("\\bradice (quadrata )?di\\b|\\bsquare root of\\b|√", " sqrt ")
                .replaceAll("\\b(\\d+(?:[.,]\\d+)?)\\s*(%|per ?cento) di\\b", "$1 % ")
                .replaceAll("\\b(\\d+(?:[.,]\\d+)?)\\s*(percent|per cent) of\\b", "$1 % ")
                .replaceAll("\\bdiviso( per)?\\b|\\bdivided by\\b", " / ")
                .replaceAll("\\bper\\b|\\btimes\\b|\\bmultiplied by\\b|×|\\bx\\b", " * ")
                .replaceAll("\\bpi[uù]\\b|\\bplus\\b", " + ")
                .replaceAll("\\bmeno\\b|\\bminus\\b", " - ")
                .replaceAll("\\balla\\b|\\bto the power of\\b|\\belevato a\\b", " ^ ")
                .replaceAll("(\\d),(\\d)", "$1.$2")
                .replace(':', '/');
        if (!t.matches("[\\d\\s.+\\-*/^()%sqrt]+")) {
            return null;                      // other words left: a real question
        }
        try {
            final Parser p = new Parser(t);
            final BigDecimal v = p.expr();
            if (!p.done()) {
                return null;
            }
            final String shown = text.replaceAll("[?=!]+\\s*$", "").trim();
            return shown + " = **" + format(v) + "**";
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String format(BigDecimal v) {
        final BigDecimal r = v.round(new MathContext(12)).stripTrailingZeros();
        final String s = r.abs().compareTo(new BigDecimal("1e15")) >= 0 ? r.toString()
                : r.toPlainString();
        return s.replace('.', ',');
    }

    /** recursive descent: expr = term (+|- term)*, term = factor (*|/|% factor)*, power ^ */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s.replaceAll("\\s+", "");
        }

        boolean done() {
            return i == s.length();
        }

        private boolean eat(String tok) {
            if (s.startsWith(tok, i)) {
                i += tok.length();
                return true;
            }
            return false;
        }

        BigDecimal expr() {
            BigDecimal v = term();
            for (;;) {
                if (eat("+")) {
                    v = v.add(term());
                } else if (eat("-")) {
                    v = v.subtract(term());
                } else {
                    return v;
                }
            }
        }

        BigDecimal term() {
            BigDecimal v = power();
            for (;;) {
                if (eat("*")) {
                    v = v.multiply(power());
                } else if (eat("/")) {
                    v = v.divide(power(), MathContext.DECIMAL64);
                } else if (eat("%")) {
                    // "20 % 150" = 20 percent of 150
                    v = v.multiply(power()).divide(BigDecimal.valueOf(100), MathContext.DECIMAL64);
                } else {
                    return v;
                }
            }
        }

        BigDecimal power() {
            final BigDecimal base = unary();
            if (eat("^")) {
                final BigDecimal e = power();
                if (e.stripTrailingZeros().scale() <= 0 && e.abs().intValue() <= 999) {
                    final int n = e.intValue();
                    return n >= 0 ? base.pow(n, MathContext.DECIMAL64)
                            : BigDecimal.ONE.divide(base.pow(-n, MathContext.DECIMAL64),
                                    MathContext.DECIMAL64);
                }
                return BigDecimal.valueOf(Math.pow(base.doubleValue(), e.doubleValue()));
            }
            return base;
        }

        BigDecimal unary() {
            if (eat("-")) {
                return unary().negate();
            }
            if (eat("+")) {
                return unary();
            }
            if (eat("sqrt")) {
                final BigDecimal x = unary();
                if (x.signum() < 0) {
                    throw new ArithmeticException("sqrt");
                }
                return BigDecimal.valueOf(Math.sqrt(x.doubleValue()));
            }
            if (eat("(")) {
                final BigDecimal v = expr();
                if (!eat(")")) {
                    throw new IllegalArgumentException(")");
                }
                return v;
            }
            final int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("number");
            }
            return new BigDecimal(s.substring(start, i));
        }
    }
}
