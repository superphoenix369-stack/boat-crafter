package com.example.boatbase;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Draws one block through Minecraft's "submit" renderer.
 *
 * The name and shape of the method that draws a block has changed between 26.x versions, so this looks for it
 * at run time (and writes what it found to the game log under "[boatbase]"). It tries the plain
 * {@code submitBlock(PoseStack, BlockState, light, overlay, outline)} first, then {@code submitMovingBlock}.
 */
final class BoatBlockSubmitter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean searched;
    private static Method submitBlock;        // (PoseStack, BlockState, int, int, int)
    private static Method submitMoving;       // (PoseStack, MovingBlockRenderState[, int])
    private static Class<?> movingStateClass;
    private static boolean loggedFailure;

    private BoatBlockSubmitter() {}

    static void submit(SubmitNodeCollector collector, PoseStack poseStack, BlockState state, int light,
                       double x, double y, double z) {
        if (!searched) {
            search(collector);
        }
        try {
            if (submitBlock != null) {
                submitBlock.invoke(collector, poseStack, state, light, OverlayTexture.NO_OVERLAY, 0);
                return;
            }
            if (submitMoving != null) {
                Object movingState = buildMovingState(state, BlockPos.containing(x, y + 0.5, z));
                if (submitMoving.getParameterCount() == 2) {
                    submitMoving.invoke(collector, poseStack, movingState);
                } else {
                    submitMoving.invoke(collector, poseStack, movingState, 0);
                }
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.error("[boatbase] could not draw a boat block", t);
            }
        }
    }

    private static void search(SubmitNodeCollector collector) {
        searched = true;
        for (Method m : collector.getClass().getMethods()) {
            String name = m.getName();
            Class<?>[] p = m.getParameterTypes();
            if (name.startsWith("submit") && name.contains("Block")) {
                LOGGER.info("[boatbase] renderer offers {} {}", name, Arrays.toString(p));
            }
            if (name.equals("submitBlock") && p.length == 5 && p[0] == PoseStack.class && p[1] == BlockState.class) {
                submitBlock = m;
            }
            if (name.equals("submitMovingBlock") && (p.length == 2 || p.length == 3) && p[0] == PoseStack.class) {
                if (submitMoving == null || p.length < submitMoving.getParameterCount()) {
                    submitMoving = m;
                    movingStateClass = p[1];
                }
            }
        }
        if (submitBlock != null) {
            LOGGER.info("[boatbase] drawing boat blocks with submitBlock");
        } else if (submitMoving != null) {
            LOGGER.info("[boatbase] drawing boat blocks with submitMovingBlock");
        } else {
            LOGGER.error("[boatbase] no way to draw blocks found - the sailing boat will be invisible. "
                    + "Please send this log line and the 'renderer offers' lines above it.");
        }
    }

    private static Object buildMovingState(BlockState state, BlockPos pos) throws Exception {
        Object s = movingStateClass.getDeclaredConstructor().newInstance();
        ClientLevel level = Minecraft.getInstance().level;
        set(s, "randomSeedPos", pos);
        set(s, "blockPos", pos);
        set(s, "blockState", state);
        if (level != null) {
            set(s, "biome", level.getBiome(pos));
            set(s, "lightEngine", level.getLightEngine());
            try {
                set(s, "cardinalLighting", level.getClass().getMethod("cardinalLighting").invoke(level));
            } catch (ReflectiveOperationException ignored) {
                // not present in this version
            }
        }
        return s;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getField(field);
            f.set(target, value);
        } catch (ReflectiveOperationException ignored) {
            // this version has no field with that name
        }
    }
}
