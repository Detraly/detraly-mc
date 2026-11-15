package com.detraly.analytics.economy;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * What the platform offers to the economy tracker. Every method is called on the main server thread only, because
 * an economy provider gives no thread-safety guarantee. A platform without an economy returns {@code null} from
 * {@link com.detraly.analytics.platform.Platform#economyHost()}.
 */
public interface EconomyHost {
    /** What the provider is. The text fields are cleaned before they are sent. */
    record Info(String provider, String currencySingular, String currencyPlural, int fractionalDigits,
            boolean bankSupport) {
    }

    /** A player that can have a balance. */
    record Known(UUID uuid, String name) {
    }

    /** The current provider, or {@code null} when there is none (yet). Must be cheap: it is polled. */
    Info info();

    /**
     * The balance of a player, or empty when it cannot be read.
     *
     * @param checkAccount ask the provider whether the account exists first, and answer empty when it does not.
     *        The tracker passes false for players who are online.
     */
    OptionalDouble balance(UUID id, boolean checkAccount);

    /**
     * The ids of the players the server has data for, online or not. Called once per sweep, <b>off the main
     * thread</b>, so it must be thread-safe and must not touch the Bukkit API. Listing files is fine; looking up
     * names is not (that is {@link #knownPlayer(UUID)}).
     */
    List<UUID> knownPlayerIds();

    /**
     * One player listed by {@link #knownPlayerIds()}, with the name the server has for them, or {@code null} when
     * there is none. Called on the main thread, once per player, inside the tick budget.
     */
    Known knownPlayer(UUID id);

    /**
     * The player a command names: an online player by name, or a player whose name the server already has in
     * memory. Must never do a lookup that can wait for the network. {@code null} when unknown.
     */
    Known resolve(String name);
}
