package com.simibubi.create.api.connectivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.utility.Iterate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;

public class ConnectivityHandler {

	public static <T extends BlockEntity & IMultiBlockEntityContainer> void formMulti(T be) {
		SearchCache<T> cache = new SearchCache<>();
		List<T> frontier = new ArrayList<>();
		frontier.add(be);
		formMulti(be.getType(), be.getLevel(), cache, frontier);
	}

	private static <T extends BlockEntity & IMultiBlockEntityContainer> void formMulti(BlockEntityType<?> type,
																					   BlockGetter level, SearchCache<T> cache, List<T> frontier) {
		PriorityQueue<Pair<Integer, T>> creationQueue = makeCreationQueue();
		Set<BlockPos> visited = new HashSet<>();
		Direction.Axis mainAxis = frontier.get(0).getMainConnectionAxis();

		int minX = (mainAxis == Direction.Axis.Y ? Integer.MAX_VALUE : Integer.MIN_VALUE);
		int minY = (mainAxis != Direction.Axis.Y ? Integer.MAX_VALUE : Integer.MIN_VALUE);
		int minZ = (mainAxis == Direction.Axis.Y ? Integer.MAX_VALUE : Integer.MIN_VALUE);

		for (T be : frontier) {
			BlockPos pos = be.getBlockPos();
			minX = Math.min(pos.getX(), minX);
			minY = Math.min(pos.getY(), minY);
			minZ = Math.min(pos.getZ(), minZ);
		}
		if (mainAxis == Direction.Axis.Y)
			minX -= frontier.get(0).getMaxWidth();
		if (mainAxis != Direction.Axis.Y)
			minY -= frontier.get(0).getMaxWidth();
		if (mainAxis == Direction.Axis.Y)
			minZ -= frontier.get(0).getMaxWidth();

		while (!frontier.isEmpty()) {
			T part = frontier.remove(0);
			BlockPos partPos = part.getBlockPos();
			if (visited.contains(partPos))
				continue;

			visited.add(partPos);

			int amount = tryToFormNewMulti(part, cache, true);
			if (amount > 1) {
				creationQueue.add(Pair.of(amount, part));
			}

			for (Direction.Axis axis : Iterate.axes) {
				Direction dir = Direction.get(Direction.AxisDirection.NEGATIVE, axis);
				BlockPos next = partPos.relative(dir);

				if (next.getX() <= minX || next.getY() <= minY || next.getZ() <= minZ)
					continue;
				if (visited.contains(next))
					continue;
				T nextBe = partAt(type, level, next);
				if (nextBe == null)
					continue;
				if (nextBe.isRemoved())
					continue;
				frontier.add(nextBe);
			}
		}
		visited.clear();

		while (!creationQueue.isEmpty()) {
			Pair<Integer, T> next = creationQueue.poll();
			T toCreate = next.getValue();
			if (visited.contains(toCreate.getBlockPos()))
				continue;

			visited.add(toCreate.getBlockPos());
			tryToFormNewMulti(toCreate, cache, false);
		}
	}

	private static <T extends BlockEntity & IMultiBlockEntityContainer> int tryToFormNewMulti(T be, SearchCache<T> cache,
																							  boolean simulate) {
		int bestWidth = 1;
		int bestAmount = -1;
		if (!be.isController())
			return 0;

		int radius = be.getMaxWidth();
		for (int w = 1; w <= radius; w++) {
			int amount = tryToFormNewMultiOfWidth(be, w, cache, true);
			if (amount < bestAmount)
				continue;
			bestWidth = w;
			bestAmount = amount;
		}

		if (!simulate) {
			int beWidth = be.getWidth();
			if (beWidth == bestWidth && beWidth * beWidth * be.getHeight() == bestAmount)
				return bestAmount;

			splitMultiAndInvalidate(be, cache, false);
			if (be instanceof IMultiBlockEntityContainer.Fluid) {
				IMultiBlockEntityContainer.Fluid ifluid = (IMultiBlockEntityContainer.Fluid) be;
				if (ifluid.hasTank())
					ifluid.setTankSize(0, bestAmount);
			}

			tryToFormNewMultiOfWidth(be, bestWidth, cache, false);

			be.preventConnectivityUpdate();
			be.setWidth(bestWidth);
			be.setHeight(bestAmount / bestWidth / bestWidth);
			be.notifyMultiUpdated();
		}
		return bestAmount;
	}

