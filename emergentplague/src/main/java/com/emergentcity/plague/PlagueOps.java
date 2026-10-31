package com.emergentcity.plague;

import com.emergentcity.city.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The plague's work in a town's blocks, by its voxelizer: graves squeezed into cemeteries, dug up again, bones in the streets. */
final class PlagueOps {
    private PlagueOps() {}


    /**
     * Plague mode: more graves squeezed into a cemetery, up to max cells this time. First the gaps left in the rows of
     * graves get headstones and plots, then the narrow walks between the rows are dug as flat plots (still walkable,
     * so the alleys and the mausoleums can be reached) until there is no room left. The alleys, walls, mausoleums and
     * cypresses stay. taken: the cells crowded so far (added to here).
     */
    static OpList crowdOps(Voxelizer v, Cemetery cm, java.util.Set<Long> taken, int max, int[] res) {
        boolean alongX = v.cemAlong.getOrDefault(cm.id, true);
        List<int[]> rows = new ArrayList<>(), walks = new ArrayList<>();
        v.raster(cm.poly, (i, j) -> {
            int k = v.idx(i, j);
            if (v.kind[k] != v.CEMETERY || v.s.water[k]) return;
            long kk = v.key(i, j);
            if (taken.contains(kk) || v.cemDecor.containsKey(kk) || v.cemSurface.containsKey(kk)) return;
            if (v.lamps.containsKey(kk) || (v.m.keepOut != null && v.m.keepOut[k])) return;
            int lat = alongX ? j : i;
            (Math.floorMod(lat, 4) == 3 ? walks : rows).add(new int[]{i, j});
        });
        java.util.Comparator<int[]> scatter = java.util.Comparator.comparingLong(c -> v.hash(c[0], c[1], 211 + cm.id));
        rows.sort(scatter);
        walks.sort(scatter);
        OpList o = v.begin(false);
        int n = 0;
        for (List<int[]> list : List.of(rows, walks))
            for (int[] c : list) {
                if (n >= max) break;
                int i = c[0], j = c[1], y = v.lvl[v.idx(i, j)];
                long hh = v.hash(i, j, 223);
                taken.add(v.key(i, j));
                n++;
                if (list == rows && hh % 3 != 0) {
                    v.put(i, y, j, Palette.s(hh % 4 == 0 ? Blocks.PODZOL : Blocks.COARSE_DIRT));
                    BlockState[] st = headstoneStates(hh);
                    for (int h = 0; h < st.length; h++) v.put(i, y + 1 + h, j, st[h]);
                } else {
                    v.put(i, y, j, Palette.s(hh % 5 < 2 ? Blocks.PODZOL : hh % 5 < 4 ? Blocks.COARSE_DIRT : Blocks.ROOTED_DIRT));
                    if (hh % 9 == 0) v.put(i, y + 1, j, Palette.s(Blocks.DEAD_BUSH));
                    else if (hh % 23 == 0) v.put(i, y + 1, j, Palette.s(Blocks.POPPY));
                }
            }
        v.end();
        res[0] = n; // graves added
        res[1] = rows.size() + walks.size() - n; // free cells left
        return o;
    }

    /** Plague mode: the extra graves dug up again (their bones go to the catacombs); the cells go back to grass. */
    static OpList uncrowdOps(Voxelizer v, java.util.Collection<Long> cells) {
        OpList o = v.begin(false);
        for (long kk : cells) {
            int i = (int) (kk >> 32), j = (int) kk;
            if (!v.in(i, j)) continue;
            int y = v.lvl[v.idx(i, j)];
            // the marker taken away and the grave dug out: a shallow hole of turned earth
            v.put(i, y + 2, j, Blocks.AIR);
            v.put(i, y + 1, j, Blocks.AIR);
            v.put(i, y, j, Blocks.AIR);
            v.put(i, y - 1, j, v.hash(i, j, 61) % 3 == 0 ? Blocks.ROOTED_DIRT : Blocks.COARSE_DIRT);
        }
        v.end();
        return o;
    }

