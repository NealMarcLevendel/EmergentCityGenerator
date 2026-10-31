package com.emergentcity.plague;

import com.emergentcity.city.*;
import com.emergentcity.city.LiveCityJob.BuildTask;
import com.emergentcity.city.LiveCityJob.Change;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Plague mode: one city that goes on, with a plague now and then (after Rome, and the plague towns of Europe from the
 * Black Death to 1720). See the README's Plague mode.
 *
 * Between plagues the town evolves as usual and recovers: empty houses are taken again and ruins rebuilt, the middle
 * of town first, so outlying ruins can stand for a long time. Where a whole block has fallen in, its rubble is spread
 * and the block is rebuilt a level higher with its streets: the old paving stays underneath (here and there a window
 * shows it), the houses that still stand beside it are a step below the street, and over the centuries the town rises.
 *
 * During a plague the town stops growing. Graves fill the cemeteries; when they are full a plague pit is dug outside
 * the town and the dead carts take the dead there. The plague spreads from house to house: each struck house is shut
 * up with a red cross on the door, then stands empty; empty houses decay (roof, then walls) unless they are taken again.
 * Fires burn in the streets while it lasts. A bad plague can leave a church in ruins; while it stands in ruins the town
 * founds no new church and rebuilds it in time. A few grand ruins near the monuments are kept as antiquities. With
 * catacombs, the overfull cemeteries are emptied into them between plagues, as in Paris from 1785.
 */
public final class PlagueMode implements TownMode {
    public enum Phase { QUIET, EPIDEMIC }

    /** A house the plague has struck: 0 shut up (red cross), 1 empty, 2 decayed (roof in), 3 collapsed. old: what was built. */
    public static final class Decay {
        public int stage, since;
        public final OpList old;
        public final int base;
        public final double level;
        public final long salt;
        public boolean worn;
        /** Where the red cross hangs, or Long.MIN_VALUE. */
        public volatile long mark = Long.MIN_VALUE;

        Decay(int since, OpList old, int base, double level, long salt) { this.since = since; this.old = old; this.base = base; this.level = level; this.salt = salt; }
    }

    /** Where carted bones go: the catacombs (filled bit by bit), or one plague's pit. */
    public static final class Sink {
        public volatile OpList fill;
        public int next, tasksLeft;
        public long placed;
        final String kind;

        Sink(String kind) { this.kind = kind; }

        public boolean full() { return fill != null && next >= fill.n && tasksLeft == 0; }
    }

    /** A plague pit outside the town: dug, filled, covered over. */
    public static final class Pit {
        final OpList dig = new OpList(), cap = new OpList();
        OpList fill = new OpList();
        public int i0, j0, w, h, y;
    }

    public final LiveCityJob job;
    /** The mode is on for this town. */
    public boolean plague;
    public Phase phase = Phase.QUIET;
    public int epidemics, nextAt = -1, epidemicStart, quietSince, target, struck, housesBefore;
    /** Houses the town comes back to after a plague: part of the loss stays lost (the outskirts stay in ruins for long). */
    public int goal;
    public double severity;
    P origin;
    boolean monumentThisTime;

    public final Map<House, Decay> decay = new java.util.IdentityHashMap<>();
    public final Set<House> antiquities = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    final Set<House> struckNow = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    final Set<House> shutDone = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Ruined monuments, by the review they fell in. */
    public final Map<Landmark, Integer> ruinedLandmarks = new HashMap<>();

    public final Map<Cemetery, Set<Long>> crowded = new java.util.concurrent.ConcurrentHashMap<>();
    public final Set<Cemetery> fullCems = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public volatile int gravesDug;
    public final Sink catSink = new Sink("bones:cat");
    public Sink pitSink;
    public volatile Pit pit;
    public final List<Pit> pits = java.util.Collections.synchronizedList(new ArrayList<>());
    volatile boolean pitPending;
    boolean pitWanted;
    int owedLoads;
    final Set<Long> bonedStreet = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Layers added to each junction (node id), and the windows down to old streets: {x, floor y, z}. */
    public final Map<Integer, Integer> rise = new HashMap<>();
    /** The plague after which each junction last rose: a junction rises at most once between two plagues. */
    final Map<Integer, Integer> riseEra = new HashMap<>();
    public final List<int[]> divots = java.util.Collections.synchronizedList(new ArrayList<>());

    // work for the next analysis (server thread)
    List<House> pendingShut = new ArrayList<>(), pendingEmpty = new ArrayList<>(), pendingDecay = new ArrayList<>(),
            pendingCollapse = new ArrayList<>(), pendingWear = new ArrayList<>();
    List<Landmark> pendingRuinL = new ArrayList<>(), pendingRestoreL = new ArrayList<>();
    List<Cemetery> pendingExhume = new ArrayList<>();
    Set<Edge> pendingRelay = new HashSet<>();
    List<CityBlock> pendingGround = new ArrayList<>();
    List<int[]> pendingDivots = new ArrayList<>();
    int pendingFires;
    final List<Long> fires = java.util.Collections.synchronizedList(new ArrayList<>());

    public int reoccupied, rebuilt, raisedBlocks, monumentsRuined, monumentsRestored, housesStruck;

    public PlagueMode(LiveCityJob job) { this.job = job; }

    private Config sc() { return job.sc; }

    private void count(String what, int n) { job.evolveCounts.merge(what, n, Integer::sum); job.cycleCounts.merge(what, n, Integer::sum); }

    private long rnd(long a, long b) { return Voxelizer.hash((int) (a ^ (a >>> 32)), (int) b, (int) job.seed + 991); }

    // ------------------------------------------------------------------ TownMode

    @Override public boolean active() { return plague && phase == Phase.EPIDEMIC; }
    @Override public int cycleSeconds() { return sc().i("plague.cycleSeconds"); }
    @Override public int masons() { return sc().i("plague.masons"); }

    @Override
    public void evolveStarts(boolean fresh) {
        if (!plague) return;
        if (nextAt < 0) nextAt = job.cycle + sc().i("plague.firstAfter");
        if (job.cat == null && digCatacombs) {
            job.digCatacombs();
            job.send(Component.literal("[EmergentCity] The quarrymen dig catacombs under the town first.").withStyle(ChatFormatting.GRAY));
        }
    }

    /** The evolve command asked for catacombs to be dug. */
    public boolean digCatacombs;

    @Override
    public List<Change> review() {
        if (!plague) return null;
        int cyc = job.cycle;
        if (nextAt < 0) nextAt = cyc + sc().i("plague.firstAfter");
        int emptied = advanceDecay();
        List<Change> out = new ArrayList<>();
        if (phase == Phase.QUIET && cyc >= nextAt) startEpidemic();
        if (phase == Phase.EPIDEMIC) epidemicReview(emptied);
        else recoveryReview(out);
        for (Change ch : out) count(ch.action, 1);
        return out;
    }

