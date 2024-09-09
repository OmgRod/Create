package com.simibubi.create.content.contraptions;

import static net.minecraft.world.entity.Entity.collideBoundingBox;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableFloat;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.MutablePair;

import com.google.common.base.Predicates;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.AllPackets;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity.ContraptionRotationState;
import com.simibubi.create.content.contraptions.ContraptionColliderLockPacket.ContraptionColliderLockPacketRequest;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterMovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.sync.ClientMotionPacket;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.collision.ContinuousOBBCollider.ContinuousSeparationManifold;
import com.simibubi.create.foundation.collision.Matrix3d;
import com.simibubi.create.foundation.collision.OrientedBB;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;

public class ContraptionCollider {

	public static Vec3 worldToLocalPos(Vec3 worldPos, Vec3 anchorVec, Matrix3d rotationMatrix, float yawOffset) {
		Vec3 localPos = worldPos.subtract(anchorVec);
		localPos = localPos.subtract(VecHelper.CENTER_OF_ORIGIN);
		localPos = VecHelper.rotate(localPos, -yawOffset, Axis.Y);
		localPos = rotationMatrix.transform(localPos);
		localPos = localPos.add(VecHelper.CENTER_OF_ORIGIN);
		return localPos;
	}

	/** From Entity#collide **/
	static Vec3 collide(Vec3 p_20273_, Entity e) {
		AABB aabb = e.getBoundingBox();
		List<VoxelShape> list = e.level().getEntityCollisions(e, aabb.expandTowards(p_20273_));
		Vec3 vec3 = p_20273_.lengthSqr() == 0.0D ? p_20273_ : collideBoundingBox(e, p_20273_, aabb, e.level(), list);
		boolean flag = p_20273_.x != vec3.x;
		boolean flag1 = p_20273_.y != vec3.y;
		boolean flag2 = p_20273_.z != vec3.z;
		boolean flag3 = flag1 && p_20273_.y < 0.0D;
		if (e.getStepHeight() > 0.0F && flag3 && (flag || flag2)) {
			Vec3 vec31 = collideBoundingBox(e, new Vec3(p_20273_.x, (double) e.getStepHeight(), p_20273_.z), aabb,
					e.level(), list);
			Vec3 vec32 = collideBoundingBox(e, new Vec3(0.0D, (double) e.getStepHeight(), 0.0D),
					aabb.expandTowards(p_20273_.x, 0.0D, p_20273_.z), e.level(), list);
			if (vec32.y < (double) e.getStepHeight()) {
				Vec3 vec33 =
						collideBoundingBox(e, new Vec3(p_20273_.x, 0.0D, p_20273_.z), aabb.move(vec32), e.level(), list)
								.add(vec32);
				if (vec33.horizontalDistanceSqr() > vec31.horizontalDistanceSqr()) {
					vec31 = vec33;
				}
			}

			if (vec31.horizontalDistanceSqr() > vec3.horizontalDistanceSqr()) {
				return vec31.add(collideBoundingBox(e, new Vec3(0.0D, -vec31.y + p_20273_.y, 0.0D), aabb.move(vec31),
						e.level(), list));
			}
		}

		return vec3;
	}

