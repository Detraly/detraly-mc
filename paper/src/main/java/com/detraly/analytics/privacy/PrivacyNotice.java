package com.detraly.analytics.privacy;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

public final class PrivacyNotice {
    private PrivacyNotice() {
    }

    public static void send(Player player) {
        TextComponent header = new TextComponent("[Detraly] Privacy notice");
        header.setColor(ChatColor.AQUA);

        TextComponent body = new TextComponent(
                "This server uses Detraly analytics: it counts when you join and leave, and uses your IP address to work out your country. "
                        + "The address itself is not stored. Opting out stops all of this on every server using Detraly.");
        body.setColor(ChatColor.GRAY);

        TextComponent optOut = new TextComponent("[Click here to opt out of Detraly everywhere]");
        optOut.setColor(ChatColor.RED);
        optOut.setUnderlined(true);
        optOut.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/detraly optout"));
        optOut.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text("Stop Detraly tracking you on every server")));

        TextComponent note = new TextComponent(
                "To resume, run /detraly optin. It removes your Detraly opt-out, so tracking resumes on every server except servers where you ran /detraly optout yourself; run it on each of those too.");
        note.setColor(ChatColor.GRAY);

        player.spigot().sendMessage(header);
        player.spigot().sendMessage(body);
        player.spigot().sendMessage(optOut);
        player.spigot().sendMessage(note);
    }
}