    @Override
    public void taskDone(BuildTask t) {
        switch (t.kind) {
            case "shut" -> { if (t.lot != null) shutDone.add(t.lot); }
            case "bones:pit" -> { if (pitSink != null) { pitSink.tasksLeft--; pitSink.placed += t.ops.n; } else if (lastPitSink != null) { lastPitSink.tasksLeft--; lastPitSink.placed += t.ops.n; } }
            case "bones:cat" -> { catSink.tasksLeft--; catSink.placed += t.ops.n; }
            case "pit" -> { // dug: the dead carts waiting for it come now
                pitPending = false;
                for (int k = 0; k < owedLoads; k++) queueBones(pitSink, t.site, false);
                owedLoads = 0;
            }
            case "exhume:dense" -> {
                queueBones(catSink, t.site, false);
                // the carters take most of the heap down with them; a few bones are always left lying about
                if (t.pile != null && !t.pile.isEmpty()) {
                    OpList cart = new OpList();
                    BlockState air = Palette.s(Blocks.AIR);
                    for (long[] col : t.pile) {
                        if (col.length == 0) continue;
                        long p0 = col[0];
                        if (Voxelizer.hash(BlockPos.getX(p0), BlockPos.getZ(p0), 13) % 100 < sc().d("plague.pileLeft") * 100) continue;
                        for (int c = col.length - 1; c >= 0; c--) cart.add(BlockPos.getX(col[c]), BlockPos.getY(col[c]), BlockPos.getZ(col[c]), air);
                    }
                    if (cart.n > 0) job.addTask(new BuildTask(cart, t.site, "cart", sc().i("mc.live.groundRate")));
                }
            }
            case "ruin:monument" -> monumentsRuined++;
            case "restore:monument" -> monumentsRestored++;
            default -> {
                if (t.kind.equals("change:reoccupy")) reoccupied += t.houses.size();
                if (t.kind.equals("change:restore")) rebuilt += t.houses.size();
            }
        }
    }

    private Sink lastPitSink;

    @Override public void growStarts() {}

    @Override
    public String barText() {
        if (!plague) return null;
        if (phase == Phase.EPIDEMIC) {
            String where = pitSink != null && pitSink.fill != null ? String.format(Locale.ROOT, ", plague pit %d%% full", fill(pitSink))
                    : fullCems.size() >= job.m.cemeteries.size() ? ", the cemeteries are full" : "";
            return String.format(Locale.ROOT, "The plague (%d): %d of %d houses struck%s", epidemics, struck, Math.max(struck, target), where);
        }
        int lived = 0, gone = 0;
        for (House h : job.built.keySet()) if (!decay.containsKey(h)) lived++;
        for (Decay d : decay.values()) if (d.stage >= 1) gone++;
        return String.format(Locale.ROOT, "Emergent City: review %d - %d houses lived in, %d empty or in ruins, %d plague(s) so far", job.cycle, lived, gone, epidemics);
    }

    private static int fill(Sink s) { return s.fill == null || s.fill.n == 0 ? 0 : (int) Math.min(100, 100.0 * s.placed / s.fill.n); }

    @Override
    public String stats() {
        if (!plague) return null;
        int empty = 0, ruins = 0;
        for (Decay d : decay.values()) { if (d.stage == 1) empty++; else if (d.stage >= 2) ruins++; }
        int maxRise = 0;
        for (int v : rise.values()) maxRise = Math.max(maxRise, v);
        return String.format(Locale.ROOT, "plague: %d so far%s; %d houses struck in all, %d taken again, %d rebuilt; now %d empty, %d in ruins (%d kept as antiquities); "
                        + "%d block(s) rebuilt a level higher (streets up to %d higher); %d monument(s) ruined, %d restored, %d in ruins now; %d graves; %d plague pit(s)%s",
                epidemics, phase == Phase.EPIDEMIC ? " (one now)" : "", housesStruck, reoccupied, rebuilt, empty, ruins, antiquities.size(), raisedBlocks, maxRise,
                monumentsRuined, monumentsRestored, ruinedLandmarks.size(), gravesDug, pits.size(),
                job.cat != null ? String.format(Locale.ROOT, "; catacombs %d%% full", fill(catSink)) : "");
    }

    private static final Map<String, String> LABELS = Map.ofEntries(Map.entry("struck", "house(s) struck, shut up with a red cross on the door"),
            Map.entry("emptied", "house(s) left empty"), Map.entry("decayed", "empty house(s) fallen into decay"),
            Map.entry("collapsed", "ruin(s) fallen in"), Map.entry("worn", "old ruin(s) worn down"),
            Map.entry("reoccupy", "empty house(s) taken again"), Map.entry("restore", "ruin(s) rebuilt"),
            Map.entry("raised", "ruined block(s) rebuilt a level higher"), Map.entry("monument ruin", "monument(s) fallen into ruin"),
            Map.entry("monument restored", "ruined monument(s) restored"), Map.entry("exhume", "batch(es) of graves emptied into the catacombs"),
            Map.entry("dead cart", "cartload(s) of the dead taken to the plague pit"), Map.entry("antiquity", "ruin(s) kept as antiquities"));

    @Override public Map<String, String> labels() { return LABELS; }