	private static PlayerType getPlayerType(Entity entity) {
		if (!(entity instanceof Player))
			return PlayerType.NONE;
		if (!entity.level().isClientSide)
			return PlayerType.SERVER;
		MutableBoolean isClient = new MutableBoolean(false);
		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> isClient.setValue(isClientPlayerEntity(entity)));
		return isClient.booleanValue() ? PlayerType.CLIENT : PlayerType.REMOTE;
	}

	@OnlyIn(Dist.CLIENT)
	private static boolean isClientPlayerEntity(Entity entity) {
		return entity instanceof LocalPlayer;
	}

	private static List<VoxelShape> getPotentiallyCollidedShapes(Level world, Contraption contraption, AABB localBB) {
		double height = localBB.getYsize();
		double width = localBB.getXsize();
		double horizontalFactor = (height > width && width != 0) ? height / width : 1;
		double verticalFactor = (width > height && height != 0) ? width / height : 1;
		AABB blockScanBB = localBB.inflate(0.5f);
		blockScanBB = blockScanBB.inflate(horizontalFactor, verticalFactor, horizontalFactor);

		BlockPos min = BlockPos.containing(blockScanBB.minX, blockScanBB.minY, blockScanBB.minZ);
		BlockPos max = BlockPos.containing(blockScanBB.maxX, blockScanBB.maxY, blockScanBB.maxZ);

		List<VoxelShape> potentialHits = BlockPos.betweenClosedStream(min, max)
				.filter(contraption.getBlocks()::containsKey)
				.filter(Predicates.not(contraption::isHiddenInPortal))
				.map(p -> {
					BlockState blockState = contraption.getBlocks().get(p).state();
					BlockPos pos = contraption.getBlocks().get(p).pos();
					VoxelShape collisionShape = blockState.getCollisionShape(world, p);
					return collisionShape.move(pos.getX(), pos.getY(), pos.getZ());
				})
				.filter(Predicates.not(VoxelShape::isEmpty))
				.toList();

		return potentialHits;
	}

	public static boolean collideBlocks(AbstractContraptionEntity contraptionEntity) {
		if (!contraptionEntity.supportsTerrainCollision())
			return false;

		Level world = contraptionEntity.getCommandSenderWorld();
		Vec3 motion = contraptionEntity.getDeltaMovement();
		TranslatingContraption contraption = (TranslatingContraption) contraptionEntity.getContraption();
		AABB bounds = contraptionEntity.getBoundingBox();
		Vec3 position = contraptionEntity.position();
		BlockPos gridPos = BlockPos.containing(position);

		if (contraption == null)
			return false;
		if (bounds == null)
			return false;
		if (motion.equals(Vec3.ZERO))
			return false;

		Direction movementDirection = Direction.getNearest(motion.x, motion.y, motion.z);

		// Blocks in the world
		if (movementDirection.getAxisDirection() == AxisDirection.POSITIVE)
			gridPos = gridPos.relative(movementDirection);
		if (isCollidingWithWorld(world, contraption, gridPos, movementDirection))
			return true;

		// Other moving Contraptions
		for (ControlledContraptionEntity otherContraptionEntity : world.getEntitiesOfClass(
				ControlledContraptionEntity.class, bounds.inflate(1), e -> !e.equals(contraptionEntity))) {
			if (isCollidingWithContraption(contraption, otherContraptionEntity.getContraption())) {
				return true;
			}
		}

		return false;
	}

	private static boolean isCollidingWithWorld(Level world, TranslatingContraption contraption, BlockPos pos,
												Direction movementDirection) {
		BlockState blockState = world.getBlockState(pos);
		return !blockState.getCollisionShape(world, pos).isEmpty();
	}

	private static boolean isCollidingWithContraption(TranslatingContraption contraption,
													  TranslatingContraption otherContraption) {
		AABB aabb = contraption.getLocalBB();
		AABB otherAabb = otherContraption.getLocalBB();
		return aabb.intersects(otherAabb);
	}

	@OnlyIn(Dist.CLIENT)
	public static boolean isClientPlayer(Entity entity) {
		return entity instanceof LocalPlayer;
	}

	@OnlyIn(Dist.CLIENT)
	public static boolean isServerPlayer(Entity entity) {
		return entity instanceof ServerPlayer;
	}

	public static boolean isContraptionOfType(Contraption contraption, Class<?> type) {
		return contraption.getClass() == type;
	}

	public static boolean canContraptionCollideWithOtherContraptions(Contraption contraption) {
		if (contraption == null)
			return false;
		return contraption.getCollidesWithOtherContraptions();
	}

	public static void playSound(Entity entity, SoundEvents soundEvent) {
		if (entity.level().isClientSide)
			return;
		entity.level().playSound(null, entity.blockPosition(), soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
	}

	public static void sendClientMotionPacket(Entity entity, Vec3 motion) {
		if (entity instanceof LocalPlayer) {
			AllPackets.sendToServer(new ClientMotionPacket(entity.getId(), motion));
		}
	}

	public static boolean isContraptionAttachedTo(BlockState blockState) {
		return blockState.hasProperty(BlockBreakingMovementBehaviour.BREAKING);
	}
}
