package com.simibubi.create.content.contraptions.render;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

import org.apache.commons.lang3.tuple.MutablePair;

import com.jozufozu.flywheel.api.model.Model;
import com.jozufozu.flywheel.api.task.Plan;
import com.jozufozu.flywheel.api.visual.BlockEntityVisual;
import com.jozufozu.flywheel.api.visual.DynamicVisual;
import com.jozufozu.flywheel.api.visual.LitVisual;
import com.jozufozu.flywheel.api.visual.TickableVisual;
import com.jozufozu.flywheel.api.visual.VisualFrameContext;
import com.jozufozu.flywheel.api.visual.VisualTickContext;
import com.jozufozu.flywheel.api.visualization.BlockEntityVisualizer;
import com.jozufozu.flywheel.api.visualization.VisualEmbedding;
import com.jozufozu.flywheel.api.visualization.VisualizationContext;
import com.jozufozu.flywheel.api.visualization.VisualizerRegistry;
import com.jozufozu.flywheel.lib.instance.InstanceTypes;
import com.jozufozu.flywheel.lib.instance.TransformedInstance;
import com.jozufozu.flywheel.lib.model.baked.MultiBlockModelBuilder;
import com.jozufozu.flywheel.lib.task.ForEachPlan;
import com.jozufozu.flywheel.lib.task.NestedPlan;
import com.jozufozu.flywheel.lib.task.PlanMap;
import com.jozufozu.flywheel.lib.task.RunnablePlan;
import com.jozufozu.flywheel.lib.visual.AbstractEntityVisual;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.Contraption.RenderedBlocks;
import com.simibubi.create.content.contraptions.behaviour.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.foundation.utility.worldWrappers.WrappedBlockAndTintGetter;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.client.model.data.ModelData;

public class ContraptionVisual<E extends AbstractContraptionEntity> extends AbstractEntityVisual<E> implements DynamicVisual, TickableVisual, LitVisual {
	protected final VisualEmbedding embedding;
	private final List<BlockEntityVisual<?>> children = new ArrayList<>();
	private final List<ActorVisual> actors = new ArrayList<>();
	private final PlanMap<DynamicVisual, VisualFrameContext> dynamicVisuals = new PlanMap<>();
	private final PlanMap<TickableVisual, VisualTickContext> tickableVisuals = new PlanMap<>();
	private VirtualRenderWorld virtualRenderWorld;
	private Notifier notifier;
	private Model model;
	private TransformedInstance structure;

	private final PoseStack contraptionMatrix = new PoseStack();

	public ContraptionVisual(VisualizationContext ctx, E entity) {
		super(ctx, entity);
		embedding = ctx.createEmbedding();
    }

	@Override
	public void init(float partialTick) {
		setEmbeddingMatrices(partialTick);

		Contraption contraption = entity.getContraption();
		virtualRenderWorld = ContraptionRenderDispatcher.setupRenderWorld(level, contraption);

		RenderedBlocks blocks = contraption.getRenderedBlocks();
		BlockAndTintGetter modelWorld = new WrappedBlockAndTintGetter(virtualRenderWorld) {
			@Override
			public BlockState getBlockState(BlockPos pos) {
				return blocks.lookup().apply(pos);
			}
		};

		model = new MultiBlockModelBuilder(modelWorld, blocks.positions())
				.modelDataLookup(pos -> contraption.modelData.getOrDefault(pos, ModelData.EMPTY))
				.build();

		structure = embedding.instancerProvider()
				.instancer(InstanceTypes.TRANSFORMED, model)
				.createInstance();

		for (BlockEntity be : contraption.maybeInstancedBlockEntities) {
			setupVisualizer(be, partialTick);
		}

		for (var actor : contraption.getActors()) {
			setupActor(actor, partialTick);
		}

		updateLight();
	}

	private void setupActor(MutablePair<StructureTemplate.StructureBlockInfo, MovementContext> actor, float partialTick) {
		MovementContext context = actor.getRight();
		if (context == null) {
			return;
		}
		if (context.world == null) {
			context.world = level;
		}

		StructureTemplate.StructureBlockInfo blockInfo = actor.getLeft();

		MovementBehaviour movementBehaviour = AllMovementBehaviours.getBehaviour(blockInfo.state());
		if (movementBehaviour == null) {
			return;
		}
		var instance = movementBehaviour.createInstance(this.embedding, virtualRenderWorld, context);

		if (instance == null) {
			return;
		}

		instance.init(partialTick);

		actors.add(instance);
	}