    private void announce(String text) {
        job.send(Component.literal("☠ ").withStyle(ChatFormatting.DARK_RED).append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    // ------------------------------------------------------------------ the plague

    private int lived() {
        int n = 0;
        for (House h : job.built.keySet()) if (!decay.containsKey(h)) n++;
        return n;
    }

    private void startEpidemic() {
        phase = Phase.EPIDEMIC;
        epidemics++;
        epidemicStart = job.cycle;
        struck = 0;
        struckNow.clear();
        monumentThisTime = false;
        housesBefore = lived();
        double u = (rnd(epidemics, 1) % 10000) / 10000.0, lo = sc().d("plague.minShare"), hi = sc().d("plague.maxShare");
        severity = lo + (hi - lo) * u * u; // mostly mild, now and then a great plague
        target = Math.max(3, (int) Math.round(severity * housesBefore));
        // it comes in at the edge of town (with a traveller, by the river or the road), at a house picked by chance
        List<House> alive = aliveHouses();
        P cc = job.m.centers.get(0).pos;
        alive.sort(Comparator.comparingDouble((House h) -> -h.c.dist(cc) * (0.6 + 0.4 * (rnd(h.id, epidemics) % 1000) / 1000.0)));
        origin = alive.isEmpty() ? cc : alive.get(0).c;
        job.evolveSteps = 0; // nobody builds anything new while it lasts
        job.m.noNewCemeteries = true;
        pendingFires = 1;
        job.ensureMasons(masons());
        String how = severity >= 0.3 ? "A great plague! " : severity >= 0.15 ? "The plague is back. " : "A plague, a mild one by the look of it. ";
        announce(how + "Houses where it strikes are shut up with the household inside and a red cross on the door. Fires are lit in the "
                + "streets to clear the air, and the graves fill the cemeteries.");
    }

    private List<House> aliveHouses() {
        List<House> out = new ArrayList<>();
        for (House h : job.built.keySet())
            if (!decay.containsKey(h) && !job.busyHouses.contains(h) && !h.plazaFront) out.add(h); // the palazzi on the squares are kept by their owners
        out.sort(Comparator.comparingInt(h -> h.id));
        return out;
    }

    private void epidemicReview(int emptied) {
        int cyc = job.cycle;
        int per = (int) Math.ceil(target / (double) Math.max(1, sc().i("plague.spreadReviews")));
        if (struck < target) {
            // from house to house: the nearest to where it has been, a little by chance
            List<P> seen = new ArrayList<>();
            for (House h : struckNow) seen.add(h.c);
            if (seen.isEmpty()) seen.add(origin);
            List<House> pool = aliveHouses();
            Map<House, Double> near = new java.util.IdentityHashMap<>();
            for (House h : pool) {
                double d = 1e18;
                for (P q : seen) d = Math.min(d, h.c.dist(q));
                near.put(h, d + 18.0 * (rnd(h.id, cyc) % 1000) / 1000.0);
            }
            pool.sort(Comparator.comparingDouble(near::get));
            int n = Math.min(per, target - struck);
            for (House h : pool.subList(0, Math.min(n, pool.size()))) strike(h);
        }
        // the dead: in the cemeteries while there is room, then in a plague pit outside the town
        boolean cemsFull = job.m.cemeteries.isEmpty() || fullCems.size() >= job.m.cemeteries.size();
        if (cemsFull && emptied > 0) {
            if (pitSink == null || (pitSink.fill != null && pitSink.next >= pitSink.fill.n)) {
                if (pitSink != null) closePit();
                pitSink = new Sink("bones:pit");
                pitWanted = true;
                announce("The cemeteries are full. A plague pit is dug in the fields outside the town (as in London, 1665), and at night the "
                        + "dead carts go round: \"Bring out your dead!\"");
            }
            for (int k = 0; k < emptied; k++) queueBones(pitSink, origin, false);
            count("dead cart", emptied);
        }
        // a great plague may take a church with it: nobody is left to keep it
        if (!monumentThisTime && severity >= sc().d("plague.monumentShare") && struck >= target / 2
                && ruinedLandmarks.size() < sc().i("plague.ruinedMonumentsMax")) {
            Landmark main = job.m.centers.get(0).landmark;
            Landmark pick = null;
            double bd = 1e18;
            for (Landmark l : job.builtLandmarks) {
                if (l == main || ruinedLandmarks.containsKey(l)) continue;
                double d = l.front.dist(origin);
                if (d < bd) { bd = d; pick = l; }
            }
            if (pick != null) {
                monumentThisTime = true;
                ruinedLandmarks.put(pick, cyc);
                pendingRuinL.add(pick);
                job.m.noNewLandmarks = true;
                count("monument ruin", 1);
                announce("The " + pick.type + " has lost its priests and its parish. Nobody keeps it up: its roof falls in.");
            }
        }
        if (struck >= target && cyc - epidemicStart >= 2) endEpidemic();
    }

    private void strike(House h) {
        long hh = Voxelizer.hash(h.id, (int) job.seed, 313);
        boolean shell = (hh % 1000) < sc().d("plague.standingShare") * 1000;
        double level = shell ? 0.45 + 0.3 * ((hh >>> 12) % 1000) / 1000.0 : 0;
        Decay d = new Decay(job.cycle, job.built.get(h), job.builtBase.getOrDefault(h, h.builtBase), level, h.id * 7919L + job.seed + epidemics);
        decay.put(h, d);
        struckNow.add(h);
        job.busyHouses.add(h);
        pendingShut.add(h);
        struck++;
        housesStruck++;
        count("struck", 1);
    }

    private void endEpidemic() {
        phase = Phase.QUIET;
        quietSince = job.cycle;
        int lo = sc().i("plague.intervalMin"), hi = Math.max(lo, sc().i("plague.intervalMax"));
        nextAt = job.cycle + lo + (int) (rnd(epidemics, 7) % (hi - lo + 1));
        job.evolveSteps = sc().i("mc.evolve.growthSteps");
        job.m.noNewCemeteries = false;
        pendingFires = 2;
        if (pitSink != null) closePit();
        job.undo.compact(); // one entry per block changed, however long the town goes on
        goal = housesBefore - (int) Math.round(struck * sc().d("plague.lostShare"));
        announce(String.format(Locale.ROOT, "With the cold weather the plague burns out. It struck %d of %d houses (%d%%). "
                        + "The town goes on: empty houses will be taken again, ruins rebuilt.", struck, housesBefore,
                (int) Math.round(100.0 * struck / Math.max(1, housesBefore))));
    }

    private void closePit() {
        Pit pt = pit;
        Sink s = pitSink;
        if (pt != null && s != null && s.fill != null && s.next < s.fill.n) {
            // not full: the rest is filled in with earth before it is covered over
            OpList earth = new OpList();
            BlockState dirt = Palette.s(Blocks.COARSE_DIRT);
            for (int q = s.next; q < s.fill.n; q++) {
                long p = s.fill.pos[q];
                if (BlockPos.getY(p) <= pt.y) earth.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), dirt);
            }
            s.next = s.fill.n;
            if (earth.n > 0) job.addTask(new BuildTask(earth, new P(pt.i0 + pt.w / 2.0, pt.j0 + pt.h / 2.0), "pit:earth", sc().i("mc.live.groundRate")));
        }
        if (pt != null && pt.cap.n > 0) job.addTask(new BuildTask(pt.cap, new P(pt.i0 + pt.w / 2.0, pt.j0 + pt.h / 2.0), "pit:close", sc().i("mc.live.groundRate")));
        lastPitSink = pitSink;
        pitSink = null;
        pit = null;
    }

    /** Struck houses go on, unless they are taken again: shut up, empty, decayed, fallen in, worn down. Returns how many emptied now. */
    private int advanceDecay() {
        int cyc = job.cycle, emptied = 0;
        for (var e : decay.entrySet()) {
            House h = e.getKey();
            Decay d = e.getValue();
            int age = cyc - d.since;
            if (d.stage == 0) {
                if (shutDone.contains(h) && age >= sc().i("plague.shutReviews")) { d.stage = 1; d.since = cyc; pendingEmpty.add(h); emptied++; count("emptied", 1); }
                continue;
            }
            if (job.busyHouses.contains(h)) continue; // a job in hand
            if (d.stage == 1 && age >= sc().i("plague.decayReviews")) { d.stage = 2; d.since = cyc; job.busyHouses.add(h); pendingDecay.add(h); count("decayed", 1); }
            else if (d.stage == 2 && age >= sc().i("plague.collapseReviews")) {
                d.stage = 3; d.since = cyc; job.busyHouses.add(h); pendingCollapse.add(h); count("collapsed", 1);
                // a grand ruin by a monument may be kept as it is, an antiquity
                if (d.level >= 0.55 && h.builtCells != null && h.builtCells.size() >= 60 && antiquities.size() < sc().i("plague.antiquities")) {
                    for (Landmark l : job.builtLandmarks)
                        if (l.front.dist(h.c) < 60) { antiquities.add(h); count("antiquity", 1); break; }
                }
            } else if (d.stage == 3 && !d.worn && !antiquities.contains(h) && age >= sc().i("plague.wearReviews")) {
                d.worn = true; job.busyHouses.add(h); pendingWear.add(h); count("worn", 1);
            }
        }
        return emptied;
    }

    // ------------------------------------------------------------------ between plagues: the town recovers

