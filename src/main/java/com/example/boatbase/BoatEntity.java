package com.example.boatbase;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A boat that is sailing. While a boat sails, its blocks are taken out of the world and held here, and drawn
 * by {@link BoatEntityRenderer}; when it stops they are put back as real blocks ("dock and sail").
 *
 * Block positions are stored relative to the entity: block (dx,dy,dz) fills the cell whose minimum corner is
 * (x - 0.5 + dx, y + dy, z - 0.5 + dz), so the entity origin is the middle of the engine's cell.
 */
public class BoatEntity extends Entity {
    /** One block of the boat. {@code blockEntity} holds chest contents etc. (server side only, may be null). */
    public record Entry(int dx, int dy, int dz, BlockState state, CompoundTag blockEntity) {}

    /** Every boat the game client currently knows about (used for walking on deck). */
    public static final List<BoatEntity> CLIENT_BOATS = new ArrayList<>();

    // The block list is sent to clients as text (version + base64 of a block of ints).
    private static final EntityDataAccessor<String> DATA =
            SynchedEntityData.defineId(BoatEntity.class, EntityDataSerializers.STRING);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedAttachment, loggedPosition;

    private List<Entry> entries = List.of();
    private List<AABB> localBoxes = List.of();
    private AABB localBounds = new AABB(-0.5, 0, -0.5, 0.5, 1, 0.5);
    private double seatX, seatY, seatZ;     // helmsman's feet, relative to the entity origin
    private int waterTopY = 62;
    private int version;
    private int orphanTicks;

    // Client only: how far the boat moved during the last tick, so the local player can be carried along.
    public double tickDx, tickDz;
    public double rawStep;                         // how far the last network update moved the boat
    private double smoothX, smoothZ;               // the boat's smoothed position (what you see)
    private double prevSmoothX, prevSmoothZ;       // ...one tick earlier
    private double targetX, targetZ;               // the latest position the server told us about
    private int lerpSteps;
    private boolean haveLast;
    private static final int LERP_STEPS = 2;       // spread each update over this many ticks

    public BoatEntity(EntityType<? extends BoatEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        if (level.isClientSide()) {
            CLIENT_BOATS.add(this);
        }
    }

    /**
     * Client: how far back from the current position the boat should be drawn right now. The server only moves the
     * boat once per tick, so without this the boat would be drawn in 2-block jumps while you move smoothly.
     */
    public double renderX(float partialTick) {
        return haveLast ? prevSmoothX + (smoothX - prevSmoothX) * partialTick : getX();
    }
    public double renderZ(float partialTick) {
        return haveLast ? prevSmoothZ + (smoothZ - prevSmoothZ) * partialTick : getZ();
    }

    /**
     * The boat's position as the game client should treat it. The server's updates reach the client unevenly
     * (sometimes none for a tick, then two or three at once), so on the client the boat glides towards each
     * update instead of jumping. Everything the client does with the boat (drawing it, seating the helmsman,
     * walking on deck) uses this same smoothed position, so they always agree.
     */
    public double bx() { return (level().isClientSide() && haveLast) ? smoothX : getX(); }
    public double bz() { return (level().isClientSide() && haveLast) ? smoothZ : getZ(); }

    // ------------------------------------------------------------ data

    public List<Entry> getEntries() { return entries; }
    public List<AABB> getLocalBoxes() { return localBoxes; }
    public int getWaterTopY() { return waterTopY; }
    public void setWaterTopY(int y) { this.waterTopY = y; }
    public double getSeatX() { return seatX; }
    public double getSeatY() { return seatY; }
    public double getSeatZ() { return seatZ; }

    /** Server: replace the whole block list and send it to the clients. */
    public void setEntries(List<Entry> list, double sx, double sy, double sz) {
        this.seatX = sx;
        this.seatY = sy;
        this.seatZ = sz;
        setEntriesLocal(list);
        sync();
    }

