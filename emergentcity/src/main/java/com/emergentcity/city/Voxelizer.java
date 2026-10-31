package com.emergentcity.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns the simulated city into block changes. Grid cell (i, j) = world column (ox + i, oz + j),
 * simulation point (i, j). "Level" = y of the walking-surface block; people stand at level + 1.
 *
 * The whole city footprint (every street-bounded face) is paved first; green space is then carved
 * into block interiors (the shared rear gardens, and a planted centre in blocks without houses).
 * Gaps left in a block's ring of houses (rejected corner plots, tiny units) are absorbed by the
 * neighbouring house so street walls stay closed.
 *
 * Order of writes (later wins): terrain cut/fill and clearing -> paving / grass -> houses (walls,
 * facade, floors, roof) -> monuments -> fountains and trees.
 */
public final class Voxelizer {
    public static final byte NONE = 0, STREET = 1, PLAZA = 2, JUNCTION = 3, HOUSE = 4, GARDEN = 5, PAVED = 6, LANDMARK = 7, CEMETERY = 8;

    public final CityModel m;
    final Terrain t;
    public final TerrainSampler s;
    final BlockPlan plan;
    public final int W, H, ox, oz;
    public final byte[] kind;
    public final int[] owner, lvl, cls;
    final Config c;
    public final java.util.Map<Integer, Integer> nodeLevel;
    /** Where block changes go: the whole-city plan (instant mode) or one build job (villager mode). */
    Sink sink;
    /** Villager mode: cells of finished buildings and gardens that later paving must not touch. */
    boolean[] protect;
    private boolean guard;
    /**
     * Street furniture cells (lamp posts, restaurant tables and chairs), cell key -> walking level. Guarded paving
     * never overwrites them; their own job places them. Shared across rebuilds in villager mode.
     */
    public final java.util.Map<Long, Integer> lamps;
    public final HashMap<Long, int[]> seats = new HashMap<>(); // cell -> {level, kind 0 table / 1 chair, facing ordinal}
    /** Villager mode: lamps are added street by street as surveyors lay them, not recomputed for the whole town. */
    boolean incrementalLamps;
    /** Courtyard door per house id: {cell i, cell j, direction ordinal from the house into the courtyard}. */
    final HashMap<Integer, int[]> rearDoor = new HashMap<>();
    int courtyardDoors, courtyardNoDoor;
    /** Villager mode: highest y the town has placed in each column (so rebuilding clears its own older work). */
    public int[] placedTop;
    /** Villager mode: world positions of standing buildings' overhangs (cornices, balconies) above streets and plazas. */
    java.util.Set<Long> overhangs;
    private boolean placingFurniture;
    private final HashMap<Integer, Integer> houseBase = new HashMap<>();
    int housesBuilt, gapCellsFilled;
    /** World-space extent of everything the city touches (filled by build). */
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
    final HashMap<Integer, List<int[]>> houseCells = new HashMap<>();
    /** Instant mode: what each house placed (for reports and checks). */
    final java.util.IdentityHashMap<House, OpList> builtOps = new java.util.IdentityHashMap<>();

    // ---- party walls and units
    /** A neighbour that relies on a cell being solid over [lo, hi] (it built no wall of its own there). */
    record Need(House h, int lo, int hi) {}
    /** World column -> neighbours relying on it. Shared across rebuilds in villager mode (set by the job). */
    java.util.Map<Long, List<Need>> partyNeed = new java.util.concurrent.ConcurrentHashMap<>();
    /** Planned {base, top} per house id (top = the row the roof starts on). */
    private final HashMap<Integer, int[]> planned = new HashMap<>();
    private final HashMap<Integer, Units.Unit> unitOf = new HashMap<>();
    /** House being built: writes into cells owned by another house are filtered, solid writes recorded. */
    private House cur;
    private boolean foreignWall;
    private HashMap<Long, BlockState> curWrites;
    private HashMap<Long, int[]> curForeign; // world column -> {lo, hi} written into a neighbour's column
    /** Procedural house being built: perimeter cells (grid key) left open because a neighbour's wall stands there. */
    private final HashMap<Long, java.util.BitSet> yieldY = new HashMap<>();
    private final HashSet<Long> fullYield = new HashSet<>();
    int unitHouses, partyCells, unitMissing;

    Voxelizer(CityModel m, TerrainSampler s, int minY, int maxY) { this(m, s, minY, maxY, new HashMap<>(), new HashMap<>()); }

    Voxelizer(CityModel m, TerrainSampler s, int minY, int maxY, java.util.Map<Integer, Integer> nodeLevels) {
        this(m, s, minY, maxY, nodeLevels, new HashMap<>());
    }

    /** nodeLevels is shared across rebuilds in villager mode so streets laid earlier keep their levels. */
    Voxelizer(CityModel m, TerrainSampler s, int minY, int maxY, java.util.Map<Integer, Integer> nodeLevels, java.util.Map<Long, Integer> lamps) {
        this.m = m; this.t = m.t; this.s = s; this.c = m.cfg; this.nodeLevel = nodeLevels; this.lamps = lamps;
        W = t.cols; H = t.rows; ox = s.ox; oz = s.oz;
        kind = new byte[W * H];
        owner = new int[W * H];
        lvl = new int[W * H];
        cls = new int[W * H];
        java.util.Arrays.fill(owner, -1);
        plan = new BlockPlan(minY, maxY);
        sink = plan;
    }

