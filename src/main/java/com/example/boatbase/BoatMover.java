package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.block.Rotation;
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
import java.util.function.Function;

/**
 * Moves and turns every block connected to a Boat Base. Fewer blocks = faster boat.
 *
 * Speed (blocks/second) = BASE_SPEED * engines / (1 + (blockCount - 1) / BLOCKS_FOR_HALF_SPEED), capped at MAX_SPEED
 *   (an "engine" is a Boat Base block; every one on the boat adds thrust)
 *   1 engine:  1 block -> 40 b/s,  21 blocks -> 20 b/s,  100 blocks -> ~7 b/s,  400 blocks -> ~2 b/s
 *   3 engines: 21 blocks -> 60 b/s,  100 blocks -> ~20 b/s
 *
 * The world only changes every UPDATE_INTERVAL_TICKS ticks (in a jump of up to MAX_BLOCKS_PER_UPDATE blocks).
 * Changing blocks every single tick makes the game client re-draw the boat's chunk so often that the boat
 * barely shows up; fewer, bigger updates let the client keep up. If the boat still flickers, lower BASE_SPEED
 * or raise UPDATE_INTERVAL_TICKS.
 *
 * Steering: sit at a Ship's Wheel.  W = go, S = stop, A / D = turn the boat left / right.  Sneak = leave.
 */
public final class BoatMover {
    private static final int MAX_BLOCKS = 4096;
    private static final double BASE_SPEED = 40.0;           // blocks/second for a lone base
    private static final double BLOCKS_FOR_HALF_SPEED = 20.0;
    private static final double MIN_SPEED = 0.5;
    private static final int UPDATE_INTERVAL_TICKS = 2;      // the world changes this often (2 = 10 times/second)
    private static final int MAX_BLOCKS_PER_UPDATE = 8;      // 8 blocks every 2 ticks = 80 blocks/second top speed
    private static final double MAX_SPEED = MAX_BLOCKS_PER_UPDATE * 20.0 / UPDATE_INTERVAL_TICKS;
    private static final double SEAT_DROP = 1.375;           // helmsman's feet stand on the floor; raise/lower by changing this
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final List<Sailing> ACTIVE = new ArrayList<>();

    private static final class Sailing {
        final ServerLevel level;
        BlockPos base;
        final int waterTopY;           // highest water block of the sea the boat floats on
        Direction dir;                 // the way the boat is currently pointing / sailing
        Direction pendingDir;          // the way it has been asked to turn to
        boolean moving = false;
        int lastCount = 1;
        int lastEngines = 1;
        Set<BlockPos> known = new HashSet<>();   // blocks seen last time, used to find another engine if one is lost
        double progress = 0;
        int sinceUpdate = 0;
        /** What was in each cell before the boat moved into it, so it can be put back. */
        final Map<BlockPos, BlockState> displaced = new HashMap<>();
        Entity seat;                   // invisible seat at the helm (null if nobody is steering)
        boolean prevLeft, prevRight;

        Sailing(ServerLevel level, BlockPos base, Direction dir, int waterTopY) {
            this.level = level;
            this.base = base;
            this.dir = dir;
            this.pendingDir = dir;
            this.waterTopY = waterTopY;
            this.known.add(base);
        }
    }

    private BoatMover() {}

    // ---------------------------------------------------------------- public API

    public static boolean isMoving(ServerLevel level, BlockPos base) {
        Sailing s = findBoat(level, base);
        return s != null && s.moving;
    }

    /** Start sailing. A boat that is already known will turn to face {@code dir} first. */
    public static void start(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = getOrCreate(level, base, dir);
        s.pendingDir = dir;
        s.moving = true;
    }

    public static void stop(ServerLevel level, BlockPos base) {
        Sailing s = findBoat(level, base);
        if (s != null) s.moving = false;
    }

    /** Turn the boat to face {@code dir}. */
    public static void setDirection(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = findBoat(level, base);
        if (s != null) s.pendingDir = dir;
    }

