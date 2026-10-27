package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
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
 * Moves and turns every block connected to a Boat Engine (the Boat Base block). Fewer blocks = faster boat.
 *
 * Speed (blocks/second) = BASE_SPEED * engines / (1 + (blockCount - 1) / BLOCKS_FOR_HALF_SPEED), capped at MAX_SPEED
 *   (an "engine" is a Boat Engine block; every one on the boat adds thrust)
 *   1 engine:  1 block -> 40 b/s,  21 blocks -> 20 b/s,  100 blocks -> ~7 b/s,  400 blocks -> ~2 b/s
 *   3 engines: 21 blocks -> 60 b/s,  100 blocks -> ~20 b/s
 *
 * The world only changes every UPDATE_INTERVAL_TICKS ticks (in a jump of up to MAX_BLOCKS_PER_UPDATE blocks).
 * Changing blocks every single tick makes the game client re-draw the boat's chunk so often that the boat
 * barely shows up; fewer, bigger updates let the client keep up. If the boat still flickers, lower BASE_SPEED
 * or raise UPDATE_INTERVAL_TICKS.
 *
 * Steering: stand at a Ship's Wheel.  Hold W = accelerate, hold S = brake, let go = coast to a stop,
 * A / D = turn the boat left / right in 45-degree steps (so it can sail diagonally), Sneak = leave the helm.
 */
