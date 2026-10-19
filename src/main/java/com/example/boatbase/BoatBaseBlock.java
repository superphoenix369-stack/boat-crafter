package com.example.boatbase;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BoatBaseBlock extends Block {
    public static final MapCodec<BoatBaseBlock> CODEC = simpleCodec(BoatBaseBlock::new);

    public BoatBaseBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    /**
     * Right-click: start sailing the way you are facing.
     * Right-click while sailing: stop. Sneak + right-click while sailing: turn to face your direction.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        Direction facing = player.getDirection();

        if (BoatMover.isSailing(serverLevel, pos)) {
            if (player.isShiftKeyDown()) {
                BoatMover.setDirection(serverLevel, pos, facing);
                player.displayClientMessage(Component.literal("Heading " + facing.getName()), true);
            } else {
                BoatMover.stop(serverLevel, pos);
                player.displayClientMessage(Component.literal("Boat stopped"), true);
            }
        } else {
            if (!level.getBlockState(pos.below()).is(Blocks.WATER)) {
                player.displayClientMessage(Component.literal("The Boat Base must float on water"), true);
            } else {
                BoatMover.start(serverLevel, pos, facing);
                player.displayClientMessage(Component.literal("Sailing " + facing.getName()), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
