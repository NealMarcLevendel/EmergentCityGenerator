package com.emergentcity.city;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the starting set of house units as vanilla structure files (no game classes needed):
 * widths 5-10 (party wall to party wall, both included), depths 7-9, and one separate design for each of eight angles
 * 0, 11.25, ..., 78.75 degrees (quarter turns cover the rest of the circle). Each unit has four stacked pieces:
 * ground (home), shop, upper (repeated per storey) and roof, drawn with the placeholder blocks described in {@link Units}.
 *
 * A design at angle a: the front runs from the origin to the integer point nearest (W-1)(cos a, sin a); the party
 * walls run from both front corners in the exact direction (-sin a, cos a), so the right wall of one unit and the
 * left wall of the next coincide cell for cell. Cells whose centre lies within half a cell of the parallelogram's
 * edges or inside it form the footprint; footprint cells with a neighbour outside are walls.
 *
 * Usage: java com.emergentcity.city.UnitDesigner <output dir> (writes the .nbt files and index.txt).
 */
public final class UnitDesigner {
    private UnitDesigner() {}

    static final int GROUND = 4, UPPER = 3, ROOF_RISE = 4;
    static final String WALL = "white_wool", RUSTIC = "gray_wool", TRIM = "light_gray_wool", BAND = "cyan_wool",
            PILASTER = "black_wool", FLOOR = "brown_wool", ACCENT = "purple_wool", DOOR = "iron_door",
            ROOF = "waxed_cut_copper", ROOF_ST = "waxed_cut_copper_stairs", ROOF_SL = "waxed_cut_copper_slab",
            CORNICE = "waxed_exposed_cut_copper_stairs", GLASS = "glass", AIR = "air";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "src/main/resources/data/emergentcity/structures/units");
        Files.createDirectories(out);
        List<String> names = new ArrayList<>();
        for (int k = 0; k < 8; k++)
            for (int w = 5; w <= 10; w++)
                for (int d = 7; d <= 9; d++) {
                    Design ds = new Design(w, d, k * 11.25);
                    if (ds.cells.size() < 12) continue;
                    String key = String.format(Locale.ROOT, "w%dd%da%d", w, d, k);
                    write(out, key + "_ground", ds.ground(false), names);
                    write(out, key + "_shop", ds.ground(true), names);
                    write(out, key + "_upper", ds.upper(), names);
                    write(out, key + "_roof", ds.roof(), names);
                }
        Files.writeString(out.resolve("index.txt"), String.join("\n", names) + "\n");
        System.out.println("wrote " + names.size() + " structure files to " + out);
    }

    private static void write(Path dir, String name, Map<String, Object> nbt, List<String> names) throws Exception {
        try (OutputStream o = Files.newOutputStream(dir.resolve(name + ".nbt"))) { Nbt.write(nbt, o); }
        names.add(name);
    }

    /** A block in a piece: id (without namespace) and properties. */
    record B(String id, Map<String, String> props) {
        static B of(String id) { return new B(id, Map.of()); }
        static B of(String id, String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return new B(id, m);
        }
    }

    static final class Design {
        final int w, dep;
        final double a, ux, uz, nx, nz;     // pitch direction u, inward normal n (front -> rear)
        final int px, pz;                   // origin -> next
        final double mx, mz;                // side-wall normal (left -> right), perpendicular to n
        final double rearF, rightG;
        final List<int[]> cells = new ArrayList<>();
        final HashSet<Long> in = new HashSet<>();
        final HashMap<Long, int[]> role = new HashMap<>(); // cell -> {front, rear, left, right} flags
        int minX, minZ, maxX, maxZ;

        final double wd, wf; // wall band widths across the side walls and across the front / rear walls

        Design(int w, int dep, double deg) {
            this.w = w; this.dep = dep; this.a = Math.toRadians(deg);
            px = (int) Math.round((w - 1) * Math.cos(a)); pz = (int) Math.round((w - 1) * Math.sin(a));
            double pl = Math.hypot(px, pz);
            ux = px / pl; uz = pz / pl; nx = -uz; nz = ux;
            mx = Math.cos(a); mz = Math.sin(a); // the party walls run along (-sin a, cos a); m is its normal
            double dvx = (dep - 1) * -Math.sin(a), dvz = (dep - 1) * Math.cos(a);
            rearF = dvx * nx + dvz * nz;
            rightG = px * mx + pz * mz;
            // walls are standard digital lines (4-connected bands as wide as |m.x| + |m.z|) centred on the edges, so the
            // right wall of a unit and the left wall of the next one (its origin = this unit's "next") are the same cells
            wd = Math.abs(mx) + Math.abs(mz);
            wf = Math.abs(nx) + Math.abs(nz);
            int R = w + dep + 2;
            for (int z = -R; z <= R; z++) for (int x = -R; x <= R; x++) if (inside(x, z)) { cells.add(new int[]{x, z}); in.add(k(x, z)); }
            minX = minZ = Integer.MAX_VALUE; maxX = maxZ = Integer.MIN_VALUE;
            for (int[] c : cells) {
                minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minZ = Math.min(minZ, c[1]); maxZ = Math.max(maxZ, c[1]);
                double f = c[0] * nx + c[1] * nz, g = c[0] * mx + c[1] * mz;
                int[] r = {lt(f, wf / 2) ? 1 : 0, ge(f - rearF, -wf / 2) ? 1 : 0, lt(g, wd / 2) ? 1 : 0, ge(g - rightG, -wd / 2) ? 1 : 0};
                if (r[0] + r[1] + r[2] + r[3] > 0) role.put(k(c[0], c[1]), r);
            }
            minX -= 1; minZ -= 1; maxX += 1; maxZ += 1; // room for the cornice
        }

        private static double r6(double v) { return Math.round(v * 1e6) / 1e6; }
        private static boolean lt(double a, double b) { return r6(a) < r6(b); }
        private static boolean ge(double a, double b) { return r6(a) >= r6(b); }

        boolean inside(int x, int z) {
            double f = x * nx + z * nz, g = x * mx + z * mz;
            return ge(f, -wf / 2) && lt(f - rearF, wf / 2) && ge(g, -wd / 2) && lt(g - rightG, wd / 2);
        }

        static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        static long k(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
        boolean wall(int x, int z) { return role.containsKey(k(x, z)); }
        boolean front(int x, int z) { int[] r = role.get(k(x, z)); return r != null && r[0] == 1; }
        boolean rear(int x, int z) { int[] r = role.get(k(x, z)); return r != null && r[1] == 1 && r[0] == 0; }
        boolean side(int x, int z) { int[] r = role.get(k(x, z)); return r != null && r[0] == 0 && r[1] == 0; }
        double along(int x, int z) { return x * ux + z * uz; }

        /** Horizontal direction name nearest a vector (x east, z south). */
        static String dir(double x, double z) { return Math.abs(x) >= Math.abs(z) ? (x >= 0 ? "east" : "west") : (z >= 0 ? "south" : "north"); }
        static int[] step(String d) { return switch (d) { case "east" -> new int[]{1, 0}; case "west" -> new int[]{-1, 0}; case "south" -> new int[]{0, 1}; default -> new int[]{0, -1}; }; }
        String outDir() { return dir(-nx, -nz); }
        String inDir() { return dir(nx, nz); }

        /** Front cells that actually face the street (their outward neighbour is outside), ordered along the front. */
        List<int[]> facing() {
            int[] o = step(outDir());
            List<int[]> l = new ArrayList<>();
            for (int[] c : cells) if (front(c[0], c[1]) && !in.contains(k(c[0] + o[0], c[1] + o[1])) && in.contains(k(c[0] - o[0], c[1] - o[1]))) l.add(c);
            l.sort((p, q) -> Double.compare(along(p[0], p[1]), along(q[0], q[1])));
            return l;
        }

        /** Bays: window columns at regular spacing along the street front, the door in the middle one. */
        int[] bays(List<int[]> fr) {
            int n = fr.size();
            if (n < 3) return new int[0];
            int nb = Math.max(1, (int) Math.round((n - 1) / 2.4));
            int[] b = new int[nb];
            for (int i = 0; i < nb; i++) b[i] = Math.max(1, Math.min(n - 2, (int) Math.round((i + 0.5) * n / nb - 0.5)));
            return b;
        }

        List<int[]> facingRear() {
            int[] o = step(inDir());
            List<int[]> l = new ArrayList<>();
            for (int[] c : cells) if (rear(c[0], c[1]) && !in.contains(k(c[0] + o[0], c[1] + o[1])) && in.contains(k(c[0] - o[0], c[1] - o[1]))) l.add(c);
            l.sort((p, q) -> Double.compare(along(p[0], p[1]), along(q[0], q[1])));
            return l;
        }

        /** Interior cells next to a side (party) wall, from the rear: {x, z, direction index toward the wall}. */
        List<int[]> alongSide(boolean left) {
            List<int[]> l = new ArrayList<>();
            for (int[] c : cells) {
                if (wall(c[0], c[1])) continue;
                for (int d = 0; d < 4; d++) {
                    int ox = c[0] + DIRS[d][0], oz = c[1] + DIRS[d][1];
                    int[] r = role.get(k(ox, oz));
                    if (r == null || r[0] == 1 || r[1] == 1) continue;
                    if ((left ? r[2] : r[3]) == 1) { l.add(new int[]{c[0], c[1], d}); break; }
                }
            }
            l.sort((p, q) -> Double.compare(q[0] * nx + q[1] * nz, p[0] * nx + p[1] * nz));
            return l;
        }

        int[] centre() {
            double cx = 0, cz = 0; int n = 0;
            for (int[] c : cells) if (!wall(c[0], c[1])) { cx += c[0]; cz += c[1]; n++; }
            if (n == 0) return cells.get(0);
            cx /= n; cz /= n;
            int[] best = null; double bd = 1e9;
            for (int[] c : cells) if (!wall(c[0], c[1])) { double d = Math.hypot(c[0] - cx, c[1] - cz); if (d < bd) { bd = d; best = c; } }
            return best;
        }

        static final String[] HD = {"east", "south", "west", "north"};

        /** The ladder: an interior cell against the right party wall near the rear, with free cells around it. */
        int[] ladder() {
            List<int[]> l = alongSide(false);
            return l.isEmpty() ? null : l.get(0);
        }

        // ---------------------------------------------------------- pieces

        Map<String, Object> ground(boolean shop) {
            Piece p = new Piece(this, GROUND);
            List<int[]> fr = facing();
            int[] bays = bays(fr);
            int doorIdx = bays.length == 0 ? fr.size() / 2 : bays[bays.length / 2];
            String out = outDir();
            for (int[] c : cells) p.put(c[0], 0, c[1], B.of(FLOOR));
            for (int[] c : cells) {
                if (!wall(c[0], c[1])) { for (int y = 1; y < GROUND; y++) p.put(c[0], y, c[1], B.of(AIR)); continue; }
                for (int y = 1; y < GROUND; y++) p.put(c[0], y, c[1], B.of(front(c[0], c[1]) ? RUSTIC : WALL));
            }
            HashSet<Long> bayCells = new HashSet<>();
            for (int b : bays) if (b != doorIdx) bayCells.add(k(fr.get(b)[0], fr.get(b)[1]));
            for (int i = 0; i < fr.size(); i++) {
                int[] c = fr.get(i);
                long kk = k(c[0], c[1]);
                if (i == doorIdx) {
                    p.put(c[0], 1, c[1], B.of(DOOR, "facing", out, "half", "lower", "hinge", "left", "open", "false"));
                    p.put(c[0], 2, c[1], B.of(DOOR, "facing", out, "half", "upper", "hinge", "left", "open", "false"));
                    p.put(c[0], 3, c[1], B.of(shop ? ACCENT : TRIM));
                } else if (shop && Math.abs(i - doorIdx) == 1) {
                    for (int y = 1; y < GROUND; y++) p.put(c[0], y, c[1], B.of(ACCENT)); // painted posts either side of the entrance
                } else if (bayCells.contains(kk)) {
                    if (shop) { p.put(c[0], 1, c[1], B.of(GLASS)); p.put(c[0], 2, c[1], B.of(GLASS)); p.put(c[0], 3, c[1], B.of(ACCENT)); }
                    else { p.put(c[0], 2, c[1], B.of(GLASS)); p.put(c[0], 3, c[1], B.of(GLASS)); }
                } else if (shop && i > 0 && i < fr.size() - 1) p.put(c[0], 3, c[1], B.of(ACCENT)); // sign band
            }
            rearWindows(p, 2, 3, 7);
            // interior
            HashSet<Long> used = new HashSet<>();
            int[] door = fr.isEmpty() ? null : fr.get(doorIdx);
            if (door != null) { int[] o = step(inDir()); used.add(k(door[0] + o[0], door[1] + o[1])); }
            int[] ld = ladder();
            if (ld != null) {
                used.add(k(ld[0], ld[1])); used.add(k(ld[0] - DIRS[ld[2]][0], ld[1] - DIRS[ld[2]][1]));
                for (int y = 1; y < GROUND; y++) p.put(ld[0], y, ld[1], B.of("ladder", "facing", HD[(ld[2] + 2) % 4], "waterlogged", "false"));
            }
            int[] cen = centre();
            if (cen != null && !used.contains(k(cen[0], cen[1]))) { p.put(cen[0], 1, cen[1], B.of("lantern", "hanging", "false", "waterlogged", "false")); used.add(k(cen[0], cen[1])); }
            List<int[]> left = alongSide(true);
            if (shop) {
                int n = 0; // counter of barrels along the left wall
                for (int[] c : left) { if (n >= 3 || used.contains(k(c[0], c[1]))) continue; p.put(c[0], 1, c[1], B.of("barrel", "facing", "up", "open", "false")); used.add(k(c[0], c[1])); n++; }
            } else {
                bed(p, left, used, 1);
                for (int[] c : left) { if (used.contains(k(c[0], c[1]))) continue; p.put(c[0], 1, c[1], B.of("crafting_table")); used.add(k(c[0], c[1])); break; }
                for (int[] c : alongSide(false)) { if (used.contains(k(c[0], c[1]))) continue; p.put(c[0], 1, c[1], B.of("barrel", "facing", "up", "open", "false")); used.add(k(c[0], c[1])); break; }
            }
            p.marker(0, 0, 0, "origin"); p.marker(px, 0, pz, "next");
            int[] rc = cells.get(0); double rd = 1e9; // rear-left corner: the footprint cell nearest the exact corner
            for (int[] c : cells) { double dd = Math.hypot(c[0] - (dep - 1) * -Math.sin(a), c[1] - (dep - 1) * Math.cos(a)); if (dd < rd) { rd = dd; rc = c; } }
            p.marker(rc[0], 0, rc[1], "rear");
            return p.nbt();
        }

        Map<String, Object> upper() {
            Piece p = new Piece(this, UPPER);
            List<int[]> fr = facing();
            int[] bays = bays(fr);
            HashSet<Long> bayCells = new HashSet<>();
            for (int b : bays) bayCells.add(k(fr.get(b)[0], fr.get(b)[1]));
            for (int[] c : cells) {
                if (!wall(c[0], c[1])) { p.put(c[0], 0, c[1], B.of(FLOOR)); for (int y = 1; y < UPPER; y++) p.put(c[0], y, c[1], B.of(AIR)); continue; }
                boolean f = front(c[0], c[1]);
                p.put(c[0], 0, c[1], B.of(f ? BAND : WALL));
                for (int y = 1; y < UPPER; y++) p.put(c[0], y, c[1], B.of(WALL));
            }
            for (int i = 0; i < fr.size(); i++) {
                int[] c = fr.get(i);
                if (bayCells.contains(k(c[0], c[1]))) { p.put(c[0], 1, c[1], B.of(GLASS)); p.put(c[0], 2, c[1], B.of(GLASS)); }
                else if (i > 0 && i < fr.size() - 1 && (i == 1 || i == fr.size() - 2)) { p.put(c[0], 1, c[1], B.of(PILASTER)); p.put(c[0], 2, c[1], B.of(PILASTER)); }
            }
            rearWindows(p, 1, 2, 5);
            HashSet<Long> used = new HashSet<>();
            int[] ld = ladder();
            if (ld != null) {
                used.add(k(ld[0], ld[1])); used.add(k(ld[0] - DIRS[ld[2]][0], ld[1] - DIRS[ld[2]][1]));
                for (int y = 0; y < UPPER; y++) p.put(ld[0], y, ld[1], B.of("ladder", "facing", HD[(ld[2] + 2) % 4], "waterlogged", "false"));
            }
            int[] cen = centre();
            if (cen != null && !used.contains(k(cen[0], cen[1]))) { p.put(cen[0], 1, cen[1], B.of("lantern", "hanging", "false", "waterlogged", "false")); used.add(k(cen[0], cen[1])); }
            List<int[]> left = alongSide(true);
            bed(p, left, used, 1);
            for (int[] c : left) { if (used.contains(k(c[0], c[1]))) continue; p.put(c[0], 1, c[1], B.of("chest", "facing", HD[(c[2] + 2) % 4], "type", "single", "waterlogged", "false")); used.add(k(c[0], c[1])); break; }
            return p.nbt();
        }

        Map<String, Object> roof() {
            Piece p = new Piece(this, ROOF_RISE + 2);
            String inD = inDir(), outD = outDir();
            int[] o = step(outD);
            for (int[] c : cells) p.put(c[0], 0, c[1], B.of(wall(c[0], c[1]) ? (side(c[0], c[1]) ? WALL : TRIM) : FLOOR));
            // cornice over the street, along the front
            for (int[] c : facing()) {
                int ox = c[0] + o[0], oz = c[1] + o[1];
                p.put(ox, 0, oz, B.of(CORNICE, "facing", inD, "half", "top", "shape", "straight", "waterlogged", "false"));
            }
            for (int[] c : cells) {
                double f = c[0] * nx + c[1] * nz;
                double da = f, db = rearF - f;
                int rise = (int) Math.floor(Math.min(da, db) + 0.5);
                boolean capped = rise >= ROOF_RISE;
                rise = Math.min(rise, ROOF_RISE);
                boolean gable = side(c[0], c[1]);
                for (int y = 1; y <= rise; y++) p.put(c[0], y, c[1], B.of(gable ? WALL : ROOF));
                B cap = capped || Math.abs(da - db) < 1.0 ? B.of(ROOF_SL, "type", "bottom", "waterlogged", "false")
                        : B.of(ROOF_ST, "facing", da < db ? inD : outD, "half", "bottom", "shape", "straight", "waterlogged", "false");
                p.put(c[0], rise + 1, c[1], cap);
            }
            return p.nbt();
        }

        private void rearWindows(Piece p, int y0, int y1, int salt) {
            List<int[]> r = facingRear();
            for (int i = 1; i + 1 < r.size(); i += 3) {
                int[] c = r.get(i);
                if ((i + salt) % 2 == 0 && r.size() > 4) continue;
                for (int y = y0; y <= y1; y++) p.put(c[0], y, c[1], B.of(GLASS));
            }
        }

        private void bed(Piece p, List<int[]> along, HashSet<Long> used, int y) {
            for (int[] c : along) {
                if (used.contains(k(c[0], c[1]))) continue;
                for (int d = 0; d < 4; d++) {
                    int hx = c[0] + DIRS[d][0], hz = c[1] + DIRS[d][1];
                    if (!in.contains(k(hx, hz)) || wall(hx, hz) || used.contains(k(hx, hz))) continue;
                    if (d == c[2]) continue;
                    String f = HD[d];
                    p.put(c[0], y, c[1], B.of("red_bed", "facing", f, "part", "foot", "occupied", "false"));
                    p.put(hx, y, hz, B.of("red_bed", "facing", f, "part", "head", "occupied", "false"));
                    used.add(k(c[0], c[1])); used.add(k(hx, hz));
                    return;
                }
            }
        }
    }

    /** One structure file under construction, in unit coordinates (origin at 0, 0). */
    static final class Piece {
        final Design d;
        final int h;
        final LinkedHashMap<Long, B>[] layers;
        final List<Object[]> markers = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Piece(Design d, int h) {
            this.d = d; this.h = h;
            layers = new LinkedHashMap[h];
            for (int i = 0; i < h; i++) layers[i] = new LinkedHashMap<>();
        }

        void put(int x, int y, int z, B b) { if (y >= 0 && y < h) layers[y].put(Design.k(x, z), b); }
        void marker(int x, int y, int z, String name) { layers[y].remove(Design.k(x, z)); markers.add(new Object[]{x, y, z, name}); }

        Map<String, Object> nbt() {
            List<Object> palette = new ArrayList<>();
            Map<B, Integer> ids = new HashMap<>();
            List<Object> blocks = new ArrayList<>();
            for (int y = 0; y < h; y++)
                for (var e : layers[y].entrySet()) {
                    int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey().intValue();
                    blocks.add(entry(x, y, z, id(palette, ids, e.getValue()), null));
                }
            B sb = B.of("structure_block", "mode", "data");
            for (Object[] m : markers) {
                Map<String, Object> nbt = new LinkedHashMap<>();
                nbt.put("id", "minecraft:structure_block");
                nbt.put("mode", "DATA");
                nbt.put("metadata", m[3]);
                nbt.put("name", "");
                nbt.put("author", "emergentcity");
                nbt.put("posX", 0); nbt.put("posY", 1); nbt.put("posZ", 0);
                nbt.put("sizeX", 0); nbt.put("sizeY", 0); nbt.put("sizeZ", 0);
                nbt.put("rotation", "NONE"); nbt.put("mirror", "NONE");
                nbt.put("ignoreEntities", (byte) 1); nbt.put("powered", (byte) 0); nbt.put("showair", (byte) 0); nbt.put("showboundingbox", (byte) 1);
                nbt.put("integrity", 1.0f); nbt.put("seed", 0L);
                blocks.add(entry((int) m[0], (int) m[1], (int) m[2], id(palette, ids, sb), nbt));
            }
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("DataVersion", 3465);
            root.put("size", List.of(d.maxX - d.minX + 1, h, d.maxZ - d.minZ + 1));
            root.put("palette", palette);
            root.put("blocks", blocks);
            root.put("entities", new ArrayList<>());
            return root;
        }

        private Map<String, Object> entry(int x, int y, int z, int state, Map<String, Object> nbt) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("pos", List.of(x - d.minX, y, z - d.minZ));
            e.put("state", state);
            if (nbt != null) e.put("nbt", nbt);
            return e;
        }

        private static int id(List<Object> palette, Map<B, Integer> ids, B b) {
            return ids.computeIfAbsent(b, x -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("Name", "minecraft:" + b.id());
                if (!b.props().isEmpty()) e.put("Properties", new LinkedHashMap<>(b.props()));
                palette.add(e);
                return palette.size() - 1;
            });
        }
    }
}
