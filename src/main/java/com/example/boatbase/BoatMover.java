package com.example.boatbase;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
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
 *   1 block   -> 40 b/s
 *   21 blocks -> 20 b/s
 *   100 blocks -> ~7 b/s
 *   400 blocks -> ~2 b/s
 * Tweak the constants below to taste.
 */
public final class BoatMover {
    private static final int MAX_BLOCKS = 4096;
    private static final double BASE_SPEED = 40.0;          // blocks/second for a lone base
    private static final double BLOCKS_FOR_HALF_SPEED = 20.0;
    private static final double MIN_SPEED = 0.5;
    private static final int MAX_STEPS_PER_TICK = 2;        // 2 blocks/tick = 40 blocks/second
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final List<Sailing> ACTIVE = new ArrayList<>();

    private static final class Sailing {
        final ServerLevel level;
        BlockPos base;
        final int baseY;
        Direction dir;
        int lastCount = 1;
        double progress = 0;

        Sailing(ServerLevel level, BlockPos base, Direction dir) {
            this.level = level;
            this.base = base;
            this.baseY = base.getY();
            this.dir = dir;
        }
    }

    private BoatMover() {}

    public static boolean isSailing(ServerLevel level, BlockPos base) {
        return find(level, base) != null;
    }

    public static void start(ServerLevel level, BlockPos base, Direction dir) {
        if (find(level, base) == null) {
            ACTIVE.add(new Sailing(level, base.immutable(), dir));
        }
    }

    public static void stop(ServerLevel level, BlockPos base) {
        ACTIVE.removeIf(s -> s.level == level && s.base.equals(base));
    }

    public static void setDirection(ServerLevel level, BlockPos base, Direction dir) {
        Sailing s = find(level, base);
        if (s != null) s.dir = dir;
    }

    private static Sailing find(ServerLevel level, BlockPos base) {
        for (Sailing s : ACTIVE) {
            if (s.level == level && s.base.equals(base)) return s;
        }
        return null;
    }

    public static double speedFor(int blockCount) {
        double speed = BASE_SPEED / (1.0 + (Math.max(1, blockCount) - 1) / BLOCKS_FOR_HALF_SPEED);
        return Math.max(MIN_SPEED, speed);
    }

    public static void tick() {
        Iterator<Sailing> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Sailing s = it.next();
            if (!s.level.getBlockState(s.base).is(BoatBaseMod.BOAT_BASE)) {
                it.remove();
                continue;
            }

            s.progress = Math.min(s.progress + speedFor(s.lastCount) / 20.0, MAX_STEPS_PER_TICK);
            int steps = (int) s.progress;
            if (steps < 1) continue;

            Set<BlockPos> blocks = collect(s);
            if (blocks == null) {
                notifyNearby(s, "Boat too big (max " + MAX_BLOCKS + " blocks)");
                it.remove();
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
                it.remove();
            }
        }
    }

    private static void notifyNearby(Sailing s, String msg) {
        for (ServerPlayer p : s.level.players()) {
            if (p.distanceToSqr(s.base.getX(), s.base.getY(), s.base.getZ()) < 48 * 48) {
                p.displayClientMessage(Component.literal(msg), true);
            }
        }
    }

    private static boolean isTerrain(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.BEDROCK);
    }

    /** Flood-fills every solid block attached to the base. Returns null if the boat is too big. */
    private static Set<BlockPos> collect(Sailing s) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(s.base);
        queue.add(s.base);
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = cur.relative(d);
                if (seen.contains(n)) continue;
                BlockState st = s.level.getBlockState(n);
                if (st.isAir() || st.getBlock() instanceof LiquidBlock) continue;
                if (isTerrain(st)) continue;
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

        // 1. Check every destination is free.
        for (BlockPos pos : blocks) {
            BlockPos t = pos.relative(d);
            if (blocks.contains(t)) continue;
            if (!level.isInWorldBounds(t)) return null;
            BlockState ts = level.getBlockState(t);
            if (t.getY() < s.baseY) {
                if (!ts.is(Blocks.WATER)) return null;          // hull must stay in water
            } else {
                if (!(ts.isAir() || ts.is(Blocks.WATER) || (ts.canBeReplaced() && ts.getFluidState().isEmpty()))) {
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
        AABB box = new AABB(minX - 1, minY, minZ - 1, maxX + 2, maxY + 4, maxZ + 2);
        List<Entity> riders = new ArrayList<>();
        for (Entity e : level.getEntities((Entity) null, box)) {
            BlockPos feet = e.blockPosition();
            if (blocks.contains(feet.below()) || blocks.contains(feet) || blocks.contains(feet.below(2))) {
                riders.add(e);
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

        // 4. Clear the old positions. Removing the block entity first stops chests from spilling their items.
        for (BlockPos p : blocks) {
            if (blockEntityData.containsKey(p)) {
                level.removeBlockEntity(p);
            }
            BlockState vacated = p.getY() < s.baseY ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            level.setBlock(p, vacated, FLAGS);
        }

        // 5. Place everything one block over, restoring container contents.
        Set<BlockPos> moved = new HashSet<>();
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
            moved.add(target.immutable());
        }
        s.base = s.base.relative(d);

        // 6. Carry the riders along.
        for (Entity e : riders) {
            e.teleportRelative(d.getStepX(), 0, d.getStepZ());
        }
        return moved;
    }
}