	@SuppressWarnings("unchecked")
	protected  <T extends BlockEntity> void setupVisualizer(T be, float partialTicks) {
		BlockEntityVisualizer<? super T> visualizer = (BlockEntityVisualizer<? super T>) VisualizerRegistry.getVisualizer(be.getType());
		if (visualizer == null) {
			return;
		}

		Level world = be.getLevel();
		be.setLevel(virtualRenderWorld);
		BlockEntityVisual<? super T> visual = visualizer.createVisual(this.embedding, be);

		visual.init(partialTicks);

		children.add(visual);

		if (visual instanceof DynamicVisual dynamic) {
			dynamicVisuals.add(dynamic, dynamic.planFrame());
		}

		if (visual instanceof TickableVisual tickable) {
			tickableVisuals.add(tickable, tickable.planTick());
		}

		be.setLevel(world);
	}

	@Override
	public Plan<VisualTickContext> planTick() {
		return NestedPlan.of(
				ForEachPlan.of(() -> actors, ActorVisual::tick),
				tickableVisuals
		);
	}

	@Override
	public Plan<VisualFrameContext> planFrame() {
		return NestedPlan.of(
				RunnablePlan.of(this::beginFrame),
				ForEachPlan.of(() -> actors, ActorVisual::beginFrame),
				dynamicVisuals
		);
	}

	protected void beginFrame(VisualFrameContext context) {
		var partialTick = context.partialTick();
		setEmbeddingMatrices(partialTick);

		// TODO: re-collect light if needed
	}

	private void setEmbeddingMatrices(float partialTick) {
		double x = Mth.lerp(partialTick, entity.xOld, entity.getX());
		double y = Mth.lerp(partialTick, entity.yOld, entity.getY());
		double z = Mth.lerp(partialTick, entity.zOld, entity.getZ());

		contraptionMatrix.setIdentity();
		contraptionMatrix.translate(x, y, z);
		entity.applyLocalTransforms(contraptionMatrix, partialTick);

		embedding.transforms(contraptionMatrix.last().pose(), contraptionMatrix.last().normal());
	}

	@Override
	public void updateLight() {
		// FIXME: Some blocks (e.g. large waterwheels) extend well beyond their actual block
		//  and might have lighting issues here
		var boundingBox = entity.getBoundingBox();

		int minX = Mth.floor(boundingBox.minX) - 1;
		int minY = Mth.floor(boundingBox.minY) - 1;
		int minZ = Mth.floor(boundingBox.minZ) - 1;
		int sizeX = Mth.ceil(boundingBox.maxX) - minX + 2;
		int sizeY = Mth.ceil(boundingBox.maxY) - minY + 2;
		int sizeZ = Mth.ceil(boundingBox.maxZ) - minZ + 2;

		embedding.collectLight(level, minX, minY, minZ, sizeX, sizeY, sizeZ);
	}

	@Override
	public void collectLightSections(LongConsumer consumer) {
		var boundingBox = entity.getBoundingBox();

		int minX = Mth.floor(boundingBox.minX) - 1;
		int minY = Mth.floor(boundingBox.minY) - 1;
		int minZ = Mth.floor(boundingBox.minZ) - 1;
		int sizeXChunks = SectionPos.blockToSectionCoord(Mth.ceil(boundingBox.maxX) - minX + 2) + 1;
		int sizeYChunks = SectionPos.blockToSectionCoord(Mth.ceil(boundingBox.maxY) - minY + 2) + 1;
		int sizeZChunks = SectionPos.blockToSectionCoord(Mth.ceil(boundingBox.maxZ) - minZ + 2) + 1;

		var base = SectionPos.asLong(SectionPos.blockToSectionCoord(minX), SectionPos.blockToSectionCoord(minY), SectionPos.blockToSectionCoord(minZ));

		for (int x = 0; x < sizeXChunks; x++) {
			for (int y = 0; y < sizeYChunks; y++) {
				for (int z = 0; z < sizeZChunks; z++) {
					consumer.accept(SectionPos.offset(base, x, y, z));
				}
			}
		}
	}

	@Override
	public void initLightSectionNotifier(Notifier notifier) {
		this.notifier = notifier;
	}

	@Override
	protected void _delete() {
		children.forEach(BlockEntityVisual::delete);

		actors.forEach(ActorVisual::delete);

		if (model != null) {
			model.delete();
		}

		if (structure != null) {
			structure.delete();
		}
	}
}
