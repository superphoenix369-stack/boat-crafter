package com.example.boatbase;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.List;

public class BoatRenderState extends EntityRenderState {
    public List<BoatEntity.Entry> entries = List.of();
    public double worldX, worldY, worldZ;
    public float tilt;                  // degrees the boat is turned (clockwise)
    public double offsetX, offsetZ;     // where the smoothed boat is, relative to where Minecraft draws the entity
}
