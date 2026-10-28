package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
     * (For proper steering, build a Ship's Wheel on the boat.)
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        int heading = BoatMover.headingFromYaw(player.getYRot());

        if (BoatMover.isMoving(serverLevel, pos)) {
            if (player.isShiftKeyDown()) {
                BoatMover.setHeading(serverLevel, pos, heading);
                say(player, "Heading " + BoatMover.headingName(heading));
            } else {
                BoatMover.stop(serverLevel, pos);
                say(player, "Boat stopped");
            }
        } else {
            if (!BoatMover.isWaterLike(level.getBlockState(pos.below()))) {
                say(player, "The Boat Engine must float on water");
            } else {
                BoatMover.start(serverLevel, pos, heading);
                say(player, "Sailing " + BoatMover.headingName(heading));
            }
        }
        return InteractionResult.SUCCESS;
    }
}
