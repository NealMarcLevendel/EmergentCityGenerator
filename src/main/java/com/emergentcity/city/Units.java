package com.emergentcity.city;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The library of house units: designs stored as vanilla structure files, stacked from pieces.
 *
 * A unit is a group of structure files named {@code <key>_<piece>[_<variant>]}, piece one of
 * ground (home ground floor), shop (shop ground floor), upper (one upper storey, repeated) and roof. Files come from
 * the mod ({@code data/emergentcity/structures/units/}), and override / extend it from the world's
 * {@code generated/emergentcity/structures/units/} (where a structure block saves "emergentcity:units/...") and
 * {@code config/emergentcity/units/}.
 *
 * Frame: the ground piece carries data markers (structure blocks in DATA mode) "origin" (front-left corner of the
 * footprint, on the party wall), "next" (where the next unit's origin goes: the front-right corner, on the shared
 * party wall) and optionally "rear" (rear-left corner). The other pieces line up with the ground piece by their
 * structure coordinates (save them with the same X/Z offset and size), or by their own "origin" marker.
 * The unit's angle is the direction origin -> next: a design is made for one angle and the town turns it only in
 * quarter turns, so a street at 22.5 degrees uses a design drawn at 22.5 degrees.
 *
 * Placeholder blocks take the town's palette: white wool = wall, gray wool = ground-floor rustication, light gray
 * wool = trim, cyan wool = string course (trim on grand houses, wall otherwise), black wool = pilaster, brown wool =
 * floor planks, purple wool = painted accent wood, iron door = the house's door, waxed cut copper (block, stairs,
 * slab) = roof, waxed exposed cut copper stairs = cornice. Everything else is placed as it is.
 */
final class Units {
    private Units() {}

    static final String[] PIECES = {"ground", "shop", "upper", "roof"};

    static final class Piece {
        final Template t;
        final int ox, oz; // template position of the unit origin
        final int[][] pos; final BlockState[] st;
        final int height;
        Piece(Template t, int ox, int oz) {
            this.t = t; this.ox = ox; this.oz = oz; height = t.sy;
            List<int[]> p = new ArrayList<>(); List<BlockState> s = new ArrayList<>();
            for (int i = 0; i < t.pos.size(); i++) {
                if (t.state.get(i).isAir()) continue;
                int[] q = t.pos.get(i);
                p.add(new int[]{q[0] - ox, q[1], q[2] - oz});
                s.add(t.state.get(i));
            }
            pos = p.toArray(new int[0][]); st = s.toArray(new BlockState[0]);
        }
    }

    static final class Unit {
        final String key;
        final List<Piece> ground = new ArrayList<>(), shop = new ArrayList<>(), upper = new ArrayList<>(), roof = new ArrayList<>();
        int px, pz;     // origin -> next (local)
        double rx, rz;  // origin -> rear (local)
        double angle;   // degrees of the pitch direction, [0, 360)
        double width, depth;
        /** Footprint cells relative to the origin (columns of the ground piece's bottom layer). */
        final List<int[]> cells = new ArrayList<>();
        final HashSet<Long> cellSet = new HashSet<>();
        /** Footprint cells with a neighbour outside the footprint (the perimeter walls). */
        final HashSet<Long> edge = new HashSet<>();
        Unit(String key) { this.key = key; }
        double pitchLen() { return Math.hypot(px, pz); }
        boolean usable() { return !ground.isEmpty() || !shop.isEmpty(); }
    }

    private static volatile List<Unit> LIB = null;
    static String lastReport = "";

    static List<Unit> all() {
        List<Unit> l = LIB;
        if (l == null) { synchronized (Units.class) { if (LIB == null) LIB = load(); l = LIB; } }
        return l;
    }

    static void reload() { synchronized (Units.class) { LIB = load(); } }

    static final List<Path> EXTRA_DIRS = new ArrayList<>();

    private static final Pattern NAME = Pattern.compile("(.+)_(ground|shop|upper|roof)(?:_(\\w+))?");