    public int idx(int i, int j) { return j * W + i; }
    public boolean in(int i, int j) { return i >= 0 && j >= 0 && i < W && j < H; }
    public void put(int i, int y, int j, BlockState st) {
        if (cur != null && in(i, j)) {
            int k = idx(i, j);
            if (kind[k] == HOUSE && owner[k] != cur.id) {
                // a neighbour's cell: only a unit's own perimeter wall may rise there, and only above the neighbour's
                // walls (where a taller unit shares the column with a lower one)
                if (!foreignWall || !solid(st)) return;
                House n = m.houses.get(owner[k]);
                int[] pn = planned.get(n.id);
                int nTop = n.wallSpan != null ? n.builtTop : pn != null ? pn[1] : Integer.MIN_VALUE;
                if (y <= nTop || covered(n, i, j, y)) return;
                curForeign.merge(wkey(i, j), new int[]{y, y}, (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            } else if (kind[k] == LANDMARK) return;
            curWrites.put(BlockPos.asLong(i, y, j), st);
        }
        if (guard && protect != null && in(i, j) && protect[idx(i, j)]) return;
        if (m.keepOut != null && in(i, j) && m.keepOut[idx(i, j)]) return; // existing structures are never touched
        if (guard && !placingFurniture && in(i, j)) {
            long ck = ((long) i << 32) | (j & 0xffffffffL);
            if (lamps.containsKey(ck) || seats.containsKey(ck)) return; // street furniture is placed by its own job
        }
        if (guard && overhangs != null && overhangs.contains(BlockPos.asLong(ox + i, y, oz + j))) return; // a building's cornice/balcony
        sink.add(ox + i, y, oz + j, st);
    }
    public void put(int i, int y, int j, Block b) { put(i, y, j, b.defaultBlockState()); }

    public static long hash(int i, int j, int salt) {
        long h = i * 0x9E3779B97F4A7C15L ^ j * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return h & Long.MAX_VALUE;
    }

    public int nodeLevel(Node n) {
        Integer v = nodeLevel.get(n.id);
        if (v == null) { v = (int) Math.round(t.height(n.p)); nodeLevel.put(n.id, v); }
        return v;
    }

    public interface CellVisitor { void visit(int i, int j); }

    /** Visits grid cells whose centre lies inside the polygon (half-open rule: shared edges belong to one side). */
    public void raster(List<P> poly, CellVisitor v) {
        double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
        for (P p : poly) { x0 = Math.min(x0, p.x()); y0 = Math.min(y0, p.y()); x1 = Math.max(x1, p.x()); y1 = Math.max(y1, p.y()); }
        for (int j = (int) Math.floor(y0); j <= (int) Math.ceil(y1); j++)
            for (int i = (int) Math.floor(x0); i <= (int) Math.ceil(x1); i++)
                if (in(i, j) && Geom.pointInPoly(new P(i, j), poly)) v.visit(i, j);
    }

    public void raster(P[] poly, CellVisitor v) { raster(List.of(poly), v); }

    // ------------------------------------------------------------------ build

    BlockPlan build() {
        classify();
        terrainAndSurfaces();
        for (House h : m.houses) h.wallSpan = null;
        for (House h : m.houses) {
            OpList o = new OpList();
            Sink prev = sink;
            sink = (x, y, z, st) -> { prev.add(x, y, z, st); o.add(x, y, z, st); };
            house(h);
            sink = prev;
            builtOps.put(h, o);
        }
        for (Landmark l : m.landmarks) landmark(l);
        if (c.b("mc.fountains")) for (Plaza p : m.plazas) if (p.central) fountain(p);
        gardenDecor();
        placingFurniture = true;
        for (var e : lamps.entrySet()) lampPost((int) (e.getKey() >> 32), (int) (long) e.getKey().intValue(), e.getValue());
        for (var e : seats.entrySet()) seat(e.getKey(), e.getValue());
        placingFurniture = false;
        return plan;
    }

    /** Villager mode: classify the current city without emitting anything. */
    void prepare() { classify(); }

    private void classify() {
        for (Plaza p : m.plazas) {
            int lv = nodeLevel(p.node);
            if (p.block && p.corners.size() >= 3) {
                // a block square slopes gently between the levels of the junctions around it
                List<Node> cs = p.corners;
                raster(p.poly, (i, j) -> {
                    int k = idx(i, j);
                    if (kind[k] != NONE) return;
                    double sw = 0, sl = 0;
                    for (Node n : cs) { double dd = n.p.dist(new P(i, j)), d2 = Math.max(1, dd * dd); sw += 1 / d2; sl += nodeLevel(n) / d2; }
                    kind[k] = PLAZA; lvl[k] = (int) Math.round(sl / sw); cls[k] = 3;
                });
            } else raster(p.poly, (i, j) -> { int k = idx(i, j); if (kind[k] == NONE) { kind[k] = PLAZA; lvl[k] = lv; cls[k] = 3; } });
        }
        double exp = c.d("street.junctionExpansion");
        for (Node n : m.g.aliveNodes()) {
            double w = 0;
            int nc = 1;
            for (Edge e : n.edges) { w = Math.max(w, e.widthAt(n)); nc = Math.max(nc, e.cls); }
            double r = w / 2 * (n.deg() >= 3 ? exp : 1.0);
            int lv = nodeLevel(n), ncf = nc;
            for (int j = (int) Math.floor(n.p.y() - r); j <= (int) Math.ceil(n.p.y() + r); j++)
                for (int i = (int) Math.floor(n.p.x() - r); i <= (int) Math.ceil(n.p.x() + r); i++) {
                    if (!in(i, j) || n.p.dist(new P(i, j)) > r) continue;
                    int k = idx(i, j);
                    if (kind[k] == NONE) { kind[k] = JUNCTION; lvl[k] = lv; cls[k] = ncf; }
                }
        }
        for (Edge e : m.g.aliveEdges()) {
            int la = nodeLevel(e.a), lb = nodeLevel(e.b);
            P a = e.a.p, b = e.b.p, ab = b.sub(a);
            double l2 = Math.max(1e-9, ab.dot(ab));
            P nrm = ab.norm().perpL();
            P[] quad = {a.add(nrm.mul(e.wA / 2)), b.add(nrm.mul(e.wB / 2)), b.sub(nrm.mul(e.wB / 2)), a.sub(nrm.mul(e.wA / 2))};
            raster(quad, (i, j) -> {
                int k = idx(i, j);
                if (kind[k] != NONE) return;
                double tt = Geom.clamp(new P(i, j).sub(a).dot(ab) / l2, 0, 1);
                kind[k] = STREET; lvl[k] = (int) Math.round(la + (lb - la) * tt); cls[k] = e.cls; owner[k] = e.id;
            });
        }
        // 1. pave the whole city footprint: every street-bounded face
        for (Face f : m.g.faces()) {
            if (f.area() < 20 || f.area() > c.d("block.maxHousingFace")) continue;
            raster(f.poly(), (i, j) -> { int k = idx(i, j); if (kind[k] == NONE) { kind[k] = PAVED; lvl[k] = paveLevel(i, j); } });
        }
        // 2. carve green space into block interiors
        for (CityBlock b : m.blocks) {
            for (List<P> g : b.gardens) // gardens never reach the street: a paved margin stays along it
                raster(g, (i, j) -> { int k = idx(i, j); if (kind[k] == PAVED && !nearStreet(i, j, 2)) { kind[k] = GARDEN; lvl[k] = paveLevel(i, j); } });
            if (b.houses.isEmpty() && b.buildable != null) {
                // block without houses becomes a small park: green inside, paved 2-block margin along the streets
                for (List<P> ring : Geom.areaRings(b.buildable)) {
                    List<P> rr = new ArrayList<>(ring);
                    if (Geom.signedArea(rr) < 0) java.util.Collections.reverse(rr);
                    raster(rr, (i, j) -> { int k = idx(i, j); if (kind[k] == PAVED && !nearStreet(i, j, 2)) { kind[k] = GARDEN; lvl[k] = paveLevel(i, j); } });
                }
            }
        }
        // 2b. cemeteries: the whole block, walled at its edge
        for (Cemetery cm : m.cemeteries)
            raster(cm.poly, (i, j) -> { int k = idx(i, j); if (kind[k] == PAVED || kind[k] == GARDEN) { kind[k] = CEMETERY; lvl[k] = paveLevel(i, j); } });
        // cleared lots in an evolving town stay green until they are rebuilt
        for (CityBlock b : m.blocks)
            for (House v : b.vacant)
                raster(v.q, (i, j) -> { int k = idx(i, j); if (kind[k] == PAVED) { kind[k] = GARDEN; lvl[k] = paveLevel(i, j); } });
        // house floor level = street level in front of the door, so entrances meet the street
        for (House h : m.houses) {
            P f = h.frontMid(), out = h.outward();
            int base = Integer.MIN_VALUE;
            for (double d = 0.5; d <= h.streetWidth / 2 + 4 && base == Integer.MIN_VALUE; d += 0.5) {
                P q = f.add(out.mul(d));
                int i = (int) Math.round(q.x()), j = (int) Math.round(q.y());
                if (!in(i, j)) break;
                byte kk = kind[idx(i, j)];
                if (kk == STREET || kk == PLAZA || kk == JUNCTION) base = lvl[idx(i, j)];
            }
            if (base == Integer.MIN_VALUE) base = (int) Math.round(t.height(h.c));
            houseBase.put(h.id, base);
        }
        // 3a. unit houses take their designed footprint first, so a design is always built whole; the shared party
        // wall column between two units belongs to the taller one (a standing house keeps what it has built)
        List<int[]> contested = new ArrayList<>();
        for (House h : m.houses) {
            if (h.unitKey == null) continue;
            Units.Unit u = Units.byKey(h.unitKey);
            if (u == null) { h.unitKey = null; unitMissing++; continue; }
            unitOf.put(h.id, u);
            for (int[] cc : u.cells) {
                int[] r = Units.rot(cc[0], cc[1], h.unitTurn);
                int i = h.ai + r[0], j = h.aj + r[1];
                if (!in(i, j)) continue;
                int k = idx(i, j);
                if (kind[k] == PAVED || kind[k] == GARDEN || kind[k] == NONE) { kind[k] = HOUSE; owner[k] = h.id; }
                else if (kind[k] == HOUSE && owner[k] != h.id) contested.add(new int[]{k, h.id});
            }
        }
        for (House h : m.houses) planned.put(h.id, plan(h));
        for (int[] ct : contested) {
            int k = ct[0], a = ct[1], b = owner[k];
            if (claims(m.houses.get(a), k) && !claims(m.houses.get(b), k)) owner[k] = a;
            else if (claims(m.houses.get(b), k)) continue;
            else if (taller(a, b)) owner[k] = a;
        }
        // 3b. other houses: their plot quad, on the cells left
        for (House h : m.houses) {
            if (unitOf.containsKey(h.id)) continue;
            raster(h.q, (i, j) -> {
                int k = idx(i, j);
                if (kind[k] == PAVED || kind[k] == GARDEN || kind[k] == NONE) { kind[k] = HOUSE; owner[k] = h.id; }
            });
        }
        // 4. close the street walls: release tiny fragments, then let neighbouring houses absorb gap cells
        HashMap<Integer, Integer> counts = new HashMap<>();
        for (int k = 0; k < kind.length; k++) if (kind[k] == HOUSE) counts.merge(owner[k], 1, Integer::sum);
        for (int k = 0; k < kind.length; k++)
            if (kind[k] == HOUSE && counts.get(owner[k]) < 6 && !unitOf.containsKey(owner[k])) { kind[k] = PAVED; owner[k] = -1; }
        for (CityBlock b : m.blocks) fillGaps(b);
        for (Landmark l : m.landmarks) {
            raster(l.fp, (i, j) -> {
                int k = idx(i, j);
                // a monument keeps its footprint: a street widened after it was placed narrows alongside it
                // (streets are never routed through a monument, so only such edge strips are affected)
                if (kind[k] != PLAZA || l.free) { kind[k] = LANDMARK; owner[k] = l.id; }
            });
        }
        for (int k = 0; k < kind.length; k++) {
            if (kind[k] == HOUSE) {
                lvl[k] = houseBase.get(owner[k]);
                houseCells.computeIfAbsent(owner[k], x -> new ArrayList<>()).add(new int[]{k % W, k / W});
            }
            if (kind[k] == LANDMARK) lvl[k] = landmarkBase(m.landmarks.get(owner[k]));
            if (kind[k] != NONE) {
                int x = ox + k % W, z = oz + k / W;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
        }
        gradeCourtyards();
        planCemeteries();
        for (House h : m.houses) courtyardDoor(h);
        if (!incrementalLamps) {
            for (Edge e : m.g.aliveEdges()) lampCells(e, e.width, null);
            for (Plaza p : m.plazas) plazaLamps(p);
        }
        for (House h : m.houses) if ("restaurant".equals(h.use)) outdoorSeating(h);
        if (c.b("block.gateWalls")) gateWalls();
    }

    /**
     * Hofje walls: any stretch of a built block's street edge still open after the infill pass (a gap between
     * buildings that would show the courtyard) gets a garden wall along the street with a door in the middle,
     * so the block reads as closed. key -> {direction toward the street, door 1/0, wall state, trim state, door state}.
     */
    final HashMap<Long, int[]> gateWall = new HashMap<>();
    int gateWallRuns;

    private void gateWalls() {
        gateWall.clear();
        for (CityBlock b : m.blocks) {
            if (b.houses.isEmpty() || b.facePoly == null) continue;
            HashMap<Long, Integer> cand = new HashMap<>(); // key -> direction toward the street
            raster(b.facePoly, (i, j) -> {
                if (i < 3 || j < 3 || i >= W - 3 || j >= H - 3) return;
                int k = idx(i, j);
                if (kind[k] != PAVED && kind[k] != GARDEN) return;
                int dir = -1;
                for (int d = 0; d < 4; d++) if (isStreetLike(kind[idx(i + DIRS[d][0], j + DIRS[d][1])])) { dir = d; break; }
                if (dir < 0) return;
                // a pavement strip in front of a facade is not a gap: the cell behind must be open ground, not a building
                for (int st = 1; st <= 2; st++) {
                    byte in = kind[idx(i - st * DIRS[dir][0], j - st * DIRS[dir][1])];
                    if (in == HOUSE || in == LANDMARK) return;
                }
                P p = new P(i, j);
                for (List<P> tp : b.tips) if (Geom.pointInPoly(p, tp)) return; // the paved nose of a sharp corner stays open
                cand.put(key(i, j), dir);
            });
            if (cand.isEmpty()) continue;
            // wall material from the block's own houses
            House h0 = b.houses.get(0);
            Palette pal = Palette.forHouse(c, h0);
            int wall = Block.getId(pal.wallFor(c, h0)), trim = Block.getId(pal.trim.defaultBlockState());
            HashSet<Long> seen = new HashSet<>();
            for (long k0 : new ArrayList<>(cand.keySet())) {
                if (seen.contains(k0)) continue;
                List<Long> run = new ArrayList<>();
                java.util.ArrayDeque<Long> q = new java.util.ArrayDeque<>();
                q.add(k0); seen.add(k0);
                while (!q.isEmpty()) {
                    long kk = q.poll(); run.add(kk);
                    int ci = (int) (kk >> 32), cj = (int) kk;
                    for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                        long nk = key(ci + di, cj + dj);
                        if (cand.containsKey(nk) && seen.add(nk)) q.add(nk);
                    }
                }
                if (run.size() < 3 || run.size() > 80) continue;
                gateWallRuns++;
                // the door: the cell nearest the middle of the run with open ground behind it
                double mx = 0, mz = 0;
                for (long kk : run) { mx += (int) (kk >> 32); mz += (int) kk; }
                mx /= run.size(); mz /= run.size();
                long door = Long.MIN_VALUE; double bd = 1e9;
                for (long kk : run) {
                    int ci = (int) (kk >> 32), cj = (int) kk, d = cand.get(kk);
                    byte behind = kind[idx(ci - DIRS[d][0], cj - DIRS[d][1])];
                    if (behind != PAVED && behind != GARDEN) continue;
                    double dd = Math.hypot(ci - mx, cj - mz);
                    if (dd < bd) { bd = dd; door = kk; }
                }
                for (long kk : run) {
                    int d = cand.get(kk);
                    gateWall.put(kk, new int[]{d, kk == door ? 1 : 0, wall, trim, Block.getId(pal.door.defaultBlockState())});
                }
            }
        }
    }

    public static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /**
     * Courtyard door: a boundary cell whose neighbour is garden, preferring the rear and the middle of the house.
     * The garden cells in front of it become a paved path that steps (one block per cell) from the house floor down
     * or up to the garden, so the door is usable whatever the slope. The path is part of the block's ground job.
     */
    private void courtyardDoor(House h) {
        List<int[]> cells = houseCells.get(h.id);
        if (cells == null || cells.size() < 6 || h.passage) return;
        int base = houseBase.get(h.id);
        P rearDir = h.outward().mul(-1);
        HashSet<Long> mine = new HashSet<>();
        for (int[] cl : cells) mine.add(key(cl[0], cl[1]));
        int[] best = null;
        double bestScore = -1e18;
        boolean anyGarden = false;
        HashSet<Long> busy = unitFurniture(h);
        for (int[] cl : cells) {
            if (!isBoundary(cl[0], cl[1], HOUSE, h.id)) continue;
            for (int d = 0; d < 4; d++) {
                int oi = cl[0] + DIRS[d][0], oj = cl[1] + DIRS[d][1];
                if (!in(oi, oj) || kind[idx(oi, oj)] != GARDEN) continue;
                anyGarden = true;
                int ii = cl[0] - DIRS[d][0], ij = cl[1] - DIRS[d][1]; // the room cell just inside the door
                if (!mine.contains(key(ii, ij)) || isBoundary(ii, ij, HOUSE, h.id) || busy.contains(key(ii, ij))) continue;
                int run = 0;
                for (int r = 1; r <= 6; r++) {
                    int pi = cl[0] + DIRS[d][0] * r, pj = cl[1] + DIRS[d][1] * r;
                    if (!in(pi, pj) || kind[idx(pi, pj)] != GARDEN) break;
                    run++;
                }
                double align = new P(DIRS[d][0], DIRS[d][1]).dot(rearDir);
                double score = 3 * align + Math.min(run, 4) - (run < 3 ? 6 : 0) - 0.15 * new P(cl[0], cl[1]).dist(h.c)
                        - 0.6 * Math.abs(lvl[idx(oi, oj)] - base);
                if (score > bestScore) { bestScore = score; best = new int[]{cl[0], cl[1], d}; }
            }
        }
        if (best == null) { if (anyGarden) courtyardNoDoor++; return; }
        // path: landing at floor level, then one-block steps toward the garden's own level
        int d = best[2], level = base;
        for (int r = 1; r <= 8; r++) {
            int pi = best[0] + DIRS[d][0] * r, pj = best[1] + DIRS[d][1] * r;
            if (!in(pi, pj) || kind[idx(pi, pj)] != GARDEN) break;
            int k = idx(pi, pj);
            int natural = lvl[k]; // graded courtyard level
            if (r > 1) {
                if (natural == level) break;
                level += Integer.signum(natural - level);
            }
            kind[k] = PAVED;
            lvl[k] = level;
        }
        rearDoor.put(h.id, best);
        courtyardDoors++;
    }

    public static long key(int i, int j) { return ((long) i << 32) | (j & 0xffffffffL); }

    /** Cells where a unit's ground floor has furniture (kept clear of the courtyard door). */
    private HashSet<Long> unitFurniture(House h) {
        HashSet<Long> out = new HashSet<>();
        Units.Unit u = unitOf.get(h.id);
        if (u == null) return out;
        List<Units.Piece> gs = new ArrayList<>(u.ground);
        gs.addAll(u.shop);
        for (Units.Piece p : gs)
            for (int n = 0; n < p.pos.length; n++) {
                int[] lp = p.pos[n];
                if (lp[1] != 1 || Units.mapPlaceholder(p.st[n], Palette.MONUMENT, p.st[n], h, Blocks.OAK_DOOR, false) != null) continue;
                int[] r = Units.rot(lp[0], lp[2], h.unitTurn);
                out.add(key(h.ai + r[0], h.aj + r[1]));
            }
        return out;
    }

    /**
     * Courtyards are terraced as in hill towns instead of following the raw hillside: each garden cell takes the floor
     * level of the nearest house (distance measured through the garden), so every house has a level terrace outside
     * its rear door, and neighbouring terraces meet with a step. On flat ground this changes nothing visible.
     */
    private void gradeCourtyards() {
        int n = W * H;
        int[] dist = new int[n];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        int[] level = new int[n];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int k = 0; k < n; k++) {
            if (kind[k] != GARDEN) continue;
            int i = k % W, j = k / W;
            int best = Integer.MIN_VALUE, cnt = 0;
            long sum = 0;
            for (int[] d : DIRS) {
                int ii = i + d[0], jj = j + d[1];
                if (!in(ii, jj)) continue;
                int kk = idx(ii, jj);
                if (kind[kk] == HOUSE && houseBase.containsKey(owner[kk])) { sum += houseBase.get(owner[kk]); cnt++; }
            }
            if (cnt > 0) { level[k] = (int) Math.round(sum / (double) cnt); dist[k] = 0; q.add(k); }
        }
        while (!q.isEmpty()) {
            int k = q.poll();
            int i = k % W, j = k / W;
            for (int[] d : DIRS) {
                int ii = i + d[0], jj = j + d[1];
                if (!in(ii, jj)) continue;
                int kk = idx(ii, jj);
                if (kind[kk] != GARDEN || dist[kk] <= dist[k] + 1) continue;
                dist[kk] = dist[k] + 1;
                level[kk] = level[k];
                q.add(kk);
            }
        }
        for (int k = 0; k < n; k++) if (kind[k] == GARDEN && dist[k] != Integer.MAX_VALUE) lvl[k] = level[k];
    }

    /**
     * Lamp posts along a street edge: every ~12 blocks, alternating sides, on the street's edge cells, never against a
     * house front (so never in front of a door or shop window), never inside junction openings. into != null collects
     * the cells (villager mode adds them as the street is laid).
     */
    List<long[]> lampCells(Edge e, double width, java.util.Map<Long, Integer> levels) {
        List<long[]> out = new ArrayList<>();
        double spacing = c.d("mc.lampSpacing");
        if (spacing <= 0) return out;
        P a = e.a.p, b = e.b.p, d = b.sub(a);
        double L = d.len();
        if (L < 6) return out;
        P u = d.norm(), n = u.perpL();
        int k = 0;
        for (double sPos = Math.floorMod(e.id * 7, (int) spacing) + spacing / 2; sPos < L - 4; sPos += spacing, k++) {
            if (sPos < 4) continue;
            int side = ((k + e.id) & 1) == 0 ? 1 : -1;
            P c0 = a.add(u.mul(sPos)).add(n.mul(side * Math.max(0.5, width / 2 - 0.5)));
            int i = (int) Math.round(c0.x()), j = (int) Math.round(c0.y());
            if (!in(i, j)) continue;
            int kk = idx(i, j);
            byte here = kind[kk];
            if (levels == null && !(here == STREET && owner[kk] == e.id)) continue;
            if (protect != null && protect[kk]) continue;
            int oi = (int) Math.round(c0.x() + n.x() * side), oj = (int) Math.round(c0.y() + n.y() * side);
            if (in(oi, oj)) {
                byte outer = kind[idx(oi, oj)];
                if (outer == HOUSE || outer == LANDMARK || isStreetLike(outer) && levels == null) continue;
            }
            if (lampNear(i, j, 5)) continue;
            int level = levels != null ? levels.getOrDefault(key(i, j), Integer.MIN_VALUE) : lvl[kk];
            if (level == Integer.MIN_VALUE || s.water[kk]) continue;
            lamps.put(key(i, j), level);
            out.add(new long[]{i, j, level});
        }
        return out;
    }

    private boolean lampNear(int i, int j, int r) {
        for (int dj = -r; dj <= r; dj++) for (int di = -r; di <= r; di++) if (lamps.containsKey(key(i + di, j + dj))) return true;
        return false;
    }

    /** A ring of lamp posts inside a plaza, clear of its fountain and its edges. */
    List<long[]> plazaLamps(Plaza p) {
        List<long[]> out = new ArrayList<>();
        double spacing = c.d("mc.lampSpacing");
        if (spacing <= 0) return out;
        double rr = Math.max(4, 0.6 * p.radius);
        int count = Math.max(3, (int) Math.round(2 * Math.PI * rr / (spacing * 0.8)));
        for (int q = 0; q < count; q++) {
            double ang = 2 * Math.PI * q / count + p.node.id * 0.37;
            P pc = p.center();
            int i = (int) Math.round(pc.x() + rr * Math.cos(ang)), j = (int) Math.round(pc.y() + rr * Math.sin(ang));
            if (!in(i, j)) continue;
            int kk = idx(i, j);
            if (kind[kk] != PLAZA || (protect != null && protect[kk]) || s.water[kk]) continue;
            boolean edge = false;
            for (int[] dd : DIRS) { int ii = i + dd[0], jj = j + dd[1]; if (in(ii, jj) && kind[idx(ii, jj)] == HOUSE) edge = true; }
            if (edge || seats.containsKey(key(i, j))) continue;
            lamps.put(key(i, j), lvl[kk]);
            out.add(new long[]{i, j, lvl[kk]});
        }
        return out;
    }

    /** Lamp post: three fence posts topped with a glowstone lantern. */
    private void lampPost(int i, int j, int level) {
        for (int y = level + 1; y <= level + 3; y++) put(i, y, j, Blocks.DARK_OAK_FENCE);
        put(i, level + 4, j, Blocks.GLOWSTONE);
    }

    /**
     * Outdoor seating for a restaurant: up to two tables with chairs on the plaza or a wide street in front of it,
     * leaving a free lane along the facade and at least two free cells beyond the tables.
     */
    private void outdoorSeating(House h) {
        P q0 = h.q[0], q1 = h.q[1], out = h.outward(), along = q1.sub(q0).norm();
        Direction ad = Palette.dirOf(along);
        int placed = 0;
        for (double tt : new double[]{0.28, 0.72}) {
            P f = q0.lerp(q1, tt);
            P tp = f.add(out.mul(2.6));
            int ti = (int) Math.round(tp.x()), tj = (int) Math.round(tp.y());
            int[][] chairs = {{ti + ad.getStepX(), tj + ad.getStepZ()}, {ti - ad.getStepX(), tj - ad.getStepZ()}};
            if (!seatable(ti, tj) || !seatable(chairs[0][0], chairs[0][1]) || !seatable(chairs[1][0], chairs[1][1])) continue;
            // free lane between facade and table, and room beyond it
            P lane = f.add(out.mul(1.2)), b1 = tp.add(out.mul(1.0)), b2 = tp.add(out.mul(2.0));
            if (!streetCell(lane) || !streetCell(b1) || !streetCell(b2)) continue;
            int level = lvl[idx(ti, tj)];
            if (lvl[idx(chairs[0][0], chairs[0][1])] != level || lvl[idx(chairs[1][0], chairs[1][1])] != level) continue;
            seats.put(key(ti, tj), new int[]{level, 0, 0});
            seats.put(key(chairs[0][0], chairs[0][1]), new int[]{level, 1, ad.ordinal()});
            seats.put(key(chairs[1][0], chairs[1][1]), new int[]{level, 1, ad.getOpposite().ordinal()});
            placed++;
        }
        h.seating = placed;
    }

    private boolean streetCell(P p) {
        int i = (int) Math.round(p.x()), j = (int) Math.round(p.y());
        return in(i, j) && isStreetLike(kind[idx(i, j)]) && !lamps.containsKey(key(i, j));
    }

    private boolean seatable(int i, int j) {
        if (!in(i, j)) return false;
        int k = idx(i, j);
        byte kk = kind[k];
        if (!isStreetLike(kk) || s.water[k] || lamps.containsKey(key(i, j)) || seats.containsKey(key(i, j))) return false;
        if (protect != null && protect[k]) return false;
        for (Plaza p : m.plazas) if (p.central && p.center().dist(new P(i, j)) < 4) return false; // fountain
        return true;
    }

    private void seat(long ck, int[] sv) {
        int i = (int) (ck >> 32), j = (int) ck;
        if (sv[1] == 0) {
            put(i, sv[0] + 1, j, Blocks.OAK_FENCE);
            put(i, sv[0] + 2, j, Blocks.OAK_PRESSURE_PLATE);
        } else {
            put(i, sv[0] + 1, j, Palette.stair(Blocks.OAK_STAIRS, Direction.values()[sv[2]], false));
        }
    }

    private boolean nearStreet(int i, int j, int r) {
        for (int dj = -r; dj <= r; dj++) for (int di = -r; di <= r; di++)
            if (in(i + di, j + dj) && isStreetLike(kind[idx(i + di, j + dj)])) return true;
        return false;
    }

    int paveLevel(int i, int j) { return (int) Math.round(t.height(i, j)); }

    // ------------------------------------------------------------------ evolving layout

    /** Restores one cell by its current classification (land outside the town back to grass), removing any lamp post. */
    private void restoreCell(int i, int j, int level) {
        if (!in(i, j)) return;
        int k = idx(i, j);
        if (protect != null && protect[k]) return;
        // buildings, monuments and gardens are prepared by their own jobs (which may run before or after this one)
        if (kind[k] == HOUSE || kind[k] == LANDMARK || kind[k] == GARDEN) return;
        Integer lampLevel = lamps.remove(key(i, j));
        if (lampLevel != null) for (int y = lampLevel + 1; y <= lampLevel + 4; y++) put(i, y, j, Blocks.AIR);
        if (kind[k] != NONE) { cellTerrain(i, j); return; }
        if (s.water[k]) return;
        int top = s.ground[k], ct = Math.max(s.clearTop[k], top);
        if (placedTop != null) ct = Math.max(ct, placedTop[k]);
        for (int y = level + 1; y <= ct; y++) put(i, y, j, Blocks.AIR);
        for (int y = top + 1; y < level; y++) put(i, y, j, Blocks.DIRT);
        put(i, level, j, Blocks.GRASS_BLOCK);
    }

    private P[] strip(Edge e, double wa, double wb, double extendA, double extendB) {
        P a = e.a.p, b = e.b.p, u = b.sub(a).norm(), n = u.perpL();
        a = a.sub(u.mul(extendA)); b = b.add(u.mul(extendB));
        return new P[]{a.add(n.mul(wa / 2)), b.add(n.mul(wb / 2)), b.sub(n.mul(wb / 2)), a.sub(n.mul(wa / 2))};
    }

    private int levelAlong(Edge e, int i, int j) {
        int la = nodeLevel(e.a), lb = nodeLevel(e.b);
        P ab = e.b.p.sub(e.a.p);
        double t = Geom.clamp(new P(i, j).sub(e.a.p).dot(ab) / Math.max(1e-9, ab.dot(ab)), 0, 1);
        return (int) Math.round(la + (lb - la) * t);
    }

    /**
     * Re-lays a street at its current width (it changed since it was laid): cells the narrower street gives back become
     * sidewalk or garden by their classification, lamp posts that would now stand in the carriageway move to the new
     * edge, and the street is paved again. Buildings are never cut into: widening only takes free cells (guard).
     */
    public OpList relayOps(Edge e, double oldWidth) {
        OpList o = begin(true);
        P[] oldQ = strip(e, oldWidth, oldWidth, 0, 0), newQ = strip(e, e.wA, e.wB, 0, 0);
        List<int[]> freed = new ArrayList<>();
        raster(oldQ, (i, j) -> { if (!Geom.pointInPoly(new P(i, j), newQ) && !isStreetLike(kind[idx(i, j)])) freed.add(new int[]{i, j}); });
        for (int[] cl : freed) restoreCell(cl[0], cl[1], levelAlong(e, cl[0], cl[1]));
        List<long[]> inside = new ArrayList<>();
        for (var en : lamps.entrySet()) {
            int i = (int) (en.getKey() >> 32), j = en.getKey().intValue();
            P p = new P(i, j);
            if (Geom.pointInPoly(p, newQ) && Geom.distPointSeg(p, e.a.p, e.b.p) < e.width / 2 - 0.9) inside.add(new long[]{i, j});
        }
        for (long[] l : inside) restoreCell((int) l[0], (int) l[1], levelAlong(e, (int) l[0], (int) l[1]));
        end();
        o.addAll(streetOps(e));
        return o;
    }

    /** Takes up a removed street: its cells become sidewalk, garden or grass by their new classification. */
    OpList unpaveOps(Edge e, boolean deadEndAtA, boolean deadEndAtB) {
        OpList o = begin(true);
        double w = Math.max(e.wA, e.wB);
        P[] q = strip(e, e.wA + 0.6, e.wB + 0.6, deadEndAtA ? w / 2 + 1 : 0, deadEndAtB ? w / 2 + 1 : 0);
        raster(q, (i, j) -> { if (!isStreetLike(kind[idx(i, j)])) restoreCell(i, j, levelAlong(e, i, j)); });
        end();
        return o;
    }

    /** Plaza reshaped: cells it no longer covers are restored by their classification; newly covered cells are paved. */
    OpList plazaReshapeOps(java.util.Set<Long> oldCells, java.util.Set<Long> newCells, Plaza p) {
        OpList o = begin(true);
        for (long ck : oldCells) {
            if (newCells.contains(ck)) continue;
            int i = (int) (ck >> 32), j = (int) ck;
            if (in(i, j) && kind[idx(i, j)] != PLAZA) restoreCell(i, j, lvl[idx(i, j)]);
        }
        if (p != null) {
            for (long ck : newCells) {
                if (oldCells.contains(ck)) continue;
                int i = (int) (ck >> 32), j = (int) ck;
                cellTerrain(i, j);
            }
            placingFurniture = true;
            for (long[] l : plazaLamps(p)) lampPost((int) l[0], (int) l[1], (int) l[2]);
            placingFurniture = false;
        }
        end();
        return o;
    }

    /** Current plaza cells (classified as plaza and free to pave). */
    java.util.Set<Long> plazaCells(Plaza p) {
        java.util.Set<Long> out = new HashSet<>();
        raster(p.poly, (i, j) -> { int k = idx(i, j); if (kind[k] == PLAZA && (protect == null || !protect[k])) out.add(key(i, j)); });
        return out;
    }

    /** Villager mode: lamp posts and seating as their own small job (their cells are skipped by guarded paving). */
    OpList furnitureOps(List<long[]> lampList, House restaurant) {
        OpList o = begin(false);
        placingFurniture = true;
        if (lampList != null)
            for (long[] l : lampList) {
                if (protect != null && in((int) l[0], (int) l[1]) && protect[idx((int) l[0], (int) l[1])]) continue;
                lampPost((int) l[0], (int) l[1], (int) l[2]);
            }
        if (restaurant != null) {
            P c0 = restaurant.c;
            for (var e : seats.entrySet()) {
                int i = (int) (e.getKey() >> 32), j = e.getKey().intValue();
                if (new P(i, j).dist(c0) < restaurant.depth + 6) { cellTerrain(i, j); seat(e.getKey(), e.getValue()); }
            }
        }
        placingFurniture = false;
        end();
        return o;
    }

    /**
     * Paved cells inside a block's ring of houses (between the street edge and the garden) are gaps in
     * the street wall. A breadth-first flood from the block's houses assigns each such cell to the
     * nearest house (up to 6 cells away), so corners and rejected plots become part of a building.
     */
    private void fillGaps(CityBlock b) {
        if (b.houses.isEmpty()) return;
        int gapDepth = c.i("block.gapFillDepth");
        int frontFill = c.i("block.gapFillFront");
        java.util.HashSet<Long> cand = new java.util.HashSet<>();
        for (List<P> ring : b.rings)
            raster(ring, (i, j) -> {
                if (kind[idx(i, j)] != PAVED) return;
                P p = new P(i, j);
                if (!nearStreet(i, j, 2)) for (List<P> g : b.gardens) if (Geom.pointInPoly(p, g)) return; // the street margin can still close
                for (List<P> tp : b.tips) if (Geom.pointInPoly(p, tp)) return; // open corner squares stay open
                cand.add(((long) i << 32) | (j & 0xffffffffL));
            });
        if (cand.isEmpty()) return;
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        List<Long> sorted = new ArrayList<>(cand);
        java.util.Collections.sort(sorted);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (long key : sorted) {
            int i = (int) (key >> 32), j = (int) key;
            for (int[] d : dirs) {
                int ii = i + d[0], jj = j + d[1];
                if (!in(ii, jj)) continue;
                int k2 = idx(ii, jj);
                if (kind[k2] == HOUSE && owner[k2] >= 0 && owner[k2] < m.houses.size() && m.houses.get(owner[k2]).block == b
                        && !unitOf.containsKey(owner[k2])) { // a unit keeps its designed shape; plain houses widen
                    q.add(new int[]{i, j, owner[k2], 1});
                    break;
                }
            }
        }
        while (!q.isEmpty()) {
            int[] e = q.poll();
            int k = idx(e[0], e[1]);
            if (kind[k] != PAVED) continue;
            kind[k] = HOUSE; owner[k] = e[2]; gapCellsFilled++;
            // along the street edge a house may widen much further to close a gap (a building beats a wall);
            // elsewhere only gapDepth cells, so courtyards are not eaten
            boolean front = nearStreet(e[0], e[1], 1);
            if (e[3] >= (front ? frontFill : gapDepth)) continue;
            for (int[] d : dirs) {
                int ii = e[0] + d[0], jj = e[1] + d[1];
                if (in(ii, jj) && kind[idx(ii, jj)] == PAVED && cand.contains(((long) ii << 32) | (jj & 0xffffffffL)))
                    q.add(new int[]{ii, jj, e[2], e[3] + 1});
            }
        }
    }

    int landmarkBase(Landmark l) {
        Plaza p = l.center.plaza;
        if (l.free && p != null && p.block && p.corners.size() >= 3) {
            // stands at the level of the square around it
            P cen = Geom.centroid(java.util.Arrays.asList(l.fp));
            double sw = 0, sl = 0;
            for (Node n : p.corners) { double dd = n.p.dist(cen), d2 = Math.max(1, dd * dd); sw += 1 / d2; sl += nodeLevel(n) / d2; }
            return (int) Math.round(sl / sw);
        }
        Node n = p != null ? p.node : l.center.node;
        return nodeLevel(n);
    }

    // ------------------------------------------------------------------ terrain shaping and paving

    public boolean isStreetLike(byte k) { return k == STREET || k == PLAZA || k == JUNCTION; }

    private void terrainAndSurfaces() {
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) cellTerrain(i, j);
    }

