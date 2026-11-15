package com.detraly.analytics.economy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Builds the {@code economy_info} and {@code economy} Pulse messages and holds the field rules they share. */
public final class EconomyMessages {
    /** Events in one message. */
    public static final int MAX_EVENTS = 100;

    public static final double MAX_ABS_AMOUNT = 1e15;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*-]{1,32}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_:-]{1,32}");

    private EconomyMessages() {
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public static boolean validLabel(String label) {
        return label != null && LABEL.matcher(label).matches();
    }

    /** A finite number the API accepts as a balance. */
    public static boolean validBalance(double value) {
        return Double.isFinite(value) && Math.abs(value) <= MAX_ABS_AMOUNT;
    }

    public static JSONObject info(EconomyHost.Info info) {
        String provider = text(info.provider(), 128);
        JSONObject o = new JSONObject();
        o.put("type", "economy_info");
        o.put("provider", provider.isEmpty() ? "Unknown economy" : provider);
        o.put("currency_singular", text(info.currencySingular(), 64));
        o.put("currency_plural", text(info.currencyPlural(), 64));
        o.put("fractional_digits", Math.max(-1, Math.min(8, info.fractionalDigits())));
        o.put("bank_support", info.bankSupport());
        return o;
    }

    /** One {@code economy} message. At most {@link #MAX_EVENTS} events: about 50 KB at the largest, under the limit. */
    public static JSONObject batch(long id, List<EconomyEvent> events) {
        JSONArray array = new JSONArray();
        for (EconomyEvent event : events) {
            array.put(event.toJson());
        }
        JSONObject o = new JSONObject();
        o.put("type", "economy");
        o.put("id", id);
        o.put("events", array);
        return o;
    }

    /**
     * Text that follows the specs text rule: no control, format or separator character other than a plain space, no
     * {@code <} or {@code >}, no section sign, no space at either end, at most {@code maxBytes} UTF-8 bytes.
     * May return an empty string.
     */
    public static String text(String value, int maxBytes) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            if (!allowed(cp)) {
                continue;
            }
            int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            bytes += size;
        }
        return sb.toString().trim();
    }

    private static boolean allowed(int cp) {
        if (cp == '<' || cp == '>' || cp == 0xA7) {
            return false;
        }
        if (cp == ' ') {
            return true;
        }
        switch (Character.getType(cp)) {
            case Character.CONTROL:
            case Character.FORMAT:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SPACE_SEPARATOR:
            case Character.SURROGATE:
            case Character.UNASSIGNED:
            case Character.PRIVATE_USE:
                return false;
            default:
                return true;
        }
    }
}
