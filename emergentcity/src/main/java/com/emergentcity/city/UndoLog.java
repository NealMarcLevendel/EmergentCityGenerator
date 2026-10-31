package com.emergentcity.city;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Previous block states, replayed in reverse to restore the world (block-entity contents are not kept). */
public final class UndoLog {
    long[] pos = new long[4096];
    int[] st = new int[4096];
    int n;
    final ServerLevel level;
    final String label;
    /** Villagers spawned for this city, removed again on undo. */
    final java.util.List<java.util.UUID> entities = new java.util.ArrayList<>();

    UndoLog(ServerLevel level, String label) { this.level = level; this.label = label; }

    void add(long p, int s) {
        if (n == pos.length) { pos = Arrays.copyOf(pos, n * 2); st = Arrays.copyOf(st, n * 2); }
        pos[n] = p; st[n] = s; n++;
    }

    /**
     * Keeps only the first (oldest) entry for each position: undo still brings every block back to what it was before
     * the city, but the log holds one entry per block changed, however often it changed (towns that come and go forever).
     */
    public void compact() {
        int cap = Integer.highestOneBit(Math.max(16, n * 2)) << 1;
        long[] keys = new long[cap];
        boolean[] used = new boolean[cap];
        int w = 0;
        for (int r = 0; r < n; r++) {
            long p = pos[r];
            int h = (int) ((p * 0x9E3779B97F4A7C15L) >>> 33) & (cap - 1);
            boolean seen = false;
            while (used[h]) { if (keys[h] == p) { seen = true; break; } h = (h + 1) & (cap - 1); }
            if (seen) continue;
            used[h] = true; keys[h] = p;
            pos[w] = p; st[w] = st[r]; w++;
        }
        n = w;
        if (pos.length > 2 * n + 4096) { pos = Arrays.copyOf(pos, n + 4096); st = Arrays.copyOf(st, n + 4096); }
    }

    /** Restores up to the time budget, newest change first; returns true when everything is restored. */
    boolean undoTick(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (n > 0) {
            n--;
            long p = pos[n];
            mp.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            level.setBlock(mp, Block.stateById(st[n]), Placer.FLAGS);
            if ((n & 127) == 0 && System.nanoTime() > end) return false;
        }
        for (java.util.UUID id : entities) {
            net.minecraft.world.entity.Entity e = level.getEntity(id);
            if (e != null) e.discard();
        }
        entities.clear();
        return true;
    }
}
