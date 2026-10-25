package com.emergentcity.city;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies a {@link BlockPlan} a time slice per server tick, recording previous states for undo.
 * Flags 2|16: send to clients, skip neighbour shape updates (no cascades, no gravity/fluid chain
 * reactions; pane/stair/door states are set explicitly by the voxelizer).
 */
final class Placer {
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    final ServerLevel level;
    final BlockPlan plan;
    final List<Long> order;
    final UndoLog undo;
    int chunkIdx, opIdx;
    long placed, skipped;

    Placer(ServerLevel level, BlockPlan plan, List<Long> order, UndoLog undo) {
        this.level = level; this.plan = plan; this.order = order; this.undo = undo;
    }

    /** Returns true when finished. */
    boolean tick(long budgetNanos) {
        long end = System.nanoTime() + budgetNanos;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (chunkIdx < order.size()) {
            BlockPlan.Ops ops = plan.chunks.get(order.get(chunkIdx));
            while (opIdx < ops.n) {
                long p = ops.pos[opIdx];
                BlockState target = Block.stateById(ops.st[opIdx]);
                opIdx++;
                mp.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
                BlockState cur = level.getBlockState(mp);
                if (cur == target || (target.isAir() && cur.isAir())) { skipped++; }
                else {
                    undo.add(p, Block.getId(cur));
                    level.setBlock(mp, target, FLAGS);
                    placed++;
                }
                if ((opIdx & 127) == 0 && System.nanoTime() > end) return false;
            }
            chunkIdx++;
            opIdx = 0;
        }
        return true;
    }

    double progress() {
        return order.isEmpty() ? 1 : (chunkIdx + (double) opIdx / Math.max(1, plan.chunks.get(order.get(Math.min(chunkIdx, order.size() - 1))).n)) / order.size();
    }
}

/** Previous block states, replayed in reverse to restore the world (block-entity contents are not kept). */
final class UndoLog {
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
