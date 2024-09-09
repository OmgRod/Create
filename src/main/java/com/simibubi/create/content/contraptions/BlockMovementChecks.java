package com.simibubi.create.content.contraptions;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllTags.AllBlockTags;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.contraptions.actors.AttachedActorBlock;
import com.simibubi.create.content.contraptions.actors.harvester.HarvesterBlock;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlock;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlock;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlock;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.SailBlock;
import com.simibubi.create.content.contraptions.chassis.AbstractChassisBlock;
import com.simibubi.create.content.contraptions.chassis.StickerBlock;
import com.simibubi.create.content.contraptions.mounted.CartAssemblerBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlock.PistonState;
import com.simibubi.create.content.contraptions.pulley.PulleyBlock;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleExtenderBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.kinetics.crank.HandCrankBlock;
import com.simibubi.create.content.kinetics.fan.NozzleBlock;
import com.simibubi.create.content.logistics.vault.ItemVaultBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.station.StationBlock;
import com.simibubi.create.content.trains.track.ITrackBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WoolCarpetBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.PushReaction;

public class BlockMovementChecks {

	private static final List<MovementNecessaryCheck> MOVEMENT_NECESSARY_CHECKS = new ArrayList<>();
	private static final List<MovementAllowedCheck> MOVEMENT_ALLOWED_CHECKS = new ArrayList<>();
	private static final List<BrittleCheck> BRITTLE_CHECKS = new ArrayList<>();
	private static final List<AttachedCheck> ATTACHED_CHECKS = new ArrayList<>();
	private static final List<NotSupportiveCheck> NOT_SUPPORTIVE_CHECKS = new ArrayList<>();

	// Registration
	// Add new checks to the front instead of the end

	public static void registerMovementNecessaryCheck(MovementNecessaryCheck check) {
		MOVEMENT_NECESSARY_CHECKS.add(0, check);
	}

	public static void registerMovementAllowedCheck(MovementAllowedCheck check) {
		MOVEMENT_ALLOWED_CHECKS.add(0, check);
	}

	public static void registerBrittleCheck(BrittleCheck check) {
		BRITTLE_CHECKS.add(0, check);
	}

	public static void registerAttachedCheck(AttachedCheck check) {
		ATTACHED_CHECKS.add(0, check);
	}

	public static void registerNotSupportiveCheck(NotSupportiveCheck check) {
		NOT_SUPPORTIVE_CHECKS.add(0, check);
	}

	public static void registerAllChecks(AllChecks checks) {
		registerMovementNecessaryCheck(checks);
		registerMovementAllowedCheck(checks);
		registerBrittleCheck(checks);
		registerAttachedCheck(checks);
		registerNotSupportiveCheck(checks);
	}

	// Actual check methods

	public static boolean isMovementNecessary(BlockState state, Level world, BlockPos pos) {
		for (MovementNecessaryCheck check : MOVEMENT_NECESSARY_CHECKS) {
			CheckResult result = check.isMovementNecessary(state, world, pos);
			if (result != CheckResult.PASS) {
				return result.toBoolean();
			}
		}
		return isMovementNecessaryFallback(state, world, pos);
	}

	public static boolean isMovementAllowed(BlockState state, Level world, BlockPos pos) {
		for (MovementAllowedCheck check : MOVEMENT_ALLOWED_CHECKS) {
			CheckResult result = check.isMovementAllowed(state, world, pos);
			if (result != CheckResult.PASS) {
				return result.toBoolean();
			}
		}
		return isMovementAllowedFallback(state, world, pos);
	}

	/**
	 * Brittle blocks will be collected first, as they may break when other blocks
	 * are removed before them
	 */
	public static boolean isBrittle(BlockState state) {
		for (BrittleCheck check : BRITTLE_CHECKS) {
			CheckResult result = check.isBrittle(state);
			if (result != CheckResult.PASS) {
				return result.toBoolean();
			}
		}
		return isBrittleFallback(state);
	}

	/**
	 * Attached blocks will move if blocks they are attached to are moved
	 */
	public static boolean isBlockAttachedTowards(BlockState state, Level world, BlockPos pos, Direction direction) {
		for (AttachedCheck check : ATTACHED_CHECKS) {
			CheckResult result = check.isBlockAttachedTowards(state, world, pos, direction);
			if (result != CheckResult.PASS) {
				return result.toBoolean();
			}
		}
		return isBlockAttachedTowardsFallback(state, world, pos, direction);
	}

	/**
	 * Non-Supportive blocks will not continue a chain of blocks picked up by e.g. a
	 * piston
	 */
	public static boolean isNotSupportive(BlockState state, Direction facing) {
		for (NotSupportiveCheck check : NOT_SUPPORTIVE_CHECKS) {
			CheckResult result = check.isNotSupportive(state, facing);
			if (result != CheckResult.PASS) {
				return result.toBoolean();
			}
		}
		return isNotSupportiveFallback(state, facing);
	}

	// Fallback checks

	private static boolean isMovementNecessaryFallback(BlockState state, Level world, BlockPos pos) {
		if (isBrittle(state))
			return true;
		if (AllBlockTags.MOVABLE_EMPTY_COLLIDER.matches(state))
			return true;
		if (state.getCollisionShape(world, pos).isEmpty())
			return false;
		if (state.canBeReplaced())
			return false;
		return true;
	}

