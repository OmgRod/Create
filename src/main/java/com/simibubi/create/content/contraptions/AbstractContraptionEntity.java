package com.simibubi.create.content.contraptions;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.MutablePair;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.AllPackets;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceMovement;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsStopControllingPacket;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.render.ContraptionRenderInfo;
import com.simibubi.create.content.contraptions.sync.ContraptionSeatMappingPacket;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.collision.Matrix3d;
import com.simibubi.create.foundation.mixin.accessor.ServerLevelAccessor;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;

public abstract class AbstractContraptionEntity extends Entity implements IEntityAdditionalSpawnData {

	private static final EntityDataAccessor<Boolean> STALLED =
			SynchedEntityData.defineId(AbstractContraptionEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Optional<UUID>> CONTROLLED_BY =
			SynchedEntityData.defineId(AbstractContraptionEntity.class, EntityDataSerializers.OPTIONAL_UUID);

	public final Map<Entity, MutableInt> collidingEntities;
	protected Contraption contraption;
	protected boolean initialized;
	protected boolean prevPosInvalid;
	private boolean skipActorStop;

	public AbstractContraptionEntity(EntityType<?> type, Level world) {
		super(type, world);
		this.collidingEntities = new IdentityHashMap<>();
	}

	@Override
	protected void defineSynchedData() {
		this.getEntityData().define(STALLED, false);
		this.getEntityData().define(CONTROLLED_BY, Optional.empty());
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putBoolean("Stalled", this.isStalled());
		if (this.getControlledBy().isPresent()) {
			tag.putUUID("ControlledBy", this.getControlledBy().get());
		}
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		this.setStalled(tag.getBoolean("Stalled"));
		if (tag.hasUUID("ControlledBy")) {
			this.setControlledBy(Optional.of(tag.getUUID("ControlledBy")));
		}
	}

	@Override
	public void writeSpawnData(FriendlyByteBuf buffer) {
		buffer.writeBoolean(this.isStalled());
		buffer.writeBoolean(this.getControlledBy().isPresent());
		this.getControlledBy().ifPresent(uuid -> buffer.writeUUID(uuid));
	}

	@Override
	public void readSpawnData(FriendlyByteBuf buffer) {
		this.setStalled(buffer.readBoolean());
		if (buffer.readBoolean()) {
			this.setControlledBy(Optional.of(buffer.readUUID()));
		}
	}

	public boolean isStalled() {
		return this.getEntityData().get(STALLED);
	}

	public void setStalled(boolean stalled) {
		this.getEntityData().set(STALLED, stalled);
	}

	public Optional<UUID> getControlledBy() {
		return this.getEntityData().get(CONTROLLED_BY);
	}

	public void setControlledBy(Optional<UUID> uuid) {
		this.getEntityData().set(CONTROLLED_BY, uuid);
	}

	public void onCollision(Entity entity) {
		if (entity instanceof TamableAnimal) {
			TamableAnimal ta = (TamableAnimal) entity;
			// Process TamableAnimal entity
		}
		if (entity instanceof LivingEntity) {
			LivingEntity le = (LivingEntity) entity;
			// Process LivingEntity entity
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (this.isStalled()) {
			return;
		}
		// Update the contraption
	}

	@Override
	public void kill() {
		super.kill();
		// Handle contraption destruction
	}

	@Override
	public void onRemovedFromWorld() {
		super.onRemovedFromWorld();
		// Clean up contraption
	}

	@Override
	public void move(MoverType type, Vec3 vec) {
		super.move(type, vec);
		// Update contraption movement
	}

	@Override
	protected void onMove() {
		super.onMove();
		// Update contraption position
	}

	@Override
	public void push(Entity entity) {
		super.push(entity);
		// Handle pushing entities
	}

	@Override
	public void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		// Save contraption data
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		// Load contraption data
	}

	@Override
	public void setPos(double x, double y, double z) {
		super.setPos(x, y, z);
		// Update contraption position
	}

	@Override
	public void setPosRaw(double x, double y, double z) {
		super.setPosRaw(x, y, z);
		// Update contraption position
	}

	@Override
	public void move(MoverType type, double x, double y, double z) {
		super.move(type, x, y, z);
		// Update contraption movement
	}

	@Override
	protected void tickDeath() {
		super.tickDeath();
		// Handle contraption death
	}

	@Override
	protected void tickStart() {
		super.tickStart();
		// Start contraption update
	}

	@Override
	protected void tickEnd() {
		super.tickEnd();
		// End contraption update
	}

	@Override
	protected void handleCollision(Vec3 vec) {
		super.handleCollision(vec);
		// Handle contraption collision
	}

	@Override
	protected void updateMovement() {
		super.updateMovement();
		// Update contraption movement
	}

	@Override
	public void pushEntities() {
		super.pushEntities();
		// Push entities around the contraption
	}

	@Override
	public void applyEntityCollision(Entity entity) {
		super.applyEntityCollision(entity);
		// Handle entity collision

		with contraption;
	}
}
