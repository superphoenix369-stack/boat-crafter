package com.example.boatbase;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Lets the local player stand on, walk on and be blocked by a sailing boat, and carries them along with it.
 * (A sailing boat is an entity, and Minecraft only knows how to collide with a single box, so this does it by hand.)
 */
public final class BoatClientCollision {
    private static final double STEP_UP = 0.6;
    private static final double EPS = 1.0E-4;
    private static final int CARRY_TICKS = 20;     // keep carrying for a moment after a jump

    private static final Logger LOGGER = LogUtils.getLogger();
    private static int debugLines;
    private static int debugTick;

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
        debug(p);
        if (p.isPassenger() || p.isSpectator() || p.getAbilities().flying) {
            supportId = -1;
            return;
        }

        // 1. Carry the player along with the boat they were standing on.
        if (supportId != -1 && sinceSupport <= CARRY_TICKS) {
            for (BoatEntity b : BoatEntity.CLIENT_BOATS) {
                if (b.getId() == supportId) {
                    // Slide with the boat, and swing round the engine by however much the boat turned this tick.
                    double relX = p.getX() - (b.bx() - b.tickDx);
                    double relZ = p.getZ() - (b.bz() - b.tickDz);
                    double a = Math.toRadians(b.tickTurn);
                    double c = Math.cos(a);
                    double sn = Math.sin(a);
                    p.setPos(b.bx() + relX * c - relZ * sn, p.getY(), b.bz() + relX * sn + relZ * c);
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

    /** Writes a few lines to the game log so a "boat lags behind" report can be diagnosed. */
    private static void debug(LocalPlayer p) {
        debugTick++;
        for (BoatEntity b : BoatEntity.CLIENT_BOATS) {
            double step = b.rawStep;
            if (step > 0.6 && debugLines < 60) {
                debugLines++;
                LOGGER.info("[boatbase] boat update was {} blocks (boat at {}, {})",
                        String.format("%.2f", step), String.format("%.2f", b.getX()), String.format("%.2f", b.getZ()));
            }
            if (p.getVehicle() == b) {
                Vec3 want = b.helmSpot();
                double off = p.position().distanceTo(want);
                if (off > 0.25 && debugLines < 60) {
                    debugLines++;
                    LOGGER.info("[boatbase] rider is {} blocks from the helm spot (boat step {})",
                            String.format("%.2f", off), String.format("%.2f", step));
                }
                if (debugTick % 100 == 0 && debugLines < 60) {
                    debugLines++;
                    LOGGER.info("[boatbase] riding: boat step {} blocks/tick, rider offset {} blocks, {} blocks in boat",
                            String.format("%.3f", step), String.format("%.2f", off), b.getEntries().size());
                }
            }
        }
    }

    /** Returns true if the player ended up standing on this boat. */
    private static boolean resolve(LocalPlayer p, BoatEntity boat) {
        if (!p.getBoundingBox().inflate(0.5).intersects(boat.broadBounds())) return false;
        // The work is done in the boat's own frame, where its blocks are plain boxes (a boat sailing on a diagonal is
        // turned 45 degrees in the world). In that frame the player is a box of the same size, a little slimmer
        // when the boat is turned (a square turned by 45 degrees would otherwise stick out of it).
        boolean tilted = boat.isTurned();
        double c = boat.cosA();
        double sn = boat.sinA();
        boolean supported = false;
        for (int pass = 0; pass < 6; pass++) {
            boolean changed = false;
            for (AABB b : boat.getLocalBoxes()) {
                AABB pw = p.getBoundingBox();
                double rx = (pw.minX + pw.maxX) / 2 - boat.bx();
                double rz = (pw.minZ + pw.maxZ) / 2 - boat.bz();
                double lx = boat.localDx(rx, rz);
                double lz = boat.localDz(rx, rz);
                double span = Math.abs(c) + Math.abs(sn);            // 1.0 when lined up, 1.41 at 45 degrees
                double hx = tilted ? 0.3 + 0.15 * (span - 1.0) : (pw.maxX - pw.minX) / 2;
                double hz = tilted ? 0.3 + 0.15 * (span - 1.0) : (pw.maxZ - pw.minZ) / 2;
                double by = boat.getY();
                AABB pb = new AABB(lx - hx, pw.minY - by, lz - hz, lx + hx, pw.maxY - by, lz + hz);
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
                    } else {
                        // Push out sideways along the shortest way, in the boat's frame, then turn that push
                        // back into a world movement.
                        double pushX = 0, pushZ = 0;
                        if (m == posX) pushX = posX + EPS;
                        else if (m == negX) pushX = -(negX + EPS);
                        else if (m == posZ) pushZ = posZ + EPS;
                        else pushZ = -(negZ + EPS);
                        p.setPos(p.getX() + pushX * c - pushZ * sn, p.getY(), p.getZ() + pushX * sn + pushZ * c);
                    }
                }
                changed = true;
            }
            if (!changed) break;
        }
        return supported;
    }
}