	private static boolean isMovementAllowedFallback(BlockState state, Level world, BlockPos pos) {
		Block block = state.getBlock();
		if (block instanceof AbstractChassisBlock)
			return true;
		if (state.getDestroySpeed(world, pos) == -1)
			return false;
		if (AllBlockTags.RELOCATION_NOT_SUPPORTED.matches(state))
			return false;
		if (AllBlockTags.NON_MOVABLE.matches(state))
			return false;
		if (ContraptionMovementSetting.get(state.getBlock()) == ContraptionMovementSetting.UNMOVABLE)
			return false;

		// Move controllers only when they aren't moving
		if (block instanceof MechanicalPistonBlock && state.getValue(MechanicalPistonBlock.STATE) != PistonState.MOVING)
			return true;
		if (block instanceof MechanicalBearingBlock) {
			BlockEntity be = world.getBlockEntity(pos);
			if (be instanceof MechanicalBearingBlockEntity)
				return !((MechanicalBearingBlockEntity) be).isRunning();
		}
		if (block instanceof ClockworkBearingBlock) {
			BlockEntity be = world.getBlockEntity(pos);
			if (be instanceof ClockworkBearingBlockEntity)
				return !((ClockworkBearingBlockEntity

						) be).isRunning();
		}
		if (block instanceof MovingContraptionBlock) {
			return false;
		}

		return true;
	}

	private static boolean isBrittleFallback(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof BasePressurePlateBlock)
			return true;
		if (block instanceof MechanicalPistonBlock)
			return true;
		if (block instanceof AbstractChassisBlock)
			return true;
		if (block instanceof AbstractBogeyBlock)
			return true;
		if (block instanceof AbstractChassisBlock)
			return true;
		if (block instanceof BaseRailBlock)
			return true;
		if (block instanceof RedStoneWireBlock)
			return true;
		if (block instanceof DiodeBlock)
			return true;
		if (block instanceof LadderBlock)
			return true;
		if (block instanceof SignBlock)
			return true;
		if (block instanceof TorchBlock)
			return true;
		if (block instanceof WallTorchBlock)
			return true;
		if (block instanceof WallSignBlock)
			return true;
		if (block instanceof StandingSignBlock)
			return true;
		if (block instanceof WoolCarpetBlock)
			return true;
		if (block instanceof BedBlock)
			return true;
		if (block instanceof DoorBlock)
			return true;
		if (block instanceof BellBlock)
			return true;
		if (block instanceof FlowerPotBlock)
			return true;
		if (block instanceof GrindingWheelBlock)
			return true;
		if (block instanceof MechanicalBearingBlock)
			return true;
		if (block instanceof AbstractBogeyBlock)
			return true;
		if (block instanceof HandCrankBlock)
			return true;
		if (block instanceof WhistleBlock)
			return true;
		if (block instanceof WhistleExtenderBlock)
			return true;
		if (block instanceof FluidTankBlock)
			return true;
		if (block instanceof SlidingDoorBlock)
			return true;
		if (block instanceof RedstoneLinkBlock)
			return true;

		return false;
	}

	private static boolean isBlockAttachedTowardsFallback(BlockState state, Level world, BlockPos pos, Direction direction) {
		Block block = state.getBlock();

		if (block instanceof LadderBlock) {
			return state.getValue(LadderBlock.FACING) == direction.getOpposite();
		}
		if (block instanceof WallTorchBlock) {
			return state.getValue(WallTorchBlock.FACING) == direction.getOpposite();
		}
		if (block instanceof WallSignBlock) {
			return state.getValue(WallSignBlock.FACING) == direction.getOpposite();
		}
		if (block instanceof StandingSignBlock) {
			return direction == Direction.DOWN;
		}
		if (block instanceof BasePressurePlateBlock) {
			return direction == Direction.DOWN;
		}
		if (block instanceof FaceAttachedHorizontalDirectionalBlock && !(block instanceof GrindstoneBlock)) {
			return true;
		}
		if (block instanceof CartAssemblerBlock) {
			return false;
		}
		if (block instanceof BaseRailBlock) {
			return true;
		}
		if (block instanceof DiodeBlock) {
			return true;
		}
		if (block instanceof RedStoneWireBlock) {
			return true;
		}
		if (block instanceof WoolCarpetBlock) {
			return true;
		}
		if (block instanceof WhistleBlock) {
			return true;
		}
		if (block instanceof WhistleExtenderBlock) {
			return true;
		}
		if (block instanceof AbstractBogeyBlock) {
			AbstractBogeyBlock<?> bogey = (AbstractBogeyBlock<?>) block;
			return bogey.getStickySurfaces(world, pos, state).contains(direction);
		}

		return false;
	}

	private static boolean isNotSupportiveFallback(BlockState state, Direction facing) {
		Block block = state.getBlock();

		if (block instanceof AbstractChassisBlock) {
			return false;
		}
		if (block instanceof MovingContraptionBlock) {
			return false;
		}
		if (block instanceof MechanicalPistonBlock) {
			return false;
		}
		if (block instanceof MechanicalBearingBlock) {
			return false;
		}
		if (block instanceof ClockworkBearingBlock) {
			return false;
		}
		if (block instanceof PulleyBlock) {
			return false;
		}
		if (block instanceof RedstoneLinkBlock) {
			return false;
		}
		if (block instanceof AbstractBogeyBlock) {
			return false;
		}

		return true;
	}
}
