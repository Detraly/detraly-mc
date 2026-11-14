package com.detraly.analytics.pulse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConsoleRedactor {

    static final int MAX_LINE_BYTES = 4000;

    private static final int MAX_WORK_CHARS = 20_000;

    private static final String IP_MARK = "[ip hidden]";
    private static final String TOKEN_MARK = "[token hidden]";

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern MC_COLOUR = Pattern.compile("§[0-9A-FK-ORXa-fk-orx]");
    private static final Pattern TOKEN = Pattern.compile("dtl_[A-Za-z0-9_-]{20,}");

    private static final Pattern LINK_COMMAND = Pattern.compile(
            "\\bdetraly[\\s\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]+link\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PLAYER_INPUT = Pattern.compile(
            "issued server command:|^(?:\\[Not Secure\\] )?<[^>\\s]{1,40}> "

                    + "|/(?:login|register|changepassword|changepass|unregister|passwd|cpw)\\b\\s*\\S",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])(?::[0-9]{1,5})?");

    private static final Pattern IPV6 = Pattern.compile(
            "(?:(?<![0-9A-Za-z:])|(?<=[A-Za-z]:))[0-9A-Fa-f:]{3,}(?:%[0-9A-Za-z._-]+)?(?![0-9A-Za-z])");

    private ConsoleRedactor() {
    }

    public static List<String> clean(String message) {
        List<String> out = new ArrayList<>();
        if (message == null || message.isEmpty()) {
            return out;
        }

        if (LINK_COMMAND.matcher(visibleText(message)).find()) {
            return out;
        }
        for (String part : message.split("\\R")) {
            String line = cleanLine(part);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    static String cleanLine(String raw) {
        String s = visibleText(raw.length() > MAX_WORK_CHARS ? raw.substring(0, MAX_WORK_CHARS) : raw);
        if (PLAYER_INPUT.matcher(s).find() || LINK_COMMAND.matcher(s).find()) {
            return null;
        }
        s = TOKEN.matcher(s).replaceAll(TOKEN_MARK);
        s = IPV4.matcher(s).replaceAll(IP_MARK);
        s = IPV6.matcher(s).replaceAll((MatchResult m) ->
                looksLikeIpv6(m.group()) ? IP_MARK : Matcher.quoteReplacement(m.group()));
        s = truncateUtf8(s, MAX_LINE_BYTES);
        return s.trim().isEmpty() ? null : s;
    }

    private static boolean looksLikeIpv6(String candidate) {
        int zone = candidate.indexOf('%');
        String run = zone >= 0 ? candidate.substring(0, zone) : candidate;
        int colons = 0;
        int group = 0;
        for (int i = 0; i < run.length(); i++) {
            if (run.charAt(i) == ':') {
                colons++;
                group = 0;
            } else if (++group > 5) {
                return false;
            }
        }
        return run.contains("::") || colons >= 3;
    }

    private static String visibleText(String raw) {
        String s = ANSI.matcher(raw).replaceAll("");
        s = MC_COLOUR.matcher(s).replaceAll("");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t' || cp == '\n' || cp == '\r') {
                sb.append((char) cp);
            } else if (cp < 0x20 || cp == 0x7f || (cp >= 0x80 && cp <= 0x9f)
                    || (cp >= 0xD800 && cp <= 0xDFFF) || Character.getType(cp) == Character.FORMAT) {

                continue;
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    static String truncateUtf8(String s, int maxBytes) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return s;
        }
        int bytes = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }
}
