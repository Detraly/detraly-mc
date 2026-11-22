package com.detraly.analytics.pulse;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.json.JSONObject;

public final class TickProfiler implements Profiler {
    private static final int MAX_DEPTH = 128;
    private static final int CACHE_SIZE = 20_000;
    private static final long MIN_WINDOW_MILLIS = 10_000L;
    private static final long REFRESH_TICKS = 1200L;
    private static final int MAX_NAME = 128;

    private final JavaPlugin plugin;
    private volatile Thread mainThread;

    private volatile List<PluginClassIndex.Plugin> plugins = List.of();
    private BukkitTask refreshTask;

    private final Object lock = new Object();
    private Thread sampler;

    private Thread stopping;
    private volatile boolean sampling;

    private final Map<ProfileAttribution.Source, long[]> counts = new HashMap<>();
    private long samples;
    private long busy;
    private long spikes;

    private static final long NO_TICK = Long.MIN_VALUE;
    private static final long SLOW_TICK_NANOS = 50_000_000L;
    private volatile long tickStart = NO_TICK;

    private volatile boolean spikeCapable;
    private final Listener tickListener = new Listener() {
    };
    private long windowStartNanos;

    private List<PluginClassIndex.Plugin> indexedFor;
    private PluginClassIndex index;
    private final Map<String, Set<String>> jarCache = new HashMap<>();
    private final Map<String, String> cache = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public TickProfiler(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void attach() {
        mainThread = Thread.currentThread();
        registerTickEvents();
        refreshPlugins();

        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                com.detraly.analytics.safety.Guard.wrap(plugin.getLogger(), "the plugin list refresh",
                        this::refreshPlugins), 100L, REFRESH_TICKS);
    }

    public void detach() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        stopSampling();
        Thread lingering;
        synchronized (lock) {
            lingering = stopping;
            stopping = null;
        }
        if (lingering != null && lingering.isAlive()) {
            join(lingering);
        }
        try {
            HandlerList.unregisterAll(tickListener);
        } catch (RuntimeException | LinkageError ignored) {

        }
        spikeCapable = false;
        mainThread = null;
    }

    private void registerTickEvents() {
        try {
            Class<? extends Event> start = Class.forName("com.destroystokyo.paper.event.server.ServerTickStartEvent")
                    .asSubclass(Event.class);
            Class<? extends Event> end = Class.forName("com.destroystokyo.paper.event.server.ServerTickEndEvent")
                    .asSubclass(Event.class);
            plugin.getServer().getPluginManager().registerEvent(start, tickListener, EventPriority.MONITOR,
                    (listener, event) -> tickStart = System.nanoTime(), plugin, true);
            plugin.getServer().getPluginManager().registerEvent(end, tickListener, EventPriority.MONITOR,
                    (listener, event) -> tickStart = NO_TICK, plugin, true);
            spikeCapable = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            spikeCapable = false;
        }
    }

    private void refreshPlugins() {
        List<PluginClassIndex.Plugin> now = new ArrayList<>();
        for (Plugin p : plugin.getServer().getPluginManager().getPlugins()) {
            String name = label(p.getName());
            File jar = p.isEnabled() ? jarOf(p) : null;
            if (name != null && jar != null) {
                now.add(new PluginClassIndex.Plugin(name, jar));
            }
        }
        now.sort(Comparator.comparing(PluginClassIndex.Plugin::name));

        if (!now.equals(plugins)) {
            plugins = List.copyOf(now);
        }
    }

    private static File jarOf(Plugin p) {
        try {
            CodeSource source = p.getClass().getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            File file = Path.of(source.getLocation().toURI()).toFile();
            return file.isFile() ? file : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    public void startSampling() {
        synchronized (lock) {
            Thread target = mainThread;
            if (sampler != null || target == null) {
                return;
            }
            counts.clear();
            samples = 0L;
            busy = 0L;
            spikes = 0L;
            windowStartNanos = System.nanoTime();
            sampling = true;
            Thread t = new Thread(() -> run(target.threadId()), "Detraly-Profiler");
            t.setDaemon(true);
            sampler = t;
            t.start();
        }
    }

    public void stopSampling() {
        Thread t;
        synchronized (lock) {
            sampling = false;
            t = sampler;
            sampler = null;
            if (t != null) {
                stopping = t;
            }
        }
        if (t != null) {
            LockSupport.unpark(t);
            t.interrupt();
            join(t);
        }
    }

    private static void join(Thread t) {
        boolean interrupted = Thread.interrupted();
        try {
            t.join(2000L);
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isSampling() {
        return sampling;
    }

    public JSONObject drainMessage() {
        Map<ProfileAttribution.Source, ProfileMessage.Count> taken = new HashMap<>();
        long n;
        long b;
        long sp;
        long seconds;
        synchronized (lock) {
            if (!sampling) {
                return null;
            }
            long now = System.nanoTime();
            long millis = (now - windowStartNanos) / 1_000_000L;
            if (millis < MIN_WINDOW_MILLIS || samples == 0L) {
                return null;
            }
            seconds = Math.round(millis / 1000.0);
            for (Map.Entry<ProfileAttribution.Source, long[]> e : counts.entrySet()) {
                taken.put(e.getKey(), new ProfileMessage.Count(e.getValue()[0], e.getValue()[1]));
            }
            n = samples;
            b = busy;
            sp = spikes;
            counts.clear();
            samples = 0L;
            busy = 0L;
            spikes = 0L;
            windowStartNanos = now;
        }

        return ProfileMessage.build(seconds, n, b, spikeCapable ? sp : null, taken);
    }

    private void run(long threadId) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        while (sampling && !Thread.currentThread().isInterrupted()) {

            LockSupport.parkNanos(ProfileSchedule.nextDelayNanos(ThreadLocalRandom.current()));
            if (!sampling) {
                break;
            }
            ProfileAttribution.Source source;
            boolean slow;
            try {

                long started = tickStart;
                slow = spikeCapable && started != NO_TICK && System.nanoTime() - started > SLOW_TICK_NANOS;
                ThreadInfo info = threads.getThreadInfo(threadId, MAX_DEPTH);
                if (info == null) {
                    continue;
                }
                source = attribute(info.getStackTrace());
            } catch (Throwable e) {
                continue;
            }
            synchronized (lock) {
                if (!sampling) {
                    break;
                }
                samples++;
                if (source != ProfileAttribution.IDLE) {
                    busy++;
                    long[] c = counts.computeIfAbsent(source, k -> new long[2]);
                    c[0]++;
                    if (slow) {
                        c[1]++;
                        spikes++;
                    }
                }
            }
        }
    }

    ProfileAttribution.Source attribute(StackTraceElement[] frames) {
        List<PluginClassIndex.Plugin> now = plugins;
        if (now != indexedFor) {

            index = PluginClassIndex.build(now, jarCache);
            cache.clear();
            indexedFor = now;
        }
        PluginClassIndex current = index;
        return ProfileAttribution.classify(frames, cls -> pluginOf(current, cls));
    }

    private String pluginOf(PluginClassIndex current, String className) {
        String cached = cache.get(className);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String found = current.pluginOf(className);
        cache.put(className, found == null ? "" : found);
        return found;
    }

    static String label(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length() && sb.length() < MAX_NAME; i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '<' && c != '>') {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }
}
