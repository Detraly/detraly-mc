package com.detraly.analytics.vault;

import com.detraly.analytics.economy.EconomyHost;
import java.util.OptionalDouble;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The only class that uses Vault types. It is loaded only after {@link VaultEconomyHost} saw that Vault is present,
 * so a server without Vault never loads it. Vault gives no thread-safety guarantee: every method here must be called
 * on the main server thread. It reads balances only; it never deposits, withdraws or replaces the provider.
 */
final class VaultBridge implements VaultAccess {
    private final Server server;
    private final Economy economy;

    private VaultBridge(Server server, Economy economy) {
        this.server = server;
        this.economy = economy;
    }

    /** The registered economy, or null when there is none or it is not enabled. */
    static VaultAccess lookup(Server server) {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager()
                .getRegistration(Economy.class);
        if (registration == null) {
            return null;
        }
        Economy provider = registration.getProvider();
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        return new VaultBridge(server, provider);
    }

    @Override
    public EconomyHost.Info info() {
        return new EconomyHost.Info(economy.getName(), economy.currencyNameSingular(),
                economy.currencyNamePlural(), economy.fractionalDigits(), economy.hasBankSupport());
    }

    @Override
    public OptionalDouble balance(UUID id, boolean checkAccount) {
        OfflinePlayer player = server.getOfflinePlayer(id);
        if (checkAccount && !economy.hasAccount(player)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(economy.getBalance(player));
    }
}
