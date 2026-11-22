package com.detraly.analytics.fabric;

import com.detraly.analytics.platform.PlayerRef;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;

final class FabricPlayer implements PlayerRef {
    private final MinecraftServer server;
    private final ServerPlayer player;

    FabricPlayer(MinecraftServer server, ServerPlayer player) {
        this.server = server;
        this.player = player;
    }

    @Override
    public String name() {
        return player.getGameProfile().name();
    }

    @Override
    public UUID uuid() {
        return player.getUUID();
    }

    @Override
    public InetSocketAddress address() {
        SocketAddress address = player.connection.getRemoteAddress();
        return address instanceof InetSocketAddress inet ? inet : null;
    }

    @Override
    public boolean isOnline() {
        return server.getPlayerList().getPlayer(player.getUUID()) != null;
    }

    @Override
    public void sendPrivacyNotice() {
        player.sendSystemMessage(Component.literal("[Detraly] Privacy notice").withStyle(ChatFormatting.AQUA));
        player.sendSystemMessage(Component.literal(
                "This server uses Detraly analytics: it counts when you join and leave, and uses your IP address to work out your country. "
                        + "The address itself is not stored. Opting out stops all of this on every server using Detraly.").withStyle(ChatFormatting.GRAY));
        MutableComponent optOut = Component.literal("[Click here to opt out of Detraly everywhere]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand("/detraly optout"))
                .withHoverEvent(new HoverEvent.ShowText(
                        Component.literal("Stop Detraly tracking you on every server"))));
        player.sendSystemMessage(optOut);
        player.sendSystemMessage(Component.literal("To resume, run /detraly optin. It removes your Detraly opt-out, so tracking resumes on every server except servers where you ran /detraly optout yourself; run it on each of those too.")
                .withStyle(ChatFormatting.GRAY));
    }
}
