package com.simibubi.create.content.contraptions;

import static com.simibubi.create.foundation.utility.AngleHelper.angleLerp;
import static com.simibubi.create.foundation.utility.AngleHelper.wrapAngle180;

import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.contraptions.bearing.StabilizedContraption;
import com.simibubi.create.content.contraptions.minecart.MinecartSim2020;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity.CartMovementMode;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.NBTHelper;
import com.simibubi.create.foundation.utility.VecHelper;

import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartFurnace;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.LazyOptional;

/**
 * Ex: Minecarts, Couplings <br>
 * Oriented Contraption Entities can rotate freely around two axes
 * simultaneously.
 */
public class OrientedContraptionEntity extends AbstractContraptionEntity {

    private static final Ingredient FUEL_ITEMS = Ingredient.of(Items.COAL, Items.CHARCOAL);

    private static final EntityDataAccessor<Optional<UUID>> COUPLING =
        SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Direction> INITIAL_ORIENTATION =
        SynchedEntityData.defineId(OrientedContraptionEntity.class, EntityDataSerializers.DIRECTION);

    protected Vec3 motionBeforeStall;
    protected boolean forceAngle;
    private boolean isSerializingFurnaceCart;
    private boolean attachedExtraInventories;
    private boolean manuallyPlaced;

    public float prevYaw;
    public float yaw;
    public float targetYaw;

    public float prevPitch;
    public float pitch;

    public int nonDamageTicks;

    public OrientedContraptionEntity(EntityType<?> type, Level world) {
        super(type, world);
        motionBeforeStall = Vec3.ZERO;
        attachedExtraInventories = false;
        isSerializingFurnaceCart = false;
        nonDamageTicks = 10;
    }

    public static OrientedContraptionEntity create(Level world, Contraption contraption, Direction initialOrientation) {
        OrientedContraptionEntity entity =
            new OrientedContraptionEntity(AllEntityTypes.ORIENTED_CONTRAPTION.get(), world);
        entity.setContraption(contraption);
        entity.setInitialOrientation(initialOrientation);
        entity.startAtInitialYaw();
        return entity;
    }

    public static OrientedContraptionEntity createAtYaw(Level world, Contraption contraption,
        Direction initialOrientation, float initialYaw) {
        OrientedContraptionEntity entity = create(world, contraption, initialOrientation);
        entity.startAtYaw(initialYaw);
        entity.manuallyPlaced = true;
        return entity;
    }

    public void setInitialOrientation(Direction direction) {
        entityData.set(INITIAL_ORIENTATION, direction);
    }

    public Direction getInitialOrientation() {
        return entityData.get(INITIAL_ORIENTATION);
    }

    @Override
    public float getYawOffset() {
        return getInitialYaw();
    }

    public float getInitialYaw() {
        return (isInitialOrientationPresent() ? entityData.get(INITIAL_ORIENTATION) : Direction.SOUTH).toYRot();
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(COUPLING, Optional.empty());
        entityData.define(INITIAL_ORIENTATION, Direction.UP);
    }

    @Override
    public ContraptionRotationState getRotationState() {
        ContraptionRotationState crs = new ContraptionRotationState();

        float yawOffset = getYawOffset();
        crs.zRotation = pitch;
        crs.yRotation = -yaw + yawOffset;

        if (pitch != 0 && yaw != 0) {
            crs.secondYRotation = -yaw;
            crs.yRotation = yawOffset;
        }

        return crs;
    }

    @Override
    public void stopRiding() {
        if (!level().isClientSide && isAlive())
            disassemble();
        super.stopRiding();
    }

    @Override
    protected void readAdditional(CompoundTag compound, boolean spawnPacket) {
        super.readAdditional(compound, spawnPacket);

        if (compound.contains("InitialOrientation"))
            setInitialOrientation(NBTHelper.readEnum(compound, "InitialOrientation", Direction.class));

        yaw = compound.getFloat("Yaw");
        pitch = compound.getFloat("Pitch");
        manuallyPlaced = compound.getBoolean("Placed");

        if (compound.contains("ForceYaw"))
            startAtYaw(compound.getFloat("ForceYaw"));

        ListTag vecNBT = compound.getList("CachedMotion", 6);
        if (!vecNBT.isEmpty()) {
            motionBeforeStall = new Vec3(vecNBT.getDouble(0), vecNBT.getDouble(1), vecNBT.getDouble(2));
            if (!motionBeforeStall.equals(Vec3.ZERO))
                targetYaw = prevYaw = yaw += yawFromVector(motionBeforeStall);
            setDeltaMovement(Vec3.ZERO);
        }

        setCouplingId(compound.contains("OnCoupling") ? compound.getUUID("OnCoupling") : null);
    }

