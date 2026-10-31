package com.emergentcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One to four skulls in a block, all looking the same way, set flush with the side they face. Placing another skull
 * on the block adds one (like candles): two side by side, then a third on top, then a fourth, so a full block is a
 * 2 x 2 frieze of skulls, as in the ossuary walls.
 */
public class SkullPileBlock extends Block {
    public static final IntegerProperty SKULLS = IntegerProperty.create("skulls", 1, 4);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Skulls (x, y) in a block facing north: each 8 x 8 x 8, front at z = 0. */
    static final int[][][] LAYOUT = {
            {{4, 0}},
            {{0, 0}, {8, 0}},
            {{0, 0}, {8, 0}, {4, 8}},
            {{0, 0}, {8, 0}, {0, 8}, {8, 8}}};
    private static final VoxelShape[][] SHAPES = new VoxelShape[4][4]; // [horizontal index][skulls - 1]

    static {
        for (Direction d : Direction.Plane.HORIZONTAL)
            for (int n = 0; n < 4; n++) {
                VoxelShape s = Shapes.empty();
                for (int[] sk : LAYOUT[n]) s = Shapes.or(s, box(d, sk[0], sk[1], 0, sk[0] + 8, sk[1] + 8, 8));
                SHAPES[d.get2DDataValue()][n] = s;
            }
    }

    /** A box given for a block facing north, turned to face d. */
    static VoxelShape box(Direction d, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (d) {
            case EAST -> Block.box(16 - z2, y1, x1, 16 - z1, y2, x2);
            case SOUTH -> Block.box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case WEST -> Block.box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            default -> Block.box(x1, y1, z1, x2, y2, z2);
        };
    }

    public SkullPileBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(SKULLS, 1).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(SKULLS, FACING); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState here = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (here.is(this)) return here.setValue(SKULLS, Math.min(4, here.getValue(SKULLS) + 1));
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        if (!ctx.isSecondaryUseActive() && ctx.getItemInHand().is(asItem()) && state.getValue(SKULLS) < 4) return true;
        return super.canBeReplaced(state, ctx);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPES[state.getValue(FACING).get2DDataValue()][state.getValue(SKULLS) - 1];
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) { return true; }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) { return state.setValue(FACING, rot.rotate(state.getValue(FACING))); }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
}
