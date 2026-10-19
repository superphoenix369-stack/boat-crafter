package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves every block connected to a Boat Base. Fewer blocks = faster boat.
 *
 * Speed (blocks/second) = BASE_SPEED / (1 + (blockCount - 1) / BLOCKS_FOR_HALF_SPEED)
 *   1 block -> 40 b/s,  21 blocks -> 20 b/s,  100 blocks -> ~7 b/s,  400 blocks -> ~2 b/s
 *
 * Steering: sit at a Ship's Wheel.  W = go, S = stop, A / D = turn left / right.  Sneak = leave.
 */
public final class BoatMover {
    private static final int MAX_BLOCKS = 4096;
    private static final double BASE_SPEED = 40.0;           // blocks/second for a lone base
    private static final double BLOCKS_FOR_HALF_SPEED = 20.0;
    private static final double MIN_SPEED = 0.5;
    private static final int MAX_STEPS_PER_TICK = 2;         // 2 blocks/tick = 40 blocks/second
    private static final double SEAT_DROP = 0.875;           // raise/lower the helm seat by changing this
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final List<Sailing> ACTIVE = new ArrayList<>();

    private static final class Sailing {
        final ServerLevel level;
        BlockPos base;
        final int waterTopY;           // highest water block of the sea the boat floats on
        Direction dir;
        boolean moving = false;
        int lastCount = 1;
        double progress = 0;
        /** What was in each cell before the boat moved into it, so it can be put back. */
        final Map<BlockPos, BlockState> displaced = new HashMap<>();
        Entity seat;                   // invisible seat at the helm (null if nobody is steering)
        boolean prevLeft, prevRight;

        Sailing(ServerLevel level, BlockPos base, Direction dir, int waterTopY) {
            this.level = level;
            this.base = base;
            this.dir = dir;
            this.waterTopY = waterTopY;
        }
    }

    private BoatMover() {}

    // ---------------------------------------------------------------- public API

    public static boolean isMoving(ServerLevel level, BlockPos base) {
        Sailing s = find(level, base);
        return s != null && s.moving;
    }

    public static void start(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = getOrCreate(level, base, dir);
        s.dir = dir;
        s.moving = true;
    }

    public static void stop(ServerLevel level, BlockPos base) {
        Sailing s = find(level, base);
        if (s != null) s.moving = false;
    }

    public static void setDirection(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = find(level, base);
        if (s != null) s.dir = dir;
    }

    public static double speedFor(int blockCount) {
        double speed = BASE_SPEED / (1.0 + (Math.max(1, blockCount) - 1) / BLOCKS_FOR_HALF_SPEED);
        return Math.max(MIN_SPEED, speed);
    }

