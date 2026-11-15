package com.detraly.analytics.economy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Recognises economy commands such as {@code /pay Steve 250} from configured patterns like
 * {@code pay {target} {amount}}. Pure text matching: it never keeps the command it was given.
 *
 * <p>A pattern is words separated by spaces. The first word is the command label and must be plain text. The other
 * words are either plain text (matched without regard to case) or one of {@code {target}}, {@code {amount}} and
 * {@code {action}}. The input has to have exactly as many words as the pattern. The leading {@code /} and a
 * {@code namespace:} prefix on the first word are ignored. An amount is a plain decimal number; commas are removed
 * first. {@code {action}} is {@code give}, {@code take}, {@code set} or {@code reset}, and for {@code reset} the
 * amount may be left out.
 */
public final class CommandPatterns {
    public static final String ACTION_GIVE = "give";
    public static final String ACTION_TAKE = "take";
    public static final String ACTION_SET = "set";
    public static final String ACTION_RESET = "reset";

    private static final Pattern AMOUNT = Pattern.compile("(\\d+(\\.\\d*)?|\\.\\d+)");
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_.*-]{1,32}");

    /**
     * A matched command. {@code label} comes from the pattern, never from what was typed. {@code action} is null
     * for a pattern without {@code {action}}. {@code amount} is 0 for a reset.
     */
    public record Match(String label, String action, String target, double amount) {
    }

    private enum Kind { TEXT, TARGET, AMOUNT, ACTION }

    private record Token(Kind kind, String text) {
    }

    private record Compiled(String label, List<Token> tokens, List<Token> withoutAmount) {
    }

    private final List<Compiled> compiled;

    private CommandPatterns(List<Compiled> compiled) {
        this.compiled = compiled;
    }

    public static CommandPatterns none() {
        return new CommandPatterns(List.of());
    }

    /**
     * @param admin patterns for admin commands (they need {@code {action}} and {@code {target}}) when true, for
     *        pay commands (they need {@code {target}} and {@code {amount}}) when false
     * @param rejected receives each pattern that is not usable, may be null
     */
    public static CommandPatterns compile(List<String> patterns, boolean admin, List<String> rejected) {
        List<Compiled> out = new ArrayList<>();
        if (patterns != null) {
            for (String pattern : patterns) {
                Compiled c = compileOne(pattern, admin);
                if (c != null) {
                    out.add(c);
                } else if (rejected != null) {
                    rejected.add(String.valueOf(pattern));
                }
            }
        }
        return new CommandPatterns(List.copyOf(out));
    }

    public int size() {
        return compiled.size();
    }

    private static Compiled compileOne(String pattern, boolean admin) {
        if (pattern == null) {
            return null;
        }
        String[] words = pattern.trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return null;
        }
        String label = labelOf(words[0]);
        if (label == null || label.indexOf('{') >= 0 || !EconomyMessages.validLabel(label)) {
            return null;
        }
        List<Token> tokens = new ArrayList<>();
        tokens.add(new Token(Kind.TEXT, label));
        boolean target = false;
        boolean amount = false;
        boolean action = false;
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            switch (w.toLowerCase(Locale.ROOT)) {
                case "{target}":
                    if (target) {
                        return null;
                    }
                    target = true;
                    tokens.add(new Token(Kind.TARGET, null));
                    break;
                case "{amount}":
                    if (amount) {
                        return null;
                    }
                    amount = true;
                    tokens.add(new Token(Kind.AMOUNT, null));
                    break;
                case "{action}":
                    if (action) {
                        return null;
                    }
                    action = true;
                    tokens.add(new Token(Kind.ACTION, null));
                    break;
                default:
                    if (w.indexOf('{') >= 0 || w.indexOf('}') >= 0) {
                        return null;
                    }
                    tokens.add(new Token(Kind.TEXT, w.toLowerCase(Locale.ROOT)));
                    break;
            }
        }
        if (admin ? !(action && target) : !(target && amount) || action) {
            return null;
        }
        List<Token> withoutAmount = null;
        if (admin && amount) {
            withoutAmount = new ArrayList<>(tokens);
            withoutAmount.removeIf(t -> t.kind() == Kind.AMOUNT);
        }
        return new Compiled(label, List.copyOf(tokens), withoutAmount == null ? null : List.copyOf(withoutAmount));
    }

    /** The command label of a typed word: no leading slash, no namespace prefix, lower case. */
    private static String labelOf(String word) {
        String w = word;
        while (w.startsWith("/")) {
            w = w.substring(1);
        }
        int colon = w.indexOf(':');
        if (colon >= 0) {
            w = w.substring(colon + 1);
        }
        w = w.toLowerCase(Locale.ROOT);
        return w.isEmpty() ? null : w;
    }

    /** The first pattern the command line matches, or null. */
    public Match match(String commandLine) {
        if (commandLine == null || compiled.isEmpty()) {
            return null;
        }
        String trimmed = commandLine.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512) {
            return null;
        }
        String[] words = trimmed.split("\\s+");
        String label = labelOf(words[0]);
        if (label == null) {
            return null;
        }
        for (Compiled c : compiled) {
            if (!c.label().equals(label)) {
                continue;
            }
            Match m = tryTokens(c, c.tokens(), words, false);
            if (m == null && c.withoutAmount() != null) {
                m = tryTokens(c, c.withoutAmount(), words, true);
            }
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static Match tryTokens(Compiled c, List<Token> tokens, String[] words, boolean noAmountWord) {
        if (tokens.size() != words.length) {
            return null;
        }
        String target = null;
        String action = null;
        String amountText = null;
        for (int i = 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            String w = words[i];
            switch (t.kind()) {
                case TEXT:
                    if (!t.text().equals(w.toLowerCase(Locale.ROOT))) {
                        return null;
                    }
                    break;
                case TARGET:
                    if (!TARGET.matcher(w).matches()) {
                        return null;
                    }
                    target = w;
                    break;
                case AMOUNT:
                    amountText = w;
                    break;
                case ACTION:
                    action = w.toLowerCase(Locale.ROOT);
                    if (!isAction(action)) {
                        return null;
                    }
                    break;
                default:
                    return null;
            }
        }
        boolean reset = ACTION_RESET.equals(action);
        if (noAmountWord && !reset) {
            return null;
        }
        double amount = 0.0;
        if (reset) {
            amount = 0.0;
        } else if (amountText != null) {
            Double parsed = parseAmount(amountText);
            if (parsed == null) {
                return null;
            }
            amount = parsed;
        } else if (action != null) {
            return null;
        }
        if (action == null && amount <= 0.0) {
            return null;
        }
        return new Match(c.label(), action, target, amount);
    }

    private static boolean isAction(String a) {
        return a.equals(ACTION_GIVE) || a.equals(ACTION_TAKE) || a.equals(ACTION_SET) || a.equals(ACTION_RESET);
    }

    /** A plain decimal number with commas removed, from 0 to 1e15; otherwise null. */
    static Double parseAmount(String text) {
        String s = text.replace(",", "");
        if (!AMOUNT.matcher(s).matches()) {
            return null;
        }
        try {
            double v = new BigDecimal(s.endsWith(".") ? s.substring(0, s.length() - 1) : s).doubleValue();
            return Double.isFinite(v) && v <= EconomyMessages.MAX_ABS_AMOUNT ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
