package com.detraly.analytics.storage;

public final class JsonDepth {

    public static final int MAX_DEPTH = 16;

    private JsonDepth() {
    }

    public static boolean exceeds(String text, int max) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '{') {
                if (++depth > max) {
                    return true;
                }
            } else if (c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            }
        }
        return false;
    }
}
