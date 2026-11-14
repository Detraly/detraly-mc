package com.detraly.analytics.fabric;

import com.detraly.analytics.DetralyCore;
import com.detraly.analytics.command.CommandActor;
import com.detraly.analytics.command.Segment;
import com.detraly.analytics.command.Tone;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

final class FabricCommand {
    private static final int ADMIN_LEVEL = 4;

    private FabricCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<DetralyCore> core) {
        dispatcher.register(Commands.literal("detraly")
                .executes(ctx -> run(ctx, core, ""))
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            DetralyCore c = core.get();
                            String remaining = builder.getRemaining();
                            if (c != null && remaining.indexOf(' ') < 0) {
                                for (String option : c.commands().complete(new Actor(ctx.getSource()),
                                        new String[] {remaining})) {
                                    builder.suggest(option);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> run(ctx, core, StringArgumentType.getString(ctx, "args")))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Supplier<DetralyCore> core, String raw) {
        Actor actor = new Actor(ctx.getSource());
        DetralyCore c = core.get();
        if (c == null) {
            actor.send(Segment.prefixed(Tone.BAD, "Detraly is not running."));
            return 0;
        }
        String trimmed = raw.trim();
        String[] args = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        c.commands().execute(actor, args);
        return 1;
    }

    private static final class Actor implements CommandActor {
        private final CommandSourceStack source;

        Actor(CommandSourceStack source) {
            this.source = source;
        }

        @Override
        public boolean isConsole() {
            return source.getEntity() == null && "Server".equals(source.getTextName());
        }

        @Override
        public UUID playerId() {
            return source.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        }

        @Override
        public boolean isAdmin() {
            return source.hasPermission(ADMIN_LEVEL);
        }

        @Override
        public void send(Segment... parts) {
            MutableComponent line = Component.empty();
            for (Segment part : parts) {
                line.append(Component.literal(part.text()).withStyle(color(part.tone())));
            }
            source.sendSuccess(() -> line, false);
        }

        private static ChatFormatting color(Tone tone) {
            switch (tone) {
                case BRAND:
                    return ChatFormatting.GOLD;
                case GOOD:
                    return ChatFormatting.GREEN;
                case BAD:
                    return ChatFormatting.RED;
                case WARN:
                    return ChatFormatting.YELLOW;
                case MUTED:
                    return ChatFormatting.GRAY;
                default:
                    return ChatFormatting.WHITE;
            }
        }
    }
}