    private void recoveryReview(List<Change> out) {
        int cyc = job.cycle;
        P cc = job.m.centers.get(0).pos;
        // ruined churches are restored before the town founds any new one
        for (var e : new ArrayList<>(ruinedLandmarks.entrySet()))
            if (cyc - e.getValue() >= sc().i("plague.restoreMonumentAfter") && pendingRestoreL.isEmpty()) {
                ruinedLandmarks.remove(e.getKey());
                pendingRestoreL.add(e.getKey());
                count("monument restored", 1);
                announce("The " + e.getKey().type + " is restored.");
                break;
            }
        job.m.noNewLandmarks = !ruinedLandmarks.isEmpty();
        // with catacombs: the overfull cemeteries emptied into them, a batch a review, until they are full
        if (job.cat != null && !catSink.full()) {
            for (Cemetery cm : job.m.cemeteries) {
                Set<Long> cs = crowded.get(cm);
                if (cs == null || cs.isEmpty() || pendingExhume.contains(cm)) continue;
                pendingExhume.add(cm);
                count("exhume", 1);
                break;
            }
        }
        // the town comes back to its goal at the full rate; after that only slowly (a house now and then)
        int budget = lived() < goal ? sc().i("plague.rebuildPerReview") : (cyc % 4 == 0 ? 1 : 0);
        // a whole block fallen in: the rubble is spread, the block and its streets are a level higher, and it is rebuilt
        CityBlock blk = null;
        double bd = 1e18;
        for (CityBlock b : job.m.blocks) {
            if (b.houses.isEmpty() || b.face == null || b.cemetery != null) continue;
            boolean all = true;
            for (House h : b.houses) {
                Decay d = decay.get(h);
                if (d == null || d.stage < 2 || job.busyHouses.contains(h) || antiquities.contains(h)) { all = false; break; }
            }
            if (!all) continue;
            boolean monument = false;
            for (Landmark l : job.m.landmarks) if (Geom.pointInPoly(Geom.centroid(java.util.Arrays.asList(l.fp)), b.facePoly)) monument = true;
            if (monument) continue;
            double d = Geom.centroid(b.facePoly).dist(cc);
            if (d < bd) { bd = d; blk = b; }
        }
        Set<House> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (blk != null && raiseBlock(blk)) {
            for (House h : blk.houses) { out.add(rebuild(h)); done.add(h); }
            count("raised", 1);
            budget -= Math.min(budget, blk.houses.size() / 2);
        }
        // the empty houses are taken again first, then the ruins rebuilt, the middle of town first: outlying ruins wait
        List<House> cand = new ArrayList<>();
        for (var e : decay.entrySet())
            if (e.getValue().stage >= 1 && !done.contains(e.getKey()) && !job.busyHouses.contains(e.getKey()) && !antiquities.contains(e.getKey())) cand.add(e.getKey());
        cand.sort(Comparator.comparingDouble((House h) -> (decay.get(h).stage == 1 ? 0 : 1000) + h.c.dist(cc) * (0.7 + 0.6 * (rnd(h.id, cyc) % 1000) / 1000.0)));
        for (House h : cand.subList(0, Math.min(budget, cand.size()))) out.add(rebuild(h));
    }

    /** A struck house taken again (empty: put back as it was) or rebuilt (a ruin: a new house on the lot). */
    private Change rebuild(House h) {
        Decay d = decay.remove(h);
        boolean ruin = d != null && d.stage >= 2;
        if (ruin) { h.variant++; h.fixedFloors = 0; h.useFixed = false; }
        Change ch = new Change(ruin ? "restore" : "reoccupy");
        ch.build.add(h);
        ch.site = h.frontMid().add(h.outward().mul(2));
        return ch;
    }

    /**
     * The rubble of a fallen block spread and the block raised a level, with the streets round it: the old surface is
     * recorded as the ground (so the old paving stays underneath), the junctions at its corners go up a level, and the
     * streets meeting them are laid again. One window down to the old street is left in the longest of them.
     */
    private boolean raiseBlock(CityBlock b) {
        Voxelizer v = job.vox;
        if (v == null) return false;
        int maxRise = sc().i("plague.maxRise");
        List<Node> up = new ArrayList<>();
        for (Node n : b.face.cycle())
            if (rise.getOrDefault(n.id, 0) < maxRise && riseEra.getOrDefault(n.id, -1) != epidemics && !up.contains(n)) up.add(n);
        if (up.isEmpty()) return false;
        TerrainSampler s = job.sampler;
        Terrain t = job.m.t;
        java.util.function.IntConsumer bury = k -> {
            int floor = v.lvl[k];
            synchronized (divots) {
                for (java.util.Iterator<int[]> it = divots.iterator(); it.hasNext(); ) {
                    int[] dv = it.next();
                    if (v.idx(dv[0] - v.ox, dv[2] - v.oz) == k) { floor = dv[1]; it.remove(); } // a window covered over again
                }
            }
            if (floor > s.ground[k]) s.ground[k] = floor;
        };
        v.raster(b.facePoly, (i, j) -> {
            int k = v.idx(i, j);
            if (v.kind[k] == Voxelizer.LANDMARK || v.kind[k] == Voxelizer.NONE) return;
            bury.accept(k);
            t.h[k] += 1;
        });
        Set<Edge> edges = new HashSet<>();
        for (Node n : up) for (Edge e : n.edges) if (e.alive) edges.add(e);
        for (Edge e : edges) {
            P a = e.a.p, c = e.b.p, nrm = c.sub(a).norm().perpL();
            double w = Math.max(e.wA, e.wB) / 2 + 1;
            P[] q = {a.add(nrm.mul(w)), c.add(nrm.mul(w)), c.sub(nrm.mul(w)), a.sub(nrm.mul(w))};
            v.raster(q, (i, j) -> { int k = v.idx(i, j); if (v.isStreetLike(v.kind[k])) bury.accept(k); });
        }
        for (Node n : up) {
            double r = 2;
            for (Edge e : n.edges) r = Math.max(r, e.widthAt(n) / 2 * 1.6 + 1);
            for (int j = (int) Math.floor(n.p.y() - r); j <= Math.ceil(n.p.y() + r); j++)
                for (int i = (int) Math.floor(n.p.x() - r); i <= Math.ceil(n.p.x() + r); i++)
                    if (v.in(i, j) && v.isStreetLike(v.kind[v.idx(i, j)])) bury.accept(v.idx(i, j));
        }
        for (Node n : up) {
            job.nodeLevels.put(n.id, v.nodeLevel(n) + 1);
            rise.merge(n.id, 1, Integer::sum);
            riseEra.put(n.id, epidemics);
        }
        pendingRelay.addAll(edges);
        pendingGround.add(b);
        // a window down to the old street: two cells at the side of the longest raised street of the block, left open
        Edge best = null;
        double bl = 0;
        for (Edge e : b.face.edges()) if (up.contains(e.a) && up.contains(e.b) && e.a.p.dist(e.b.p) > bl) { bl = e.a.p.dist(e.b.p); best = e; }
        if (best != null && bl >= 14) {
            P a = best.a.p, c = best.b.p, u = c.sub(a).norm(), nrm = u.perpL(), mid = a.lerp(c, 0.5);
            double off = Math.max(1, best.width / 2 - 1.2) * (rnd(best.id, job.cycle) % 2 == 0 ? 1 : -1);
            for (double along : new double[]{-0.5, 0.5}) {
                P q = mid.add(u.mul(along)).add(nrm.mul(off));
                int i = (int) Math.round(q.x()), j = (int) Math.round(q.y());
                if (!v.in(i, j)) continue;
                int k = v.idx(i, j);
                if (v.kind[k] != Voxelizer.STREET) continue;
                boolean lamp = job.lampCells.containsKey(((long) i << 32) | (j & 0xffffffffL));
                if (lamp) continue;
                int[] dv = {v.ox + i, v.lvl[k], v.oz + j, best.id};
                pendingDivots.add(dv);
            }
        }
        raisedBlocks++;
        return true;
    }

    // ------------------------------------------------------------------ the work, in each analysis