    @Override
    protected void writeAdditional(CompoundTag compound, boolean spawnPacket) {
        super.writeAdditional(compound, spawnPacket);

        if (motionBeforeStall != null)
            compound.put("CachedMotion", newDoubleList(motionBeforeStall.x, motionBeforeStall.y, motionBeforeStall.z));

        Direction optional = entityData.get(INITIAL_ORIENTATION);
        if (optional.getAxis().isHorizontal())
            NBTHelper.writeEnum(compound, "InitialOrientation", optional);
        if (forceAngle) {
            compound.putFloat("ForceYaw", yaw);
            forceAngle = false;
        }

        compound.putBoolean("Placed", manuallyPlaced);
        compound.putFloat("Yaw", yaw);
        compound.putFloat("Pitch", pitch);

        if (getCouplingId() != null)
            compound.putUUID("OnCoupling", getCouplingId());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (INITIAL_ORIENTATION.equals(key) && isInitialOrientationPresent() && !manuallyPlaced)
            startAtInitialYaw();
    }

    public boolean isInitialOrientationPresent() {
        return entityData.get(INITIAL_ORIENTATION).getAxis().isHorizontal();
    }

    public void startAtInitialYaw() {
        startAtYaw(getInitialYaw());
    }

    public void startAtYaw(float yaw) {
        targetYaw = this.yaw = prevYaw = yaw;
        forceAngle = true;
    }

    @Override
    public Vec3 applyRotation(Vec3 localPos, float partialTicks) {
        localPos = VecHelper.rotate(localPos, getInitialYaw(), Axis.Y);
        localPos = VecHelper.rotate(localPos, getViewXRot(partialTicks), Axis.Z);
        localPos = VecHelper.rotate(localPos, getViewYRot(partialTicks), Axis.Y);
        return localPos;
    }

    @Override
    public Vec3 reverseRotation(Vec3 localPos, float partialTicks) {
        localPos = VecHelper.rotate(localPos, -getViewYRot(partialTicks), Axis.Y);
        localPos = VecHelper.rotate(localPos, -getViewXRot(partialTicks), Axis.Z);
        localPos = VecHelper.rotate(localPos, -getInitialYaw(), Axis.Y);
        return localPos;
    }

    public float getViewYRot(float partialTicks) {
        return -(partialTicks == 1.0F ? yaw : angleLerp(partialTicks, prevYaw, yaw));
    }

    public float getViewXRot(float partialTicks) {
        return partialTicks == 1.0F ? pitch : angleLerp(partialTicks, prevPitch, pitch);
    }

    @Override
    protected void tickContraption() {
        super.tickContraption();

        if (forceAngle) {
            forceAngle = false;
            prevYaw = yaw = targetYaw;
        }

        if (!level().isClientSide)
            return;

        if (attachedExtraInventories && isSerializingFurnaceCart && contraption != null) {
            contraption.serializeInventories();
            attachedExtraInventories = false;
        }

        prevPitch = pitch;
        prevYaw = yaw;

        if (contraption == null || !contraption.isActive())
            return;

        pitch = contraption.getPitch();
        yaw = contraption.getYaw();

        if (contraption instanceof StabilizedContraption)
            motionBeforeStall = Vec3.ZERO;
        else if (motionBeforeStall != null && !motionBeforeStall.equals(Vec3.ZERO)) {
            targetYaw = yaw += yawFromVector(motionBeforeStall);
            setDeltaMovement(Vec3.ZERO);
        }
    }

    @Override
    protected void disassemble() {
        if (contraption == null)
            return;

        ContraptionEntityData ceData = contraption.getEntityData();
        if (ceData != null)
            ceData.setEntityState(contraption);

        if (contraption instanceof MountedContraption)
            ((MountedContraption) contraption).disassemble();
    }

    public void setCouplingId(@Nullable UUID id) {
        entityData.set(COUPLING, Optional.ofNullable(id));
    }

    @Nullable
    public UUID getCouplingId() {
        return entityData.get(COUPLING).orElse(null);
    }

    public boolean hasCoupling() {
        return getCouplingId() != null;
    }

    public boolean isAttachedTo(AcceptableCoupling coupling) {
        if (hasCoupling())
            return coupling.getCouplingId().equals(getCouplingId());
        return false;
    }

    @Override
    public boolean isPassenger(Entity entity) {
        return contraption != null && contraption.hasEntity(entity);
    }

    public void setAttachedExtraInventories(boolean attached) {
        this.attachedExtraInventories = attached;
    }

    public void setIsSerializingFurnaceCart(boolean isSerializing) {
        this.isSerializingFurnaceCart = isSerializing;
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void render(PoseStack poseStack, float partialTicks, int packedLight) {
        if (contraption == null)
            return;

        TransformStack ms = TransformStack.of(poseStack)
            .rotateY(-getViewYRot(partialTicks))
            .rotateX(-getViewXRot(partialTicks))
            .translate(-getContraptionRenderOffsetX(), -getContraptionRenderOffsetY(), -getContraptionRenderOffsetZ());
        contraption.render(poseStack, partialTicks, packedLight, ms);
    }
}
