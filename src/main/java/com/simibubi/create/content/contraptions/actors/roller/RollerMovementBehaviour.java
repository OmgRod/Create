package com.simibubi.create.content.contraptions.actors.roller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.simibubi.create.AllTags;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity.RollingMode;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.pulley.PulleyContraption;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.content.trains.bogey.StandardBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.ITrackSelector;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.foundation.item.ItemHelper;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.Pair;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import com.simibubi.create.infrastructure.config.AllConfigs;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

public class RollerMovementBehaviour extends BlockBreakingMovementBehaviour {

	@Override
	public boolean isActive(MovementContext context) {
		return super.isActive(context) && !(context.contraption instanceof PulleyContraption)
				&& VecHelper.isVecPointingTowards(context.relativeMotion, context.state.getValue(RollerBlock.FACING));
	}

	@Override
	public boolean disableBlockEntityRendering() {
		return true;
	}

	@Nullable
	@Override
	public ActorVisual createVisual(VisualizationContext visualizationContext, VirtualRenderWorld simulationWorld,
									MovementContext movementContext) {
		return new RollerActorVisual(visualizationContext, simulationWorld, movementContext);
	}

	@Override
	public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
									ContraptionMatrices matrices, MultiBufferSource buffers) {
		if (!VisualizationManager.supportsVisualization(context.world))
			RollerRenderer.renderInContraption(context, renderWorld, matrices, buffers);
	}

	@Override
	public Vec3 getActiveAreaOffset(MovementContext context) {
		return Vec3.atLowerCornerOf(context.state.getValue(RollerBlock.FACING)
						.getNormal())
				.scale(.45)
				.subtract(0, 2, 0);
	}

	@Override
	protected float getBlockBreakingSpeed(MovementContext context) {
		return Mth.clamp(super.getBlockBreakingSpeed(context) * 1.5f, 1 / 128f, 16f);
	}

	@Override
	public boolean canBreak(Level world, BlockPos breakingPos, BlockState state) {
		for (Direction side : Iterate.directions)
			if (world.getBlockState(breakingPos.relative(side))
					.is(BlockTags.PORTALS))
				return false;

		return super.canBreak(world, breakingPos, state) && !state.getCollisionShape(world, breakingPos)
				.isEmpty() && !AllTags.AllBlockTags.TRACKS.matches(state);
	}

	@Override
	protected DamageSource getDamageSource(Level level) {
		return CreateDamageSources.roller(level);
	}

	@Override
	public void visitNewPosition(MovementContext context, BlockPos pos) {
		Level world = context.world;
		BlockState stateVisited = world.getBlockState(pos);
		if (!stateVisited.isRedstoneConductor(world, pos))
			damageEntities(context, pos, world);
		if (world.isClientSide)
			return;

		List<BlockPos> positionsToBreak = getPositionsToBreak(context, pos);
		if (positionsToBreak.isEmpty()) {
			triggerPaver(context, pos);
			return;
		}

		BlockPos argMax = null;
		double max = -1;
		for (BlockPos toBreak : positionsToBreak) {
			float hardness = context.world.getBlockState(toBreak)
					.getDestroySpeed(world, toBreak);
			if (hardness < max)
				continue;
			max = hardness;
			argMax = toBreak;
		}

		if (argMax == null) {
			triggerPaver(context, pos);
			return;
		}

		context.data.put("ReferencePos", NbtUtils.writeBlockPos(pos));
		context.data.put("BreakingPos", NbtUtils.writeBlockPos(argMax));
		context.stall = true;
	}

	@Override
	protected void onBlockBroken(MovementContext context, BlockPos pos, BlockState brokenState) {
		super.onBlockBroken(context, pos, brokenState);
		if (!context.data.contains("ReferencePos"))
			return;

		BlockPos referencePos = NbtUtils.readBlockPos(context.data.getCompound("ReferencePos"));
		for (BlockPos otherPos : getPositionsToBreak(context, referencePos))
			if (!otherPos.equals(pos))
				destroyBlock(context, otherPos);

		triggerPaver(context, referencePos);
		context.data.remove("ReferencePos");
	}

	@Override
	protected void destroyBlock(MovementContext context, BlockPos breakingPos) {
		BlockState blockState = context.world.getBlockState(breakingPos);
		boolean noHarvest = blockState.is(BlockTags.NEEDS_IRON_TOOL) || blockState.is(BlockTags.NEEDS_STONE_TOOL)
				|| blockState.is(BlockTags.NEEDS_DIAMOND_TOOL);

		BlockHelper.destroyBlock(context.world, breakingPos, 1f, stack -> {
			if (noHarvest || context.world.random.nextBoolean())
				return;
			this.dropItem(context, stack);
		});

		super.destroyBlock(context, breakingPos);
	}

	RollerTravellingPoint rollerScout = new RollerTravellingPoint();

	protected List<BlockPos> getPositionsToBreak(MovementContext context, BlockPos visitedPos) {
		ArrayList<BlockPos> positions = new ArrayList<>();

		RollingMode mode = getMode(context);
		if (mode != RollingMode.TUNNEL_PAVE)
			return positions;

		int startingY = 1;
		if (!getStateToPaveWith(context).isAir()) {
			FilterItemStack filter = context.getFilterFromBE();
			if (!ItemHelper
					.extract(context.contraption.getSharedInventory(),
							stack -> filter.test(context.world, stack), 1, true)
					.isEmpty())
				startingY = 0;
		}

		// Train
		PaveTask profileForTracks = createHeightProfileForTracks(context);
		if (profileForTracks != null) {
			for (Couple<Integer> coords : profileForTracks.keys()) {
				float height = profileForTracks.get(coords);
				BlockPos targetPosition = BlockPos.containing(coords.getFirst(), height, coords.getSecond());
				boolean shouldPlaceSlab = height > Math.floor(height) + .45;
				if (startingY == 1 && shouldPlaceSlab && context.world.getBlockState(targetPosition.above())
						.getOptionalValue(SlabBlock.TYPE)
						.orElse(SlabType.DOUBLE) == SlabType.BOTTOM)
					startingY = 2;
				for (int i = startingY; i <= (shouldPlaceSlab ? 3 : 2); i++)
					if (testBreakerTarget(context, targetPosition.above(i), i))
						positions.add(targetPosition.above(i));
			}
			return positions;
		}

		// Otherwise
		for (int i = startingY; i <= 2; i++)
			if (testBreakerTarget(context, visitedPos.above(i), i))
				positions.add(

						visitedPos.above(i));

		return positions;
	}

	private boolean testBreakerTarget(MovementContext context, BlockPos pos, int level) {
		BlockState state = context.world.getBlockState(pos);
		if (!state.getBlock().is(BlockTags.FENCES) && state.getMaterial().isSolid())
			return true;
		if (level > 1 && state.getBlock() instanceof SlabBlock
				&& state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
			return true;
		return state.getBlock() instanceof FallingBlock;
	}

	private void triggerPaver(MovementContext context, BlockPos pos) {
		if (getStateToPaveWith(context).isAir())
			return;

		int y = context.world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1;
		int xzRadius = 2;

		for (int x = -xzRadius; x <= xzRadius; x++) {
			for (int z = -xzRadius; z <= xzRadius; z++) {
				BlockPos targetPos = pos.offset(x, 0, z);
				if (targetPos.getY() < y)
					continue;
				BlockState stateAtTarget = context.world.getBlockState(targetPos);
				if (stateAtTarget.is(BlockTags.ICE))
					context.world.setBlock(targetPos, stateAtTarget.getFluidState().createLegacyBlock(), 3);
			}
		}
	}

	private RollingMode getMode(MovementContext context) {
		return context.state.getValue(RollerBlock.MODE);
	}

	private BlockState getStateToPaveWith(MovementContext context) {
		return AllBlocks.PAVEABLE.get().defaultBlockState();
	}

	private PaveTask createHeightProfileForTracks(MovementContext context) {
		TrackGraph trackGraph = ((CarriageContraptionEntity) context.contraption).getTrackGraph();
		if (trackGraph == null)
			return null;

		HashSet<TrackEdge> edges = new HashSet<>();
		for (Carriage carriage : trackGraph.getCarriages()) {
			CarriageContraptionEntity carriageEntity = (CarriageContraptionEntity) carriage.getContraption();
			if (carriageEntity == null)
				continue;
			for (BlockPos pos : carriageEntity.getAllPositions())
				edges.addAll(trackGraph.getEdges(pos));
		}
		return new PaveTask(edges);
	}

	private class PaveTask {

		private HashSet<TrackEdge> edges;
		private HashMap<Couple<Integer>, Float> heightProfile = new HashMap<>();

		public PaveTask(HashSet<TrackEdge> edges) {
			this.edges = edges;
			computeHeightProfile();
		}

		private void computeHeightProfile() {
			for (TrackEdge edge : edges) {
				for (BlockPos pos : edge.getTrackPositions()) {
					float height = (float) pos.getY();
					heightProfile.put(Couple.create(pos.getX(), pos.getZ()), height);
				}
			}
		}

		public Float get(Couple<Integer> coords) {
			return heightProfile.get(coords);
		}

		public Set<Couple<Integer>> keys() {
			return heightProfile.keySet();
		}
	}
}