	private static <T extends BlockEntity & IMultiBlockEntityContainer> int tryToFormNewMultiOfWidth(T be, int width,
																									 SearchCache<T> cache, boolean simulate) {
		int amount = 0;
		int height = 0;
		BlockEntityType<?> type = be.getType();
		Level level = be.getLevel();
		if (level == null)
			return 0;
		BlockPos origin = be.getBlockPos();

		IFluidTank beTank = null;
		FluidStack fluid = FluidStack.EMPTY;
		if (be instanceof IMultiBlockEntityContainer.Fluid) {
			IMultiBlockEntityContainer.Fluid ifluid = (IMultiBlockEntityContainer.Fluid) be;
			if (ifluid.hasTank()) {
				beTank = ifluid.getTank(0);
				fluid = beTank.getFluid();
			}
		}
		Direction.Axis axis = be.getMainConnectionAxis();

		Search: for (int yOffset = 0; yOffset < be.getMaxLength(axis, width); yOffset++) {
			for (int xOffset = 0; xOffset < width; xOffset++) {
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos;
					switch (axis) {
						case X:
							pos = origin.offset(yOffset, xOffset, zOffset);
							break;
						case Y:
							pos = origin.offset(xOffset, yOffset, zOffset);
							break;
						case Z:
							pos = origin.offset(xOffset, zOffset, yOffset);
							break;
						default:
							throw new IllegalStateException("Unexpected value: " + axis);
					}
					Optional<T> part = cache.getOrCache(type, level, pos);
					if (part.isEmpty())
						break Search;

					T controller = part.get();
					int otherWidth = controller.getWidth();
					if (otherWidth > width)
						break Search;
					if (otherWidth == width && controller.getHeight() == be.getMaxLength(axis, width))
						break Search;

					Direction.Axis conAxis = controller.getMainConnectionAxis();
					if (axis != conAxis)
						break Search;

					BlockPos conPos = controller.getBlockPos();
					if (!conPos.equals(origin)) {
						if (axis == Direction.Axis.Y) {
							if (conPos.getX() < origin.getX())
								break Search;
							if (conPos.getZ() < origin.getZ())
								break Search;
							if (conPos.getX() + otherWidth > origin.getX() + width)
								break Search;
							if (conPos.getZ() + otherWidth > origin.getZ() + width)
								break Search;
						} else {
							if (axis == Direction.Axis.Z && conPos.getX() < origin.getX())
								break Search;
							if (conPos.getY() < origin.getY())
								break Search;
							if (axis == Direction.Axis.X && conPos.getZ() < origin.getZ())
								break Search;
							if (axis == Direction.Axis.Z && conPos.getX() + otherWidth > origin.getX() + width)
								break Search;
							if (conPos.getY() + otherWidth > origin.getY() + width)
								break Search;
							if (axis == Direction.Axis.X && conPos.getZ() + otherWidth > origin.getZ() + width)
								break Search;
						}
					}
					if (controller instanceof IMultiBlockEntityContainer.Fluid) {
						IMultiBlockEntityContainer.Fluid ifluidCon = (IMultiBlockEntityContainer.Fluid) controller;
						if (ifluidCon.hasTank()) {
							FluidStack otherFluid = ifluidCon.getFluid(0);
							if (!fluid.isEmpty() && !otherFluid.isEmpty() && !fluid.isFluidEqual(otherFluid))
								break Search;
						}
					}
				}
			}
			amount += width * width;
			height++;
		}

		if (simulate)
			return amount;

		Object extraData = be.getExtraData();

		for (int yOffset = 0; yOffset < height; yOffset++) {
			for (int xOffset = 0; xOffset < width; xOffset++) {
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos;
					switch (axis) {
						case X:
							pos = origin.offset(yOffset, xOffset, zOffset);
							break;
						case Y:
							pos = origin.offset(xOffset, yOffset, zOffset);
							break;
						case Z:
							pos = origin.offset(xOffset, zOffset, yOffset);
							break;
						default:
							throw new IllegalStateException("Unexpected value: " + axis);
					}
					Optional<T> part = cache.getOrCache(type, level, pos);
					if (part.isEmpty())
						continue;

					T controller = part.get();
					controller.setWidth(width);
					controller.setHeight(height);

					if (controller instanceof IMultiBlockEntityContainer.Fluid) {
						IMultiBlockEntityContainer.Fluid ifluid = (IMultiBlockEntityContainer.Fluid) controller;
						if (ifluid.hasTank()) {
							IFluidTank tank = ifluid.getTank(0);
							if (beTank != null && !fluid.isEmpty()) {
								FluidStack filled = tank.fill(new FluidStack(fluid.getFluid(), tank.getCapacity()), IFluidHandler.FluidAction.EXECUTE);
								if (filled.getAmount() > 0)
									tank.setFluid(new FluidStack(fluid.getFluid(), filled.getAmount()));
							}
						}
					}
					controller.setExtraData(extraData);
				}
			}
		}
		return amount;
	}

	private static <T extends BlockEntity & IMultiBlockEntityContainer> void splitMultiAndInvalidate(T be, SearchCache<T> cache, boolean simulate) {
		BlockPos origin = be.getBlockPos();
		Direction.Axis axis = be.getMainConnectionAxis();
		int width = be.getWidth();
		int height = be.getHeight();

		if (simulate)
			return;

		for (int yOffset = 0; yOffset < height; yOffset++) {
			for (int xOffset = 0; xOffset < width; xOffset++) {
				for (int zOffset = 0; zOffset < width; zOffset++) {
					BlockPos pos;
					switch (axis) {
						case X:
							pos = origin.offset(yOffset, xOffset, zOffset);
							break;
						case Y:
							pos = origin.offset(xOffset, yOffset, zOffset);
							break;
						case Z:
							pos = origin.offset(xOffset, zOffset, yOffset);
							break;
						default:
							throw new IllegalStateException("Unexpected value: " + axis);
					}
					Optional<T> part = cache.getOrCache(be.getType(), be.getLevel(), pos);
					if (part.isEmpty())
						continue;

					T controller = part.get();
					if (controller != be) {
						controller.setWidth(0);
						controller.setHeight(0);
					}
				}
			}
		}
		be.setWidth(0);
		be.setHeight(0);
		be.invalidate();
	}

	private static <T extends BlockEntity & IMultiBlockEntityContainer> PriorityQueue<Pair<Integer, T>> makeCreationQueue() {
		return new PriorityQueue<>((a, b) -> Integer.compare(b.getKey(), a.getKey()));
	}

	@Nullable
	private static <T extends BlockEntity & IMultiBlockEntityContainer> T partAt(BlockEntityType<?> type, BlockGetter level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (type != be.getType())
			return null;
		return (T) be;
	}

	private static class SearchCache<T extends BlockEntity & IMultiBlockEntityContainer> {
		private final Map<BlockPos, Optional<T>> cache = new HashMap<>();

		public Optional<T> getOrCache(BlockEntityType<?> type, BlockGetter level, BlockPos pos) {
			return cache.computeIfAbsent(pos, p -> Optional.ofNullable(partAt(type, level, p)));
		}
	}
}
