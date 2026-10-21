package com.example.boatbase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lets the local player stand on, walk on and be blocked by a sailing boat, and carries them along with it.
 * (A sailing boat is an entity, and Minecraft only knows how to collide with a single box, so this does it by hand.)
 */
public final class BoatClientCollision {
    private static final double STEP_UP = 0.6;
    private static final double EPS = 1.0E-4;
    private static final int CARRY_TICKS = 20;     // keep carrying for a moment after a jump

    private static int supportId = -1;
    private static int sinceSupport = 999;

    private BoatClientCollision() {}

    public static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            BoatEntity.CLIENT_BOATS.clear();
            supportId = -1;
            return;
        }
        BoatEntity.CLIENT_BOATS.removeIf(b -> b.isRemoved());
        if (p.isPassenger() || p.isSpectator() || p.getAbilities().flying) {
            supportId = -1;
            return;
        }

        // 1. Carry the player along with the boat they were standing on.
        if (supportId != -1 && sinceSupport <= CARRY_TICKS) {
            for (BoatEntity b : BoatEntity.CLIENT_BOATS) {
                if (b.getId() == supportId) {
                    p.setPos(p.getX() + b.tickDx, p.getY(), p.getZ() + b.tickDz);
                }
            }
        }

        // 2. Push the player out of the boat's blocks (and up onto the deck).
        int standingOn = -1;
        for (BoatEntity b : BoatEntity.CLIENT_BOATS) {
            if (resolve(p, b)) standingOn = b.getId();
        }
        if (standingOn != -1) {
            supportId = standingOn;
            sinceSupport = 0;
        } else {
            sinceSupport++;
        }
    }

    /** Returns true if the player ended up standing on this boat. */
    private static boolean resolve(LocalPlayer p, BoatEntity boat) {
        if (!p.getBoundingBox().inflate(0.5).intersects(boat.broadBounds())) return false;
        boolean supported = false;
        for (int pass = 0; pass < 6; pass++) {
            boolean changed = false;
            for (AABB local : boat.getLocalBoxes()) {
                AABB b = local.move(boat.getX(), boat.getY(), boat.getZ());
                AABB pb = p.getBoundingBox();
                if (!pb.intersects(b)) continue;

                double up = b.maxY - pb.minY;
                if (up <= STEP_UP) {
                    p.setPos(p.getX(), p.getY() + up + EPS, p.getZ());
                    p.setOnGround(true);
                    p.resetFallDistance();
                    Vec3 dm = p.getDeltaMovement();
                    if (dm.y < 0) p.setDeltaMovement(dm.x, 0, dm.z);
                    supported = true;
                } else {
                    double posX = b.maxX - pb.minX;
                    double negX = pb.maxX - b.minX;
                    double posZ = b.maxZ - pb.minZ;
                    double negZ = pb.maxZ - b.minZ;
                    double m = Math.min(Math.min(posX, negX), Math.min(posZ, negZ));
                    double down = pb.maxY - b.minY;
                    if (p.getDeltaMovement().y > 0 && down < m) {
                        p.setPos(p.getX(), p.getY() - down - EPS, p.getZ());
                        Vec3 dm = p.getDeltaMovement();
                        p.setDeltaMovement(dm.x, 0, dm.z);
                    } else if (m == posX) {
                        p.setPos(p.getX() + posX + EPS, p.getY(), p.getZ());
                    } else if (m == negX) {
                        p.setPos(p.getX() - negX - EPS, p.getY(), p.getZ());
                    } else if (m == posZ) {
                        p.setPos(p.getX(), p.getY(), p.getZ() + posZ + EPS);
                    } else {
                        p.setPos(p.getX(), p.getY(), p.getZ() - negZ - EPS);
                    }
                }
                changed = true;
            }
            if (!changed) break;
        }
        return supported;
    }
}
