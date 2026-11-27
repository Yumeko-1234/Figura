package org.figuramc.figura.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.PerfProbe;

// /figura perf [seconds] and /figura perf nodraw [seconds], see PerfProbe
class PerfCommand {

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        LiteralArgumentBuilder<FiguraClientCommandSource> perf = LiteralArgumentBuilder.literal("perf");
        perf.executes(c -> start(c.getSource(), 10, false));
        perf.then(RequiredArgumentBuilder.<FiguraClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(1, 120))
                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "seconds"), false)));

        LiteralArgumentBuilder<FiguraClientCommandSource> noDraw = LiteralArgumentBuilder.literal("nodraw");
        noDraw.executes(c -> start(c.getSource(), 10, true));
        noDraw.then(RequiredArgumentBuilder.<FiguraClientCommandSource, Integer>argument("seconds", IntegerArgumentType.integer(1, 120))
                .executes(c -> start(c.getSource(), IntegerArgumentType.getInteger(c, "seconds"), true)));
        perf.then(noDraw);
        return perf;
    }

    private static int start(FiguraClientCommandSource source, int seconds, boolean noDraw) {
        if (PerfProbe.isActive()) {
            source.figura$sendError(Component.literal("A perf measurement is already running"));
            return 0;
        }
        PerfProbe.start(seconds, noDraw);
        source.figura$sendFeedback(Component.literal("Measuring for " + seconds + " s" + (noDraw ? " without drawing other avatars" : "") + ", close chat and stand still"));
        return 1;
    }
}
