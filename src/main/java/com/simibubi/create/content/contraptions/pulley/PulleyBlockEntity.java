package com.simibubi.create.content.contraptions.pulley;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.BlockMovementChecks;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchObservable;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.NBTHelper;
import com.simibubi.create.infrastructure.config.AllConfigs;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PulleyBlockEntity extends LinearActuatorBlockEntity implements ThresholdSwitchObservable {

    protected int initialOffset;
    private float prevAnimatedOffset;

    protected BlockPos mirrorParent;
    protected List<BlockPos> mirrorChildren;
    public WeakReference<AbstractContraptionEntity> sharedMirrorContraption;

    public PulleyBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected AABB createRenderBoundingBox() {
        double expandY = -offset;
        if (sharedMirrorContraption != null) {
            AbstractContraptionEntity ace = sharedMirrorContraption.get();
            if (ace != null)
                expandY = ace.getY() - worldPosition.getY();
        }
        return super.createRenderBoundingBox().expandTowards(0, expandY, 0);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        registerAwardables(behaviours, AllAdvancements.PULLEY_MAXED);
    }

    @Override
    public void tick() {
        float prevOffset = offset;
        super.tick();

        if (level.isClientSide() && mirrorParent != null) {
            if (sharedMirrorContraption == null || sharedMirrorContraption.get() == null
                    || !sharedMirrorContraption.get().isAlive()) {
                sharedMirrorContraption = null;
                // Adjusted for Java 8 compatibility
                if (level.getBlockEntity(mirrorParent) instanceof PulleyBlockEntity) {
                    PulleyBlockEntity pte = (PulleyBlockEntity) level.getBlockEntity(mirrorParent);
                    if (pte.movedContraption != null) {
                        sharedMirrorContraption = new WeakReference<>(pte.movedContraption);
                    }
                }
            }
        }

        if (isVirtual())
            prevAnimatedOffset = offset;
        invalidateRenderBoundingBox();

        if (prevOffset < 200 && offset >= 200)
            award(AllAdvancements.PULLEY_MAXED);
    }

    @Override
    protected boolean isPassive() {
        return mirrorParent != null;
    }

    @Nullable
    public AbstractContraptionEntity getAttachedContraption() {
        return mirrorParent != null && sharedMirrorContraption != null ? sharedMirrorContraption.get()
                : movedContraption;
    }

    @Override
    protected void assemble() throws AssemblyException {
        if (!(level.getBlockState(worldPosition).getBlock() instanceof PulleyBlock))
            return;
        if (speed == 0 && mirrorParent == null)
            return;
        int maxLength = AllConfigs.server().kinetics.maxRopeLength.get();
        int i = 1;
        while (i <= maxLength) {
            BlockPos ropePos = worldPosition.below(i);
            BlockState ropeState = level.getBlockState(ropePos);
            if (!AllBlocks.ROPE.has(ropeState) && !AllBlocks.PULLEY_MAGNET.has(ropeState)) {
                break;
            }
            ++i;
        }
        offset = i - 1;
        if (offset >= getExtensionRange() && getSpeed() > 0)
            return;
        if (offset <= 0 && getSpeed() < 0)
            return;

        // Collect Construct
        if (!level.isClientSide && mirrorParent == null) {
            needsContraption = false;
            BlockPos anchor = worldPosition.below(Mth.floor(offset + 1));
            initialOffset = Mth.floor(offset);
            PulleyContraption contraption = new PulleyContraption(initialOffset);
            boolean canAssembleStructure = contraption.assemble(level, anchor);

            if (canAssembleStructure) {
                Direction movementDirection = getSpeed() > 0 ? Direction.DOWN : Direction.UP;
                if (ContraptionCollider.isCollidingWithWorld(level, contraption, anchor.relative(movementDirection),
                        movementDirection))
                    canAssembleStructure = false;
            }

            if (!canAssembleStructure && getSpeed() > 0)
                return;

            removeRopes();

            if (!contraption.getBlocks().isEmpty()) {
                contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
                movedContraption = ControlledContraptionEntity.create(level, this, contraption);
                movedContraption.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
                level.addFreshEntity(movedContraption);
                forceMove = true;
                needsContraption = true;

                if (contraption.containsBlockBreakers())
                    award(AllAdvancements.CONTRAPTION_ACTORS);

                for (BlockPos pos : contraption.createColliders(level, Direction.UP)) {
                    if (pos.getY() != 0)
                        continue;
                    pos = pos.offset(anchor);
                    // Adjusted for Java 8 compatibility
                    if (level.getBlockEntity(new BlockPos(pos.getX(), worldPosition.getY(), pos.getZ())) instanceof PulleyBlockEntity) {
                        PulleyBlockEntity pbe = (PulleyBlockEntity) level.getBlockEntity(new BlockPos(pos.getX(), worldPosition.getY(), pos.getZ()));
                        pbe.startMirroringOther(worldPosition);
                    }
                }
            }
        }

        if (mirrorParent != null)
            removeRopes();

        clientOffsetDiff = 0;
        running = true;
        sendData();
    }

    private void removeRopes() {
        for (int i = ((int) offset); i > 0; i--) {
            BlockPos offset = worldPosition.below(i);
            BlockState oldState = level.getBlockState(offset);
            level.setBlock(offset, oldState.getFluidState().createLegacyBlock(), 66);
        }
    }

    @Override
    public void disassemble() {
        if (!running && movedContraption == null && mirrorParent == null)
            return;
        offset = getGridOffset(offset);
        if (movedContraption != null)
            resetContraptionToOffset();

        if (!level.isClientSide) {
            if (shouldCreateRopes()) {
                if (offset > 0) {
                    BlockPos magnetPos = worldPosition.below((int) offset);
                    FluidState ifluidstate = level.getFluidState(magnetPos);
                    if (level.getBlockState(magnetPos).getDestroySpeed(level, magnetPos) != -1) {

                        level.destroyBlock(magnetPos, level.getBlockState(magnetPos).getCollisionShape(level, magnetPos).isEmpty());
                        level.setBlock(magnetPos, AllBlocks.PULLEY_MAGNET.getDefaultState()
                                .setValue(BlockStateProperties.WATERLOGGED, Boolean.valueOf(ifluidstate.getType() == Fluids.WATER)),
                                66);
                    }
                }

                boolean[] waterlog = new boolean[(int) offset];

                for (boolean destroyPass : Iterate.trueAndFalse) {
                    for (int i = 1; i <= ((int) offset) - 1; i++) {
                        BlockPos ropePos = worldPosition.below(i);
                        if (level.getBlockState(ropePos).getDestroySpeed(level, ropePos) == -1)
                            continue;

                        if (destroyPass) {
                            FluidState ifluidstate = level.getFluidState(ropePos);
                            waterlog[i] = ifluidstate.getType() == Fluids.WATER;
                            level.destroyBlock(ropePos, level.getBlockState(ropePos).getCollisionShape(level, ropePos).isEmpty());
                            continue;
                        }

                        level.setBlock(worldPosition.below(i), AllBlocks.ROPE.getDefaultState()
                                .setValue(BlockStateProperties.WATERLOGGED, waterlog[i]), 66);
                    }
                }
            }

            if (movedContraption != null && mirrorParent == null)
                movedContraption

.removeBlocksFromWorld(level, BlockPos.ZERO);

            removeRopes();

            if (mirrorParent != null) {
                notifyMirrorsOfDisassembly();
                mirrorParent = null;
            }
        }

        super.disassemble();
    }

    @Override
    public void notifyMirrorsOfDisassembly() {
        if (mirrorChildren == null)
            return;
        for (BlockPos blockPos : mirrorChildren) {
            // Adjusted for Java 8 compatibility
            if (level.getBlockEntity(blockPos) instanceof PulleyBlockEntity) {
                PulleyBlockEntity pbe = (PulleyBlockEntity) level.getBlockEntity(blockPos);
                pbe.offset = offset;
                pbe.disassemble();
                pbe.mirrorParent = null;
                pbe.notifyUpdate();
            }
        }
        mirrorChildren.clear();
        notifyUpdate();
    }

    @Override
    public void writeSafe(CompoundTag tag) {
        super.writeSafe(tag);
        tag.putInt("Offset", (int) offset);
        if (mirrorParent != null)
            tag.put("MirrorParent", NbtUtils.writeBlockPos(mirrorParent));
        if (mirrorChildren != null && !mirrorChildren.isEmpty()) {
            ListTag childrenList = new ListTag();
            for (BlockPos pos : mirrorChildren)
                childrenList.add(NbtUtils.writeBlockPos(pos));
            tag.put("MirrorChildren", childrenList);
        }
        if (sharedMirrorContraption != null && sharedMirrorContraption.get() != null)
            tag.put("Contraption", NbtUtils.writeBlockPos(sharedMirrorContraption.get().getBlockPos()));
    }

    @Override
    public void read(CompoundTag tag) {
        super.read(tag);
        offset = tag.getInt("Offset");
        if (tag.contains("MirrorParent", Tag.TAG_COMPOUND))
            mirrorParent = NbtUtils.readBlockPos(tag.getCompound("MirrorParent"));
        if (tag.contains("MirrorChildren", Tag.TAG_LIST)) {
            ListTag childrenList = tag.getList("MirrorChildren", Tag.TAG_COMPOUND);
            mirrorChildren = new ArrayList<>();
            for (int i = 0; i < childrenList.size(); i++)
                mirrorChildren.add(NbtUtils.readBlockPos(childrenList.getCompound(i)));
        }
        if (tag.contains("Contraption", Tag.TAG_COMPOUND)) {
            BlockPos contraptionPos = NbtUtils.readBlockPos(tag.getCompound("Contraption"));
            sharedMirrorContraption = new WeakReference<>((AbstractContraptionEntity) level.getBlockEntity(contraptionPos));
        }
    }

    @Override
    public void startMirroringOther(BlockPos other) {
        mirrorParent = other;
        mirrorChildren = new ArrayList<>();
        sendData();
    }

    @Override
    public void sendData() {
        super.sendData();
        if (level.isClientSide())
            return;
        BlockPos pos = worldPosition;
        if (mirrorParent != null)
            pos = mirrorParent;
        if (mirrorChildren != null)
            for (BlockPos child : mirrorChildren)
                sendDataForChild(child);
    }

    @Override
    public void sendDataForChild(BlockPos child) {
        // Implement sending data for child
    }

    @Override
    public void resetContraptionToOffset() {
        if (movedContraption != null)
            movedContraption.setContraptionOffset(initialOffset);
    }

    @Override
    public void award(AllAdvancements advancement) {
        super.award(advancement);
    }

    @Override
    public void setClientOffset(float offset) {
        this.clientOffsetDiff = offset;
        sendData();
    }

    @Override
    public float getClientOffset() {
        return clientOffsetDiff;
    }

    @Override
    protected boolean shouldCreateRopes() {
        return true;
    }

    @Override
    protected boolean isVirtual() {
        return false;
    }

    @Override
    public boolean canAnimate() {
        return true;
    }
}