    public static double speedFor(int blockCount, int engines) {
        double drag = 1.0 + (Math.max(1, blockCount) - 1) / BLOCKS_FOR_HALF_SPEED;
        double speed = BASE_SPEED * Math.max(1, engines) / drag;
        return Math.min(MAX_SPEED, Math.max(MIN_SPEED, speed));
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

        // The helmsman stands in the cell the wheel faces, so that cell (and the one above) must be clear.
        BlockState wheelState = level.getBlockState(wheel);
        Direction facing = wheelState.hasProperty(ShipWheelBlock.FACING)
                ? wheelState.getValue(ShipWheelBlock.FACING)
                : player.getDirection().getOpposite();
        BlockPos front = wheel.relative(facing);
        if (!level.getBlockState(front).getCollisionShape(level, front).isEmpty()
                || !level.getBlockState(front.above()).getCollisionShape(level, front.above()).isEmpty()) {
            tell(player, "Clear the space in front of the wheel to stand there");
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

        // Looked up by name because the ARMOR_STAND constant is not available under that name in 26.x.
        EntityType<?> seatType = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "armor_stand"));
        Entity seat = seatType == null ? null : seatType.create(level, EntitySpawnReason.COMMAND);
        if (seat == null) {
            tell(player, "Could not create the helm seat");
            return;
        }
        seat.setPos(front.getX() + 0.5, front.getY() - SEAT_DROP, front.getZ() + 0.5);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        level.addFreshEntity(seat);
        player.startRiding(seat);

        s.seat = seat;
        s.prevLeft = false;
        s.prevRight = false;
        tell(player, "At the helm: W go, S stop, A/D turn, Sneak to leave. Heading " + s.pendingDir.getName());
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
                BlockPos other = null;
                for (BlockPos p : s.known) {
                    if (s.level.getBlockState(p).is(BoatBaseMod.BOAT_BASE)) {
                        other = p.immutable();
                        break;
                    }
                }
                if (other == null) {
                    if (s.seat != null) s.seat.discard();
                    it.remove();
                    continue;
                }
                s.base = other;
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

            // Turn the whole boat, one quarter-turn per tick.
            if (s.pendingDir != s.dir) {
                boolean clockwise = s.pendingDir != s.dir.getCounterClockWise();
                if (!rotateBoat(s, clockwise)) {
                    notifyNearby(s, "No room to turn");
                    s.pendingDir = s.dir;
                }
            }

            if (!s.moving) {
                if (s.seat == null && s.pendingDir == s.dir) it.remove();
                continue;
            }

            // Sail: only change the world every UPDATE_INTERVAL_TICKS ticks, in one bigger jump.
            s.progress = Math.min(s.progress + speedFor(s.lastCount, s.lastEngines) / 20.0, MAX_BLOCKS_PER_UPDATE);
            if (++s.sinceUpdate < UPDATE_INTERVAL_TICKS) continue;
            s.sinceUpdate = 0;
            int want = (int) s.progress;
            if (want < 1) continue;

            Set<BlockPos> blocks = collect(s.level, s.base);
            if (blocks == null) {
                notifyNearby(s, "Boat too big (max " + MAX_BLOCKS + " blocks)");
                s.moving = false;
                continue;
            }
            s.lastCount = blocks.size();
            int engines = 0;
            for (BlockPos p : blocks) {
                if (s.level.getBlockState(p).is(BoatBaseMod.BOAT_BASE)) engines++;
            }
            s.lastEngines = Math.max(1, engines);
            s.known = blocks;