    /** Kelp, seagrass and corals: the boat ignores these and sails straight through them. */
    public static boolean isSeaPlant(BlockState st) {
        return st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT)
                || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS)
                || st.is(Blocks.SEA_PICKLE)
                || st.is(BlockTags.CORALS) || st.is(BlockTags.WALL_CORALS);
    }

    public static boolean isWaterLike(BlockState st) {
        return st.is(Blocks.WATER) || isSeaPlant(st);
    }

    /** Called when a player right-clicks a Ship's Wheel. */
    public static void sit(ServerLevel level, BlockPos wheel, ServerPlayer player) {
        Set<BlockPos> blocks = collect(level, wheel);
        if (blocks == null) {
            tell(player, "Boat too big (max " + MAX_BLOCKS + " blocks)");
            return;
        }
        BlockPos base = null;
        for (BlockPos p : blocks) {
            if (level.getBlockState(p).is(BoatBaseMod.BOAT_BASE)) {
                base = p;
                break;
            }
        }
        if (base == null) {
            tell(player, "Connect the wheel to a Boat Base");
            return;
        }

        Sailing s = getOrCreate(level, base, player.getDirection());
        if (s.seat != null && s.seat.isAlive() && !s.seat.getPassengers().isEmpty()) {
            tell(player, "Someone is already at the helm");
            return;
        }
        if (s.seat != null) {
            s.seat.discard();
            s.seat = null;
        }

        Entity seat = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (seat == null) {
            tell(player, "Could not create the helm seat");
            return;
        }
        seat.setPos(wheel.getX() + 0.5, wheel.getY() - SEAT_DROP, wheel.getZ() + 0.5);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        level.addFreshEntity(seat);
        player.startRiding(seat);

        s.seat = seat;
        s.prevLeft = false;
        s.prevRight = false;
        tell(player, "At the helm: W go, S stop, A/D turn, Sneak to leave. Heading " + s.dir.getName());
    }

    /** Called when the server stops, so nothing is left over between worlds. */
    public static void shutdown() {
        for (Sailing s : ACTIVE) {
            if (s.seat != null) s.seat.discard();
        }
        ACTIVE.clear();
    }

    // ---------------------------------------------------------------- ticking

    public static void tick() {
        Iterator<Sailing> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Sailing s = it.next();

            if (!s.level.getBlockState(s.base).is(BoatBaseMod.BOAT_BASE)) {
                if (s.seat != null) s.seat.discard();
                it.remove();
                continue;
            }

            // Helm: read steering input from whoever is sitting there.
            if (s.seat != null) {
                Entity rider = s.seat.isAlive() ? s.seat.getFirstPassenger() : null;
                if (rider == null) {
                    s.seat.discard();
                    s.seat = null;
                    s.moving = false;
                } else if (rider instanceof ServerPlayer p) {
                    steer(s, p);
                }
            }

            if (!s.moving) {
                if (s.seat == null) it.remove();
                continue;
            }

            s.progress = Math.min(s.progress + speedFor(s.lastCount) / 20.0, MAX_STEPS_PER_TICK);
            int steps = (int) s.progress;
            if (steps < 1) continue;

            Set<BlockPos> blocks = collect(s.level, s.base);
            if (blocks == null) {
                notifyNearby(s, "Boat too big (max " + MAX_BLOCKS + " blocks)");
                s.moving = false;
                continue;
            }
            s.lastCount = blocks.size();

            int done = 0;
            boolean blocked = false;
            for (int i = 0; i < steps; i++) {
                Set<BlockPos> next = move(s, blocks);
                if (next == null) {
                    blocked = true;
                    break;
                }
                blocks = next;
                done++;
            }
            s.progress -= done;
            if (blocked) {
                notifyNearby(s, "Boat blocked");
                s.moving = false;
            }
        }
    }

    private static void steer(Sailing s, ServerPlayer p) {
        Input in = p.getLastClientInput();
        if (in.forward() && !s.moving) {
            s.moving = true;
            tell(p, "Full ahead - heading " + s.dir.getName());
        }
        if (in.backward() && s.moving) {
            s.moving = false;
            tell(p, "Boat stopped");
        }
        if (in.left() && !s.prevLeft) {
            s.dir = s.dir.getCounterClockWise();
            tell(p, "Heading " + s.dir.getName());
        }
        if (in.right() && !s.prevRight) {
            s.dir = s.dir.getClockWise();
            tell(p, "Heading " + s.dir.getName());
        }
        s.prevLeft = in.left();
        s.prevRight = in.right();
    }

    // ---------------------------------------------------------------- helpers

    private static Sailing find(ServerLevel level, BlockPos base) {
        for (Sailing s : ACTIVE) {
            if (s.level == level && s.base.equals(base)) return s;
        }
        return null;
    }

    private static Sailing getOrCreate(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = find(level, base);
        if (s == null) {
            s = new Sailing(level, base.immutable(), dir, computeWaterTop(level, base));
            ACTIVE.add(s);
        }
        return s;
    }

    /** Finds the top water block of the sea around the base (so we know what is "water" and what is "air"). */
    private static int computeWaterTop(ServerLevel level, BlockPos base) {
        int best = base.getY() - 1;
        BlockPos[] columns = {base, base.north(), base.south(), base.east(), base.west()};
        for (BlockPos c : columns) {
            int y = base.getY() - 1;
            if (!isWaterLike(level.getBlockState(c.atY(y)))) continue;
            while (isWaterLike(level.getBlockState(c.atY(y + 1)))) y++;
            best = Math.max(best, y);
        }
        return best;
    }

    private static void tell(ServerPlayer p, String msg) {
        p.sendOverlayMessage(Component.literal(msg));
    }

    private static void notifyNearby(Sailing s, String msg) {
        for (ServerPlayer p : s.level.players()) {
            if (p.distanceToSqr(s.base.getX(), s.base.getY(), s.base.getZ()) < 48 * 48) {
                tell(p, msg);
            }
        }
    }

    private static boolean isTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.CORAL_BLOCKS)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.BEDROCK);
    }

    /** Flood-fills every solid block attached to the start block. Returns null if the boat is too big. */
    private static Set<BlockPos> collect(ServerLevel level, BlockPos start) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = start.immutable();
        seen.add(first);
        queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (seen.contains(n)) continue;
                BlockState st = level.getBlockState(n);
                if (st.isAir() || st.getBlock() instanceof LiquidBlock) continue;
                if (isSeaPlant(st) || isTerrain(st)) continue;
                BlockPos imm = n.immutable();
                seen.add(imm);
                if (seen.size() > MAX_BLOCKS) return null;
                queue.add(imm);
            }
        }
        return seen;
    }

    /** Moves the boat one block. Returns the boat's new block positions, or null if it is blocked. */
    private static Set<BlockPos> move(Sailing s, Set<BlockPos> blocks) {
        ServerLevel level = s.level;
        Direction d = s.dir;

        Set<BlockPos> moved = new HashSet<>();
        for (BlockPos p : blocks) moved.add(p.relative(d).immutable());

        // 1. Check every destination is free (kelp, seagrass and corals do not count as obstacles).
        for (BlockPos t : moved) {
            if (blocks.contains(t)) continue;
            if (!level.isInWorldBounds(t)) return null;
            BlockState ts = level.getBlockState(t);
            if (t.getY() <= s.waterTopY) {
                if (!isWaterLike(ts)) return null;                       // hull must stay in water
            } else {
                if (!(ts.isAir() || ts.is(Blocks.WATER) || isSeaPlant(ts)
                        || (ts.canBeReplaced() && ts.getFluidState().isEmpty()))) {
                    return null;
                }
            }
        }

        // 2. Find entities riding on the boat before it moves.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        AABB box = new AABB(minX - 1, minY - 2, minZ - 1, maxX + 2, maxY + 4, maxZ + 2);
        List<Entity> riders = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box)) {
            if (e.isPassenger() || e == s.seat) continue;   // passengers travel with their seat
            BlockPos feet = e.blockPosition();
            if (blocks.contains(feet.below()) || blocks.contains(feet) || blocks.contains(feet.below(2))) {
                riders.add(e);
            }
        }
        if (s.seat != null && s.seat.isAlive()) riders.add(s.seat);

        // 3. Remember what we are about to push out of the way, so it can be put back later.
        s.displaced.keySet().retainAll(blocks);
        for (BlockPos t : moved) {
            if (!blocks.contains(t)) {
                s.displaced.put(t, level.getBlockState(t));
            }
        }

        // 4. Save blocks and container contents (block entities) before touching anything.
        Map<BlockPos, BlockState> old = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntityData = new HashMap<>();
        for (BlockPos p : blocks) {
            old.put(p, level.getBlockState(p));
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                blockEntityData.put(p, be.saveWithFullMetadata(level.registryAccess()));
            }
        }

        // 5. Clear the cells the boat is leaving, restoring the water (or kelp etc.) that was there before.
        //    Removing the block entity first stops chests from spilling their items.
        for (BlockPos p : blocks) {
            if (blockEntityData.containsKey(p)) {
                level.removeBlockEntity(p);
            }
        }
        for (BlockPos p : blocks) {
            if (moved.contains(p)) continue;   // another boat block is about to take this cell
            BlockState restore = s.displaced.remove(p);
            if (restore == null) {
                restore = p.getY() <= s.waterTopY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
            level.setBlock(p, restore, FLAGS);
        }

        // 6. Place everything one block over, restoring container contents.
        for (Map.Entry<BlockPos, BlockState> e : old.entrySet()) {
            BlockPos target = e.getKey().relative(d);
            level.setBlock(target, e.getValue(), FLAGS);
            CompoundTag tag = blockEntityData.get(e.getKey());
            if (tag != null) {
                BlockEntity restored = BlockEntity.loadStatic(target, e.getValue(), tag, level.registryAccess());
                if (restored != null) {
                    level.setBlockEntity(restored);
                }
            }
        }
        s.base = s.base.relative(d).immutable();

        // 7. Carry the riders (and the helm seat, with its passenger) along.
        for (Entity e : riders) {
            e.teleportRelative(d.getStepX(), 0, d.getStepZ());
        }
        return moved;
    }
}