    /** Cut/fill, clearing and the walking surface of one classified cell. */
    private void cellTerrain(int i, int j) {
        int k = idx(i, j);
        byte kk = kind[k];
        if (kk == NONE) return;
        int target = lvl[k], top = s.ground[k], ct = Math.max(s.clearTop[k], top);
        if (placedTop != null && placedTop[k] > ct) ct = placedTop[k]; // also clear anything the town built here before
        if (s.water[k]) {
            if (kk == STREET || kk == JUNCTION) { // bridge deck over water
                for (int y = target + 1; y <= ct; y++) put(i, y, j, Blocks.AIR);
                put(i, target, j, Blocks.STONE_BRICKS);
                if (border(i, j)) put(i, target + 1, j, Blocks.STONE_BRICK_SLAB);
            }
            return;
        }
        for (int y = target + 1; y <= ct; y++) put(i, y, j, Blocks.AIR);
        Block fill = (kk == HOUSE || kk == LANDMARK) ? Blocks.STONE_BRICKS : (kk == GARDEN || kk == CEMETERY ? Blocks.DIRT : Blocks.COBBLESTONE);
        for (int y = top + 1; y < target; y++) put(i, y, j, fill);
        put(i, target, j, surface(i, j, kk));
        if (isStreetLike(kk) || kk == PAVED) stepUp(i, j, target, kk);
    }

