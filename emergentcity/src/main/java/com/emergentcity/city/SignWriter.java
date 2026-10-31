package com.emergentcity.city;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/** Writes the text of placed plaques (wall signs); waxed so players can't edit them by accident. */
final class SignWriter {
    private SignWriter() {}

    static int apply(ServerLevel level, List<Catacombs.Sign> signs) {
        int n = 0;
        for (Catacombs.Sign s : signs) {
            BlockPos p = BlockPos.of(s.pos());
            BlockEntity be = level.getBlockEntity(p);
            if (!(be instanceof SignBlockEntity sb)) continue;
            SignText t = new SignText();
            for (int i = 0; i < 4 && i < s.lines().length; i++) t = t.setMessage(i, Component.literal(s.lines()[i]));
            sb.setText(t, true);
            sb.setWaxed(true);
            sb.setChanged();
            BlockState st = level.getBlockState(p);
            level.sendBlockUpdated(p, st, st, 3);
            n++;
        }
        return n;
    }
}
