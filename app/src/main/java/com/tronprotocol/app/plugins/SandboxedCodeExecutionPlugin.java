package com.tronprotocol.app.plugins;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import android.util.Base64;

/**
 * Restricted sandbox-like execution plugin.
 * Does not execute native shell or arbitrary process code.
 */
public class SandboxedCodeExecutionPlugin implements Plugin {
    private static final String ID = "sandbox_exec";
    private static final int MAX_INPUT = 4000;
    private boolean enabled = true;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Sandbox Exec";
    }

    @Override
    public String getDescription() {
        return "Restricted execution primitives. Commands: calc|expression, json_get|json|field, b64_encode|text, b64_decode|text, upper|text, lower|text";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public PluginResult execute(String input) {
        long start = System.currentTimeMillis();
        try {
            if (TextUtils.isEmpty(input)) {
                return PluginResult.error("No command provided", elapsed(start));
            }
            if (input.length() > MAX_INPUT) {
                return PluginResult.error("Input too large", elapsed(start));
            }

            String[] parts = input.split("\\|", 3);
            String command = parts[0].trim().toLowerCase();
            switch (command) {
                case "calc":
                    if (parts.length < 2) return PluginResult.error("Usage: calc|expression", elapsed(start));
                    return PluginResult.success(String.valueOf(evalMath(parts[1].trim())), elapsed(start));
                case "json_get":
                    if (parts.length < 3) return PluginResult.error("Usage: json_get|json|field", elapsed(start));
                    JSONObject json = new JSONObject(parts[1]);
                    if (!json.has(parts[2])) {
                        return PluginResult.error("Field not found: " + parts[2], elapsed(start));
                    }
                    return PluginResult.success(String.valueOf(json.get(parts[2])), elapsed(start));
                case "b64_encode":
                    if (parts.length < 2) return PluginResult.error("Usage: b64_encode|text", elapsed(start));
                    return PluginResult.success(Base64.encodeToString(parts[1].getBytes(), Base64.NO_WRAP), elapsed(start));
                case "b64_decode":
                    if (parts.length < 2) return PluginResult.error("Usage: b64_decode|text", elapsed(start));
                    return PluginResult.success(new String(Base64.decode(parts[1], Base64.DEFAULT)), elapsed(start));
                case "upper":
                    if (parts.length < 2) return PluginResult.error("Usage: upper|text", elapsed(start));
                    return PluginResult.success(parts[1].toUpperCase(), elapsed(start));
                case "lower":
                    if (parts.length < 2) return PluginResult.error("Usage: lower|text", elapsed(start));
                    return PluginResult.success(parts[1].toLowerCase(), elapsed(start));
                default:
                    return PluginResult.error("Unknown command: " + command, elapsed(start));
            }
        } catch (Exception e) {
            return PluginResult.error("Sandbox execution failed: " + e.getMessage(), elapsed(start));
        }
    }

    private double evalMath(String expression) {
        if (!expression.matches("[0-9+\\-*/(). ]+")) {
            throw new IllegalArgumentException("Expression contains unsupported characters");
        }
        return new ExpressionParser(expression).parse();
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    @Override
    public void initialize(Context context) {
        // No-op
    }

    @Override
    public void destroy() {
        // No-op
    }

    /**
     * Small math parser supporting +, -, *, / and parentheses.
     */
    private static class ExpressionParser {
        private final String s;
        private int pos = -1;
        private int ch;

        ExpressionParser(String s) {
            this.s = s;
        }

        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < s.length()) throw new RuntimeException("Unexpected: " + (char) ch);
            return x;
        }

        private void nextChar() {
            ch = (++pos < s.length()) ? s.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        private double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else return x;
            }
        }

        private double parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return -parseFactor();

            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                eat(')');
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(s.substring(startPos, this.pos));
            } else {
                throw new RuntimeException("Unexpected: " + (char) ch);
            }

            return x;
        }
    }
}
