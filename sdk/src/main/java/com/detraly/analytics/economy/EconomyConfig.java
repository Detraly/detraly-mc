package com.detraly.analytics.economy;

import com.detraly.analytics.config.ConfigSource;
import java.util.List;

/** The {@code economy} section of the config. Every key is optional. */
public record EconomyConfig(boolean enabled, int pollSeconds, int sweepHours, List<String> payCommands,
        List<String> adminCommands) {
    public static final int MIN_POLL_SECONDS = 10;
    public static final int DEFAULT_POLL_SECONDS = 30;
    public static final int MAX_POLL_SECONDS = 3600;
    public static final int DEFAULT_SWEEP_HOURS = 24;
    public static final int MAX_SWEEP_HOURS = 24 * 365;

    public static final List<String> DEFAULT_PAY_COMMANDS = List.of(
            "pay {target} {amount}", "epay {target} {amount}", "money pay {target} {amount}");
    public static final List<String> DEFAULT_ADMIN_COMMANDS = List.of(
            "eco {action} {target} {amount}", "economy {action} {target} {amount}",
            "eeco {action} {target} {amount}", "money {action} {target} {amount}");

    public static EconomyConfig load(ConfigSource config) {
        boolean enabled = config.getBoolean("economy.enabled", true);
        int poll = clamp(config.getInt("economy.poll-seconds", DEFAULT_POLL_SECONDS), MIN_POLL_SECONDS,
                MAX_POLL_SECONDS);
        int sweep = clamp(config.getInt("economy.sweep-hours", DEFAULT_SWEEP_HOURS), 0, MAX_SWEEP_HOURS);
        List<String> pay = config.getStringList("economy.pay-commands");
        List<String> admin = config.getStringList("economy.admin-commands");
        return new EconomyConfig(enabled, poll, sweep, pay == null ? DEFAULT_PAY_COMMANDS : List.copyOf(pay),
                admin == null ? DEFAULT_ADMIN_COMMANDS : List.copyOf(admin));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