    @Override
    public TownMode.Work prepareAnalysis() {
        if (!plague) return null;
        final boolean crowd = phase == Phase.EPIDEMIC;
        final boolean wantPit = pitWanted;
        pitWanted = false;
        final Sink pitTo = pitSink;
        final int pitArea = (int) Geom.clamp(target * 1.2, 30, 320);
        final boolean wantCatFill = job.cat != null && catSink.fill == null && !pendingExhume.isEmpty();
        final List<House> shutNow = pendingShut, emptyNow = pendingEmpty, decayNow = pendingDecay, collapseNow = pendingCollapse, wearNow = pendingWear;
        pendingShut = new ArrayList<>(); pendingEmpty = new ArrayList<>(); pendingDecay = new ArrayList<>(); pendingCollapse = new ArrayList<>(); pendingWear = new ArrayList<>();
        final List<Landmark> ruinL = pendingRuinL, restoreL = pendingRestoreL;
        pendingRuinL = new ArrayList<>(); pendingRestoreL = new ArrayList<>();
        final List<Cemetery> exCems = pendingExhume;
        pendingExhume = new ArrayList<>();
        final List<Edge> relay = new ArrayList<>(pendingRelay);
        pendingRelay = new HashSet<>();
        final List<CityBlock> ground = pendingGround;
        pendingGround = new ArrayList<>();
        final List<int[]> newDivots = pendingDivots;
        pendingDivots = new ArrayList<>();
        final int firesNow = pendingFires;
        pendingFires = 0;
        // what the jobs need to know about the houses (copied here: masons change these maps while the analysis runs)
        final Map<House, Decay> dsnap = new java.util.IdentityHashMap<>();
        final Map<House, OpList> nbOps = new java.util.IdentityHashMap<>();
        final Set<House> standing = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<House> l : List.of(shutNow, emptyNow, decayNow, collapseNow, wearNow))
            for (House h : l) {
                dsnap.put(h, decay.get(h));
                if (h.block != null)
                    for (House o : h.block.houses) {
                        OpList oo = job.built.get(o);
                        Decay od = decay.get(o);
                        if (oo != null && (od == null || od.stage <= 1)) standing.add(o); // whole houses hold their party walls up
                        if (oo == null && od != null) oo = od.old;
                        if (oo != null) nbOps.putIfAbsent(o, oo);
                    }
            }
        final Set<House> antiq = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        antiq.addAll(antiquities);
        if (shutNow.isEmpty() && emptyNow.isEmpty() && decayNow.isEmpty() && collapseNow.isEmpty() && wearNow.isEmpty() && ruinL.isEmpty() && restoreL.isEmpty()
                && exCems.isEmpty() && relay.isEmpty() && ground.isEmpty() && firesNow == 0 && !crowd && !wantPit && !wantCatFill) return null;
        return (vx, tasks) -> {
            final int groundRate = sc().i("mc.live.groundRate"), rate = sc().i("mc.live.buildRate");
            BlockState air = Palette.s(Blocks.AIR);
            if (wantCatFill) catSink.fill = job.cat.boneFill();
            if (wantPit && pitTo != null) {
                P near = job.m.centers.get(0).pos;
                double big = -1;
                for (Cemetery cm : job.m.cemeteries) if (cm.area > big) { big = cm.area; near = cm.center(); }
                Pit pt = pitPlan(vx, null, near, pitArea);
                if (pt != null) {
                    pit = pt;
                    pits.add(pt);
                    pitPending = true;
                    pitTo.fill = pt.fill;
                    tasks.add(new BuildTask(pt.dig, new P(pt.i0 + pt.w / 2.0, pt.j0 + pt.h / 2.0), "pit", groundRate));
                } else pitTo.fill = new OpList(); // nowhere to dig one: the carts go further out
            }
            // graves squeezed into the cemeteries
            if (crowd) {
                int per = sc().i("plague.gravesPerReview");
                for (Cemetery cm : job.m.cemeteries) {
                    if (fullCems.contains(cm)) continue;
                    Set<Long> taken = crowded.computeIfAbsent(cm, k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
                    int[] cr = new int[2];
                    OpList go = PlagueOps.crowdOps(vx, cm, taken, per, cr);
                    if (cr[1] == 0) fullCems.add(cm);
                    gravesDug += cr[0];
                    if (go.n > 0) tasks.add(new BuildTask(go, cm.center(), "graves", groundRate));
                }
            }
            // shut up with the household inside: a red cross over the door (a red banner hung on the lintel)
            for (House h : shutNow) {
                Decay d = dsnap.get(h);
                int[] at = d == null || d.old == null ? null : crossOver(d.old, h);
                if (at == null) { shutDone.add(h); continue; }
                d.mark = BlockPos.asLong(at[0], at[1], at[2]);
                OpList so = new OpList();
                so.add(at[0], at[1], at[2], Catacombs.st("minecraft:red_wall_banner[facing=" + new String[]{"north", "east", "south", "west"}[at[3]] + "]"));
                BuildTask bt = new BuildTask(so, h.frontMid().add(h.outward().mul(2.0)), "shut", groundRate);
                bt.lot = h;
                tasks.add(bt);
            }
            // the fires lit in the streets to clear the air (London, September 1665), and their embers afterwards
            if (firesNow == 1) {
                OpList fo = new OpList();
                BlockState fire = Catacombs.st("minecraft:campfire[lit=true]");
                List<int[]> spots = new ArrayList<>();
                int n = (int) Geom.clamp(housesBefore / 30.0, 3, 16);
                List<Integer> cand = new ArrayList<>();
                for (int k = 0; k < vx.kind.length; k++) {
                    if (vx.kind[k] != Voxelizer.STREET) continue;
                    int i = k % vx.W, j = k / vx.W;
                    boolean front = false;
                    for (int[] dd : Voxelizer.DIRS) if (vx.in(i + dd[0], j + dd[1]) && vx.kind[vx.idx(i + dd[0], j + dd[1])] == Voxelizer.HOUSE) front = true;
                    if (front && !job.lampCells.containsKey(((long) i << 32) | (j & 0xffffffffL))) cand.add(k);
                }
                cand.sort(Comparator.comparingLong(k -> Voxelizer.hash(k % vx.W, k / vx.W, (int) job.seed + 4242 + epidemics)));
                for (int k : cand) {
                    if (spots.size() >= n) break;
                    int i = k % vx.W, j = k / vx.W;
                    boolean close = false;
                    for (int[] sp : spots) if (Math.hypot(sp[0] - i, sp[1] - j) < 22) close = true;
                    if (close) continue;
                    spots.add(new int[]{i, j});
                    fo.add(vx.ox + i, vx.lvl[k] + 1, vx.oz + j, fire);
                    fires.add(BlockPos.asLong(vx.ox + i, vx.lvl[k] + 1, vx.oz + j));
                }
                if (fo.n > 0) tasks.add(new BuildTask(fo, job.m.centers.get(0).pos, "fires", groundRate));
            } else if (firesNow == 2) { // put out and cleared away
                OpList fo = new OpList();
                synchronized (fires) { for (long fp : fires) fo.add(BlockPos.getX(fp), BlockPos.getY(fp), BlockPos.getZ(fp), air); fires.clear(); }
                if (fo.n > 0) tasks.add(new BuildTask(fo, job.m.centers.get(0).pos, "fires", groundRate));
            }
            // empty: the cross taken down, doors and glass gone
            for (House h : emptyNow) {
                Decay d = dsnap.get(h);
                if (d == null || d.old == null) continue;
                OpList eo = emptyOps(d.old);
                if (d.mark != Long.MIN_VALUE) eo.add(BlockPos.getX(d.mark), BlockPos.getY(d.mark), BlockPos.getZ(d.mark), air);
                BuildTask bt = new BuildTask(eo, h.frontMid().add(h.outward().mul(2.0)), "empty", rate * 2);
                bt.lot = h;
                bt.forgets.add(h);
                tasks.add(bt);
            }
            // decayed (roof fallen in) and collapsed (to stumps, or a shell): walls a whole neighbour stands on stay
            for (List<House> l : List.of(decayNow, collapseNow))
                for (House h : l) {
                    Decay d = dsnap.get(h);
                    if (d == null || d.old == null) continue;
                    java.util.HashSet<Long> keep = new java.util.HashSet<>();
                    if (h.block != null)
                        for (House o : h.block.houses) {
                            if (o == h || !standing.contains(o)) continue;
                            OpList oo = nbOps.get(o);
                            if (oo != null) for (int q = 0; q < oo.n; q++) keep.add(oo.pos[q]);
                        }
                    boolean decayed = l == decayNow;
                    OpList ro = ruin(d.old, d.base, decayed ? 0.8 : d.level, keep::contains, d.salt, decayed ? 0 : sc().d("plague.ruinBones"));
                    BuildTask bt = new BuildTask(ro, h.frontMid().add(h.outward().mul(2.0)), decayed ? "decay" : "collapse", rate * 2);
                    bt.lot = h;
                    bt.forgets.add(h);
                    tasks.add(bt);
                }
            // old ruins worn down to stumps and grassed over
            for (House h : wearNow) {
                Decay d = dsnap.get(h);
                if (d == null || d.old == null) continue;
                java.util.HashSet<Long> keep = new java.util.HashSet<>();
                if (h.block != null)
                    for (House o : h.block.houses) {
                        if (o == h || !(standing.contains(o) || antiq.contains(o))) continue;
                        OpList oo = nbOps.get(o);
                        if (oo != null) for (int q = 0; q < oo.n; q++) keep.add(oo.pos[q]);
                    }
                BuildTask bt = new BuildTask(wear(d.old, d.base, keep::contains, d.salt), h.frontMid().add(h.outward().mul(2.0)), "wear", rate * 2);
                bt.lot = h;
                bt.forgets.add(h);
                tasks.add(bt);
            }
            // the overfull cemeteries emptied into the catacombs: a batch of graves dug up, the bones heaped by the gate for the carters
            for (Cemetery cm : exCems) {
                Set<Long> all = crowded.getOrDefault(cm, Set.of());
                List<Long> cells = new ArrayList<>();
                for (long k2 : all) { if (cells.size() >= 80) break; cells.add(k2); }
                all.removeAll(cells);
                fullCems.remove(cm);
                OpList eo = PlagueOps.uncrowdOps(vx, cells);
                int[] gate = vx.cemeteryGates.get(cm.id);
                P at = gate != null ? new P(gate[0], gate[1]) : cm.center();
                List<long[]> pile = new ArrayList<>();
                eo.addAll(PlagueOps.bonePileOps(vx, at, Math.max(4, Math.min(16, cells.size() / 6 + 3)), bonedStreet, pile));
                BuildTask bt = new BuildTask(eo, at, "exhume:dense", rate * 2);
                bt.graves = cells.size();
                bt.pile = pile;
                tasks.add(bt);
            }
            // monuments: roofless and broken off at about half height; or restored, put back as built
            for (Landmark l : ruinL) {
                OpList lo = vx.landmarkOps(l);
                P lc = Geom.centroid(java.util.Arrays.asList(l.fp));
                int li = (int) Math.round(lc.x()), lj = (int) Math.round(lc.y());
                int floor = vx.in(li, lj) ? vx.lvl[vx.idx(li, lj)] : vx.lvl[vx.idx((int) Math.round(l.front.x()), (int) Math.round(l.front.y()))];
                OpList ro = ruin(lo, floor, 0.3 + 0.35 * (Voxelizer.hash(l.id, (int) job.seed, 17) % 1000) / 1000.0, p -> false, l.id * 104729L + job.seed + epidemics, 0);
                tasks.add(new BuildTask(ro, l.front.add(l.axisDir.mul(4)), "ruin:monument", rate * 3));
            }
            for (Landmark l : restoreL) {
                OpList lo = vx.landmarkOps(l);
                lo.sortByHeight();
                tasks.add(new BuildTask(lo, l.front.add(l.axisDir.mul(4)), "restore:monument", rate * 2));
            }
            // a fallen block a level higher: its streets laid again (with a window down to the old street) and its ground
            for (Edge e : relay) {
                if (!e.alive) continue;
                OpList ro = vx.relayOps(e, e.laidWidth > 0 ? e.laidWidth : e.width);
                for (int[] dv : newDivots) if (dv[3] == e.id) { ro.add(dv[0], dv[1] + 1, dv[2], air); divots.add(new int[]{dv[0], dv[1], dv[2]}); }
                if (ro.n > 0) tasks.add(new BuildTask(ro, e.mid(), "street", groundRate));
            }
            for (CityBlock b : ground) tasks.add(new BuildTask(vx.groundOps(b), Geom.centroid(b.facePoly), "block", groundRate));
        };
    }