    /**
     * Stepped streets: where the next paved cell is exactly one block higher, a stair in the lower
     * cell (facing up-slope) lets players walk up without jumping. Bigger jumps stay as small walls.
     */
    private void stepUp(int i, int j, int target, byte kk) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int up = -1, upCount = 0;
        for (int d = 0; d < 4; d++) {
            int ii = i + dirs[d][0], jj = j + dirs[d][1];
            if (!in(ii, jj)) continue;
            byte nk = kind[idx(ii, jj)];
            if (!(isStreetLike(nk) || nk == PAVED) || s.water[idx(ii, jj)]) continue;
            if (lvl[idx(ii, jj)] == target + 1) { up = d; upCount++; }
        }
        if (upCount != 1) return; // corners and saddles stay as plain steps
        int oi = i - dirs[up][0], oj = j - dirs[up][1];
        if (in(oi, oj) && lvl[idx(oi, oj)] > target) return;
        Direction facing = dirs[up][0] == 1 ? Direction.EAST : dirs[up][0] == -1 ? Direction.WEST : dirs[up][1] == 1 ? Direction.SOUTH : Direction.NORTH;
        int cl = cls[idx(i, j)];
        Block stair = kk == PLAZA ? Blocks.POLISHED_ANDESITE_STAIRS
                : kk == PAVED || cl == 2 ? Blocks.STONE_BRICK_STAIRS : cl >= 3 ? Blocks.POLISHED_ANDESITE_STAIRS : Blocks.COBBLESTONE_STAIRS;
        put(i, target + 1, j, Palette.stair(stair, facing, false));
    }

    private boolean border(int i, int j) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int ii = i + d[0], jj = j + d[1];
            if (!in(ii, jj) || !isStreetLike(kind[idx(ii, jj)])) return true;
        }
        return false;
    }

    private BlockState surface(int i, int j, byte kk) {
        long h = hash(i, j, 1) % 100;
        return switch (kk) {
            case STREET, JUNCTION -> {
                int cl = cls[idx(i, j)];
                if (cl <= 1) yield Palette.s(h < 70 ? Blocks.COBBLESTONE : h < 90 ? Blocks.ANDESITE : Blocks.MOSSY_COBBLESTONE);
                if (cl == 2) yield Palette.s(h < 85 ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS);
                yield Palette.s(border(i, j) ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE);
            }
            case PLAZA -> Palette.s((((i >> 1) + (j >> 1)) & 1) == 0 ? Blocks.SMOOTH_STONE : Blocks.POLISHED_ANDESITE);
            case HOUSE -> Palette.s(Blocks.STONE_BRICKS);
            case LANDMARK -> Palette.s(Blocks.POLISHED_ANDESITE);
            case PAVED -> Palette.s(h < 70 ? Blocks.STONE_BRICKS : h < 88 ? Blocks.POLISHED_ANDESITE : h < 96 ? Blocks.CRACKED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS);
            case CEMETERY -> cemSurface.getOrDefault(key(i, j), Palette.s(Blocks.GRASS_BLOCK));
            default -> Palette.s(Blocks.GRASS_BLOCK);
        };
    }

    // ------------------------------------------------------------------ party walls

    long wkey(int i, int j) { return key(ox + i, oz + j); }

    /** Storey heights in blocks: a unit's pieces, or the facade's storeys. */
    int[] storeys(House h) {
        Units.Unit u = unitOf.get(h.id);
        int n = h.facade == null ? 1 : Math.max(1, h.facade.floorH.length);
        if (u != null) {
            if (u.upper.isEmpty()) n = 1;
            int[] hb = new int[n];
            hb[0] = Units.heightOf(u.ground.isEmpty() ? u.shop : u.ground);
            for (int k = 1; k < n; k++) hb[k] = Units.heightOf(u.upper);
            return hb;
        }
        int minStorey = c.i("mc.storeyMinBlocks");
        int[] hb = new int[n];
        for (int k = 0; k < n; k++) hb[k] = h.facade == null ? minStorey : Math.max(minStorey, (int) Math.round(h.facade.floorH[k]));
        return hb;
    }

    private int[] plan(House h) {
        int base = houseBase.getOrDefault(h.id, 0), top = base;
        for (int v : storeys(h)) top += v;
        return new int[]{base, top};
    }

    /** a is preferred over b for a shared cell: the taller house, then the lower floor, then the lower id. */
    private boolean taller(int a, int b) {
        int[] pa = planned.get(a), pb = planned.get(b);
        if (pa[1] != pb[1]) return pa[1] > pb[1];
        if (pa[0] != pb[0]) return pa[0] < pb[0];
        return a < b;
    }

    /** A standing house keeps the columns it has built. */
    private boolean claims(House h, int k) {
        if (h.wallSpan == null || h.builtCells == null) return false;
        int wx = ox + k % W, wz = oz + k / W;
        for (int[] c : h.builtCells) if (c[0] == wx && c[1] == wz) return true;
        return false;
    }

    private static boolean solid(BlockState st) {
        if (st == null || st.isAir()) return false;
        Block b = st.getBlock();
        return b != Blocks.GLASS && b != Blocks.GLASS_PANE && b != Blocks.LADDER && b != Blocks.LANTERN
                && !st.is(net.minecraft.tags.BlockTags.DOORS);
    }

    /**
     * Is the neighbour's column (i, j) a solid wall at height y? For a standing house, what it built; for a unit
     * still to be built, its whole height (designs wall their perimeter); for a plain house still to be built, its
     * whole height if it wins the shared wall against the house being built now (taller, then lower, then lower id).
     */
    boolean covered(House n, int i, int j, int y) {
        if (n.wallSpan != null) {
            int[] r = n.wallSpan.get(wkey(i, j));
            if (r == null) return false;
            for (int q = 0; q + 1 < r.length; q += 2) if (y >= r[q] && y <= r[q + 1]) return true;
            return false;
        }
        List<int[]> nc = houseCells.get(n.id);
        if (nc == null || nc.size() < 6 || n.facade == null) return false; // never built
        int[] p = planned.get(n.id);
        if (p == null || y <= p[0] || y > p[1]) return false;
        Units.Unit nu = unitOf.get(n.id);
        if (nu != null) { // a unit walls the perimeter of its design: is this column on it?
            int[] lc = Units.rot(i - n.ai, j - n.aj, -n.unitTurn);
            return nu.edge.contains(key(lc[0], lc[1]));
        }
        return cur != null && !unitOf.containsKey(cur.id) ? taller(n.id, cur.id) : true;
    }

    /**
     * A plain house next to other houses builds no wall of its own where a neighbour's wall already stands: its
     * perimeter cells facing only neighbours stay open (part of the room) at every height the neighbours' walls
     * cover, so neighbours share one wall. The rest of its perimeter is walled as usual.
     */
    private void computeYields(House h, List<int[]> cells, int base, int top) {
        yieldY.clear(); fullYield.clear();
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            if (!isBoundary(i, j, HOUSE, h.id)) continue;
            List<int[]> nb = new ArrayList<>();
            boolean ok = true;
            for (int[] d : DIRS) {
                int ni = i + d[0], nj = j + d[1];
                if (owns(ni, nj, HOUSE, h.id)) continue;
                if (!in(ni, nj) || kind[idx(ni, nj)] != HOUSE) { ok = false; break; }
                nb.add(new int[]{ni, nj});
            }
            if (!ok || nb.isEmpty()) continue;
            java.util.BitSet bs = new java.util.BitSet();
            for (int y = base + 1; y <= top; y++) {
                boolean all = true;
                for (int[] q : nb) if (!covered(m.houses.get(owner[idx(q[0], q[1])]), q[0], q[1], y)) { all = false; break; }
                if (all) bs.set(y - base);
            }
            if (bs.isEmpty()) continue;
            long kk = key(i, j);
            yieldY.put(kk, bs);
            if (bs.cardinality() == top - base) fullYield.add(kk);
            partyCells++;
            for (int[] q : nb) {
                int lo = base + bs.nextSetBit(0), hi = base + bs.length() - 1;
                partyNeed.computeIfAbsent(wkey(q[0], q[1]), x -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new Need(h, lo, hi));
            }
        }
    }

    private boolean isWall(int i, int j, int id) { return isBoundary(i, j, HOUSE, id) && !fullYield.contains(key(i, j)); }

    private boolean yielded(long cellKey, int y, int base) {
        java.util.BitSet bs = yieldY.get(cellKey);
        return bs != null && y > base && bs.get(y - base);
    }

    /** Neighbours that lean on this house's columns get them solid, even above this house's own roofline. */
    private void ensureNeeds(House h, List<int[]> cells, BlockState wall) {
        for (int[] cl : cells) {
            List<Need> l = partyNeed.get(wkey(cl[0], cl[1]));
            if (l == null) continue;
            for (Need nd : l) {
                if (nd.h() == h || nd.h().wallSpan == null) continue; // only neighbours that stand (or were just built)
                for (int y = nd.lo(); y <= nd.hi(); y++)
                    if (!solid(curWrites.get(BlockPos.asLong(cl[0], y, cl[1])))) put(cl[0], y, cl[1], wall);
            }
        }
    }

    /** What this house's perimeter columns now hold, for neighbours built after it. */
    private void recordWalls(House h, List<int[]> cells, int base) {
        HashMap<Long, int[]> span = new HashMap<>();
        HashSet<Long> mine = new HashSet<>();
        for (int[] cl : cells) mine.add(key(cl[0], cl[1]));
        int maxY = base;
        for (long p : curWrites.keySet()) maxY = Math.max(maxY, BlockPos.getY(p));
        for (int[] cl : cells) {
            boolean edge = false;
            for (int[] d : DIRS) if (!mine.contains(key(cl[0] + d[0], cl[1] + d[1]))) { edge = true; break; }
            if (!edge) continue;
            List<Integer> runs = new ArrayList<>();
            int start = Integer.MIN_VALUE;
            for (int y = base + 1; y <= maxY + 1; y++) {
                boolean sol = y <= maxY && solid(curWrites.get(BlockPos.asLong(cl[0], y, cl[1])));
                if (sol && start == Integer.MIN_VALUE) start = y;
                if (!sol && start != Integer.MIN_VALUE) { runs.add(start); runs.add(y - 1); start = Integer.MIN_VALUE; }
            }
            int[] r = new int[runs.size()];
            for (int q = 0; q < r.length; q++) r[q] = runs.get(q);
            span.put(wkey(cl[0], cl[1]), r);
        }
        h.wallSpan = span;
        for (var e : curForeign.entrySet())
            partyNeed.computeIfAbsent(e.getKey(), x -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(new Need(h, e.getValue()[0], e.getValue()[1]));
    }

    /** Forget what a house relied on (it is about to be rebuilt or cleared). */
    void forgetParty(House h) {
        h.wallSpan = null;
        for (List<Need> l : partyNeed.values()) l.removeIf(nd -> nd.h() == h);
    }

    // ------------------------------------------------------------------ houses

    private boolean owns(int i, int j, byte kk, int id) {
        if (!in(i, j)) return false;
        int k = idx(i, j);
        return kind[k] == kk && owner[k] == id;
    }

    private boolean isBoundary(int i, int j, byte kk, int id) {
        return !owns(i + 1, j, kk, id) || !owns(i - 1, j, kk, id) || !owns(i, j + 1, kk, id) || !owns(i, j - 1, kk, id);
    }

    private static double distLine(P p, P a, P b) {
        P d = b.sub(a);
        double l = d.len();
        return l < 1e-9 ? p.dist(a) : Math.abs(d.cross(p.sub(a))) / l;
    }

    private void house(House h) {
        List<int[]> cells = houseCells.getOrDefault(h.id, List.of());
        if (cells.size() < 6 || h.facade == null) return;
        housesBuilt++;
        cur = h; curWrites = new HashMap<>(); curForeign = new HashMap<>();
        try {
            Palette pal = Palette.forHouse(c, h);
            BlockState wall = pal.wallFor(c, h);
            if (unitOf.containsKey(h.id)) placeUnit(h, cells, pal, wall); else procHouse(h, cells, pal, wall);
            ensureNeeds(h, cells, wall);
            recordWalls(h, cells, houseBase.get(h.id));
        } finally {
            cur = null; curWrites = null; curForeign = null; yieldY.clear(); fullYield.clear();
        }
    }

    /**
     * A unit house: its pieces stacked (ground or shop floor, one upper piece per further storey, the roof), turned in
     * quarter turns to the plot and anchored on the plot's origin cell, placeholders swapped for the town's palette.
     * Blocks are placed only on the house's own cells (and the cornice over the street); a party-wall column that
     * belongs to the neighbour is left to the neighbour's wall.
     */
    private void placeUnit(House h, List<int[]> cells, Palette pal, BlockState wall) {
        Units.Unit u = unitOf.get(h.id);
        int base = houseBase.get(h.id);
        int[] hb = storeys(h);
        int q = h.unitTurn;
        boolean commercial = !"residential".equals(h.use);
        boolean accentDoor = commercial || ((h.id * 40503L + 17) % 1000) < c.d("use.accentDoorShare") * 1000;
        Block door = accentDoor ? Palette.accent(h)[1] : pal.door;
        boolean grand = h.tier <= 2;
        long hv = hash(h.ai, h.aj, 5 + h.variant);
        List<Units.Piece> stack = new ArrayList<>();
        stack.add(Units.pick(commercial && !u.shop.isEmpty() ? u.shop : u.ground.isEmpty() ? u.shop : u.ground, hv));
        for (int k = 1; k < hb.length; k++) stack.add(Units.pick(u.upper, hv + k * 7919L));
        Units.Piece roof = Units.pick(u.roof, hv + 13);
        int y0 = base, top = base;
        for (int v : hb) top += v;
        h.builtCells = new ArrayList<>(cells.size());
        for (int[] cl : cells) h.builtCells.add(new int[]{ox + cl[0], oz + cl[1]});
        h.builtBase = base; h.builtTop = top;
        unitHouses++;
        // where this unit's perimeter wall would stand against a neighbour's wall (not a chained party wall: rows
        // meeting at a corner, a plain house alongside), the neighbour's wall serves and the floors run up to it
        computeYields(h, cells, base, top);
        for (int pi = 0; pi <= stack.size(); pi++) {
            Units.Piece p = pi < stack.size() ? stack.get(pi) : roof;
            if (p == null) break;
            boolean isRoof = pi == stack.size();
            int yb = isRoof ? top : y0;
            for (int n = 0; n < p.pos.length; n++) {
                int[] lp = p.pos[n];
                int[] r = Units.rot(lp[0], lp[2], q);
                int i = h.ai + r[0], j = h.aj + r[1], y = yb + lp[1];
                if (!in(i, j)) continue;
                byte kk = kind[idx(i, j)];
                if (kk != HOUSE) {
                    // outside the footprint only the eaves and cornice, and only over open ground
                    if (!isRoof || lp[1] > 1 || !(isStreetLike(kk) || kk == PAVED || kk == GARDEN)) continue;
                    if (lamps.containsKey(key(i, j))) continue;
                }
                BlockState st = Units.mapPlaceholder(p.st[n], pal, wall, h, door, grand);
                if (st == null) st = p.st[n];
                if (kk == HOUSE && u.edge.contains(key(lp[0], lp[2])) && yielded(key(i, j), y, base)) {
                    if (lp[1] != 0) continue;
                    st = pal.floor.defaultBlockState();
                }
                foreignWall = u.edge.contains(key(lp[0], lp[2]));
                put(i, y, j, States.rotate(st, q));
                foreignWall = false;
            }
            if (!isRoof) y0 += p.height;
        }
        exposedSideWindows(h, cells, base, hb);
        // courtyard door, cut into the rear wall where the plot meets the garden
        int[] rd = rearDoor.get(h.id);
        if (rd != null) {
            Direction rdir = d2(DIRS[rd[2]][0], DIRS[rd[2]][1]);
            put(rd[0], base + 1, rd[1], Palette.door(pal.door, rdir, false));
            put(rd[0], base + 2, rd[1], Palette.door(pal.door, rdir, true));
            int ii = rd[0] - DIRS[rd[2]][0], ij = rd[1] - DIRS[rd[2]][1];
            for (int y = base + 1; y <= base + 2; y++) {
                BlockState was = curWrites.get(BlockPos.asLong(ii, y, ij));
                if (was != null && !was.is(net.minecraft.tags.BlockTags.BEDS) && was.getBlock() != Blocks.LADDER) put(ii, y, ij, Palette.s(Blocks.AIR));
            }
        }
    }

    /**
     * A unit's party wall that ends up facing a street or paving (the end of a row, a gap the infill could not close)
     * gets windows at the same rhythm as a facade, so no street sees a blank wall.
     */
    private void exposedSideWindows(House h, List<int[]> cells, int base, int[] hb) {
        HashSet<Long> mine = new HashSet<>();
        for (int[] cl : cells) mine.add(key(cl[0], cl[1]));
        int[] sb = new int[hb.length];
        for (int k = 0, y = base; k < hb.length; k++) { sb[k] = y; y += hb[k]; }
        for (int d = 0; d < 4; d++) {
            List<int[]> run = new ArrayList<>();
            for (int[] cl : cells) {
                int ni = cl[0] + DIRS[d][0], nj = cl[1] + DIRS[d][1], bi = cl[0] - DIRS[d][0], bj = cl[1] - DIRS[d][1];
                if (!in(ni, nj) || mine.contains(key(ni, nj)) || !mine.contains(key(bi, bj))) continue;
                byte nk = kind[idx(ni, nj)];
                if (!isStreetLike(nk) && nk != PAVED) continue;
                if (!solid(curWrites.get(BlockPos.asLong(cl[0], base + 2, cl[1])))) continue;
                run.add(cl);
            }
            if (run.size() < 3) continue;
            final int dd = d;
            run.sort((x, y) -> DIRS[dd][0] != 0 ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]));
            int st = 0;
            for (int e = 1; e <= run.size(); e++) {
                boolean brk = e == run.size();
                if (!brk) {
                    int[] pa = run.get(e - 1), pb = run.get(e);
                    brk = (DIRS[dd][0] != 0 ? pb[1] - pa[1] : pb[0] - pa[0]) > 1;
                }
                if (!brk) continue;
                int L = e - st;
                boolean glazed = false;
                for (int u = st; u < e && !glazed; u++)
                    for (int y = base + 1; y < sb[sb.length - 1] + hb[hb.length - 1]; y++)
                        if (curWrites.get(BlockPos.asLong(run.get(u)[0], y, run.get(u)[1])) != null
                                && curWrites.get(BlockPos.asLong(run.get(u)[0], y, run.get(u)[1])).getBlock() == Blocks.GLASS) { glazed = true; break; }
                if (L >= 3 && !glazed) {
                    int nWin = Math.max(1, (int) Math.round((L - 2) / 3.0));
                    for (int w = 0; w < nWin; w++) {
                        int pos = Math.max(st + 1, Math.min(st + L - 2, st + 1 + (int) Math.round((w + 0.5) * (L - 2) / nWin - 0.5)));
                        int[] cl = run.get(pos);
                        for (int k = 0; k < hb.length; k++) {
                            int wb = hb[k] >= 4 ? 2 : 1;
                            for (int r = wb; r <= hb[k] - 1; r++) put(cl[0], sb[k] + r, cl[1], Palette.s(Blocks.GLASS));
                        }
                    }
                }
                st = e;
            }
        }
    }

    private void procHouse(House h, List<int[]> cells, Palette pal, BlockState wall) {
        Facade f = h.facade;
        int base = houseBase.get(h.id);
        int[] hb = storeys(h);
        int total = 0;
        for (int v : hb) total += v;
        int[] storeyBase = new int[hb.length];
        for (int k = 0, y = base; k < hb.length; k++) { storeyBase[k] = y; y += hb[k]; }
        int top = base + total;
        computeYields(h, cells, base, top);
        List<int[]> world = new ArrayList<>(cells.size());
        for (int[] cl : cells) world.add(new int[]{ox + cl[0], oz + cl[1]});
        h.builtCells = world; h.builtBase = base; h.builtTop = top;
        P q0 = h.q[0], q1 = h.q[1], q2 = h.q[2], q3 = h.q[3];
        P along = q1.sub(q0).norm(), out = h.outward();
        Direction outDir = Palette.dirOf(out), inDir = outDir.getOpposite();
        double bayW = h.frontage / f.bays;
        // facade cells: boundary cells along the front edge; window columns at bay centres
        HashMap<Long, Double> front = new HashMap<>();
        HashMap<Long, Boolean> rear = new HashMap<>();
        HashMap<Long, Double> side2 = new HashMap<>(); // corner houses: second street front
        Direction side2Dir = Palette.dirOf(q2.sub(q1).norm().perpL().mul(-1));
        double side2Len = q1.dist(q2);
        double[] bestBay = new double[f.bays];
        long[] bestBayCell = new long[f.bays];
        java.util.Arrays.fill(bestBay, 1e9);
        for (int[] cl : cells) {
            if (!isWall(cl[0], cl[1], h.id)) continue;
            P p = new P(cl[0], cl[1]);
            long key = ((long) cl[0] << 32) | (cl[1] & 0xffffffffL);
            if (Geom.distPointSeg(p, q0, q1) <= 1.25) {
                double tt = Geom.clamp(p.sub(q0).dot(along), 0, h.frontage);
                front.put(key, tt);
                int b = Math.min(f.bays - 1, (int) (tt / bayW));
                double dc = Math.abs(tt - (b + 0.5) * bayW);
                if (dc < bestBay[b]) { bestBay[b] = dc; bestBayCell[b] = key; }
            } else if (h.front2 && Geom.distPointSeg(p, q1, q2) <= 1.25) {
                side2.put(key, p.sub(q1).dot(q2.sub(q1).norm()));
            } else if (Geom.distPointSeg(p, q3, q2) <= 1.25) rear.put(key, true);
        }
        // tall, narrow windows (Dutch canal-house rhythm): exactly one window column per bay, at the bay centre
        HashMap<Long, Integer> windowBay = new HashMap<>();
        for (int b = 0; b < f.bays; b++) if (bestBay[b] < 1e8) windowBay.put(bestBayCell[b], b);
        long doorCell = f.doorBay < f.bays && bestBay[f.doorBay] < 1e8 ? bestBayCell[f.doorBay] : Long.MIN_VALUE;
        // side windows of corner houses: one column per bay of the second front
        HashMap<Long, Boolean> side2Win = new HashMap<>();
        if (!side2.isEmpty()) {
            int sb = Math.max(1, (int) Math.round(side2Len / Math.max(2.0, bayW)));
            double sw = side2Len / sb;
            double[] bd = new double[sb];
            long[] bc = new long[sb];
            java.util.Arrays.fill(bd, 1e9);
            for (var e : side2.entrySet()) {
                double tt = e.getValue();
                if (tt < 1 || tt > side2Len - 1) continue;
                int b = Math.min(sb - 1, (int) (tt / sw));
                double dc = Math.abs(tt - (b + 0.5) * sw);
                if (dc < bd[b]) { bd[b] = dc; bc[b] = e.getKey(); }
            }
            for (int b = 0; b < sb; b++) if (bd[b] < 1e8) side2Win.put(bc[b], true);
        }
        // any other wall facing a street, plaza or paving (side walls at block ends, infill sides, through-lot backs)
        // gets windows at a regular rhythm, so no street sees a blank wall
        {
            HashSet<Long> mineCells = new HashSet<>();
            for (int[] cl : cells) mineCells.add(key(cl[0], cl[1]));
            int[] rd0 = rearDoor.get(h.id);
            long rdKey = rd0 == null ? Long.MIN_VALUE : key(rd0[0], rd0[1]);
            for (int d = 0; d < 4; d++) {
                List<int[]> run = new ArrayList<>();
                for (int[] cl : cells) {
                    long kk = key(cl[0], cl[1]);
                    if (kk == rdKey || front.containsKey(kk) || side2.containsKey(kk) || !isWall(cl[0], cl[1], h.id)) continue;
                    int ni = cl[0] + DIRS[d][0], nj = cl[1] + DIRS[d][1];
                    if (!in(ni, nj) || mineCells.contains(key(ni, nj))) continue;
                    byte nk = kind[idx(ni, nj)];
                    if (!isStreetLike(nk) && nk != PAVED) continue;
                    run.add(cl);
                }
                if (run.size() < 3) continue;
                // order along the wall (perpendicular to d) and split into contiguous stretches
                final int dd = d;
                run.sort((x, y) -> DIRS[dd][0] != 0 ? Integer.compare(x[1], y[1]) : Integer.compare(x[0], y[0]));
                int st = 0;
                for (int e = 1; e <= run.size(); e++) {
                    boolean brk = e == run.size();
                    if (!brk) {
                        int[] pa = run.get(e - 1), pb = run.get(e);
                        int stepAlong = DIRS[dd][0] != 0 ? pb[1] - pa[1] : pb[0] - pa[0];
                        int stepAcross = DIRS[dd][0] != 0 ? Math.abs(pb[0] - pa[0]) : Math.abs(pb[1] - pa[1]);
                        brk = stepAlong > 1 || stepAcross > 1;
                    }
                    if (!brk) continue;
                    int L = e - st;
                    if (L >= 3) {
                        int nWin = Math.max(1, (int) Math.round((L - 2) / 3.0));
                        for (int w = 0; w < nWin; w++) {
                            int pos = st + 1 + (int) Math.round((w + 0.5) * (L - 2) / nWin - 0.5);
                            pos = Math.max(st + 1, Math.min(st + L - 2, pos));
                            int[] cl = run.get(pos);
                            long kk = key(cl[0], cl[1]);
                            side2.put(kk, 0.0);
                            side2Win.put(kk, true);
                            rear.remove(kk);
                        }
                        for (int u = st; u < e; u++) { long kk = key(run.get(u)[0], run.get(u)[1]); if (!side2.containsKey(kk)) { side2.put(kk, -1.0); rear.remove(kk); } }
                    }
                    st = e;
                }
            }
        }
        // gateway houses: an archway through the ground floor gives access to the shared garden
        HashSet<Long> tunnel = new HashSet<>();
        if (h.passage && doorCell != Long.MIN_VALUE) {
            P start = new P((int) (doorCell >> 32), (int) doorCell), inV = out.mul(-1);
            for (double d = 0; d < h.depth + 6; d += 0.5) {
                P q = start.add(inV.mul(d));
                int ti = (int) Math.round(q.x()), tj = (int) Math.round(q.y());
                if (!owns(ti, tj, HOUSE, h.id)) { if (d > 2) break; else continue; }
                tunnel.add(key(ti, tj));
            }
            doorCell = Long.MIN_VALUE;
        }
        int archTop = Math.min(3, hb[0] - 1);
        int[] rd = rearDoor.get(h.id);
        long rearDoorCell = rd == null ? Long.MIN_VALUE : key(rd[0], rd[1]);
        Direction rearDir = rd == null ? null : d2(DIRS[rd[2]][0], DIRS[rd[2]][1]);
        boolean commercial = !"residential".equals(h.use);
        // entrance flanked by painted wood: every shop, and some homes; shops add a painted sign band over it
        Block[] accent = Palette.accent(h);
        boolean accentDoor = commercial || ((h.id * 40503L + 17) % 1000) < c.d("use.accentDoorShare") * 1000;
        HashSet<Long> flank = new HashSet<>();
        double doorT = doorCell != Long.MIN_VALUE && front.containsKey(doorCell) ? front.get(doorCell) : Double.NaN;
        if (accentDoor && !Double.isNaN(doorT))
            for (var fe : front.entrySet()) {
                double dt = Math.abs(fe.getValue() - doorT);
                if (fe.getKey() != doorCell && dt > 0.4 && dt <= 1.6) flank.add(fe.getKey());
            }
        Block doorBlock = accentDoor ? accent[1] : pal.door;
        // reserved interior cells: just inside each door, the ladder and its access cell, the centre lantern
        HashSet<Long> reserved = new HashSet<>(tunnel);
        if (doorCell != Long.MIN_VALUE) reserved.add(key((int) (doorCell >> 32) + inDir.getStepX(), (int) doorCell + inDir.getStepZ()));
        if (rd != null) reserved.add(key(rd[0] - DIRS[rd[2]][0], rd[1] - DIRS[rd[2]][1]));
        int[] lamp = null;
        double ld = 1e9;
        for (int[] cl : cells) if (!isWall(cl[0], cl[1], h.id) && !reserved.contains(key(cl[0], cl[1]))) {
            double d = new P(cl[0], cl[1]).dist(h.c);
            if (d < ld) { ld = d; lamp = cl; }
        }
        if (lamp != null) reserved.add(key(lamp[0], lamp[1]));
        int[] ladder = hb.length >= 2 ? ladderCell(h, cells, reserved, windowBay, side2Win, doorCell, rearDoorCell) : null;
        if (ladder != null) {
            reserved.add(key(ladder[0], ladder[1]));
            reserved.add(key(ladder[0] - DIRS[ladder[2]][0], ladder[1] - DIRS[ladder[2]][1]));
        }
        // a glowstone wall lamp on the front, on a pier next to the door
        long wallLamp = Long.MIN_VALUE;
        if (c.d("mc.lampSpacing") > 0 && doorCell != Long.MIN_VALUE) {
            double bestD = 1e9;
            for (Long kf : front.keySet()) {
                if (windowBay.containsKey(kf) || kf == doorCell || flank.contains(kf)) continue;
                int fi = (int) (kf >> 32), fj = kf.intValue();
                int si = fi + outDir.getStepX(), sj = fj + outDir.getStepZ();
                if (!in(si, sj) || !isStreetLike(kind[idx(si, sj)])) continue;
                double d = new P(fi, fj).dist(new P((int) (doorCell >> 32), (int) doorCell));
                if (d < bestD) { bestD = d; wallLamp = kf; }
            }
        }
        int lampRel = commercial ? hb[0] - 1 : Math.min(3, hb[0] - 1);
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            long key = key(i, j);
            boolean bnd = isWall(i, j, h.id);
            boolean inTunnel = tunnel.contains(key);
            put(i, base, j, inTunnel ? Palette.s(Blocks.COBBLESTONE) : pal.floor.defaultBlockState());
            if (!bnd) {
                for (int k = 1; k < hb.length; k++) put(i, storeyBase[k], j, pal.floor.defaultBlockState());
                if (inTunnel) for (int y = base + 1; y <= base + archTop; y++) put(i, y, j, Palette.s(Blocks.AIR));
                continue;
            }
            boolean isFront = front.containsKey(key);
            Integer bay = windowBay.get(key);
            for (int y = base + 1; y <= top; y++) {
                int k = storeyOf(y, storeyBase);
                int rel = y - storeyBase[k];
                if (yielded(key, y, base)) { // the neighbour's wall stands here: this cell is part of the room
                    if (k > 0 && rel == 0) put(i, y, j, pal.floor.defaultBlockState());
                    continue;
                }
                int winTop = hb[k] - 1, winBottom = hb[k] >= 4 ? 2 : 1; // 3-block storeys: a 2-high window from the floor up
                BlockState st = (k == 0 && f.rustication && isFront) ? pal.rustic.defaultBlockState() : wall;
                if (isFront) {
                    double tt = front.get(key);
                    boolean pil = f.pilasters && k > 0 && f.bays > 1 && nearBayEdge(tt, bayW, h.frontage);
                    if (pil) st = pal.pilaster.defaultBlockState();
                    if (h.tier <= 2 && k > 0 && rel == 0 && bay == null) st = pal.trim.defaultBlockState();
                    if (y == top) st = pal.trim.defaultBlockState();
                    if (key == doorCell && k == 0) {
                        if (rel == 1) st = Palette.door(doorBlock, outDir, false);
                        else if (rel == 2) st = Palette.door(doorBlock, outDir, true);
                        else if (accentDoor && rel == 3) st = accent[0].defaultBlockState(); // painted lintel / shop sign
                        else if (rel == 3 && f.doorPediment) st = pal.trim.defaultBlockState();
                    } else if (k == 0 && flank.contains(key)) {
                        // painted wood posts either side of the entrance, joined by the sign band over the door
                        if (rel >= 1 && rel <= (commercial ? Math.max(3, hb[0] - 1) : 3)) st = accent[0].defaultBlockState();
                    } else if (k == 0 && commercial && bay != null && rel >= 1 && rel <= Math.max(2, hb[0] - 2)) {
                        // shop window: one tall window per bay (no wall of glass), with a painted stall-riser on wide fronts
                        st = rel == 1 && hb[0] >= 5 ? accent[0].defaultBlockState() : Palette.s(Blocks.GLASS);
                    } else if (bay != null && rel >= winBottom && rel <= winTop) {
                        st = Palette.s(Blocks.GLASS);
                    } else if (bay != null && rel == 0 && k > 0 && f.ornament > 0.15) {
                        st = pal.trim.defaultBlockState(); // lintel of the window below
                    } else if (bay != null && rel == winBottom - 1 && f.ornament > 0.3) {
                        st = pal.trim.defaultBlockState(); // sill
                    } else if (key == wallLamp && k == 0 && rel == lampRel) {
                        st = Palette.s(Blocks.GLOWSTONE);
                    }
                } else if (side2.containsKey(key)) {
                    if (side2Win.containsKey(key) && rel >= winBottom && rel <= winTop) st = Palette.s(Blocks.GLASS);
                    else if (y == top) st = pal.trim.defaultBlockState();
                } else if (key == rearDoorCell && k == 0 && (rel == 1 || rel == 2)) {
                    st = Palette.door(pal.door, rearDir, rel == 2);
                } else if (rear.containsKey(key) && key != rearDoorCell && (hash(i, j, 7) % 3) == 0 && rel >= winBottom && rel <= winTop) {
                    st = Palette.s(Blocks.GLASS);
                }
                if (inTunnel && k == 0 && rel >= 1 && rel <= archTop) st = Palette.s(Blocks.AIR);
                put(i, y, j, st);
            }
        }
        // balcony on the piano nobile of tier-2 houses, cantilevered over the street
        if (f.balconies && hb.length > 1) {
            for (var e : windowBay.entrySet()) {
                int b = e.getValue();
                if (Math.abs(b - (f.bays - 1) / 2.0) > 1) continue;
                int i = (int) (e.getKey() >> 32), j = e.getKey().intValue();
                int oi = i + outDir.getStepX(), oj = j + outDir.getStepZ();
                if (!in(oi, oj) || !isStreetLike(kind[idx(oi, oj)]) || lamps.containsKey(key(oi, oj))) continue;
                put(oi, storeyBase[1], oj, Palette.slab(Blocks.SMOOTH_STONE_SLAB, true));
                put(oi, storeyBase[1] + 1, oj, Palette.s(Blocks.IRON_BARS));
            }
        }
        // cornice: upside-down stairs projecting over the street on richer facades
        if (f.ornament > 0.3) {
            for (Long key : front.keySet()) {
                int i = (int) (key >> 32), j = key.intValue();
                int oi = i + outDir.getStepX(), oj = j + outDir.getStepZ();
                if (in(oi, oj) && isStreetLike(kind[idx(oi, oj)])) put(oi, top, oj, Palette.stair(pal.cornice, inDir, true));
            }
        }
        if (ladder != null) {
            Direction face = d2(-DIRS[ladder[2]][0], -DIRS[ladder[2]][1]); // ladder faces away from its wall
            int ladderTop = storeyBase[hb.length - 1] + 1;
            for (int y = base + 1; y <= ladderTop; y++) put(ladder[0], y, ladder[1], Palette.ladder(face));
        }
        furnish(h, cells, hb, storeyBase, reserved);
        if (c.b("mc.lanterns") && lamp != null)
            for (int k = 0; k < hb.length; k++) put(lamp[0], storeyBase[k] + 1, lamp[1], Blocks.LANTERN);
        roof(h, cells, pal, wall, top, front, rear);
    }

    /**
     * Ladder to the upper floors: an interior cell against a plain side or rear wall (not a door, window or the
     * front), with a free cell in front of it to step on. Returns {i, j, direction ordinal toward the wall}.
     */
    private int[] ladderCell(House h, List<int[]> cells, HashSet<Long> reserved, HashMap<Long, Integer> windowBay,
                             HashMap<Long, Boolean> side2Win, long doorCell, long rearDoorCell) {
        HashSet<Long> mine = new HashSet<>();
        for (int[] cl : cells) mine.add(key(cl[0], cl[1]));
        P out = h.outward();
        int[] best = null;
        double bestScore = -1e18;
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            if (isWall(i, j, h.id) || reserved.contains(key(i, j))) continue;
            for (int d = 0; d < 4; d++) {
                int wi = i + DIRS[d][0], wj = j + DIRS[d][1], ai = i - DIRS[d][0], aj = j - DIRS[d][1];
                long wk = key(wi, wj);
                if (!mine.contains(wk) || !isWall(wi, wj, h.id)) continue; // needs its own wall behind
                if (windowBay.containsKey(wk) || side2Win.containsKey(wk) || wk == doorCell || wk == rearDoorCell) continue;
                if (!mine.contains(key(ai, aj)) || isWall(ai, aj, h.id) || reserved.contains(key(ai, aj))) continue;
                double toFront = new P(DIRS[d][0], DIRS[d][1]).dot(out);
                double score = -2 * Math.max(0, toFront) - 0.1 * new P(i, j).dist(h.c) + (hash(i, j, 21) % 7) * 0.01;
                if (score > bestScore) { bestScore = score; best = new int[]{i, j, d}; }
            }
        }
        return best;
    }

    private static final Block[] JOB_SITES = {Blocks.LECTERN, Blocks.COMPOSTER, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE, Blocks.BARREL, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.LOOM, Blocks.STONECUTTER,
            Blocks.CAULDRON, Blocks.BREWING_STAND};

    /**
     * Interior by use, storey by storey. Furniture goes against the walls (the room ring), keeping the cells inside
     * the doors, the ladder and its step, and the centre free, so the plan stays walkable.
     * - residential: a bed on every storey (the ground floor kitchen gets a job block in some houses);
     * - mixed (shop below, home above): shop counter of barrels downstairs, beds upstairs;
     * - bookstore (standalone commercial): bookshelves along every wall, a lectern, no beds;
     * - restaurant: tables with chairs, a smoker and barrels downstairs; home upstairs.
     */
    private void furnish(House h, List<int[]> cells, int[] hb, int[] storeyBase, HashSet<Long> reserved) {
        List<int[]> ring = new ArrayList<>(), centre = new ArrayList<>();
        for (int[] cl : cells) {
            if (isWall(cl[0], cl[1], h.id) || reserved.contains(key(cl[0], cl[1]))) continue;
            boolean nearWall = false;
            for (int[] dd : DIRS) if (isWall(cl[0] + dd[0], cl[1] + dd[1], h.id) && owns(cl[0] + dd[0], cl[1] + dd[1], HOUSE, h.id)) nearWall = true;
            (nearWall ? ring : centre).add(cl);
        }
        String use = h.use == null ? "residential" : h.use;
        for (int k = 0; k < hb.length; k++) {
            int y = storeyBase[k] + 1;
            HashSet<Long> used = new HashSet<>();
            boolean ground = k == 0;
            switch (use) {
                case "bookstore" -> {
                    int n = 0;
                    for (int[] cl : ring) {
                        if ((hash(cl[0], cl[1], 31 + k) % 5) == 0) continue; // a few gaps so it does not feel like a vault
                        put(cl[0], y, cl[1], Blocks.BOOKSHELF);
                        if (hb[k] >= 4) put(cl[0], y + 1, cl[1], Blocks.BOOKSHELF);
                        used.add(key(cl[0], cl[1]));
                        n++;
                    }
                    if (!centre.isEmpty()) put(centre.get(0)[0], y, centre.get(0)[1], Blocks.LECTERN);
                }
                case "restaurant", "mixed" -> {
                    if (ground) {
                        if (use.equals("restaurant")) {
                            int tables = 0;
                            for (int[] cl : centre) {
                                if (tables >= 3 || ((cl[0] + cl[1]) & 1) != 0) continue;
                                put(cl[0], y, cl[1], Blocks.OAK_FENCE);
                                put(cl[0], y + 1, cl[1], Blocks.OAK_PRESSURE_PLATE);
                                used.add(key(cl[0], cl[1]));
                                tables++;
                            }
                            int fittings = 0;
                            for (int[] cl : ring) {
                                if (fittings >= 3) break;
                                put(cl[0], y, cl[1], fittings == 0 ? Palette.s(Blocks.SMOKER) : Palette.s(Blocks.BARREL));
                                used.add(key(cl[0], cl[1]));
                                fittings++;
                            }
                        } else {
                            int counter = 0;
                            for (int[] cl : ring) {
                                if (counter >= 4) break;
                                put(cl[0], y, cl[1], Blocks.BARREL);
                                used.add(key(cl[0], cl[1]));
                                counter++;
                            }
                        }
                    } else bed(ring, centre, used, y);
                }
                default -> { // residential
                    bed(ring, centre, used, y);
                    if (ground && (h.id % 3 == 0 || h.tier == 2))
                        for (int[] cl : ring) {
                            if (used.contains(key(cl[0], cl[1]))) continue;
                            put(cl[0], y, cl[1], JOB_SITES[(int) (hash(h.id, 0, 11) % JOB_SITES.length)].defaultBlockState());
                            break;
                        }
                }
            }
        }
    }

    /** One bed against a wall: two adjacent free ring/centre cells. */
    private void bed(List<int[]> ring, List<int[]> centre, HashSet<Long> used, int y) {
        HashSet<Long> free = new HashSet<>();
        for (int[] cl : ring) free.add(key(cl[0], cl[1]));
        for (int[] cl : centre) free.add(key(cl[0], cl[1]));
        free.removeAll(used);
        for (int[] a : ring) {
            if (used.contains(key(a[0], a[1]))) continue;
            for (int[] d : DIRS) {
                long b = key(a[0] + d[0], a[1] + d[1]);
                if (!free.contains(b)) continue;
                Direction f = d2(d[0], d[1]);
                put(a[0], y, a[1], Palette.bed(f, false));
                put(a[0] + d[0], y, a[1] + d[1], Palette.bed(f, true));
                used.add(key(a[0], a[1])); used.add(b);
                return;
            }
        }
    }

    private static Direction d2(int dx, int dz) {
        return dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int storeyOf(int y, int[] storeyBase) {
        int k = 0;
        while (k + 1 < storeyBase.length && y >= storeyBase[k + 1]) k++;
        return k;
    }

    private static boolean nearBayEdge(double tt, double bayW, double frontage) {
        double r = tt % bayW;
        return (r < 0.5 || bayW - r < 0.5) && tt > 0.5 && tt < frontage - 0.5;
    }

    private void roof(House h, List<int[]> cells, Palette pal, BlockState wall, int top, HashMap<Long, Double> front, HashMap<Long, Boolean> rear) {
        boolean uniform = !c.s("mc.roofForm").equals("auto");
        String roof = uniform ? "steep-eaves" : h.facade.roof;
        int maxRise = c.i("mc.maxRoofRise");
        P q0 = h.q[0], q1 = h.q[1], q2 = h.q[2], q3 = h.q[3];
        if (!uniform && (h.tier == 4 || roof.equals("parapet"))) {
            for (int[] cl : cells) {
                boolean bnd = isWall(cl[0], cl[1], h.id);
                if (bnd && h.tier != 4) put(cl[0], top + 1, cl[1], pal.trim.defaultBlockState());
                else put(cl[0], top + 1, cl[1], Palette.slab(pal.roofSlab, false));
            }
            return;
        }
        boolean gableFront = roof.equals("front-gable");
        P along = q1.sub(q0).norm(), out = h.outward();
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            P p = new P(i, j);
            long key = ((long) i << 32) | (j & 0xffffffffL);
            double da, db;
            Direction towardA, towardB; // direction from side A toward the ridge, and from side B
            if (gableFront) { da = distLine(p, q0, q3); db = distLine(p, q1, q2); towardA = Palette.dirOf(along); towardB = Palette.dirOf(along.mul(-1)); }
            else { da = distLine(p, q0, q1); db = distLine(p, q3, q2); towardA = Palette.dirOf(out.mul(-1)); towardB = Palette.dirOf(out); }
            int rise = (int) Math.floor(Math.min(da, db) + 0.5);
            boolean capped = rise >= maxRise;
            rise = Math.min(rise, maxRise);
            boolean bnd = isWall(i, j, h.id);
            boolean gableWall = bnd && (gableFront ? (front.containsKey(key) || rear.containsKey(key)) : !(front.containsKey(key) || rear.containsKey(key)));
            for (int y = top + 1; y <= top + rise; y++) put(i, y, j, gableWall ? wall : pal.roofFull.defaultBlockState());
            BlockState cap = capped || Math.abs(da - db) < 1.0 ? Palette.slab(pal.roofSlab, false) : Palette.stair(pal.roofStair, da < db ? towardA : towardB, false);
            put(i, top + rise + 1, j, cap);
        }
    }

    // ------------------------------------------------------------------ monuments

    private void landmark(Landmark l) {
        List<int[]> cells = new ArrayList<>();
        raster(l.fp, (i, j) -> { if (owns(i, j, LANDMARK, l.id)) cells.add(new int[]{i, j}); });
        if (cells.isEmpty()) return;
        P f0 = l.fp[0], U = l.fp[1].sub(f0).norm(), V = l.fp[3].sub(f0).norm();
        double Wd = l.width, Dd = l.depth;
        int base = landmarkBase(l), Ht = (int) Math.round(l.height);
        if (l.free && l.center != null && l.center.plaza != null) plazaTrees(l.center.plaza);
        Palette pal = Palette.MONUMENT;
        Direction front = Palette.dirOf(V.mul(-1));
        Direction uDir = Palette.dirOf(U);
        BlockState stone = pal.wall(0), trim = pal.trim.defaultBlockState(), roofB = pal.roofFull.defaultBlockState();
        BlockState copper = Palette.s(Blocks.WAXED_WEATHERED_CUT_COPPER), glass = Palette.s(Blocks.LIGHT_GRAY_STAINED_GLASS);
        switch (l.type) {
            case "church" -> { church(l, cells, f0, U, V, base, Ht, pal); return; }
            case "forum" -> { forum(l, cells, f0, U, V, base, pal); return; }
            case "colosseum" -> { colosseum(l, cells, f0, U, V, base, Ht, pal); return; }
            default -> { }
        }
        boolean cathedral = l.type.endsWith("cathedral");
        int body = cathedral ? (int) Geom.clamp(Math.round(Math.min(0.42 * Ht, 0.72 * Wd)), 8, Ht - 6)
                : (int) Geom.clamp(Math.round(Math.min(0.45 * Ht, 14)), 7, Ht - 5);
        int ts = (int) Math.max(4, Math.round((cathedral ? 0.21 : 0.24) * Wd));
        int towerTop = (int) Math.round((cathedral ? 0.86 : 0.9) * Ht);
        P domeC = f0.add(U.mul(Wd / 2)).add(V.mul(0.62 * Dd));
        int rd = (int) Math.max(3, Math.round(0.23 * Wd)), drumH = (int) Math.max(2, Math.round(0.12 * Ht));
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            P p = new P(i, j);
            double u = p.sub(f0).dot(U), v = p.sub(f0).dot(V);
            boolean bnd = isBoundary(i, j, LANDMARK, l.id);
            put(i, base, j, pal.floor.defaultBlockState());
            boolean tower = cathedral ? (v < ts && (u < ts || u > Wd - ts)) : (v < ts && Math.abs(u - Wd / 2) < ts / 2.0);
            if (tower) {
                double tu = cathedral ? (u < ts ? u : Wd - u) : u - (Wd / 2 - ts / 2.0);
                boolean tb = tu < 1 || tu > ts - 1.5 || v < 1 || v > ts - 1.5;
                if (tb) for (int y = base + 1; y <= base + towerTop; y++) {
                    boolean belfry = y > base + towerTop - 5 && y < base + towerTop - 1 && Math.abs(tu - ts / 2.0) < 1 + 0.01 && (v < 1 || v > ts - 1.5 || tu < 1 || tu > ts - 1.5);
                    put(i, y, j, belfry ? Palette.s(Blocks.AIR) : stone);
                }
                int S = Math.max(1, Ht - towerTop);
                for (int sIdx = 1; sIdx <= S; sIdx++) {
                    double inset = sIdx * (ts / 2.0) / (S + 1);
                    if (tu >= inset - 0.01 && tu <= ts - 1 - inset + 0.01 && v >= inset - 0.01 && v <= ts - 1 - inset + 0.01)
                        put(i, base + towerTop + sIdx, j, roofB);
                }
                continue;
            }
            if (cathedral) {
                if (v < 3) { // portico: columns, entablature, pediment
                    int colH = (int) Math.round(body * 0.7);
                    if (v < 1.5 && Math.round(u) % 3 == 0) for (int y = base + 1; y <= base + colH; y++) put(i, y, j, Palette.s(Blocks.QUARTZ_PILLAR));
                    put(i, base + colH + 1, j, trim);
                    if (v < 1) {
                        int rise = (int) Math.round((Wd / 2 - Math.abs(u - Wd / 2)) * 0.35);
                        for (int y = base + colH + 2; y <= base + colH + 1 + rise; y++) put(i, y, j, stone);
                    }
                    continue;
                }
                boolean frontWall = v < 4;
                if (bnd || frontWall) {
                    for (int y = base + 1; y <= base + body; y++) {
                        BlockState st = stone;
                        if (frontWall && Math.abs(u - Wd / 2) <= 1.5 && y <= base + 5) st = Palette.s(Blocks.AIR);
                        else if (!frontWall && ((int) Math.floor(v)) % 4 == 2 && y >= base + 4 && y <= base + body - 3) st = glass;
                        put(i, y, j, st);
                    }
                }
                double r = Math.min(u, Wd - u);
                int rise = (int) Math.floor(r * 0.6);
                for (int y = base + body + 1; y <= base + body + rise; y++) put(i, y, j, roofB);
                put(i, base + body + rise + 1, j, Math.abs(u - Wd / 2) < 1 ? Palette.slab(pal.roofSlab, false)
                        : Palette.stair(pal.roofStair, u < Wd / 2 ? uDir : uDir.getOpposite(), false));
                double dd = p.dist(domeC);
                int ridge = (int) Math.floor(Wd / 2 * 0.6);
                if (dd <= rd + 0.5) {
                    int y0 = base + body + ridge + 1;
                    if (dd > rd - 1) for (int y = y0; y <= base + body + drumH + ridge; y++) put(i, y, j, ((y - y0) % 3 == 1 && dd > rd - 0.5) ? glass : stone);
                    int dome0 = base + body + drumH + ridge + 1;
                    for (int dy = 0; dy <= rd; dy++) {
                        double rr = Math.sqrt(Math.max(0, rd * rd - dy * dy));
                        if (dd <= rr + 0.5 && dd > rr - 1.2) put(i, dome0 + dy, j, copper);
                    }
                    if (dd < 0.75) for (int y = dome0 + rd + 1; y <= Math.min(base + Ht - 1, dome0 + rd + 3); y++) put(i, y, j, trim);
                }
            } else {
                boolean court = u > 0.28 * Wd && u < 0.72 * Wd && v > 0.3 * Dd && v < 0.78 * Dd;
                if (court) { put(i, base, j, Palette.s(Blocks.GRASS_BLOCK)); continue; }
                boolean courtEdge = false;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    P q = new P(i + d[0], j + d[1]);
                    double uu = q.sub(f0).dot(U), vv = q.sub(f0).dot(V);
                    if (uu > 0.28 * Wd && uu < 0.72 * Wd && vv > 0.3 * Dd && vv < 0.78 * Dd) courtEdge = true;
                }
                if (bnd || courtEdge) {
                    boolean sideWall = Math.abs(u) < 1 || Math.abs(u - Wd) < 1.5;
                    double param = sideWall ? v : u;
                    BlockState hallGlass = Palette.s(Blocks.GLASS);
                    for (int y = base + 1; y <= base + body; y++) {
                        int m4 = Math.floorMod((int) Math.floor(param), 4);
                        BlockState st = stone;
                        if (bnd && (m4 == 1 || m4 == 2) && y <= base + 3) st = Palette.s(Blocks.AIR); // ground-floor arcade
                        else if ((m4 == 1 || m4 == 2) && (y - base) % 5 == 2 && y > base + 4) st = hallGlass;
                        put(i, y, j, st);
                    }
                    put(i, base + body + 1, j, trim);
                } else put(i, base + body + 1, j, Palette.slab(pal.roofSlab, false));
            }
        }
        // lanterns inside the monument
        if (c.b("mc.lanterns")) {
            P mid = f0.add(U.mul(Wd / 2)).add(V.mul(Dd / 2));
            put((int) Math.round(mid.x()), base + 1, (int) Math.round(mid.y()), Blocks.LANTERN);
        }
    }

    /** A parish church: a nave with tall windows and a pitched roof, one tower with a spire over the west door. */
    private void church(Landmark l, List<int[]> cells, P f0, P U, P V, int base, int Ht, Palette pal) {
        double Wd = l.width, Dd = l.depth;
        BlockState stone = pal.wall(0), trim = pal.trim.defaultBlockState(), glass = Palette.s(Blocks.LIGHT_GRAY_STAINED_GLASS);
        Direction uDir = Palette.dirOf(U);
        int body = (int) Geom.clamp(Math.round(0.75 * Wd), 6, 10);
        int ts = (int) Math.max(4, Math.round(0.4 * Wd)) | 1; // odd, so the spire has a middle
        int towerTop = (int) Math.max(body + 6, Math.round(0.7 * Ht));
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            P p = new P(i, j);
            double u = p.sub(f0).dot(U), v = p.sub(f0).dot(V);
            boolean bnd = isBoundary(i, j, LANDMARK, l.id);
            put(i, base, j, pal.floor.defaultBlockState());
            double tu = u - (Wd - ts) / 2.0;
            if (v < ts && tu >= 0 && tu < ts) { // tower over the door
                boolean tb = tu < 1 || tu >= ts - 1 || v < 1 || v >= ts - 1;
                if (tb) for (int y = base + 1; y <= base + towerTop; y++) {
                    BlockState st = stone;
                    if (v < 1 && Math.abs(tu - (ts - 1) / 2.0) < 0.6 && y <= base + 3) st = Palette.s(Blocks.AIR); // door
                    if (y > base + towerTop - 4 && y < base + towerTop && Math.abs(tu - (ts - 1) / 2.0) < 0.6) st = Palette.s(Blocks.AIR); // belfry
                    put(i, y, j, st);
                }
                int S = Math.max(2, Ht - towerTop);
                for (int k = 1; k <= S; k++) {
                    double inset = k * (ts / 2.0) / (S + 1);
                    if (tu >= inset - 0.01 && tu <= ts - 1 - inset + 0.01 && v >= inset - 0.01 && v <= ts - 1 - inset + 0.01)
                        put(i, base + towerTop + k, j, pal.roofFull.defaultBlockState());
                }
                continue;
            }
            if (bnd) for (int y = base + 1; y <= base + body; y++) {
                boolean side = Math.min(u, Wd - u) < 1.5;
                BlockState st = side && Math.floorMod((int) Math.floor(v), 4) == 2 && y >= base + 3 && y <= base + body - 2 ? glass : stone;
                put(i, y, j, st);
            }
            put(i, base + body + 1, j, bnd ? trim : stone);
            double rr = Math.min(u, Wd - u);
            int rise = (int) Math.floor(rr * 0.7);
            for (int y = base + body + 2; y <= base + body + 1 + rise; y++) put(i, y, j, pal.roofFull.defaultBlockState());
            put(i, base + body + 2 + rise, j, Math.abs(u - Wd / 2) < 1 ? Palette.slab(pal.roofSlab, false)
                    : Palette.stair(pal.roofStair, u < Wd / 2 ? uDir : uDir.getOpposite(), false));
        }
        if (c.b("mc.lanterns")) { P mid = f0.add(U.mul(Wd / 2)).add(V.mul(Dd * 0.6)); put((int) Math.round(mid.x()), base + 1, (int) Math.round(mid.y()), Blocks.LANTERN); }
    }

    /** A forum: a colonnaded portico round an open paved court, with a small temple at the far end. */
    private void forum(Landmark l, List<int[]> cells, P f0, P U, P V, int base, Palette pal) {
        double Wd = l.width, Dd = l.depth;
        BlockState stone = pal.wall(0), trim = pal.trim.defaultBlockState(), pillar = Palette.s(Blocks.QUARTZ_PILLAR);
        Direction uDir = Palette.dirOf(U);
        int colH = 6, porch = 3;
        double t0 = 0.28 * Wd, t1 = 0.72 * Wd, tv0 = 0.66 * Dd, tv1 = Dd - porch - 1; // temple
        int tBody = 8;
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            P p = new P(i, j);
            double u = p.sub(f0).dot(U), v = p.sub(f0).dot(V);
            double edge = Math.min(Math.min(u, Wd - 1 - u), Math.min(v, Dd - 1 - v));
            boolean bnd = isBoundary(i, j, LANDMARK, l.id);
            put(i, base, j, edge < porch + 0.5 ? Palette.s(Blocks.POLISHED_ANDESITE) : Palette.s(Blocks.SMOOTH_STONE));
            if (edge < porch + 0.5) { // portico: outer and inner colonnades under a flat roof
                boolean outer = bnd, inner = edge >= porch - 0.5;
                double along = Math.min(Math.min(u, Wd - 1 - u), Math.min(v, Dd - 1 - v)) == Math.min(u, Wd - 1 - u) ? v : u;
                boolean col = (outer || inner) && Math.floorMod((int) Math.round(along), 3) == 0;
                boolean entrance = v < porch + 0.5 && Math.abs(u - (Wd - 1) / 2.0) < 2; // open front in the middle
                if (col && !(entrance && inner)) for (int y = base + 1; y <= base + colH; y++) put(i, y, j, pillar);
                put(i, base + colH + 1, j, (outer || inner) ? trim : Palette.slab(Blocks.SMOOTH_QUARTZ_SLAB, false));
                if (outer) put(i, base + colH + 2, j, Palette.slab(Blocks.SMOOTH_QUARTZ_SLAB, false));
                continue;
            }
            if (u >= t0 && u <= t1 && v >= tv0 - porch && v <= tv1) { // temple on a podium
                put(i, base + 1, j, stone);
                if (v < tv0) { // its own portico: columns and pediment
                    if (v < tv0 - porch + 1 && Math.floorMod((int) Math.round(u - t0), 2) == 0) for (int y = base + 2; y <= base + tBody; y++) put(i, y, j, pillar);
                } else {
                    boolean tb = u < t0 + 1 || u > t1 - 1 || v > tv1 - 1;
                    if (tb) for (int y = base + 2; y <= base + tBody; y++) put(i, y, j, stone);
                }
                put(i, base + tBody + 1, j, trim);
                double tm = (t0 + t1) / 2, rr = (t1 - t0) / 2 - Math.abs(u - tm);
                int rise = (int) Math.floor(rr * 0.45);
                for (int y = base + tBody + 2; y <= base + tBody + 1 + rise; y++) put(i, y, j, stone);
                put(i, base + tBody + 2 + rise, j, Math.abs(u - tm) < 1 ? Palette.slab(Blocks.SMOOTH_QUARTZ_SLAB, false)
                        : Palette.stair(Blocks.SMOOTH_QUARTZ_STAIRS, u < tm ? uDir : uDir.getOpposite(), false));
            }
        }
    }

    /**
     * A colosseum: an oval of three tiers of arches round tiered seating and a sand arena, entered through the arches
     * and through gates at either end of its long axis.
     */
    private void colosseum(Landmark l, List<int[]> cells, P f0, P U, P V, int base, int Ht, Palette pal) {
        double a = l.width / 2, b = l.depth / 2;
        P c0 = f0.add(U.mul(a)).add(V.mul(b));
        BlockState stone = pal.wall(0), trim = pal.trim.defaultBlockState(), seat = Palette.s(Blocks.STONE_BRICKS);
        int tier = Math.max(4, Ht / 3), wallTop = 3 * tier;
        double arena = 0.52; // the arena's share of the radii
        int arches = Math.max(16, (int) Math.round(Math.PI * (a + b) / 3.5));
        for (int[] cl : cells) {
            int i = cl[0], j = cl[1];
            P p = new P(i, j).sub(c0);
            double u = p.dot(U), v = p.dot(V);
            double e = Math.sqrt((u / a) * (u / a) + (v / b) * (v / b));
            if (e > 1.0) continue;
            double ang = Math.atan2(v / b, u / a);
            boolean archCol = Math.floorMod((int) Math.floor((ang + Math.PI) / (2 * Math.PI) * arches * 2), 2) == 0;
            boolean gate = Math.abs(v) < 2 && Math.abs(u) > a * arena; // gates on the long axis
            if (e > 0.9) { // outer wall: tiers of arches, an attic storey on top
                put(i, base, j, stone);
                for (int y = base + 1; y <= base + wallTop + 2; y++) {
                    int rel = y - base, t = (rel - 1) / tier, r = (rel - 1) % tier;
                    BlockState st = stone;
                    if (t < 3 && archCol && r >= (t == 0 ? 0 : 1) && r <= tier - 2) st = Palette.s(Blocks.AIR);
                    if (t < 3 && r == tier - 1) st = trim;
                    if (gate && rel <= 4) st = Palette.s(Blocks.AIR);
                    put(i, y, j, st);
                }
                continue;
            }
            if (e > arena) { // seating rising from the arena wall to the outer wall, a walkway behind it
                double f = (e - arena) / (0.9 - arena);
                int h = 2 + (int) Math.floor(f * (wallTop - 3));
                put(i, base, j, stone);
                for (int y = base + 1; y <= base + h; y++) put(i, y, j, gate && y <= base + 4 ? Palette.s(Blocks.AIR) : seat);
                if (gate) for (int y = base + 1; y <= base + 4; y++) put(i, y, j, Palette.s(Blocks.AIR));
                continue;
            }
            put(i, base, j, Palette.s(Blocks.SAND)); // arena
            if (e > arena - 0.06) put(i, base + 1, j, trim); // low arena wall
        }
    }

    /**
     * Large trees round a free-standing monument's square, set in from its edge and clear of the monument and its
     * front, so the oversized square keeps a human scale.
     */
    private void plazaTrees(Plaza p) {
        Landmark lm = null;
        for (Landmark l : m.landmarks) if (l.free && l.center != null && l.center.plaza == p) lm = l;
        if (lm == null || p.poly.size() < 3) return;
        // walk the square's outline and step 6 m in from each side (works for irregular merged squares too)
        List<P> ring = p.poly;
        double sgn = Geom.signedArea(ring) >= 0 ? 1 : -1;
        P[] keep = Geom.expandRect(lm.fp, 5.0);
        double sp = 9.0, acc = sp / 2;
        java.util.List<P> planted = new ArrayList<>();
        for (int k = 0; k < ring.size(); k++) {
            P a = ring.get(k), b = ring.get((k + 1) % ring.size());
            double len = a.dist(b);
            if (len < 1e-6) continue;
            P inward = b.sub(a).norm().perpL().mul(sgn);
            for (; acc < len; acc += sp) {
                P q = a.lerp(b, acc / len).add(inward.mul(6.0));
                if (!Geom.pointInPoly(q, ring)) continue;
                double edge = 1e9;
                for (int e2 = 0; e2 < ring.size(); e2++) edge = Math.min(edge, Geom.distPointSeg(q, ring.get(e2), ring.get((e2 + 1) % ring.size())));
                if (edge < 5.0) continue;
                boolean crowded = false;
                for (P o : planted) if (o.dist(q) < 7.5) { crowded = true; break; }
                if (crowded) continue;
                if (Geom.pointInPoly(q, java.util.Arrays.asList(keep))) continue;
                if (q.sub(lm.front).dot(lm.axisDir) > 0 && Geom.distPointSeg(q, lm.front, lm.front.add(lm.axisDir.mul(40))) < 6) continue; // the view of the front
                bigTree((int) Math.round(q.x()), (int) Math.round(q.y()));
                planted.add(q);
            }
            acc -= len;
        }
    }

    /** A large dark-oak-like tree: 2x2 trunk on a grass bed, a broad canopy. */
    /** Grid cells of the large plaza trees (their canopies reach about 4 blocks out). */
    final List<int[]> bigTrees = new ArrayList<>();

    private void bigTree(int i, int j) {
        for (int dj = -1; dj <= 2; dj++) for (int di = -1; di <= 2; di++)
            if (!in(i + di, j + dj) || kind[idx(i + di, j + dj)] != PLAZA || lamps.containsKey(key(i + di, j + dj))) return;
        bigTrees.add(new int[]{i, j});
        int lv = lvl[idx(i, j)];
        for (int dj = -1; dj <= 2; dj++) for (int di = -1; di <= 2; di++) put(i + di, lv, j + dj, Blocks.GRASS_BLOCK);
        int h = 7 + (int) (hash(i, j, 41) % 3);
        for (int y = lv + 1; y <= lv + h; y++) for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) put(i + di, y, j + dj, Blocks.DARK_OAK_LOG);
        double cx = i + 0.5, cz = j + 0.5;
        for (int dy = -2; dy <= 3; dy++) for (int dj = -5; dj <= 6; dj++) for (int di = -5; di <= 6; di++) {
            double dx = i + di - cx, dz = j + dj - cz;
            double rr = (dx * dx + dz * dz) / 25.0 + (dy - 0.5) * (dy - 0.5) / 9.0;
            if (rr > 1.0) continue;
            if (dy <= 0 && di >= 0 && di <= 1 && dj >= 0 && dj <= 1) continue; // trunk
            put(i + di, lv + h + dy, j + dj, Palette.leaves());
        }
    }

    // ------------------------------------------------------------------ plazas and gardens

    private void fountain(Plaza p) {
        P pc = p.center();
        for (Landmark l : m.landmarks) if (l.free && l.center != null && l.center.plaza == p) pc = l.front.add(l.axisDir.mul(8)); // in front of the monument
        int ci = (int) Math.round(pc.x()), cj = (int) Math.round(pc.y());
        int lv = in(ci, cj) && kind[idx(ci, cj)] == PLAZA ? lvl[idx(ci, cj)] : nodeLevel(p.node);
        for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
            int i = ci + di, j = cj + dj;
            if (!in(i, j) || kind[idx(i, j)] != PLAZA) return;
        }
        for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
            int i = ci + di, j = cj + dj;
            put(i, lv, j, Blocks.STONE_BRICKS);
            if (Math.max(Math.abs(di), Math.abs(dj)) == 2) put(i, lv + 1, j, Blocks.STONE_BRICKS);
            else if (di == 0 && dj == 0) {
                put(i, lv + 1, j, Blocks.STONE_BRICKS);
                put(i, lv + 2, j, Blocks.STONE_BRICKS);
                put(i, lv + 3, j, Blocks.BELL); // village meeting point
            } else put(i, lv + 1, j, Blocks.WATER);
            if (c.b("mc.lanterns") && Math.abs(di) == 2 && Math.abs(dj) == 2) put(i, lv + 2, j, Blocks.LANTERN);
        }
    }

    // ------------------------------------------------------------------ cemeteries

    /** Surface blocks (paths, grave plots, mausoleum floors) and what stands on each cemetery cell, from level + 1 up. */
    public final HashMap<Long, BlockState> cemSurface = new HashMap<>();
    public final HashMap<Long, BlockState[]> cemDecor = new HashMap<>();
    /** Mausoleums: {centre i, centre j, floor level, door side (0 north, 1 east, 2 south, 3 west), cemetery id}. */
    final List<int[]> mausoleums = new ArrayList<>();
    /** Each cemetery's gate: id -> {i, j, level}. */
    public final HashMap<Integer, int[]> cemeteryGates = new HashMap<>();

    /** One cemetery's planned layout (kept on the Cemetery, frozen once villagers build it). */
    record CemPlan(HashMap<Long, BlockState> surface, HashMap<Long, BlockState[]> decor, List<int[]> mausoleums, int[] gate, HashMap<Long, Integer> levels, boolean alongX) {}

    /** Which way each cemetery's rows of graves run (by cemetery id): along x, or along z. */
    public final HashMap<Integer, Boolean> cemAlong = new HashMap<>();

    private void planCemeteries() {
        cemSurface.clear(); cemDecor.clear(); mausoleums.clear(); cemeteryGates.clear(); cemAlong.clear();
        for (Cemetery cm : m.cemeteries) {
            if (cm.plan instanceof CemPlan fp) { applyPlan(cm, fp); continue; }
            HashMap<Long, BlockState> s0 = new HashMap<>(cemSurface);
            HashMap<Long, BlockState[]> d0 = new HashMap<>();
            for (var e : cemDecor.entrySet()) d0.put(e.getKey(), e.getValue().clone());
            int mz0 = mausoleums.size();
            HashMap<Long, Integer> lv0 = new HashMap<>();
            raster(cm.poly, (i, j) -> lv0.put(key(i, j), lvl[idx(i, j)]));
            planCemetery(cm);
            // what this cemetery added, kept as its plan
            HashMap<Long, BlockState> sp = new HashMap<>();
            for (var e : cemSurface.entrySet()) if (!e.getValue().equals(s0.get(e.getKey()))) sp.put(e.getKey(), e.getValue());
            HashMap<Long, BlockState[]> dp = new HashMap<>();
            for (var e : cemDecor.entrySet()) if (!java.util.Arrays.equals(e.getValue(), d0.get(e.getKey()))) dp.put(e.getKey(), e.getValue().clone());
            HashMap<Long, Integer> lp = new HashMap<>();
            for (var e : lv0.entrySet()) { int i = (int) (e.getKey() >> 32), j = (int) (long) e.getKey().intValue(); if (lvl[idx(i, j)] != e.getValue()) lp.put(e.getKey(), lvl[idx(i, j)]); }
            cm.lastPlan = new CemPlan(sp, dp, new ArrayList<>(mausoleums.subList(mz0, mausoleums.size())), cemeteryGates.get(cm.id), lp, cemAlong.getOrDefault(cm.id, true));
        }
    }

    private void applyPlan(Cemetery cm, CemPlan p) {
        cemSurface.putAll(p.surface());
        for (var e : p.decor().entrySet()) cemDecor.put(e.getKey(), e.getValue().clone());
        for (int[] mz : p.mausoleums()) { int[] c2 = mz.clone(); c2[4] = cm.id; mausoleums.add(c2); }
        if (p.gate() != null) cemeteryGates.put(cm.id, p.gate());
        cemAlong.put(cm.id, p.alongX());
        for (var e : p.levels().entrySet()) {
            int i = (int) (e.getKey() >> 32), j = (int) (long) e.getKey().intValue();
            if (in(i, j)) lvl[idx(i, j)] = e.getValue();
        }
        // the frozen layout stands on what is now classified as this cemetery's ground
        raster(cm.poly, (i, j) -> { if (p.decor().containsKey(key(i, j)) && (kind[idx(i, j)] == PAVED || kind[idx(i, j)] == GARDEN)) kind[idx(i, j)] = CEMETERY; });
    }

    private void decor(int i, int j, int h, BlockState st) {
        long kk = key(i, j);
        BlockState[] a = cemDecor.get(kk);
        if (a == null || a.length <= h) {
            BlockState[] b = new BlockState[Math.max(h + 1, a == null ? 0 : a.length)];
            if (a != null) System.arraycopy(a, 0, b, 0, a.length);
            a = b;
            cemDecor.put(kk, a);
        }
        a[h] = st;
    }

    private static int[] turn(int x, int z, int q) {
        for (int n = 0; n < q; n++) { int t = x; x = -z; z = t; }
        return new int[]{x, z};
    }

    /**
     * A cemetery's layout: a wall round the block with a gate on the street nearest its middle (pillars with lanterns),
     * a gravel alley from the gate across the block and a cross alley, mausoleums facing the alleys (one per 450 m2 or
     * so, up to four), rows of headstones with their plots between narrow walks, and a few cypresses.
     */
    private void planCemetery(Cemetery cm) {
        List<int[]> cells = new ArrayList<>();
        HashSet<Long> inside = new HashSet<>();
        raster(cm.poly, (i, j) -> { if (in(i, j) && kind[idx(i, j)] == CEMETERY) { cells.add(new int[]{i, j}); inside.add(key(i, j)); } });
        if (cells.size() < 30) return;
        HashSet<Long> wall = new HashSet<>(), path = new HashSet<>(), reserved = new HashSet<>();
        for (int[] cc : cells) for (int[] d : DIRS) if (!inside.contains(key(cc[0] + d[0], cc[1] + d[1]))) { wall.add(key(cc[0], cc[1])); break; }
        // the gate: a wall cell on a street, nearest the middle
        P cen = cm.center();
        int[] gate = null;
        int gd = -1;
        double bd = 1e18;
        for (int[] cc : cells) {
            if (!wall.contains(key(cc[0], cc[1]))) continue;
            for (int d = 0; d < 4; d++) {
                int oi = cc[0] + DIRS[d][0], oj = cc[1] + DIRS[d][1];
                if (!in(oi, oj) || !isStreetLike(kind[idx(oi, oj)])) continue;
                double dd = cen.dist(new P(cc[0], cc[1]));
                if (dd < bd) { bd = dd; gate = cc; gd = d; }
            }
        }
        if (gate == null) return;
        int ox0 = DIRS[gd][0], oz0 = DIRS[gd][1], ax = -oz0, az = ox0; // out through the gate; along the wall
        for (int t = -1; t <= 1; t++) { long g = key(gate[0] + ax * t, gate[1] + az * t); wall.remove(g); path.add(g); }
        for (int t : new int[]{-2, 2}) {
            int pi = gate[0] + ax * t, pj = gate[1] + az * t;
            if (!wall.contains(key(pi, pj))) continue;
            wall.remove(key(pi, pj));
            reserved.add(key(pi, pj));
            decor(pi, pj, 0, Palette.s(Blocks.STONE_BRICKS)); decor(pi, pj, 1, Palette.s(Blocks.STONE_BRICKS));
            decor(pi, pj, 2, Palette.s(Blocks.CHISELED_STONE_BRICKS)); decor(pi, pj, 3, Palette.s(Blocks.LANTERN));
        }
        cemeteryGates.put(cm.id, new int[]{gate[0], gate[1], lvl[idx(gate[0], gate[1])]});
        // the main alley straight in from the gate, and a cross alley half way along it
        List<int[]> main = new ArrayList<>();
        for (int t = 1; ; t++) {
            int pi = gate[0] - ox0 * t, pj = gate[1] - oz0 * t;
            if (!inside.contains(key(pi, pj)) || wall.contains(key(pi, pj))) break;
            main.add(new int[]{pi, pj});
            path.add(key(pi, pj));
            if (inside.contains(key(pi + ax, pj + az)) && !wall.contains(key(pi + ax, pj + az))) path.add(key(pi + ax, pj + az));
        }
        List<int[][]> sides = new ArrayList<>(); // {path cell, side direction}
        for (int[] p : main) { sides.add(new int[][]{p, {-ax, -az}}); sides.add(new int[][]{{p[0] + ax, p[1] + az}, {ax, az}}); }
        if (main.size() >= 10) {
            int[] mid = main.get(main.size() / 2);
            for (int s2 : new int[]{1, -1}) for (int t = 0; ; t++) {
                int pi = mid[0] + ax * s2 * t, pj = mid[1] + az * s2 * t;
                if (!inside.contains(key(pi, pj)) || wall.contains(key(pi, pj))) break;
                path.add(key(pi, pj));
                if (inside.contains(key(pi - ox0, pj - oz0)) && !wall.contains(key(pi - ox0, pj - oz0))) path.add(key(pi - ox0, pj - oz0));
                sides.add(new int[][]{{pi, pj}, {ox0, oz0}});
                sides.add(new int[][]{{pi - ox0, pj - oz0}, {-ox0, -oz0}});
            }
        }
        // mausoleums along the alleys, doors onto them
        int want = (int) Geom.clamp(Math.round(cells.size() / 450.0), 1, 4);
        List<int[]> placed = new ArrayList<>();
        java.util.Collections.sort(sides, (x, y) -> Long.compare(hash(x[0][0], x[0][1], 61 + cm.id), hash(y[0][0], y[0][1], 61 + cm.id)));
        for (int[][] sd : sides) {
            if (placed.size() >= want) break;
            int[] p = sd[0];
            int sx = sd[1][0], sz = sd[1][1];
            int ci = p[0] + sx * 3, cj = p[1] + sz * 3;
            boolean ok = true;
            for (int[] q : placed) if (Math.hypot(q[0] - ci, q[1] - cj) < 9) ok = false;
            int f0 = Integer.MIN_VALUE, fmin = Integer.MAX_VALUE;
            for (int dj = -2; dj <= 2 && ok; dj++) for (int di = -2; di <= 2 && ok; di++) {
                long kk = key(ci + di, cj + dj);
                if (!inside.contains(kk) || wall.contains(kk) || path.contains(kk) || reserved.contains(kk)) { ok = false; break; }
                int l = lvl[idx(ci + di, cj + dj)];
                f0 = Math.max(f0, l); fmin = Math.min(fmin, l);
            }
            if (!ok || f0 - fmin > 2 || Math.abs(lvl[idx(p[0], p[1])] - f0) > 1) continue; // on a slope it stands on a plinth
            int door = sx == 0 ? (sz > 0 ? 0 : 2) : (sx > 0 ? 3 : 1); // the door looks back at the alley (-s)
            mausoleum(ci, cj, f0, door, reserved);
            mausoleums.add(new int[]{ci, cj, f0, door, cm.id});
            placed.add(new int[]{ci, cj});
        }
        // graves in rows facing the alleys, narrow walks between; a few cypresses
        boolean alongX = ox0 != 0;
        cemAlong.put(cm.id, alongX);
        for (int[] cc : cells) {
            int i = cc[0], j = cc[1];
            long kk = key(i, j);
            if (wall.contains(kk) || path.contains(kk) || reserved.contains(kk)) continue;
            boolean nearPath = false;
            for (int[] d : DIRS) if (path.contains(key(i + d[0], j + d[1])) || wall.contains(key(i + d[0], j + d[1]))) nearPath = true;
            long hh = hash(i, j, 71);
            if (!nearPath && hh % 1000 < 12) { cypress(i, j, reserved); continue; }
            int along = alongX ? i : j, lat = alongX ? j : i;
            if (Math.floorMod(lat, 4) == 3) continue; // a walk
            int ph = Math.floorMod(along, 3);
            if (ph == 2) continue;
            int hi = alongX ? i - ph : i, hj = alongX ? j : j - ph; // this grave's headstone cell
            int pi = alongX ? hi + 1 : hi, pj = alongX ? hj : hj + 1;   // and its plot
            boolean free = true;
            for (long q : new long[]{key(hi, hj), key(pi, pj)}) if (!inside.contains(q) || wall.contains(q) || path.contains(q) || reserved.contains(q)) free = false;
            if (!free) continue;
            if (ph == 0) headstone(i, j, hh);
            else {
                cemSurface.put(kk, Palette.s(hh % 5 < 3 ? Blocks.COARSE_DIRT : Blocks.PODZOL));
                if (hh % 13 == 0) decor(i, j, 0, Palette.s(hh % 2 == 0 ? Blocks.POPPY : Blocks.OXEYE_DAISY));
            }
        }
        // the wall: two courses with a capping, mossy here and there; the alleys in gravel
        for (long w : wall) {
            int i = (int) (w >> 32), j = (int) w;
            long hh = hash(i, j, 73);
            decor(i, j, 0, Palette.s(hh % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS));
            decor(i, j, 1, Palette.s(hh % 7 == 0 ? Blocks.MOSSY_STONE_BRICK_SLAB : Blocks.STONE_BRICK_SLAB));
        }
        for (long p : path) cemSurface.put(p, Palette.s(Blocks.GRAVEL));
    }

    private void headstone(int i, int j, long hh) {
        int v = (int) (hh % 20);
        if (v < 8) decor(i, j, 0, Palette.s(Blocks.STONE_BRICK_WALL));
        else if (v < 11) decor(i, j, 0, Palette.s(Blocks.ANDESITE_WALL));
        else if (v < 14) decor(i, j, 0, Palette.s(Blocks.MOSSY_STONE_BRICK_WALL));
        else if (v < 16) decor(i, j, 0, Palette.s(Blocks.COBBLESTONE_WALL));
        else if (v < 18) { decor(i, j, 0, Palette.s(Blocks.CHISELED_STONE_BRICKS)); decor(i, j, 1, Palette.s(Blocks.STONE_BRICK_SLAB)); } // a tomb
        else { decor(i, j, 0, Palette.s(Blocks.STONE_BRICK_WALL)); decor(i, j, 1, Palette.s(Blocks.STONE_BRICK_WALL)); } // an obelisk
    }

    /** A cypress: a tall dark spire. */
    private void cypress(int i, int j, HashSet<Long> reserved) {
        int hgt = 5 + (int) (hash(i, j, 79) % 3);
        for (int h = 0; h < hgt; h++) decor(i, j, h, Palette.s(Blocks.SPRUCE_LOG));
        BlockState lv = Catacombs.st("spruce_leaves[persistent=true]");
        for (int h = 1; h <= hgt; h++) for (int[] d : DIRS) if (h < hgt - 1 || (h + d[0] + d[1]) % 2 == 0) decor(i + d[0], j + d[1], h, lv);
        decor(i, j, hgt, lv);
        decor(i, j, hgt + 1, lv);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) reserved.add(key(i + di, j + dj));
    }

    /**
     * A mausoleum, 5 x 5 with its door onto the alley: stone walls with pilasters at the corners, a carved lintel,
     * a slab roof with a finial, and inside a sarcophagus with a candle. Where the catacombs come, a spiral stair takes
     * the place of the sarcophagus (Catacombs uses the list).
     */
    private void mausoleum(int ci, int cj, int f0, int door, HashSet<Long> reserved) {
        int q = door; // 0 north (the model below), then quarter turns clockwise
        String face = new String[]{"north", "east", "south", "west"}[door];
        BlockState air = Palette.s(Blocks.AIR);
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            int[] r = turn(dx, dz, q);
            int i = ci + r[0], j = cj + r[1];
            lvl[idx(i, j)] = f0;
            cemSurface.put(key(i, j), Palette.s(Blocks.POLISHED_ANDESITE));
            boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2, corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
            for (int h = 0; h < 3; h++) decor(i, j, h, ring ? Palette.s(corner ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS) : air);
            if (dz == -2 && dx == 0) {
                decor(i, j, 0, Catacombs.st("dark_oak_door[facing=" + face + ",half=lower,hinge=left,open=false]"));
                decor(i, j, 1, Catacombs.st("dark_oak_door[facing=" + face + ",half=upper,hinge=left,open=false]"));
                decor(i, j, 2, Palette.s(Blocks.CHISELED_STONE_BRICKS));
            }
            if (dz == -2 && Math.abs(dx) == 1) decor(i, j, 2, Palette.s(Blocks.CHISELED_STONE_BRICKS));
            decor(i, j, 3, dz == -2 && dx == 0 ? Palette.s(Blocks.CHISELED_STONE_BRICKS) : Palette.s(Blocks.STONE_BRICK_SLAB));
            if (dx == 0 && dz == 0) {
                decor(i, j, 0, Palette.s(Blocks.SMOOTH_STONE));
                decor(i, j, 1, Catacombs.st("candle[candles=2,lit=true]"));
                decor(i, j, 4, Palette.s(Blocks.STONE_BRICK_WALL));
            }
        }
        for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) { int[] r = turn(dx, dz, q); reserved.add(key(ci + r[0], cj + r[1])); }
    }

    private void gardenDecor() {
        for (int j = 2; j < H - 2; j++) for (int i = 2; i < W - 2; i++) cellDecor(i, j);
    }

    private void cellDecor(int i, int j) {
        BlockState[] cd = cemDecor.get(key(i, j));
        if (cd != null && in(i, j) && kind[idx(i, j)] == CEMETERY) {
            int lv = lvl[idx(i, j)];
            for (int h = 0; h < cd.length; h++) if (cd[h] != null) put(i, lv + 1 + h, j, cd[h]);
            return;
        }
        double chance = c.d("mc.gardenTreeChance");
        int[] gw = gateWall.get(key(i, j));
        if (gw != null) { // hofje wall: 3 high with a capping course, a door in the middle facing the street
            int lv = lvl[idx(i, j)];
            Direction toStreet = d2(DIRS[gw[0]][0], DIRS[gw[0]][1]);
            for (int rel = 1; rel <= 3; rel++) {
                BlockState st = rel == 3 ? Block.stateById(gw[3]) : Block.stateById(gw[2]);
                if (gw[1] == 1 && rel <= 2) st = Palette.door(Block.stateById(gw[4]).getBlock(), toStreet, rel == 2);
                put(i, lv + rel, j, st);
            }
            return;
        }
        {
            if (i < 2 || j < 2 || i >= W - 2 || j >= H - 2) return;
            int k = idx(i, j);
            if (kind[k] != GARDEN || s.water[k]) return;
            int lv = lvl[k];
            double r = (hash(i, j, 3) % 100000) / 100000.0;
            if (r < chance && clearAround(i, j, 2)) {
                int hgt = 4 + (int) (hash(i, j, 5) % 2);
                for (int y = lv + 1; y <= lv + hgt; y++) put(i, y, j, Blocks.OAK_LOG);
                for (int dy = hgt - 2; dy <= hgt + 1; dy++) for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                    double rr = di * di + dj * dj + Math.pow(dy - hgt + 0.5, 2) * 1.4;
                    if (rr > 5.2 || (di == 0 && dj == 0 && dy <= hgt)) continue;
                    put(i + di, lv + dy + 1 - 1, j + dj, Palette.leaves());
                }
            } else if (r > 0.9 && r < 0.93 && clearAround(i, j, 1)) {
                put(i, lv + 1, j, Palette.leaves());
            } else if (r > 0.95) {
                long f = hash(i, j, 9) % 3;
                put(i, lv + 1, j, f == 0 ? Blocks.POPPY : f == 1 ? Blocks.DANDELION : Blocks.OXEYE_DAISY);
            }
        }
    }

    // ------------------------------------------------------------------ villager mode: one job at a time

    public OpList begin(boolean guarded) {
        OpList o = new OpList();
        sink = o;
        guard = guarded;
        return o;
    }

    public void end() { sink = plan; guard = false; }

    /** Paving, gardens and foundations of one block's face (its half of each bounding street included). */
    public OpList groundOps(CityBlock b) {
        OpList o = begin(true);
        List<int[]> cells = new ArrayList<>();
        // House and monument cells are prepared by their own jobs (see houseOps): a block job that ran after a house
        // was finished used to overwrite its floor and clear its walls down to the cut level.
        raster(b.facePoly, (i, j) -> { byte k = kind[idx(i, j)]; if (k != HOUSE && k != LANDMARK) cells.add(new int[]{i, j}); });
        for (int[] cl : cells) cellTerrain(cl[0], cl[1]);
        for (int[] cl : cells) cellDecor(cl[0], cl[1]);
        end();
        return o;
    }

    /**
     * Villager mode, final pass: pave (or green) every remaining city cell that no block job covered,
     * e.g. faces too small or awkward for houses. Split into tiles so several masons share the work.
     */
    List<OpList> leftoverOps(boolean[] done, int tile) {
        HashMap<Long, List<int[]>> tiles = new HashMap<>();
        for (int j = 0; j < H; j++) for (int i = 0; i < W; i++) {
            int k = idx(i, j);
            if ((kind[k] != PAVED && kind[k] != GARDEN) || done[k]) continue;
            tiles.computeIfAbsent(((long) (i / tile) << 32) | (j / tile), x -> new ArrayList<>()).add(new int[]{i, j});
        }
        List<Long> keys = new ArrayList<>(tiles.keySet());
        java.util.Collections.sort(keys);
        List<OpList> out = new ArrayList<>();
        for (long key : keys) {
            OpList o = begin(true);
            for (int[] cl : tiles.get(key)) cellTerrain(cl[0], cl[1]);
            for (int[] cl : tiles.get(key)) cellDecor(cl[0], cl[1]);
            end();
            if (o.n > 0) out.add(o);
        }
        return out;
    }

    /**
     * Everything one house needs, self-contained so jobs can run in any order: the ground under its own footprint
     * (cut, foundation fill, clearing), then the building. Ground ops come first, so a stable bottom-up sort keeps
     * "clear, then build" at every height.
     */
    OpList houseOps(House h) {
        OpList o = begin(false);
        List<int[]> cells = houseCells.getOrDefault(h.id, List.of());
        if (cells.size() >= 6 && h.facade != null) for (int[] cl : cells) cellTerrain(cl[0], cl[1]);
        house(h);
        end();
        return o;
    }

    /** A monument with the ground under its footprint (same reasoning as houseOps). */
    public OpList landmarkOps(Landmark l) {
        OpList o = begin(false);
        raster(l.fp, (i, j) -> { if (owns(i, j, LANDMARK, l.id)) cellTerrain(i, j); });
        landmark(l);
        end();
        return o;
    }

    /** A plaza's paving (never over finished buildings), plus fountain and bell on central squares. */
    OpList plazaOps(Plaza p) {
        OpList o = begin(true);
        raster(p.poly, (i, j) -> { if (kind[idx(i, j)] == PLAZA) cellTerrain(i, j); });
        if (p.central && c.b("mc.fountains")) fountain(p);
        if (incrementalLamps) {
            placingFurniture = true;
            for (long[] l : plazaLamps(p)) lampPost((int) l[0], (int) l[1], (int) l[2]);
            placingFurniture = false;
        }
        end();
        return o;
    }

    /**
     * A street segment as a surveyor lays it: its strip and the openings at both junctions, at the levels
     * interpolated between its end nodes. Ops are ordered along the street so paving can follow the villager.
     */
    OpList streetOps(Edge e) {
        OpList o = begin(true);
        int la = nodeLevel(e.a), lb = nodeLevel(e.b);
        P a = e.a.p, b = e.b.p, ab = b.sub(a);
        double l2 = Math.max(1e-9, ab.dot(ab)), exp = c.d("street.junctionExpansion");
        P nrm = ab.norm().perpL();
        java.util.TreeMap<Double, List<int[]>> byT = new java.util.TreeMap<>();
        HashMap<Long, Integer> lev = new HashMap<>();
        P[] quad = {a.add(nrm.mul(e.wA / 2)), b.add(nrm.mul(e.wB / 2)), b.sub(nrm.mul(e.wB / 2)), a.sub(nrm.mul(e.wA / 2))};
        raster(quad, (i, j) -> {
            double tt = Geom.clamp(new P(i, j).sub(a).dot(ab) / l2, 0, 1);
            lev.put(((long) i << 32) | (j & 0xffffffffL), (int) Math.round(la + (lb - la) * tt));
            byT.computeIfAbsent(tt, x -> new ArrayList<>()).add(new int[]{i, j});
        });
        for (Node n : new Node[]{e.a, e.b}) {
            double w = 0;
            for (Edge x : n.edges) w = Math.max(w, x.widthAt(n));
            double r = w / 2 * (n.deg() >= 3 ? exp : 1.0);
            int lv = nodeLevel(n);
            double tt = n == e.a ? 0 : 1;
            for (int j = (int) Math.floor(n.p.y() - r); j <= (int) Math.ceil(n.p.y() + r); j++)
                for (int i = (int) Math.floor(n.p.x() - r); i <= (int) Math.ceil(n.p.x() + r); i++) {
                    if (!in(i, j) || n.p.dist(new P(i, j)) > r) continue;
                    long key = ((long) i << 32) | (j & 0xffffffffL);
                    if (lev.containsKey(key)) continue;
                    lev.put(key, lv);
                    byT.computeIfAbsent(tt, x -> new ArrayList<>()).add(new int[]{i, j});
                }
        }
        // temporarily classify the whole strip as street so surfaces, edges and stairs see their neighbours
        List<int[]> saved = new ArrayList<>();
        for (List<int[]> l : byT.values()) for (int[] cl : l) {
            int k = idx(cl[0], cl[1]);
            if (protect != null && protect[k]) continue;
            saved.add(new int[]{k, kind[k], lvl[k], cls[k]});
            kind[k] = STREET; lvl[k] = lev.get(((long) cl[0] << 32) | (cl[1] & 0xffffffffL)); cls[k] = e.cls;
        }
        for (var en : byT.entrySet()) {
            o.mark(en.getKey());
            for (int[] cl : en.getValue()) if (protect == null || !protect[idx(cl[0], cl[1])]) cellTerrain(cl[0], cl[1]);
        }
        for (int q = saved.size() - 1; q >= 0; q--) {
            int[] sv = saved.get(q);
            kind[sv[0]] = (byte) sv[1]; lvl[sv[0]] = sv[2]; cls[sv[0]] = sv[3];
        }
        if (incrementalLamps) {
            o.mark(1.01);
            placingFurniture = true;
            for (long[] l : lampCells(e, e.width, lev)) lampPost((int) l[0], (int) l[1], (int) l[2]);
            placingFurniture = false;
        }
        end();
        return o;
    }

    private boolean clearAround(int i, int j, int r) {
        for (int dj = -r; dj <= r; dj++) for (int di = -r; di <= r; di++)
            if (!in(i + di, j + dj) || kind[idx(i + di, j + dj)] != GARDEN || gateWall.containsKey(key(i + di, j + dj))) return false;
        return true;
    }
}
