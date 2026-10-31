package com.emergentcity.city;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
    /**
     * Underground work (the catacombs): below this y, blocks that are not natural ground (a mineshaft's planks and
     * rails, a stronghold's bricks, chests and spawners) are left as they are.
     */
    int naturalBelowY = Integer.MIN_VALUE;

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
                else if (mp.getY() < naturalBelowY && manMade(mp, cur)) { skipped++; }
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

    private boolean manMade(BlockPos p, BlockState s) {
        if (s.isAir()) return false;
        return level.getBlockEntity(p) != null || s.is(BlockTags.PLANKS) || s.is(BlockTags.RAILS) || s.is(BlockTags.STONE_BRICKS)
                || s.is(BlockTags.WOOL) || s.is(BlockTags.FENCES) || s.is(Blocks.COBWEB) || s.is(Blocks.DEEPSLATE_BRICKS)
                || s.is(Blocks.DEEPSLATE_TILES) || s.is(Blocks.REINFORCED_DEEPSLATE) || s.is(Blocks.POLISHED_DEEPSLATE)
                || s.is(Blocks.SCULK) || s.is(Blocks.SCULK_VEIN);
    }

    double progress() {
        return order.isEmpty() ? 1 : (chunkIdx + (double) opIdx / Math.max(1, plan.chunks.get(order.get(Math.min(chunkIdx, order.size() - 1))).n)) / order.size();
    }
}

