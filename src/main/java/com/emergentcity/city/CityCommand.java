package com.emergentcity.city;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * /emergentcity generate <radius> [options]   villagers grow and build a town around you (live). Options, any order:
 *        <seed>          a number: reproduce a town
 *        freestanding    monuments stand free in the middle of their squares (cathedral, church, forum, colosseum, halls);
 *                        squares grow by merging neighbouring blocks to stay in proportion, with large trees round them
 *        grand           build one oversized landmark (grand cathedral) with a square to match
 *        bricks          brick facades in several tones with red nether brick roofs (= palette=redbrick)
 *        palette=<name>  warm, redbrick, brick, whitewash or stone
 *        boulevards[=n]  wide straight avenues from the main centre (default 2 = one line through the centre; up to 4)
 *        evolve          after the town is complete, a crew keeps developing it
 *        er=<radius>     evolution radius (implies evolve); terrain is read out to this radius
 *        maintain        masons replace house blocks players break (sneak-break removes a block for good)
 *        insane          insane evolution (implies evolve): the crew ignores the storey cap and keeps building taller
 *        storeys=<n>     the most storeys a house may have (default automatic: 4)
 *        units=off       draw every house from scratch instead of from the unit designs
 * /emergentcity evolve [radius] [maintain]    bring the last finished villager town back to life
 * /emergentcity stop                       end evolution gracefully
 * /emergentcity instant <radius> [seed] [grand] [bricks] [boulevards]   plan and place the whole city at once
 * /emergentcity units                      reload the unit designs (built-in + saved with structure blocks) and list them
 * /emergentcity status | cancel | undo | reloadconfig
 */
public final class CityCommand {
    private CityCommand() {}

    private static CityTask job;
    private static final Deque<UndoLog> history = new ArrayDeque<>();
    private static UndoLog undoing;
    private static CommandSourceStack undoSource;
    private static Config config;

    static Path configPath() { return FMLPaths.CONFIGDIR.get().resolve("emergentcity.properties"); }

    static Config config() {
        if (config == null) {
            try {
                Path p = configPath();
                if (!Files.exists(p)) Config.defaults().write(p, true);
                config = Config.load(p);
            } catch (Exception e) {
                System.err.println("[EmergentCity] Config problem, using defaults: " + e);
                config = Config.defaults();
            }
        }
        return config;
    }

    /** Options after the radius, in any order. */
    static final class Options {
        Long seed;
        boolean evolve, grand, maintain, insane, free;
        Boolean units;
        Integer storeys;
        String palette;
        Integer boulevards;
        Integer evolveRadius;
        String error;
    }

