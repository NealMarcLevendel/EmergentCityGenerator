package com.emergentcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Loose bones and fragments packed together: falls like gravel, with pale dust. */
public class BoneGravelBlock extends FallingBlock {
    public BoneGravelBlock(Properties props) { super(props); }

    @Override
    public int getDustColor(BlockState state, BlockGetter level, BlockPos pos) { return 0xD9CFB4; }
}
