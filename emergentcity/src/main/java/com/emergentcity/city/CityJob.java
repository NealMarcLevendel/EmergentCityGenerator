package com.emergentcity.city;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One city generation: sample terrain (server thread, time-sliced) -> simulate and voxelize
 * (background thread) -> place blocks (server thread, time-sliced, undoable).
 * Progress is shown as a boss bar plus chat updates; the final chat summary gives the city's extent.
 */
public final class CityJob implements CityTask {
    enum Stage { SAMPLING, SIMULATING, PLACING, DIGGING, DONE, FAILED, CANCELLED }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "emergentcity-sim");
        t.setDaemon(true);
        return t;
    });

    record Result(CityModel model, BlockPlan plan, Voxelizer vox, Catacombs cat) {}

    final CommandSourceStack source;
    final ServerLevel level;
    final int centerX, centerZ, radius;
    final long seed;
    final Config cfg;
    final TerrainSampler sampler;
    final UndoLog undo;
    final ServerBossEvent bar;
    Stage stage = Stage.SAMPLING;
    CompletableFuture<Result> future;
    Result result;
    Placer placer, digger;
    int ticks, lastChatQuarter = -1;
    volatile int simPhase, simPhases;
    long startNanos = System.nanoTime(), stageNanos = System.nanoTime();

    CityJob(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius, long seed, Config cfg) {
        this.source = source; this.level = level; this.centerX = centerX; this.centerZ = centerZ;
        this.radius = radius; this.seed = seed; this.cfg = cfg;
        int half = radius + cfg.i("mc.margin");
        sampler = new TerrainSampler(level, centerX - half, centerZ - half, 2 * half + 1, 2 * half + 1);
        undo = new UndoLog(level, String.format(Locale.ROOT, "city at %d %d, radius %d, seed %d", centerX, centerZ, radius, seed));
        simPhases = cfg.i("growth.phases") + 1;
        bar = new ServerBossEvent(Component.literal("Emergent City: reading terrain"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_20);
        ServerPlayer p = source.getPlayer();
        if (p != null) bar.addPlayer(p);
        startPanel();
    }

    // ------------------------------------------------------------------ chat helpers

    static MutableComponent tag() {
        return Component.literal("[EmergentCity] ").withStyle(ChatFormatting.GOLD);
    }

    void send(Component c) { source.sendSuccess(() -> c, false); }

    void say(String msg) { send(tag().append(Component.literal(msg).withStyle(ChatFormatting.WHITE))); }

    /** "||||||||||" coloured progress bar, the classic Minecraft chat style. */
    static MutableComponent progressBar(double frac, int width) {
        int filled = (int) Math.round(Geom.clamp(frac, 0, 1) * width);
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("|".repeat(filled)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("|".repeat(width - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Clickable coordinates: clicking puts a teleport command in the chat box. */
    static MutableComponent coords(int x, int y, int z) {
        String tp = String.format(Locale.ROOT, "/tp @s %d %d %d", x, y, z);
        return Component.literal(String.format(Locale.ROOT, "%d %d %d", x, y, z)).withStyle(st -> st
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to teleport here"))));
    }

    static MutableComponent rule() {
        return Component.literal("                                                  ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.STRIKETHROUGH);
    }

    private void startPanel() {
        send(rule());
        send(Component.literal(" Emergent City ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.format(Locale.ROOT, "radius %d around %d, %d  -  seed %d", radius, centerX, centerZ, seed))
                        .withStyle(ChatFormatting.GRAY)));
        send(stageLine(1, "Read terrain", ChatFormatting.YELLOW, "in progress"));
        send(stageLine(2, "Grow streets, blocks and buildings", ChatFormatting.DARK_GRAY, "waiting"));
        send(stageLine(3, "Place blocks", ChatFormatting.DARK_GRAY, "waiting"));
        send(rule());
    }

    static MutableComponent stageLine(int n, String name, ChatFormatting col, String state) {
        String mark = col == ChatFormatting.GREEN ? " ✔ " : (col == ChatFormatting.YELLOW ? " ▶ " : " ○ ");
        return Component.literal(mark).withStyle(col)
                .append(Component.literal(n + ". " + name + "  ").withStyle(col == ChatFormatting.DARK_GRAY ? ChatFormatting.GRAY : ChatFormatting.WHITE))
                .append(Component.literal(state).withStyle(col));
    }

    private void stageDone(int n, String name, String detail) {
        double secs = (System.nanoTime() - stageNanos) / 1e9;
        stageNanos = System.nanoTime();
        send(stageLine(n, name, ChatFormatting.GREEN, String.format(Locale.ROOT, "%s (%.0f s)", detail, secs)));
        lastChatQuarter = -1;
    }

    private void progressChat(String what, double frac) {
        int quarter = (int) Math.floor(frac * 4);
        if (quarter <= lastChatQuarter || quarter >= 4) return;
        lastChatQuarter = quarter;
        if (quarter == 0) return;
        send(Component.literal("   ").append(progressBar(frac, 30))
                .append(Component.literal(String.format(Locale.ROOT, " %s %d%%", what, quarter * 25)).withStyle(ChatFormatting.GRAY)));
    }

    private void updateBar(String text, double frac) {
        if (ticks % 5 != 0) return;
        bar.setName(Component.literal(String.format(Locale.ROOT, "Emergent City: %s - %.0f%%", text, 100 * frac)));
        bar.setProgress((float) Geom.clamp(frac, 0, 1));
    }

    void closeBar() { bar.removeAllPlayers(); }

    // ------------------------------------------------------------------ Minecraft-scale settings

    /**
     * Settings derived for Minecraft scale (1 block = 1 m, towns of a few hundred blocks): narrow lanes,
     * small blocks, shallow plots, closed street walls. Any key set explicitly in the config file wins.
     */
    static Config scaled(Config cfg, int radius) {
        if (!cfg.b("mc.scaleToRadius")) return cfg.i("mc.maxStoreys") > 0 ? cfg.with("arch.maxFloors", cfg.i("mc.maxStoreys")) : cfg;
        int phases = cfg.i("growth.phases");
        double g = Geom.clamp(radius / 300.0, 1.0, 1.3); // bigger cities get slightly wider main streets
        Config c = cfg;
        c = set(c, "growth.initialRadius", Math.round(0.3 * radius));
        c = set(c, "growth.radiusPerPhase", Math.round(0.7 * radius / phases));
        c = set(c, "growth.stepsPerPhase", (int) Geom.clamp(Math.round(280 * Math.pow(radius / 400.0, 1.5) * 2.6), 100, 900));
        c = set(c, "growth.mapMargin", 4);
        // street widths (blocks)
        c = set(c, "street.local.min", 3); c = set(c, "street.local.max", 3.5); // side streets mostly 3 wide
        c = set(c, "street.connector.min", 4); c = set(c, "street.connector.max", Math.round(6 * g));
        c = set(c, "street.primary.min", Math.round(6 * g)); c = set(c, "street.primary.max", Math.round(9 * g));
        c = set(c, "street.civic.min", Math.round(8 * g)); c = set(c, "street.civic.max", Math.round(11 * g));
        c = set(c, "street.boulevard.width", Math.round(14 * g));
        c = set(c, "street.maxStepPerEdge", 1.5);
        c = set(c, "street.junctionExpansion", 1.1);
        c = set(c, "plaza.radiusWidths", 2.0);
        c = set(c, "plaza.maxRadius", Geom.clamp(Math.round(0.1 * radius), 12, 26));
        // smaller blocks: shorter segments, closer branching, smaller loops
        c = set(c, "growth.segmentLength.local", 15); c = set(c, "growth.segmentLength.connector", 18); c = set(c, "growth.segmentLength.primary", 22);
        c = set(c, "growth.branchFrontageMin", 32); c = set(c, "growth.branchFrontageMax", 48); // side streets about a block apart
        // blocks of a fairly even size (about 30-45 m across): no tiny loops, and big blocks get cut sooner,
        // so most blocks keep a courtyard with room to walk around
        c = set(c, "growth.minLoopPerimeter", 130); c = set(c, "growth.goodLoopPerimeterMax", 280);
        c = set(c, "growth.clearanceFree", 13); c = set(c, "growth.clearanceNode", 6);
        // full blocks: streets running alongside each other leave room for two rows of plots and a courtyard
        // (2 x the shallowest plot + 3), and streets meet and branch at near-right angles with gentle bends,
        // so blocks come out regular rather than as wedges and strips
        c = set(c, "growth.minBlockDepth", 17);
        c = set(c, "growth.minJunctionAngleDeg", 50); c = set(c, "growth.branchAngleJitterDeg", 5);
        c = set(c, "growth.curvatureStrength", 0.2); c = set(c, "growth.turnSamplesDeg", "-12,-6,-2,0,2,6,12");
        c = set(c, "growth.boulevardBranchSpacing", 40);
        c = set(c, "growth.minSegment", 5); c = set(c, "growth.snapEndpoint", 5);
        c = set(c, "block.maxArea", 2200); c = set(c, "block.minArea", 150); c = set(c, "block.maxHousingFace", 20000);
        c = set(c, "block.minEdge", 3.5);
        // shallower, narrower plots; every plot built (continuity 1) so street walls are closed
        // near-square plots (about 7-9 wide and deep) so every house has a walkable interior;
        // corner houses front both streets, very sharp corners stay open
        c = set(c, "block.houseDepthMin", 7); c = set(c, "block.houseDepthMax", 9);
        c = set(c, "block.houseFrontageMin", 5); c = set(c, "block.houseFrontageMax", 10); c = set(c, "block.houseFrontageTarget", 7);
        c = set(c, "block.frontageToDepth", 0.95);
        c = set(c, "density.houseUnitArea", 40);
        // height cap: at most 4 storeys (about 13 blocks plus roof) so plaza frontages stay in scale
        c = set(c, "arch.maxFloors", 4);
        // compact storeys: 3 blocks upstairs (2-high windows), 4 on the ground floor (a taller piano nobile on
        // grand houses); storey count still follows street width, so heights stay in proportion to the street
        c = set(c, "arch.floorHeight", 3.4);
        c = set(c, "arch.groundFloorExtra", 0.8);
        c = set(c, "arch.maxHeightToFrontage", 1.9);
        c = set(c, "block.minCornerAngleDeg", 70);
        c = set(c, "block.gapFillDepth", 3);
        c = set(c, "block.continuity", 1.0);
        c = set(c, "block.passageMinPerimeter", 160);
        c = set(c, "house.minArea", 20); c = set(c, "house.minEdge", 3.0); c = set(c, "house.minAngleDeg", 45);
        // a slightly larger cathedral, scaled to the town (radius 200 -> about 20 x 42, 32-48 tall)
        long cw = Math.round(Geom.clamp(0.1 * radius, 17, 24));
        c = set(c, "landmark.cathedral.width", cw); c = set(c, "landmark.cathedral.depth", Math.round(2.1 * cw));
        c = set(c, "landmark.hall.width", 14); c = set(c, "landmark.hall.depth", 16);
        c = set(c, "centers.minSpacing", Math.max(110, Math.round(0.75 * radius)));
        c = set(c, "landmark.minHouses", radius < 150 ? 15 : 30);
        // Minecraft terrain is steep and players climb 1-block steps, so allow stepped lanes on hills;
        // a stronger grade cost still makes streets prefer to follow the contours
        c = set(c, "growth.maxGrade.local", 0.85);
        c = set(c, "growth.maxGrade.connector", 0.6);
        c = set(c, "growth.maxGrade.primary", 0.45);
        c = set(c, "w.grade", 3.0);
        if (cfg.i("mc.maxStoreys") > 0) { // the player's storey limit wins; above the usual 4, heights scale up to reach it
            int ms = cfg.i("mc.maxStoreys");
            c = c.with("arch.maxFloors", ms);
            if (ms > 4) c = c.with("arch.heightScale", ms / 3.0); // the usual town tops out around 3 storeys
        }
        return c;
    }

    private static Config set(Config c, String k, Object v) { return c.has(k) ? c : c.with(k, v); }

    // ------------------------------------------------------------------ state machine

    /** Returns true when the job is finished (successfully or not). */
    @Override
    public boolean tick(MinecraftServer server) {
        ticks++;
        long budget = cfg.i("mc.tickBudgetMs") * 1_000_000L;
        try {
            switch (stage) {
                case SAMPLING -> {
                    boolean done = sampler.tick(budget);
                    double fr = sampler.progress();
                    updateBar("reading terrain", fr);
                    progressChat("terrain read", fr);
                    if (done) {
                        stageDone(1, "Read terrain", sampler.chunksTotal + " chunks");
                        send(stageLine(2, "Grow streets, blocks and buildings", ChatFormatting.YELLOW, "in progress"));
                        stage = Stage.SIMULATING;
                        future = CompletableFuture.supplyAsync(this::simulate, EXEC);
                    }
                }
                case SIMULATING -> {
                    int ph = simPhase;
                    updateBar(ph < simPhases ? "growing the city, phase " + Math.max(1, ph) + " of " + (simPhases - 1)
                            : "shaping blocks and buildings", Math.max(0, ph - 1) / (double) simPhases);
                    if (future.isDone()) {
                        result = future.get();
                        CityModel m = result.model();
                        stageDone(2, "Grow streets, blocks and buildings", String.format(Locale.ROOT,
                                "%d houses in %d blocks, %.1f km of street, %d centre(s), %d plaza(s), %d monument(s)",
                                m.houses.size(), m.blocks.size(), m.g.totalLength() / 1000, m.centers.size(), m.plazas.size(), m.landmarks.size()));
                        send(stageLine(3, "Place blocks", ChatFormatting.YELLOW, String.format(Locale.ROOT, "%,d changes", result.plan().total)));
                        List<Long> order = result.plan().orderedFrom(centerX >> 4, centerZ >> 4);
                        placer = new Placer(level, result.plan(), order, undo);
                        stage = Stage.PLACING;
                    }
                }
                case PLACING -> {
                    boolean done = placer.tick(budget);
                    double fr = placer.progress();
                    updateBar("placing blocks", fr);
                    progressChat("built", fr);
                    if (done) {
                        stageDone(3, "Place blocks", String.format(Locale.ROOT, "%,d blocks changed", placer.placed));
                        Catacombs cat = result.cat();
                        if (cat != null && cat.plan.total > 0) {
                            send(stageLine(4, "Dig the catacombs", ChatFormatting.YELLOW, String.format(Locale.ROOT, "%,d changes", cat.plan.total)));
                            digger = startDigging(level, cat, centerX, centerZ, undo);
                            stage = Stage.DIGGING;
                            return false;
                        }
                        stage = Stage.DONE;
                        closeBar();
                        summary(writeReport(server));
                        return true;
                    }
                }
                case DIGGING -> {
                    boolean done = digger.tick(budget);
                    updateBar("digging the catacombs", digger.progress());
                    progressChat("dug", digger.progress());
                    if (done) {
                        int n = SignWriter.apply(level, result.cat().signs);
                        stageDone(4, "Dig the catacombs", String.format(Locale.ROOT, "%,d blocks changed, %d plaques", digger.placed, n));
                        stage = Stage.DONE;
                        closeBar();
                        summary(writeReport(server));
                        return true;
                    }
                }
                default -> { closeBar(); return true; }
            }
        } catch (Exception e) {
            stage = Stage.FAILED;
            closeBar();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            source.sendFailure(Component.literal("[EmergentCity] Failed: " + cause));
            cause.printStackTrace();
            return true;
        }
        return false;
    }

    private Result simulate() {
        Terrain t = sampler.toTerrain(cfg.i("mc.blurRadius"), "Minecraft area at " + centerX + ", " + centerZ);
        CityModel m = CityGenerator.generate(scaled(cfg, radius), t, seed, false, ph -> simPhase = ph,
                cfg.b("mc.preserveStructures") ? sampler.keepOut() : null);
        Voxelizer v = new Voxelizer(m, sampler, level.getMinBuildHeight(), level.getMaxBuildHeight());
        BlockPlan plan = v.build();
        Catacombs cat = null;
        if (cfg.b("mc.catacombs")) { cat = new Catacombs(m, v, seed); cat.build(); }
        return new Result(m, plan, v, cat);
    }

    /** Final chat summary: where the city starts and ends, with clickable coordinates. */
    private void summary(String reportFile) {
        Voxelizer v = result.vox();
        CityModel m = result.model();
        int y0 = groundY(m.centers.get(0).pos);
        send(rule());
        send(Component.literal(" City complete ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.format(Locale.ROOT, "in %.0f s - seed %d", (System.nanoTime() - startNanos) / 1e9, seed))
                        .withStyle(ChatFormatting.GRAY)));
        send(Component.literal(" Starts (north-west corner): ").withStyle(ChatFormatting.WHITE).append(coords(v.minX, groundY(v.minX, v.minZ), v.minZ)));
        send(Component.literal(" Ends (south-east corner):   ").withStyle(ChatFormatting.WHITE).append(coords(v.maxX, groundY(v.maxX, v.maxZ), v.maxZ)));
        send(Component.literal(String.format(Locale.ROOT, " Extent: x %d to %d, z %d to %d  (%d x %d blocks)",
                v.minX, v.maxX, v.minZ, v.maxZ, v.maxX - v.minX + 1, v.maxZ - v.minZ + 1)).withStyle(ChatFormatting.GRAY));
        P c0 = m.centers.get(0).pos;
        send(Component.literal(" Main square: ").withStyle(ChatFormatting.WHITE)
                .append(coords(v.ox + (int) Math.round(c0.x()), y0 + 1, v.oz + (int) Math.round(c0.y()))));
        for (Landmark l : m.landmarks) {
            P f = l.front;
            send(Component.literal(" " + Character.toUpperCase(l.type.charAt(0)) + l.type.substring(1) + String.format(Locale.ROOT, " (%.0f m tall): ", l.height))
                    .withStyle(ChatFormatting.WHITE).append(coords(v.ox + (int) Math.round(f.x()), groundY(f) + 1, v.oz + (int) Math.round(f.y()))));
        }
        if (m.cfg.b("landmark.grand") && m.landmarks.stream().noneMatch(l -> l.grand))
            send(Component.literal(" No plaza frontage was large enough for the grand landmark; the town built an ordinary cathedral (or none) instead.").withStyle(ChatFormatting.GRAY));
        cemeteryLines(this::send, m, v);
        if (result.cat() != null) catacombLines(this::send, result.cat());
        send(Component.literal(String.format(Locale.ROOT, " %d houses (%d gap cells closed), %,d blocks changed. Report: %s",
                m.houses.size(), v.gapCellsFilled, placer.placed + (digger != null ? digger.placed : 0), reportFile)).withStyle(ChatFormatting.GRAY));
        send(Component.literal(" Undo with ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("/emergentcity undo").withStyle(st -> st.withColor(ChatFormatting.YELLOW)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/emergentcity undo")))));
        send(rule());
    }

    /** The catacombs' placer: underground, existing man-made blocks (mineshafts, strongholds) are left alone. */
    static Placer startDigging(ServerLevel level, Catacombs cat, int cx, int cz, UndoLog undo) {
        Placer p = new Placer(level, cat.plan, cat.plan.orderedFrom(cx >> 4, cz >> 4), undo);
        p.naturalBelowY = cat.yHi;
        return p;
    }

    /** One chat line per cemetery: its size and its gate (clickable). */
    static void cemeteryLines(java.util.function.Consumer<Component> out, CityModel m, Voxelizer v) {
        for (Cemetery cm : m.cemeteries) {
            int[] g = v.cemeteryGates.get(cm.id);
            if (g == null) continue;
            long maus = v.mausoleums.stream().filter(z -> z[4] == cm.id).count();
            out.accept(Component.literal(String.format(Locale.ROOT, " Cemetery (%.0f m2, %d mausoleum%s): ", cm.area, maus, maus == 1 ? "" : "s"))
                    .withStyle(ChatFormatting.WHITE).append(coords(v.ox + g[0], g[2] + 1, v.oz + g[1])));
        }
    }

    /** Chat lines with the catacombs' entrances and sights (clickable coordinates). */
    static void catacombLines(java.util.function.Consumer<Component> out, Catacombs cat) {
        out.accept(Component.literal(" Catacombs: " + cat.summary()).withStyle(ChatFormatting.WHITE));
        int shown = 0;
        java.util.Set<String> along = java.util.Set.of("Samaritaine fountain", "Sepulchral lamp", "Gilbert's tomb", "Crypt of the Passion", "Sacellum",
                "Quarrymen's footbath", "Port-Mahon model", "Ossuary (middle)", "Flooded gallery (lower level)", "Grate onto a lower gallery");
        for (Catacombs.Spot sp : cat.spots) {
            if (along.contains(sp.what())) continue; // sights along the visitor route are listed in the report
            if (sp.what().equals("Manhole") && shown++ > 0) continue;
            out.accept(Component.literal("   " + sp.what() + ": ").withStyle(ChatFormatting.GRAY).append(coords(sp.x(), sp.y(), sp.z())));
        }
    }

    private int groundY(P simPoint) { return groundY(sampler.ox + (int) Math.round(simPoint.x()), sampler.oz + (int) Math.round(simPoint.y())); }

    private int groundY(int x, int z) {
        int i = Math.max(0, Math.min(sampler.cols - 1, x - sampler.ox)), j = Math.max(0, Math.min(sampler.rows - 1, z - sampler.oz));
        Voxelizer v = result.vox();
        int k = j * sampler.cols + i;
        return v.kind[k] != Voxelizer.NONE ? v.lvl[k] + 1 : sampler.ground[k] + 1;
    }

    private String writeReport(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("emergentcity");
            Files.createDirectories(dir);
            String name = String.format(Locale.ROOT, "city_%d_%d_seed%d.txt", centerX, centerZ, seed);
            CityModel m = result.model();
            Voxelizer v = result.vox();
            StringBuilder sb = new StringBuilder(m.metrics.report(m));
            sb.append(String.format(Locale.ROOT, "%nWorld extent: x %d..%d, z %d..%d%n", v.minX, v.maxX, v.minZ, v.maxZ));
            sb.append("World origin of simulation coordinates: x=").append(sampler.ox).append(" z=").append(sampler.oz)
                    .append(" (simulation y = world z)\n");
            if (result.cat() != null) sb.append(result.cat().report());
            sb.append("\nSTRUCTURAL DECISIONS\n");
            for (String ev : m.log.events) sb.append(ev).append('\n');
            Files.writeString(dir.resolve(name), sb.toString());
            return "emergentcity/" + name;
        } catch (Exception e) {
            return "(could not write report: " + e.getMessage() + ")";
        }
    }

    @Override public UndoLog undoLog() { return undo; }

    @Override public void cancel() { stage = Stage.CANCELLED; closeBar(); }

    @Override
    public String status() {
        return switch (stage) {
            case SAMPLING -> String.format(Locale.ROOT, "reading terrain %.0f%%", 100 * sampler.progress());
            case SIMULATING -> "growing the city (phase " + Math.max(1, simPhase) + " of " + (simPhases - 1) + ")";
            case PLACING -> String.format(Locale.ROOT, "placing blocks %.0f%%", 100 * placer.progress());
            case DIGGING -> String.format(Locale.ROOT, "digging the catacombs %.0f%%", 100 * digger.progress());
            default -> stage.name().toLowerCase(Locale.ROOT);
        };
    }
}
