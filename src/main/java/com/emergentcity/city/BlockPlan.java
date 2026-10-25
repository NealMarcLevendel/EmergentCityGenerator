package com.emergentcity.city;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block changes grouped by chunk, in insertion order (later writes to the same position win).
 * Built off-thread; executed on the server thread by {@link Placer}. Compact: 12 bytes per change.
 */
final class BlockPlan implements Sink {
    static final class Ops {
        long[] pos = new long[512];
        int[] st = new int[512];
        int n;

        void add(long p, int s) {
            if (n == pos.length) { pos = Arrays.copyOf(pos, n * 2); st = Arrays.copyOf(st, n * 2); }
            pos[n] = p; st[n] = s; n++;
        }
    }

    final HashMap<Long, Ops> chunks = new HashMap<>();
    final int minY, maxY;
    long total;

    BlockPlan(int minY, int maxY) { this.minY = minY; this.maxY = maxY; }

    @Override
    public void add(int x, int y, int z, BlockState s) {
        if (y < minY || y >= maxY) return;
        long ck = ChunkPos.asLong(x >> 4, z >> 4);
        Ops o = chunks.get(ck);
        if (o == null) { o = new Ops(); chunks.put(ck, o); }
        o.add(BlockPos.asLong(x, y, z), Block.getId(s));
        total++;
    }

    /** Chunks ordered outward from the given chunk, so the city appears to grow from its centre. */
    List<Long> orderedFrom(int cx, int cz) {
        List<Long> keys = new ArrayList<>(chunks.keySet());
        keys.sort((a, b) -> {
            long da = sq(ChunkPos.getX(a) - cx) + sq(ChunkPos.getZ(a) - cz), db = sq(ChunkPos.getX(b) - cx) + sq(ChunkPos.getZ(b) - cz);
            return da != db ? Long.compare(da, db) : Long.compare(a, b);
        });
        return keys;
    }

    private static long sq(long v) { return v * v; }
}
