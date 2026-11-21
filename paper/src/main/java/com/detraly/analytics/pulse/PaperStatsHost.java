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

    private final boolean folia;

    public PaperStatsHost(JavaPlugin plugin) {
        this(plugin, false);
    }

    /** On Folia, world data belongs to regions: counts use only the cheap calls, and a world that refuses counts 0. */
    public PaperStatsHost(JavaPlugin plugin, boolean folia) {
        this.plugin = plugin;
        this.folia = folia;
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String minecraftVersion() {
        String bukkit = String.valueOf(Bukkit.getBukkitVersion());
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
        return folia || (worldEntityCount != null && worldChunkCount != null);
    }

    @Override
    public WorldCounts worldCounts() {
        boolean cheap = worldEntityCount != null && worldChunkCount != null;
        long e = 0L;
        long c = 0L;
        boolean unknown = folia && !cheap;
        List<World> all = Bukkit.getWorlds();
        for (World world : all) {
            if (cheap) {
                int entities = invokeInt(worldEntityCount, world);
                int chunks = invokeInt(worldChunkCount, world);
                if (folia && (entities < 0 || chunks < 0)) {
                    // (On Paper a failing call still counts as 0, exactly as before.)
                    // Folia refused this world off its region thread: report unknown, not a false zero.
                    unknown = true;
                }
                e += Math.max(0, entities);
                c += Math.max(0, chunks);
            } else if (!folia) {
                e += world.getEntities().size();
                c += world.getLoadedChunks().length;
            }
        }
        return unknown ? new WorldCounts(all.size(), -1L, -1L) : new WorldCounts(all.size(), e, c);
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
            return -1;
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
