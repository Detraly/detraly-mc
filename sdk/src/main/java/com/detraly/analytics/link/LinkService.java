package com.detraly.analytics.link;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.command.CommandActor;
import com.detraly.analytics.command.Segment;
import com.detraly.analytics.command.Tone;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.safety.Guard;
import com.detraly.analytics.storage.TokenStore;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class LinkService {
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Logger logger;
    private final Scheduler scheduler;
    private final DetralyApiClient client;
    private final TokenStore tokenStore;
    private final GeolocationState geoState;
    private final List<Runnable> onLinked = new CopyOnWriteArrayList<>();

    public void onLinked(Runnable action) {
        if (action != null) {
            onLinked.add(action);
        }
    }

    public LinkService(Logger logger, Scheduler scheduler, DetralyApiClient client, TokenStore tokenStore,
            GeolocationState geoState) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.client = client;
        this.tokenStore = tokenStore;
        this.geoState = geoState;
    }

    public void link(String rawCode, CommandActor reply) {
        String code = rawCode == null ? "" : rawCode.trim().toLowerCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            reply.send(Segment.prefixed(Tone.BAD,
                    "That does not look like a link code. Copy it from your server settings in the dashboard."));
            return;
        }
        scheduler.async(() -> {
            Segment[] message;
            try {
                message = handle(client.link(code));
            } catch (Throwable e) {
                message = Segment.prefixed(Tone.BAD, "Linking failed. Try again later.");
            }
            Segment[] finalMessage = message;
            try {
                scheduler.sync(Guard.wrap(logger, "the link reply", () -> reply.send(finalMessage)));
            } catch (Throwable e) {
                Guard.report(logger, "the link reply", e);
            }
        });
    }

    public boolean isLinked() {
        return tokenStore.hasToken();
    }

    private Segment[] handle(DetralyApiClient.LinkResult result) {
        switch (result.status()) {
            case OK:
                if (!tokenStore.save(result.token())) {
                    return Segment.prefixed(Tone.BAD,
                            "Linked, but the token could not be saved on this server. Check the folder permissions and the console, then get a new code and try again.");
                }
                geoState.reset();
                for (Runnable action : onLinked) {
                    Guard.run(logger, "a link follow-up", action);
                }
                return Segment.prefixed(Tone.GOOD,
                        "Server linked. To link again later, create a new link code in your server settings in the dashboard.");
            case FORBIDDEN:
                return Segment.prefixed(Tone.BAD,
                        "The IP address this server connects from does not match the server IP saved in the dashboard.");
            case NOT_FOUND:
                return Segment.prefixed(Tone.BAD,
                        "That code is unknown, already used, replaced or expired. Get a fresh code from your server settings in the dashboard.");
            case RATE_LIMITED:
                return Segment.prefixed(Tone.BAD, "Too many link attempts. Wait 60 seconds and try again.");
            case BAD_REQUEST:
                return Segment.prefixed(Tone.BAD, "The link code was rejected. Check that you copied all of it.");
            default:
                return Segment.prefixed(Tone.BAD, "Could not link right now. Try again later.");
        }
    }
}
