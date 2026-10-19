package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Steering wheel. Build it onto a boat, right-click it to take the helm. */
public class ShipWheelBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 13, 13);

    public ShipWheelBlock(Properties properties) {
        super(properties);
    }

    // No @Override on purpose: if this signature ever differs, the block just keeps a full-cube hitbox.
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            BoatMover.sit(serverLevel, pos, serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }
}