    private void setEntriesLocal(List<Entry> list) {
        this.entries = list;
        List<AABB> boxes = new ArrayList<>();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Entry en : list) {
            minX = Math.min(minX, en.dx() - 0.5);  maxX = Math.max(maxX, en.dx() + 0.5);
            minY = Math.min(minY, en.dy());        maxY = Math.max(maxY, en.dy() + 1.0);
            minZ = Math.min(minZ, en.dz() - 0.5);  maxZ = Math.max(maxZ, en.dz() + 0.5);
            VoxelShape shape = en.state().getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            for (AABB b : shape.toAabbs()) {
                boxes.add(b.move(en.dx() - 0.5, en.dy(), en.dz() - 0.5));
            }
        }
        this.localBoxes = boxes;
        if (!list.isEmpty()) {
            this.localBounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private void sync() {
        int[] a = new int[3 + entries.size() * 2];
        a[0] = (int) Math.round(seatX * 1000);
        a[1] = (int) Math.round(seatY * 1000);
        a[2] = (int) Math.round(seatZ * 1000);
        int i = 3;
        for (Entry en : entries) {
            a[i++] = (en.dx() + 512) | ((en.dy() + 512) << 10) | ((en.dz() + 512) << 20);
            a[i++] = Block.getId(en.state());
        }
        ByteBuffer buf = ByteBuffer.allocate(a.length * 4);
        for (int v : a) buf.putInt(v);
        getEntityData().set(DATA, (++version) + ":" + Base64.getEncoder().encodeToString(buf.array()));
    }

    private void decode(String text) {
        int colon = text.indexOf(':');
        if (colon < 0) return;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            return;
        }
        IntBuffer ib = ByteBuffer.wrap(bytes).asIntBuffer();
        int[] a = new int[ib.remaining()];
        ib.get(a);
        if (a.length < 3) return;
        seatX = a[0] / 1000.0;
        seatY = a[1] / 1000.0;
        seatZ = a[2] / 1000.0;
        List<Entry> list = new ArrayList<>((a.length - 3) / 2);
        for (int i = 3; i + 1 < a.length; i += 2) {
            int packed = a[i];
            int dx = (packed & 1023) - 512;
            int dy = ((packed >> 10) & 1023) - 512;
            int dz = ((packed >> 20) & 1023) - 512;
            list.add(new Entry(dx, dy, dz, Block.stateById(a[i + 1]), null));
        }
        setEntriesLocal(list);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA, "");
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA.equals(accessor) && level().isClientSide()) {
            decode(getEntityData().get(DATA));
        }
    }

    // ------------------------------------------------------------ geometry (both sides)

    /** The boat's overall bounds in world coordinates, with a little room around it. */
    public AABB broadBounds() {
        return localBounds.move(bx(), getY(), bz()).inflate(1.5, 2.0, 1.5);
    }

    /** Is this entity standing on (or touching) the boat? */
    public boolean isOnTop(Entity other) {
        AABB probe = other.getBoundingBox().inflate(0.02, 0.0, 0.02).expandTowards(0, -0.25, 0);
        for (AABB b : localBoxes) {
            if (b.move(bx(), getY(), bz()).intersects(probe)) return true;
        }
        return false;
    }

    /** The block list after a quarter turn around the engine. */
    public List<Entry> rotatedEntries(boolean clockwise) {
        Rotation rotation = clockwise ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
        List<Entry> out = new ArrayList<>(entries.size());
        for (Entry en : entries) {
            int nx = clockwise ? -en.dz() : en.dz();
            int nz = clockwise ? en.dx() : -en.dx();
            out.add(new Entry(nx, en.dy(), nz, en.state().rotate(rotation), en.blockEntity()));
        }
        return out;
    }

    /** Server: switch to the rotated block list (and turn the helmsman's seat with it). */
    public void applyRotation(List<Entry> rotated, boolean clockwise) {
        double nsx = clockwise ? -seatZ : seatZ;
        double nsz = clockwise ? seatX : -seatX;
        seatX = nsx;
        seatZ = nsz;
        setEntriesLocal(rotated);
        sync();
    }

    // ------------------------------------------------------------ riding (the helmsman rides the boat itself)

    // No @Override on these two on purpose: if a signature differs in this Minecraft version, the helmsman just
    // sits at the default spot instead of the build failing.
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        if (!loggedAttachment) {
            loggedAttachment = true;
            LOGGER.info("[boatbase] getPassengerAttachmentPoint is being used");
        }
        return new Vec3(seatX, seatY + 0.6, seatZ);   // 0.6 = how far a player's vehicle attachment is above their feet
    }

    /** Put the rider's feet exactly on the helm spot (declared public so it overrides whatever access Minecraft uses). */
    public void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        if (!loggedPosition) {
            loggedPosition = true;
            LOGGER.info("[boatbase] positionRider is being used");
        }
        moveFunction.accept(passenger, bx() + seatX, getY() + seatY, bz() + seatZ);
    }

    public Vec3 helmSpot() {
        return new Vec3(bx() + seatX, getY() + seatY, bz() + seatZ);
    }

    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }

    // ------------------------------------------------------------ ticking

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) {
            double rawX = getX();
            double rawZ = getZ();
            if (!haveLast) {
                smoothX = prevSmoothX = targetX = rawX;
                smoothZ = prevSmoothZ = targetZ = rawZ;
                tickDx = tickDz = rawStep = 0;
                haveLast = true;
            } else {
                prevSmoothX = smoothX;
                prevSmoothZ = smoothZ;
                rawStep = 0;
                if (rawX != targetX || rawZ != targetZ) {
                    rawStep = Math.hypot(rawX - targetX, rawZ - targetZ);
                    targetX = rawX;
                    targetZ = rawZ;
                    lerpSteps = LERP_STEPS;
                }
                if (lerpSteps > 0) {
                    smoothX += (targetX - smoothX) / lerpSteps;
                    smoothZ += (targetZ - smoothZ) / lerpSteps;
                    lerpSteps--;
                }
                tickDx = smoothX - prevSmoothX;
                tickDz = smoothZ - prevSmoothZ;
            }
        } else if (BoatMover.isControlled(this)) {
            orphanTicks = 0;
        } else if (++orphanTicks > 40) {
            // Nobody is driving this boat (for example the world was just loaded): put it back as blocks.
            if (!BoatMover.dockOrphan(this)) {
                orphanTicks = 0;
            }
        }
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    // ------------------------------------------------------------ saving

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("WaterTop", waterTopY);
        output.putInt("SeatX", (int) Math.round(seatX * 1000));
        output.putInt("SeatY", (int) Math.round(seatY * 1000));
        output.putInt("SeatZ", (int) Math.round(seatZ * 1000));
        List<Integer> offsets = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<CompoundTag> tags = new ArrayList<>();
        for (Entry en : entries) {
            offsets.add(en.dx());
            offsets.add(en.dy());
            offsets.add(en.dz());
            states.add(en.state());
            tags.add(en.blockEntity() == null ? new CompoundTag() : en.blockEntity());
        }
        output.store("Offsets", Codec.INT.listOf(), offsets);
        output.store("States", BlockState.CODEC.listOf(), states);
        output.store("BlockEntities", CompoundTag.CODEC.listOf(), tags);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        waterTopY = input.getIntOr("WaterTop", 62);
        seatX = input.getIntOr("SeatX", 0) / 1000.0;
        seatY = input.getIntOr("SeatY", 0) / 1000.0;
        seatZ = input.getIntOr("SeatZ", 0) / 1000.0;
        List<Integer> offsets = input.read("Offsets", Codec.INT.listOf()).orElse(List.of());
        List<BlockState> states = input.read("States", BlockState.CODEC.listOf()).orElse(List.of());
        List<CompoundTag> tags = input.read("BlockEntities", CompoundTag.CODEC.listOf()).orElse(List.of());
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < states.size() && i * 3 + 2 < offsets.size(); i++) {
            CompoundTag t = i < tags.size() ? tags.get(i) : null;
            list.add(new Entry(offsets.get(i * 3), offsets.get(i * 3 + 1), offsets.get(i * 3 + 2),
                    states.get(i), (t == null || t.isEmpty()) ? null : t));
        }
        setEntriesLocal(list);
        sync();
    }
}
