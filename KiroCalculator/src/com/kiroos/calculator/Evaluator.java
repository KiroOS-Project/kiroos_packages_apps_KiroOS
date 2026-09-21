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
import java.math.RoundingMode;

/**
 * Recursive-descent evaluator for what the keypad types:
 * <pre>
 * expr   := term (('+' | '−') term)*
 * term   := unary (('×' | '÷') unary | implicit-multiplication)*
 * unary  := ('−' | '+') unary | power
 * power  := postfix ('^' unary)?
 * postfix:= primary ('%' | '!')*
 * primary:= number | 'π' | 'e' | func '(' expr ')' | '(' expr ')' | '√' unary
 * </pre>
 * Missing closing parentheses are allowed (as on paper). Angles are in degrees or radians.
 */
final class Evaluator {

    static final class SyntaxException extends Exception {
        SyntaxException(String msg) {
            super(msg);
        }
    }

    private final String mSrc;
    private final boolean mDegrees;
    private int mPos;

    private Evaluator(String src, boolean degrees) {
        mSrc = src;
        mDegrees = degrees;
    }

    static double evaluate(String expression, boolean degrees) throws SyntaxException {
        final Evaluator e = new Evaluator(expression.replace(" ", ""), degrees);
        if (e.mSrc.isEmpty()) {
            throw new SyntaxException("empty");
        }
        final double v = e.expr();
        if (e.mPos < e.mSrc.length()) {
            throw new SyntaxException("unexpected " + e.mSrc.charAt(e.mPos));
        }
        return v;
    }

    /** 12 significant digits, no trailing zeros, scientific notation for huge/tiny values. */
    static String format(double v) {
        if (Double.isNaN(v)) {
            return null;
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "∞" : "−∞";
        }
        if (v == 0) {
            return "0";
        }
        BigDecimal b = new BigDecimal(v).round(new MathContext(12, RoundingMode.HALF_EVEN));
        final double abs = Math.abs(v);
        String s;
        if (abs >= 1e12 || abs < 1e-9) {
            s = String.format(java.util.Locale.US, "%.8E", v)
                    .replaceAll("\\.?0+E", "E").replace("E+", "E");
        } else {
            b = b.stripTrailingZeros();
            s = b.toPlainString();
        }
        return s.replace('-', '−');
    }

    private boolean eat(char c) {
        if (mPos < mSrc.length() && mSrc.charAt(mPos) == c) {
            mPos++;
            return true;
        }
        return false;
    }

    private boolean eat(String s) {
        if (mSrc.startsWith(s, mPos)) {
            mPos += s.length();
            return true;
        }
        return false;
    }

    private double expr() throws SyntaxException {
        double v = term();
        while (true) {
            if (eat('+')) {
                v += term();
            } else if (eat('−') || eat('-')) {
                v -= term();
            } else {
                return v;
            }
        }
    }

    private double term() throws SyntaxException {
        double v = unary();
        while (true) {
            if (eat('×') || eat('*')) {
                v *= unary();
            } else if (eat('÷') || eat('/')) {
                final double d = unary();
                if (d == 0) {
                    throw new SyntaxException("division by zero");
                }
                v /= d;
            } else if (startsPrimary()) {
                v *= unary(); // 2π, 3(4+1), (1+2)(3+4)
            } else {
                return v;
            }
        }
    }

    private boolean startsPrimary() {
        if (mPos >= mSrc.length()) {
            return false;
        }
        final char c = mSrc.charAt(mPos);
        return c == '(' || c == 'π' || c == 'e' || c == '√' || Character.isLetter(c)
                || Character.isDigit(c) || c == '.';
    }

    private double unary() throws SyntaxException {
        if (eat('−') || eat('-')) {
            return -unary();
        }
        if (eat('+')) {
            return unary();
        }
        return power();
    }

    private double power() throws SyntaxException {
        final double base = postfix();
        if (eat('^')) {
            return Math.pow(base, unary());
        }
        return base;
    }

    private double postfix() throws SyntaxException {
        double v = primary();
        while (true) {
            if (eat('%')) {
                v /= 100.0;
            } else if (eat('!')) {
                v = factorial(v);
            } else {
                return v;
            }
        }
    }

    private double primary() throws SyntaxException {
        if (eat('(')) {
            final double v = expr();
            eat(')'); // may be left open
            return v;
        }
        if (eat('π')) {
            return Math.PI;
        }
        if (eat('√')) {
            return Math.sqrt(unary());
        }
        final String[] funcs = {"asin", "acos", "atan", "sin", "cos", "tan", "ln", "log"};
        for (String f : funcs) {
            if (eat(f)) {
                if (!eat('(')) {
                    throw new SyntaxException("( expected");
                }
                final double arg = expr();
                eat(')');
                return apply(f, arg);
            }
        }
        if (mPos < mSrc.length() && mSrc.charAt(mPos) == 'e'
                && !(mPos + 1 < mSrc.length() && Character.isLetter(mSrc.charAt(mPos + 1)))) {
            mPos++;
            return Math.E;
        }
        return number();
    }

    private double number() throws SyntaxException {
        final int start = mPos;
        while (mPos < mSrc.length()
                && (Character.isDigit(mSrc.charAt(mPos)) || mSrc.charAt(mPos) == '.')) {
            mPos++;
        }
        // scientific notation from a previous result: 1.5E12
        if (mPos < mSrc.length() && mSrc.charAt(mPos) == 'E') {
            mPos++;
            if (mPos < mSrc.length() && (mSrc.charAt(mPos) == '−' || mSrc.charAt(mPos) == '-')) {
                mPos++;
            }
            while (mPos < mSrc.length() && Character.isDigit(mSrc.charAt(mPos))) {
                mPos++;
            }
        }
        if (start == mPos) {
            throw new SyntaxException(mPos < mSrc.length() ? "unexpected " + mSrc.charAt(mPos)
                    : "number expected");
        }
        try {
            return Double.parseDouble(mSrc.substring(start, mPos).replace('−', '-'));
        } catch (NumberFormatException e) {
            throw new SyntaxException("bad number");
        }
    }

    private double apply(String f, double x) {
        final double toRad = mDegrees ? Math.PI / 180 : 1;
        final double fromRad = mDegrees ? 180 / Math.PI : 1;
        switch (f) {
            case "sin":
                return clean(Math.sin(x * toRad));
            case "cos":
                return clean(Math.cos(x * toRad));
            case "tan":
                return clean(Math.tan(x * toRad));
            case "asin":
                return Math.asin(x) * fromRad;
            case "acos":
                return Math.acos(x) * fromRad;
            case "atan":
                return Math.atan(x) * fromRad;
            case "ln":
                return Math.log(x);
            default:
                return Math.log10(x);
        }
    }

    /** sin(180°) is 1.2e-16 in floating point; show 0 */
    private static double clean(double v) {
        return Math.abs(v) < 1e-12 ? 0 : v;
    }

    private static double factorial(double v) throws SyntaxException {
        if (v < 0 || v != Math.floor(v)) {
            throw new SyntaxException("factorial of a non-integer");
        }
        if (v > 170) {
            return Double.POSITIVE_INFINITY;
        }
        double r = 1;
        for (int i = 2; i <= (int) v; i++) {
            r *= i;
        }
        return r;
    }
}
