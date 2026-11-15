package com.detraly.analytics.economy;

import java.util.ArrayList;
import java.util.List;

public final class CommandPatternsCheck {
    private static int failures;

    public static void main(String[] args) {
        CommandPatterns pay = CommandPatterns.compile(EconomyConfig.DEFAULT_PAY_COMMANDS, false, null);
        CommandPatterns admin = CommandPatterns.compile(EconomyConfig.DEFAULT_ADMIN_COMMANDS, true, null);
        check("default pay patterns compile", pay.size() == 3);
        check("default admin patterns compile", admin.size() == 4);

        CommandPatterns.Match m = pay.match("/pay Alex 250");
        check("plain pay", m != null && m.label().equals("pay") && m.target().equals("Alex") && m.amount() == 250.0
                && m.action() == null);
        m = pay.match("PAY alex 1,250.50");
        check("case-insensitive, commas removed", m != null && m.amount() == 1250.5 && m.target().equals("alex"));
        m = pay.match("/essentials:pay Alex 5");
        check("namespace prefix is stripped", m != null && m.label().equals("pay"));
        m = pay.match("/ESSENTIALS:PAY Alex 5");
        check("label comes from the pattern, lower case", m != null && m.label().equals("pay"));
        m = pay.match("money pay Alex 5");
        check("two-word pay command", m != null && m.label().equals("money"));
        m = pay.match("  /pay   Alex   .5 ");
        check("extra spaces and a leading-dot decimal", m != null && m.amount() == 0.5);
        m = pay.match("pay Alex 5.");
        check("trailing dot decimal", m != null && m.amount() == 5.0);

        check("amount is not a number", pay.match("pay Alex abc") == null);
        check("negative amount", pay.match("pay Alex -5") == null);
        check("exponent amount", pay.match("pay Alex 1e5") == null);
        check("zero amount", pay.match("pay Alex 0") == null);
        check("amount above the limit", pay.match("pay Alex 10000000000000000") == null);
        check("extra word", pay.match("pay Alex 5 now") == null);
        check("missing amount", pay.match("pay Alex") == null);
        check("selector as target", pay.match("pay @a 5") == null);
        check("target too long", pay.match("pay " + "a".repeat(33) + " 5") == null);
        check("other command", pay.match("tell Alex 5") == null);
        check("empty line", pay.match("") == null && pay.match("   ") == null && pay.match("/") == null);
        check("null line", pay.match(null) == null);
        check("a pay command is not an admin command", admin.match("pay Alex 5") == null);
        check("money pay is not an admin action", admin.match("money pay Alex 5") == null);

        m = admin.match("eco give Alex 1000");
        check("eco give", m != null && m.label().equals("eco") && "give".equals(m.action())
                && m.target().equals("Alex") && m.amount() == 1000.0);
        m = admin.match("/Economy TAKE Alex 12.5");
        check("action is case-insensitive", m != null && "take".equals(m.action()) && m.amount() == 12.5);
        m = admin.match("eco set Alex 0");
        check("set to zero is allowed", m != null && "set".equals(m.action()) && m.amount() == 0.0);
        m = admin.match("eco reset Alex");
        check("reset has no amount", m != null && "reset".equals(m.action()) && m.amount() == 0.0);
        m = admin.match("eco reset Alex abc");
        check("reset ignores the amount word", m != null && "reset".equals(m.action()) && m.amount() == 0.0);
        check("give without amount", admin.match("eco give Alex") == null);
        check("unknown action", admin.match("eco gift Alex 5") == null);
        m = admin.match("money give Alex 5");
        check("money give", m != null && m.label().equals("money") && "give".equals(m.action()));

        List<String> rejected = new ArrayList<>();
        CommandPatterns.compile(List.of(
                "{target} pay {amount}",
                "pay {target}",
                "pay {target} {amount} {amount}",
                "pay{x} {target} {amount}",
                "pay! {target} {amount}",
                "pay {target} {amount} {action}",
                "",
                "   "), false, rejected);
        check("invalid pay patterns are rejected", rejected.size() == 8);
        rejected.clear();
        CommandPatterns.compile(List.of("eco {target} {amount}", "eco {action} {amount}", "eco {action} {target} {target}"),
                true, rejected);
        check("invalid admin patterns are rejected", rejected.size() == 3);

        CommandPatterns custom = CommandPatterns.compile(List.of("essentials:pay {target} {amount} now"), false, null);
        check("custom pattern with a plain word", custom.match("pay Bob 7 now") != null
                && custom.match("pay Bob 7") == null && custom.match("pay Bob 7 later") == null);

        CommandPatterns noAmount = CommandPatterns.compile(List.of("eco {action} {target}"), true, null);
        check("pattern without amount handles reset only", noAmount.match("eco reset Bob") != null
                && noAmount.match("eco give Bob") == null);

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}
