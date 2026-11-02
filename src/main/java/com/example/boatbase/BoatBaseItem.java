package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Places the Boat Base on top of the water surface, like a lily pad. */
public class BoatBaseItem extends BlockItem {
    public BoatBaseItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResult.PASS;
        }
        BlockPos waterPos = hit.getBlockPos();
        BlockPos placePos = waterPos.above();
        if (level.getFluidState(waterPos).is(FluidTags.WATER) && level.getBlockState(placePos).isAir()) {
            if (!level.isClientSide()) {
                level.setBlock(placePos, getBlock().defaultBlockState(), Block.UPDATE_ALL);
                ItemStack stack = player.getItemInHand(hand);
                stack.consume(1, player);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** Try the water-surface placement first, even when the player is aiming at the sea floor. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null) {
            InteractionResult result = use(context.getLevel(), player, context.getHand());
            if (result != InteractionResult.PASS) {
                return result;
            }
        }
        return super.useOn(context);
    }
}
