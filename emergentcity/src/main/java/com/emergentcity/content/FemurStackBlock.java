package com.emergentcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Femurs laid side by side, four to a layer, one to four layers to a block. Placing more femurs on the block adds a
 * layer (like snow). Seen from the end, a full block is a wall of femur heads, as in the ossuary.
 */
public class FemurStackBlock extends Block {
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 4);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    private static final VoxelShape[] SHAPES = new VoxelShape[4];

    static { for (int n = 0; n < 4; n++) SHAPES[n] = Block.box(0, 0, 0, 16, 4 * (n + 1), 16); }

    public FemurStackBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(LAYERS, 1).setValue(AXIS, Direction.Axis.Z));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(LAYERS, AXIS); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState here = ctx.getLevel().getBlockState(ctx.getClickedPos());
        if (here.is(this)) return here.setValue(LAYERS, Math.min(4, here.getValue(LAYERS) + 1));
        // the bones point away from the player, so their heads face the way you were looking
        return defaultBlockState().setValue(AXIS, ctx.getHorizontalDirection().getAxis());
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext ctx) {
        if (!ctx.isSecondaryUseActive() && ctx.getItemInHand().is(asItem()) && state.getValue(LAYERS) < 4) return true;
        return super.canBeReplaced(state, ctx);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPES[state.getValue(LAYERS) - 1]; }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) { return true; }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        if (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90)
            return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        return state;
    }
}