    /**
     * Plague mode: the bones dug up from a grave plot, heaped on the street beside it until the carters take them down to
     * the catacombs: a heap of bone gravel two high in the middle, one high round it, with skulls and femurs on top.
     * Returns the ops; pile gets one entry per column, its positions bottom up, for carting the heap away later.
     */
    static OpList bonePileOps(Voxelizer v, P at, int cells, java.util.Set<Long> done, List<long[]> pile) {
        OpList o = v.begin(false);
        int si = (int) Math.round(at.x()), sj = (int) Math.round(at.y());
        // the nearest open street cell, then outwards from it along the street
        int start = -1;
        double bd = 1e18;
        for (int j = sj - 14; j <= sj + 14; j++) for (int i = si - 14; i <= si + 14; i++) {
            if (!pileCell(v, i, j, done)) continue;
            double d = Math.hypot(i - at.x(), j - at.y());
            if (d < bd) { bd = d; start = v.idx(i, j); }
        }
        if (start < 0) { v.end(); return o; }
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        q.add(start); seen.add(start);
        List<Integer> take = new ArrayList<>();
        while (!q.isEmpty() && take.size() < cells) {
            int k = q.poll(), i = k % v.W, j = k / v.W;
            take.add(k);
            for (int[] d : v.DIRS) {
                int ii = i + d[0], jj = j + d[1];
                if (!v.in(ii, jj) || seen.contains(v.idx(ii, jj)) || !pileCell(v, ii, jj, done)) continue;
                seen.add(v.idx(ii, jj));
                q.add(v.idx(ii, jj));
            }
        }
        BlockState gravel = Catacombs.st("emergentcity:bone_gravel");
        for (int n = 0; n < take.size(); n++) {
            int k = take.get(n), i = k % v.W, j = k / v.W, y = v.lvl[k];
            done.add(v.key(i, j));
            long hh = v.hash(i, j, 991);
            int high = n < Math.max(1, take.size() / 3) ? 2 : 1;
            long[] col = new long[high + 1];
            int c = 0;
            for (int h = 1; h <= high; h++) { v.put(i, y + h, j, gravel); col[c++] = BlockPos.asLong(v.ox + i, y + h, v.oz + j); }
            int d = (int) ((hh >>> 8) & 3);
            BlockState top = hh % 3 == 0 ? Catacombs.skulls(d, 1 + (int) ((hh >>> 12) % 3)) : hh % 3 == 1 ? Catacombs.femurs(d, 1 + (int) ((hh >>> 14) % 2)) : null;
            if (top != null) { v.put(i, y + high + 1, j, top); col[c++] = BlockPos.asLong(v.ox + i, y + high + 1, v.oz + j); }
            pile.add(java.util.Arrays.copyOf(col, c));
        }
        v.end();
        return o;
    }

    private static boolean pileCell(Voxelizer v, int i, int j, java.util.Set<Long> done) {
        if (!v.in(i, j)) return false;
        int k = v.idx(i, j);
        byte kk = v.kind[k];
        if (!(v.isStreetLike(kk) || kk == v.PAVED) || v.s.water[k]) return false;
        long ck = v.key(i, j);
        if (v.lamps.containsKey(ck) || v.seats.containsKey(ck) || done.contains(ck)) return false;
        if (v.placedTop != null && v.placedTop[k] > v.lvl[k]) return false;
        return v.m.keepOut == null || !v.m.keepOut[k];
    }

    /** The blocks of one headstone (as the cemetery's own graves have them), bottom up. */
    static BlockState[] headstoneStates(long hh) {
        int v = (int) (hh % 20);
        if (v < 8) return new BlockState[]{Palette.s(Blocks.STONE_BRICK_WALL)};
        if (v < 11) return new BlockState[]{Palette.s(Blocks.ANDESITE_WALL)};
        if (v < 14) return new BlockState[]{Palette.s(Blocks.MOSSY_STONE_BRICK_WALL)};
        if (v < 16) return new BlockState[]{Palette.s(Blocks.COBBLESTONE_WALL)};
        if (v < 18) return new BlockState[]{Palette.s(Blocks.CHISELED_STONE_BRICKS), Palette.s(Blocks.STONE_BRICK_SLAB)};
        return new BlockState[]{Palette.s(Blocks.STONE_BRICK_WALL), Palette.s(Blocks.STONE_BRICK_WALL)};
    }

    /**
     * Plague mode: bones strewn on the street, the square or the paving within r of a point (with everywhere, in the
     * gardens and courtyards too): loose femurs and skulls
     * lying on the paving, and patches where the paving itself has given way to bone gravel. Nothing goes where
     * something already stands (lamps, seats, steps, other bones). density: the share of cells that get some.
     */
    static OpList streetBoneOps(Voxelizer v, P at, double r, double density, java.util.Set<Long> done, boolean everywhere) {
        OpList o = v.begin(false);
        int i0 = (int) Math.floor(at.x() - r), i1 = (int) Math.ceil(at.x() + r), j0 = (int) Math.floor(at.y() - r), j1 = (int) Math.ceil(at.y() + r);
        for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
            if (!v.in(i, j) || Math.hypot(i - at.x(), j - at.y()) > r) continue;
            int k = v.idx(i, j);
            byte kk = v.kind[k];
            if (!(v.isStreetLike(kk) || kk == v.PAVED || (everywhere && kk == v.GARDEN)) || v.s.water[k]) continue;
            long ck = v.key(i, j);
            if (v.lamps.containsKey(ck) || v.seats.containsKey(ck) || done.contains(ck)) continue;
            if (v.placedTop != null && v.placedTop[k] > v.lvl[k]) continue; // something stands on it
            if (v.m.keepOut != null && v.m.keepOut[k]) continue;
            long hh = v.hash(i, j, 977);
            double u = (hh % 10000) / 10000.0;
            if (u >= density) continue;
            done.add(ck);
            int d = (int) ((hh >>> 20) & 3), y = v.lvl[k];
            double f = u / density;
            if (f < 0.45) v.put(i, y + 1, j, Catacombs.femurs(d, 1 + (int) ((hh >>> 24) & 1)));
            else if (f < 0.65) v.put(i, y + 1, j, Catacombs.skulls(d, 1 + (int) ((hh >>> 26) % 3)));
            else v.put(i, y, j, Catacombs.st("emergentcity:bone_gravel"));
        }
        v.end();
        return o;
    }

}
