package com.emergentcity.city;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Paris-style catacombs under a finished town (command option 'catacombs'). Modelled on the quarries of the left bank
 * and the Inspection generale des carrieres' consolidation work (galleries after 1777, ossuary 1786-1810):
 *
 * <ul>
 * <li>inspection galleries under every street, a stone "copy" of the street plan with street-name plaques; streets
 *     8+ wide get double galleries under both facade lines, with cross passages;</li>
 * <li>old quarry chambers under some blocks: rock left as turned pillars, stacked arm pillars, ground backfilled
 *     behind dry-stone walls (hagues), extraction shafts, linked to the galleries by doorways or crawl-holes;</li>
 * <li>the ossuary: a winding route walled with bones (femur ends, skull friezes and crosses), plaques, the
 *     Samaritaine fountain, the sepulchral lamp, Gilbert's tomb, the Crypt of the Passion and a Sacellum chapel;</li>
 * <li>the visitor route from a spiral stair (lit, black guide line on the ceiling, the quarrymen's footbath, a
 *     Port-Mahon model) and an exit stair; fontis (collapse bells), one consolidated with painted strata;</li>
 * <li>a ramp to a lower level of sinuous original galleries, partly flooded; cataphile rooms (a pillared hall under
 *     the main square, "la Plage", a concrete bunker reached only by a crawl-hole); manhole ladders to the streets.</li>
 * </ul>
 *
 * The plan is drawn on the town's grid in 2D per level, carved into a 3D grid, and every open space is wrapped in a
 * shell of stone, so caves, aquifers and lava in a real world never leak in. Everything goes into one block plan.
 */
public final class Catacombs {
    // zones (what an open voxel belongs to); WATER is or-ed in for water voxels
    static final byte GAL = 1, QUARRY = 2, OSS = 3, ROOMZ = 4, PLAGE = 5, BUNKER = 6, STAIR = 7, SHAFT = 8, FONTIS = 9,
            PAINTED = 10, LOWER = 11, CHAT = 12;
    static final int WATER = 16;
    // special materials of solid 2D cells (their walls)
    static final byte M_HAGUE = 1, M_ARM = 2, M_MASONRY = 3, M_PORTAL = 4;
    static final int[][] D4 = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    record Sign(long pos, String[] lines) {}
    public record Spot(String what, int x, int y, int z) {}

    final CityModel m;
    final Voxelizer v;
    final Config c;
    final Random rnd;
    final int W, H, ox, oz;
    final boolean[] town;
    final int[] surf, dStreet, region;
    final byte[] up, lo, hUp, hLo, mat2;
    final boolean[] flood, pool, chapel;
    /** Floor offset of an upper cell: -1 for galleries a step lower than the rest (a ramp of slabs leads down). */
    final byte[] dUp;
    /** Gallery cells of each street (edge id -> cells), and the radius of each junction's crossroads. */
    private final Map<Integer, List<Integer>> edgeCells = new HashMap<>();
    private final Map<Integer, Double> crossR = new HashMap<>();
    private List<Integer> recording;
    /** The visitor route from the entrance stair to the ossuary, cell by cell. */
    private final List<Integer> routeCells = new ArrayList<>();
    int grates, arches, lowered, drips, lowQuarries, mausoleumStairs;
    int F, FL, yLo, yHi, D;
    byte[] g;
    final BlockPlan plan;
    final LinkedHashMap<Long, BlockState> deco = new LinkedHashMap<>(), direct = new LinkedHashMap<>();
    final List<Sign> signs = new ArrayList<>();
    public final List<Spot> spots = new ArrayList<>();
    private final List<int[]> extra = new ArrayList<>();
    private final List<int[]> fontisAt = new ArrayList<>();
    private int nextRegion = 1, plageRegion = -1, ossRegion = -1;
    /** La Plage's fresco wall: the direction from that wall into the room (0 east, 1 south, 2 west, 3 north). */
    int plageDir = -1;
    private final Map<Integer, Integer> regionZone = new HashMap<>();
    int galleryCells, quarries, fontis, manholes, lowerCells, openVoxels;
    boolean ossuary, lower, plage, bunker, hall, atelier, boneShaft;
    /** Grid cells of the spiral stairs' first step at the street (for reports): {i, j, level}. */
    public final List<int[]> entrances = new ArrayList<>();

    Catacombs(CityModel m, Voxelizer v, long seed) {
        this.m = m; this.v = v; this.c = m.cfg;
        rnd = new Random(seed ^ 0x5CA7AC0B5L);
        W = v.W; H = v.H; ox = v.ox; oz = v.oz;
        int n = W * H;
        town = new boolean[n]; surf = new int[n]; dStreet = new int[n]; region = new int[n];
        up = new byte[n]; lo = new byte[n]; hUp = new byte[n]; hLo = new byte[n]; mat2 = new byte[n];
        flood = new boolean[n]; pool = new boolean[n]; chapel = new boolean[n]; dUp = new byte[n];
        plan = new BlockPlan(v.plan.minY, v.plan.maxY);
    }

    int idx(int i, int j) { return j * W + i; }
    boolean in(int i, int j) { return i >= 4 && j >= 4 && i < W - 4 && j < H - 4; }
    private static boolean streetKind(byte k) { return k == Voxelizer.STREET || k == Voxelizer.JUNCTION || k == Voxelizer.PLAZA; }

    // ------------------------------------------------------------------ block states

    private static final HashMap<String, BlockState> STATES = new HashMap<>();

    /** "name[key=value,...]" -> state (stone for a block this game version does not have). */
    public static synchronized BlockState st(String spec) {
        BlockState s = STATES.get(spec);
        if (s != null) return s;
        String name = spec;
        TreeMap<String, String> props = new TreeMap<>();
        int b = spec.indexOf('[');
        if (b >= 0) {
            name = spec.substring(0, b);
            for (String kv : spec.substring(b + 1, spec.length() - 1).split(",")) {
                String[] p = kv.split("=");
                if (p.length == 2) props.put(p[0].trim(), p[1].trim());
            }
        }
        s = States.parse(name.contains(":") ? name : "minecraft:" + name, props);
        // the mod's own bone blocks are always registered with it; vanilla stand-ins only guard against a broken install
        if (s == null && name.startsWith("emergentcity:")) s = States.parse(name.contains("gravel") ? "minecraft:gravel" : "minecraft:bone_block", null);
        if (s == null) s = Blocks.STONE.defaultBlockState();
        STATES.put(spec, s);
        return s;
    }

    static int hash(int i, int y, int j) { return (int) (Voxelizer.hash(i, j, y * 31 + 7) % 1000); }

    private static final String[] DIR_NAME = {"east", "south", "west", "north"};

    static int dirOf(int dx, int dz) { return dx > 0 ? 0 : dz > 0 ? 1 : dx < 0 ? 2 : 3; }

    // ------------------------------------------------------------------ build

