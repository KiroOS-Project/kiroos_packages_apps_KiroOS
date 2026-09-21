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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kiro Calculator: live result while typing, scientific functions (degrees / radians),
 * history of the last 50 calculations, squircle keys on black.
 */
public class CalculatorActivity extends Activity {

    private static final int HISTORY_SIZE = 50;
    private static final String SEP = "";

    private static final String[][] BASIC = {
            {"C", "( )", "%", "÷"},
            {"7", "8", "9", "×"},
            {"4", "5", "6", "−"},
            {"1", "2", "3", "+"},
            {"±", "0", ".", "="},
    };
    private static final String[][] SCIENTIFIC = {
            {"sin", "cos", "tan", "ln", "log"},
            {"√", "^", "π", "e", "!"},
    };

    private TextView mExpression;
    private TextView mResult;
    private TextView mAngle;
    private LinearLayout mScientific;
    private SharedPreferences mPrefs;
    private boolean mDegrees = true;
    private boolean mJustEvaluated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);
        mPrefs = getSharedPreferences("calc", MODE_PRIVATE);
        mDegrees = mPrefs.getBoolean("degrees", true);
        mExpression = (TextView) findViewById(R.id.expression);
        mResult = (TextView) findViewById(R.id.result);
        mAngle = (TextView) findViewById(R.id.angle);
        mScientific = (LinearLayout) findViewById(R.id.scientific);

        buildKeys((LinearLayout) findViewById(R.id.keys), BASIC, true);
        buildKeys(mScientific, SCIENTIFIC, false);
        mScientific.setVisibility(mPrefs.getBoolean("scientific", false) ? View.VISIBLE : View.GONE);

        findViewById(R.id.btn_scientific).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean show = mScientific.getVisibility() != View.VISIBLE;
                mScientific.setVisibility(show ? View.VISIBLE : View.GONE);
                mPrefs.edit().putBoolean("scientific", show).apply();
            }
        });
        mAngle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDegrees = !mDegrees;
                mPrefs.edit().putBoolean("degrees", mDegrees).apply();
                updateAngle();
                preview();
            }
        });
        final View back = findViewById(R.id.btn_backspace);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                onKey("⌫");
            }
        });
        back.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onKey("C");
                return true;
            }
        });
        findViewById(R.id.btn_history).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHistory();
            }
        });
        final View.OnLongClickListener copy = new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final CharSequence text = ((TextView) v).getText();
                if (!TextUtils.isEmpty(text)) {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText(null,
                                    text.toString().replace('−', '-').replace("=", "").trim()));
                    Toast.makeText(CalculatorActivity.this, R.string.copied,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        };
        mExpression.setOnLongClickListener(copy);
        mResult.setOnLongClickListener(copy);
        if (savedInstanceState != null) {
            mExpression.setText(savedInstanceState.getString("expr", ""));
        }
        updateAngle();
        preview();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("expr", mExpression.getText().toString());
    }

    private void updateAngle() {
        mAngle.setText(mDegrees ? R.string.deg : R.string.rad);
    }

    private void buildKeys(LinearLayout parent, String[][] rows, boolean big) {
        final float d = getResources().getDisplayMetrics().density;
        final int gap = Math.round(5 * d);
        for (String[] r : rows) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (final String key : r) {
                final TextView k = new TextView(this);
                k.setText(key);
                k.setGravity(Gravity.CENTER);
                k.setTextSize(TypedValue.COMPLEX_UNIT_SP, big ? (key.length() > 2 ? 22 : 30) : 17);
                k.setTextColor(getColor(isOperator(key) || key.equals("=") ? R.color.kiro_on_accent
                        : R.color.kiro_text));
                final int fill;
                if (key.equals("=")) {
                    fill = R.color.kiro_accent;
                } else if (isOperator(key)) {
                    fill = R.color.op_fill;
                } else if (key.equals("C") || key.equals("( )") || key.equals("%")
                        || key.equals("±") || !big) {
                    fill = R.color.fn_fill;
                } else {
                    fill = R.color.key_fill;
                }
                k.setBackground(SquircleDrawable.button(getColor(fill), getColor(R.color.kiro_ripple)));
                k.setContentDescription(describe(key));
                k.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        onKey(key);
                    }
                });
                KiroTouch.apply(k);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        big ? 0 : Math.round(44 * d), 1f);
                lp.setMargins(gap, gap, gap, gap);
                row.addView(k, lp);
            }
            parent.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    big ? 0 : LinearLayout.LayoutParams.WRAP_CONTENT, big ? 1f : 0f));
            if (big) {
                // keys of a big row stretch to the row height
                for (int i = 0; i < row.getChildCount(); i++) {
                    final LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
                    lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
                }
            }
        }
    }

    private static boolean isOperator(String k) {
        return k.equals("÷") || k.equals("×") || k.equals("−") || k.equals("+");
    }

    private String describe(String key) {
        switch (key) {
            case "C":
                return getString(R.string.desc_clear);
            case "÷":
                return getString(R.string.desc_divide);
            case "×":
                return getString(R.string.desc_times);
            case "−":
                return getString(R.string.desc_minus);
            case "+":
                return getString(R.string.desc_plus);
            case "=":
                return getString(R.string.desc_equals);
            case "±":
                return getString(R.string.desc_negate);
            case "( )":
                return getString(R.string.desc_parens);
            case "√":
                return getString(R.string.desc_sqrt);
            default:
                return key;
        }
    }

    // --- input ----------------------------------------------------------------------------

    private void onKey(String key) {
        String expr = mExpression.getText().toString();
        final boolean afterResult = mJustEvaluated;
        mJustEvaluated = false;
        switch (key) {
            case "C":
                expr = "";
                break;
            case "⌫":
                expr = expr.isEmpty() ? "" : backspace(expr);
                break;
            case "=":
                evaluate();
                return;
            case "( )":
                expr = expr + (shouldClose(expr) ? ")" : "(");
                break;
            case "±":
                expr = negateLast(expr);
                break;
            case "sin":
            case "cos":
            case "tan":
            case "ln":
            case "log":
                expr = (afterResult ? "" : expr) + key + "(";
                break;
            default:
                if (afterResult && (Character.isDigit(key.charAt(0)) || key.equals(".")
                        || key.equals("π") || key.equals("e") || key.equals("√"))) {
                    expr = ""; // a new number starts a new calculation
                }
                if (isOperator(key) && !expr.isEmpty() && isOperator(expr.substring(expr.length() - 1))) {
                    expr = expr.substring(0, expr.length() - 1); // replace the operator
                } else if (isOperator(key) && expr.isEmpty() && !key.equals("−")) {
                    return;
                }
                if (key.equals(".") && currentNumberHasDot(expr)) {
                    return;
                }
                expr = expr + key;
                break;
        }
        mExpression.setText(expr);
        fitExpression();
        preview();
    }

    private static String backspace(String expr) {
        for (String f : Arrays.asList("asin(", "acos(", "atan(", "sin(", "cos(", "tan(", "ln(", "log(")) {
            if (expr.endsWith(f)) {
                return expr.substring(0, expr.length() - f.length());
            }
        }
        return expr.substring(0, expr.length() - 1);
    }

    private static boolean shouldClose(String expr) {
        int open = 0;
        for (int i = 0; i < expr.length(); i++) {
            final char c = expr.charAt(i);
            if (c == '(') {
                open++;
            } else if (c == ')') {
                open--;
            }
        }
        if (open <= 0 || expr.isEmpty()) {
            return false;
        }
        final char last = expr.charAt(expr.length() - 1);
        return Character.isDigit(last) || last == ')' || last == 'π' || last == 'e'
                || last == '%' || last == '!';
    }

    private static boolean currentNumberHasDot(String expr) {
        for (int i = expr.length() - 1; i >= 0; i--) {
            final char c = expr.charAt(i);
            if (c == '.') {
                return true;
            }
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return false;
    }

    /** ± on the last number: 5+3 → 5+(−3 → 5+3 */
    private static String negateLast(String expr) {
        int i = expr.length();
        while (i > 0 && (Character.isDigit(expr.charAt(i - 1)) || expr.charAt(i - 1) == '.')) {
            i--;
        }
        if (i >= 2 && expr.startsWith("(−", i - 2)) {
            return expr.substring(0, i - 2) + expr.substring(i);
        }
        return expr.substring(0, i) + "(−" + expr.substring(i);
    }

    private void fitExpression() {
        final int len = mExpression.length();
        mExpression.setTextSize(TypedValue.COMPLEX_UNIT_SP, len > 16 ? 34 : len > 11 ? 44 : 56);
    }

    private void preview() {
        final String expr = mExpression.getText().toString();
        if (expr.isEmpty() || isPlainNumber(expr)) {
            mResult.setText("");
            return;
        }
        try {
            final String r = Evaluator.format(Evaluator.evaluate(trimTrailing(expr), mDegrees));
            mResult.setText(r != null ? "= " + r : "");
        } catch (Evaluator.SyntaxException e) {
            mResult.setText("");
        }
    }

    private static boolean isPlainNumber(String s) {
        return s.matches("−?[0-9.]+");
    }

    /** "5+" previews as "5" */
    private static String trimTrailing(String expr) {
        String s = expr;
        while (!s.isEmpty() && (isOperator(s.substring(s.length() - 1)) || s.endsWith("^")
                || s.endsWith("("))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void evaluate() {
        final String expr = mExpression.getText().toString();
        if (expr.isEmpty()) {
            return;
        }
        try {
            final String r = Evaluator.format(Evaluator.evaluate(expr, mDegrees));
            if (r == null) {
                mResult.setText(R.string.error_domain);
                return;
            }
            addHistory(expr + " = " + r);
            mExpression.setText(r);
            mResult.setText("");
            mJustEvaluated = true;
            fitExpression();
            KiroTouch.enter(mExpression);
        } catch (Evaluator.SyntaxException e) {
            mResult.setText("division by zero".equals(e.getMessage())
                    ? R.string.error_div_zero : R.string.error_syntax);
        }
    }

    // --- history --------------------------------------------------------------------------

    private List<String> history() {
        final String s = mPrefs.getString("history", "");
        return s.isEmpty() ? new ArrayList<String>()
                : new ArrayList<>(Arrays.asList(s.split(SEP)));
    }

    private void addHistory(String line) {
        final List<String> h = history();
        h.add(0, line);
        while (h.size() > HISTORY_SIZE) {
            h.remove(h.size() - 1);
        }
        mPrefs.edit().putString("history", TextUtils.join(SEP, h)).apply();
    }

    private void showHistory() {
        final List<String> h = history();
        if (h.isEmpty()) {
            Toast.makeText(this, R.string.no_history, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.history)
                .setItems(h.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        // reuse the result of a past calculation
                        final String line = h.get(which);
                        final String result = line.substring(line.lastIndexOf("= ") + 2);
                        final String cur = mExpression.getText().toString();
                        mExpression.setText(mJustEvaluated ? result : cur + result);
                        mJustEvaluated = false;
                        fitExpression();
                        preview();
                    }
                })
                .setNeutralButton(R.string.clear_history, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mPrefs.edit().remove("history").apply();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
