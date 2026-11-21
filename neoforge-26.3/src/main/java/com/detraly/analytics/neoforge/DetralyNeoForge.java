package com.detraly.analytics.neoforge;

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
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.server.level.ServerPlayer;

@Mod(value = "detraly", dist = Dist.DEDICATED_SERVER)
public final class DetralyNeoForge {
    private static final String CONFIG_FILE = "config.properties";
    private static final String DEFAULT_CONFIG = "api-url=https://api.detraly.com\n"
            + "behind-proxy=false\n"
            + "live-connection=true\n"
            + "profiler=false\n";

    private final Logger logger = Log4jBridge.create("Detraly");
    private final NeoForgeScheduler scheduler = new NeoForgeScheduler(logger);
    private volatile DetralyCore core;

    public DetralyNeoForge() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onTickStart(ServerTickEvent.Pre event) {
        scheduler.onTickStart();
    }

    @SubscribeEvent
    public void onTickEnd(ServerTickEvent.Post event) {
        scheduler.onTickEnd();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NeoForgeCommand.register(event.getDispatcher(), () -> core);
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        DetralyCore c = core;
        if (c != null && event.getEntity() instanceof ServerPlayer player) {
            c.players().onJoin(new NeoForgePlayer(player.level().getServer(), player));
        }
    }

    @SubscribeEvent
    public void onQuit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.level().getServer().isStopped()) {
                // A crash skips ServerStoppingEvent; Fabric stops at the top of its shutdown, so do the same here.
                stopping();
            }
            DetralyCore c = core;
            if (c != null) {
                c.players().onQuit(new NeoForgePlayer(player.level().getServer(), player));
            }
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        started(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        stopping();
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        stopping();
        scheduler.shutdown();
    }

    private void started(MinecraftServer server) {
        try {
            File dataFolder = FMLPaths.CONFIGDIR.get().resolve("detraly").toFile();
            ConfigSource config = loadConfig(dataFolder.toPath());
            String version = ModList.get().getModContainerById("detraly")
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
            NeoForgePlatform platform = new NeoForgePlatform(server, scheduler, logger, dataFolder, config, version);
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