public final class BoatMover {
    private static final int MAX_BLOCKS = 4096;
    private static final double BASE_SPEED = 40.0;           // blocks/second for a lone base
    private static final double BLOCKS_FOR_HALF_SPEED = 20.0;
    private static final double MIN_SPEED = 0.5;
    private static final int UPDATE_INTERVAL_TICKS = 2;      // the world changes this often (2 = 10 times/second)
    private static final int MAX_BLOCKS_PER_UPDATE = 8;      // 8 blocks every 2 ticks = 80 blocks/second top speed
    private static final double MAX_SPEED = MAX_BLOCKS_PER_UPDATE * 20.0 / UPDATE_INTERVAL_TICKS;
    private static final double ACCEL_SECONDS = 3.0;         // holding W: seconds from standstill to top speed
    private static final double COAST_SECONDS = 6.0;         // letting go: seconds to drift from top speed to a stop
    private static final double BRAKE_SECONDS = 1.5;         // holding S: seconds to brake from top speed to a stop
    /** true = "dock and sail": the boat becomes a smooth moving entity while it sails and real blocks when stopped. */
    private static final boolean ENTITY_MODE = true;
    private static final int MAX_ENTITY_BLOCKS = 1500;       // biggest boat that can sail as an entity
    private static final int DOCK_DELAY_TICKS = 20;          // how long the boat must sit still before it docks
    private static final int[][] DOCK_TRIES = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double SEAT_DROP = 1.375;           // helmsman's feet stand on the floor; raise/lower by changing this
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // Eight headings: 0 north, 1 north-east, 2 east, 3 south-east, 4 south, 5 south-west, 6 west, 7 north-west.
    private static final Direction[] CARDINALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int[] HEADING_X = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] HEADING_Z = {-1, -1, 0, 1, 1, 1, 0, -1};
    private static final String[] HEADING_NAMES =
            {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final List<Sailing> ACTIVE = new ArrayList<>();

    private static final class Sailing {
        final ServerLevel level;
        BlockPos base;
        final int waterTopY;           // highest water block of the sea the boat floats on
        Direction dir;                 // which way the boat's blocks are facing (always north/east/south/west)
        int heading;                   // which of the 8 compass headings it is sailing along
        int pendingHeading;            // the heading it has been asked to turn to
        double speed = 0;              // current speed in blocks/second
        boolean cruise = false;        // set by clicking the engine: keep full throttle with nobody at the helm
        boolean wHeld, sHeld;          // throttle / brake keys held by the helmsman
        int lastCount = 1;
        int lastEngines = 1;
        Set<BlockPos> known = new HashSet<>();   // blocks seen last time, used to find another engine if one is lost
        double progress = 0;
        int sinceUpdate = 0;
        /** What was in each cell before the boat moved into it, so it can be put back. */
        final Map<BlockPos, BlockState> displaced = new HashMap<>();
        Entity seat;                   // invisible seat at the helm while docked (null if nobody is steering)
        boolean helmActive;            // somebody has taken the helm
        BoatEntity entity;             // the moving boat while it is sailing (null while docked)
        int idleTicks;                 // how long a sailing boat has been standing still
        boolean prevLeft, prevRight;

        Sailing(ServerLevel level, BlockPos base, int heading, int waterTopY) {
            this.level = level;
            this.base = base;
            this.heading = heading;
            this.pendingHeading = heading;
            this.dir = CARDINALS[heading / 2];
            this.waterTopY = waterTopY;
            this.known.add(base);
        }
    }

    private BoatMover() {}

    // ---------------------------------------------------------------- public API

    public static boolean isMoving(ServerLevel level, BlockPos base) {
        Sailing s = findBoat(level, base);
        return s != null && (s.speed > 0 || s.cruise);
    }

    /** Start sailing. A boat that is already known will turn to {@code heading} first. */
    public static void start(ServerLevel level, BlockPos base, int heading) {
        Sailing s = getOrCreate(level, base, heading);
        s.pendingHeading = heading;
        s.cruise = true;
    }

    public static void stop(ServerLevel level, BlockPos base) {
        Sailing s = findBoat(level, base);
        if (s != null) {
            s.cruise = false;
            s.speed = 0;
        }
    }

    /** Turn the boat towards {@code heading}. */
    public static void setHeading(ServerLevel level, BlockPos base, int heading) {
        Sailing s = findBoat(level, base);
        if (s != null) s.pendingHeading = heading;
    }

    /** The nearest of the 8 headings to a player's yaw (0 = south, 90 = west). */
    public static int headingFromYaw(float yaw) {
        return Math.floorMod(Math.round((yaw + 180f) / 45f), 8);
    }

    public static String headingName(int heading) {
        return HEADING_NAMES[Math.floorMod(heading, 8)];
    }

    private static int headingIndex(Direction d) {
        for (int i = 0; i < CARDINALS.length; i++) {
            if (CARDINALS[i] == d) return i * 2;
        }
        return 0;
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
            tell(player, "Connect the wheel to a Boat Engine");
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

        Sailing s = getOrCreate(level, base, headingIndex(player.getDirection()));
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
        s.helmActive = true;
        s.prevLeft = false;
        s.prevRight = false;
        tell(player, "At the helm: hold W to go, hold S to brake, A/D turn, Sneak to leave. Heading " + headingName(s.pendingHeading));
    }

    /** Called when the server stops, so nothing is left over between worlds. */
    public static void shutdown() {
        for (Sailing s : ACTIVE) {
            if (s.entity != null && !s.entity.isRemoved()) {
                dock(s);
            }
            if (s.seat != null) s.seat.discard();
        }
        ACTIVE.clear();
    }

    // ---------------------------------------------------------------- dock and sail

    private static Entity helmRider(Sailing s) {
        if (s.entity != null && !s.entity.isRemoved()) return s.entity.getFirstPassenger();
        if (s.seat != null && s.seat.isAlive()) return s.seat.getFirstPassenger();
        return null;
    }

    private static void endHelm(Sailing s) {
        if (s.seat != null) {
            s.seat.discard();
            s.seat = null;
        }
        s.helmActive = false;
        s.wHeld = false;
        s.sHeld = false;
    }

    public static boolean isControlled(BoatEntity e) {
        for (Sailing s : ACTIVE) {
            if (s.entity == e) return true;
        }
        return false;
    }

    /** A boat entity nobody is driving (e.g. after loading a world): turn it back into blocks. */
    public static boolean dockOrphan(BoatEntity e) {
        if (!(e.level() instanceof ServerLevel level)) return false;
        return dockEntity(level, e) != null;
    }

    private static Entity createSeat(ServerLevel level, double x, double yFeet, double z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(
                Identifier.fromNamespaceAndPath("minecraft", "armor_stand"));
        Entity seat = type == null ? null : type.create(level, EntitySpawnReason.COMMAND);
        if (seat == null) return null;
        seat.setPos(x, yFeet - SEAT_DROP, z);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        level.addFreshEntity(seat);
        return seat;
    }

    /** Assemble / move / dock the boat, once per tick. */
    private static void entityTick(Sailing s) {
        if (s.entity != null && s.entity.isRemoved()) {
            s.entity = null;               // it is gone (for example removed by a command)
            s.speed = 0;
            s.cruise = false;
            return;
        }

        boolean sailing = s.speed > 0;
        if (s.entity == null) {
            if (!sailing) return;
            Set<BlockPos> blocks = collect(s.level, s.base);
            if (blocks == null || blocks.size() > MAX_ENTITY_BLOCKS) {
                notifyNearby(s, "Boat too big to sail (max " + MAX_ENTITY_BLOCKS + " blocks)");
                s.speed = 0;
                s.cruise = false;
                return;
            }
            s.lastCount = blocks.size();
            int engines = 0;
            for (BlockPos p : blocks) {
                if (s.level.getBlockState(p).is(BoatBaseMod.BOAT_BASE)) engines++;
            }
            s.lastEngines = Math.max(1, engines);
            if (!assemble(s, blocks)) {
                notifyNearby(s, "Boat too spread out to sail");
                s.speed = 0;
                s.cruise = false;
                return;
            }
        }

        if (sailing) {
            advanceEntity(s);
            s.idleTicks = 0;
        } else if (s.wHeld || s.cruise) {
            s.idleTicks = 0;
        } else if (++s.idleTicks >= DOCK_DELAY_TICKS) {
            if (!dock(s)) {
                notifyNearby(s, "No room to dock here - steer to open water");
                s.idleTicks = 0;
            }
        }
    }

    /** Takes the boat's blocks out of the world and turns them into a moving entity. */
    private static boolean assemble(Sailing s, Set<BlockPos> blocks) {
        ServerLevel level = s.level;
        BlockPos origin = s.base;
        List<BoatEntity.Entry> entries = new ArrayList<>(blocks.size());
        for (BlockPos p : blocks) {
            int dx = p.getX() - origin.getX();
            int dy = p.getY() - origin.getY();
            int dz = p.getZ() - origin.getZ();
            if (Math.abs(dx) > 500 || Math.abs(dy) > 500 || Math.abs(dz) > 500) return false;
            BlockEntity be = level.getBlockEntity(p);
            CompoundTag tag = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
            entries.add(new BoatEntity.Entry(dx, dy, dz, level.getBlockState(p), tag));
        }

        BoatEntity e = new BoatEntity(BoatBaseMod.BOAT_ENTITY, level);
        e.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        e.setWaterTopY(s.waterTopY);

        Entity rider = (s.seat != null && s.seat.isAlive()) ? s.seat.getFirstPassenger() : null;
        double sx = 0, sy = 0, sz = 0;
        if (rider != null) {
            sx = s.seat.getX() - e.getX();
            sy = s.seat.getY() + SEAT_DROP - e.getY();
            sz = s.seat.getZ() - e.getZ();
        }
        e.setEntries(entries, sx, sy, sz);

        // Take the blocks out of the world (containers first, so they do not spill).
        for (BlockPos p : blocks) {
            if (level.getBlockEntity(p) != null) level.removeBlockEntity(p);
        }
        for (BlockPos p : blocks) {
            BlockState restore = s.displaced.remove(p);
            if (restore == null) {
                restore = p.getY() <= s.waterTopY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            }
            level.setBlock(p, restore, FLAGS);
        }
        level.addFreshEntity(e);

        // The helmsman now rides the boat itself instead of the invisible seat.
        if (rider != null) {
            rider.stopRiding();
            s.seat.discard();
            s.seat = null;
            rider.startRiding(e);
        }
        s.entity = e;
        s.idleTicks = 0;
        LOGGER.info("[boatbase] boat assembled: {} blocks, {} engine(s)", entries.size(), s.lastEngines);
        return true;
    }

    /** Moves the sailing boat along its heading, stopping it if it would hit something. */
    private static void advanceEntity(Sailing s) {
        BoatEntity e = s.entity;
        double len = s.speed / 20.0;
        double norm = (s.heading % 2 == 1) ? Math.sqrt(0.5) : 1.0;
        double vx = HEADING_X[s.heading] * norm * len;
        double vz = HEADING_Z[s.heading] * norm * len;
        int baseY = (int) Math.round(e.getY());

        // Check the path in small steps so the boat cannot jump over a thin obstacle.
        int sub = Math.max(1, (int) Math.ceil(len / 0.4));
        int okSteps = 0;
        for (int i = 1; i <= sub; i++) {
            if (!entriesFit(s.level, e.getWaterTopY(), e.getEntries(),
                    e.getX() + vx * i / sub, e.getZ() + vz * i / sub, baseY)) {
                break;
            }
            okSteps = i;
        }
        if (okSteps > 0) {
            double dx = vx * okSteps / sub;
            double dz = vz * okSteps / sub;
            // Players carry themselves (on their own screen); items and mobs are carried here.
            for (Entity other : onDeck(s.level, e, s)) {
                if (!(other instanceof ServerPlayer)) other.teleportRelative(dx, 0, dz);
            }
            e.setPos(e.getX() + dx, e.getY(), e.getZ() + dz);
        }
        if (okSteps < sub) {
            notifyNearby(s, "Boat blocked");
            s.speed = 0;
            s.cruise = false;
        }
    }

    private static List<Entity> onDeck(ServerLevel level, BoatEntity boat, Sailing s) {
        List<Entity> out = new ArrayList<>();
        for (Entity other : level.getEntities((Entity) null, boat.broadBounds())) {
            if (other == boat || other == s.seat || other.isPassenger()) continue;
            if (boat.isOnTop(other)) out.add(other);
        }
        return out;
    }

    /** Quarter turn of the sailing boat around its engine. */
    private static boolean rotateEntity(Sailing s, boolean clockwise) {
        BoatEntity e = s.entity;
        List<BoatEntity.Entry> rotated = e.rotatedEntries(clockwise);
        if (!entriesFit(s.level, e.getWaterTopY(), rotated, e.getX(), e.getZ(), (int) Math.round(e.getY()))) {
            return false;
        }
        double cx = e.getX();
        double cz = e.getZ();
        for (Entity other : onDeck(s.level, e, s)) {
            double rx = other.getX() - cx;
            double rz = other.getZ() - cz;
            double dx = clockwise ? -rz - rx : rz - rx;
            double dz = clockwise ? rx - rz : -rx - rz;
            other.teleportRelative(dx, 0, dz);
        }
        e.applyRotation(rotated, clockwise);
        s.dir = clockwise ? s.dir.getClockWise() : s.dir.getCounterClockWise();
        return true;
    }

    /** Puts a sailing boat back as real blocks at its current spot. Returns false if there is no room. */
    private static boolean dock(Sailing s) {
        BoatEntity e = s.entity;
        if (e == null) return true;
        Entity rider = e.getFirstPassenger();
        double sx = e.getSeatX();
        double sy = e.getSeatY();
        double sz = e.getSeatZ();

        BlockPos base = dockEntity(s.level, e);
        if (base == null) return false;

        LOGGER.info("[boatbase] boat docked at {}", base);
        s.entity = null;
        s.base = base;
        s.known = new HashSet<>();
        s.known.add(base);
        s.displaced.clear();
        s.progress = 0;
        s.idleTicks = 0;

        s.helmActive = false;
        if (rider != null && rider.isAlive()) {
            Entity stand = createSeat(s.level, base.getX() + 0.5 + sx, base.getY() + sy, base.getZ() + 0.5 + sz);
            if (stand != null) {
                rider.startRiding(stand);
                s.seat = stand;
                s.helmActive = true;
            }
        }
        return true;
    }

    /** Places the boat's blocks back into the world (nudging to a free spot if needed) and removes the entity. */
    private static BlockPos dockEntity(ServerLevel level, BoatEntity e) {
        int by = (int) Math.round(e.getY());
        int bx = Mth.floor(e.getX());
        int bz = Mth.floor(e.getZ());
        BlockPos chosen = null;
        for (int[] t : DOCK_TRIES) {
            BlockPos b = new BlockPos(bx + t[0], by, bz + t[1]);
            if (entriesFit(level, e.getWaterTopY(), e.getEntries(), b.getX() + 0.5, b.getZ() + 0.5, by)) {
                chosen = b;
                break;
            }
        }
        if (chosen == null) return null;

        // Anything standing on deck moves with the small snap onto the block grid.
        double dx = chosen.getX() + 0.5 - e.getX();
        double dz = chosen.getZ() + 0.5 - e.getZ();
        for (Entity other : level.getEntities((Entity) null, e.broadBounds())) {
            if (other == e || other.isPassenger()) continue;
            if (e.isOnTop(other)) other.teleportRelative(dx, 0, dz);
        }

        for (BoatEntity.Entry en : e.getEntries()) {
            BlockPos cell = chosen.offset(en.dx(), en.dy(), en.dz());
            level.setBlock(cell, en.state(), FLAGS);
            if (en.blockEntity() != null) {
                BlockEntity restored = BlockEntity.loadStatic(cell, en.state(), en.blockEntity(), level.registryAccess());
                if (restored != null) level.setBlockEntity(restored);
            }
        }
        e.discard();
        return chosen;
    }

    private static long columnKey(int dx, int dz) {
        return ((long) dx << 32) ^ (dz & 0xffffffffL);
    }

    /**
     * Would the boat fit with its engine cell centred at (cx, baseY, cz)? Every cell any block overlaps must be free,
     * and the lowest block of each column must be over water (so a boat cannot sail onto the beach).
     */
    private static boolean entriesFit(ServerLevel level, int waterTopY, List<BoatEntity.Entry> entries,
                                      double cx, double cz, int baseY) {
        Map<Long, Integer> lowest = new HashMap<>();
        for (BoatEntity.Entry en : entries) {
            lowest.merge(columnKey(en.dx(), en.dz()), en.dy(), Math::min);
        }
        Set<Long> seen = new HashSet<>();
        Set<Long> seenBelow = new HashSet<>();
        for (BoatEntity.Entry en : entries) {
            int y = baseY + en.dy();
            double x0 = cx - 0.5 + en.dx();
            double z0 = cz - 0.5 + en.dz();
            int minX = Mth.floor(x0 + 1.0E-3);
            int maxX = Mth.floor(x0 + 1.0 - 1.0E-3);
            int minZ = Mth.floor(z0 + 1.0E-3);
            int maxZ = Mth.floor(z0 + 1.0 - 1.0E-3);
            boolean bottom = en.dy() == lowest.get(columnKey(en.dx(), en.dz()));
            for (int ix = minX; ix <= maxX; ix++) {
                for (int iz = minZ; iz <= maxZ; iz++) {
                    BlockPos cell = new BlockPos(ix, y, iz);
                    if (seen.add(cell.asLong()) && !cellFreeFor(level, waterTopY, cell)) return false;
                    if (bottom) {
                        BlockPos below = cell.below();
                        if (below.getY() <= waterTopY && seenBelow.add(below.asLong())
                                && !isWaterLike(level.getBlockState(below))) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- ticking

    public static void tick() {
        Iterator<Sailing> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Sailing s = it.next();

            if (s.entity == null && !s.level.getBlockState(s.base).is(BoatBaseMod.BOAT_BASE)) {
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

            // Helm: read steering input from whoever is at the wheel.
            if (s.helmActive) {
                Entity rider = helmRider(s);
                if (rider == null) {
                    endHelm(s);
                } else if (rider instanceof ServerPlayer p) {
                    steer(s, p);
                }
            }

            // Turn in 45-degree steps, one per tick. When the heading reaches north, east, south or west the whole
            // boat swings round to face it; on the diagonals it keeps its last facing and sails at an angle.
            if (s.pendingHeading != s.heading) {
                int diff = Math.floorMod(s.pendingHeading - s.heading, 8);
                int step = diff <= 4 ? 1 : -1;
                int newHeading = Math.floorMod(s.heading + step, 8);
                boolean ok = true;
                if (newHeading % 2 == 0) {
                    Direction target = CARDINALS[newHeading / 2];
                    if (target != s.dir) {
                        boolean cw = target != s.dir.getCounterClockWise();
                        ok = s.entity != null ? rotateEntity(s, cw) : rotateBoat(s, cw);
                    }
                }
                if (ok) {
                    s.heading = newHeading;
                    // Like a minecart on a bend: the helmsman's camera turns with the boat.
                    if (helmRider(s) instanceof ServerPlayer helmsman) {
                        turnCamera(helmsman, step * 45f);
                    }
                } else {
                    notifyNearby(s, "No room to turn");
                    s.pendingHeading = s.heading;
                }
            }

            // Throttle: holding W speeds the boat up, holding S brakes, letting go lets it coast to a halt.
            double max = speedFor(s.lastCount, s.lastEngines);
            if (s.sHeld) {
                s.speed -= max / BRAKE_SECONDS / 20.0;
            } else if (s.cruise || s.wHeld) {
                s.speed += max / ACCEL_SECONDS / 20.0;
            } else {
                s.speed -= max / COAST_SECONDS / 20.0;
            }
            s.speed = Math.max(0, Math.min(s.speed, max));

            if (ENTITY_MODE) {
                entityTick(s);
                if (s.entity == null && s.speed <= 0 && !s.helmActive && !s.cruise && s.pendingHeading == s.heading) {
                    it.remove();
                }
                continue;
            }

            if (s.speed <= 0) {
                s.progress = 0;
                if (s.seat == null && !s.cruise && s.pendingHeading == s.heading) it.remove();
                continue;
            }

            // Sail: only change the world every UPDATE_INTERVAL_TICKS ticks, in one bigger jump.
            double stepCost = (s.heading % 2 == 1) ? Math.sqrt(2) : 1.0;   // a diagonal step covers 1.41 blocks
            s.progress = Math.min(s.progress + s.speed / 20.0, MAX_BLOCKS_PER_UPDATE);
            if (++s.sinceUpdate < UPDATE_INTERVAL_TICKS) continue;
            s.sinceUpdate = 0;
            int want = (int) (s.progress / stepCost);
            if (want < 1) continue;

            Set<BlockPos> blocks = collect(s.level, s.base);
            if (blocks == null) {
                notifyNearby(s, "Boat too big (max " + MAX_BLOCKS + " blocks)");
                s.speed = 0;
                s.cruise = false;
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
                s.speed = 0;
                s.cruise = false;
            } else {
                s.progress -= moved * stepCost;
            }
        }
    }

    private static void steer(Sailing s, ServerPlayer p) {
        Input in = p.getLastClientInput();
        s.wHeld = in.forward();
        s.sHeld = in.backward();
        if (s.sHeld) s.cruise = false;
        if (in.left() && !s.prevLeft) {
            s.pendingHeading = Math.floorMod(s.pendingHeading - 1, 8);
            tell(p, "Turning to " + headingName(s.pendingHeading));
        }
        if (in.right() && !s.prevRight) {
            s.pendingHeading = Math.floorMod(s.pendingHeading + 1, 8);
            tell(p, "Turning to " + headingName(s.pendingHeading));
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

    private static Sailing getOrCreate(ServerLevel level, BlockPos base, int heading) {
        Sailing s = findBoat(level, base);
        if (s == null) {
            s = new Sailing(level, base.immutable(), heading, computeWaterTop(level, base));
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
        return cellFreeFor(s.level, s.waterTopY, t);
    }

    private static boolean cellFreeFor(ServerLevel level, int waterTopY, BlockPos t) {
        if (!level.isInWorldBounds(t)) return false;
        BlockState ts = level.getBlockState(t);
        if (t.getY() <= waterTopY) {
            return isWaterLike(ts);                                   // hull must stay in water
        }
        return ts.isAir() || ts.is(Blocks.WATER) || isSeaPlant(ts)
                || (ts.canBeReplaced() && ts.getFluidState().isEmpty());
    }

    /** Slides the boat up to {@code want} steps along its heading. Returns how many steps it made (0 = blocked). */
    private static int translate(Sailing s, Set<BlockPos> blocks, int want) {
        final int sx = HEADING_X[s.heading];
        final int sz = HEADING_Z[s.heading];
        int k = 0;
        outer:
        for (int j = 1; j <= want; j++) {
            int ox = sx * (j - 1);
            int oz = sz * (j - 1);
            for (BlockPos p : blocks) {
                // The cell ahead; on a diagonal also the two cells beside it, so the boat cannot squeeze past a corner.
                BlockPos ahead = p.offset(ox + sx, 0, oz + sz);
                if (!blocks.contains(ahead) && !cellFree(s, ahead)) break outer;
                if (sx != 0 && sz != 0) {
                    BlockPos sideX = p.offset(ox + sx, 0, oz);
                    BlockPos sideZ = p.offset(ox, 0, oz + sz);
                    if (!blocks.contains(sideX) && !cellFree(s, sideX)) break outer;
                    if (!blocks.contains(sideZ) && !cellFree(s, sideZ)) break outer;
                }
            }
            k = j;
        }
        if (k == 0) return 0;

        final int dist = k;
        relocate(s, blocks,
                p -> p.offset(sx * dist, 0, sz * dist),
                Rotation.NONE,
                e -> new double[]{sx * dist, sz * dist});
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

    /** Turns a player's view by {@code deltaYaw} degrees (positive = clockwise seen from above). */
    private static void turnCamera(ServerPlayer player, float deltaYaw) {
        player.setYRot(player.getYRot() + deltaYaw);
        player.connection.send(new ClientboundPlayerRotationPacket(deltaYaw, true, 0f, true));
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
