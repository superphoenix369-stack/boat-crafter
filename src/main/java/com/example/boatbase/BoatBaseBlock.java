package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BoatBaseBlock extends Block {
    public BoatBaseBlock(Properties properties) {
        super(properties);
    }

    private static void say(Player player, String message) {
        if (player instanceof ServerPlayer sp) {
            sp.sendOverlayMessage(Component.literal(message));
        }
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
                say(player, "Heading " + facing.getName());
            } else {
                BoatMover.stop(serverLevel, pos);
                say(player, "Boat stopped");
            }
        } else {
            if (!level.getBlockState(pos.below()).is(Blocks.WATER)) {
                say(player, "The Boat Base must float on water");
            } else {
                BoatMover.start(serverLevel, pos, facing);
                say(player, "Sailing " + facing.getName());
            }
        }
        return InteractionResult.SUCCESS;
    }
}
