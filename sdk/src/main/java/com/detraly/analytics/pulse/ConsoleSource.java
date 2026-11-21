package com.detraly.analytics.pulse;

/** Cleans a logger name for the optional {@code source} field of a Pulse console line (feature console_source). */
final class ConsoleSource {
    static final int MAX_LENGTH = 64;

    /** Returns 1 to 64 characters from [A-Za-z0-9_.$ -] (anything else becomes '_'), or null when empty. */
    static String clean(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        int length = Math.min(name.length(), MAX_LENGTH);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '$' || c == ' ' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    private ConsoleSource() {
    }
}
