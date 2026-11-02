package com.example.boatbase;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class BoatBaseClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRenderers.register(BoatBaseMod.BOAT_ENTITY, BoatEntityRenderer::new);
        ClientTickEvents.END_CLIENT_TICK.register(BoatClientCollision::tick);
    }
}
