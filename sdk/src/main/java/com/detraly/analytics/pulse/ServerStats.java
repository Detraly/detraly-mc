package com.detraly.analytics.pulse;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.safety.Guard;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.json.JSONObject;

public final class ServerStats implements PulseClient.Source {
    private static final long MB = 1024L * 1024L;
    private static final int MAX_TEXT = 128;

    private static final long MAX_MB = 1L << 30;

    private final Logger logger;
    private final Scheduler scheduler;
    private final StatsHost host;
    private final AtomicLong ticks = new AtomicLong();
    private volatile int playersOnline;
    private volatile int maxPlayers;
    private Scheduler.Task task;
    private Runnable tickUnregister;

    private final JSONObject specs;

    private long lastTicks;
    private long lastNanos;

    private final TickHistogram ticksMillis = new TickHistogram();
    private volatile boolean statsV2;

    private volatile boolean tickEvents;
    private volatile int entities = -1;
    private volatile int chunks = -1;
    private volatile int worlds = -1;
    private volatile int pingAvg = -1;
    private long lastGcCount;
    private long lastGcTimeMs;

    public ServerStats(Logger logger, Scheduler scheduler, StatsHost host) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.host = host;
        this.specs = readSpecs(host);
        this.playersOnline = host.playersOnline();
        this.maxPlayers = Math.max(0, host.maxPlayers());
    }

    @Override
    public void begin() {
        lastTicks = ticks.get();
        lastNanos = System.nanoTime();
    }

    @Override
    public void begin(boolean withStatsV2) {
        statsV2 = withStatsV2;
        begin();
        if (withStatsV2) {
            ticksMillis.drain();
            long[] gc = gcTotals();
            lastGcCount = gc[0];
            lastGcTimeMs = gc[1];
        }
    }

    public void start() {
        if (task != null) {
            return;
        }
        lastTicks = 0L;
        lastNanos = System.nanoTime();
        task = scheduler.timer(
                Guard.wrap(logger, "the stats task", () -> {
                    long n = ticks.incrementAndGet();
                    if (n % 20L == 0L) {
                        playersOnline = host.playersOnline();
                        maxPlayers = Math.max(0, host.maxPlayers());
                    }
                    if (statsV2) {
                        sampleExtras(n);
                    }
                }), 1L, 1L);
        registerTickEvent();
    }

    private void sampleExtras(long n) {
        if (n % 20L != 0L) {
            return;
        }
        int ping = host.averagePingMillis();
        pingAvg = ping < 0 ? -1 : Math.min(100_000, ping);

        boolean cheap = host.cheapWorldCounts();
        if (entities < 0 || n % (cheap ? 200L : 600L) == 0L) {
            StatsHost.WorldCounts counts = host.worldCounts();
            worlds = Math.min(1000, Math.max(0, counts.worlds()));
            entities = (int) Math.min(10_000_000L, Math.max(0L, counts.entities()));
            chunks = (int) Math.min(10_000_000L, Math.max(0L, counts.chunks()));
        }
    }

    private void registerTickEvent() {
        try {
            tickUnregister = host.listenTickEnd(millis -> {
                if (statsV2) {
                    ticksMillis.record(millis);
                }
            });
            tickEvents = tickUnregister != null;
        } catch (RuntimeException | LinkageError e) {
            tickEvents = false;
        }
    }

    private static long[] gcTotals() {
        long count = 0L;
        long time = 0L;
        try {
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() >= 0L) {
                    count += bean.getCollectionCount();
                }
                if (bean.getCollectionTime() >= 0L) {
                    time += bean.getCollectionTime();
                }
            }
        } catch (RuntimeException e) {

        }
        return new long[] {count, time};
    }

    private void putExtras(JSONObject json) {
        if (tickEvents) {
            TickHistogram.Snapshot snap = ticksMillis.drain();
            if (snap.count() > 0L) {
                json.put("mspt_max", round2(clamp(snap.max(), 0.0, 60000.0)));
                json.put("mspt_p95", round2(clamp(snap.p95(), 0.0, 60000.0)));
                json.put("lag_ticks", Math.min(1_000_000L, snap.lagTicks()));
            }
        }
        if (entities >= 0) {
            json.put("entities", entities);
            json.put("chunks", chunks);
            json.put("worlds", worlds);
        }
        long[] gc = gcTotals();
        json.put("gc_count", Math.max(0L, Math.min(1_000_000L, gc[0] - lastGcCount)));
        json.put("gc_time_ms", Math.max(0L, Math.min(600_000L, gc[1] - lastGcTimeMs)));
        lastGcCount = gc[0];
        lastGcTimeMs = gc[1];
        try {
            json.put("threads", Math.max(0, Math.min(100_000, ManagementFactory.getThreadMXBean().getThreadCount())));
        } catch (RuntimeException e) {

        }
        int ping = pingAvg;
        if (ping >= 0 && playersOnline > 0) {
            json.put("ping_avg_ms", Math.min(100_000, ping));
        }
    }

    public void stop() {
        Runnable unregister = tickUnregister;
        tickUnregister = null;
        if (unregister != null) {
            try {
                unregister.run();
            } catch (RuntimeException | LinkageError ignored) {

            }
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @Override
    public JSONObject specsMessage() {
        return specs;
    }

    @Override
    public JSONObject statsMessage() {
        long nowTicks = ticks.get();
        if (nowTicks == 0L) {

            return null;
        }
        long nowNanos = System.nanoTime();
        if (tooSoon(nowNanos, lastNanos)) {

            return null;
        }
        double seconds = (nowNanos - lastNanos) / 1_000_000_000.0;
        double tps = (nowTicks - lastTicks) / seconds;
        lastTicks = nowTicks;
        lastNanos = nowNanos;

        Runtime runtime = Runtime.getRuntime();
        JSONObject json = new JSONObject();
        json.put("type", "stats");
        json.put("tps", round2(clamp(tps, 0.0, 1000.0)));
        Double mspt = mspt();
        if (mspt != null) {
            json.put("mspt", round2(clamp(mspt, 0.0, 60000.0)));
        }
        json.put("players_online", Math.min(playersOnline, 1_000_000));
        json.put("max_players", maxPlayers);
        json.put("memory_used_mb", Math.min(MAX_MB, (runtime.totalMemory() - runtime.freeMemory()) / MB));
        json.put("memory_max_mb", Math.min(MAX_MB, runtime.maxMemory() == Long.MAX_VALUE ? runtime.totalMemory() / MB
                : runtime.maxMemory() / MB));
        json.put("uptime_seconds", Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime() / 1000L));

        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                putPercent(json, "cpu_process", sun.getProcessCpuLoad());
                putPercent(json, "cpu_system", sun.getCpuLoad());
            }
        } catch (LinkageError | RuntimeException e) {

        }
        try {
            File root = new File(".").getAbsoluteFile();
            long total = root.getTotalSpace();
            if (total > 0L) {
                putMb(json, "disk_used_mb", Math.max(0L, total - root.getUsableSpace()) / MB);
            }
        } catch (SecurityException ignored) {

        }
        if (statsV2) {
            putExtras(json);
        }
        return json;
    }

    static boolean tooSoon(long nowNanos, long lastNanos) {
        return nowNanos - lastNanos < 1_000_000_000L;
    }

    private static void putMb(JSONObject json, String key, long megabytes) {
        if (megabytes >= 0L && megabytes <= MAX_MB) {
            json.put(key, megabytes);
        }
    }

    private static void putPercent(JSONObject json, String key, double load) {

        if (load >= 0.0 && load <= 1.0) {
            json.put(key, round2(load * 100.0));
        }
    }

    private Double mspt() {
        try {
            double value = host.averageTickMillis();
            if (!Double.isNaN(value)) {
                return value;
            }
        } catch (RuntimeException ignored) {

        }
        return null;
    }

    private static JSONObject readSpecs(StatsHost host) {
        JSONObject json = new JSONObject();
        json.put("type", "specs");
        json.put("plugin_version", text(host.pluginVersion()));
        json.put("minecraft_version", text(host.minecraftVersion()));
        json.put("server_software", text(host.softwareName()));
        json.put("java_version", text(System.getProperty("java.version")));
        json.put("os_name", text(System.getProperty("os.name")));
        json.put("os_arch", text(System.getProperty("os.arch")));
        json.put("cpu_cores", Math.max(1, Math.min(4096, Runtime.getRuntime().availableProcessors())));
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun && sun.getTotalMemorySize() > 0L) {
                putMb(json, "memory_total_mb", sun.getTotalMemorySize() / MB);
            }
        } catch (LinkageError | RuntimeException e) {

        }
        try {
            long total = new File(".").getAbsoluteFile().getTotalSpace();
            if (total > 0L) {
                putMb(json, "disk_total_mb", total / MB);
            }
        } catch (SecurityException ignored) {

        }
        return json;
    }

    private static String text(String value) {
        StringBuilder sb = new StringBuilder();
        if (value != null) {
            for (int i = 0; i < value.length() && sb.length() < MAX_TEXT; i++) {
                char c = value.charAt(i);
                if (c >= 0x20 && c < 0x7f && c != '<' && c != '>') {
                    sb.append(c);
                }
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "unknown" : out;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
