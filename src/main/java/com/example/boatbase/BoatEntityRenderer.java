package com.example.boatbase;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Draws every block of a sailing boat. */
public class BoatEntityRenderer extends EntityRenderer<BoatEntity, BoatRenderState> {
    public BoatEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public BoatRenderState createRenderState() {
        return new BoatRenderState();
    }

    @Override
    public void extractRenderState(BoatEntity entity, BoatRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.entries = entity.getEntries();
        state.tilt = entity.renderTilt(partialTick);
        state.worldX = entity.getX();
        state.worldY = entity.getY();
        state.worldZ = entity.getZ();
        Vec3 drawn = entity.getPosition(partialTick);
        state.offsetX = entity.renderX(partialTick) - drawn.x;
        state.offsetZ = entity.renderZ(partialTick) - drawn.z;
    }

    @Override
    public void submit(BoatRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        // Everything is drawn around the engine's cell centre; a diagonal boat is turned 45 degrees about it.
        poseStack.pushPose();
        poseStack.translate(state.offsetX, 0, state.offsetZ);
        if (Math.abs(state.tilt) > 0.01f) {
            // Clockwise seen from above = a negative turn about the up axis.
            poseStack.mulPose(new Matrix4f().rotationY((float) Math.toRadians(-state.tilt)));
        }
        for (BoatEntity.Entry en : state.entries) {
            poseStack.pushPose();
            poseStack.translate(en.dx() - 0.5, en.dy(), en.dz() - 0.5);
            BoatBlockSubmitter.submit(collector, poseStack, en.state(), state.lightCoords,
                    state.worldX + en.dx(), state.worldY + en.dy(), state.worldZ + en.dz());
            poseStack.popPose();
        }
        poseStack.popPose();
    }

    // No @Override on purpose: a boat is big, so never cull it just because its centre is off screen.
    public boolean shouldRender(BoatEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return true;
    }
}
