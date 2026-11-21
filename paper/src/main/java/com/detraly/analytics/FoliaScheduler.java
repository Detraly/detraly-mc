package com.detraly.analytics;

import com.detraly.analytics.platform.Scheduler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The scheduler of a Folia server. Folia has no main thread, and the Bukkit scheduler throws there, so everything
 * goes through the global region scheduler (ticks) and the async scheduler. They are reached by reflection: the
 * plugin is built against the Spigot API, which has no Folia types. Folia delays must be at least 1 tick.
 */
final class FoliaScheduler implements Scheduler {
    private static final String REGIONIZED_SERVER = "io.papermc.paper.threadedregions.RegionizedServer";
    private static final String PKG = "io.papermc.paper.threadedregions.scheduler.";

    private final JavaPlugin plugin;
    private final Object global;
    private final Object async;
    private final Method run;
    private final Method runDelayed;
    private final Method runAtFixedRate;
    private final Method runNow;
    private final Method cancel;

    private FoliaScheduler(JavaPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Object server = plugin.getServer();
        this.global = server.getClass().getMethod("getGlobalRegionScheduler").invoke(server);
        this.async = server.getClass().getMethod("getAsyncScheduler").invoke(server);
        Class<?> g = Class.forName(PKG + "GlobalRegionScheduler");
        Class<?> a = Class.forName(PKG + "AsyncScheduler");
        Class<?> task = Class.forName(PKG + "ScheduledTask");
        this.run = g.getMethod("run", Plugin.class, Consumer.class);
        this.runDelayed = g.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
        this.runAtFixedRate = g.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
        this.runNow = a.getMethod("runNow", Plugin.class, Consumer.class);
        this.cancel = task.getMethod("cancel");
    }

    /** True when this server is Folia (or a fork of it). */
    static boolean isFolia() {
        try {
            Class.forName(REGIONIZED_SERVER);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** A Folia scheduler, or null if the Folia API is not what it is expected to be. */
    static FoliaScheduler create(JavaPlugin plugin) {
        try {
            return new FoliaScheduler(plugin);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.getLogger().warning("Folia was detected, but its scheduler could not be reached: " + e);
            return null;
        }
    }

    @Override
    public Task timer(Runnable task, long delayTicks, long periodTicks) {
        Object handle = call(runAtFixedRate, global, plugin, wrap(task), Math.max(1L, delayTicks),
                Math.max(1L, periodTicks));
        return () -> call(cancel, handle);
    }

    @Override
    public void later(Runnable task, long delayTicks) {
        if (delayTicks <= 0L) {
            call(run, global, plugin, wrap(task));
        } else {
            call(runDelayed, global, plugin, wrap(task), delayTicks);
        }
    }

    @Override
    public void async(Runnable task) {
        call(runNow, async, plugin, wrap(task));
    }

    @Override
    public void sync(Runnable task) {
        call(run, global, plugin, wrap(task));
    }

    private static Consumer<Object> wrap(Runnable task) {
        return scheduled -> task.run();
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