    void build() {
        int n = W * H;
        // town footprint, surface level, distance from streets
        int minSurf = Integer.MAX_VALUE;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int k = 0; k < n; k++) {
            int i = k % W, j = k / W;
            town[k] = v.kind[k] != Voxelizer.NONE && in(i, j) && !v.s.water[k];
            surf[k] = v.kind[k] != Voxelizer.NONE ? Math.min(v.lvl[k], v.s.ground[k]) : v.s.ground[k];
            if (town[k]) minSurf = Math.min(minSurf, surf[k]);
            dStreet[k] = Integer.MAX_VALUE;
            if (streetKind(v.kind[k])) { dStreet[k] = 0; q.add(k); }
        }
        if (minSurf == Integer.MAX_VALUE) return;
        while (!q.isEmpty()) {
            int k = q.poll(), i = k % W, j = k / W;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int ii = i + di, jj = j + dj;
                if (ii < 0 || jj < 0 || ii >= W || jj >= H) continue;
                int kk = idx(ii, jj);
                if (dStreet[kk] > dStreet[k] + 1) { dStreet[kk] = dStreet[k] + 1; q.add(kk); }
            }
        }
        int minY = v.plan.minY;
        F = Math.max(minSurf - c.i("catacombs.depth"), minY + 14);
        FL = F - 7;
        yLo = FL - 3;
        yHi = F + 16;
        D = yHi - yLo + 1;

        streetGalleries();
        hallUnderSquare();
        chooseBlocks();
        rampAndLower();
        int[] door = ossDoor; // outside cell of the ossuary portal
        int[] ent = spiral(door != null ? door : center(), true);
        if (ossExit != null) spiral(ossExit, false);
        mausoleumStairs();
        manholes();
        fontis();
        visitorRoute(ent, door);
        if (c.b("catacombs.levels")) { lowerSections(); grates(); }
        plaques();
        arches();
        carve3D();
        emit();
    }

    private int[] center() {
        P c0 = m.centers.get(0).pos;
        return new int[]{(int) Math.round(c0.x()), (int) Math.round(c0.y())};
    }

    /** Ground left over the ceiling of an open cell with this air height. */
    private boolean cover(int k, int h) { return surf[k] - (F + h + 1) >= 4; }

    /** Opens an upper cell (unless it belongs to a reserved region or is outside the town). */
    private boolean open(int i, int j, byte zone, int h) {
        if (!in(i, j)) return false;
        int k = idx(i, j);
        if (!town[k] || region[k] != 0 || !cover(k, h)) return false;
        if (recording != null) recording.add(k);
        if (up[k] == 0) { up[k] = zone; hUp[k] = (byte) h; if (zone == GAL) galleryCells++; }
        else if (up[k] == GAL || up[k] == CHAT) hUp[k] = (byte) Math.max(hUp[k], h);
        return true;
    }

    // ------------------------------------------------------------------ inspection galleries

    /** Cells of a 4-connected line (each step moves along one axis, staying closest to the true line). */
    static List<int[]> line4(int x0, int z0, int x1, int z1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0), sx = x1 > x0 ? 1 : -1, sz = z1 > z0 ? 1 : -1;
        int x = x0, z = z0, ix = 0, iz = 0;
        out.add(new int[]{x, z});
        while (ix < dx || iz < dz) {
            if (iz >= dz || (ix < dx && (long) (1 + 2 * ix) * dz < (long) (1 + 2 * iz) * dx)) { x += sx; ix++; }
            else { z += sz; iz++; }
            out.add(new int[]{x, z});
        }
        return out;
    }

    /** A 2-wide gallery between two points. */
    private void strip(P a, P b, byte zone, int h) {
        int x0 = (int) Math.round(a.x()), z0 = (int) Math.round(a.y()), x1 = (int) Math.round(b.x()), z1 = (int) Math.round(b.y());
        boolean alongX = Math.abs(x1 - x0) >= Math.abs(z1 - z0);
        for (int[] p : line4(x0, z0, x1, z1)) {
            open(p[0], p[1], zone, h);
            open(p[0] + (alongX ? 0 : 1), p[1] + (alongX ? 1 : 0), zone, h);
        }
    }

    final Map<Integer, String> streetName = new HashMap<>();

    private void streetGalleries() {
        Map<Integer, Double> nodeR = new HashMap<>();
        for (Edge e : m.g.aliveEdges()) {
            P a = e.a.p, b = e.b.p, d = b.sub(a);
            double len = Math.hypot(d.x(), d.y());
            if (len < 1) continue;
            P u = d.norm(), nrm = u.perpL();
            double w = e.width, off = w >= 8 ? w / 2 - 1.5 : 0;
            recording = new ArrayList<>();
            edgeCells.put(e.id, recording);
            if (off > 0) {
                strip(a.add(nrm.mul(off)), b.add(nrm.mul(off)), GAL, 3);
                strip(a.sub(nrm.mul(off)), b.sub(nrm.mul(off)), GAL, 3);
                for (double t = 10; t < len - 6; t += 12) {
                    P mid = a.add(u.mul(t));
                    strip(mid.add(nrm.mul(off)), mid.sub(nrm.mul(off)), GAL, 3);
                }
            } else strip(a, b, GAL, 3);
            recording = null;
            for (Node nd : new Node[]{e.a, e.b}) nodeR.merge(nd.id, off + 1.5, Math::max);
        }
        // crossroads under the junctions; a wide one keeps a masonry pillar in the middle
        for (Node nd : m.g.aliveNodes()) {
            Double r = nodeR.get(nd.id);
            if (r == null) continue;
            double rr = Math.max(1.5, r);
            crossR.put(nd.id, rr);
            for (int j = (int) Math.floor(nd.p.y() - rr); j <= Math.ceil(nd.p.y() + rr); j++)
                for (int i = (int) Math.floor(nd.p.x() - rr); i <= Math.ceil(nd.p.x() + rr); i++)
                    if (Math.hypot(i - nd.p.x(), j - nd.p.y()) <= rr) open(i, j, GAL, 3);
            if (rr >= 3.5) {
                for (int j = (int) Math.floor(nd.p.y() - 1.2); j <= Math.ceil(nd.p.y() + 1.2); j++)
                    for (int i = (int) Math.floor(nd.p.x() - 1.2); i <= Math.ceil(nd.p.x() + 1.2); i++)
                        if (in(i, j) && Math.hypot(i - nd.p.x(), j - nd.p.y()) <= 1.2 && up[idx(i, j)] == GAL) { up[idx(i, j)] = 0; mat2[idx(i, j)] = M_MASONRY; galleryCells--; }
            }
        }
    }

    // ------------------------------------------------------------------ the hall under the main square ("salle Z")

    private void hallUnderSquare() {
        Plaza p = m.centers.get(0).plaza;
        if (p == null || p.poly.size() < 3 || !c.b("catacombs.rooms")) return;
        int rid = nextRegion++;
        List<int[]> cells = new ArrayList<>();
        v.raster(p.poly, (i, j) -> {
            int k = idx(i, j);
            if (in(i, j) && town[k] && region[k] == 0 && cover(k, 4)) cells.add(new int[]{i, j});
        });
        if (cells.size() < 40) return;
        int px = rnd.nextInt(7), pz = rnd.nextInt(7);
        Set<Integer> cs = new HashSet<>();
        for (int[] cc : cells) cs.add(idx(cc[0], cc[1]));
        int opened = 0;
        for (int[] cc : cells) {
            int i = cc[0], j = cc[1], k = idx(i, j);
            boolean interior = cs.contains(idx(i - 2, j)) && cs.contains(idx(i + 2, j)) && cs.contains(idx(i, j - 2)) && cs.contains(idx(i, j + 2));
            if (interior && Math.floorMod(i + px, 7) < 2 && Math.floorMod(j + pz, 7) < 2) {
                if (up[k] == GAL) galleryCells--;
                up[k] = 0; mat2[k] = M_MASONRY; region[k] = rid; continue;
            }
            if (up[k] == GAL) galleryCells--;
            up[k] = ROOMZ; hUp[k] = 4; region[k] = rid; opened++;
        }
        regionZone.put(rid, (int) ROOMZ);
        hall = opened > 0;
        // benches against the walls, candles by the pillars, a table in the middle
        int bench = 0;
        for (int[] cc : cells) {
            int i = cc[0], j = cc[1], k = idx(i, j);
            if (up[k] != ROOMZ) continue;
            for (int d = 0; d < 4; d++) {
                int kk = idx(i + D4[d][0], j + D4[d][1]);
                if (up[kk] == 0 && mat2[kk] == 0 && (bench++ % 3 == 0)) { deco(i, F + 1, j, st("sandstone_stairs[facing=" + DIR_NAME[d] + "]")); break; }
                if (up[kk] == 0 && mat2[kk] == M_MASONRY && hash(i, 1, j) < 120) { deco(i, F + 1, j, st("candle[candles=3,lit=true]")); break; }
            }
        }
        int[] mid = cells.get(cells.size() / 2);
        P cc = p.center();
        int ci = (int) Math.round(cc.x()), cj = (int) Math.round(cc.y());
        if (in(ci, cj) && up[idx(ci, cj)] == ROOMZ) mid = new int[]{ci, cj};
        if (up[idx(mid[0], mid[1])] == ROOMZ) {
            deco(mid[0], F + 1, mid[1], st("smooth_sandstone_slab[type=top]"));
            deco(mid[0], F + 2, mid[1], st("candle[candles=4,lit=true]"));
        }
        signOnWall(rid, ROOMZ, new String[]{"", "SALLE Z", "", ""});
        spots.add(new Spot("Hall under the main square (salle Z)", ox + mid[0], F + 1, oz + mid[1]));
    }

    // ------------------------------------------------------------------ blocks: ossuary, quarries, la Plage, bunker

    private int[] ossDoor, ossExit;

    /** Cells of a city block deep enough under the block (away from the street galleries). */
    private List<int[]> blockCells(CityBlock b) {
        List<int[]> cells = new ArrayList<>();
        if (b.facePoly == null || b.facePoly.size() < 3) return cells;
        v.raster(b.facePoly, (i, j) -> {
            int k = idx(i, j);
            if (in(i, j) && town[k] && region[k] == 0 && up[k] == 0 && dStreet[k] >= 4 && cover(k, 4)) cells.add(new int[]{i, j});
        });
        return largestPart(cells);
    }

    /** The largest 4-connected part of a set of cells (a block's interior can be split by a street or a pinch). */
    private List<int[]> largestPart(List<int[]> cells) {
        HashMap<Integer, int[]> byK = new HashMap<>();
        for (int[] cc : cells) byK.put(idx(cc[0], cc[1]), cc);
        HashSet<Integer> seen = new HashSet<>();
        List<int[]> best = new ArrayList<>();
        for (int[] cc : cells) {
            int k0 = idx(cc[0], cc[1]);
            if (seen.contains(k0)) continue;
            List<int[]> part = new ArrayList<>();
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(k0); seen.add(k0);
            while (!q.isEmpty()) {
                int k = q.poll();
                part.add(byK.get(k));
                for (int[] d : D4) { int kk = k + d[0] + d[1] * W; if (byK.containsKey(kk) && seen.add(kk)) q.add(kk); }
            }
            if (part.size() > best.size()) best = part;
        }
        return best;
    }

    private void chooseBlocks() {
        List<CityBlock> bl = new ArrayList<>(m.blocks);
        bl.sort((a, b) -> Long.compare(a.key, b.key));
        java.util.Collections.shuffle(bl, rnd);
        P c0 = m.centers.get(0).pos;
        double R = Math.max(40, m.radius);
        // the ossuary: the roomiest block a short way out from the centre
        CityBlock best = null;
        double bestS = 0;
        bl.removeIf(b -> b.cemetery != null); // nothing dug under the graves but the mausoleums' stairs
        for (CityBlock b : bl) {
            List<int[]> cells = blockCells(b);
            if (cells.size() < 140 || b.facePoly == null) continue;
            double dd = Geom.centroid(b.facePoly).dist(c0) / R;
            double s = cells.size() * (dd > 0.12 && dd < 0.75 ? 1.0 : 0.35);
            if (s > bestS) { bestS = s; best = b; }
        }
        if (best != null) ossuary(blockCells(best));
        boolean rooms = c.b("catacombs.rooms");
        double share = c.d("catacombs.quarryShare");
        for (CityBlock b : bl) {
            if (b == best) continue;
            List<int[]> cells = blockCells(b);
            if (cells.size() < 50) continue;
            if (rooms && !bunker && cells.size() >= 90 && cells.size() < 400 && rnd.nextDouble() < 0.5) { bunker(cells); continue; }
            if (rnd.nextDouble() >= share) continue;
            boolean asPlage = rooms && !plage && cells.size() >= 120;
            quarry(cells, asPlage);
        }
    }

    // ---- quarry chambers

    private void quarry(List<int[]> cells, boolean asPlage) {
        int rid = nextRegion++;
        byte zone = asPlage ? PLAGE : QUARRY;
        regionZone.put(rid, (int) zone);
        int[] bb = bbox(cells);
        boolean alongX = bb[2] - bb[0] >= bb[3] - bb[1];
        int per = asPlage ? 6 : 5 + rnd.nextInt(2), px = rnd.nextInt(per), pz = rnd.nextInt(per);
        // backfill: the far part of the chamber, retained by a hague
        double cut = !asPlage && rnd.nextDouble() < 0.6 ? 0.55 + 0.3 * rnd.nextDouble() : 2;
        boolean flip = rnd.nextBoolean();
        Set<Integer> cs = new HashSet<>();
        for (int[] cc : cells) cs.add(idx(cc[0], cc[1]));
        for (int[] cc : cells) {
            int i = cc[0], j = cc[1], k = idx(i, j);
            region[k] = rid;
            double t = alongX ? (i - bb[0]) / (double) Math.max(1, bb[2] - bb[0]) : (j - bb[1]) / (double) Math.max(1, bb[3] - bb[1]);
            if (flip) t = 1 - t;
            if (t > cut) { mat2[k] = M_HAGUE; continue; }
            int a = Math.floorMod(i + px, per), b = Math.floorMod(j + pz, per);
            if (a < 2 && b < 2) { mat2[k] = asPlage ? M_MASONRY : 0; continue; } // turned pillar (rock left standing) / masonry pillar
            if (!asPlage && a == per / 2 + 1 && b == per / 2 + 1 && hash(i, 0, j) < 300) { mat2[k] = M_ARM; continue; } // stacked pillar
            up[k] = zone;
            hUp[k] = (byte) (asPlage ? 3 : 3 + (hash(i / 4, 3, j / 4) < 250 ? 1 : 0));
        }
        // low ceilings next to the backfill
        for (int[] cc : cells) {
            int k = idx(cc[0], cc[1]);
            if (up[k] != zone) continue;
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++)
                if (mat2[idx(cc[0] + di, cc[1] + dj)] == M_HAGUE && region[idx(cc[0] + di, cc[1] + dj)] == rid) hUp[k] = 2;
        }
        int opened = 0;
        for (int[] cc : cells) if (up[idx(cc[0], cc[1])] == zone) opened++;
        if (opened < 20) { for (int[] cc : cells) { up[idx(cc[0], cc[1])] = 0; mat2[idx(cc[0], cc[1])] = 0; } return; }
        quarries++;
        // doorways to the street galleries (a crawl-hole now and then)
        int links = cells.size() > 260 ? 2 : 1;
        Set<Integer> used = new HashSet<>();
        for (int l = 0; l < links; l++) {
            List<Integer> path = connect(rid, zone, used, 14);
            if (path == null) break;
            boolean crawl = !asPlage && rnd.nextDouble() < 0.4;
            carvePath(path, crawl ? CHAT : GAL, crawl ? 2 : 3, !crawl);
            for (int k : path) for (int dj = -8; dj <= 8; dj++) for (int di = -8; di <= 8; di++) used.add(k + dj * W + di);
        }
        if (asPlage) {
            plage = true; plageRegion = rid;
            // fresco on the longest wall: the side facing most open cells
            int[] cnt = new int[4];
            for (int[] cc : cells) {
                int k = idx(cc[0], cc[1]);
                if (up[k] != PLAGE) continue;
                for (int d = 0; d < 4; d++) { int kk = idx(cc[0] - D4[d][0], cc[1] - D4[d][1]); if (up[kk] == 0 && region[kk] != rid) cnt[d]++; }
            }
            plageDir = 0;
            for (int d = 1; d < 4; d++) if (cnt[d] > cnt[plageDir]) plageDir = d;
            int b2 = 0;
            for (int[] cc : cells) {
                int i = cc[0], j = cc[1], k = idx(i, j);
                if (up[k] != PLAGE) continue;
                for (int d = 0; d < 4; d++) {
                    int kk = idx(i + D4[d][0], j + D4[d][1]);
                    if (up[kk] == 0 && mat2[kk] == M_MASONRY && hash(i, 2, j) < 200) { deco(i, F + 1, j, st("candle[candles=" + (1 + hash(i, 4, j) % 4) + ",lit=true]")); break; }
                    if (up[kk] == 0 && mat2[kk] == 0 && region[kk] != rid && d != (plageDir + 2) % 4 && (b2++ % 4 == 0)) { deco(i, F + 1, j, st("sandstone_stairs[facing=" + DIR_NAME[d] + "]")); break; }
                }
            }
            signOnWall(rid, PLAGE, new String[]{"", "LA PLAGE", "", ""});
            int[] mc = cells.get(cells.size() / 2);
            spots.add(new Spot("La Plage", ox + mc[0], F + 1, oz + mc[1]));
        } else {
            if (!atelier) {
                atelier = true;
                signOnWall(rid, QUARRY, new String[]{"", "L'ATELIER", "", ""});
                int best = -1, bn = -1;
                for (int[] cc : cells) {
                    int k = idx(cc[0], cc[1]), nOpen = 0;
                    if (up[k] != QUARRY) continue;
                    for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) if (up[idx(cc[0] + di, cc[1] + dj)] == QUARRY) nOpen++;
                    if (nOpen > bn) { bn = nOpen; best = k; }
                }
                if (best >= 0) spotAt("Old quarry (l'Atelier)", best);
            }
            extractionShaft(cells, rid);
        }
    }

    private void extractionShaft(List<int[]> cells, int rid) {
        if (cells.size() < 120 || rnd.nextDouble() < 0.4) return;
        for (int tries = 0; tries < 30; tries++) {
            int[] cc = cells.get(rnd.nextInt(cells.size()));
            int i = cc[0], j = cc[1];
            boolean ok = true;
            int top = Integer.MAX_VALUE;
            for (int dj = -1; dj <= 1 && ok; dj++) for (int di = -1; di <= 1 && ok; di++) {
                int k = idx(i + di, j + dj);
                if (up[k] != QUARRY || hUp[k] < 3) ok = false; else top = Math.min(top, surf[k] - 5);
            }
            if (!ok) continue;
            top = Math.min(top, yHi - 1);
            if (top < F + 8) continue;
            for (int y = F + 4; y <= top; y++)
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) extra.add(new int[]{i + di, y, j + dj, SHAFT});
            if (!boneShaft && ossuary) {
                // the shaft the bones came down from the cemeteries (1786-1788): a heap of loose bones below it
                boneShaft = true;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) deco(i + di, F + 1, j + dj, st("emergentcity:bone_gravel"));
                deco(i, F + 2, j, st("emergentcity:bone_gravel"));
                for (int[] d : D4) if (up[idx(i + 2 * d[0], j + 2 * d[1])] == QUARRY && hash(i + d[0], 5, j + d[1]) < 500) deco(i + 2 * d[0], F + 1, j + 2 * d[1], skulls(dirOf(d[0], d[1]), 1 + hash(i, 6, j) % 2));
                signOnWall(rid, QUARRY, new String[]{"PUITS", "DES", "OSSEMENTS", "1786"});
                spotAt("Bone shaft", idx(i, j));
                return;
            }
            for (int y = F + 3; y < top; y++) deco(i, y, j, st("chain[axis=y]"));
            deco(i, F + 2, j, st("chain[axis=y]"));
            deco(i, F + 1, j, st("barrel[facing=up]"));
            return;
        }
    }

    // ---- bunker (reached only by a crawl-hole)

    private void bunker(List<int[]> cells) {
        int rid = nextRegion++;
        regionZone.put(rid, (int) BUNKER);
        Set<Integer> cs = new HashSet<>();
        for (int[] cc : cells) cs.add(idx(cc[0], cc[1]));
        int[] bb = bbox(cells);
        boolean alongX = bb[2] - bb[0] >= bb[3] - bb[1];
        int a0 = alongX ? bb[0] : bb[1], a1 = alongX ? bb[2] : bb[3], b0 = alongX ? bb[1] : bb[0], b1 = alongX ? bb[3] : bb[2];
        int bm = (b0 + b1) / 2;
        List<int[]> open = new ArrayList<>();
        int aStart = Integer.MAX_VALUE, aEnd = Integer.MIN_VALUE;
        for (int a = a0 + 1; a <= Math.min(a1 - 1, a0 + 22); a++) {
            boolean ok = true;
            for (int b = bm; b <= bm + 1; b++) if (!cs.contains(ij(alongX, a, b))) ok = false;
            if (!ok) { if (aEnd > aStart) break; continue; }
            aStart = Math.min(aStart, a); aEnd = Math.max(aEnd, a);
            for (int b = bm; b <= bm + 1; b++) open.add(new int[]{a, b});
        }
        if (aEnd - aStart < 8) return;
        // rooms off both sides of the corridor
        for (int a = aStart + 1; a + 3 <= aEnd; a += 6)
            for (int side : new int[]{-1, 1}) {
                List<int[]> room = new ArrayList<>();
                boolean ok = true;
                for (int aa = a; aa < a + 4; aa++) for (int t = 1; t <= 3; t++) {
                    int b = side < 0 ? bm - t : bm + 1 + t;
                    if (!cs.contains(ij(alongX, aa, b))) ok = false;
                    room.add(new int[]{aa, b});
                }
                if (ok) open.addAll(room);
            }
        for (int[] ab : open) {
            int k = ij(alongX, ab[0], ab[1]);
            up[k] = BUNKER; hUp[k] = 3;
        }
        for (int[] cc : cells) region[idx(cc[0], cc[1])] = rid;
        List<Integer> path = connect(rid, BUNKER, new HashSet<>(), 18);
        if (path == null) { for (int[] ab : open) up[ij(alongX, ab[0], ab[1])] = 0; return; }
        carvePath(path, CHAT, 2, false);
        bunker = true;
        signOnWall(rid, BUNKER, new String[]{"", "RAUCHEN", "VERBOTEN", ""});
        int k0 = ij(alongX, aStart + 2, bm);
        spots.add(new Spot("Bunker (through a crawl-hole)", ox + k0 % W, F + 1, oz + k0 / W));
    }

    private int ij(boolean alongX, int a, int b) { return alongX ? idx(a, b) : idx(b, a); }

    private static int[] bbox(List<int[]> cells) {
        int[] bb = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] cc : cells) { bb[0] = Math.min(bb[0], cc[0]); bb[1] = Math.min(bb[1], cc[1]); bb[2] = Math.max(bb[2], cc[0]); bb[3] = Math.max(bb[3], cc[1]); }
        return bb;
    }

    // ---- the ossuary

    private void ossuary(List<int[]> cells) {
        int rid = nextRegion++;
        ossRegion = rid;
        regionZone.put(rid, (int) OSS);
        Set<Integer> cs = new HashSet<>();
        for (int[] cc : cells) cs.add(idx(cc[0], cc[1]));
        int[] bb = bbox(cells);
        boolean alongX = bb[2] - bb[0] >= bb[3] - bb[1];
        int amin = alongX ? bb[0] : bb[1], amax = alongX ? bb[2] : bb[3], bmin = alongX ? bb[1] : bb[0], bmax = alongX ? bb[3] : bb[2];
        java.util.function.BiPredicate<Integer, Integer> inReg = (a, b) -> cs.contains(ij(alongX, a, b));
        // rows of 3-wide galleries, 3-thick bone walls between them
        List<int[]> rows = new ArrayList<>(); // {bc, a0, a1}
        for (int bc = bmin + 1; bc + 1 <= bmax; bc += 6) {
            int best0 = 0, best1 = -1, s = Integer.MIN_VALUE;
            for (int a = amin; a <= amax + 1; a++) {
                boolean ok = a <= amax && inReg.test(a, bc - 1) && inReg.test(a, bc) && inReg.test(a, bc + 1);
                if (ok && s == Integer.MIN_VALUE) s = a;
                if (!ok && s != Integer.MIN_VALUE) { if (a - 1 - s > best1 - best0) { best0 = s; best1 = a - 1; } s = Integer.MIN_VALUE; }
            }
            if (best1 - best0 < 7) { if (!rows.isEmpty()) break; continue; }
            rows.add(new int[]{bc, best0, best1});
        }
        if (rows.isEmpty()) return;
        // connectors at alternate ends
        List<Integer> conn = new ArrayList<>();
        for (int r = 0; r + 1 < rows.size(); r++) {
            int[] A = rows.get(r), B = rows.get(r + 1);
            boolean right = r % 2 == 0;
            int ac = right ? Math.min(A[2], B[2]) - 1 : Math.max(A[1], B[1]) + 1, found = Integer.MIN_VALUE;
            for (int t = 0; t < 5 && found == Integer.MIN_VALUE; t++) {
                int cand = right ? ac - t : ac + t;
                // the connector must lie within both rows' runs, or the rows would not meet it
                boolean ok = cand - 1 >= Math.max(A[1], B[1]) && cand + 1 <= Math.min(A[2], B[2]);
                for (int a = cand - 1; a <= cand + 1 && ok; a++) for (int b = A[0] - 1; b <= B[0] + 1 && ok; b++) if (!inReg.test(a, b)) ok = false;
                if (ok) found = cand;
            }
            if (found == Integer.MIN_VALUE) { while (rows.size() > r + 1) rows.remove(rows.size() - 1); break; }
            conn.add(found);
        }
        // route centre line; rows trimmed to their connectors
        List<int[]> route = new ArrayList<>(); // {a, b}
        List<int[]> spans = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            int[] R = rows.get(r);
            boolean exitRight = r % 2 == 0;
            int entry = r == 0 ? (exitRight ? R[1] : R[2]) : conn.get(r - 1);
            int exit = r < conn.size() ? conn.get(r) : (exitRight ? R[2] : R[1]);
            int lo = Math.min(entry, exit) - (r == 0 || r == rows.size() - 1 ? 0 : 1), hi = Math.max(entry, exit) + (r == 0 || r == rows.size() - 1 ? 0 : 1);
            lo = Math.max(lo, R[1]); hi = Math.min(hi, R[2]);
            spans.add(new int[]{R[0], lo, hi});
            int step = exit >= entry ? 1 : -1;
            for (int a = entry; a != exit + step; a += step) route.add(new int[]{a, R[0]});
            if (r < conn.size()) for (int b = R[0] + 1; b < rows.get(r + 1)[0]; b++) route.add(new int[]{exit, b});
        }
        for (int[] sp : spans) for (int a = sp[1]; a <= sp[2]; a++) for (int b = sp[0] - 1; b <= sp[0] + 1; b++) ossOpen(alongX, a, b, 3);
        for (int r = 0; r < conn.size(); r++)
            for (int a = conn.get(r) - 1; a <= conn.get(r) + 1; a++)
                for (int b = rows.get(r)[0] - 1; b <= rows.get(r + 1)[0] + 1; b++) ossOpen(alongX, a, b, 3);
        for (int[] cc : cells) region[idx(cc[0], cc[1])] = rid;
        ossuary = true;
        int L = route.size();
        // the portal at the start: black-white-black pillars, a 1-wide door, the inscription on the lintel
        int[] first = rows.get(0);
        boolean startLeft = true; // row 0 exits right, so it starts at its left end
        int aS = spans.get(0)[1], bc0 = first[0], outA = aS - 1;
        mat2[ij(alongX, aS, bc0 - 1)] = M_PORTAL; up[ij(alongX, aS, bc0 - 1)] = 0;
        mat2[ij(alongX, aS, bc0 + 1)] = M_PORTAL; up[ij(alongX, aS, bc0 + 1)] = 0;
        int door = ij(alongX, aS, bc0);
        hUp[door] = 2;
        int out = ij(alongX, outA, bc0);
        region[out] = 0;
        open(out % W, out / W, GAL, 3);
        region[out] = rid; // keep the vestibule cell out of other features
        if (up[out] == GAL) {
            ossDoor = new int[]{out % W, out / W};
            Set<Integer> vs = new HashSet<>();
            vs.add(out);
            List<Integer> vp = connectFrom(vs, rid, 40);
            if (vp != null) carvePath(vp, GAL, 3, true);
        }
        deco(door % W, F + 3, door / W, st("chiseled_sandstone"));
        int outDir = alongX ? (startLeft ? 2 : 0) : (startLeft ? 3 : 1);
        sign(out % W, F + 3, out / W, outDir, new String[]{"ARRÊTE !", "C'EST ICI", "L'EMPIRE", "DE LA MORT"});
        // the Sacellum: the end of the last row widened into a chapel with an altar and a white cross
        int[] lastS = spans.get(spans.size() - 1);
        boolean lastExitRight = (rows.size() - 1) % 2 == 0;
        int aEnd = lastExitRight ? lastS[2] : lastS[1], dirA = lastExitRight ? 1 : -1, bL = lastS[0];
        int chapelCells = 0;
        for (int t = 0; t < 6; t++) for (int b = bL - 1; b <= bL + 3; b++) {
            int a = aEnd - dirA * t;
            if (!inReg.test(a, b)) continue;
            int k = ij(alongX, a, b);
            if (b > bL + 1 && !inReg.test(a, b + 1)) continue;
            up[k] = OSS; hUp[k] = 4; chapel[k] = true; chapelCells++;
        }
        if (chapelCells > 0) {
            for (int b = bL; b <= bL + 2; b++) { int k = ij(alongX, aEnd, b); if (up[k] == OSS) deco(k % W, F + 1, k / W, st("quartz_block")); }
            int toNave = alongX ? (dirA > 0 ? 2 : 0) : (dirA > 0 ? 3 : 1);
            for (int b : new int[]{bL, bL + 2}) { int k = ij(alongX, aEnd, b); if (up[k] == OSS) deco(k % W, F + 2, k / W, skulls(toNave, 1)); }
            int wk = ij(alongX, aEnd + dirA, bL + 1);
            for (int y = F + 2; y <= F + 4; y++) deco(wk % W, y, wk / W, st("quartz_block"));
            for (int s : new int[]{-1, 1}) { int ak = ij(alongX, aEnd + dirA, bL + 1 + s); deco(ak % W, F + 3, ak / W, st("quartz_block")); }
            for (int t = 2; t <= 4; t += 2) { int k = ij(alongX, aEnd - dirA * t, bL + 2); if (up[k] == OSS) deco(k % W, F + 1, k / W, st("smooth_sandstone_slab")); }
            int sk = ij(alongX, aEnd - dirA * 3, bL - 1);
            int sd = alongX ? 1 : 0; // facing +b (into the chapel) from the wall at bL-2
            sign(sk % W, F + 2, sk / W, sd, new String[]{"", "SACELLUM", "", ""});
            spotAt("Sacellum", ij(alongX, aEnd - dirA, bL + 1));
        }
        // the exit: out of the chapel's far side
        Set<Integer> chap = new HashSet<>();
        for (int k = 0; k < W * H; k++) if (chapel[k]) chap.add(k);
        List<Integer> ex = connectFrom(chap, rid, 30);
        if (ex != null) {
            carvePath(ex, GAL, 3, true);
            int e = ex.get(ex.size() / 3);
            ossExit = new int[]{e % W, e / W};
        }
        // monuments along the route
        List<Integer> marks = new ArrayList<>();
        int[] samar = routePoint(route, rows, 0.25, marks), lamp = routePoint(route, rows, 0.45, marks),
                gil = routePoint(route, rows, 0.62, marks), passion = routePoint(route, rows, 0.8, marks);
        if (samar != null && !rotundaRing(alongX, samar, inReg)) samar = null;
        if (lamp != null && !rotundaRing(alongX, lamp, inReg)) lamp = null;
        if (passion != null && !rotundaRing(alongX, passion, inReg)) passion = null;
        if (samar != null) {
            rotunda(alongX, samar, inReg);
            for (int b = samar[1] - 1; b <= samar[1] + 1; b++) for (int a = samar[0] - 1; a <= samar[0] + 1; a++) pool[ij(alongX, a, b)] = true;
            signNear(alongX, samar, new String[]{"FONTAINE", "DE LA", "SAMARITAINE", "1810"});
            spotAt("Samaritaine fountain", ij(alongX, samar[0], samar[1]));
        }
        if (lamp != null) {
            rotunda(alongX, lamp, inReg);
            int k = ij(alongX, lamp[0], lamp[1]);
            deco(k % W, F + 1, k / W, st("chiseled_sandstone"));
            deco(k % W, F + 2, k / W, st("sandstone_wall[up=true]"));
            deco(k % W, F + 3, k / W, st("campfire[lit=true]"));
            signNear(alongX, lamp, new String[]{"LAMPE", "SÉPULCRALE", "", ""});
            spotAt("Sepulchral lamp", ij(alongX, lamp[0], lamp[1]));
        }
        if (gil != null) {
            int b = gil[1] + 2;
            for (int a = gil[0] - 1; a <= gil[0] + 1; a++) {
                int k = ij(alongX, a, b);
                if (up[k] != 0) continue;
                deco(k % W, F + 1, k / W, st("smooth_stone"));
                deco(k % W, F + 2, k / W, st("smooth_stone"));
            }
            int sk = ij(alongX, gil[0], gil[1] + 1);
            sign(sk % W, F + 2, sk / W, alongX ? 3 : 2, new String[]{"Au banquet", "de la vie,", "infortuné", "convive..."});
            spotAt("Gilbert's tomb", sk);
        }
        if (passion != null) {
            rotunda(alongX, passion, inReg);
            for (int db = -1; db <= 1; db++) for (int da = -1; da <= 1; da++) {
                int k = ij(alongX, passion[0] + da, passion[1] + db), i = k % W, j = k / W;
                if (da == 0 && db == 0) { for (int y = F + 1; y <= F + 3; y++) deco(i, y, j, st("sandstone")); continue; }
                // a barrel of bones laid radially, their heads outward, with a band of skulls looking out
                int di = alongX ? da : db, dj = alongX ? db : da;
                int outward = Math.abs(di) >= Math.abs(dj) ? dirOf(di, 0) : dirOf(0, dj);
                deco(i, F + 1, j, femurs(outward, 4));
                deco(i, F + 2, j, skulls(outward, 4));
                deco(i, F + 3, j, femurs(outward, 4));
            }
            signNear(alongX, passion, new String[]{"CRYPTE", "DE LA", "PASSION", ""});
            spotAt("Crypt of the Passion", ij(alongX, passion[0], passion[1]));
        }
        // cemetery plaques and lights along the route
        String[][] origin = {
                {"OSSEMENTS", "DU CIMETIÈRE", "DES INNOCENTS", "1786"},
                {"OSSEMENTS", "DU CIMETIÈRE", "ST-ÉTIENNE", "1787"},
                {"OSSEMENTS", "DE LA PAROISSE", "ST-LAURENT", "1804"},
                {"OSSEMENTS", "DU CIMETIÈRE", "ST-NICOLAS", "1804"},
                {"OSSEMENTS", "DU CIMETIÈRE", "ST-LANDRY", "1792"},
                {"OSSEMENTS", "DE LA PAROISSE", "STE-MARIE", "1811"}};
        int pl = 0;
        for (int t = 6; t < L - 4; t++) {
            int[] p = route.get(t);
            boolean onRow = false;
            for (int[] r : rows) if (r[0] == p[1]) onRow = true;
            boolean nearMark = false;
            for (int mk : marks) if (Math.abs(mk - t) < 5) nearMark = true;
            if (t % 7 == 0 && !nearMark) { int k = ij(alongX, p[0], p[1]); deco(k % W, F + 3, k / W, st("lantern[hanging=true]")); }
            if (!onRow || nearMark || t % 11 != 0) continue;
            int side = (pl % 2 == 0) ? 1 : -1;
            int wk = ij(alongX, p[0], p[1] + 2 * side), ck = ij(alongX, p[0], p[1] + side);
            if (up[wk] != 0 || up[ck] != OSS) continue;
            deco(wk % W, F + 2, wk / W, st("calcite"));
            int dir = alongX ? (side > 0 ? 3 : 1) : (side > 0 ? 2 : 0);
            sign(ck % W, F + 2, ck / W, dir, origin[pl % origin.length]);
            pl++;
        }
        int[] mid = route.get(L / 2);
        int mk = ij(alongX, mid[0], mid[1]);
        spots.add(new Spot("Ossuary portal", ox + out % W, F + 1, oz + out / W));
        spots.add(new Spot("Ossuary (middle)", ox + mk % W, F + 1, oz + mk / W));
    }

    private void spotAt(String what, int k) { spots.add(new Spot(what, ox + k % W, F + 1, oz + k / W)); }

    private void ossOpen(boolean alongX, int a, int b, int h) {
        int k = ij(alongX, a, b);
        if (up[k] == 0) { up[k] = OSS; hUp[k] = (byte) h; }
    }

    private void rotunda(boolean alongX, int[] p, java.util.function.BiPredicate<Integer, Integer> inReg) {
        for (int db = -2; db <= 2; db++) for (int da = -2; da <= 2; da++)
            if (da * da + db * db <= 8 && inReg.test(p[0] + da, p[1] + db)) ossOpen(alongX, p[0] + da, p[1] + db, 3);
    }

    /** Whether a full ring of cells round a monument can be opened (so it never blocks the route). */
    private boolean rotundaRing(boolean alongX, int[] p, java.util.function.BiPredicate<Integer, Integer> inReg) {
        for (int db = -2; db <= 2; db++) for (int da = -2; da <= 2; da++)
            if ((Math.abs(da) == 2 || Math.abs(db) == 2) && !inReg.test(p[0] + da, p[1] + db)) return false;
        return true;
    }

    /** A point on a straight stretch of a row, at about this fraction of the route. */
    private int[] routePoint(List<int[]> route, List<int[]> rows, double f, List<Integer> marks) {
        int L = route.size();
        for (int d = 0; d < L / 4; d++)
            for (int s : new int[]{1, -1}) {
                int t = (int) (f * L) + s * d;
                if (t < 6 || t >= L - 6) continue;
                int[] p = route.get(t);
                boolean ok = false;
                for (int[] r : rows) if (r[0] == p[1]) ok = true;
                for (int dt = -4; dt <= 4 && ok; dt++) if (route.get(t + dt)[1] != p[1]) ok = false;
                for (int mk : marks) if (Math.abs(mk - t) < 9) ok = false;
                if (ok) { marks.add(t); return p; }
            }
        return null;
    }

    /** A plaque on the wall beside a monument (the wall 3 cells off the row's centre line). */
    private void signNear(boolean alongX, int[] p, String[] lines) {
        for (int da : new int[]{3, -3, 4, -4})
            for (int side : new int[]{1, -1}) {
                int ck = ij(alongX, p[0] + da, p[1] + side), wk = ij(alongX, p[0] + da, p[1] + 2 * side);
                if (up[ck] != OSS || up[wk] != 0) continue;
                sign(ck % W, F + 2, ck / W, alongX ? (side > 0 ? 3 : 1) : (side > 0 ? 2 : 0), lines);
                return;
            }
    }

    // ------------------------------------------------------------------ paths through the rock

    /** Shortest path from a region's open cells to an open cell outside it (not through other regions). */
    private List<Integer> connect(int rid, byte zone, Set<Integer> avoid, int maxLen) {
        Set<Integer> start = new HashSet<>();
        for (int k = 0; k < W * H; k++) if (region[k] == rid && up[k] == zone && !avoid.contains(k)) start.add(k);
        return connectFrom(start, rid, maxLen);
    }

    private List<Integer> connectFrom(Set<Integer> start, int rid, int maxLen) {
        if (start.isEmpty()) return null;
        HashMap<Integer, Integer> prev = new HashMap<>();
        HashMap<Integer, Integer> dist = new HashMap<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        List<Integer> st = new ArrayList<>(start);
        java.util.Collections.shuffle(st, rnd);
        for (int k : st) { dist.put(k, 0); q.add(k); }
        while (!q.isEmpty()) {
            int k = q.poll(), d = dist.get(k), i = k % W, j = k / W;
            if (d >= maxLen) continue;
            for (int[] dd : D4) {
                int ii = i + dd[0], jj = j + dd[1];
                if (!in(ii, jj)) continue;
                int kk = idx(ii, jj);
                if (dist.containsKey(kk)) continue;
                if (up[kk] != 0 && !start.contains(kk) && (region[kk] == 0 || region[kk] != rid) && (up[kk] == GAL || up[kk] == QUARRY || up[kk] == CHAT || up[kk] == ROOMZ)) {
                    List<Integer> path = new ArrayList<>();
                    int cur = k;
                    while (!start.contains(cur)) { path.add(cur); cur = prev.get(cur); }
                    java.util.Collections.reverse(path);
                    return path;
                }
                if (up[kk] != 0 || !town[kk] || !cover(kk, 3) || mat2[kk] != 0 && region[kk] != rid) continue;
                if (region[kk] != 0 && region[kk] != rid) continue;
                if (region[kk] == rid && touchesOpen(kk, rid, start)) continue;
                dist.put(kk, d + 1); prev.put(kk, k); q.add(kk);
            }
        }
        return null;
    }

    /** A region cell beside an open cell of that region that is not a start cell (would break into another room). */
    private boolean touchesOpen(int k, int rid, Set<Integer> start) {
        int i = k % W, j = k / W;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int kk = idx(i + di, j + dj);
            if (kk != k && region[kk] == rid && up[kk] != 0 && !start.contains(kk)) return true;
        }
        return false;
    }

    private void carvePath(List<Integer> path, byte zone, int h, boolean wide) {
        for (int n = 0; n < path.size(); n++) {
            int k = path.get(n), i = k % W, j = k / W;
            mat2[k] = 0;
            int rr = region[k];
            region[k] = 0;
            open(i, j, zone, h);
            region[k] = rr;
            if (wide) {
                int nk = path.get(Math.min(path.size() - 1, n + 1)), pk = path.get(Math.max(0, n - 1));
                int dx = (nk % W) - (pk % W), dz = (nk / W) - (pk / W);
                int si = Math.abs(dx) >= Math.abs(dz) ? 0 : 1, sj = Math.abs(dx) >= Math.abs(dz) ? 1 : 0;
                int ok = idx(i + si, j + sj);
                if (region[ok] == 0 && mat2[ok] == 0) open(i + si, j + sj, zone, h);
            }
        }
    }

    // ------------------------------------------------------------------ stairs and shafts to the streets

    private static final int[][] RING = {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};

    /** A street-level site with a flat, walkable 5x5 (or 3x3 for a manhole) square around it. */
    private boolean site(int i, int j, int r) {
        if (!in(i - r, j - r) || !in(i + r, j + r)) return false;
        int lv = v.lvl[idx(i, j)];
        for (int dj = -r; dj <= r; dj++) for (int di = -r; di <= r; di++) {
            int k = idx(i + di, j + dj);
            byte kk = v.kind[k];
            if (!(streetKind(kk) || kk == Voxelizer.PAVED) || v.lvl[k] != lv || !town[k]) return false;
            if (m.keepOut != null && m.keepOut[k]) return false;
            long ck = ((long) (i + di) << 32) | ((j + dj) & 0xffffffffL);
            if (v.lamps.containsKey(ck) || v.seats.containsKey(ck)) return false;
            if (region[k] != 0 || mat2[k] != 0) return false;
            if (v.s.ground[k] < lv - 2) return false; // a bridge or a raised terrace: no shaft through open air
        }
        for (int[] t : v.bigTrees) if (Math.hypot(t[0] - i, t[1] - j) < 7) return false;
        for (Plaza p : m.plazas) if (p.center().dist(new P(i, j)) < 5) return false; // fountains
        return lv - F > 8;
    }

    /** A spiral stair from a square or street down to the galleries; returns the bottom cell outside the stairwell. */
    private final HashSet<Integer> usedMausoleums = new HashSet<>();

    /** Can a stair go down under this 5 x 5 square (centre i, j)? */
    private boolean stairUnder(int i, int j) { return stairUnder(i, j, 20); }

    private boolean stairUnder(int i, int j, double gap) {
        if (!in(i - 2, j - 2) || !in(i + 2, j + 2)) return false;
        for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
            int kk = idx(i + di, j + dj);
            if (up[kk] != 0 && up[kk] != GAL || region[kk] != 0 || mat2[kk] != 0) return false;
        }
        for (int[] e : entrances) if (Math.hypot(e[0] - i, e[1] - j) < gap) return false;
        return true;
    }

    /**
     * A way down near a point. Where the town has cemeteries, it is a spiral stair inside one of their mausoleums;
     * otherwise a stair opening from a square or street. The entrance stands a walk away from the ossuary (as the real
     * one does, through the galleries and old quarries); the exit is as close to the end of the route as a site allows.
     */
    private int[] spiral(int[] near, boolean entrance) {
        int bm = -1;
        double bmd = 1e18;
        for (int n = 0; n < v.mausoleums.size(); n++) {
            int[] mz = v.mausoleums.get(n);
            if (usedMausoleums.contains(n)) continue;
            double dist = Math.hypot(mz[0] - near[0], mz[1] - near[1]);
            if (dist > (entrance ? 230 : 120)) continue;
            double d = entrance ? Math.abs(dist - 60) : dist;
            if (d >= bmd || mz[2] - F <= 8 || !stairUnder(mz[0], mz[1], 8)) continue;
            bmd = d; bm = n;
        }
        if (bm >= 0) {
            int[] mz = v.mausoleums.get(bm);
            usedMausoleums.add(bm);
            return buildSpiral(mz[0], mz[1], mz[2], mz[3], true, entrance ? "Entrance stair (mausoleum)" : "Exit stair (mausoleum)");
        }
        int best = -1;
        double bd = 1e18;
        for (int j = 3; j < H - 3; j++) for (int i = 3; i < W - 3; i++) {
            double dist = Math.hypot(i - near[0], j - near[1]);
            if (dist > 95) continue;
            double d = entrance ? Math.abs(dist - 60) : dist;
            if (d >= bd) continue;
            int k = idx(i, j);
            if (!streetKind(v.kind[k])) continue;
            if (!stairUnder(i, j) || !site(i, j, 2)) continue;
            bd = d; best = k;
        }
        if (best < 0) return null;
        return buildSpiral(best % W, best / W, v.lvl[best], 0, false, entrance ? "Entrance stair" : "Exit stair");
    }

    /** Every mausoleum that can have one gets a stair down: the cemeteries are the usual ways into the catacombs. */
    private void mausoleumStairs() {
        for (int n = 0; n < v.mausoleums.size(); n++) {
            int[] mz = v.mausoleums.get(n);
            if (usedMausoleums.contains(n) || mz[2] - F <= 8 || !stairUnder(mz[0], mz[1], 8)) continue;
            usedMausoleums.add(n);
            buildSpiral(mz[0], mz[1], mz[2], mz[3], true, "Mausoleum stair");
        }
    }

    private static int[] turnQ(int x, int z, int q) {
        for (int n = 0; n < q; n++) { int t = x; x = -z; z = t; }
        return new int[]{x, z};
    }

    /**
     * A spiral stair centred on (ci, cj) from 'top' (the walking surface) down to the gallery floor, entered from the
     * side q quarter turns clockwise from north. In a mausoleum the building stands round it (its floor is the stair's
     * frame, a lantern replaces the sarcophagus); in the street it gets a frame, a railing and a lantern on the newel.
     */
    private int[] buildSpiral(int ci, int cj, int top, int q, boolean mausoleum, String label) {
        int[][] ring = new int[8][];
        for (int n = 0; n < 8; n++) ring[n] = turnQ(RING[n][0], RING[n][1], q);
        int[] entry = turnQ(0, -2, q);
        int sEnd = top - (F + 1);
        // the bottom step lands so that the next free side opens toward the galleries
        int p0 = 1; // top step: on the entry side
        int pEnd = Math.floorMod(p0 + sEnd, 8);
        int exitIdx = -1;
        for (int t = 2; t <= 5; t++) { int pi = Math.floorMod(pEnd + t, 8); if (pi % 2 == 1) { exitIdx = pi; break; } }
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int k = idx(ci + di, cj + dj);
            if (up[k] == GAL) galleryCells--;
            up[k] = di == 0 && dj == 0 ? 0 : STAIR; hUp[k] = 3; mat2[k] = di == 0 && dj == 0 ? M_MASONRY : 0;
        }
        int rid = nextRegion++;
        regionZone.put(rid, (int) STAIR);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) region[idx(ci + di, cj + dj)] = rid;
        // open column above the stairwell's 2D carving, then straight up to the surface
        for (int y = F + 4; y <= top; y++)
            for (int[] r : ring) put3(ci + r[0], y, cj + r[1], STAIR);
        for (int y = Math.max(F + 1, yHi); y <= top; y++) {
            directAt(ci, y, cj, st("smooth_sandstone"));
            for (int[] r : ring) directAt(ci + r[0], y, cj + r[1], st("air"));
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++)
                if (Math.abs(di) == 2 || Math.abs(dj) == 2) directAt(ci + di, y, cj + dj, st(y == top ? "polished_andesite" : "cut_sandstone"));
        }
        directAt(ci, top + 1, cj, st("lantern"));
        if (mausoleum) directAt(ci, top + 2, cj, st("air")); // the sarcophagus's candle
        // steps: one per ring cell, clockwise going down
        for (int s = 0; s <= sEnd; s++) {
            int pi = Math.floorMod(p0 + s, 8), y = top - s;
            int[] r = ring[pi];
            int face;
            if (s == 0) face = (3 + q) % 4; // up toward the entry
            else { int[] rp = ring[Math.floorMod(pi - 1, 8)]; face = dirOf(rp[0] - r[0], rp[1] - r[1]); }
            BlockState stair = st("sandstone_stairs[facing=" + DIR_NAME[face] + "]");
            at(ci + r[0], y, cj + r[1], stair);
            if (y - 1 >= F + 1) at(ci + r[0], y - 1, cj + r[1], st("smooth_sandstone"));
        }
        if (!mausoleum) {
            // railing round the opening, open on the entry side
            List<int[]> rail = new ArrayList<>();
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++)
                if ((Math.abs(di) == 2 || Math.abs(dj) == 2) && !(di == entry[0] && dj == entry[1])) rail.add(new int[]{ci + di, cj + dj});
            Set<Long> railSet = new HashSet<>();
            for (int[] rr : rail) railSet.add(((long) rr[0] << 32) | (rr[1] & 0xffffffffL));
            for (int[] rr : rail) {
                StringBuilder sb = new StringBuilder("iron_bars[");
                for (int d = 0; d < 4; d++) {
                    long nk = ((long) (rr[0] + D4[d][0]) << 32) | ((rr[1] + D4[d][1]) & 0xffffffffL);
                    sb.append(d > 0 ? "," : "").append(DIR_NAME[d]).append('=').append(railSet.contains(nk));
                }
                directAt(rr[0], top + 1, rr[1], st(sb.append(']').toString()));
            }
        }
        entrances.add(new int[]{ci, cj, top});
        spots.add(new Spot(label, ox + ci + entry[0], top + 1, oz + cj + entry[1]));
        if (mausoleum) mausoleumStairs++;
        // passage from the foot of the stair to the galleries
        int[] r = ring[exitIdx];
        int oi = ci + r[0] * 2, oj = cj + r[1] * 2;
        int ok = idx(oi, oj);
        if (up[ok] == 0) {
            int rr2 = region[ok];
            region[ok] = 0;
            open(oi, oj, GAL, 3);
            region[ok] = rr2;
        }
        Set<Integer> st0 = new HashSet<>();
        st0.add(ok);
        if (!(up[ok] == GAL && touchesGallery(ok, rid))) {
            List<Integer> path = connectFrom(st0, -99, 40);
            if (path != null) carvePath(path, GAL, 3, true);
        }
        return new int[]{oi, oj};
    }

    private boolean touchesGallery(int k, int rid) {
        int i = k % W, j = k / W;
        for (int[] d : D4) { int kk = idx(i + d[0], j + d[1]); if (up[kk] == GAL && region[kk] != rid) return true; }
        return false;
    }

    /** Ladders up to iron-framed manholes in the street. */
    private void manholes() {
        // where the cemeteries' mausoleums give the ways in, a manhole or two is enough
        int want = mausoleumStairs > 0 ? 1 : Math.min(5, 2 + galleryCells / 1500);
        List<int[]> placed = new ArrayList<>();
        List<Integer> cand = new ArrayList<>();
        for (int k = 0; k < W * H; k++) if (up[k] == GAL && region[k] == 0 && v.kind[k] == Voxelizer.STREET) cand.add(k);
        java.util.Collections.shuffle(cand, rnd);
        // parts of the network cut off from the stairs (by a river, say) get a manhole of their own first
        int[] comp = new int[W * H];
        int nc = 0;
        List<Integer> sizes = new ArrayList<>();
        sizes.add(0);
        for (int k0 = 0; k0 < W * H; k0++) {
            if (up[k0] == 0 || comp[k0] != 0) continue;
            nc++;
            int size = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(k0); comp[k0] = nc;
            while (!q.isEmpty()) {
                int k = q.poll(); size++;
                for (int[] d : D4) { int kk = k + d[0] + d[1] * W; if (kk >= 0 && kk < W * H && up[kk] != 0 && comp[kk] == 0) { comp[kk] = nc; q.add(kk); } }
            }
            sizes.add(size);
        }
        boolean[] served = new boolean[nc + 1];
        for (int[] e : entrances) for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) { int kk = idx(e[0] + di, e[1] + dj); if (up[kk] != 0) served[comp[kk]] = true; }
        List<Integer> ordered = new ArrayList<>();
        for (int k : cand) if (!served[comp[k]] && sizes.get(comp[k]) >= 60) ordered.add(k);
        for (int k : cand) if (!ordered.contains(k)) ordered.add(k);
        int extraWant = 0;
        for (int c2 = 1; c2 <= nc; c2++) if (!served[c2] && sizes.get(c2) >= 60) extraWant++;
        want = Math.min(want + extraWant, 12);
        for (int k : ordered) {
            if (placed.size() >= want) break;
            if (served[comp[k]] && placed.size() >= want - countUnserved(served, sizes)) continue;
            int i = k % W, j = k / W;
            boolean far = true;
            double gap = served[comp[k]] ? 45 : 12;
            for (int[] p : placed) if (Math.hypot(p[0] - i, p[1] - j) < gap) far = false;
            for (int[] e : entrances) if (Math.hypot(e[0] - i, e[1] - j) < 20) far = false;
            if (!far || !site(i, j, 1)) continue;
            int wall = -1;
            for (int d = 0; d < 4 && wall < 0; d++) { int kk = idx(i + D4[d][0], j + D4[d][1]); if (up[kk] == 0 && mat2[kk] == 0) wall = d; }
            if (wall < 0) continue;
            int top = v.lvl[k];
            String face = DIR_NAME[(wall + 2) % 4];
            for (int y = F + 1; y < top; y++) {
                if (y > F + 3) put3(i, y, j, SHAFT);
                at(i, y, j, st("ladder[facing=" + face + "]"));
                if (y >= yHi) for (int[] r : RING) directAt(i + r[0], y, j + r[1], st("cut_sandstone"));
            }
            for (int[] r : RING) directAt(i + r[0], top, j + r[1], st("polished_andesite"));
            directAt(i, top, j, st("spruce_trapdoor[half=top,facing=" + face + ",open=false]"));
            placed.add(new int[]{i, j});
            served[comp[k]] = true;
            manholes++;
            spots.add(new Spot("Manhole", ox + i, top + 1, oz + j));
        }
    }

    private static int countUnserved(boolean[] served, List<Integer> sizes) {
        int n = 0;
        for (int c = 1; c < served.length; c++) if (!served[c] && sizes.get(c) >= 60) n++;
        return n;
    }

    // ------------------------------------------------------------------ lower level

    private void rampAndLower() {
        if (!c.b("catacombs.lowerLevel") || FL - 3 < v.plan.minY + 2) return;
        List<Integer> cand = new ArrayList<>();
        for (int k = 0; k < W * H; k++) if ((up[k] == QUARRY || up[k] == GAL) && region[k] == 0 || up[k] == QUARRY) cand.add(k);
        java.util.Collections.shuffle(cand, rnd);
        for (int k : cand) {
            int i = k % W, j = k / W;
            for (int d = 0; d < 4; d++) {
                int dx = D4[d][0], dz = D4[d][1], sx = dz != 0 ? 1 : 0, sz = dx != 0 ? 1 : 0;
                boolean ok = true;
                for (int s = 1; s <= 9 && ok; s++)
                    for (int w = -1; w <= 2 && ok; w++) {
                        int ii = i + dx * s + sx * w, jj = j + dz * s + sz * w;
                        if (!in(ii, jj)) { ok = false; break; }
                        int kk = idx(ii, jj);
                        // the ramp's two cells are rock; beside it nothing but rock or a plain gallery at its head
                        if (w == 0 || w == 1) { if (up[kk] != 0 || region[kk] != 0 || mat2[kk] != 0 || !town[kk]) ok = false; }
                        else if (s >= 2 && (up[kk] != 0 || region[kk] != 0)) ok = false;
                    }
                if (!ok) continue;
                int sw = up[idx(i + sx, j + sz)];
                if (sw == 0) continue; // the ramp is 2 wide: both cells at its head must be open
                ramp(i, j, d);
                return;
            }
        }
    }

    private void ramp(int i, int j, int d) {
        int dx = D4[d][0], dz = D4[d][1], sx = dz != 0 ? 1 : 0, sz = dx != 0 ? 1 : 0;
        int rid = nextRegion++;
        regionZone.put(rid, (int) STAIR);
        String back = DIR_NAME[(d + 2) % 4];
        for (int s = 1; s <= 9; s++)
            for (int w = 0; w <= 1; w++) {
                int ii = i + dx * s + sx * w, jj = j + dz * s + sz * w;
                int fl = F - Math.min(7, s);
                region[idx(ii, jj)] = rid;
                for (int y = fl + 1; y <= fl + 4; y++) put3(ii, y, jj, STAIR);
                if (s <= 7) at(ii, fl, jj, st("sandstone_stairs[facing=" + back + "]"));
                if (s >= 8) { lo[idx(ii, jj)] = LOWER; hLo[idx(ii, jj)] = 3; }
            }
        // pilasters framing the head of the ramp, a plaque on one of them
        boolean signed = false;
        for (int w : new int[]{-1, 2}) {
            int ii = i + dx + sx * w, jj = j + dz + sz * w;
            if (!in(ii, jj) || up[idx(ii, jj)] != 0) continue;
            region[idx(ii, jj)] = rid;
            at(ii, F + 1, jj, st("smooth_sandstone"));
            at(ii, F + 2, jj, st("smooth_sandstone"));
            at(ii, F + 3, jj, st("chiseled_sandstone"));
            int si0 = ii - dx, sj0 = jj - dz;
            if (!signed && in(si0, sj0) && up[idx(si0, sj0)] != 0 && hUp[idx(si0, sj0)] >= 3) {
                sign(si0, F + 2, sj0, (d + 2) % 4, new String[]{"", "CARRIÈRES", "NIVEAU", "INFÉRIEUR"});
                signed = true;
            }
        }
        lower = true;
        int si = i + dx * 9, sj = j + dz * 9;
        spots.add(new Spot("Ramp to the lower level", ox + i + dx, F + 1, oz + j + dz));
        // sinuous original galleries: walkers from the foot of the ramp
        int budget = Math.min(3200, Math.max(400, galleryCells / 2));
        List<int[]> starts = new ArrayList<>(List.of(new int[]{si, sj, d}, new int[]{si, sj, (d + 1) % 4}, new int[]{si, sj, (d + 3) % 4}));
        List<Integer> opened = new ArrayList<>();
        for (int w = 0; w < 40 && lowerCells < budget; w++) {
            int[] s0;
            if (w < starts.size()) s0 = starts.get(w);
            else { // branch off somewhere along what is dug already
                if (opened.isEmpty()) break;
                int k0 = opened.get(rnd.nextInt(opened.size()));
                s0 = new int[]{k0 % W, k0 / W, rnd.nextInt(4)};
            }
            int x = s0[0], z = s0[1], dir = s0[2];
            for (int seg = 0; seg < 22 && lowerCells < budget; seg++) {
                int len = 4 + rnd.nextInt(6), h = rnd.nextDouble() < 0.6 ? 3 : 2;
                boolean moved = false;
                for (int t = 0; t < len; t++) {
                    int nx = x + D4[dir][0], nz = z + D4[dir][1];
                    if (!lowerOk(nx, nz)) break;
                    // the old galleries wander but rarely run alongside each other
                    int ahead = idx(nx + D4[dir][0], nz + D4[dir][1]);
                    if (t > 0 && lo[ahead] != 0 && rnd.nextDouble() < 0.6) { x = nx; z = nz; lowOpen(x, z, h); moved = true; break; }
                    x = nx; z = nz; moved = true;
                    lowOpen(x, z, h);
                    lowOpen(x + (D4[dir][1] != 0 ? 1 : 0), z + (D4[dir][0] != 0 ? 1 : 0), h);
                    opened.add(idx(x, z));
                }
                if (moved && rnd.nextDouble() < 0.07) {
                    double r = 2.5 + 1.5 * rnd.nextDouble();
                    for (int dj = -4; dj <= 4; dj++) for (int di = -4; di <= 4; di++)
                        if (di * di + dj * dj <= r * r && !(r >= 3 && di == 0 && dj == 0) && lowerOk(x + di, z + dj)) lowOpen(x + di, z + dj, 3);
                }
                double tr = rnd.nextDouble();
                dir = tr < 0.3 ? (dir + 1) % 4 : tr < 0.6 ? (dir + 3) % 4 : (moved ? dir : (dir + 2) % 4);
            }
        }
        // flooded stretches
        List<Integer> lc = new ArrayList<>();
        for (int k = 0; k < W * H; k++) if (lo[k] == LOWER && Math.hypot(k % W - si, k / W - sj) > 14) lc.add(k);
        for (int f = 0; f < 2 && !lc.isEmpty(); f++) {
            int k0 = lc.get(rnd.nextInt(lc.size()));
            double r = 5 + rnd.nextInt(4);
            for (int k : lc) if (Math.hypot(k % W - k0 % W, k / W - k0 / W) <= r) flood[k] = true;
            if (f == 0) spots.add(new Spot("Flooded gallery (lower level)", ox + k0 % W, FL + 1, oz + k0 / W));
        }
    }

    private boolean lowerOk(int i, int j) { return in(i, j) && town[idx(i, j)] && region[idx(i, j)] == 0; }

    private void lowOpen(int i, int j, int h) {
        if (!lowerOk(i, j)) return;
        int k = idx(i, j);
        if (lo[k] == 0) { lo[k] = LOWER; hLo[k] = (byte) h; lowerCells++; }
    }

    // ------------------------------------------------------------------ fontis

    private void fontis() {
        List<Integer> cand = new ArrayList<>();
        for (int k = 0; k < W * H; k++) if (up[k] == GAL && region[k] == 0) cand.add(k);
        java.util.Collections.shuffle(cand, rnd);
        int want = Math.min(12, 1 + galleryCells / 350);
        List<int[]> placed = new ArrayList<>();
        for (int k : cand) {
            if (placed.size() >= want) break;
            int i = k % W, j = k / W;
            boolean far = true;
            for (int[] p : placed) if (Math.hypot(p[0] - i, p[1] - j) < 25) far = false;
            for (int[] e : entrances) if (Math.hypot(e[0] - i, e[1] - j) < 8) far = false;
            if (!far) continue;
            boolean painted = placed.isEmpty();
            int Hb = painted ? 9 : 5 + rnd.nextInt(4);
            int lim = Integer.MAX_VALUE;
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) lim = Math.min(lim, surf[idx(i + di, j + dj)] - 5 - (F + 3));
            Hb = Math.min(Hb, Math.min(lim, yHi - 1 - (F + 3)));
            if (Hb < 4) continue;
            byte z = painted ? PAINTED : FONTIS;
            for (int t = 1; t <= Hb; t++) {
                double f = (t - 1) / (double) Hb, r = 1.0 + (painted ? 1.6 : 1.3) * Math.pow(1 - f, 0.7);
                for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++)
                    if (Math.hypot(di, dj) <= r + 0.3 * ((hash(i + di, t, j + dj) % 100) / 100.0)) put3(i + di, F + 3 + t, j + dj, z);
            }
            // a widened foot so the bell stands over open floor
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) if (Math.hypot(di, dj) <= 2.2) open(i + di, j + dj, painted ? GAL : CHAT, 3);
            if (!painted) {
                at(i, F + 1, j, st("cobblestone"));
                for (int[] dd : D4) if (hash(i + dd[0], 9, j + dd[1]) < 500 && up[idx(i + dd[0], j + dd[1])] != 0) at(i + dd[0], F + 1, j + dd[1], st("coarse_dirt"));
            }
            placed.add(new int[]{i, j});
            fontisAt.add(new int[]{i, j});
            fontis++;
            if (painted) spots.add(new Spot("Consolidated fontis (painted strata)", ox + i, F + 1, oz + j));
        }
    }

    // ------------------------------------------------------------------ the visitor route

    private void visitorRoute(int[] ent, int[] door) {
        if (ent == null || door == null) return;
        // shortest walk through the open upper cells
        int s = idx(ent[0], ent[1]), t = idx(door[0], door[1]);
        HashMap<Integer, Integer> prev = new HashMap<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        prev.put(s, -1);
        q.add(s);
        while (!q.isEmpty() && !prev.containsKey(t)) {
            int k = q.poll(), i = k % W, j = k / W;
            for (int[] d : D4) {
                int kk = idx(i + d[0], j + d[1]);
                if (prev.containsKey(kk) || up[kk] == 0 || up[kk] == OSS || up[kk] == STAIR || up[kk] == BUNKER) continue;
                prev.put(kk, k);
                q.add(kk);
            }
        }
        if (!prev.containsKey(t)) return;
        List<Integer> path = new ArrayList<>();
        for (int k = t; k != -1; k = prev.get(k)) path.add(k);
        java.util.Collections.reverse(path);
        routeCells.addAll(path);
        int n = path.size();
        for (int a = 0; a < n; a++) {
            int k = path.get(a), i = k % W, j = k / W;
            boolean underBell = false;
            for (int[] f : fontisAt) if (Math.abs(f[0] - i) <= 3 && Math.abs(f[1] - j) <= 3) underBell = true;
            if (!underBell) deco(i, F + hUp[k] + 1, j, st("black_concrete")); // the guide line on the ceiling
            if (a % 8 == 4 && hUp[k] >= 3 && !underBell) deco(i, F + hUp[k], j, st("lantern[hanging=true]"));
        }
        // the quarrymen's footbath and the Port-Mahon model, in alcoves beside the route
        boolean bath = false, mahon = false;
        for (int a = Math.max(2, n / 5); a < n - 2 && !(bath && mahon); a++) {
            int k = path.get(a), i = k % W, j = k / W, k2 = path.get(a + 1), dx = k2 % W - i, dz = k2 / W - j;
            boolean wantBath = !bath && a > n / 4, wantMahon = bath && !mahon && a > n / 2;
            if (!wantBath && !wantMahon || Math.abs(dx) + Math.abs(dz) != 1) continue;
            for (int side : new int[]{1, -1}) {
                int sx = -dz * side, sz = dx * side, ai = i + sx, aj = j + sz;
                if (in(ai, aj) && up[idx(ai, aj)] != 0) { ai += sx; aj += sz; } // step out of the 2-wide gallery
                if (!alcoveOk(ai, aj) || !in(ai + sx, aj + sz) || up[idx(ai + sx, aj + sz)] != 0) continue;
                int faceIn = dirOf(-sx, -sz);
                if (wantBath) {
                    open(ai, aj, GAL, 3);
                    pool[idx(ai, aj)] = true;
                    region[idx(ai, aj)] = -1;
                    at(ai, F + 1, aj, st("iron_bars[" + barProps(faceIn) + "]"));
                    sign(ai, F + 3, aj, faceIn, new String[]{"BAIN DE PIEDS", "DES CARRIERS", "", ""});
                    bath = true;
                    spots.add(new Spot("Quarrymen's footbath", ox + ai, F + 1, oz + aj));
                    break;
                }
                int bi = ai + dx, bj = aj + dz, ci = ai - dx, cj = aj - dz;
                if (!alcoveOk(bi, bj) || !alcoveOk(ci, cj)) continue;
                for (int[] cc : new int[][]{{ai, aj}, {bi, bj}, {ci, cj}}) { open(cc[0], cc[1], GAL, 3); region[idx(cc[0], cc[1])] = -1; }
                // a little fort: towers at the ends, a gate between ramparts
                at(bi, F + 1, bj, st("chiseled_sandstone")); at(bi, F + 2, bj, st("sandstone_wall[up=true]"));
                at(ci, F + 1, cj, st("chiseled_sandstone")); at(ci, F + 2, cj, st("sandstone_wall[up=true]"));
                at(ai, F + 1, aj, st("cut_sandstone_slab[type=top]"));
                at(ai, F + 2, aj, st("sandstone_wall[up=true]"));
                sign(ai, F + 3, aj, faceIn, new String[]{"PORT-MAHON", "DÉCURE", "1777-1782", ""});
                mahon = true;
                spots.add(new Spot("Port-Mahon model", ox + ai, F + 1, oz + aj));
                break;
            }
        }
    }

    private boolean alcoveOk(int i, int j) {
        if (!in(i, j)) return false;
        int k = idx(i, j);
        return up[k] == 0 && region[k] == 0 && mat2[k] == 0 && town[k] && cover(k, 3);
    }

    private static String barProps(int face) {
        // a railing across the alcove's mouth: bars joined along the gallery
        boolean x = face == 1 || face == 3;
        return "east=" + x + ",west=" + x + ",north=" + !x + ",south=" + !x;
    }

    // ------------------------------------------------------------------ levels, grates and arches

    /**
     * Some streets' galleries run a step (a metre) lower than the crossroads they join, reached by shallow ramps of
     * slabs, so the network has several elevations as the old quarry floors did. The visitor route keeps its level.
     */
    private void lowerSections() {
        HashSet<Integer> route = new HashSet<>(routeCells);
        double share = c.d("catacombs.lowerShare");
        // under a wide street the main tunnel follows one of the two galleries; the other runs a step lower
        for (Edge e : m.g.aliveEdges()) {
            List<Integer> cells = edgeCells.get(e.id);
            if (cells == null || e.width < 8) continue;
            P a0 = e.a.p, u = e.b.p.sub(a0).norm(), nrm = u.perpL();
            double off = e.width / 2 - 1.5, side = 0;
            for (int k : cells) if (route.contains(k)) side += Math.signum(new P(k % W, k / W).sub(a0).dot(nrm));
            if (side == 0) continue;
            double ra = crossR.getOrDefault(e.a.id, 1.5), rb = crossR.getOrDefault(e.b.id, 1.5);
            boolean ok = true;
            for (int k : cells) for (int dj = -1; dj <= 1 && ok; dj++) for (int di = -1; di <= 1 && ok; di++)
                if (!route.contains(k) && deco.containsKey(BlockPos.asLong(ox + k % W + di, F + 1, oz + k / W + dj))) ok = false;
            if (!ok) continue;
            int n = 0;
            for (int k : cells) {
                if (up[k] != GAL || region[k] != 0 || route.contains(k)) continue;
                P q = new P(k % W, k / W);
                double o = q.sub(a0).dot(nrm);
                if (Math.signum(o) == Math.signum(side) || Math.abs(o) < off - 1.2) continue;
                double d = Math.min(q.dist(e.a.p) - ra, q.dist(e.b.p) - rb);
                if (d <= 1.5) continue;
                dUp[k] = -1;
                n++;
                if (d <= 3.0) deco(k % W, F, k / W, st("smooth_sandstone_slab[type=bottom]"));
            }
            if (n > 0) lowered++;
        }
        for (Edge e : m.g.aliveEdges()) {
            List<Integer> cells = edgeCells.get(e.id);
            if (cells == null || e.width >= 8 || e.a.p.dist(e.b.p) < 16) continue;
            if (Voxelizer.hash(e.id, 41, 7) % 1000 >= share * 1000) continue;
            double ra = crossR.getOrDefault(e.a.id, 1.5), rb = crossR.getOrDefault(e.b.id, 1.5);
            boolean ok = true;
            for (int k : cells) {
                if (route.contains(k)) { ok = false; break; }
                int i = k % W, j = k / W;
                // nothing standing on this floor already (a heap under a fontis, a manhole's ladder)
                for (int dj = -1; dj <= 1 && ok; dj++) for (int di = -1; di <= 1 && ok; di++)
                    if (deco.containsKey(BlockPos.asLong(ox + i + di, F + 1, oz + j + dj))) ok = false;
            }
            if (!ok) continue;
            int n = 0;
            for (int k : cells) {
                if (up[k] != GAL || region[k] != 0) continue;
                P q = new P(k % W, k / W);
                double da = q.dist(e.a.p) - ra, db = q.dist(e.b.p) - rb, d = Math.min(da, db);
                if (d <= 1.5) continue;
                dUp[k] = -1;
                n++;
                if (d <= 3.0) deco(k % W, F, k / W, st("smooth_sandstone_slab[type=bottom]")); // the ramp's half step
            }
            if (n > 0) lowered++;
        }
        // old quarry chambers worked at a lower floor: about half of them, unless something already stands on the floor
        for (var e : regionZone.entrySet()) {
            if (e.getValue() != QUARRY || Voxelizer.hash(e.getKey(), 43, 9) % 2 != 0) continue;
            int rid = e.getKey();
            boolean ok = true;
            List<Integer> cells = new ArrayList<>();
            for (int k = 0; k < W * H && ok; k++) {
                if (region[k] != rid || up[k] != QUARRY) continue;
                cells.add(k);
                if (deco.containsKey(BlockPos.asLong(ox + k % W, F + 1, oz + k / W))) ok = false;
            }
            if (!ok || cells.isEmpty()) continue;
            for (int k : cells) dUp[k] = -1;
            lowQuarries++;
        }
    }

    /**
     * Grates in the side of the main tunnel: a window of iron bars through which you look down into a closed-off
     * stretch of gallery a step lower, dimly lit.
     */
    private void grates() {
        int n = routeCells.size(), last = -100;
        for (int a = 4; a < n - 4; a++) {
            if (a - last < 10) continue;
            int k = routeCells.get(a), i = k % W, j = k / W;
            if (up[k] != GAL || hUp[k] < 3 || dUp[k] != 0 || region[k] != 0) continue;
            boolean done = false;
            for (int[] s0 : D4) {
                int sx = s0[0], sz = s0[1], ux = sz != 0 ? 1 : 0, uz = sx != 0 ? 1 : 0; // out to the side, and along the wall
                int wi = i + sx, wj = j + sz;
                if (in(wi, wj) && up[idx(wi, wj)] != 0) { wi += sx; wj += sz; } // across the 2-wide gallery
                if (!in(wi, wj)) continue;
                int wk = idx(wi, wj);
                if (up[wk] != 0 || region[wk] != 0 || mat2[wk] != 0 || up[idx(wi - sx, wj - sz)] == 0) continue;
                if (deco.containsKey(BlockPos.asLong(ox + wi, F + 2, oz + wj)) || deco.containsKey(BlockPos.asLong(ox + wi - sx, F + 2, oz + wj - sz))) continue;
                // 1. a window onto a gallery or quarry already a step lower; 2. a closed-off stretch right behind the wall;
                // 3. a short window passage onto a small lower chamber (fits beside diagonal galleries too)
                done = window(wi, wj, sx, sz, ux, uz) || pocket(wi, wj, sx, sz, ux, uz, 1, 2, 2) || pocket(wi, wj, sx, sz, ux, uz, 2, 3, 1);
                if (done) {
                    grates++;
                    last = a;
                    if (grates == 1) spotAt("Grate onto a lower gallery", k);
                    break;
                }
            }
        }
    }

    /**
     * A sealed pocket a step lower behind a grate at (wi, wj): 'deep' rows of cells starting 'from' cells beyond the
     * grate, 2 * half + 1 cells long; any cells between the grate and the pocket become a 2-high window passage.
     */
    private boolean pocket(int wi, int wj, int sx, int sz, int ux, int uz, int from, int deep, int half) {
        List<Integer> pocket = new ArrayList<>();
        for (int t = -half; t <= half; t++) for (int dd = from; dd < from + deep; dd++) {
            int pi = wi + sx * dd + ux * t, pj = wj + sz * dd + uz * t;
            if (!in(pi, pj)) return false;
            int pk = idx(pi, pj);
            boolean quarryRock = region[pk] > 0 && regionZone.getOrDefault(region[pk], 0) == QUARRY;
            if (up[pk] != 0 || (region[pk] != 0 && !quarryRock) || mat2[pk] != 0 || !town[pk] || !cover(pk, 4)) return false;
            pocket.add(pk);
        }
        List<Integer> passage = new ArrayList<>();
        for (int t = 1; t < from; t++) {
            int ck = idx(wi + sx * t, wj + sz * t);
            if (up[ck] != 0 || region[ck] != 0 || mat2[ck] != 0) return false;
            passage.add(ck);
        }
        HashSet<Integer> mine = new HashSet<>(pocket);
        mine.addAll(passage);
        mine.add(idx(wi, wj));
        for (int pk : mine) {
            if (pk == idx(wi, wj)) continue;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int qk = pk + di + dj * W;
                if (!mine.contains(qk) && up[qk] != 0) return false;
            }
        }
        for (int pk : pocket) { up[pk] = GAL; hUp[pk] = 3; dUp[pk] = -1; region[pk] = -1; }
        String bars = barsAcross(ux);
        region[idx(wi, wj)] = -1;
        for (int y = F + 1; y <= F + 2; y++) { put3(wi, y, wj, GAL); deco(wi, y, wj, st(bars)); }
        for (int ck : passage) { region[ck] = -1; for (int y = F + 1; y <= F + 2; y++) put3(ck % W, y, ck / W, GAL); }
        // something to see down there: a lamp left burning, and a heap of rubble or bones
        int far = idx(wi + sx * (from + deep - 1) + ux * half, wj + sz * (from + deep - 1) + uz * half);
        int mid = idx(wi + sx * (from + deep - 1) - ux * half, wj + sz * (from + deep - 1) - uz * half);
        deco(far % W, F + 3, far / W, st("lantern[hanging=true]"));
        deco(mid % W, F, mid / W, st(ossuary && grates % 2 == 0 ? "emergentcity:bone_gravel" : "cobblestone"));
        return true;
    }

    private static String barsAcross(int ux) {
        return "iron_bars[" + (ux != 0 ? "east=true,west=true,north=false,south=false" : "east=false,west=false,north=true,south=true") + "]";
    }

    /**
     * A grate at (wi, wj) on a window through 1-3 blocks of rock onto a quarry chamber worked a step lower; the
     * window is 2 high and nothing else opens off it.
     */
    private boolean window(int wi, int wj, int sx, int sz, int ux, int uz) {
        for (int depth = 1; depth <= 5; depth++) {
            int qi = wi + sx * depth, qj = wj + sz * depth;
            if (!in(qi, qj)) return false;
            int qk = idx(qi, qj);
            if ((up[qk] == QUARRY || up[qk] == GAL) && dUp[qk] == -1 && hUp[qk] >= 3) {
                for (int t = 0; t < depth; t++) {
                    int ci = wi + sx * t, cj = wj + sz * t, ck = idx(ci, cj);
                    if (up[ck] != 0 || mat2[ck] != 0 && t > 0) return false;
                    for (int s2 : new int[]{-1, 1}) if (t > 0 && up[idx(ci + ux * s2, cj + uz * s2)] != 0) return false;
                    for (int y = F + 1; y <= F + 2; y++) if (deco.containsKey(BlockPos.asLong(ox + ci, y, oz + cj))) return false;
                }
                String bars = barsAcross(ux);
                for (int t = 0; t < depth; t++) {
                    int ci = wi + sx * t, cj = wj + sz * t;
                    region[idx(ci, cj)] = -1;
                    mat2[idx(ci, cj)] = 0;
                    for (int y = F + 1; y <= F + 2; y++) { put3(ci, y, cj, GAL); if (t == 0) deco(ci, y, cj, st(bars)); }
                }
                return true;
            }
            int ck = idx(qi, qj);
            if (up[ck] != 0) return false;
        }
        return false;
    }

    /**
     * Masonry arches across straight 2-wide galleries every ten blocks: the top course corbelled out from both walls
     * (upside-down stairs), on dressed piers.
     */
    private void arches() {
        for (int k = 0; k < W * H; k++) {
            if (up[k] != GAL || region[k] != 0 || hUp[k] != 3) continue;
            int i = k % W, j = k / W;
            for (int axis = 0; axis < 2; axis++) {
                // the gallery runs along x (axis 0) with cells j and j+1, or along z (axis 1) with cells i and i+1
                int along = axis == 0 ? i : j;
                if (Math.floorMod(along, 10) != 0) continue;
                int ax = axis == 0 ? 0 : 1, az = axis == 0 ? 1 : 0; // across
                int bx = axis == 0 ? 1 : 0, bz = axis == 0 ? 0 : 1; // along
                int k2 = idx(i + ax, j + az), w1 = idx(i - ax, j - az), w2 = idx(i + 2 * ax, j + 2 * az);
                if (up[k2] != GAL || region[k2] != 0 || hUp[k2] != 3 || dUp[k2] != dUp[k]) continue;
                if (up[w1] != 0 || up[w2] != 0 || mat2[w1] != 0 || mat2[w2] != 0) continue;
                boolean straight = true;
                for (int s2 : new int[]{-1, 1}) {
                    if (up[idx(i + s2 * bx, j + s2 * bz)] != GAL || up[idx(i + ax + s2 * bx, j + az + s2 * bz)] != GAL) straight = false;
                }
                if (!straight) continue;
                int y = F + 3;
                long p1 = BlockPos.asLong(ox + i, y, oz + j), p2 = BlockPos.asLong(ox + k2 % W, y, oz + k2 / W);
                if (deco.containsKey(p1) || deco.containsKey(p2) || deco.containsKey(BlockPos.asLong(ox + i, y - 1, oz + j)) || deco.containsKey(BlockPos.asLong(ox + k2 % W, y - 1, oz + k2 / W))) continue;
                boolean bell = false;
                for (int[] f : fontisAt) if (Math.abs(f[0] - i) <= 3 && Math.abs(f[1] - j) <= 3) bell = true;
                if (bell) continue;
                int toW1 = dirOf(-ax, -az), toW2 = dirOf(ax, az);
                deco(i, y, j, st("smooth_sandstone_stairs[half=top,facing=" + DIR_NAME[toW1] + "]"));
                deco(k2 % W, y, k2 / W, st("smooth_sandstone_stairs[half=top,facing=" + DIR_NAME[toW2] + "]"));
                for (int w : new int[]{w1, w2}) {
                    for (int yy = F + 1 + dUp[k]; yy <= F + 2; yy++) deco(w % W, yy, w / W, st("smooth_sandstone"));
                    deco(w % W, F + 3, w / W, st("chiseled_sandstone"));
                }
                arches++;
            }
        }
    }

    // ------------------------------------------------------------------ damp: moss and dripping ceilings

    /** Smooth 0..1 noise over the plan (about 24 blocks across a patch). */
    private double noise(int i, int j, int salt) {
        double x = i / 24.0, z = j / 24.0;
        int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
        double fx = x - x0, fz = z - z0;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = Voxelizer.hash(x0, z0, salt) % 1000 / 1000.0, b = Voxelizer.hash(x0 + 1, z0, salt) % 1000 / 1000.0,
                cc = Voxelizer.hash(x0, z0 + 1, salt) % 1000 / 1000.0, d = Voxelizer.hash(x0 + 1, z0 + 1, salt) % 1000 / 1000.0;
        return (a * (1 - fx) + b * fx) * (1 - fz) + (cc * (1 - fx) + d * fx) * fz;
    }

    private boolean[] dampCache;

    /** Damp ground: patches of the upper level, much of the lower one, and round the flooded stretches. */
    boolean damp(int i, int j, boolean lowerLevel) {
        if (!c.b("catacombs.damp")) return false;
        if (dampCache == null) {
            dampCache = new boolean[W * H];
            for (int k = 0; k < W * H; k++) if (flood[k]) for (int dj = -6; dj <= 6; dj++) for (int di = -6; di <= 6; di++) {
                int i2 = k % W + di, j2 = k / W + dj;
                if (i2 >= 0 && j2 >= 0 && i2 < W && j2 < H) dampCache[idx(i2, j2)] = true;
            }
        }
        if (lowerLevel && dampCache[idx(i, j)]) return true;
        return noise(i, j, 515) > (lowerLevel ? 0.42 : 0.6);
    }

    // ------------------------------------------------------------------ plaques

    private static final String[] PRE = {"RUE DES", "RUE DE LA", "RUE DU", "RUE ST-", "RUE DES", "IMPASSE DES"};
    private static final String[][] NAMES = {
            {"TANNEURS", "CORDIERS", "CARRIERS", "POTIERS", "BOUCHERS", "MOULINS", "FOSSÉS", "ORMES", "TILLEULS", "CAPUCINS", "CHARTREUX", "MARAÎCHERS", "PUITS", "TONNELIERS"},
            {"TOMBE", "HARPE", "MONNAIE", "PAIX", "LANTERNE", "CROIX", "FONTAINE", "VERRERIE", "POTERIE", "SANTÉ"},
            {"PUITS", "FOUR", "PONT", "MARCHÉ", "CLOÎTRE", "PARVIS", "MOULIN", "BAC", "JARDIN", "CYGNE"},
            {"JACQUES", "MICHEL", "DENIS", "ÉTIENNE", "LAURENT", "VICTOR", "GERMAIN", "MARCEL", "HONORÉ", "BENOÎT"},
            {"BLANCS-MANTEAUX", "MATHURINS", "FEUILLANTINES", "URSULINES", "CORDELIERS", "CARMES"},
            {"BOIS", "ROSIERS", "CHAMPS", "VIGNES", "CARRIÈRES", "SOURCES"}};

    private void plaques() {
        // the street above each gallery, at about its middle
        int num = 1;
        for (Edge e : m.g.aliveEdges()) {
            if (e.a.p.dist(e.b.p) < 10) continue;
            int pi = (int) (Voxelizer.hash(e.id, 3, 11) % PRE.length);
            String[] nm = NAMES[pi];
            String name = nm[(int) (Voxelizer.hash(e.id, 5, 13) % nm.length)];
            String l1 = PRE[pi].endsWith("-") ? PRE[pi] + name : PRE[pi], l2 = PRE[pi].endsWith("-") ? "" : name;
            if (PRE[pi].endsWith("-")) { l1 = "RUE"; l2 = "ST-" + name; }
            P mid = e.a.p.lerp(e.b.p, 0.5);
            boolean dbl = e.width >= 8;
            P nrm = e.b.p.sub(e.a.p).norm().perpL();
            for (int side = dbl ? -1 : 0; side <= (dbl ? 1 : 0); side += 2) {
                P at = dbl ? mid.add(nrm.mul(side * (e.width / 2 - 1.5))) : mid;
                String l3 = "";
                if (dbl) {
                    P o = nrm.mul(side);
                    l3 = Math.abs(o.x()) > Math.abs(o.y()) ? (o.x() > 0 ? "CÔTÉ EST" : "CÔTÉ OUEST") : (o.y() > 0 ? "CÔTÉ SUD" : "CÔTÉ NORD");
                }
                signAtGallery((int) Math.round(at.x()), (int) Math.round(at.y()), new String[]{l1, l2, l3, ""});
                if (!dbl) break;
            }
            // the inspection's consolidation marks: number, inspector, year
            if (Voxelizer.hash(e.id, 7, 17) % 3 == 0) {
                P q2 = e.a.p.lerp(e.b.p, 0.25);
                boolean g = Voxelizer.hash(e.id, 9, 19) % 3 != 0;
                int year = g ? 1777 + (int) (Voxelizer.hash(e.id, 2, 23) % 14) : 1810 + (int) (Voxelizer.hash(e.id, 2, 23) % 20);
                signAtGallery((int) Math.round(q2.x()), (int) Math.round(q2.y()), new String[]{"", num++ + (g ? " G. " : " H.T. ") + year, "", ""});
            }
        }
    }

    /** A plaque on a gallery wall near a cell. */
    private void signAtGallery(int i0, int j0, String[] lines) {
        for (int r = 0; r <= 3; r++)
            for (int dj = -r; dj <= r; dj++) for (int di = -r; di <= r; di++) {
                int i = i0 + di, j = j0 + dj;
                if (!in(i, j) || up[idx(i, j)] != GAL || region[idx(i, j)] != 0 || hUp[idx(i, j)] < 3) continue;
                for (int d = 0; d < 4; d++) {
                    int kk = idx(i + D4[d][0], j + D4[d][1]);
                    if (up[kk] != 0 || mat2[kk] != 0) continue;
                    if (deco.containsKey(BlockPos.asLong(ox + i, F + 2, oz + j))) continue;
                    sign(i, F + 2, j, (d + 2) % 4, lines);
                    return;
                }
            }
    }

    /** A name plaque inside a region, on any wall. */
    private void signOnWall(int rid, byte zone, String[] lines) {
        for (int k = 0; k < W * H; k++) {
            if (region[k] != rid || up[k] != zone || hUp[k] < 3) continue;
            int i = k % W, j = k / W;
            for (int d = 0; d < 4; d++) {
                int kk = idx(i + D4[d][0], j + D4[d][1]);
                if (up[kk] != 0 || mat2[kk] != 0) continue;
                if (zone == PLAGE && (d + 2) % 4 == plageDir) continue;
                if (deco.containsKey(BlockPos.asLong(ox + i, F + 2, oz + j))) continue;
                sign(i, F + 2, j, (d + 2) % 4, lines);
                return;
            }
        }
    }

    /** A wall sign in an open cell, facing direction 'face' (so attached to the block on the other side). */
    private void sign(int i, int y, int j, int face, String[] lines) {
        long p = BlockPos.asLong(ox + i, y, oz + j);
        if (deco.containsKey(p) || direct.containsKey(p)) return;
        BlockState s = st("birch_wall_sign[facing=" + DIR_NAME[face] + "]");
        deco(i, y, j, s);
        signs.add(new Sign(BlockPos.asLong(ox + i, y, oz + j), lines));
    }

    // ------------------------------------------------------------------ 3D

    private int i3(int i, int y, int j) { return ((y - yLo) * H + j) * W + i; }

    int get3(int i, int y, int j) {
        if (i < 0 || j < 0 || i >= W || j >= H || y < yLo || y > yHi) return 0;
        return g[i3(i, y, j)];
    }

    private void put3(int i, int y, int j, int code) {
        if (y > yHi - 1 || y < yLo + 1 || !in(i, j)) return;
        extra.add(new int[]{i, y, j, code});
    }

    private void deco(int i, int y, int j, BlockState s) {
        long p = BlockPos.asLong(ox + i, y, oz + j);
        BlockState was = deco.get(p);
        if (was != null && States.name(was).contains("sign")) return; // a plaque stays
        deco.put(p, s);
    }

    /** A block in or beside the grid; above the grid it goes straight to the plan. */
    private void at(int i, int y, int j, BlockState s) { if (y >= yHi) directAt(i, y, j, s); else deco(i, y, j, s); }

    private void directAt(int i, int y, int j, BlockState s) {
        long p = BlockPos.asLong(ox + i, y, oz + j);
        direct.put(p, s);
        BlockState was = deco.get(p);
        if (was != null && States.name(was).contains("sign")) signs.removeIf(sg -> sg.pos() == p);
    }

    private void carve3D() {
        g = new byte[W * H * D];
        for (int k = 0; k < W * H; k++) {
            int i = k % W, j = k / W;
            if (up[k] != 0) {
                for (int y = F + 1 + dUp[k]; y <= F + hUp[k]; y++) g[i3(i, y, j)] = up[k];
                if (pool[k]) { g[i3(i, F, j)] = (byte) (up[k] | WATER); g[i3(i, F - 1, j)] = (byte) (up[k] | WATER); }
            }
            if (lo[k] != 0) {
                for (int y = FL + 1; y <= FL + hLo[k]; y++) g[i3(i, y, j)] = lo[k];
                if (flood[k]) g[i3(i, FL, j)] = (byte) (lo[k] | WATER);
            }
        }
        for (int[] e : extra) if (g[i3(e[0], e[1], e[2])] == 0) g[i3(e[0], e[1], e[2])] = (byte) e[3];
    }

    private BlockState mix(int i, int y, int j, String... kinds) {
        int h = hash(i, y, j), acc = 0;
        for (int n = 0; n + 1 < kinds.length; n += 2) {
            acc += Integer.parseInt(kinds[n + 1]);
            if (h < acc * 10) return st(kinds[n]);
        }
        return st(kinds[0]);
    }

    private BlockState masonry(int i, int y, int j) { return mix(i, y, j, "cut_sandstone", "55", "smooth_sandstone", "30", "sandstone", "15"); }

    private BlockState rock(int i, int y, int j) { return mix(i, y, j, "sandstone", "55", "smooth_sandstone", "23", "diorite", "12", "calcite", "10"); }

    private static final String[] STRATA = {"white_terracotta", "yellow_terracotta", "light_gray_terracotta", "orange_terracotta", "white_terracotta", "brown_terracotta", "yellow_terracotta", "terracotta"};

    private static int prio(int z) {
        return switch (z) {
            case OSS -> 9; case BUNKER -> 8; case PLAGE -> 7; case ROOMZ -> 6; case PAINTED -> 5;
            case GAL, STAIR, SHAFT -> 4; default -> 1;
        };
    }

    private void emit() {
        Sink out = plan;
        BlockState air = st("air"), water = st("water");
        for (int y = yLo; y <= yHi; y++)
            for (int j = 0; j < H; j++)
                for (int i = 0; i < W; i++) {
                    int code = g[i3(i, y, j)];
                    if (code != 0) {
                        BlockState o = (code & WATER) != 0 ? water : air;
                        // damp floors: moss creeping out from the foot of the walls
                        if (o == air && get3(i, y - 1, j) == 0 && mossy(code & 15) && damp(i, j, y <= F - 2) && hash(i, y, j) < 260 && nextToWall(i, y, j))
                            o = st("moss_carpet");
                        out.add(ox + i, y, oz + j, o);
                        openVoxels++;
                        continue;
                    }
                    int above = get3(i, y + 1, j), below = get3(i, y - 1, j);
                    int best = 0, bestDir = -1;
                    for (int d = 0; d < 4; d++) {
                        int z = get3(i + D4[d][0], y, j + D4[d][1]);
                        if (z != 0 && (best == 0 || prio(z & 15) > prio(best & 15))) { best = z; bestDir = d; }
                    }
                    if (above == 0 && below == 0 && best == 0) continue;
                    BlockState s;
                    if (above != 0) s = floor(i, y, j, above);
                    else if (best != 0) s = wall(i, y, j, best & 15, bestDir);
                    else {
                        s = ceiling(i, y, j, below & 15);
                        if (mossy(below & 15) && damp(i, j, y <= F - 2)) drip(i, y, j, y <= F - 2);
                    }
                    if (best != 0 && above == 0 && mossy(best & 15) && mat2[idx(i, j)] == 0 && (best & WATER) == 0 && damp(i + D4[bestDir][0], j + D4[bestDir][1], y <= F - 2)) {
                        int hy = y - (y <= F - 2 ? FL : F);
                        if (hash(i, y, j) < (hy <= 2 ? 300 : 110)) s = st("moss_block");
                    }
                    out.add(ox + i, y, oz + j, s);
                }
        for (var e : deco.entrySet()) out.add(BlockPos.getX(e.getKey()), BlockPos.getY(e.getKey()), BlockPos.getZ(e.getKey()), e.getValue());
        for (var e : direct.entrySet()) out.add(BlockPos.getX(e.getKey()), BlockPos.getY(e.getKey()), BlockPos.getZ(e.getKey()), e.getValue());
    }

    /** Zones where damp shows (not the bones, the bunker, la Plage or the hall). */
    private static boolean mossy(int z) { return z == GAL || z == QUARRY || z == LOWER || z == CHAT || z == FONTIS || z == SHAFT; }

    private boolean nextToWall(int i, int y, int j) {
        for (int[] d : D4) if (get3(i + d[0], y, j + d[1]) == 0) return true;
        return false;
    }

    /**
     * A dripping ceiling: a pocket of water sealed in the rock just above a ceiling block. The game lets drops fall
     * from a block that has water on top of it, so drops form on the ceiling without any water showing.
     */
    private void drip(int i, int y, int j, boolean lowerLevel) {
        if (hash(i, y + 7, j) >= (lowerLevel ? 45 : 22)) return;
        int wy = y + 1;
        if (wy >= yHi || get3(i, wy, j) != 0 || deco.containsKey(BlockPos.asLong(ox + i, y, oz + j))) return;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        for (int[] a : around) if (get3(i + a[0], wy + a[1], j + a[2]) != 0) return;
        for (int[] a : around) {
            int ni = i + a[0], ny = wy + a[1], nj = j + a[2];
            if (!touchesOpenH(ni, ny, nj)) deco.putIfAbsent(BlockPos.asLong(ox + ni, ny, oz + nj), rock(ni, ny, nj));
        }
        deco.put(BlockPos.asLong(ox + i, wy, oz + j), st("water"));
        drips++;
    }

    private BlockState floor(int i, int y, int j, int above) {
        int z = above & 15;
        if ((above & WATER) != 0) return z == LOWER ? st("clay") : st("smooth_sandstone");
        return switch (z) {
            case BUNKER -> st("light_gray_concrete");
            case PLAGE -> { deco.putIfAbsent(BlockPos.asLong(ox + i, y - 1, oz + j), st("sandstone")); yield st("sand"); }
            case STAIR, SHAFT -> st("smooth_sandstone");
            case QUARRY, LOWER, FONTIS, CHAT -> mix(i, y, j, "coarse_dirt", "45", "packed_mud", "35", "tuff", "20");
            default -> mix(i, y, j, "packed_mud", "62", "coarse_dirt", "38");
        };
    }

    private BlockState ceiling(int i, int y, int j, int z) {
        return switch (z) {
            case BUNKER -> st("gray_concrete");
            case STAIR, SHAFT -> masonry(i, y, j);
            case PAINTED -> st(STRATA[Math.floorMod(y - F, STRATA.length)]);
            default -> rock(i, y, j);
        };
    }

    private BlockState wall(int i, int y, int j, int z, int d) {
        int k = idx(i, j), hy = y - F;
        // d: direction from this wall voxel to the open space
        if (mat2[k] == M_PORTAL && hy >= 1 && hy <= 3) return st(hy == 2 ? "quartz_block" : "polished_blackstone");
        if (mat2[k] == M_HAGUE) return mix(i, y, j, "end_stone_bricks", "75", "sandstone", "25");
        if (mat2[k] == M_ARM) return st(Math.floorMod(y, 2) == 0 ? "end_stone_bricks" : "smooth_sandstone");
        if (mat2[k] == M_MASONRY) return z == BUNKER ? st("gray_concrete") : st("cut_sandstone");
        switch (z) {
            case OSS: if (hy >= 1 && hy <= 3) return bones(i, y, j, d, hy); return rock(i, y, j);
            case BUNKER: return st("gray_concrete");
            case PLAGE: if (d == plageDir && hy >= 1) return fresco(i, j, d, hy); return masonry(i, y, j);
            case PAINTED: return st(STRATA[Math.floorMod(y - F, STRATA.length)]);
            case GAL: case STAIR: case SHAFT: case ROOMZ: return masonry(i, y, j);
            default: return rock(i, y, j);
        }
    }

    /** The mod's femur stack (layers 1-4) with the heads facing direction d. */
    public static BlockState femurs(int d, int layers) { return st("emergentcity:femur_stack[axis=" + (D4[d][0] != 0 ? "x" : "z") + ",layers=" + layers + "]"); }

    /** The mod's skull pile (1-4 skulls) looking in direction d. */
    public static BlockState skulls(int d, int n) { return st("emergentcity:skull_pile[facing=" + DIR_NAME[d] + ",skulls=" + n + "]"); }

    /**
     * Ossuary walls, as the bones were stacked in 1810: a facing of femur heads three courses high with friezes of
     * skulls, here and there a cross of skulls, the top course half height (femurs or a crown of skulls), and loose
     * bones (bone gravel) heaped behind the facing.
     */
    private BlockState bones(int i, int y, int j, int d, int hy) {
        int dx = D4[d][0], dz = D4[d][1];
        int along = dx != 0 ? j : i, line = dx != 0 ? i : j;
        int panel = Math.floorMod(along, 6 * 1000) / 6, pos = Math.floorMod(along, 6);
        int type = (int) (Voxelizer.hash(panel, line * 4 + d, 77) % 7);
        boolean skull = switch (type) {
            case 0, 1 -> hy == 2;
            case 2 -> hy != 2;
            case 3 -> pos == 3 || (hy == 2 && (pos == 2 || pos == 4));
            case 5 -> hy != 1 && pos % 2 == 0;
            case 6 -> hy == 1;
            default -> false;
        };
        // loose bones behind the facing, resting on rock
        int bi = i - dx, bj = j - dz;
        if (get3(bi, y, bj) == 0 && !touchesOpenH(bi, y, bj)) {
            deco.putIfAbsent(BlockPos.asLong(ox + bi, y, oz + bj), st("emergentcity:bone_gravel"));
            if (hy == 1 && get3(bi, y - 1, bj) == 0) deco.putIfAbsent(BlockPos.asLong(ox + bi, y - 1, oz + bj), rock(bi, y - 1, bj));
        }
        if (hy == 3) {
            // the top course is half height: what shows above it is rock, not whatever the ground happens to be
            if (get3(i, y + 1, j) == 0 && !touchesOpenH(i, y + 1, j)) deco.putIfAbsent(BlockPos.asLong(ox + i, y + 1, oz + j), rock(i, y + 1, j));
            return skull ? skulls(d, 2) : femurs(d, 2);
        }
        return skull ? skulls(d, 4) : femurs(d, 4);
    }

    private boolean touchesOpenH(int i, int y, int j) {
        for (int[] dd : D4) if (get3(i + dd[0], y, j + dd[1]) != 0) return true;
        return get3(i, y + 1, j) != 0 || get3(i, y - 1, j) != 0;
    }

    /** La Plage: a wave fresco across one wall. */
    private BlockState fresco(int i, int j, int d, int hy) {
        int a = D4[d][0] != 0 ? j : i;
        double crest = 2.1 + 1.0 * Math.sin(a * 0.55) + 0.45 * Math.sin(a * 1.7 + 1);
        if (hy <= crest - 1) return st("blue_concrete");
        if (hy <= crest) return st("light_blue_concrete");
        if (hy <= crest + 0.7) return st("white_concrete");
        return st("light_blue_terracotta");
    }

    // ------------------------------------------------------------------ plague mode: the catacombs fill with bones

    /** Open voxels the bones fill: the bone shaft's column is where they come down. */
    int boneCells;

    /**
     * Plague mode: every open voxel of both levels packed with bones, in the order they arrive: they come down the bone
     * shaft and spread from there along the galleries, each column filling from the floor up, so the front of the pile
     * is a slope of bones creeping through the network. Left open: the spiral stairs and a landing at their foot,
     * the manholes' ladders, the plaques, lamps and other fittings, and the water.
     */
    public OpList boneFill() {
        OpList out = new OpList();
        if (g == null) return out;
        int plane = W * H;
        boolean[] fill = new boolean[g.length];
        boolean[] col = new boolean[plane];
        boolean[] landing = new boolean[plane];
        for (int[] e : entrances)
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
                int i = e[0] + di, j = e[1] + dj;
                if (i >= 0 && j >= 0 && i < W && j < H) landing[idx(i, j)] = true;
            }
        for (int x = 0; x < g.length; x++) {
            int code = g[x];
            if (code == 0 || (code & WATER) != 0 || (code & 15) == STAIR) continue;
            int k = x % plane, y = x / plane + yLo;
            if (landing[k]) continue;
            long p = BlockPos.asLong(ox + k % W, y, oz + k / W);
            BlockState d = deco.get(p);
            if (d != null && !d.isAir() || direct.containsKey(p)) continue;
            fill[x] = true;
            col[k] = true;
        }
        // distance along the network from the bone shaft (by column; both levels fill together)
        int[] dist = new int[plane];
        java.util.Arrays.fill(dist, -1);
        int seed = -1;
        for (Spot sp : spots) if (sp.what().equals("Bone shaft")) seed = idx(sp.x() - ox, sp.z() - oz);
        if (seed < 0) for (Spot sp : spots) if (sp.what().startsWith("Ossuary (middle)")) seed = idx(sp.x() - ox, sp.z() - oz);
        if (seed < 0 || !col[seed]) {
            int[] c0 = center();
            double bd = 1e18;
            for (int k = 0; k < plane; k++) if (col[k]) { double dd = Math.hypot(k % W - c0[0], k / W - c0[1]); if (dd < bd) { bd = dd; seed = k; } }
        }
        if (seed < 0) return out;
        int base = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int k0 = seed, scan = 0; k0 >= 0; ) {
            dist[k0] = base;
            q.add(k0);
            int far = base;
            while (!q.isEmpty()) {
                int k = q.poll(), i = k % W, j = k / W;
                far = Math.max(far, dist[k]);
                for (int[] d : D4) {
                    int ii = i + d[0], jj = j + d[1];
                    if (ii < 0 || jj < 0 || ii >= W || jj >= H) continue;
                    int kk = idx(ii, jj);
                    if (col[kk] && dist[kk] < 0) { dist[kk] = dist[k] + 1; q.add(kk); }
                }
            }
            // parts not connected to the shaft (beyond a river, say) fill after it
            base = far + 1;
            k0 = -1;
            for (; scan < plane; scan++) if (col[scan] && dist[scan] < 0) { k0 = scan; break; }
        }
        // order: distance along the network plus three per block of height above the floor of the voxel's run
        int count = 0;
        for (boolean f : fill) if (f) count++;
        long[] keys = new long[count];
        int n = 0;
        for (int x = 0; x < g.length; x++) {
            if (!fill[x]) continue;
            int k = x % plane;
            int h = 0;
            for (int xb = x - plane; xb >= 0 && g[xb] != 0; xb -= plane) h++;
            keys[n++] = ((long) (dist[k] + 3 * h) << 32) | x;
        }
        java.util.Arrays.sort(keys);
        BlockState gravel = st("emergentcity:bone_gravel"), boneBlock = st("bone_block[axis=y]");
        for (long key : keys) {
            int x = (int) key, k = x % plane, y = x / plane + yLo, i = k % W, j = k / W;
            int below = x - plane;
            boolean onSolid = below < 0 || g[below] == 0 || fill[below];
            int hsh = hash(i, y, j);
            BlockState s;
            if (!onSolid) s = boneBlock; // over water, a stair or a fitting: bone gravel would fall
            else if (hsh < 120) s = femurs(hsh % 4, 4);
            else if (hsh < 150) s = boneBlock;
            else if (hsh < 175 && (x + plane >= g.length || !fill[x + plane])) s = skulls(hsh % 4, 4); // a skull pile only at the top
            else s = gravel;
            out.add(ox + i, y, oz + j, s);
        }
        boneCells = out.n;
        return out;
    }

    // ------------------------------------------------------------------ report

    /** The report file's section: levels and every sight with its coordinates. */
    String report() {
        StringBuilder sb = new StringBuilder("\nCATACOMBS\n").append(summary()).append('\n');
        sb.append(String.format(java.util.Locale.ROOT, "Upper level floor y=%d (galleries 3 high), lower level floor y=%d%n", F, FL));
        for (Spot sp : spots) sb.append(String.format(java.util.Locale.ROOT, "  %s: %d %d %d%n", sp.what(), sp.x(), sp.y(), sp.z()));
        return sb.toString();
    }

    String summary() {
        return String.format(java.util.Locale.ROOT, "%.1f km of galleries, %d quarry chambers%s%s%s%s%s, %d fontis, %d stair(s) in mausoleums, %d manholes",
                galleryCells / 2000.0, quarries, ossuary ? ", the ossuary" : "", lower ? ", a lower level" : "",
                hall ? ", a hall under the main square" : "", plage ? ", la Plage" : "", bunker ? ", a bunker" : "", fontis, mausoleumStairs, manholes);
    }
}
