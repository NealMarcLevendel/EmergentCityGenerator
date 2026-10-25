package com.emergentcity.city;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Villager mode: the agents of the simulation are villagers in the world.
 *
 * Surveyors (cartographers) are the street-growing agents. Each one walks to the end of a street it is
 * responsible for, re-reads the terrain around it, makes the growth decision there (the same proposal,
 * constraint and cost rules as the simulation), then walks the new segment while it is paved behind
 * it. Branches recruit new surveyors. Masons build what the city's feedback analysis decides between
 * growth phases: block paving and gardens, houses floor by floor, monuments and plazas. Finished
 * buildings become obstacles that later streets must route around. When the city is complete the
 * villagers are released as ordinary townsfolk with beds and job blocks in the houses.
 */
public final class LiveCityJob implements CityTask {
    enum Stage { SAMPLING, GROWING, ANALYSING, FINISHING, EVOLVING, STOPPING, DONE, FAILED, CANCELLED }
    enum Role { SURVEYOR, MASON }
    enum AState { IDLE, WALK, DECIDE, LAY, BUILD }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "emergentcity-live");
        t.setDaemon(true);
        return t;
    });

    static final class Agent {
        final Role role;
        Villager v;
        AState st = AState.IDLE;
        double x, z; // simulation coordinates
        final List<P> path = new ArrayList<>();
        int pathIdx;
        GrowthEngine.Head head;
        Edge laying;
        OpList lay;
        int layDone;
        BuildTask task;
        int taskDone;

        Agent(Role role) { this.role = role; }
    }

    static final class BuildTask {
        final OpList ops;
        final P site;
        final String kind;
        final int rate;
        /** Houses this task builds, with the ops that make each one (remembered so they can be demolished later). */
        final List<House> houses = new ArrayList<>();
        final List<OpList> houseOps = new ArrayList<>();
        final List<House> forgets = new ArrayList<>();

        BuildTask(OpList ops, P site, String kind, int rate) { this.ops = ops; this.site = site; this.kind = kind; this.rate = rate; }

        BuildTask registers(House h, OpList o) { houses.add(h); houseOps.add(o); return this; }

        /** Redevelopment: this job clears (part of) a block; when all such jobs finish, the block is re-planned. */
        CityBlock clearsBlock;
    }

    /** One change an evolving town decides on: houses to tear down and houses to (re)build in their place. */
    static final class Change {
        final String action;
        final List<House> demolish = new ArrayList<>(), build = new ArrayList<>();
        boolean toGarden;
        P site;
        CityBlock clearsBlock;

        Change(String action) { this.action = action; }
    }

    record AnalysisResult(Voxelizer vox, List<BuildTask> tasks, List<Integer> protectCells, String summary) {}

    final CommandSourceStack source;
    final ServerLevel level;
    final int centerX, centerZ, radius;
    final long seed;
    final Config cfg;
    final TerrainSampler sampler;
    final UndoLog undo;
    final ServerBossEvent bar;
    Stage stage = Stage.SAMPLING;
    Config sc;
    CityModel m;
    GrowthEngine ge;
    Voxelizer vox;
    boolean[] protect, groundDone;
    final Map<Integer, Integer> nodeLevels = new HashMap<>();
    /** Lamp posts placed so far (cell -> walking level), shared with every voxelizer rebuild. */
    final Map<Long, Integer> lampCells = new java.util.concurrent.ConcurrentHashMap<>();
    /** Party walls: columns standing houses lean on (shared with every voxelizer rebuild). */
    final Map<Long, List<Voxelizer.Need>> partyNeed = new java.util.concurrent.ConcurrentHashMap<>();
    final List<Agent> agents = new ArrayList<>();
    final ArrayDeque<BuildTask> queue = new ArrayDeque<>();
    final Set<Long> forced = new HashSet<>();
    /** Cells paved for each plaza (by junction node id), so plazas can grow, shrink, dissolve or appear elsewhere. */
    final Map<Integer, Set<Long>> plazaPaved = new HashMap<>();
    /** Highest y placed in each column (cleared again when something is rebuilt there). */
    int[] placedTop;
    /** Positions of standing buildings' overhangs outside their footprints, kept clear of street and plaza work. */
    final Set<Long> overhangs = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Evolving towns: radius (blocks, around the main square) within which the town keeps changing. */
    int evolveRadius;
    /** Streets taken up this review (removed from the graph on the server thread), unpaved by the next analysis. */
    final List<Object[]> removedEdges = new ArrayList<>();
    /** Blocks being cleared for redevelopment, and those whose clearing is finished (re-planned at the next review). */
    final Set<CityBlock> clearing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
            cleared = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    final Map<CityBlock, Integer> clearingLeft = new java.util.IdentityHashMap<>();
    final Set<Landmark> builtLandmarks = new HashSet<>();
    CompletableFuture<AnalysisResult> analysis;
    int phase, phases, stepsThisPhase, stepsPerPhase, ticks, tasksQueued, tasksDone, housesDone;
    // evolving towns
    boolean evolve;
    int cycle, cycleStartTick, evolveSteps;
    long evolveStartTick;
    final Map<House, OpList> built = new java.util.IdentityHashMap<>();
    final Map<House, Integer> builtFloors = new java.util.IdentityHashMap<>(), builtCycle = new java.util.IdentityHashMap<>(),
            builtBase = new java.util.IdentityHashMap<>();
    /** Columns this job has placed blocks in: never re-read as "ground" (roofs, cornices and lamps are not terrain). */
    boolean[] touched;
    final Set<House> busyHouses = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    List<Change> pendingChanges = new ArrayList<>();
    final Map<String, Integer> evolveCounts = new java.util.TreeMap<>();
    final Map<String, Integer> cycleCounts = new java.util.TreeMap<>();
    long placed, startNanos = System.nanoTime();
    long tickEnd;

    LiveCityJob(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius, long seed, Config cfg) {
        this(source, level, centerX, centerZ, radius, seed, cfg, false);
    }

    /** evolve = after the town is complete, a crew of villagers keeps growing and rebuilding it. */
    LiveCityJob(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius, long seed, Config cfg, boolean evolve) {
        this(source, level, centerX, centerZ, radius, seed, cfg, evolve, radius);
    }

    /** evolveRadius: how far from the centre evolution may change the town (terrain is read out to the larger radius). */
    LiveCityJob(CommandSourceStack source, ServerLevel level, int centerX, int centerZ, int radius, long seed, Config cfg, boolean evolve,
                int evolveRadius) {
        this.source = source; this.level = level; this.centerX = centerX; this.centerZ = centerZ;
        this.radius = radius; this.seed = seed; this.cfg = cfg; this.evolve = evolve;
        this.evolveRadius = evolveRadius;
        int half = Math.max(radius, evolve ? evolveRadius : radius) + cfg.i("mc.margin");
        sampler = new TerrainSampler(level, centerX - half, centerZ - half, 2 * half + 1, 2 * half + 1);
        undo = new UndoLog(level, String.format(Locale.ROOT, "villager city at %d %d, radius %d, seed %d", centerX, centerZ, radius, seed));
        bar = new ServerBossEvent(Component.literal("Emergent City: reading terrain"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_20);
        ServerPlayer p = source.getPlayer();
        if (p != null) bar.addPlayer(p);
        send(CityJob.rule());
        send(Component.literal(" Emergent City ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.format(Locale.ROOT, "villagers will grow a town of radius %d around %d, %d  -  seed %d",
                        radius, centerX, centerZ, seed)).withStyle(ChatFormatting.GRAY)));
        send(CityJob.stageLine(1, "Read terrain", ChatFormatting.YELLOW, "in progress"));
        send(CityJob.stageLine(2, "Surveyors grow streets, masons build", ChatFormatting.DARK_GRAY, "waiting"));
        send(CityJob.stageLine(3, "Release the townsfolk", ChatFormatting.DARK_GRAY, "waiting"));
        if (evolve) send(CityJob.stageLine(4, "Keep evolving (until /emergentcity stop)", ChatFormatting.DARK_GRAY, "waiting"));
        send(CityJob.rule());
    }

    void send(Component c) { source.sendSuccess(() -> c, false); }

    @Override public UndoLog undoLog() { return undo; }

    @Override
    public String status() {
        return switch (stage) {
            case SAMPLING -> String.format(Locale.ROOT, "reading terrain %.0f%%", 100 * sampler.progress());
            case GROWING, ANALYSING -> String.format(Locale.ROOT, "phase %d of %d: %d surveyors, %d masons, %d houses built, %d jobs queued",
                    phase, phases, count(Role.SURVEYOR), count(Role.MASON), housesDone, queue.size());
            case FINISHING -> String.format(Locale.ROOT, "masons finishing: %d jobs left", queue.size() + busy(Role.MASON));
            case EVOLVING -> "evolving: " + evolveStats();
            case STOPPING -> String.format(Locale.ROOT, "stopping: masons finishing %d job(s) in hand", busy(Role.MASON));
            default -> stage.name().toLowerCase(Locale.ROOT);
        };
    }

    @Override
    public void cancel() {
        stage = Stage.CANCELLED;
        releaseAll();
    }

    boolean isEvolving() { return stage == Stage.EVOLVING || (evolve && (stage == Stage.ANALYSING || stage == Stage.STOPPING)); }

    /** Graceful stop of an evolving town: no new changes, masons finish what they hold, then everyone is released. */
    void requestStop() {
        queue.clear();
        pendingChanges.clear();
        stage = Stage.STOPPING;
    }

    String evolveStats() {
        int houses = 0;
        for (CityBlock b : m.blocks) houses += b.houses.size();
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "review %d, %d houses, %.1f km of street, jobs queued %d", cycle, houses,
                m.g.totalLength() / 1000, queue.size()));
        if (!evolveCounts.isEmpty()) sb.append(", changes ").append(evolveCounts);
        return sb.toString();
    }

    // ------------------------------------------------------------------ main loop

    @Override
    public boolean tick(MinecraftServer server) {
        ticks++;
        long budget = cfg.i("mc.tickBudgetMs") * 1_000_000L;
        tickEnd = System.nanoTime() + budget;
        try {
            switch (stage) {
                case SAMPLING -> {
                    boolean done = sampler.tick(budget);
                    updateBar("reading terrain", sampler.progress() * 0.05);
                    if (done) startGrowing();
                }
                case GROWING, ANALYSING, FINISHING, EVOLVING, STOPPING -> live(server);
                default -> { return true; }
            }
        } catch (Exception e) {
            stage = Stage.FAILED;
            releaseAll();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            source.sendFailure(Component.literal("[EmergentCity] Failed: " + cause));
            cause.printStackTrace();
            return true;
        }
        return stage == Stage.DONE;
    }

    private void startGrowing() {
        Terrain t = sampler.toTerrain(cfg.i("mc.blurRadius"), "Minecraft area at " + centerX + ", " + centerZ);
        sc = CityJob.scaled(cfg, radius);
        m = new CityModel(sc, t, seed);
        if (cfg.b("mc.preserveStructures")) m.keepOut = sampler.keepOut();
        ge = new GrowthEngine(m);
        ge.obstacle = this::blocked;
        ge.maxRadius = radius;
        ge.seed();
        Analysis.updateAttraction(m);
        protect = new boolean[t.cols * t.rows];
        groundDone = new boolean[t.cols * t.rows];
        touched = new boolean[t.cols * t.rows];
        placedTop = new int[t.cols * t.rows];
        java.util.Arrays.fill(placedTop, Integer.MIN_VALUE);
        vox = new Voxelizer(m, sampler, level.getMinBuildHeight(), level.getMaxBuildHeight(), nodeLevels, lampCells);
        vox.incrementalLamps = true;
        vox.placedTop = placedTop;
        vox.overhangs = overhangs;
        vox.partyNeed = partyNeed;
        vox.protect = protect;
        phases = sc.i("growth.phases");
        stepsPerPhase = sc.i("growth.stepsPerPhase");
        phase = 1;
        ge.beginPhase(phase);
        stage = Stage.GROWING;
        P c0 = m.centers.get(0).pos;
        for (int k = 0; k < Math.min(ge.heads.size(), sc.i("mc.live.surveyors")); k++) spawn(Role.SURVEYOR, c0);
        for (int k = 0; k < 2; k++) spawn(Role.MASON, c0);
        send(CityJob.stageLine(1, "Read terrain", ChatFormatting.GREEN, sampler.chunksTotal + " chunks"));
        send(CityJob.stageLine(2, "Surveyors grow streets, masons build", ChatFormatting.YELLOW,
                String.format(Locale.ROOT, "%d surveyors set out from the market site at ", count(Role.SURVEYOR)))
                .append(CityJob.coords(wx(c0.x()), surfaceY(c0.x(), c0.y()), wz(c0.y()))));
    }

    private void live(MinecraftServer server) {
        if (ticks % 40 == 0) manageChunks();
        if (maintain && ticks % 20 == 0) dispatchRepairs();
        for (Agent a : new ArrayList<>(agents)) {
            if (a.v == null || a.v.isRemoved()) respawn(a);
            if (a.role == Role.SURVEYOR) surveyor(a); else mason(a);
        }
        if (stage == Stage.GROWING) {
            boolean anyWorking = false;
            for (Agent a : agents) if (a.role == Role.SURVEYOR && a.st != AState.IDLE) anyWorking = true;
            boolean noWork = !anyWorking && ge.pickFree(busyHeads()) == null;
            if (stepsThisPhase >= stepsPerPhase || noWork) {
                boolean settled = true;
                for (Agent a : agents) if (a.role == Role.SURVEYOR && (a.st == AState.LAY || a.st == AState.DECIDE)) settled = false;
                if (settled) startAnalysis(phase >= phases);
            }
        } else if (stage == Stage.ANALYSING && analysis != null && analysis.isDone()) {
            finishAnalysis();
        } else if (stage == Stage.FINISHING && queue.isEmpty() && busy(Role.MASON) == 0) {
            complete(server);
            if (evolve) startEvolving(false);
        } else if (stage == Stage.EVOLVING) {
            int maxMin = sc.i("mc.evolve.maxMinutes");
            if (maxMin > 0 && ticks - evolveStartTick >= maxMin * 1200L) { requestStop(); return; }
            boolean settled = true;
            for (Agent a : agents) if (a.role == Role.SURVEYOR && (a.st == AState.LAY || a.st == AState.DECIDE)) settled = false;
            if (ticks - cycleStartTick >= sc.i("mc.evolve.cycleSeconds") * 20 && settled) startEvolveCycle();
            if (ticks % 5 == 0) {
                double fr = (ticks - cycleStartTick) / (double) (sc.i("mc.evolve.cycleSeconds") * 20);
                bar.setName(Component.literal("Emergent City: evolving - " + evolveStats()));
                bar.setProgress((float) Geom.clamp(fr, 0, 1));
            }
            return;
        } else if (stage == Stage.STOPPING) {
            if (analysis != null && analysis.isDone()) analysis = null; // a review in progress is dropped
            if (busy(Role.MASON) == 0 && analysis == null) {
                stage = Stage.DONE;
                releaseAll();
                send(Component.literal("[EmergentCity] ").withStyle(ChatFormatting.GOLD).append(Component.literal(
                        "Evolution stopped after " + cycle + " reviews: " + evolveStats() + ". The town stays as it is; /emergentcity undo reverts everything.")
                        .withStyle(ChatFormatting.WHITE)));
            }
            return;
        }
        if (isEvolving()) return;
        double growth = (phase - 1 + Math.min(1.0, stepsThisPhase / (double) Math.max(1, stepsPerPhase))) / Math.max(1, phases);
        double build = tasksQueued == 0 ? 0 : tasksDone / (double) tasksQueued;
        String label = stage == Stage.FINISHING ? "masons finishing the town"
                : stage == Stage.ANALYSING ? "the town takes stock (phase " + phase + " of " + phases + ")"
                : "surveyors growing streets, phase " + phase + " of " + phases;
        updateBar(label + " - " + housesDone + " houses", 0.05 + 0.55 * Math.min(1, growth) + 0.4 * (stage == Stage.FINISHING ? build : build * growth));
    }

    // ------------------------------------------------------------------ surveyors: the growth agents

    private boolean growthOpen() {
        return (stage == Stage.GROWING && stepsThisPhase < stepsPerPhase) || (stage == Stage.EVOLVING && stepsThisPhase < evolveSteps);
    }

    private Set<GrowthEngine.Head> busyHeads() {
        Set<GrowthEngine.Head> s = new HashSet<>();
        for (Agent a : agents) if (a.head != null) s.add(a.head);
        return s;
    }

    private void surveyor(Agent a) {
        switch (a.st) {
            case IDLE -> {
                if (!growthOpen()) return;
                GrowthEngine.Head h = ge.pickFree(busyHeads());
                if (h == null) return;
                a.head = h;
                route(a, h.node.p);
                a.st = AState.WALK;
            }
            case WALK -> { if (move(a)) a.st = AState.DECIDE; }
            case DECIDE -> {
                GrowthEngine.Head h = a.head;
                if (!growthOpen() || h == null || !ge.heads.contains(h) || h.dormant) {
                    a.head = null; a.st = AState.IDLE; return;
                }
                if (System.nanoTime() > tickEnd) return; // decide next tick
                // perception: re-read the terrain around where the villager stands
                sampler.refreshPatch(m.t, wx(h.node.p.x()), wz(h.node.p.y()), sc.i("mc.live.senseRadius"), cfg.i("mc.blurRadius"), protect, touched);
                Node from = h.node;
                Edge e = ge.advance(h, phase);
                stepsThisPhase++;
                recruitSurveyors(from.p);
                if (e == null) { a.head = null; a.st = AState.IDLE; return; }
                a.laying = e;
                a.lay = vox.streetOps(e);
                e.laidWidth = e.width;
                a.layDone = 0;
                Node to = e.other(from);
                a.path.clear(); a.path.add(to.p); a.pathIdx = 0;
                a.st = AState.LAY;
            }
            case LAY -> {
                Edge e = a.laying;
                P b = a.path.get(0); // the surveyor walks from e.a (where it decided) to e.b
                boolean arrived = move(a);
                double prog = arrived ? 1 : Geom.clamp(1 - new P(a.x, a.z).dist(b) / Math.max(1e-6, e.len()), 0, 1);
                int target = a.lay.upTo(prog + 0.08); // paving follows just behind the villager's feet
                if (target > a.layDone) a.layDone += placeRange(a.lay, a.layDone, target);
                if (arrived) {
                    if (a.layDone < a.lay.n) { a.layDone += placeRange(a.lay, a.layDone, a.lay.n); if (a.layDone < a.lay.n) return; }
                    a.lay = null; a.laying = null;
                    GrowthEngine.Head h = a.head;
                    if (h != null && ge.heads.contains(h) && h.node.p.equals(b) && !h.dormant) a.st = AState.DECIDE;
                    else { a.head = null; a.st = AState.IDLE; }
                }
            }
            default -> a.st = AState.IDLE;
        }
    }

    private void recruitSurveyors(P where) {
        int max = isEvolving() ? sc.i("mc.evolve.surveyors") : sc.i("mc.live.surveyors");
        Set<GrowthEngine.Head> busy = busyHeads();
        int free = 0, idle = 0;
        for (GrowthEngine.Head h : ge.heads) if (!h.dormant && !busy.contains(h)) free++;
        for (Agent a : agents) if (a.role == Role.SURVEYOR && a.st == AState.IDLE) idle++;
        if (free > idle && count(Role.SURVEYOR) < max) spawn(Role.SURVEYOR, where);
    }

    /** Obstacle test for proposals: does a street strip from a to b cross a finished building or garden? */
    private boolean blocked(P a, P b, double half) {
        if (protect == null) return false;
        double L = a.dist(b);
        P d = b.sub(a).norm(), n = d.perpL();
        for (double s = 1.5; s <= L - 1.0; s += 1.0)
            for (double w = -half; w <= half + 1e-9; w += Math.max(0.5, half / 2)) {
                P q = a.add(d.mul(s)).add(n.mul(w));
                int i = (int) Math.round(q.x()), j = (int) Math.round(q.y());
                if (i >= 0 && j >= 0 && i < m.t.cols && j < m.t.rows && protect[j * m.t.cols + i]) return true;
            }
        return false;
    }

    // ------------------------------------------------------------------ the town takes stock between phases

    private void startAnalysis(boolean fin) {
        stage = Stage.ANALYSING;
        final int ph = phase;
        final List<Change> changes = pendingChanges;
        pendingChanges = new ArrayList<>();
        final int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        { // what stands in the world now, so squares and free monuments never land on it
            List<P[]> st = new ArrayList<>();
            for (House h : built.keySet()) if (h.q != null) st.add(h.q.clone());
            m.standing = st;
        }
        analysis = CompletableFuture.supplyAsync(() -> {
            Analysis.phaseUpdate(m, ph, ge, fin, false);
            Voxelizer vx = new Voxelizer(m, sampler, minY, maxY, nodeLevels, lampCells);
            vx.incrementalLamps = true;
            vx.placedTop = placedTop;
            vx.overhangs = overhangs;
            vx.partyNeed = partyNeed;
            vx.protect = protect;
            vx.prepare();
            List<BuildTask> tasks = new ArrayList<>();
            List<Integer> prot = new ArrayList<>();
            int groundRate = sc.i("mc.live.groundRate"), rate = sc.i("mc.live.buildRate");
            // plazas: new ones are paved (with fountain and lamps); existing ones that grew, shrank or dissolved are
            // reshaped (cells they no longer cover are restored, new cells paved)
            Set<Integer> current = new HashSet<>();
            int reshaped = 0;
            for (Plaza p : m.plazas) {
                current.add(p.node.id);
                Set<Long> now = vx.plazaCells(p), was = plazaPaved.get(p.node.id);
                if (was == null) {
                    plazaPaved.put(p.node.id, now);
                    tasks.add(new BuildTask(vx.plazaOps(p), p.node.p, "plaza", groundRate));
                } else if (!now.equals(was) && symmetricDiff(now, was) >= 4) {
                    plazaPaved.put(p.node.id, now);
                    OpList ro = vx.plazaReshapeOps(was, now, p);
                    if (ro.n > 0) { tasks.add(new BuildTask(ro, p.node.p, "plaza", groundRate)); reshaped++; }
                }
            }
            for (Integer id : new ArrayList<>(plazaPaved.keySet())) {
                if (current.contains(id)) continue;
                Set<Long> was = plazaPaved.remove(id);
                OpList ro = vx.plazaReshapeOps(was, java.util.Set.of(), null);
                if (ro.n > 0 && !was.isEmpty()) {
                    long k0 = was.iterator().next();
                    tasks.add(new BuildTask(ro, new P((int) (k0 >> 32), (int) k0), "plaza", groundRate));
                    reshaped++;
                }
            }
            // evolving town: streets taken up, and streets whose width drifted with traffic are re-laid
            int relaid = 0, unpaved = 0;
            if (evolve && ph > phases) {
                synchronized (removedEdges) {
                    for (Object[] re : removedEdges) {
                        Edge e = (Edge) re[0];
                        OpList uo = vx.unpaveOps(e, (Boolean) re[1], (Boolean) re[2]);
                        if (uo.n > 0) { tasks.add(new BuildTask(uo, e.mid(), "street", groundRate)); unpaved++; }
                    }
                    removedEdges.clear();
                }
                List<Edge> drift = new ArrayList<>();
                P cc = m.centers.get(0).pos;
                for (Edge e : m.g.aliveEdges())
                    if (e.laidWidth > 0 && Math.abs(e.width - e.laidWidth) >= 1.0 && e.mid().dist(cc) <= evolveRadius) drift.add(e);
                drift.sort((x, y) -> Double.compare(Math.abs(y.width - y.laidWidth), Math.abs(x.width - x.laidWidth)));
                for (Edge e : drift.subList(0, Math.min(drift.size(), sc.i("mc.evolve.relaysPerCycle")))) {
                    OpList ro = vx.relayOps(e, e.laidWidth);
                    e.laidWidth = e.width;
                    if (ro.n > 0) { tasks.add(new BuildTask(ro, e.mid(), "street", groundRate)); relaid++; }
                }
            }
            final int layoutChanges = reshaped + relaid + unpaved;
            int newBlocks = 0, newHouses = 0;
            for (CityBlock b : m.blocks) {
                Landmark inside = null;
                for (Landmark l : m.landmarks) if (!builtLandmarks.contains(l) && Geom.pointInPoly(Geom.centroid(l.fp), b.facePoly)) inside = l;
                if (b.locked || (b.houses.isEmpty() && inside == null)) continue;
                b.locked = true;
                if (!b.houses.isEmpty()) b.style = b.houses.get(0).style;
                b.lockAnchor = !b.houses.isEmpty() ? b.houses.get(0).c : Geom.centroid(inside.fp);
                b.lockArea = b.faceArea;
                m.lockedBlocks.add(b);
                newBlocks++;
                P site = b.houses.isEmpty() ? Geom.centroid(inside.fp) : b.houses.get(b.houses.size() / 2).c;
                tasks.add(new BuildTask(vx.groundOps(b), site, "block", groundRate));
                List<House> order = new ArrayList<>(b.houses);
                for (House h : order) {
                    OpList ops = vx.houseOps(h);
                    if (ops.n == 0) continue;
                    ops.sortByHeight();
                    h.useFixed = true;
                    h.fixedFloors = h.floors;
                    tasks.add(new BuildTask(ops, h.frontMid().add(h.outward().mul(2.0)), "house", rate).registers(h, ops));
                    newHouses++;
                    if ("restaurant".equals(h.use)) {
                        OpList seat = vx.furnitureOps(null, h);
                        if (seat.n > 0) tasks.add(new BuildTask(seat, h.frontMid().add(h.outward().mul(3.0)), "seating", groundRate));
                    }
                }
                vx.raster(b.facePoly, (i, j) -> {
                    int k = vx.idx(i, j);
                    groundDone[k] = true;
                    if (vx.kind[k] == Voxelizer.HOUSE || vx.kind[k] == Voxelizer.GARDEN || vx.kind[k] == Voxelizer.LANDMARK
                            || vx.kind[k] == Voxelizer.PAVED) prot.add(k);
                });
            }
            for (Landmark l : m.landmarks) {
                if (builtLandmarks.contains(l)) continue;
                builtLandmarks.add(l);
                OpList ops = vx.landmarkOps(l);
                P c = Geom.centroid(l.fp);
                for (OpList q : ops.quadrants(vx.ox + (int) Math.round(c.x()), vx.oz + (int) Math.round(c.y()))) {
                    if (q.n == 0) continue;
                    q.sortByHeight();
                    tasks.add(new BuildTask(q, l.front.add(l.axisDir.mul(4)), "monument", rate * 2));
                }
                vx.raster(l.fp, (i, j) -> prot.add(vx.idx(i, j)));
            }
            // evolving town: turn this review's decisions into staged jobs that change only what differs
            int changed = 0;
            for (Change ch : changes) {
                List<OpList> newOps = new ArrayList<>();
                List<House> newHouses2 = new ArrayList<>();
                // party walls a standing neighbour leans on stay up when the house that built them goes
                java.util.HashSet<Long> leanedOn = new java.util.HashSet<>();
                java.util.Set<House> going = new java.util.HashSet<>(ch.demolish);
                going.addAll(ch.build);
                for (House h : ch.demolish) {
                    OpList old = built.get(h);
                    if (old == null) continue;
                    for (int q = 0; q < old.n; q++) {
                        long p = old.pos[q];
                        List<Voxelizer.Need> l = partyNeed.get(Voxelizer.key(BlockPos.getX(p), BlockPos.getZ(p)));
                        if (l == null) continue;
                        int y = BlockPos.getY(p);
                        for (Voxelizer.Need nd : l)
                            if (!going.contains(nd.h()) && nd.h().wallSpan != null && y >= nd.lo() && y <= nd.hi()) { leanedOn.add(p); break; }
                    }
                }
                for (House h : going) vx.forgetParty(h);
                for (House h : ch.build) {
                    OpList ops = vx.houseOps(h);
                    if (ops.n == 0) continue;
                    ops.sortByHeight();
                    newOps.add(ops);
                    newHouses2.add(h);
                }
                java.util.HashSet<Long> shared = new java.util.HashSet<>(leanedOn);
                for (House h : ch.demolish)
                    if (h.block != null)
                        for (House o : h.block.houses) {
                            if (ch.demolish.contains(o) || ch.build.contains(o)) continue;
                            OpList oo = built.get(o);
                            if (oo != null) for (int q = 0; q < oo.n; q++) shared.add(oo.pos[q]);
                        }
                OpList all = stagedChange(ch.demolish, newOps, ch.toGarden, shared::contains);
                if (all.n == 0) { busyHouses.removeAll(ch.demolish); busyHouses.removeAll(ch.build); continue; }
                BuildTask bt = new BuildTask(all, ch.site, "change:" + ch.action, rate);
                for (int q = 0; q < newHouses2.size(); q++) bt.registers(newHouses2.get(q), newOps.get(q));
                for (House h : ch.demolish) if (!ch.build.contains(h)) bt.forgets.add(h);
                bt.clearsBlock = ch.clearsBlock;
                tasks.add(bt);
                changed++;
            }
            if (fin) {
                // last phase: pave and plant whatever no block job covered, then measure the finished town
                for (OpList o : vx.leftoverOps(groundDone, 24)) {
                    long p0 = o.pos[0];
                    P site = new P(BlockPos.getX(p0) - vx.ox, BlockPos.getZ(p0) - vx.oz);
                    tasks.add(new BuildTask(o, site, "paving", groundRate));
                }
                m.metrics = Metrics.compute(m);
            }
            for (long sk : vx.seats.keySet()) prot.add(vx.idx((int) (sk >> 32), (int) sk)); // outdoor seating stays
            String summary = String.format(Locale.ROOT, "%d street segments, %d centre(s), %d plaza(s), %d monument(s); %d new block(s) with %d houses to build",
                    m.g.aliveEdges().size(), m.centers.size(), m.plazas.size(), m.landmarks.size(), newBlocks, newHouses)
                    + (changes.isEmpty() ? "" : "; " + changed + " building change(s)")
                    + (layoutChanges == 0 ? "" : "; " + layoutChanges + " street/plaza change(s)");
            return new AnalysisResult(vx, tasks, prot, summary);
        }, EXEC);
    }

    private void finishAnalysis() {
        AnalysisResult r;
        try { r = analysis.get(); } catch (Exception e) { throw new RuntimeException(e); }
        analysis = null;
        vox = r.vox();
        for (int k : r.protectCells()) protect[k] = true;
        // plazas first, then blocks and houses nearest the centre first
        List<BuildTask> sorted = new ArrayList<>(r.tasks());
        P c0 = m.centers.get(0).pos;
        sorted.sort(Comparator.comparingInt((BuildTask t) -> t.kind.equals("plaza") ? 0 : 1).thenComparingDouble(t -> t.site.dist(c0)));
        queue.addAll(sorted);
        tasksQueued += sorted.size();
        for (BuildTask t : sorted) busyHouses.addAll(t.houses);
        if (evolve && phase > phases) { // a review of an evolving town
            Analysis.spawnFeedback(m, phase, ge);
            phase++;
            ge.beginPhase(phase);
            stepsThisPhase = 0;
            cycleStartTick = ticks;
            stage = Stage.EVOLVING;
            int every = Math.max(1, sc.i("mc.evolve.reportEvery"));
            if (cycle % every == 0 && !cycleCounts.isEmpty()) {
                send(Component.literal(String.format(Locale.ROOT, "   Town review %d: ", cycle)).withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(describe(cycleCounts) + " (" + r.summary() + ")").withStyle(ChatFormatting.WHITE)));
                cycleCounts.clear();
            }
            return;
        }
        send(Component.literal(String.format(Locale.ROOT, "   Phase %d of %d: ", phase, phases)).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(r.summary()).withStyle(ChatFormatting.WHITE)));
        int maxMasons = sc.i("mc.live.masons");
        while (count(Role.MASON) < maxMasons && queue.size() > count(Role.MASON) * 3) spawn(Role.MASON, c0);
        if (phase >= phases) {
            stage = Stage.FINISHING;
            for (Agent a : agents) if (a.role == Role.SURVEYOR && a.head != null) a.head = null;
            send(CityJob.stageLine(2, "Surveyors grow streets, masons build", ChatFormatting.YELLOW,
                    String.format(Locale.ROOT, "streets complete (%.1f km); masons finishing %d jobs", m.g.totalLength() / 1000, queue.size())));
            return;
        }
        Analysis.spawnFeedback(m, phase, ge); // edits the graph, so it runs here on the server thread
        phase++;
        ge.beginPhase(phase);
        stepsThisPhase = 0;
        stage = Stage.GROWING;
    }

    // ------------------------------------------------------------------ evolving town

    /** Positions a building placed outside its own footprint (cornices, balconies over the street). */
    private void rememberOverhangs(House h, OpList ops) {
        if (h.builtCells == null) return;
        HashSet<Long> cols = new HashSet<>();
        for (int[] c : h.builtCells) cols.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL));
        int airId = Block.getId(Palette.s(Blocks.AIR));
        for (int i = 0; i < ops.n; i++) {
            long p = ops.pos[i];
            if (ops.st[i] == airId || BlockPos.getY(p) <= h.builtBase) continue;
            if (!cols.contains(((long) BlockPos.getX(p) << 32) ^ (BlockPos.getZ(p) & 0xffffffffL))) overhangs.add(p);
        }
    }

    private void forgetOverhangs(House h) {
        OpList old = built.get(h);
        if (old == null || h.builtCells == null) return;
        for (int i = 0; i < old.n; i++) overhangs.remove(old.pos[i]);
    }

    static int symmetricDiff(Set<Long> a, Set<Long> b) {
        int d = 0;
        for (long x : a) if (!b.contains(x)) d++;
        for (long x : b) if (!a.contains(x)) d++;
        return d;
    }

    /**
     * A building change applied in stages, touching only what differs: positions the old building(s) had that the new
     * ones do not are removed top-down (the floor of a cleared lot becomes grass); positions whose block changes are
     * set bottom-up; everything identical (walls of a storey that stays, the facade when only the interior changes) is
     * left standing. Ground below the old floor and blocks shared with neighbours are never removed.
     */
    OpList stagedChange(List<House> demolish, List<OpList> newOps, boolean toGarden, java.util.function.LongPredicate shared) {
        java.util.LinkedHashMap<Long, Integer> oldState = new java.util.LinkedHashMap<>();
        Map<Long, Integer> floorOf = new HashMap<>();
        for (House h : demolish) {
            OpList old = built.get(h);
            if (old == null) continue;
            int base = builtBase.getOrDefault(h, h.builtBase);
            for (int i = 0; i < old.n; i++) {
                if (BlockPos.getY(old.pos[i]) < base) continue;
                oldState.put(old.pos[i], old.st[i]);
                floorOf.put(old.pos[i], base);
            }
        }
        java.util.LinkedHashMap<Long, Integer> newState = new java.util.LinkedHashMap<>();
        for (OpList o : newOps) for (int i = 0; i < o.n; i++) { newState.remove(o.pos[i]); newState.put(o.pos[i], o.st[i]); }
        OpList out = new OpList();
        int airId = Block.getId(Palette.s(Blocks.AIR)), grassId = Block.getId(Palette.s(Blocks.GRASS_BLOCK));
        List<Long> removals = new ArrayList<>();
        for (var e : oldState.entrySet()) {
            if (newState.containsKey(e.getKey()) || e.getValue() == airId || shared.test(e.getKey())) continue;
            removals.add(e.getKey());
        }
        removals.sort((a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a))); // top down
        for (long p : removals) {
            boolean floor = BlockPos.getY(p) == floorOf.get(p);
            out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), Block.stateById(floor && toGarden ? grassId : airId));
        }
        for (var e : newState.entrySet()) { // already bottom-up (house ops are sorted by height)
            Integer was = oldState.get(e.getKey());
            if (was != null && was.intValue() == e.getValue()) continue;
            long p = e.getKey();
            out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), Block.stateById(e.getValue()));
        }
        return out;
    }

    /**
     * Hands the finished town over to a small crew that keeps working: the first few surveyors and masons stay on
     * the job (fresh = spawn a new crew, when resuming a town finished earlier); everyone else lives as townsfolk.
     */
    void startEvolving(boolean fresh) {
        evolve = true;
        stage = Stage.EVOLVING;
        int sampled = Math.min(sampler.cols, sampler.rows) / 2 - cfg.i("mc.margin");
        if (evolveRadius > sampled) {
            send(Component.literal("[EmergentCity] Evolution radius limited to " + sampled + " (the area read when the town was founded).").withStyle(ChatFormatting.GRAY));
            evolveRadius = sampled;
        }
        ge.maxRadius = evolveRadius;
        cycle = 0;
        phase = Math.max(phase, phases) + 1;
        ge.beginPhase(phase);
        stepsThisPhase = 0;
        evolveSteps = sc.i("mc.evolve.growthSteps");
        cycleStartTick = ticks;
        evolveStartTick = ticks;
        int keepS = sc.i("mc.evolve.surveyors"), keepM = sc.i("mc.evolve.masons"), ks = 0, km = 0;
        List<Agent> crew = new ArrayList<>();
        if (!fresh)
            for (Agent a : agents) {
                if (a.v == null || a.v.isRemoved()) continue;
                boolean keep = a.role == Role.SURVEYOR ? ks++ < keepS : km++ < keepM;
                if (!keep) continue;
                a.v.setNoAi(true);
                a.v.setInvulnerable(true);
                a.v.setCustomName(Component.literal(a.role == Role.SURVEYOR ? "Surveyor" : "Mason"));
                a.st = AState.IDLE; a.head = null; a.task = null;
                crew.add(a);
            }
        agents.clear();
        agents.addAll(crew);
        P c0 = m.centers.get(0).pos;
        while (count(Role.SURVEYOR) < keepS) spawn(Role.SURVEYOR, c0);
        while (count(Role.MASON) < keepM) spawn(Role.MASON, c0);
        ServerPlayer p = source.getPlayer();
        if (p != null) bar.addPlayer(p);
        int maxMin = sc.i("mc.evolve.maxMinutes");
        send(CityJob.stageLine(4, "Keep evolving", ChatFormatting.YELLOW, String.format(Locale.ROOT,
                "%d surveyors and %d masons stay on within radius %d; town review every %d s%s. /emergentcity stop ends it.", keepS, keepM,
                evolveRadius, sc.i("mc.evolve.cycleSeconds"), maxMin > 0 ? " for " + maxMin + " min" : "")));
    }

    /** A town review: decide a few building changes, then re-analyse the town (in the background) and queue the work. */
    private void startEvolveCycle() {
        cycle++;
        // blocks whose old houses are all gone are re-planned now: unlocked (fresh plots at this analysis) and, when large
        // enough, cut by a new street - the layout itself changes
        for (CityBlock b : new ArrayList<>(cleared)) {
            cleared.remove(b);
            b.locked = false;
            m.lockedBlocks.remove(b);
            if (b.facePoly != null) {
                vox.raster(b.facePoly, (i, j) -> { int k = vox.idx(i, j); protect[k] = false; groundDone[k] = false; });
                if (b.face != null && b.faceArea > 0.45 * sc.d("block.maxArea")) {
                    boolean alive = true;
                    for (Edge e : b.face.edges()) if (!e.alive) alive = false;
                    if (alive) { ge.spawnInfill(b.face, phase); evolveCounts.merge("new street", 1, Integer::sum); cycleCounts.merge("new street", 1, Integer::sum); }
                }
            }
        }
        pendingChanges = selectChanges();
        for (Change ch : pendingChanges) { busyHouses.addAll(ch.demolish); busyHouses.addAll(ch.build); }
        removeStreets();
        startAnalysis(false);
    }

    /**
     * Streets that have lost their purpose are taken up: dead-end stubs nothing faces, and low-traffic streets between
     * blocks with no houses (which merges them into one block that can be re-planned). At most one per review.
     */
    private void removeStreets() {
        if (cycle % 2 != 0) return;
        P cc = m.centers.get(0).pos;
        Set<Integer> housedFaceEdges = new HashSet<>();
        for (CityBlock b : m.blocks) if (!b.houses.isEmpty() || clearing.contains(b)) for (Edge e : b.face.edges()) housedFaceEdges.add(e.id);
        Set<Node> busyNodes = new HashSet<>();
        for (Agent a : agents) if (a.head != null) busyNodes.add(a.head.node);
        for (Edge e : m.g.aliveEdges()) {
            if (e.seedRoad || e.axis || e.bridge || e.demand > 0.25 || e.mid().dist(cc) > evolveRadius) continue;
            if (busyNodes.contains(e.a) || busyNodes.contains(e.b) || housedFaceEdges.contains(e.id)) continue;
            boolean deadA = e.a.deg() == 1, deadB = e.b.deg() == 1;
            boolean frontage = false;
            for (CityBlock b : m.blocks) for (House h : b.houses) if (Geom.distPointSeg(h.c, e.a.p, e.b.p) < e.width / 2 + h.depth + 2) { frontage = true; break; }
            if (frontage) continue;
            if (!(deadA || deadB)) {
                // an interior street: only if it separates two faces without houses
                if (e.a.deg() < 3 || e.b.deg() < 3) continue;
            }
            for (Plaza p : m.plazas) if (p.node == e.a || p.node == e.b) frontage = true;
            for (Center ce : m.centers) if (ce.node == e.a || ce.node == e.b) frontage = true;
            if (frontage) continue;
            ge.heads.removeIf(hd -> hd.node == e.a && deadA || hd.node == e.b && deadB);
            m.g.removeEdge(e);
            synchronized (removedEdges) { removedEdges.add(new Object[]{e, deadA, deadB}); }
            evolveCounts.merge("street removed", 1, Integer::sum);
            cycleCounts.merge("street removed", 1, Integer::sum);
            return;
        }
    }

    static final String[] ACTIONS = {"raise", "renew", "convert", "merge", "split", "clear", "redevelop"};

    /** Insane evolution: villagers ignore the storey cap and keep building taller. */
    boolean insane;

    /** The typical storey count of a block's houses along its streets (corners left out). */
    private static int rowFloors(CityBlock b) {
        if (b == null) return 99;
        List<Integer> fl = new ArrayList<>();
        for (House h : b.houses) if (!h.corner) fl.add(h.floors);
        if (fl.isEmpty()) return 99;
        java.util.Collections.sort(fl);
        return fl.get(fl.size() / 2);
    }

    private double[] weights() {
        double[] w = {3, 2.5, 2, 1.5, 1, 1.2, 0.6};
        for (String part : sc.s("mc.evolve.weights").split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(kv[0].trim())) w[i] = Double.parseDouble(kv[1].trim());
        }
        if (insane) w[0] *= 6; // insane evolution: mostly building upwards
        return w;
    }

    private static boolean near(P a, P b) { return a.dist(b) < 0.35; }

    /**
     * Chooses this review's building changes inside the evolution radius, guided by the town's own analysis:
     * - raise: an important house gains one storey (staged: only the roof and the new storey change), within the caps;
     * - renew: an old house gets a new facade (only the front changes);
     * - convert: a building changes use (home to shop or restaurant on busier streets, or back to home);
     * - merge / split: two narrow neighbours become one house, or a wide house becomes two;
     * - clear: a modest house is taken down and its lot becomes a garden, built on again a few reviews later;
     * - redevelop: an old, low-value block is cleared; at the next review it is re-planned with fresh plots (and a new
     *   street through it when large enough).
     */
    private List<Change> selectChanges() {
        List<Change> out = new ArrayList<>();
        java.util.SplittableRandom r = new java.util.SplittableRandom(seed * 0x9E3779B97F4A7C15L + cycle * 7919L);
        double fmin = sc.d("block.houseFrontageMin"), fmax = sc.d("block.houseFrontageMax");
        int maxFloors = sc.i("arch.maxFloors"), minAge = sc.i("mc.evolve.minAgeCycles");
        double maxHF = sc.d("arch.maxHeightToFrontage") * sc.d("arch.heightScale"), fh = sc.d("arch.floorHeight"), gx = sc.d("arch.groundFloorExtra");
        P cc = m.centers.get(0).pos;
        List<House> pool = new ArrayList<>();
        for (CityBlock b : m.blocks) {
            if (clearing.contains(b)) continue;
            for (House h : b.houses) if (built.containsKey(h) && !busyHouses.contains(h) && h.c.dist(cc) <= evolveRadius) pool.add(h);
        }
        Set<House> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // cleared lots whose time has come are rebuilt first
        for (CityBlock b : m.blocks)
            for (House v : new ArrayList<>(b.vacant))
                if (cycle - v.vacantSince >= sc.i("mc.evolve.vacantCycles") && out.size() < 2) {
                    b.vacant.remove(v);
                    b.houses.add(v);
                    v.variant++;
                    v.fixedFloors = 0;
                    v.useFixed = false; // a new building takes the use that suits its street now
                    Change ch = new Change("rebuild");
                    ch.build.add(v);
                    ch.site = v.frontMid().add(v.outward().mul(2));
                    out.add(ch);
                }
        if (pool.isEmpty()) return out;
        // a shop or restaurant left on a street that is no longer a main street becomes a home again
        int demoted = 0;
        for (House h : pool) {
            if (demoted >= 2 || "residential".equals(h.use) || Architecture.onMainStreet(h, sc)) continue;
            h.use = "residential";
            Change ch = new Change("convert");
            ch.demolish.add(h); ch.build.add(h);
            ch.site = h.frontMid().add(h.outward().mul(2));
            out.add(ch);
            used.add(h);
            demoted++;
        }
        double[] w = weights();
        double wsum = 0;
        for (double x : w) wsum += x;
        int want = sc.i("mc.evolve.changesPerCycle") * (insane ? 2 : 1);
        boolean redevelopedThisCycle = false;
        for (int k = 0, tries = 0; k < want && tries < want * 8; tries++) {
            double u = r.nextDouble() * wsum;
            int act = 0;
            while (act < w.length - 1 && u > w[act]) { u -= w[act]; act++; }
            List<House> sample = new ArrayList<>();
            for (int i = 0; i < 14; i++) { House h = pool.get(r.nextInt(pool.size())); if (!used.contains(h)) sample.add(h); }
            if (sample.isEmpty()) continue;
            Change ch = null;
            switch (ACTIONS[act]) {
                case "raise" -> {
                    House best = null;
                    for (House h : sample) {
                        if (insane) {
                            // no storey cap: only the top of the world stops them
                            if (h.builtTop + 10 >= level.getMaxBuildHeight()) continue;
                            // the tallest, most important buildings keep climbing, so towers rise out of the town
                            if (best == null || h.floors + 3 * h.importance > best.floors + 3 * best.importance) best = h;
                            continue;
                        }
                        int cap = Math.min(maxFloors, (int) Math.floor((maxHF * h.frontage - gx) / fh));
                        // keep each block's even cornice line: corners may rise one storey above the row, other houses
                        // only catch up to it
                        int row = rowFloors(h.block);
                        cap = Math.min(cap, h.corner ? row + 1 : row);
                        if (h.floors < cap && (best == null || h.importance > best.importance)) best = h;
                    }
                    if (best != null) {
                        best.fixedFloors = best.floors + (insane ? 1 + r.nextInt(2) : 1);
                        ch = new Change("raise"); ch.demolish.add(best); ch.build.add(best);
                    }
                }
                case "renew" -> {
                    House best = null;
                    for (House h : sample)
                        if (cycle - builtCycle.getOrDefault(h, cycle) >= minAge && (best == null || builtCycle.get(h) < builtCycle.get(best))) best = h;
                    if (best != null) { best.variant++; ch = new Change("renew"); ch.demolish.add(best); ch.build.add(best); }
                }
                case "convert" -> {
                    for (House h : sample) {
                        String to = null;
                        if ("residential".equals(h.use) && h.floors >= 2 && Architecture.onMainStreet(h, sc) && !h.passage)
                            to = (h.plazaFront || h.streetCls >= 3) && r.nextDouble() < 0.35 ? "restaurant" : "mixed";
                        else if (!"residential".equals(h.use) && (h.importance < 0.35 || !Architecture.onMainStreet(h, sc))) to = "residential";
                        if (to == null) continue;
                        h.use = to;
                        ch = new Change("convert");
                        ch.demolish.add(h); ch.build.add(h);
                        break;
                    }
                }
                case "merge" -> {
                    for (House a : sample) {
                        if (a.corner || a.passage || a.frontage > fmax) continue;
                        House nb = null;
                        for (House o : a.block.houses)
                            if (o != a && !o.corner && !o.passage && near(a.q[1], o.q[0]) && near(a.q[2], o.q[3])) { nb = o; break; }
                        if (nb == null || used.contains(nb) || busyHouses.contains(nb) || !built.containsKey(nb)) continue;
                        P[] q = {a.q[0], nb.q[1], nb.q[2], a.q[3]};
                        if (q[0].dist(q[1]) > 1.6 * fmax || !Geom.validQuad(q, sc.d("house.minArea"), sc.d("house.minEdge"), sc.d("house.minAngleDeg"))) continue;
                        ch = new Change("merge");
                        ch.demolish.add(a); ch.demolish.add(nb); ch.build.add(a);
                        a.q = q; a.frontage = q[0].dist(q[1]); a.c = Geom.centroid(q); a.variant++;
                        a.fixedFloors = Math.max(a.floors, nb.floors);
                        a.block.houses.remove(nb);
                        used.add(nb);
                        ch.site = a.frontMid().add(a.outward().mul(2));
                        break;
                    }
                }
                case "split" -> {
                    for (House h : sample) {
                        if (h.corner || h.passage || h.frontage < 2 * Math.max(fmin, 6.5)) continue; // both halves stay walkable
                        P m01 = h.q[0].lerp(h.q[1], 0.5), m32 = h.q[3].lerp(h.q[2], 0.5);
                        P[] qa = {h.q[0], m01, m32, h.q[3]}, qb = {m01, h.q[1], h.q[2], m32};
                        double minA = sc.d("house.minArea"), minE = sc.d("house.minEdge"), minAng = sc.d("house.minAngleDeg");
                        if (!Geom.validQuad(qa, minA, minE, minAng) || !Geom.validQuad(qb, minA, minE, minAng)) continue;
                        House a = copyLot(h, qa), b = copyLot(h, qb);
                        int idx = h.block.houses.indexOf(h);
                        h.block.houses.set(idx, a);
                        h.block.houses.add(idx + 1, b);
                        ch = new Change("split");
                        ch.demolish.add(h); ch.build.add(a); ch.build.add(b);
                        break;
                    }
                }
                case "clear" -> {
                    House best = null;
                    for (House h : sample)
                        if (!h.corner && !h.passage && h.block.houses.size() >= 5 && (best == null || h.importance < best.importance)) best = h;
                    if (best != null) {
                        ch = new Change("clear");
                        ch.demolish.add(best);
                        ch.toGarden = true;
                        best.block.houses.remove(best);
                        best.block.vacant.add(best);
                        best.vacantSince = cycle;
                    }
                }
                case "redevelop" -> {
                    if (redevelopedThisCycle || !clearing.isEmpty() || cycle < minAge) break;
                    CityBlock best = null;
                    double bestScore = 1e18;
                    for (House h : sample) {
                        CityBlock b = h.block;
                        if (b == null || b.houses.size() < 3 || clearing.contains(b)) continue;
                        boolean ok = true;
                        double imp = 0;
                        for (House o : b.houses) {
                            if (!built.containsKey(o) || busyHouses.contains(o) || used.contains(o) || cycle - builtCycle.getOrDefault(o, cycle) < minAge) ok = false;
                            imp += o.importance;
                        }
                        for (Landmark l : m.landmarks) if (Geom.pointInPoly(Geom.centroid(l.fp), b.facePoly)) ok = false;
                        if (!ok) continue;
                        imp /= b.houses.size();
                        if (imp < bestScore) { bestScore = imp; best = b; }
                    }
                    if (best == null) break;
                    // one staged clearing job per house (so several masons share it); the block is re-planned when all finish
                    clearing.add(best);
                    clearingLeft.put(best, best.houses.size());
                    for (House h : new ArrayList<>(best.houses)) {
                        Change c2 = new Change("redevelop");
                        c2.demolish.add(h);
                        c2.clearsBlock = best;
                        c2.site = h.frontMid().add(h.outward().mul(2));
                        out.add(c2);
                        used.add(h);
                    }
                    best.houses.clear();
                    best.vacant.clear();
                    redevelopedThisCycle = true;
                    k++;
                    evolveCounts.merge("redevelop", 1, Integer::sum);
                    cycleCounts.merge("redevelop", 1, Integer::sum);
                }
                default -> { }
            }
            if (ch == null) continue;
            House h0 = ch.demolish.isEmpty() ? ch.build.get(0) : ch.demolish.get(0);
            if (ch.site == null) ch.site = h0.frontMid().add(h0.outward().mul(2));
            used.addAll(ch.demolish);
            used.addAll(ch.build);
            out.add(ch);
            k++;
        }
        for (Change ch : out) {
            if (ch.action.equals("redevelop")) continue;
            evolveCounts.merge(ch.action, 1, Integer::sum);
            cycleCounts.merge(ch.action, 1, Integer::sum);
        }
        return out;
    }

    private static House copyLot(House h, P[] q) {
        House n = new House();
        n.q = q; n.block = h.block; n.ring = h.ring; n.seq = h.seq; n.edgeIdx = h.edgeIdx; n.depth = h.depth;
        n.frontage = q[0].dist(q[1]); n.c = Geom.centroid(q); n.style = h.style; n.variant = h.variant + 1;
        n.use = h.use; n.useFixed = true;
        return n;
    }

    static String describe(Map<String, Integer> counts) {
        Map<String, String> verb = Map.ofEntries(Map.entry("raise", "raised a storey"), Map.entry("renew", "new facade"),
                Map.entry("convert", "changed use"), Map.entry("merge", "merged into one"), Map.entry("split", "split in two"),
                Map.entry("clear", "cleared for a garden"), Map.entry("rebuild", "built on a cleared lot"),
                Map.entry("redevelop", "block(s) cleared for redevelopment"), Map.entry("new street", "new street(s) through redeveloped blocks"),
                Map.entry("street removed", "street(s) taken up"));
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " " + verb.getOrDefault(k, k)));
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ maintenance: masons put back broken house blocks

    /** Opt-in: while the crew is in town, masons replace blocks of finished houses that players break. */
    boolean maintain;
    /** column (world x,z) -> the finished house standing on it */
    private final Map<Long, House> houseAt = new HashMap<>();
    /** broken positions waiting for a mason: pos -> {tick due, state id} */
    private final Map<Long, long[]> repairDue = new java.util.LinkedHashMap<>();
    private final Set<Long> repairing = new HashSet<>();
    /** positions already repaired once, and positions the player chose to remove (sneak-break); never repaired again */
    private final Set<Long> repaired = new HashSet<>(), leftRemoved = new HashSet<>();
    int repairsQueued, repairsDeclined;

    private static long col(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private void indexColumns(House h) {
        if (!maintain || h.builtCells == null) return; // only needed for block maintenance
        for (int[] c : h.builtCells) houseAt.put(col(c[0], c[1]), h);
    }

    /** Maintenance switched on for a town that was built without it: index the houses standing now. */
    void enableMaintenance() {
        maintain = true;
        houseAt.clear();
        for (House h : built.keySet()) indexColumns(h);
    }

    private void unindexColumns(House h) {
        if (h.builtCells == null) return;
        for (int[] c : h.builtCells) houseAt.remove(col(c[0], c[1]), h);
    }

    /** A player broke a block. Returns true when a repair was scheduled. */
    boolean onBlockBroken(BlockPos pos, boolean sneaking) {
        if (!maintain || stage == Stage.STOPPING || stage == Stage.DONE || stage == Stage.SAMPLING) return false;
        long p = pos.asLong();
        if (sneaking) { leftRemoved.add(p); repairDue.remove(p); return false; }
        if (leftRemoved.contains(p) || repairing.contains(p) || repairDue.containsKey(p)) return false;
        if (repaired.contains(p)) { leftRemoved.add(p); repairsDeclined++; return false; } // broken again after a repair: leave it
        House h = houseAt.get(col(pos.getX(), pos.getZ()));
        if (h == null || busyHouses.contains(h)) return false;
        OpList ops = built.get(h);
        if (ops == null) return false;
        int state = -1;
        for (int i = ops.n - 1; i >= 0; i--) if (ops.pos[i] == p) { state = ops.st[i]; break; } // later ops win
        if (state < 0 || Block.stateById(state).isAir()) return false;
        repairDue.put(p, new long[]{ticks + 20L * cfg.i("mc.maintain.delaySeconds"), state});
        return true;
    }

    /** Hand due repairs to the masons as small jobs, grouped by house. */
    private void dispatchRepairs() {
        if (repairDue.isEmpty()) return;
        Map<House, OpList> byHouse = new java.util.LinkedHashMap<>();
        var it = repairDue.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue()[0] > ticks) continue;
            it.remove();
            long p = e.getKey();
            House h = houseAt.get(col(BlockPos.getX(p), BlockPos.getZ(p)));
            if (h == null || busyHouses.contains(h) || leftRemoved.contains(p)) continue; // rebuilt or demolished meanwhile
            OpList o = byHouse.computeIfAbsent(h, k -> new OpList());
            o.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), Block.stateById((int) e.getValue()[1]));
            repairing.add(p);
        }
        for (var e : byHouse.entrySet()) {
            OpList o = e.getValue();
            long p0 = o.pos[0];
            P site = new P(BlockPos.getX(p0) - sampler.ox, BlockPos.getZ(p0) - sampler.oz);
            queue.addFirst(new BuildTask(o, site, "repair", 4));
            repairsQueued += o.n;
        }
    }

    // ------------------------------------------------------------------ masons: the builders

    private void mason(Agent a) {
        switch (a.st) {
            case IDLE -> {
                if (queue.isEmpty()) return;
                BuildTask best = null;
                double bd = 1e18;
                int look = 0;
                for (BuildTask t : queue) {
                    double d = t.site.dist(new P(a.x, a.z)) + (t.kind.equals("plaza") ? -1000 : t.kind.equals("repair") ? -2000 : 0);
                    if (d < bd) { bd = d; best = t; }
                    if (++look > 40) break;
                }
                queue.remove(best);
                a.task = best;
                a.taskDone = 0;
                route(a, best.site);
                a.st = AState.WALK;
            }
            case WALK -> { if (move(a)) a.st = AState.BUILD; }
            case BUILD -> {
                BuildTask t = a.task;
                int to = Math.min(t.ops.n, a.taskDone + t.rate);
                int done = placeRange(t.ops, a.taskDone, to);
                a.taskDone += done;
                if (ticks % 8 == 0) a.v.swing(InteractionHand.MAIN_HAND);
                if (ticks % 20 == 0) snapY(a);
                if (a.taskDone >= t.ops.n) {
                    tasksDone++;
                    if (t.kind.equals("house")) housesDone++;
                    if (t.kind.equals("repair")) for (int i = 0; i < t.ops.n; i++) { repaired.add(t.ops.pos[i]); repairing.remove(t.ops.pos[i]); }
                    for (House h : t.forgets) forgetOverhangs(h);
                    for (int i = 0; i < t.houses.size(); i++) {
                        House h = t.houses.get(i);
                        forgetOverhangs(h);
                        built.put(h, t.houseOps.get(i));
                        indexColumns(h);
                        rememberOverhangs(h, t.houseOps.get(i));
                        builtFloors.put(h, h.floors);
                        builtBase.put(h, h.builtBase);
                        builtCycle.put(h, cycle);
                        busyHouses.remove(h);
                    }
                    for (House h : t.forgets) { unindexColumns(h); built.remove(h); builtFloors.remove(h); builtCycle.remove(h); builtBase.remove(h); busyHouses.remove(h); }
                    if (t.clearsBlock != null) {
                        int left = clearingLeft.merge(t.clearsBlock, -1, Integer::sum);
                        if (left <= 0) { clearing.remove(t.clearsBlock); clearingLeft.remove(t.clearsBlock); cleared.add(t.clearsBlock); }
                    }
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, wx(t.site.x()) + 0.5, surfaceY(t.site.x(), t.site.y()) + 1.5,
                            wz(t.site.y()) + 0.5, 6, 0.6, 0.6, 0.6, 0.0);
                    a.task = null;
                    a.st = AState.IDLE;
                }
            }
            default -> a.st = AState.IDLE;
        }
    }

    // ------------------------------------------------------------------ placing blocks (with undo)

    /** Places ops [from, to) within this tick's time budget; returns how many were processed. */
    private int placeRange(OpList ops, int from, int to) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int k = from;
        for (; k < to; k++) {
            if (((k - from) & 63) == 63 && System.nanoTime() > tickEnd) break;
            long p = ops.pos[k];
            int y = BlockPos.getY(p);
            if (y < minY || y >= maxY) continue;
            mp.set(BlockPos.getX(p), y, BlockPos.getZ(p));
            BlockState target = Block.stateById(ops.st[k]);
            BlockState cur = level.getBlockState(mp);
            if (cur == target || (target.isAir() && cur.isAir())) continue;
            undo.add(p, Block.getId(cur));
            level.setBlock(mp, target, Placer.FLAGS);
            placed++;
            int ti = mp.getX() - sampler.ox, tj = mp.getZ() - sampler.oz;
            if (touched != null && ti >= 0 && tj >= 0 && ti < sampler.cols && tj < sampler.rows) {
                int tk = tj * sampler.cols + ti;
                touched[tk] = true;
                if (!target.isAir() && y > placedTop[tk]) placedTop[tk] = y;
            }
        }
        return k - from;
    }

    // ------------------------------------------------------------------ villagers: bodies, walking, routes

    int wx(double simX) { return sampler.ox + (int) Math.floor(simX + 0.5); }
    int wz(double simY) { return sampler.oz + (int) Math.floor(simY + 0.5); }

    int surfaceY(double simX, double simY) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx(simX), wz(simY)); }

    private int count(Role r) { int n = 0; for (Agent a : agents) if (a.role == r) n++; return n; }
    private int busy(Role r) { int n = 0; for (Agent a : agents) if (a.role == r && a.st != AState.IDLE) n++; return n; }

    private void spawn(Role role, P at) {
        Agent a = new Agent(role);
        a.x = at.x(); a.z = at.y();
        agents.add(a);
        respawn(a);
    }

    private void respawn(Agent a) {
        Villager v = EntityType.VILLAGER.create(level);
        if (v == null) return;
        v.setPos(wx(a.x) + 0.5, surfaceY(a.x, a.z), wz(a.z) + 0.5);
        v.setNoAi(true);
        v.setInvulnerable(true);
        v.setPersistenceRequired();
        v.setVillagerData(v.getVillagerData().setProfession(a.role == Role.SURVEYOR ? VillagerProfession.CARTOGRAPHER : VillagerProfession.MASON));
        v.setCustomName(Component.literal(a.role == Role.SURVEYOR ? "Surveyor" : "Mason"));
        v.addTag("emergentcity");
        level.addFreshEntity(v);
        undo.entities.add(v.getUUID());
        a.v = v;
    }

    private void snapY(Agent a) {
        a.v.setPos(wx(a.x) + 0.5, surfaceY(a.x, a.z), wz(a.z) + 0.5);
    }

    /** Walks one tick along the agent's path; returns true on arrival. Agents hurry on long trips. */
    private boolean move(Agent a) {
        if (a.pathIdx >= a.path.size()) return true;
        double remaining = 0;
        P cur = new P(a.x, a.z);
        for (int k = a.pathIdx; k < a.path.size(); k++) { remaining += cur.dist(a.path.get(k)); cur = a.path.get(k); }
        double step = sc.d("mc.live.walkSpeed") * Geom.clamp(1 + remaining / 60.0, 1, 3);
        while (step > 0 && a.pathIdx < a.path.size()) {
            P t = a.path.get(a.pathIdx);
            double dx = t.x() - a.x, dz = t.y() - a.z, d = Math.hypot(dx, dz);
            if (d <= step) {
                a.x = t.x(); a.z = t.y(); step -= d; a.pathIdx++;
            } else {
                a.x += dx / d * step; a.z += dz / d * step;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                a.v.setYRot(yaw); a.v.setYHeadRot(yaw); a.v.setYBodyRot(yaw);
                step = 0;
            }
        }
        a.v.setPos(sampler.ox + a.x + 0.5, surfaceY(a.x, a.z), sampler.oz + a.z + 0.5);
        return a.pathIdx >= a.path.size();
    }

    /** Route along existing streets (shortest path on the street graph); straight across for short hops. */
    private void route(Agent a, P dest) {
        a.path.clear();
        a.pathIdx = 0;
        P from = new P(a.x, a.z);
        if (from.dist(dest) < 30) { a.path.add(dest); return; }
        Node s = nearestNode(from), t = nearestNode(dest);
        if (s == null || t == null || s == t) { a.path.add(dest); return; }
        HashMap<Integer, Double> dist = new HashMap<>();
        HashMap<Integer, Node> prev = new HashMap<>();
        PriorityQueue<Object[]> pq = new PriorityQueue<>(Comparator.comparingDouble(o -> (Double) o[0]));
        dist.put(s.id, 0.0);
        pq.add(new Object[]{0.0, s});
        while (!pq.isEmpty()) {
            Object[] top = pq.poll();
            double d = (Double) top[0];
            Node u = (Node) top[1];
            if (u == t) break;
            if (d > dist.getOrDefault(u.id, Double.MAX_VALUE)) continue;
            for (Edge e : u.edges) {
                Node v = e.other(u);
                double nd = d + e.len();
                if (nd < dist.getOrDefault(v.id, Double.MAX_VALUE)) { dist.put(v.id, nd); prev.put(v.id, u); pq.add(new Object[]{nd, v}); }
            }
        }
        if (!prev.containsKey(t.id)) { a.path.add(dest); return; }
        List<P> rev = new ArrayList<>();
        for (Node n = t; n != null; n = prev.get(n.id)) rev.add(n.p);
        for (int k = rev.size() - 1; k >= 0; k--) a.path.add(rev.get(k));
        a.path.add(dest);
    }

    private Node nearestNode(P p) {
        Node best = null;
        double bd = Double.MAX_VALUE;
        for (double r = 24; r <= 400 && best == null; r *= 2)
            for (Node n : m.g.nodesNear(p, r)) { double d = n.p.dist(p); if (d < bd) { bd = d; best = n; } }
        return best;
    }

    /** Keep the chunks under working villagers loaded so they are not unloaded mid-task. */
    private void manageChunks() {
        Set<Long> want = new HashSet<>();
        for (Agent a : agents) want.add(ChunkPos.asLong(wx(a.x) >> 4, wz(a.z) >> 4));
        for (long c : want) if (!forced.contains(c)) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), true);
        for (long c : forced) if (!want.contains(c)) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
        forced.clear();
        forced.addAll(want);
    }

    private void releaseAll() {
        for (Agent a : agents) {
            if (a.v == null || a.v.isRemoved()) continue;
            a.v.setNoAi(false);
            a.v.setInvulnerable(false);
            a.v.setCustomName(null);
        }
        for (long c : forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
        forced.clear();
        bar.removeAllPlayers();
    }

    private void updateBar(String text, double frac) {
        if (ticks % 5 != 0) return;
        bar.setName(Component.literal(String.format(Locale.ROOT, "Emergent City: %s - %.0f%%", text, 100 * Geom.clamp(frac, 0, 1))));
        bar.setProgress((float) Geom.clamp(frac, 0, 1));
    }

    // ------------------------------------------------------------------ completion

    private void complete(MinecraftServer server) {
        stage = Stage.DONE;
        int townsfolk = 0;
        for (Agent a : agents) if (a.v != null && !a.v.isRemoved()) townsfolk++;
        releaseAll();
        if (m.metrics == null) m.metrics = Metrics.compute(m);
        send(CityJob.stageLine(2, "Surveyors grow streets, masons build", ChatFormatting.GREEN,
                String.format(Locale.ROOT, "%d houses, %.1f km of street", housesDone, m.g.totalLength() / 1000)));
        send(CityJob.stageLine(3, "Release the townsfolk", ChatFormatting.GREEN, townsfolk + " villagers now live here"));
        String report = writeReport(server);
        Voxelizer v = vox;
        send(CityJob.rule());
        send(Component.literal(" Town complete ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(String.format(Locale.ROOT, "in %.0f min - seed %d", (System.nanoTime() - startNanos) / 6e10, seed))
                        .withStyle(ChatFormatting.GRAY)));
        send(Component.literal(" Starts (north-west corner): ").withStyle(ChatFormatting.WHITE)
                .append(CityJob.coords(v.minX, surfaceAt(v.minX, v.minZ), v.minZ)));
        send(Component.literal(" Ends (south-east corner):   ").withStyle(ChatFormatting.WHITE)
                .append(CityJob.coords(v.maxX, surfaceAt(v.maxX, v.maxZ), v.maxZ)));
        send(Component.literal(String.format(Locale.ROOT, " Extent: x %d to %d, z %d to %d  (%d x %d blocks)",
                v.minX, v.maxX, v.minZ, v.maxZ, v.maxX - v.minX + 1, v.maxZ - v.minZ + 1)).withStyle(ChatFormatting.GRAY));
        P c0 = m.centers.get(0).pos;
        send(Component.literal(" Main square (bell): ").withStyle(ChatFormatting.WHITE)
                .append(CityJob.coords(wx(c0.x()), surfaceY(c0.x(), c0.y()), wz(c0.y()))));
        for (Landmark l : m.landmarks)
            send(Component.literal(" " + Character.toUpperCase(l.type.charAt(0)) + l.type.substring(1) + String.format(Locale.ROOT, " (%.0f m tall): ", l.height))
                    .withStyle(ChatFormatting.WHITE).append(CityJob.coords(wx(l.front.x()), surfaceY(l.front.x(), l.front.y()), wz(l.front.y()))));
        if (m.cfg.b("landmark.grand") && m.landmarks.stream().noneMatch(l -> l.grand))
            send(Component.literal(" No plaza frontage was large enough for the grand landmark; the town built an ordinary cathedral (or none) instead.").withStyle(ChatFormatting.GRAY));
        send(Component.literal(String.format(Locale.ROOT, " %,d blocks placed. Report: %s", placed, report)).withStyle(ChatFormatting.GRAY));
        send(CityJob.rule());
    }

    private int surfaceAt(int x, int z) { return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z); }

    private String writeReport(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("emergentcity");
            Files.createDirectories(dir);
            String name = String.format(Locale.ROOT, "villagecity_%d_%d_seed%d.txt", centerX, centerZ, seed);
            StringBuilder sb = new StringBuilder(m.metrics.report(m));
            sb.append(String.format(Locale.ROOT, "%nVillager mode: %d surveyors, %d masons, %d houses built, %d jobs%n",
                    count(Role.SURVEYOR), count(Role.MASON), housesDone, tasksDone));
            sb.append("\nSTRUCTURAL DECISIONS\n");
            for (String ev : m.log.events) sb.append(ev).append('\n');
            Files.writeString(dir.resolve(name), sb.toString());
            return "emergentcity/" + name;
        } catch (Exception e) {
            return "(could not write report: " + e.getMessage() + ")";
        }
    }
}
