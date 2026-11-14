package com.detraly.analytics.fabric;

import com.detraly.analytics.DetralyCore;
import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.safety.Guard;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

public final class DetralyFabric implements ModInitializer {
    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_CONFIG = "api-url=https://api.detraly.com\n"
            + "behind-proxy=false\n"
            + "live-connection=true\n"
            + "profiler=false\n";

    private final Logger logger = Log4jBridge.create("Detraly");
    private final FabricScheduler scheduler = new FabricScheduler(logger);
    private volatile DetralyCore core;

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> scheduler.onTickStart());
        ServerTickEvents.END_SERVER_TICK.register(server -> scheduler.onTickEnd());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FabricCommand.register(dispatcher, () -> core));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            DetralyCore c = core;
            if (c != null) {
                c.players().onJoin(new FabricPlayer(server, handler.getPlayer()));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DetralyCore c = core;
            if (c != null) {
                c.players().onQuit(new FabricPlayer(server, handler.getPlayer()));
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(this::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> scheduler.shutdown());
    }

    private void started(MinecraftServer server) {
        try {
            File dataFolder = FabricLoader.getInstance().getConfigDir().resolve("detraly").toFile();
            ConfigSource config = loadConfig(dataFolder.toPath());
            String version = FabricLoader.getInstance().getModContainer("detraly")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
            FabricPlatform platform = new FabricPlatform(server, scheduler, logger, dataFolder, config, version);
            DetralyCore created = new DetralyCore(platform);
            core = created;
            created.start();
        } catch (Throwable e) {
            Guard.report(logger, "start-up", e);
        }
    }

    private void stopping() {
        DetralyCore c = core;
        core = null;
        if (c != null) {
            Guard.run(logger, "shut-down", c::stop);
        }
    }

    private ConfigSource loadConfig(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve(CONFIG_FILE);
        if (!Files.exists(file)) {
            try (OutputStream out = Files.newOutputStream(file)) {
                out.write(DEFAULT_CONFIG.getBytes(StandardCharsets.UTF_8));
            }
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return ConfigSource.of(properties);
    }
}
