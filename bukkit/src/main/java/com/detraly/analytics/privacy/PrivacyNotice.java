package com.detraly.analytics.privacy;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;

public final class PrivacyNotice {
    private static final String HEADER = "[Detraly] Privacy notice";
    private static final String BODY = "This server uses Detraly analytics: it counts when you join and leave, and uses your IP address to work out your country. "
            + "The address itself is not stored. Opting out stops all of this on every server using Detraly.";
    private static final String OPT_OUT = "[Click here to opt out of Detraly everywhere]";
    private static final String OPT_OUT_PLAIN = "To opt out of Detraly everywhere, run /detraly optout";
    private static final String NOTE = "To resume, run /detraly optin. It removes your Detraly opt-out, so tracking resumes on every server except servers where you ran /detraly optout yourself; run it on each of those too.";

    private PrivacyNotice() {
    }

    public static void send(Player player) {
        try {
            sendRich(player);
        } catch (UnsupportedOperationException | LinkageError e) {
            // A plain Bukkit server without the Spigot chat API: same words, no colour and no click.
            sendPlain(player);
        }
    }

    private static void sendRich(Player player) {
        TextComponent header = new TextComponent(HEADER);
        header.setColor(ChatColor.AQUA);

        TextComponent body = new TextComponent(BODY);
        body.setColor(ChatColor.GRAY);

        TextComponent optOut = new TextComponent(OPT_OUT);
        optOut.setColor(ChatColor.RED);
        optOut.setUnderlined(true);
        optOut.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/detraly optout"));
        optOut.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text("Stop Detraly tracking you on every server")));

        TextComponent note = new TextComponent(NOTE);
        note.setColor(ChatColor.GRAY);

        // The first call is the probe: a server without the Spigot chat API throws here, before anything was shown.
        player.spigot().sendMessage(header);
        player.spigot().sendMessage(body);
        player.spigot().sendMessage(optOut);
        player.spigot().sendMessage(note);
    }

    private static void sendPlain(Player player) {
        player.sendMessage(HEADER);
        player.sendMessage(BODY);
        player.sendMessage(OPT_OUT_PLAIN);
        player.sendMessage(NOTE);
    }
}