    private static List<Unit> load() {
        TreeMap<String, Template> files = new TreeMap<>();
        int builtin = 0, user = 0, bad = 0;
        try (InputStream idx = Units.class.getResourceAsStream("/data/emergentcity/structures/units/index.txt")) {
            if (idx != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(idx, StandardCharsets.UTF_8));
                for (String line; (line = r.readLine()) != null; ) {
                    String n = line.trim();
                    if (n.isEmpty()) continue;
                    try (InputStream in = Units.class.getResourceAsStream("/data/emergentcity/structures/units/" + n + ".nbt")) {
                        if (in == null) { bad++; continue; }
                        files.put(n, Template.read(n, in));
                        builtin++;
                    } catch (Exception e) { bad++; }
                }
            }
        } catch (Exception ignored) {}
        for (Path dir : EXTRA_DIRS) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            try (var st = Files.walk(dir, 3)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    String fn = p.getFileName().toString();
                    if (!fn.endsWith(".nbt")) continue;
                    String n = fn.substring(0, fn.length() - 4).toLowerCase(Locale.ROOT);
                    try (InputStream in = Files.newInputStream(p)) { files.put(n, Template.read(n, in)); user++; } catch (Exception e) { bad++; }
                }
            } catch (Exception ignored) {}
        }
        Map<String, Unit> byKey = new TreeMap<>();
        Map<String, TreeMap<String, Template>> parts = new TreeMap<>();
        for (var e : files.entrySet()) {
            Matcher mt = NAME.matcher(e.getKey());
            if (!mt.matches()) continue;
            parts.computeIfAbsent(mt.group(1), k -> new TreeMap<>()).put(mt.group(2) + "#" + (mt.group(3) == null ? "" : mt.group(3)), e.getValue());
        }
        List<Unit> out = new ArrayList<>();
        for (var e : parts.entrySet()) {
            Unit u = assemble(e.getKey(), e.getValue());
            if (u != null) out.add(u); else bad++;
        }
        lastReport = String.format(Locale.ROOT, "%d unit(s) from %d built-in and %d own structure file(s)%s", out.size(), builtin, user,
                bad > 0 ? ", " + bad + " skipped" : "");
        return java.util.Collections.unmodifiableList(out);
    }

    private static Unit assemble(String key, TreeMap<String, Template> ps) {
        Unit u = new Unit(key);
        Template g0 = null;
        for (var e : ps.entrySet()) if (e.getKey().startsWith("ground#") || e.getKey().startsWith("shop#")) { if (e.getValue().markers.containsKey("origin")) { g0 = e.getValue(); break; } }
        if (g0 == null) return null;
        int[] o = g0.markers.get("origin"), nx = g0.markers.get("next"), rr = g0.markers.get("rear");
        if (nx == null) return null;
        u.px = nx[0] - o[0]; u.pz = nx[2] - o[2];
        if (u.px == 0 && u.pz == 0) return null;
        u.angle = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(u.pz, u.px)) * 100), 36000) / 100.0;
        for (var e : ps.entrySet()) {
            Template t = e.getValue();
            int[] to = t.markers.get("origin");
            int ox = to != null ? to[0] : o[0], oz = to != null ? to[2] : o[2];
            if (to == null && (t.sx != g0.sx || t.sz != g0.sz)) { /* aligned by coordinates anyway */ }
            Piece p = new Piece(withMarkerFloor(t), ox, oz);
            String kind = e.getKey().substring(0, e.getKey().indexOf('#'));
            switch (kind) {
                case "ground" -> u.ground.add(p);
                case "shop" -> u.shop.add(p);
                case "upper" -> u.upper.add(p);
                default -> u.roof.add(p);
            }
        }
        // footprint: columns with a block on the bottom layer of the ground piece
        Piece gp = !u.ground.isEmpty() ? u.ground.get(0) : u.shop.get(0);
        for (int i = 0; i < gp.pos.length; i++) {
            int[] q = gp.pos[i];
            if (q[1] != 0) continue;
            long k = Voxelizer.key(q[0], q[2]);
            if (u.cellSet.add(k)) u.cells.add(new int[]{q[0], q[2]});
        }
        if (u.cells.size() < 6) return null;
        for (int[] c : u.cells)
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (!u.cellSet.contains(Voxelizer.key(c[0] + d[0], c[1] + d[1]))) { u.edge.add(Voxelizer.key(c[0], c[1])); break; }
        double ux = u.px / u.pitchLen(), uz = u.pz / u.pitchLen(), nx2 = -uz, nz2 = ux; // inward normal (front -> rear)
        if (rr != null) { u.rx = rr[0] - o[0]; u.rz = rr[2] - o[2]; }
        else { double dm = 0; for (int[] c : u.cells) dm = Math.max(dm, c[0] * nx2 + c[1] * nz2); u.rx = nx2 * dm; u.rz = nz2 * dm; }
        u.width = u.pitchLen() + 1;
        u.depth = Math.abs(u.rx * nx2 + u.rz * nz2) + 1;
        return u;
    }

    /** Marker positions are part of the floor: the structure block that marked them is replaced by floor planks. */
    private static Template withMarkerFloor(Template t) {
        for (int[] m : t.markers.values()) {
            boolean taken = false;
            for (int[] q : t.pos) if (q[0] == m[0] && q[1] == m[1] && q[2] == m[2]) { taken = true; break; }
            if (!taken) { t.pos.add(m.clone()); t.state.add(Blocks.BROWN_WOOL.defaultBlockState()); }
        }
        t.markers.keySet().retainAll(java.util.Set.of("origin", "next", "rear"));
        return t;
    }

    // ------------------------------------------------------------------ placement

    /** Rotates a local (x, z) offset by quarter turns clockwise seen from above (east -> south). */
    static int[] rot(int x, int z, int q) {
        return switch (Math.floorMod(q, 4)) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    /** The town's block for a placeholder (null = not a placeholder). */
    static BlockState mapPlaceholder(BlockState s, Palette pal, BlockState wall, House h, Block door, boolean grand) {
        Block b = s.getBlock();
        if (b == Blocks.WHITE_WOOL) return wall;
        if (b == Blocks.GRAY_WOOL) return h.facade != null && h.facade.rustication ? pal.rustic.defaultBlockState() : wall;
        if (b == Blocks.LIGHT_GRAY_WOOL) return pal.trim.defaultBlockState();
        if (b == Blocks.CYAN_WOOL) return grand ? pal.trim.defaultBlockState() : wall;
        if (b == Blocks.BLACK_WOOL) return h.facade != null && h.facade.pilasters ? pal.pilaster.defaultBlockState() : wall;
        if (b == Blocks.BROWN_WOOL) return pal.floor.defaultBlockState();
        if (b == Blocks.PURPLE_WOOL) return Palette.accent(h)[0].defaultBlockState();
        if (b == Blocks.IRON_DOOR) return States.retype(s, door);
        if (b == Blocks.WAXED_CUT_COPPER) return pal.roofFull.defaultBlockState();
        if (b == Blocks.WAXED_CUT_COPPER_STAIRS) return States.retype(s, pal.roofStair);
        if (b == Blocks.WAXED_CUT_COPPER_SLAB) return States.retype(s, pal.roofSlab);
        if (b == Blocks.WAXED_EXPOSED_CUT_COPPER_STAIRS) return States.retype(s, pal.cornice);
        return null;
    }

    /** How far a unit's angle is from a direction (degrees), after the best quarter turn; out[1] = that turn. */
    static double angleFit(Unit u, double dirDeg, int[] turn) {
        double d = dirDeg - u.angle;
        int q = (int) Math.round(d / 90.0);
        double err = d - 90.0 * q;
        if (turn != null) turn[0] = Math.floorMod(q, 4);
        return err;
    }

    static int heightOf(List<Piece> l) { return l.isEmpty() ? 0 : l.get(0).height; }

    static <T> T pick(List<T> l, long h) { return l.isEmpty() ? null : l.get((int) Math.floorMod(h, (long) l.size())); }

    /** Unit by key (houses keep a reference by key across library reloads). */
    static Unit byKey(String key) {
        for (Unit u : all()) if (u.key.equals(key)) return u;
        return null;
    }

    static Map<String, Integer> summary() {
        Map<String, Integer> m = new HashMap<>();
        for (Unit u : all()) m.merge(String.format(Locale.ROOT, "%.0f", u.angle), 1, Integer::sum);
        return m;
    }
}
