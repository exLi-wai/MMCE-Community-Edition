package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.block.MachineCasingBlock;
import com.lw.modularmachine.block.MachinePortBlock;
import com.lw.modularmachine.blockentity.MachineCasingBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.jetbrains.annotations.ApiStatus;

/**
 * Whole-pattern creative validation, atomic write batch and reverse rollback.
 */
@ApiStatus.Internal
final class AssemblyCreativePlacement {
  private static final int BULK_FLAGS = Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
  private AssemblyCreativePlacement() {
  }

  private record CreativePlacement(BlockSnapshot before, BlockState after, List<ItemStack> displacedItems) {
    BlockPos position() {
      return before.getPos();
    }
  }

  /** {@code matched} counts parts already correct plus parts written now. */
  record Result(int matched, int placed) {
  }

  /** Internal failure retaining its translatable reason for the client. */
  static final class Failure extends IllegalStateException {
    final Component reason;

    private Failure(String translationKey, BlockPos position) {
      super(translationKey + ": " + position.toShortString());
      reason = Component.translatable(translationKey, position.toShortString());
    }
  }

  /**
   * validate the whole structure, then commit it as one batch. Rebuilding over an
   * existing structure is a no-op rather than a failure, and only the controller
   * position needs interaction range (checked by the caller).
   */
  static Result build(ServerPlayer player, AssemblyPattern pattern) {
    var level = player.serverLevel();
    ServerThreadGuard.require(level, "Creative assembly");
    if (AssemblyPlacement.active()
        || level.captureBlockSnapshots
        || level.restoringBlockSnapshots
        || !level.capturedBlockSnapshots.isEmpty()) {
      throw new Failure("message.mmcece.assembly.creative.position_unavailable", pattern.origin());
    }
    var placements = new ArrayList<CreativePlacement>();
    int matched = 0;
    for (var part : pattern.parts()) {
      var position = part.position();
      if (level.isOutsideBuildHeight(position)
          || !level.hasChunkAt(position)
          || !level.getWorldBorder().isWithinBounds(position)
          || !level.mayInteract(player, position)
          || part.states().isEmpty()) {
        throw new Failure("message.mmcece.assembly.creative.position_unavailable", position);
      }
      var before = level.getBlockState(position);
      var after = part.states().get(0);
      if (before.equals(after)) {
        matched++;
        continue;
      }
      // Removing either the original head or a newly placed head during rollback
      // destroys its matching base, which may lie outside this batch's snapshots.
      if (before.getBlock() instanceof PistonHeadBlock || after.getBlock() instanceof PistonHeadBlock) {
        throw new Failure("message.mmcece.assembly.creative.unsafe_replacement", position);
      }
      placements.add(
          new CreativePlacement(
              snapshot(level, position, before),
              after,
              displacedItems(level, position, before, after)));
    }

    if (placements.isEmpty()) {
      return new Result(matched, 0);
    }

    int placed = 0;
    int attempted = 0;
    boolean restoring = level.restoringBlockSnapshots;
    AssemblyPlacement.begin();
    try {
      // Native item buses clear their contents on removal. Suppress their drops until
      // the entire batch has committed, so a rollback cannot duplicate those items.
      level.restoringBlockSnapshots = true;
      try {
        for (var placement : placements) {
          attempted++;
          boolean written = level.setBlock(placement.position(), placement.after(), BULK_FLAGS);
          if (!written && !level.getBlockState(placement.position()).equals(placement.after())) {
            throw new Failure("message.mmcece.assembly.creative.write_failed", placement.position());
          }
          placed++;
          if (!level.getBlockState(placement.position()).equals(placement.after())) {
            throw new Failure("message.mmcece.assembly.creative.state_mismatch", placement.position());
          }
        }
      } catch (RuntimeException failure) {
        rollback(level, placements, attempted, failure);
        throw failure;
      }
      level.restoringBlockSnapshots = restoring;
      for (var placement : placements) {
        for (var stack : placement.displacedItems()) {
          Block.popResource(level, placement.position(), stack);
        }
      }
      for (var placement : placements) {
        level.sendBlockUpdated(
            placement.position(),
            placement.before().getState(),
            placement.after(),
            Block.UPDATE_CLIENTS);
        level.updateNeighborsAt(placement.position(), placement.after().getBlock());
      }
    } finally {
      level.restoringBlockSnapshots = restoring;
      AssemblyPlacement.end();
    }
    return new Result(matched + placed, placed);
  }

  private static BlockSnapshot snapshot(ServerLevel level, BlockPos position, BlockState state) {
    var entity = level.getBlockEntity(position);
    if (state.hasBlockEntity() || entity != null) {
      boolean nativePort = state.getBlock().getClass() == MachinePortBlock.class
          && entity != null
          && entity.getClass() == MachinePortBlockEntity.class
          && !((MachinePortBlockEntity) entity).resourceTransactionInProgress();
      boolean nativeCasing = state.getBlock().getClass() == MachineCasingBlock.class
          && entity instanceof MachineCasingBlockEntity;
      // Unknown removal hooks (including vanilla containers) can drop items or
      // change external state outside NeoForge's snapshot restoration protocol.
      if (!nativePort && !nativeCasing) {
        throw new Failure("message.mmcece.assembly.creative.unsafe_block_entity", position);
      }
    }

    return BlockSnapshot.create(level.dimension(), level, position, BULK_FLAGS);
  }

  private static void rollback(ServerLevel level, List<CreativePlacement> placements, int attempted, RuntimeException failure) {
    for (int index = attempted - 1; index >= 0; index--) {
      var snapshot = placements.get(index).before();
      try {
        var position = snapshot.getPos();
        var replaced = level.getBlockState(position);
        level.setBlock(position, snapshot.getState(), BULK_FLAGS);
        if (!level.getBlockState(position).equals(snapshot.getState())) {
          throw new IllegalStateException("Creative assembly rollback could not restore block at " + position);
        }
        // A failed write can already have restored the state while losing its BE.
        // BlockSnapshot.restore skips NBT when setBlock returns false; restore it
        // explicitly even when the original state is already present.
        if (snapshot.getTag() != null) {
          if (level.getBlockEntity(position) == null) {
            var restored = snapshot.recreateBlockEntity(level.registryAccess());
            if (restored != null) {
              level.setBlockEntity(restored);
            }
          }
          if (!snapshot.restoreBlockEntity(level, position)) {
            throw new IllegalStateException("Creative assembly rollback could not restore block entity at " + position);
          }
        }
        level.sendBlockUpdated(position, replaced, snapshot.getState(), Block.UPDATE_CLIENTS);
      } catch (RuntimeException restoreFailure) {
        if (restoreFailure != failure) {
          failure.addSuppressed(restoreFailure);
        }
      }
    }
  }

  private static List<ItemStack> displacedItems(ServerLevel level, BlockPos position, BlockState before, BlockState after) {
    if (before.is(after.getBlock()) || !(level.getBlockEntity(position) instanceof MachinePortBlockEntity port)) {
      return List.of();
    }
    var stacks = new ArrayList<ItemStack>();
    var inventory = port.inventory();
    for (int slot = 0; slot < inventory.getSlots(); slot++) {
      var stack = inventory.getStackInSlot(slot);
      if (!stack.isEmpty()) {
        stacks.add(stack.copy());
      }
    }

    return List.copyOf(stacks);
  }

}
