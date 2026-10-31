package com.emergentcity.city;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Where voxelized block changes are written. */
interface Sink {
    void add(int x, int y, int z, BlockState s);
}

/**
 * An ordered list of block changes for one villager job (a street segment, a block's paving, a house).
 * Later entries win at the same position. Optional marks map progress (0..1) to positions in the list,
 * so a street can be paved just behind the surveyor walking it.
 */
public final class OpList implements Sink {
    public long[] pos = new long[256];
    public int[] st = new int[256];
    public int n;
    private double[] markT = new double[16];
    private int[] markIdx = new int[16];
    private int marks;

    @Override
    public void add(int x, int y, int z, BlockState s) {
        if (n == pos.length) { pos = Arrays.copyOf(pos, n * 2); st = Arrays.copyOf(st, n * 2); }
        pos[n] = BlockPos.asLong(x, y, z);
        st[n] = Block.getId(s);
        n++;
    }

    void mark(double t) {
        if (marks == markT.length) { markT = Arrays.copyOf(markT, marks * 2); markIdx = Arrays.copyOf(markIdx, marks * 2); }
        markT[marks] = t; markIdx[marks] = n; marks++;
    }

    /** Number of ops that belong to progress <= t (all ops when there are no marks). */
    int upTo(double t) {
        if (marks == 0) return n;
        for (int k = 0; k < marks; k++) if (markT[k] > t) return markIdx[k];
        return n;
    }

    public void addAll(OpList o) {
        for (int i = 0; i < o.n; i++) {
            if (n == pos.length) { pos = Arrays.copyOf(pos, n * 2); st = Arrays.copyOf(st, n * 2); }
            pos[n] = o.pos[i]; st[n] = o.st[i]; n++;
        }
    }

    /**
     * Demolition of a building that was placed from 'built': everything above its floor level becomes air, top down.
     * Foundations and ground below the floor are never touched. With floorTo != null the floor itself is replaced
     * too (a cleared lot). Only positions the building itself placed are affected.
     */
    static OpList demolition(OpList built, int floorY, BlockState air, BlockState floorTo) { return demolition(built, floorY, air, floorTo, p -> false); }

    /** keep: positions also placed by a neighbouring building (shared cornice/balcony cells over the street). */
    static OpList demolition(OpList built, int floorY, BlockState air, BlockState floorTo, java.util.function.LongPredicate keep) {
        int minY = floorY;
        java.util.TreeMap<Integer, java.util.LinkedHashSet<Long>> byY = new java.util.TreeMap<>(java.util.Comparator.reverseOrder());
        for (int i = 0; i < built.n; i++) {
            int y = BlockPos.getY(built.pos[i]);
            if (y < floorY || Block.stateById(built.st[i]).isAir() || keep.test(built.pos[i])) continue; // ground work and cleared air stay
            byY.computeIfAbsent(y, yy -> new java.util.LinkedHashSet<>()).add(built.pos[i]);
        }
        OpList o = new OpList();
        int airId = Block.getId(air);
        for (var e : byY.entrySet()) {
            boolean floor = e.getKey() == minY;
            if (floor && floorTo == null) continue;
            int id = floor ? Block.getId(floorTo) : airId;
            for (long p : e.getValue()) {
                if (o.n == o.pos.length) { o.pos = Arrays.copyOf(o.pos, o.n * 2); o.st = Arrays.copyOf(o.st, o.n * 2); }
                o.pos[o.n] = p; o.st[o.n] = id; o.n++;
            }
        }
        return o;
    }

    /** Splits into four quadrant jobs around (cx, cz) so several masons can work on one monument. */
    OpList[] quadrants(int cx, int cz) {
        OpList[] q = {new OpList(), new OpList(), new OpList(), new OpList()};
        for (int i = 0; i < n; i++) {
            int k = (BlockPos.getX(pos[i]) < cx ? 0 : 1) + (BlockPos.getZ(pos[i]) < cz ? 0 : 2);
            OpList o = q[k];
            if (o.n == o.pos.length) { o.pos = Arrays.copyOf(o.pos, o.n * 2); o.st = Arrays.copyOf(o.st, o.n * 2); }
            o.pos[o.n] = pos[i]; o.st[o.n] = st[i]; o.n++;
        }
        return q;
    }

    /** Stable sort bottom-up, so buildings rise floor by floor (same-position order is preserved). */
    public void sortByHeight() {
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Integer.compare(BlockPos.getY(pos[a]), BlockPos.getY(pos[b])));
        long[] p2 = new long[n];
        int[] s2 = new int[n];
        for (int i = 0; i < n; i++) { p2[i] = pos[idx[i]]; s2[i] = st[idx[i]]; }
        pos = p2; st = s2;
        marks = 0;
    }
}
