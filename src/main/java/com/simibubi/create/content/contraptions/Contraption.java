package com.simibubi.create.content.contraptions;

import com.mojang.datafixers.util.Pair;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.content.contraptions.components.structureMovement.MovementBehaviour;
import com.simibubi.create.content.contraptions.components.structureMovement.MovementContext;
import com.simibubi.create.content.contraptions.components.structureMovement.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.components.structureMovement.RopeBlock;
import com.simibubi.create.content.contraptions.components.structureMovement.ShaftBlock;
import com.simibubi.create.content.contraptions.components.structureMovement.SlidingDoorBlock;
import com.simibubi.create.content.contraptions.components.structureMovement.SuperGlueEntity;
import com.simibubi.create.content.contraptions.components.structureMovement.impl.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.contraptions.components.structureMovement.impl.HarvesterMovementBehaviour;
import com.simibubi.create.content.contraptions.components.structureMovement.impl.PulleyBlockEntity;
import com.simibubi.create.content.contraptions.controllers.StructureBlockInfo;
import com.simibubi.create.content.contraptions.controllers.StructureTransform;
import com.simibubi.create.content.contraptions.controllers.storage.Storage;
import com.simibubi.create.content.contraptions.controllers.storage.StorageWrapper;
import com.simibubi.create.content.contraptions.controllers.storage.AbstractContraptionEntity;
import com.simibubi.create.foundation.utility.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FluidStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.fluids.FluidStack;
import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Contraption {

	private final Map<BlockPos, StructureBlockInfo> blocks = new HashMap<>();
	private final Map<UUID, Integer> seatMapping = new HashMap<>();
	private final List<BlockPos> seats = new ArrayList<>();
	private final List<MutablePair<StructureBlockInfo, MovementContext>> actors = new ArrayList<>();
	private final Map<BlockPos, MovingInteractionBehaviour> interactors = new HashMap<>();
	private final Set<ItemStack> disabledActors = new HashSet<>();
	private final Map<UUID, StabilizedSubContraption> stabilizedSubContraptions = new HashMap<>();
	private final List<BlockEntity> renderedBlockEntities = new ArrayList<>();
	private final Storage storage = new Storage();
	private Optional<List<AABB>> simplifiedEntityColliders = Optional.empty();
	private CompletableFuture<List<AABB>> simplifiedEntityColliderProvider;

	public void removeBlocksFromWorld(Level world, StructureTransform transform) {
		// Implementation here...
	}

	public void addBlocksToWorld(Level world, StructureTransform transform) {
		if (disassembled)
			return;
		disassembled = true;

		translateMultiblockControllers(transform);

		for (boolean nonBrittles : Iterate.trueAndFalse) {
			for (StructureBlockInfo block : blocks.values()) {
				if (nonBrittles == BlockMovementChecks.isBrittle(block.state()))
					continue;

				BlockPos targetPos = transform.apply(block.pos());
				BlockState state = transform.apply(block.state());

				if (customBlockPlacement(world, targetPos, state))
					continue;

				if (nonBrittles)
					for (Direction face : Iterate.directions)
						state = state.updateShape(face, world.getBlockState(targetPos.relative(face)), world, targetPos,
								targetPos.relative(face));

				BlockState blockState = world.getBlockState(targetPos);
				if (blockState.getDestroySpeed(world, targetPos) == -1 || (state.getCollisionShape(world, targetPos)
						.isEmpty()
						&& !blockState.getCollisionShape(world, targetPos)
						.isEmpty())) {
					if (targetPos.getY() == world.getMinBuildHeight())
						targetPos = targetPos.above();
					world.levelEvent(2001, targetPos, Block.getId(state));
					Block.dropResources(state, world, targetPos, null);
					continue;
				}
				if (state.getBlock() instanceof SimpleWaterloggedBlock
						&& state.hasProperty(BlockStateProperties.WATERLOGGED)) {
					FluidState fluidState = world.getFluidState(targetPos);
					state = state.setValue(BlockStateProperties.WATERLOGGED, fluidState.getType() == Fluids.WATER);
				}

				world.destroyBlock(targetPos, true);

				if (AllBlocks.SHAFT.has(state))
					state = ShaftBlock.pickCorrectShaftType(state, world, targetPos);
				if (state.hasProperty(SlidingDoorBlock.VISIBLE))
					state = state.setValue(SlidingDoorBlock.VISIBLE, !state.getValue(SlidingDoorBlock.OPEN))
							.setValue(SlidingDoorBlock.POWERED, false);
				// Stop Sculk shriekers from getting "stuck" if moved mid-shriek.
				if (state.is(Blocks.SCULK_SHRIEKER)) {
					state = Blocks.SCULK_SHRIEKER.defaultBlockState();
				}

				world.setBlock(targetPos, state, Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_ALL);

				boolean verticalRotation = transform.rotationAxis == null || transform.rotationAxis.isHorizontal();
				verticalRotation = verticalRotation && transform.rotation != Rotation.NONE;
				if (verticalRotation) {
					if (state.getBlock() instanceof RopeBlock || state.getBlock() instanceof MagnetBlock
							|| state.getBlock() instanceof DoorBlock)
						world.destroyBlock(targetPos, true);
				}

				BlockEntity blockEntity = world.getBlockEntity(targetPos);

				CompoundTag tag = block.nbt();

				// Temporary fix: Calling load(CompoundTag tag) on a Sculk sensor causes it to not react to vibrations.
				if (state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.SCULK_SHRIEKER))
					tag = null;

				if (blockEntity != null)
					tag = NBTProcessors.process(state, blockEntity, tag, false);
				if (blockEntity != null && tag != null) {
					tag.putInt("x", targetPos.getX());
					tag.putInt("y", targetPos.getY());
					tag.putInt("z", targetPos.getZ());

					if (verticalRotation && blockEntity instanceof PulleyBlockEntity) {
						tag.remove("Offset");
						tag.remove("InitialOffset");
					}

					if (blockEntity instanceof IMultiBlockEntityContainer) {
						if (tag.contains("LastKnownPos") || capturedMultiblocks.isEmpty()) {
							tag.put("LastKnownPos", NbtUtils.writeBlockPos(BlockPos.ZERO.below(Integer.MAX_VALUE - 1)));
						}
					}

					blockEntity.load(tag);
					storage.addStorageToWorld(block, blockEntity);
				}

				transform.apply(blockEntity);
			}
		}

		for (StructureBlockInfo block : blocks.values()) {
			if (!shouldUpdateAfterMovement(block))
				continue;
			BlockPos targetPos = transform.apply(block.pos());
			world.markAndNotifyBlock(targetPos, world.getChunkAt(targetPos), block.state(), block.state(),
					Block.UPDATE_MOVE_BY_PISTON | Block.UPDATE_ALL, 512);
		}

		for (AABB box : superglue) {
			box = new AABB(transform.apply(new Vec3(box.minX, box.minY, box.minZ)),
					transform.apply(new Vec3(box.maxX, box.maxY, box.maxZ)));
			if (!world.isClientSide)
				world.addFreshEntity(new SuperGlueEntity(world, box));
		}

		storage.clear();
	}

	protected void translateMultiblockControllers(StructureTransform transform) {
		if (transform.rotationAxis != null && transform.rotationAxis != Axis.Y && transform.rotation != Rotation.NONE) {
			capturedMultiblocks.values().forEach(info -> {
				info.nbt().put("LastKnownPos", NbtUtils.writeBlockPos(BlockPos.ZERO.below(Integer.MAX_VALUE - 1)));
			});
			return;
		}

		capturedMultiblocks.keySet().forEach(controllerPos -> {
			Collection<StructureBlockInfo> multiblockParts = capturedMultiblocks.get(controllerPos);
			Optional<BoundingBox> optionalBoundingBox = BoundingBox.encapsulatingPositions(multiblockParts.stream().map(info -> transform.apply(info.pos())).toList());
			if (optionalBoundingBox.isEmpty())
				return;

			BoundingBox boundingBox = optionalBoundingBox.get();
			BlockPos newControllerPos = new BlockPos(boundingBox.minX(), boundingBox.minY(), boundingBox.minZ());
			BlockPos newLocalPos = toLocalPos(newControllerPos);
			BlockPos otherPos = transform

					.apply(controllerPos);

			StructureBlockInfo prevControllerInfo = blocks.get(controllerPos);
			StructureBlockInfo newControllerInfo = blocks.get(otherPos);

			blocks.put(otherPos, new StructureBlockInfo(newControllerInfo.pos(), newControllerInfo.state(), prevControllerInfo.nbt()));
			blocks.put(controllerPos, new StructureBlockInfo(prevControllerInfo.pos(), prevControllerInfo.state(), newControllerInfo.nbt()));
		});
	}

	public void addPassengersToWorld(Level world, StructureTransform transform, List<Entity> seatedEntities) {
		for (Entity seatedEntity : seatedEntities) {
			if (getSeatMapping().isEmpty())
				continue;
			Integer seatIndex = getSeatMapping().get(seatedEntity.getUUID());
			if (seatIndex == null)
				continue;
			BlockPos seatPos = getSeats().get(seatIndex);
			seatPos = transform.apply(seatPos);
			if (!(world.getBlockState(seatPos).getBlock() instanceof SeatBlock))
				continue;
			if (SeatBlock.isSeatOccupied(world, seatPos))
				continue;
			SeatBlock.sitDown(world, seatPos, seatedEntity);
		}
	}

	public void startMoving(Level world) {
		disabledActors.clear();

		for (MutablePair<StructureBlockInfo, MovementContext> pair : actors) {
			MovementContext context = new MovementContext(world, pair.left, this);
			MovementBehaviour behaviour = AllMovementBehaviours.getBehaviour(pair.left.state());
			if (behaviour != null)
				behaviour.startMoving(context);
			pair.setRight(context);
			if (behaviour instanceof ContraptionControlsMovement)
				disableActorOnStart(context);
		}

		for (ItemStack stack : disabledActors)
			setActorsActive(stack, false);
	}

	protected void disableActorOnStart(MovementContext context) {
		if (!ContraptionControlsMovement.isDisabledInitially(context))
			return;
		ItemStack filter = ContraptionControlsMovement.getFilter(context);
		if (filter == null)
			return;
		if (isActorTypeDisabled(filter))
			return;
		disabledActors.add(filter);
	}

	public boolean isActorTypeDisabled(ItemStack filter) {
		return disabledActors.stream()
				.anyMatch(i -> ContraptionControlsMovement.isSameFilter(i, filter));
	}

	public void setActorsActive(ItemStack referenceStack, boolean enable) {
		for (MutablePair<StructureBlockInfo, MovementContext> pair : actors) {
			MovementBehaviour behaviour = AllMovementBehaviours.getBehaviour(pair.left.state());
			if (behaviour == null)
				continue;
			ItemStack behaviourStack = behaviour.canBeDisabledVia(pair.right);
			if (behaviourStack == null)
				continue;
			if (!referenceStack.isEmpty() && !ContraptionControlsMovement.isSameFilter(referenceStack, behaviourStack))
				continue;
			pair.right.disabled = !enable;
			if (!enable)
				behaviour.onDisabledByControls(pair.right);
		}
	}

	public List<ItemStack> getDisabledActors() {
		return disabledActors;
	}

	public void stop(Level world) {
		forEachActor(world, (behaviour, ctx) -> {
			behaviour.stopMoving(ctx);
			ctx.position = null;
			ctx.motion = Vec3.ZERO;
			ctx.relativeMotion = Vec3.ZERO;
			ctx.rotation = v -> v;
		});
	}

	public void forEachActor(Level world, BiConsumer<MovementBehaviour, MovementContext> callBack) {
		for (MutablePair<StructureBlockInfo, MovementContext> pair : actors) {
			MovementBehaviour behaviour = AllMovementBehaviours.getBehaviour(pair.getLeft().state());
			if (behaviour == null)
				continue;
			callBack.accept(behaviour, pair.getRight());
		}
	}

	protected boolean shouldUpdateAfterMovement(StructureBlockInfo info) {
		if (PoiTypes.forState(info.state()).isPresent())
			return false;
		if (info.state().getBlock() instanceof SlidingDoorBlock)
			return false;
		return true;
	}

	public void expandBoundsAroundAxis(Direction.Axis axis) {
		Set<BlockPos> blocks = getBlocks().keySet();

		int radius = (int) (Math.ceil(Math.sqrt(getRadius(blocks, axis))));

		int maxX = radius + 2;
		int maxY = radius + 2;
		int maxZ = radius + 2;
		int minX = -radius - 1;
		int minY = -radius - 1;
		int minZ = -radius - 1;

		if (axis == Direction.Axis.X) {
			maxX = (int) bounds.maxX;
			minX = (int) bounds.minX;
		} else if (axis == Direction.Axis.Y) {
			maxY = (int) bounds.maxY;
			minY = (int) bounds.minY;
		} else if (axis == Direction.Axis.Z) {
			maxZ = (int) bounds.maxZ;
			minZ = (int) bounds.minZ;
		}

		bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	public Map<UUID, Integer> getSeatMapping() {
		return seatMapping;
	}

	public BlockPos getSeatOf(UUID entityId) {
		if (!getSeatMapping().containsKey(entityId))
			return null;
		int seatIndex = getSeatMapping().get(entityId);
		if (seatIndex >= getSeats().size())
			return null;
		return getSeats().get(seatIndex);
	}

	public BlockPos getBearingPosOf(UUID subContraptionEntityId) {
		if (stabilizedSubContraptions.containsKey(subContraptionEntityId))
			return stabilizedSubContraptions.get(subContraptionEntityId).getConnectedPos();
		return null;
	}

	public void setSeatMapping(Map<UUID, Integer> seatMapping) {
		this.seatMapping = seatMapping;
	}

	public List<BlockPos> getSeats() {
		return seats;
	}

	public Map<BlockPos, StructureBlockInfo> getBlocks() {
		return blocks;
	}

	public List<MutablePair<StructureBlockInfo, MovementContext>> getActors() {
		return actors;
	}

	@Nullable
	public MutablePair<StructureBlockInfo, MovementContext> getActorAt(BlockPos localPos) {
		for (MutablePair<StructureBlockInfo, MovementContext> pair : actors)
			if (localPos.equals(pair.left.pos()))
				return pair;
		return null;
	}

	public Map<BlockPos, MovingInteractionBehaviour> getInteractors() {
		return interactors;
	}

	public void invalidateColliders() {
		simplifiedEntityColliders = Optional.empty();
		gatherBBsOffThread();
	}

	private void gatherBBsOffThread() {
		getContraptionWorld();
		simplifiedEntityColliderProvider = CompletableFuture.supplyAsync(() -> {
			VoxelShape combinedShape = Shapes.empty();
			for (Map.Entry<BlockPos, StructureBlockInfo> entry : blocks.entrySet()) {
				StructureBlockInfo info = entry.getValue();
				BlockPos localPos = entry.getKey();
				VoxelShape collisionShape = info.state().getCollisionShape(world, localPos, CollisionContext.empty());
				if (collisionShape.isEmpty())
					continue;
				combinedShape = Shapes.joinUnoptimized(combinedShape,
						collisionShape.move(localPos.getX(), localPos.getY(), localPos.getZ()), BooleanOp.OR);
			}
			return combinedShape.optimize().toAabbs();
		}).thenAccept(r -> {
			simplifiedEntityColliders = Optional.of(r);
			simplifiedEntityColliderProvider = null;
		});
	}

	public static float getRadius(Set<BlockPos> blocks, Direction.Axis axis) {
		switch (axis) {
			case X:
				return getMaxDistSqr(blocks, BlockPos::getY, BlockPos::getZ);
			case Y:
				return getMaxDistSqr(blocks, BlockPos::getX, BlockPos::getZ);
			case Z:
				return getMaxDistSqr(blocks, BlockPos::getX, BlockPos::getY);
		}
		throw new IllegalStateException("Impossible axis");
	}

	public static float getMaxDistSqr(Set<BlockPos> blocks, ICoordinate one, ICoordinate other) {
		float maxDistSq = -1;
		for (BlockPos pos : blocks) {
			float a = one.get(pos);
			float b = other.get(pos);
			float distSq = a * a + b * b;
			if (distSq > maxDistSq)
				maxDistSq = distSq;
		}
		return maxDistSq;
	}

	public IItemHandlerModifiable getSharedInventory() {
		return storage.getItems();
	}

	public IItemHandlerModifiable getSharedFuelInventory() {
		return storage.getFuelItems();
	}

	public IFluidHandler getSharedFluidTanks() {
		return storage.getFluids();
	}

	public RenderedBlocks getRenderedBlocks() {
		return new RenderedBlocks(pos -> {
			StructureBlockInfo info = blocks.get(pos);
			if (info == null) {
				return Blocks.AIR.defaultBlockState();
			}
			return info.state();
		}, blocks.keySet());
	}

	public Collection<BlockEntity> getRenderedBEs() {
		return renderedBlockEntities;
	}

	public boolean isHiddenInPortal(BlockPos localPos) {
		return false;
	}

	public Optional<List<AABB>> getSimplifiedEntityColliders() {
		return simplifiedEntityColliders;
	}

	public void handleContraptionFluidPacket(BlockPos localPos, FluidStack containedFluid) {
		storage.updateContainedFluid(localPos, containedFluid);
	}

	public static class ContraptionInvWrapper extends CombinedInvWrapper {
		protected final boolean isExternal

				;

		public ContraptionInvWrapper(boolean isExternal, IItemHandlerModifiable... itemHandler) {
			super(itemHandler);
			this.isExternal = isExternal;
		}

		public ContraptionInvWrapper(IItemHandlerModifiable... itemHandler) {
			this(false, itemHandler);
		}

		public boolean isSlotExternal(int slot) {
			if (isExternal)
				return true;
			IItemHandlerModifiable handler = getHandlerFromIndex(getIndexForSlot(slot));
			return handler instanceof ContraptionInvWrapper && ((ContraptionInvWrapper) handler).isSlotExternal(slot);
		}
	}

	public void tickStorage(AbstractContraptionEntity entity) {
		storage.entityTick(entity);
	}

	public boolean containsBlockBreakers() {
		for (MutablePair<StructureBlockInfo, MovementContext> pair : actors) {
			MovementBehaviour behaviour = AllMovementBehaviours.getBehaviour(pair.getLeft().state());
			if (behaviour instanceof BlockBreakingMovementBehaviour || behaviour instanceof HarvesterMovementBehaviour)
				return true;
		}
		return false;
	}

	public record RenderedBlocks(Function<BlockPos, BlockState> lookup, Iterable<BlockPos> positions) {
	}
}
