package com.detraly.analytics.pulse;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.DoubleConsumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperStatsHost implements StatsHost {
    private static final String TICK_END_EVENT = "com.destroystokyo.paper.event.server.ServerTickEndEvent";

    private final JavaPlugin plugin;
    private final Method averageTickTime = findAverageTickTime();
    private final Method worldEntityCount = findWorldMethod("getEntityCount");
    private final Method worldChunkCount = findWorldMethod("getChunkCount");

    public PaperStatsHost(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String minecraftVersion() {
        // Paper has Server#getMinecraftVersion (spigot-api 1.21 does not, so it is reached by reflection). On Paper 26.x
        // getBukkitVersion() is "26.3.build.257-stable", not "1.21-R0.1-SNAPSHOT", so it cannot be cut at the first dash.
        try {
            Object value = Bukkit.getServer().getClass().getMethod("getMinecraftVersion").invoke(Bukkit.getServer());
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Spigot / old server: fall back to the Bukkit version below.
        }
        String bukkit = String.valueOf(Bukkit.getBukkitVersion());
        int build = bukkit.indexOf(".build.");
        if (build > 0) {
            return bukkit.substring(0, build);
        }
        int dash = bukkit.indexOf('-');
        return dash > 0 ? bukkit.substring(0, dash) : bukkit;
    }

    @Override
    public String softwareName() {
        return Bukkit.getName();
    }

    @Override
    public int playersOnline() {
        return Bukkit.getOnlinePlayers().size();
    }

    @Override
    public int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    @Override
    public double averageTickMillis() {
        if (averageTickTime == null) {
            return Double.NaN;
        }
        try {
            Object value = averageTickTime.invoke(Bukkit.getServer());
            if (value instanceof Number n) {
                return n.doubleValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {

        }
        return Double.NaN;
    }

    @Override
    public int averagePingMillis() {
        long total = 0L;
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            total += Math.max(0, player.getPing());
            count++;
        }
        return count == 0 ? -1 : (int) Math.min(100_000L, total / count);
    }

    @Override
    public boolean cheapWorldCounts() {
        return worldEntityCount != null && worldChunkCount != null;
    }

    @Override
    public WorldCounts worldCounts() {
        boolean cheap = cheapWorldCounts();
        long e = 0L;
        long c = 0L;
        List<World> all = Bukkit.getWorlds();
        for (World world : all) {
            if (cheap) {
                e += invokeInt(worldEntityCount, world);
                c += invokeInt(worldChunkCount, world);
            } else {
                e += world.getEntities().size();
                c += world.getLoadedChunks().length;
            }
        }
        return new WorldCounts(all.size(), e, c);
    }

    @Override
    public Runnable listenTickEnd(DoubleConsumer tickMillis) {
        try {
            Class<? extends Event> type = Class.forName(TICK_END_EVENT).asSubclass(Event.class);
            Method duration = type.getMethod("getTickDuration");
            Listener listener = new Listener() {
            };
            Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR,
                    (l, event) -> {
                        if (type.isInstance(event)) {
                            try {
                                Object value = duration.invoke(event);
                                if (value instanceof Number number) {
                                    tickMillis.accept(number.doubleValue());
                                }
                            } catch (ReflectiveOperationException | RuntimeException ignored) {

                            }
                        }
                    }, plugin, true);
            return () -> HandlerList.unregisterAll(listener);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static int invokeInt(Method method, World world) {
        try {
            Object value = method.invoke(world);
            return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static Method findWorldMethod(String name) {
        try {
            return World.class.getMethod(name);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method findAverageTickTime() {
        try {
            return Bukkit.getServer().getClass().getMethod("getAverageTickTime");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
