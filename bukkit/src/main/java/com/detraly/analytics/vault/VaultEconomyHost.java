package com.detraly.analytics.vault;

import com.detraly.analytics.economy.EconomyHost;
import com.detraly.analytics.safety.Guard;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The economy of a Paper or Spigot server, read through Vault. The provider is looked up lazily: Detraly loads at
 * server start, before Vault and the economy plugin, so {@link #refresh()} runs when the server finished loading
 * and whenever an economy is registered or unregistered. This class mentions no Vault type; a server without Vault
 * simply never finds a provider.
 */
public final class VaultEconomyHost implements EconomyHost {
    private static final String VAULT = "Vault";

    private final JavaPlugin plugin;
    private static final String DAT = ".dat";
    private static final int UUID_TEXT_LENGTH = 36;

    private final Method offlinePlayerIfCached = findIfCached();

    /** The main world's playerdata folder. Set on the main thread, read by the async listing. */
    private volatile File playerData;

    // Main thread only.
    private VaultAccess access;
    private Info info;

    public VaultEconomyHost(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Looks the provider up again. Call on the main thread. */
    public void refresh() {
        VaultAccess found = null;
        Info foundInfo = null;
        try {
            Server server = plugin.getServer();
            List<World> worlds = server.getWorlds();
            if (!worlds.isEmpty()) {
                playerData = new File(worlds.get(0).getWorldFolder(), "playerdata");
            }
            if (server.getPluginManager().isPluginEnabled(VAULT)) {
                found = VaultBridge.lookup(server);
                foundInfo = found == null ? null : found.info();
            }
        } catch (Throwable e) {
            // Includes a missing Vault class and a provider that throws: no economy, and the server goes on.
            found = null;
            foundInfo = null;
            Guard.report(plugin.getLogger(), "the economy lookup", e);
        }
        access = found;
        info = foundInfo;
    }

    @Override
    public Info info() {
        return info;
    }

    @Override
    public OptionalDouble balance(UUID id, boolean checkAccount) {
        VaultAccess a = access;
        return a == null ? OptionalDouble.empty() : a.balance(id, checkAccount);
    }

    /**
     * The ids in the main world's {@code playerdata} folder. It reads the folder only (no player files, no Bukkit
     * calls), so it is safe off the main thread. {@code Bukkit.getOfflinePlayers()} is avoided on purpose: asking
     * a player without a cached profile for a name reads and decompresses their data file.
     */
    @Override
    public List<UUID> knownPlayerIds() {
        File folder = playerData;
        List<UUID> out = new ArrayList<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            String fileName = f.getName();
            if (!fileName.endsWith(DAT) || fileName.length() != UUID_TEXT_LENGTH + DAT.length()) {
                continue;
            }
            try {
                out.add(UUID.fromString(fileName.substring(0, UUID_TEXT_LENGTH)));
            } catch (IllegalArgumentException e) {
                // Not a player file: skip it.
            }
        }
        return out;
    }

    /** One player's name, through {@code getOfflinePlayer(UUID)}, which never waits for the network. */
    @Override
    public Known knownPlayer(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? null : new Known(id, name);
    }

    /**
     * An online player by name (any case), or a player the server has cached by name (Paper). It never calls
     * {@code Bukkit.getOfflinePlayer(String)}, which can wait for a web lookup on the main thread.
     */
    @Override
    public Known resolve(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return new Known(online.getUniqueId(), online.getName());
        }
        Method m = offlinePlayerIfCached;
        if (m == null) {
            return null;
        }
        try {
            Object result = m.invoke(plugin.getServer(), name);
            if (result instanceof OfflinePlayer p && p.getName() != null) {
                return new Known(p.getUniqueId(), p.getName());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static Method findIfCached() {
        try {
            return Server.class.getMethod("getOfflinePlayerIfCached", String.class);
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }
}
