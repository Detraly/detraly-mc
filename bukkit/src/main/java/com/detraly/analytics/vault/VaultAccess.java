package com.detraly.analytics.vault;

import com.detraly.analytics.economy.EconomyHost;
import java.util.UUID;
import java.util.OptionalDouble;

/**
 * What the plugin needs from a Vault economy. It mentions no Vault type, so the classes that use it load without
 * Vault. Only {@link VaultBridge} touches Vault, and it is loaded only when Vault is present.
 */
interface VaultAccess {
    EconomyHost.Info info();

    OptionalDouble balance(UUID id, boolean checkAccount);
}