    static Options parse(String text, boolean live) {
        Options o = new Options();
        if (text == null) return o;
        for (String tok : text.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            String t = tok.toLowerCase(java.util.Locale.ROOT);
            try {
                if (t.matches("-?\\d+")) { o.seed = Long.parseLong(t); continue; }
                if (t.equals("grand")) { o.grand = true; continue; }
                if (t.equals("freestanding") || t.equals("free")) { o.free = true; continue; }
                if (t.equals("bricks") || t.equals("brick")) { o.palette = "redbrick"; continue; }
                if (t.equals("units=off") || t.equals("nounits") || t.equals("units=false")) { o.units = false; continue; }
                if (t.equals("units") || t.equals("units=on") || t.equals("units=true")) { o.units = true; continue; }
                if (t.equals("boulevards") || t.equals("boulevard")) { o.boulevards = 2; continue; }
                if (t.startsWith("boulevards=")) {
                    o.boulevards = Integer.parseInt(t.substring(11));
                    if (o.boulevards < 0 || o.boulevards > 4) { o.error = "boulevards=<n> takes 0 to 4."; return o; }
                    continue;
                }
                if (t.startsWith("palette=")) {
                    o.palette = t.substring(8);
                    if (!java.util.List.of("warm", "redbrick", "brick", "whitewash", "stone").contains(o.palette)) {
                        o.error = "Unknown palette '" + o.palette + "'. Palettes: warm, redbrick, brick, whitewash, stone.";
                        return o;
                    }
                    continue;
                }
                if (live && t.equals("evolve")) { o.evolve = true; continue; }
                if (live && t.equals("maintain")) { o.maintain = true; continue; }
                if (live && (t.equals("insane") || t.equals("insane_evolve") || t.equals("insaneevolve"))) { o.insane = true; o.evolve = true; continue; }
                if (t.startsWith("storeys=") || t.startsWith("stories=") || t.startsWith("floors=")) {
                    o.storeys = Integer.parseInt(t.substring(t.indexOf('=') + 1));
                    if (o.storeys < 1 || o.storeys > 30) { o.error = "storeys=<n> takes 1 to 30."; return o; }
                    continue;
                }
                if (live && (t.startsWith("er=") || t.startsWith("evolveradius="))) {
                    o.evolveRadius = Integer.parseInt(t.substring(t.indexOf('=') + 1));
                    o.evolve = true;
                    if (o.evolveRadius < 16) { o.error = "Evolution radius must be at least 16."; return o; }
                    continue;
                }
            } catch (NumberFormatException e) { o.error = "Not a number in '" + tok + "'."; return o; }
            o.error = "Unknown option '" + tok + "'. Options: [seed] grand bricks palette=<name> boulevards[=n] storeys=<n> freestanding units=off" + (live ? " evolve er=<radius> maintain insane" : "") + ".";
            return o;
        }
        return o;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String name, boolean live) {
        var radius = Commands.argument("radius", IntegerArgumentType.integer(16, 2000))
                .executes(ctx -> generate(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), new Options(), live));
        var options = Commands.argument("options", StringArgumentType.greedyString())
                .executes(ctx -> {
                    Options o = parse(StringArgumentType.getString(ctx, "options"), live);
                    if (o.error != null) { fail(ctx.getSource(), o.error); return 0; }
                    return generate(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), o, live);
                });
        return Commands.literal(name).then(radius.then(options));
    }

    /** The most recent villager town, kept so /emergentcity evolve can bring it back to life. */
    private static LiveCityJob lastTown;

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("emergentcity").requires(s -> s.hasPermission(2))
                .then(mode("generate", true))
                .then(mode("instant", false))
                .then(Commands.literal("status").executes(ctx -> {
                    msg(ctx.getSource(), job != null ? "Working: " + job.status() : undoing != null ? "Undoing..." : "Idle. " + history.size() + " undoable city(ies).");
                    return 1;
                }))
                .then(Commands.literal("evolve")
                        .executes(ctx -> evolve(ctx.getSource(), null))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .executes(ctx -> evolve(ctx.getSource(), StringArgumentType.getString(ctx, "options")))))
                .then(Commands.literal("stop").executes(ctx -> {
                    if (job instanceof LiveCityJob lj && lj.isEvolving()) {
                        lj.requestStop();
                        msg(ctx.getSource(), "Stopping: the masons finish the job in hand, then the crew goes home.");
                        return 1;
                    }
                    msg(ctx.getSource(), job == null ? "Nothing is running." : "Not evolving; use /emergentcity cancel to stop a town being built.");
                    return 0;
                }))
                .then(Commands.literal("cancel").executes(ctx -> {
                    if (job == null) { msg(ctx.getSource(), "Nothing to cancel."); return 0; }
                    job.cancel();
                    if (job.undoLog().n > 0 || !job.undoLog().entities.isEmpty()) history.push(job.undoLog());
                    job = null;
                    msg(ctx.getSource(), "Cancelled. The villagers are free; what was built can be reverted with /emergentcity undo.");
                    return 1;
                }))
                .then(Commands.literal("undo").executes(ctx -> {
                    if (job != null || undoing != null) { fail(ctx.getSource(), "Busy; wait or /emergentcity cancel first."); return 0; }
                    if (history.isEmpty()) { fail(ctx.getSource(), "Nothing to undo (undo history is kept until the server stops)."); return 0; }
                    undoing = history.pop();
                    undoSource = ctx.getSource();
                    msg(ctx.getSource(), "Reverting " + undoing.label + " (" + undoing.n + " block changes, " + undoing.entities.size() + " villagers)...");
                    return 1;
                }))
                .then(Commands.literal("units").executes(ctx -> {
                    unitDirs(ctx.getSource().getServer());
                    Units.reload();
                    msg(ctx.getSource(), Units.lastReport + ". Designs per angle: " + new java.util.TreeMap<>(Units.summary())
                            + ". Your own: save structures named emergentcity:units/<name>_ground (also _shop, _upper, _roof) with a structure block.");
                    return 1;
                }))
                .then(Commands.literal("reloadconfig").executes(ctx -> {
                    config = null;
                    config();
                    unitDirs(ctx.getSource().getServer());
                    Units.reload();
                    msg(ctx.getSource(), "Config reloaded from " + configPath() + "; " + Units.lastReport + ".");
                    return 1;
                })));
    }

    /**
     * Where players' own unit designs are read from: the world's generated structures (what a structure block saves as
     * "emergentcity:units/...") and config/emergentcity/units. Reloads the library when the world changes.
     */
    static void unitDirs(MinecraftServer server) {
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        try {
            if (server != null) dirs.add(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.GENERATED_DIR).resolve("emergentcity").resolve("structures").resolve("units"));
        } catch (Exception ignored) {}
        dirs.add(FMLPaths.CONFIGDIR.get().resolve("emergentcity").resolve("units"));
        if (!dirs.equals(Units.EXTRA_DIRS)) {
            Units.EXTRA_DIRS.clear();
            Units.EXTRA_DIRS.addAll(dirs);
            Units.reload();
        }
    }

    private static void msg(CommandSourceStack s, String m) { s.sendSuccess(() -> Component.literal("[EmergentCity] " + m), false); }
    private static void fail(CommandSourceStack s, String m) { s.sendFailure(Component.literal("[EmergentCity] " + m)); }

    /** /emergentcity evolve [radius] [maintain]: bring the last finished villager town back to life. */
    private static int evolve(CommandSourceStack src, String text) {
        if (job != null || undoing != null) { fail(src, "Busy (" + (job != null ? job.status() : "undo") + ")."); return 0; }
        if (lastTown == null || lastTown.stage != LiveCityJob.Stage.DONE || history.peek() != lastTown.undoLog()) {
            fail(src, "No finished villager town to evolve. Use /emergentcity generate <radius> evolve.");
            return 0;
        }
        Integer er = null;
        boolean maintain = false, insane = false;
        if (text != null) for (String tok : text.trim().split("\\s+")) {
            String t = tok.toLowerCase(java.util.Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.equals("maintain")) { maintain = true; continue; }
            if (t.equals("insane")) { insane = true; continue; }
            if (t.startsWith("er=") || t.startsWith("evolveradius=")) t = t.substring(t.indexOf('=') + 1);
            try { er = Integer.parseInt(t); } catch (NumberFormatException e) { fail(src, "Usage: /emergentcity evolve [radius] [maintain] [insane]"); return 0; }
            if (er < 16) { fail(src, "Evolution radius must be at least 16."); return 0; }
        }
        history.pop(); // back in progress; it returns to the undo history when evolution stops
        if (er != null) lastTown.evolveRadius = er;
        if (maintain) lastTown.enableMaintenance(); else lastTown.maintain = false;
        lastTown.insane = insane;
        lastTown.startEvolving(true);
        job = lastTown;
        return 1;
    }

    /** A player broke a block: an evolving town with 'maintain' may schedule a repair. */
    public static void onBlockBroken(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos, boolean sneaking) {
        if (job instanceof LiveCityJob lj && lj.level == level) lj.onBlockBroken(pos, sneaking);
    }

    private static int generate(CommandSourceStack src, int radius, Options o, boolean live) {
        if (job != null || undoing != null) { fail(src, "A city is already being built (" + (job != null ? job.status() : "undo") + ")."); return 0; }
        Config cfg = config();
        if (radius < cfg.i("mc.minRadius") || radius > cfg.i("mc.maxRadius")) {
            fail(src, "Radius must be between " + cfg.i("mc.minRadius") + " and " + cfg.i("mc.maxRadius") + " (see config/emergentcity.properties).");
            return 0;
        }
        long seed = o.seed != null ? o.seed : src.getLevel().getRandom().nextLong();
        if (o.grand) cfg = cfg.with("landmark.grand", true);
        if (o.free) cfg = cfg.with("mc.freestanding", true);
        if (o.palette != null) cfg = cfg.with("mc.palette", o.palette);
        if (o.boulevards != null) cfg = cfg.with("mc.boulevards", o.boulevards);
        if (o.storeys != null) cfg = cfg.with("mc.maxStoreys", o.storeys);
        if (o.units != null) cfg = cfg.with("mc.units", o.units);
        if (cfg.b("mc.units")) {
            unitDirs(src.getServer());
            if (Units.all().isEmpty()) msg(src, "No unit designs found; every house is drawn from scratch.");
        }
        int er = o.evolveRadius != null ? o.evolveRadius : radius;
        if (er > cfg.i("mc.maxRadius")) { fail(src, "Evolution radius must be at most " + cfg.i("mc.maxRadius") + "."); return 0; }
        int x = (int) Math.floor(src.getPosition().x), z = (int) Math.floor(src.getPosition().z);
        if (live) {
            LiveCityJob lj = new LiveCityJob(src, src.getLevel(), x, z, radius, seed, cfg, o.evolve, er);
            lj.maintain = o.maintain;
            lj.insane = o.insane;
            job = lj;
        } else job = new CityJob(src, src.getLevel(), x, z, radius, seed, cfg);
        return 1;
    }

    /** Called every server tick (END phase). */
    public static void tick(MinecraftServer server) {
        if (job != null && job.tick(server)) {
            if (job.undoLog().n > 0 || !job.undoLog().entities.isEmpty()) history.push(job.undoLog());
            if (job instanceof LiveCityJob lj) lastTown = lj;
            while (history.size() > 5) history.removeLast();
            job = null;
        }
        if (undoing != null && undoing.undoTick(config().i("mc.tickBudgetMs") * 1_000_000L)) {
            msg(undoSource, "Undo complete.");
            undoing = null;
        }
    }

    /** Server stopping: free the villagers and release forced chunks so nothing is left half-controlled. */
    public static void shutdown() {
        if (job != null) job.cancel();
        job = null;
    }
}
