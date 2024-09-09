package com.simibubi.create.content.decoration.girder;

import static net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock.FACE;
import static net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.AllShapes;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.decoration.bracket.BracketBlock;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.decoration.placard.PlacardBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.logistics.chute.AbstractChuteBlock;
import com.simibubi.create.content.redstone.nixieTube.NixieTubeBlock;
import com.simibubi.create.content.trains.display.FlapDisplayBlock;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.placement.IPlacementHelper;
import com.simibubi.create.foundation.placement.PlacementHelpers;
import com.simibubi.create.foundation.utility.Iterate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GirderBlock extends Block implements SimpleWaterloggedBlock, IWrenchable {

    private static final int placementHelperId = PlacementHelpers.register(new GirderPlacementHelper());

    public static final BooleanProperty X = BooleanProperty.create("x");
    public static final BooleanProperty Z = BooleanProperty.create("z");
    public static final BooleanProperty TOP = BooleanProperty.create("top");
    public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");
    public static final EnumProperty<Axis> AXIS = BlockStateProperties.AXIS;

    public GirderBlock(Properties p_49795_) {
        super(p_49795_);
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false)
            .setValue(AXIS, Axis.Y)
            .setValue(TOP, false)
            .setValue(BOTTOM, false)
            .setValue(X, false)
            .setValue(Z, false));
    }

    @Override
    protected void createBlockStateDefinition(Builder<Block, BlockState> pBuilder) {
        super.createBlockStateDefinition(pBuilder.add(X, Z, TOP, BOTTOM, AXIS, WATERLOGGED));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getBlockSupportShape(BlockState pState, BlockGetter pReader, BlockPos pPos) {
        return Shapes.or(super.getBlockSupportShape(pState, pReader, pPos), AllShapes.EIGHT_VOXEL_POLE.get(Axis.Y));
    }

    @Override
    public InteractionResult use(BlockState pState, Level pLevel, BlockPos pPos, Player pPlayer, InteractionHand pHand,
        BlockHitResult pHit) {
        if (pPlayer == null)
            return InteractionResult.PASS;

        ItemStack itemInHand = pPlayer.getItemInHand(pHand);
        if (AllBlocks.SHAFT.isIn(itemInHand)) {
            KineticBlockEntity.switchToBlockState(pLevel, pPos, AllBlocks.METAL_GIRDER_ENCASED_SHAFT.getDefaultState()
                .setValue(WATERLOGGED, pState.getValue(WATERLOGGED))
                .setValue(TOP, pState.getValue(TOP))
                .setValue(BOTTOM, pState.getValue(BOTTOM))
                .setValue(GirderEncasedShaftBlock.HORIZONTAL_AXIS, pState.getValue(X) || pHit.getDirection()
                    .getAxis() == Axis.Z ? Axis.Z : Axis.X));

            pLevel.playSound(null, pPos, SoundEvents.NETHERITE_BLOCK_HIT, SoundSource.BLOCKS, 0.5f, 1.25f);
            if (!pLevel.isClientSide && !pPlayer.isCreative()) {
                itemInHand.shrink(1);
                if (itemInHand.isEmpty())
                    pPlayer.setItemInHand(pHand, ItemStack.EMPTY);
            }

            return InteractionResult.SUCCESS;
        }

        if (AllItems.WRENCH.isIn(itemInHand) && !pPlayer.isShiftKeyDown()) {
            if (GirderWrenchBehavior.handleClick(pLevel, pPos, pState, pHit))
                return InteractionResult.sidedSuccess(pLevel.isClientSide);
            return InteractionResult.FAIL;
        }

        IPlacementHelper helper = PlacementHelpers.get(placementHelperId);
        if (helper.matchesItem(itemInHand))
            return helper.getOffset(pPlayer, pLevel, pState, pPos, pHit)
                .placeInWorld(pLevel, (BlockItem) itemInHand.getItem(), pPlayer, pHand, pHit);

        return InteractionResult.PASS;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void tick(BlockState p_60462_, ServerLevel p_60463_, BlockPos p_60464_, RandomSource p_60465_) {
        Block.updateOrDestroy(p_60462_, Block.updateFromNeighbourShapes(p_60462_, p_60463_, p_60464_), p_60463_,
            p_60464_, 3);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState, LevelAccessor world,
        BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(WATERLOGGED))
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        Axis axis = direction.getAxis();

        if (direction.getAxis() != Axis.Y) {
            if (state.getValue(AXIS) != direction.getAxis()) {
                Property<Boolean> updateProperty =
                    axis == Axis.X ? X : axis == Axis.Z ? Z : direction == Direction.UP ? TOP : BOTTOM;
                if (!isConnected(world, pos, state, direction)
                    && !isConnected(world, pos, state, direction.getOpposite()))
                    state = state.setValue(updateProperty, false);
            }
        } else if (state.getValue(AXIS) != Axis.Y) {
            if (world.getBlockState(pos.above())
                .getBlockSupportShape(world, pos.above())
                .isEmpty())
                state = state.setValue(TOP, false);
            if (world.getBlockState(pos.below())
                .getBlockSupportShape(world, pos.below())
                .isEmpty())
                state = state.setValue(BOTTOM, false);
        }

        for (Direction d : Iterate.directionsInAxis(axis))
            state = updateState(world, pos, state, d);

        return state;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        FluidState ifluidstate = level.getFluidState(pos);
        BlockState state = super.getStateForPlacement(context);
        state = state.setValue(X, face.getAxis() == Axis.X);
        state = state.setValue(Z, face.getAxis() == Axis.Z);
        state = state.setValue(AXIS, face.getAxis());

        for (Direction d : Iterate.directionsInAxis(face.getAxis()))
            state = updateState(level, pos, state, d);

        return state.setValue(WATERLOGGED, ifluidstate.getType() == Fluids.WATER);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        switch (rotation) {
            case CLOCKWISE_90:
                return state.setValue(AXIS, state.getValue(AXIS) == Axis.X ? Axis.Z : Axis.X);
            case COUNTERCLOCKWISE_90:
                return state.setValue(AXIS, state.getValue(AXIS) == Axis.X ? Axis.Z : Axis.X);
            case CLOCKWISE_180:
                return state;
            default:
                return state;
        }
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        if (mirror == Mirror.FRONT_BACK || mirror == Mirror.LEFT_RIGHT) {
            if (state.getValue(AXIS) == Axis.Y)
                return state.setValue(AXIS, Axis.Y);
            return state.setValue(AXIS, state.getValue(AXIS) == Axis.X ? Axis.Z : Axis.X);
        }
        return state;
    }

    @Override
    public boolean isPathfindable(BlockState pState, BlockGetter pReader, BlockPos pPos, PathComputationType pType) {
        return pType == PathComputationType.WATER;
    }

    public static boolean isFacingBracket(BlockAndTintGetter level, BlockPos pos, Direction d) {
        BlockEntity blockEntity = level.getBlockEntity(pos.relative(d));
        if (!(blockEntity instanceof SmartBlockEntity)) {
            return false;
        }
        SmartBlockEntity sbe = (SmartBlockEntity) blockEntity;
        BracketedBlockEntityBehaviour behaviour = sbe.getBehaviour(BracketedBlockEntityBehaviour.TYPE);
        if (behaviour == null)
            return false;
        BlockState bracket = behaviour.getBracket();
        if (bracket == null || !bracket.hasProperty(BracketBlock.FACING))
            return false;
        return bracket.getValue(BracketBlock.FACING) == d;
    }

    private BlockState updateState(LevelAccessor world, BlockPos pos, BlockState state, Direction d) {
        if (!isConnected(world, pos, state, d)) {
            Property<Boolean> updateProperty =
                d.getAxis() == Axis.X ? X : d.getAxis() == Axis.Z ? Z : d == Direction.UP ? TOP : BOTTOM;
            return state.setValue(updateProperty, false);
        }
        return state;
    }

    private boolean isConnected(LevelAccessor world, BlockPos pos, BlockState state, Direction d) {
        BlockPos neighborPos = pos.relative(d);
        BlockState neighborState = world.getBlockState(neighborPos);
        BlockEntity blockEntity = world.getBlockEntity(neighborPos);
        return neighborState.is(this) || blockEntity instanceof KineticBlockEntity;
    }
}
