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
     * Right-click: switch the engine on or off.
     * On: the boat becomes a moving boat and stays one, even when it sits still (so you can walk about on it).
     * Off: the boat coasts to a stop, straightens up and turns back into blocks, ready to build on.
     * (Steer with a Ship's Wheel built on the boat.)
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        int heading = BoatMover.headingFromYaw(player.getYRot());

        if (BoatMover.isEngineOn(serverLevel, pos)) {
            BoatMover.engineOff(serverLevel, pos);
            say(player, "Engine off");
        } else if (!BoatMover.isWaterLike(level.getBlockState(pos.below()))) {
            say(player, "The Boat Engine must float on water");
        } else {
            BoatMover.engineOn(serverLevel, pos, heading);
            say(player, "Engine on");
        }
        return InteractionResult.SUCCESS;
    }
}