    /** An empty house: doors and glass gone (the rest stands). */
    static OpList emptyOps(OpList old) {
        java.util.LinkedHashMap<Long, Integer> state = new java.util.LinkedHashMap<>();
        for (int q = 0; q < old.n; q++) { state.remove(old.pos[q]); state.put(old.pos[q], old.st[q]); }
        OpList out = new OpList();
        BlockState air = Palette.s(Blocks.AIR);
        for (var e : state.entrySet()) {
            String n = States.name(Block.stateById(e.getValue()));
            if (n.endsWith("_door") || n.contains("glass") || n.endsWith("_trapdoor")) {
                long p = e.getKey();
                out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), air);
            }
        }
        return out;
    }

    /** Carting bones: one cartload (a share of the fill) into the catacombs or the pit, or all that is left. */
    private void queueBones(Sink s, P site, boolean all) {
        if (s == null) return;
        if (s == pitSink && pitPending) { owedLoads++; return; } // the pit is still being dug
        OpList bf = s.fill;
        if (bf == null || bf.n == 0 || s.next >= bf.n) return;
        int loads = s == catSink ? Math.max(1, sc().i("plague.cartloads")) : Math.max(1, target);
        int load = (int) Math.ceil(bf.n / (double) loads);
        int to = all ? bf.n : Math.min(bf.n, s.next + load);
        if (site == null) site = job.m.centers.get(0).pos;
        int chunk = 3000, rate = sc().i("plague.boneRate");
        for (int a = s.next; a < to; a += chunk) {
            OpList o = new OpList();
            for (int q = a; q < Math.min(to, a + chunk); q++)
                o.add(BlockPos.getX(bf.pos[q]), BlockPos.getY(bf.pos[q]), BlockPos.getZ(bf.pos[q]), Block.stateById(bf.st[q]));
            job.addTask(new BuildTask(o, site, s.kind, rate));
            s.tasksLeft++;
        }
        s.next = Math.max(s.next, to);
    }

    // ------------------------------------------------------------------ shapes

    /** The door of a house as built (its lower half nearest the front), and the red cross over it: {x, y, z, facing 0-3 (N E S W)}, or null. */
    int[] crossOver(OpList old, House h) {
        double fx = h.frontMid().x() + job.sampler.ox, fz = h.frontMid().y() + job.sampler.oz;
        java.util.Set<Long> solid = new java.util.HashSet<>();
        long door = Long.MIN_VALUE;
        double bd = 1e18;
        for (int q = 0; q < old.n; q++) {
            BlockState b = Block.stateById(old.st[q]);
            long p = old.pos[q];
            if (b.isAir()) { solid.remove(p); continue; }
            solid.add(p);
            if (!States.name(b).endsWith("_door") || !"lower".equals(States.props(b).get("half"))) continue;
            double d = Math.hypot(BlockPos.getX(p) - fx, BlockPos.getZ(p) - fz);
            if (d < bd) { bd = d; door = p; }
        }
        if (door == Long.MIN_VALUE || bd > 6) return null;
        P o = h.outward();
        int dx = Math.abs(o.x()) >= Math.abs(o.y()) ? (int) Math.signum(o.x()) : 0, dz = dx == 0 ? (int) Math.signum(o.y()) : 0;
        int x = BlockPos.getX(door), y = BlockPos.getY(door), z = BlockPos.getZ(door);
        for (int yy = y + 2; yy <= y + 4; yy++)
            if (solid.contains(BlockPos.asLong(x, yy, z)) && !solid.contains(BlockPos.asLong(x + dx, yy, z + dz)))
                return new int[]{x + dx, yy, z + dz, dz < 0 ? 0 : dx > 0 ? 1 : dz > 0 ? 2 : 3};
        return null;
    }

    private static double distToPoly(P p, List<P> poly) {
        double d = 1e18;
        for (int k = 0; k < poly.size(); k++) d = Math.min(d, Geom.distPointSeg(p, poly.get(k), poly.get((k + 1) % poly.size())));
        return Geom.pointInPoly(p, poly) ? 0 : d;
    }

    /**
     * Years later: what is left of a ruin. Everything above a stump of one or two blocks is gone, what is left is
     * weathered, and the floor is grassed over with the odd fern. Blocks shared with a ruin still standing stay.
     */
    static OpList wear(OpList old, int base, java.util.function.LongPredicate keep, long salt) {
        java.util.LinkedHashMap<Long, Integer> state = new java.util.LinkedHashMap<>();
        for (int q = 0; q < old.n; q++) { state.remove(old.pos[q]); state.put(old.pos[q], old.st[q]); }
        int airId = Block.getId(Palette.s(Blocks.AIR));
        List<Long> remove = new ArrayList<>();
        List<long[]> change = new ArrayList<>();
        java.util.Set<Long> floorAt = new java.util.HashSet<>();
        for (var e : state.entrySet()) {
            long p = e.getKey();
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            if (y < base || keep.test(p)) continue;
            int stump = base + 1 + (int) (Voxelizer.hash(x, z, (int) salt + 9) % 2);
            long h = Voxelizer.hash(x * 17 + y, z, (int) (salt >> 5)) % 100;
            if (y > stump) { remove.add(p); continue; }
            if (e.getValue() == airId) continue;
            if (y == base) { floorAt.add(BlockPos.asLong(x, 0, z)); if (h < 75) change.add(new long[]{p, h < 50 ? 3 : 4}); continue; }
            if (furnishing(States.name(Block.stateById(e.getValue())))) { remove.add(p); continue; }
            if (h < 45) change.add(new long[]{p, h < 25 ? 0 : 1});
            else if (h < 55) change.add(new long[]{p, 2});
        }
        remove.sort((a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a)));
        OpList out = new OpList();
        BlockState air = Palette.s(Blocks.AIR);
        for (long p : remove) out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), air);
        BlockState[] to = {Palette.s(Blocks.MOSSY_COBBLESTONE), Palette.s(Blocks.COBBLESTONE), Palette.s(Blocks.MOSS_BLOCK), Palette.s(Blocks.GRASS_BLOCK), Palette.s(Blocks.COARSE_DIRT)};
        for (long[] c : change) out.add(BlockPos.getX(c[0]), BlockPos.getY(c[0]), BlockPos.getZ(c[0]), to[(int) c[1]]);
        // ferns on the grassed-over floors, where nothing is left standing
        for (long[] c : change) {
            if (c[1] != 3) continue;
            int x = BlockPos.getX(c[0]), y = BlockPos.getY(c[0]), z = BlockPos.getZ(c[0]);
            Integer above = state.get(BlockPos.asLong(x, y + 1, z));
            boolean clear = above == null || above == airId || remove.contains(BlockPos.asLong(x, y + 1, z));
            if (clear && Voxelizer.hash(x, z, 4711) % 6 == 0) out.add(x, y + 1, z, Palette.s(Blocks.FERN));
        }
        return out;
    }

    private static boolean furnishing(String name) {
        for (String w : new String[]{"glass", "door", "_bed", "ladder", "lantern", "torch", "carpet", "sign", "pot", "fence", "pane", "bars", "chest",
                "barrel", "bookshelf", "crafting", "furnace", "lectern", "candle", "flower", "banner", "button", "plate", "cauldron", "anvil", "bell",
                "stairs", "slab", "leaves", "sapling", "water", "smoker", "loom", "cartography", "grindstone", "stonecutter", "composter", "campfire"})
            if (name.contains(w)) return true;
        return false;
    }

    /**
     * A house fallen into ruin: everything above a jagged line is gone (low stumps of wall for most, a tall shell with
     * gaping windows for a few), the roof, glass, doors and furnishings are gone, the stone that is left is weathered and
     * broken through in places, and rubble lies on the floor. Blocks shared with a standing neighbour stay.
     */
    static OpList ruin(OpList old, int base, double level, java.util.function.LongPredicate keep, long salt, double bones) {
        java.util.LinkedHashMap<Long, Integer> state = new java.util.LinkedHashMap<>();
        int top = base;
        for (int q = 0; q < old.n; q++) {
            state.remove(old.pos[q]);
            state.put(old.pos[q], old.st[q]);
            if (!Block.stateById(old.st[q]).isAir()) top = Math.max(top, BlockPos.getY(old.pos[q]));
        }
        boolean shell = level > 0;
        int cutBase = shell ? base + (int) Math.round((top - base) * level) : base + 1;
        Map<Long, Integer> cut = new HashMap<>();
        List<Long> remove = new ArrayList<>();
        List<long[]> change = new ArrayList<>();
        java.util.Set<Long> rubbleCols = new java.util.LinkedHashSet<>();
        int airId = Block.getId(Palette.s(Blocks.AIR));
        for (var e : state.entrySet()) {
            long p = e.getKey();
            int y = BlockPos.getY(p), x = BlockPos.getX(p), z = BlockPos.getZ(p);
            if (y < base || e.getValue() == airId || keep.test(p)) continue;
            BlockState s = Block.stateById(e.getValue());
            long ck = ((long) x << 32) ^ (z & 0xffffffffL);
            int c = cut.computeIfAbsent(ck, k -> cutBase + (int) (Voxelizer.hash(x, z, (int) salt) % (shell ? 4 : 3)) - (shell ? 1 : 0));
            long h = Voxelizer.hash(x * 31 + y, z, (int) (salt >> 3)) % 1000;
            if (y > c) { remove.add(p); if (y == base + 1) rubbleCols.add(p); continue; }
            if (y == base) continue; // the floor stays
            if (furnishing(States.name(s))) { remove.add(p); if (y == base + 1) rubbleCols.add(p); continue; }
            if (h < 70) { remove.add(p); continue; } // broken through
            if (h < 260) change.add(new long[]{p, h < 160 ? 0 : h < 220 ? 1 : 2});
        }
        remove.sort((a, b) -> Integer.compare(BlockPos.getY(b), BlockPos.getY(a))); // top down
        OpList out = new OpList();
        BlockState air = Palette.s(Blocks.AIR);
        for (long p : remove) out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), air);
        BlockState[] weathered = {Palette.s(Blocks.MOSSY_COBBLESTONE), Palette.s(Blocks.COBBLESTONE), Palette.s(Blocks.CRACKED_STONE_BRICKS)};
        for (long[] c : change) out.add(BlockPos.getX(c[0]), BlockPos.getY(c[0]), BlockPos.getZ(c[0]), weathered[(int) c[1]]);
        // rubble on the ground floor
        BlockState[] rubble = {Palette.s(Blocks.GRAVEL), Palette.s(Blocks.COBBLESTONE), Palette.s(Blocks.COBBLESTONE_SLAB), Palette.s(Blocks.MOSSY_COBBLESTONE)};
        java.util.Set<Long> heaped = new java.util.HashSet<>();
        for (long p : rubbleCols) {
            long h = Voxelizer.hash(BlockPos.getX(p), BlockPos.getZ(p), 4242) % 100;
            Integer below = state.get(BlockPos.asLong(BlockPos.getX(p), BlockPos.getY(p) - 1, BlockPos.getZ(p)));
            if (h < 22 && below != null && below != airId) { out.add(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), rubble[(int) (h % 4)]); heaped.add(p); }
        }
        // the dead left where they fell: bone gravel on the floor, the odd skull
        if (bones > 0) {
            java.util.Set<Long> gone = new java.util.HashSet<>(remove);
            for (var e : state.entrySet()) {
                long p = e.getKey();
                if (BlockPos.getY(p) != base || e.getValue() == airId || keep.test(p)) continue;
                int x = BlockPos.getX(p), z = BlockPos.getZ(p);
                long up = BlockPos.asLong(x, base + 1, z);
                Integer above = state.get(up);
                boolean open = above == null || above == airId || gone.contains(up);
                if (!open || heaped.contains(up)) continue;
                long h = Voxelizer.hash(x, z, 5150 + (int) salt) % 1000;
                if (h >= bones * 1000) continue;
                out.add(x, base + 1, z, h % 7 == 0 ? Catacombs.skulls((int) (h % 4), 1 + (int) (h % 3)) : Catacombs.st("emergentcity:bone_gravel"));
            }
        }
        return out;
    }

    /**
     * The plague pit: a trench in open, flat ground outside the town (clear of it by a few blocks, not in water, nor on
     * anything kept), as near as can be to the given place, of about the given area. Dug three deep; filled layer by
     * layer from one end with bones, skulls and femurs on top; covered over in the end with a low grassy mound and a post.
     */
    Pit pitPlan(Voxelizer vx, boolean[] keep, P near, int area) {
        int W = vx.W, H = vx.H;
        int w = Math.max(5, (int) Math.round(Math.sqrt(area / 2.0))), l = Math.max(8, 2 * w);
        int[] bad = new int[(W + 1) * (H + 1)]; // prefix sums of cells a pit may not touch
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
            int k = j * W + i;
            boolean b = vx.kind[k] != Voxelizer.NONE || vx.s.water[k] || (keep != null && keep[k]) || (job.m.keepOut != null && job.m.keepOut[k])
                    || vx.s.clearTop[k] - vx.s.ground[k] > 2 || job.placedTop[k] != Integer.MIN_VALUE;
            bad[(j + 1) * (W + 1) + i + 1] = (b ? 1 : 0) + bad[j * (W + 1) + i + 1] + bad[(j + 1) * (W + 1) + i] - bad[j * (W + 1) + i];
        }
        int m0 = 3;
        int[] best = null;
        double bd = 1e18;
        for (int o = 0; o < 2; o++) {
            int rw = o == 0 ? l : w, rh = o == 0 ? w : l;
            for (int j0 = m0 + 2; j0 + rh + m0 + 2 < H; j0 += 2) for (int i0 = m0 + 2; i0 + rw + m0 + 2 < W; i0 += 2) {
                double d = Math.hypot(i0 + rw / 2.0 - near.x(), j0 + rh / 2.0 - near.y());
                if (d >= bd) continue;
                int a0 = i0 - m0, b0 = j0 - m0, a1 = i0 + rw + m0, b1 = j0 + rh + m0;
                if (bad[b1 * (W + 1) + a1] - bad[b0 * (W + 1) + a1] - bad[b1 * (W + 1) + a0] + bad[b0 * (W + 1) + a0] != 0) continue;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int j = j0; j < j0 + rh; j++) for (int i = i0; i < i0 + rw; i++) { int g = vx.s.ground[j * W + i]; lo = Math.min(lo, g); hi = Math.max(hi, g); }
                if (hi - lo > 1) continue;
                bd = d;
                best = new int[]{i0, j0, rw, rh, lo};
            }
        }
        if (best == null) return null;
        Pit pt = new Pit();
        pt.i0 = best[0]; pt.j0 = best[1]; pt.w = best[2]; pt.h = best[3]; pt.y = best[4];
        BlockState air = Palette.s(Blocks.AIR), gravel = Catacombs.st("emergentcity:bone_gravel"), grass = Palette.s(Blocks.GRASS_BLOCK),
                dirt = Palette.s(Blocks.COARSE_DIRT);
        int y = pt.y, floor = y - 2;
        boolean alongI = pt.w >= pt.h;
        for (int j = pt.j0; j < pt.j0 + pt.h; j++) for (int i = pt.i0; i < pt.i0 + pt.w; i++) {
            int k = j * W + i, x = vx.ox + i, z = vx.oz + j;
            for (int yy = Math.max(vx.s.clearTop[k], vx.s.ground[k]); yy >= floor; yy--) pt.dig.add(x, yy, z, air);
            pt.dig.add(x, floor - 1, z, dirt); // turned earth at the bottom
        }
        // filled from one end, a layer at a time; skulls and femurs on the top layer as it goes
        int len = alongI ? pt.w : pt.h, wid = alongI ? pt.h : pt.w;
        for (int yy = floor; yy <= y; yy++) for (int a = 0; a < len; a++) for (int b = 0; b < wid; b++) {
            int i = alongI ? pt.i0 + a : pt.i0 + b, j = alongI ? pt.j0 + b : pt.j0 + a;
            int x = vx.ox + i, z = vx.oz + j;
            pt.fill.add(x, yy, z, gravel);
            if (yy == y) {
                long hh = Voxelizer.hash(i, j, 7373);
                if (hh % 5 == 0) pt.fill.add(x, y + 1, z, Catacombs.skulls((int) ((hh >>> 8) & 3), 1 + (int) ((hh >>> 10) % 3)));
                else if (hh % 5 == 1) pt.fill.add(x, y + 1, z, Catacombs.femurs((int) ((hh >>> 8) & 3), 1 + (int) ((hh >>> 12) % 2)));
            }
        }
        // covered over: earth and grass, a mound a block high in the middle, and a post at one end
        for (int j = pt.j0; j < pt.j0 + pt.h; j++) for (int i = pt.i0; i < pt.i0 + pt.w; i++) {
            int x = vx.ox + i, z = vx.oz + j;
            boolean rim = i == pt.i0 || j == pt.j0 || i == pt.i0 + pt.w - 1 || j == pt.j0 + pt.h - 1;
            pt.cap.add(x, y + 1, z, air);
            if (rim) pt.cap.add(x, y, z, grass);
            else { pt.cap.add(x, y, z, Palette.s(Blocks.DIRT)); pt.cap.add(x, y + 1, z, grass); }
        }
        int pi = alongI ? pt.i0 + 1 : pt.i0 + pt.w / 2, pj = alongI ? pt.j0 + pt.h / 2 : pt.j0 + 1;
        pt.cap.add(vx.ox + pi, y + 2, vx.oz + pj, Palette.s(Blocks.DARK_OAK_FENCE));
        pt.cap.add(vx.ox + pi, y + 3, vx.oz + pj, Palette.s(Blocks.DARK_OAK_FENCE));
        return pt;
    }
}
