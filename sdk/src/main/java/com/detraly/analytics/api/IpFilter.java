package com.detraly.analytics.api;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

public final class IpFilter {

    private IpFilter() {
    }

    public static boolean isPublicAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (ip.indexOf(':') >= 0) {
            int[] words = parseIpv6(ip);
            if (words == null) {
                return false;
            }
            return !isNonPublicV6(words);
        }
        int[] v4 = parseIpv4(ip);
        return v4 != null && !isNonPublicV4(v4);
    }

    public static boolean mayTransmitIp(String apiBaseUrl) {
        return ApiUrlPolicy.isAllowed(apiBaseUrl);
    }

    public static String hostAddressOf(InetSocketAddress address) {
        if (address == null || address.isUnresolved()) {
            return null;
        }
        InetAddress inet = address.getAddress();
        if (inet == null) {
            return null;
        }
        String host = inet.getHostAddress();
        if (host == null) {
            return null;
        }
        int zone = host.indexOf('%');
        if (zone >= 0) {
            host = host.substring(0, zone);
        }
        host = host.replace("[", "").replace("]", "").trim();
        return host.isEmpty() ? null : host;
    }

    private static int[] parseIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || (p.length() > 1 && p.charAt(0) == '0')) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    private static int[] parseIpv6(String s) {
        int dc = s.indexOf("::");
        if (dc != s.lastIndexOf("::")) {
            return null;
        }
        List<Integer> head;
        List<Integer> tail = new ArrayList<>();
        if (dc < 0) {
            head = parseGroups(s, true);
            if (head == null || head.size() != 8) {
                return null;
            }
        } else {
            String left = s.substring(0, dc);
            String right = s.substring(dc + 2);
            head = parseGroups(left, false);
            List<Integer> r = parseGroups(right, true);
            if (head == null || r == null) {
                return null;
            }
            tail = r;
            if (head.size() + tail.size() > 7) {
                return null;
            }
        }
        int[] words = new int[8];
        for (int i = 0; i < head.size(); i++) {
            words[i] = head.get(i);
        }
        for (int i = 0; i < tail.size(); i++) {
            words[8 - tail.size() + i] = tail.get(i);
        }
        return words;
    }

    private static List<Integer> parseGroups(String s, boolean allowV4Last) {
        List<Integer> words = new ArrayList<>();
        if (s.isEmpty()) {
            return words;
        }
        String[] parts = s.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                return null;
            }
            if (allowV4Last && i == parts.length - 1 && p.indexOf('.') >= 0) {
                int[] v4 = parseIpv4(p);
                if (v4 == null) {
                    return null;
                }
                words.add((v4[0] << 8) | v4[1]);
                words.add((v4[2] << 8) | v4[3]);
                continue;
            }
            if (p.length() > 4) {
                return null;
            }
            int v = 0;
            for (int j = 0; j < p.length(); j++) {
                int d = Character.digit(p.charAt(j), 16);
                if (d < 0 || p.charAt(j) > 'f') {
                    return null;
                }
                v = v * 16 + d;
            }
            words.add(v);
        }
        return words;
    }

    private static boolean isNonPublicV4(int[] b) {
        int a = b[0];
        int c = b[1];
        return a == 0
                || a == 10
                || (a == 100 && (c & 0xC0) == 64)
                || a == 127
                || (a == 169 && c == 254)
                || (a == 172 && (c & 0xF0) == 16)
                || (a == 192 && c == 0 && b[2] == 0)
                || (a == 192 && c == 168)
                || (a == 198 && (c == 18 || c == 19))
                || a >= 224;
    }

    private static boolean isNonPublicV6(int[] w) {
        boolean firstSixZero = true;
        for (int i = 0; i < 6; i++) {
            if (w[i] != 0) {
                firstSixZero = false;
                break;
            }
        }

        if (firstSixZero) {
            return true;
        }

        if (w[0] == 0 && w[1] == 0 && w[2] == 0 && w[3] == 0 && w[4] == 0 && w[5] == 0xFFFF) {
            int[] v4 = {w[6] >> 8, w[6] & 0xFF, w[7] >> 8, w[7] & 0xFF};
            return isNonPublicV4(v4);
        }

        if (w[0] == 0x0064 && w[1] == 0xFF9B && w[2] == 0 && w[3] == 0 && w[4] == 0 && w[5] == 0) {
            return true;
        }

        if (w[0] == 0x0064 && w[1] == 0xFF9B && w[2] == 0x0001) {
            return true;
        }

        if (w[0] == 0x0100 && w[1] == 0 && w[2] == 0 && w[3] == 0) {
            return true;
        }

        if (w[0] == 0x2001 && w[1] == 0) {
            return true;
        }
        return w[0] == 0x2002
                || (w[0] & 0xFE00) == 0xFC00
                || (w[0] & 0xFFC0) == 0xFE80
                || (w[0] & 0xFFC0) == 0xFEC0
                || (w[0] & 0xFF00) == 0xFF00;
    }
}
