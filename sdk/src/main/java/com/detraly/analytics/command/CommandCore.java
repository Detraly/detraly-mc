package com.detraly.analytics.command;

import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.link.LinkService;
import com.detraly.analytics.privacy.PrivacyService;
import com.detraly.analytics.safety.Guard;
import com.detraly.analytics.storage.TokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class CommandCore {
    private final Logger logger;
    private final LinkService linkService;
    private final TokenStore tokenStore;
    private final GeolocationState geoState;
    private final PrivacyService privacy;
    private volatile Supplier<String> liveStatus = () -> "off";
    private volatile Supplier<String> economyStatus = () -> "off";

    public CommandCore(Logger logger, LinkService linkService, TokenStore tokenStore, GeolocationState geoState,
            PrivacyService privacy) {
        this.logger = logger;
        this.linkService = linkService;
        this.tokenStore = tokenStore;
        this.geoState = geoState;
        this.privacy = privacy;
    }

    public void setLiveStatus(Supplier<String> liveStatus) {
        this.liveStatus = liveStatus;
    }

    public void setEconomyStatus(Supplier<String> economyStatus) {
        this.economyStatus = economyStatus;
    }

    public void execute(CommandActor actor, String[] args) {
        try {
            dispatch(actor, args);
        } catch (Throwable e) {
            Guard.report(logger, "the /detraly command", e);
        }
    }

    private void dispatch(CommandActor actor, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "link":
                handleLink(actor, args);
                break;
            case "status":
                handleStatus(actor);
                break;
            case "optout":
                handleOptOut(actor, true);
                break;
            case "optin":
                handleOptOut(actor, false);
                break;
            default:
                sendHelp(actor);
                break;
        }
    }

    private void handleLink(CommandActor actor, String[] args) {
        if (!actor.isAdmin()) {
            actor.send(Segment.prefixed(Tone.BAD, "You do not have permission to do that."));
            return;
        }
        if (!actor.isConsole()) {
            actor.send(Segment.prefixed(Tone.BAD,
                    "Run this in the server console so the code does not end up in chat logs."));
            return;
        }
        if (args.length < 2) {
            actor.send(Segment.prefixed(Tone.WARN, "Usage: detraly link <code>"));
            return;
        }
        actor.send(Segment.prefixed(Tone.MUTED, "Linking..."));
        linkService.link(args[1], actor);
    }

    private void handleStatus(CommandActor actor) {
        if (!actor.isAdmin()) {
            actor.send(Segment.prefixed(Tone.BAD, "You do not have permission to do that."));
            return;
        }
        boolean linked = tokenStore.hasToken();
        actor.send(Segment.of(Tone.BRAND, "[Detraly] "), Segment.of(Tone.PLAIN, "Linked: "),
                Segment.of(linked ? Tone.GOOD : Tone.BAD, linked ? "yes" : "no"));
        actor.send(Segment.of(Tone.BRAND, "[Detraly] "), Segment.of(Tone.PLAIN, "Geolocation notice: "),
                Segment.of(geoState.isOn() ? Tone.GOOD : Tone.MUTED, geoState.isOn() ? "on" : "off"));
        String live = liveStatus.get();
        actor.send(Segment.of(Tone.BRAND, "[Detraly] "), Segment.of(Tone.PLAIN, "Live connection: "),
                Segment.of("connected".equals(live) ? Tone.GOOD : Tone.MUTED, live));
        String economy = economyStatus.get();
        boolean economyIdle = economy.equals("off") || economy.equals("not found")
                || economy.equals("waiting for the API") || economy.startsWith("not supported");
        actor.send(Segment.of(Tone.BRAND, "[Detraly] "), Segment.of(Tone.PLAIN, "Economy: "),
                Segment.of(economyIdle ? Tone.MUTED : Tone.GOOD, economy));
    }

    private void handleOptOut(CommandActor actor, boolean optOut) {
        if (actor.playerId() == null) {
            actor.send(Segment.prefixed(Tone.BAD, "Only players can use this command."));
            return;
        }
        if (optOut) {
            privacy.optOut(actor);
        } else {
            privacy.optIn(actor);
        }
    }

    private void sendHelp(CommandActor actor) {
        actor.send(Segment.prefixed(Tone.WARN, "Commands:"));
        if (actor.isAdmin()) {
            actor.send(Segment.of(Tone.MUTED, "/detraly link <code>"), Segment.of(Tone.PLAIN,
                    " - link this server (console only). A new code from your dashboard server settings "
                            + "replaces the old token."));
            actor.send(Segment.of(Tone.MUTED, "/detraly status"),
                    Segment.of(Tone.PLAIN, " - show link, geolocation notice, live connection and economy state"));
        }
        if (actor.playerId() != null) {
            actor.send(Segment.of(Tone.MUTED, "/detraly optout"),
                    Segment.of(Tone.PLAIN, " - stop Detraly tracking you, on every server"));
            actor.send(Segment.of(Tone.MUTED, "/detraly optin"), Segment.of(Tone.PLAIN, " - remove your Detraly opt-out, so tracking resumes on every server except servers where you ran /detraly optout yourself (run it on each of those too)"));
        }
    }

    public List<String> complete(CommandActor actor, String[] args) {
        List<String> result = new ArrayList<>();
        try {
            if (args.length != 1) {
                return result;
            }
            List<String> options = new ArrayList<>();
            options.add("help");
            if (actor.isAdmin()) {
                if (actor.isConsole()) {
                    options.add("link");
                }
                options.add("status");
            }
            if (actor.playerId() != null) {
                options.add("optout");
                options.add("optin");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    result.add(option);
                }
            }
        } catch (Throwable e) {
            return new ArrayList<>();
        }
        return result;
    }
}
