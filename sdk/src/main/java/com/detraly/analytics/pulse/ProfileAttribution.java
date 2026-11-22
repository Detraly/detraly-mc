package com.detraly.analytics.pulse;

import java.util.function.Function;

public final class ProfileAttribution {

    public record Source(String kind, String name) {
    }

    public static final Source IDLE = new Source("idle", "idle");

    private static final String[] NOT_PLUGINS = {
        "java.", "javax.", "jdk.", "sun.", "net.minecraft.", "org.bukkit.", "org.spigotmc.",
        "io.papermc.paper.", "com.destroystokyo.", "ca.spottedleaf.", "com.mojang.", "it.unimi.",
        "io.netty.", "org.apache.logging.", "com.google."
    };

    private ProfileAttribution() {
    }

    public static Source classify(StackTraceElement[] frames, Function<String, String> pluginOf) {
        if (frames == null || frames.length == 0) {
            return IDLE;
        }

        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (mayBePlugin(cls)) {
                String plugin = pluginOf.apply(cls);
                if (plugin != null) {
                    return new Source("plugin", plugin);
                }
            }
        }
        if (waiting(frames)) {
            return IDLE;
        }

        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("net.minecraft.commands.functions") || cls.contains("ServerFunctionManager")
                    || cls.contains("CommandFunction")) {
                return new Source("datapack", "functions");
            }
        }

        for (StackTraceElement frame : frames) {
            String area = area(frame.getClassName());
            if (area != null) {
                return new Source("server", area);
            }
        }

        return new Source("server", "other");
    }

    static boolean mayBePlugin(String className) {
        for (String prefix : NOT_PLUGINS) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    static String area(String cls) {
        if (cls.startsWith("net.minecraft.world.level.block.entity.")) {
            return "block_entities";
        }
        if (cls.startsWith("net.minecraft.world.entity.")) {
            return "entities";
        }
        if (cls.contains("ChunkMap") || cls.contains("ServerChunkCache")
                || cls.startsWith("net.minecraft.world.level.chunk.")) {
            return "chunks";
        }
        if (cls.startsWith("net.minecraft.network.") || cls.contains("ServerGamePacketListenerImpl")) {
            return "network";
        }
        if (cls.startsWith("net.minecraft.world.level.") || cls.contains("ServerLevel")) {
            return "world";
        }
        return null;
    }

    static boolean waiting(StackTraceElement[] frames) {
        if (isWaitCall(frames[0])) {
            return true;
        }
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
                continue;
            }

            String m = frame.getMethodName();
            return m.equals("waitUntilNextTick") || m.equals("managedBlock") || m.equals("waitForTasks");
        }
        return false;
    }

    private static boolean isWaitCall(StackTraceElement frame) {
        String cls = frame.getClassName();
        String m = frame.getMethodName();
        switch (cls) {
            case "java.lang.Thread":
                return m.equals("sleep") || m.equals("sleep0") || m.equals("sleepNanos");
            case "java.util.concurrent.locks.LockSupport":
            case "jdk.internal.misc.Unsafe":
            case "sun.misc.Unsafe":
                return m.startsWith("park");
            case "java.lang.Object":
                return m.equals("wait") || m.equals("wait0");
            default:
                return false;
        }
    }
}