            int moved = translate(s, blocks, want);
            if (moved == 0) {
                notifyNearby(s, "Boat blocked");
                s.moving = false;
            } else {
                s.progress -= moved;
            }
        }
    }

    private static void steer(Sailing s, ServerPlayer p) {
        Input in = p.getLastClientInput();
        if (in.forward() && !s.moving) {
            s.moving = true;
            tell(p, "Full ahead - heading " + s.pendingDir.getName());
        }
        if (in.backward() && s.moving) {
            s.moving = false;
            tell(p, "Boat stopped");
        }
        if (in.left() && !s.prevLeft) {
            s.pendingDir = s.pendingDir.getCounterClockWise();
            tell(p, "Turning to " + s.pendingDir.getName());
        }
        if (in.right() && !s.prevRight) {
            s.pendingDir = s.pendingDir.getClockWise();
            tell(p, "Turning to " + s.pendingDir.getName());
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

    /** The controller of the boat that this block belongs to (a boat can have several Boat Bases), or null. */
    private static Sailing findBoat(ServerLevel level, BlockPos anyBlock) {
        Set<BlockPos> boat = collect(level, anyBlock);
        if (boat == null) return null;
        for (Sailing s : ACTIVE) {
            if (s.level == level && boat.contains(s.base)) return s;
        }
        return null;
    }

    private static Sailing getOrCreate(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = findBoat(level, base);
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

    /** Can a boat block sit in this cell? Kelp, seagrass and corals do not count as obstacles. */
    private static boolean cellFree(Sailing s, BlockPos t) {
        ServerLevel level = s.level;
        if (!level.isInWorldBounds(t)) return false;
        BlockState ts = level.getBlockState(t);
        if (t.getY() <= s.waterTopY) {
            return isWaterLike(ts);                                   // hull must stay in water
        }
        return ts.isAir() || ts.is(Blocks.WATER) || isSeaPlant(ts)
                || (ts.canBeReplaced() && ts.getFluidState().isEmpty());
    }

    /** Slides the boat up to {@code want} blocks along its heading. Returns how far it actually went (0 = blocked). */
    private static int translate(Sailing s, Set<BlockPos> blocks, int want) {
        Direction d = s.dir;
        int k = 0;
        outer:
        for (int j = 1; j <= want; j++) {
            for (BlockPos p : blocks) {
                BlockPos t = p.relative(d, j);
                if (blocks.contains(t)) continue;
                if (!cellFree(s, t)) break outer;
            }
            k = j;
        }
        if (k == 0) return 0;

        final int dist = k;
        relocate(s, blocks,
                p -> p.relative(d, dist),
                Rotation.NONE,
                e -> new double[]{d.getStepX() * dist, d.getStepZ() * dist});
        return k;
    }

    /** Turns the whole boat a quarter turn around the Boat Base. Returns false if there is no room. */
    private static boolean rotateBoat(Sailing s, boolean clockwise) {
        Set<BlockPos> blocks = collect(s.level, s.base);
        if (blocks == null) return false;

        final int px = s.base.getX();
        final int pz = s.base.getZ();
        Function<BlockPos, BlockPos> mapper = p -> {
            int dx = p.getX() - px;
            int dz = p.getZ() - pz;
            return clockwise
                    ? new BlockPos(px - dz, p.getY(), pz + dx)
                    : new BlockPos(px + dz, p.getY(), pz - dx);
        };

        for (BlockPos p : blocks) {
            BlockPos t = mapper.apply(p);
            if (blocks.contains(t)) continue;
            if (!cellFree(s, t)) return false;
        }

        final double cx = px + 0.5;
        final double cz = pz + 0.5;
        relocate(s, blocks, mapper,
                clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90,
                e -> {
                    double rx = e.getX() - cx;
                    double rz = e.getZ() - cz;
                    return clockwise
                            ? new double[]{-rz - rx, rx - rz}
                            : new double[]{rz - rx, -rx - rz};
                });
        s.dir = clockwise ? s.dir.getClockWise() : s.dir.getCounterClockWise();
        return true;
    }

    /**
     * Moves every block to its mapped position (rotating block states if needed), carrying container contents,
     * riders and the helm seat along. Destinations must already have been checked.
     */
    private static void relocate(Sailing s, Set<BlockPos> blocks, Function<BlockPos, BlockPos> mapper,
                                 Rotation rotation, Function<Entity, double[]> entityMove) {
        ServerLevel level = s.level;

        Map<BlockPos, BlockPos> mapping = new HashMap<>();
        Set<BlockPos> moved = new HashSet<>();
        for (BlockPos p : blocks) {
            BlockPos t = mapper.apply(p).immutable();
            mapping.put(p, t);
            moved.add(t);
        }

        // 1. Find entities riding on the boat before it moves.
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

        // 2. Remember what we are about to push out of the way, so it can be put back later.
        s.displaced.keySet().retainAll(blocks);
        for (BlockPos t : moved) {
            if (!blocks.contains(t)) {
                s.displaced.put(t, level.getBlockState(t));
            }
        }

        // 3. Save blocks and container contents (block entities) before touching anything.
        Map<BlockPos, BlockState> old = new HashMap<>();
        Map<BlockPos, CompoundTag> blockEntityData = new HashMap<>();
        for (BlockPos p : blocks) {
            old.put(p, level.getBlockState(p));
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                blockEntityData.put(p, be.saveWithFullMetadata(level.registryAccess()));
            }
        }

        // 4. Clear the cells the boat is leaving, restoring the water (or kelp etc.) that was there before.
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

        // 5. Place everything at its new position, restoring container contents.
        for (Map.Entry<BlockPos, BlockState> e : old.entrySet()) {
            BlockPos target = mapping.get(e.getKey());
            BlockState state = rotation == Rotation.NONE ? e.getValue() : e.getValue().rotate(rotation);
            level.setBlock(target, state, FLAGS);
            CompoundTag tag = blockEntityData.get(e.getKey());
            if (tag != null) {
                BlockEntity restored = BlockEntity.loadStatic(target, state, tag, level.registryAccess());
                if (restored != null) {
                    level.setBlockEntity(restored);
                }
            }
        }
        s.base = mapping.get(s.base);
        s.known = moved;

        // 6. Carry the riders (and the helm seat, with its passenger) along.
        for (Entity e : riders) {
            double[] delta = entityMove.apply(e);
            e.teleportRelative(delta[0], 0, delta[1]);
        }
    }
}
