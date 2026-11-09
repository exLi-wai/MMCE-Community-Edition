package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import com.lw.modularmachine.util.minecraft.inventory.PlayerInventoryItems;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal backpack-only fluid placement. Live item handlers are never drained.
 */
@ApiStatus.Internal
final class AssemblyFluidPlacement {
  private static final int MAX_TANKS = 64;
  private static final int MAX_IDENTITIES = 64;

  private record Debit(int slot, ItemStack expected, int units, List<ItemStack> containers) {
  }
  private record Held(int slot, ItemStack original, List<ItemStack> containers) {
  }
  private record Plan(List<Debit> debits) {
  }

  private AssemblyFluidPlacement() {
  }

  static PlacementOutcome place(ServerPlayer player, AssemblyPattern.Part part, FluidStack preferred, java.util.Set<net.minecraft.world.item.Item> reservedBlocks, BooleanSupplier current) {
    var level = player.serverLevel();
    ServerThreadGuard.require(level, "Backpack fluid assembly");
    if (level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) {
      return PlacementOutcome.BUSY;
    }
    var fluids = PlacementMaterials.sourceFluids(part.states());
    if (fluids.isEmpty()) {
      return PlacementOutcome.MISSING;
    }
    var inventory = player.getInventory();
    var sources = new ArrayList<ItemStack>();
    var identities = new ArrayList<FluidStack>();
    boolean unsupported = false;
    for (var original : inventory.items) {
      boolean reserved = original.getComponentsPatch().isEmpty()
          && reservedBlocks.contains(original.getItem())
          && PlacementMaterials.automaticItem(original.getItem());
      sources.add(reserved ? ItemStack.EMPTY : original.copy());
      if (reserved) {
        continue;
      }
      if (original.isEmpty()) {
        continue;
      }
      var copy = original.copyWithCount(1);
      var handler = copy.getCapability(Capabilities.FluidHandler.ITEM);
      if (handler == null) {
        continue;
      }
      int tanks = handler.getTanks();
      if (tanks < 0 || tanks > MAX_TANKS) {
        unsupported = true;
        continue;
      }
      for (int tank = 0; tank < tanks; tank++) {
        var stored = handler.getFluidInTank(tank).copy();
        if (stored.isEmpty()
            || !fluids.contains(stored.getFluid())
            || !FluidStack.isSameFluidSameComponents(preferred, stored)) {
          continue;
        }
        if (identities.stream().anyMatch(identity -> FluidStack.isSameFluidSameComponents(identity, stored))) {
          continue;
        }
        if (identities.size() >= MAX_IDENTITIES) {
          unsupported = true;
          continue;
        }
        identities.add(stored.copyWithAmount(FluidType.BUCKET_VOLUME));
      }
    }
    var outcome = unsupported ? PlacementOutcome.FLUID_CONTAINER : PlacementOutcome.MISSING;
    for (var resource : identities) {
      if (!current.getAsBoolean()
          || !level.hasChunkAt(part.position())
          || !PlacementMaterials.canReplace(part.states(), level.getBlockState(part.position()))) {
        return PlacementOutcome.CHANGED;
      }
      var type = resource.getFluidType();
      if (type.isVaporizedOnPlacement(level, part.position(), resource)) {
        outcome = PlacementOutcome.FLUID_VAPORIZES;
        continue;
      }
      var fluidState = type.getStateForPlacement(level, part.position(), resource);
      var desired = type.getBlockForFluidState(level, part.position(), fluidState);
      if (!fluidState.isSource() || desired.isAir() || desired.hasBlockEntity() || !part.matches(desired)) {
        outcome = PlacementOutcome.INVALID_STATE;
        continue;
      }
      if (!desired.canSurvive(level, part.position())) {
        outcome = PlacementOutcome.SUPPORT;
        continue;
      }
      if (!level.isUnobstructed(desired, part.position(), CollisionContext.of(player))) {
        outcome = PlacementOutcome.COLLISION;
        continue;
      }
      var plan = prepare(sources, resource);
      if (plan != null) {
        return commit(player, part, desired, plan, current);
      }
    }
    return outcome;
  }

  /**
   * Single-item copies preserve every component and the handler's
   * replacement-container semantics.
   */
  private static Plan prepare(List<ItemStack> sources, FluidStack resource) {
    int remaining = FluidType.BUCKET_VOLUME;
    var debits = new ArrayList<Debit>();
    for (int slot = 0; slot < sources.size() && remaining > 0; slot++) {
      var source = sources.get(slot);
      if (source.isEmpty()) {
        continue;
      }
      int units = 0;
      var containers = new ArrayList<ItemStack>();
      while (units < source.getCount() && remaining > 0) {
        var copy = source.copyWithCount(1);
        var handler = copy.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler == null) {
          break;
        }
        int tanks = handler.getTanks();
        if (tanks < 0 || tanks > MAX_TANKS) {
          break;
        }
        int before = remaining;
        while (remaining > 0) {
          var wanted = resource.copyWithAmount(remaining);
          var simulated = handler.drain(wanted, FluidAction.SIMULATE);
          if (simulated.isEmpty()) {
            break;
          }
          if (simulated.getAmount() > remaining || !FluidStack.isSameFluidSameComponents(resource, simulated)) {
            throw new IllegalStateException("Fluid handler returned an invalid simulated drain");
          }
          var drained = handler.drain(simulated.copy(), FluidAction.EXECUTE);
          if (!FluidStack.matches(drained, simulated)) {
            throw new IllegalStateException("Fluid handler drain differs from its simulation");
          }
          remaining -= drained.getAmount();
        }
        if (remaining == before) {
          break;
        }
        var result = handler.getContainer().copy();
        if (!result.isEmpty() && result.getCount() != 1) {
          throw new IllegalStateException("Single-container drain returned an invalid item count");
        }
        containers.add(result);
        units++;
      }
      if (units > 0) {
        debits.add(new Debit(slot, source, units, List.copyOf(containers)));
      }
    }
    return remaining == 0 ? new Plan(List.copyOf(debits)) : null;
  }

  private static PlacementOutcome commit(ServerPlayer player, AssemblyPattern.Part part, BlockState desired, Plan plan, BooleanSupplier current) {
    var level = player.serverLevel();
    var inventory = player.getInventory();
    var pos = part.position();
    if (!current.getAsBoolean()
        || !level.hasChunkAt(pos)
        || !PlacementMaterials.canReplace(part.states(), level.getBlockState(pos))
        || !player.canInteractWithBlock(pos, 0)
        || !level.mayInteract(player, pos)
        || level.captureBlockSnapshots
        || level.restoringBlockSnapshots
        || !level.capturedBlockSnapshots.isEmpty()) {
      return PlacementOutcome.CHANGED;
    }
    for (var debit : plan.debits()) {
      if (!ItemStack.matches(inventory.getItem(debit.slot()), debit.expected())) {
        return PlacementOutcome.CHANGED;
      }
    }
    var held = new ArrayList<Held>();
    var snapshots = new ArrayList<BlockSnapshot>();
    var original = BlockSnapshot.create(level.dimension(), level, pos);
    boolean published = false;
    boolean charge = false;
    boolean attempted = false;
    try {
      for (var debit : plan.debits()) {
        var taken = inventory.removeItem(debit.slot(), debit.units());
        held.add(new Held(debit.slot(), taken, debit.containers()));
        if (taken.getCount() != debit.units() || !ItemStack.isSameItemSameComponents(taken, debit.expected())) {
          return PlacementOutcome.CHANGED;
        }
      }
      attempted = true;
      level.captureBlockSnapshots = true;
      boolean written = level.setBlock(pos, desired, Block.UPDATE_ALL);
      level.captureBlockSnapshots = false;
      snapshots.addAll(level.capturedBlockSnapshots);
      level.capturedBlockSnapshots.clear();
      if (!written || snapshots.size() != 1 || !snapshots.getFirst().getPos().equals(pos)) {
        return PlacementOutcome.REJECTED;
      }
      if (EventHooks.onBlockPlace(player, snapshots.getFirst(), Direction.UP)) {
        return PlacementOutcome.REJECTED;
      }
      if (!current.getAsBoolean()
          || !level.hasChunkAt(pos)
          || !level.getBlockState(pos).equals(desired)
          || !level.mayInteract(player, pos)) {
        return PlacementOutcome.CHANGED;
      }
      // From here callbacks may observe flowing fluid; its cost must remain paid even
      // on an exception.
      published = true;
      charge = true;
      desired.onPlace(level, pos, original.getState(), false);
      level.markAndNotifyBlock(
          pos,
          level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4),
          original.getState(),
          desired,
          Block.UPDATE_ALL,
          512);
      return PlacementOutcome.PLACED;
    } finally {
      try {
        if (attempted && !published) {
          level.captureBlockSnapshots = false;
          snapshots.addAll(level.capturedBlockSnapshots);
          level.capturedBlockSnapshots.clear();
          if (snapshots.isEmpty()) {
            snapshots.add(original);
          }
          charge = true;
          boolean restored = true;
          level.restoringBlockSnapshots = true;
          try {
            for (int index = snapshots.size() - 1; index >= 0; index--) {
              var snapshot = snapshots.get(index);
              if (!level.hasChunkAt(snapshot.getPos())) {
                restored = false;
                continue;
              }
              if (!level.getBlockState(snapshot.getPos()).equals(snapshot.getState())) {
                snapshot.restore(snapshot.getFlags() | Block.UPDATE_CLIENTS);
              }
              restored &= level.getBlockState(snapshot.getPos()).equals(snapshot.getState());
            }
          } finally {
            level.restoringBlockSnapshots = false;
          }
          charge = !restored;
          if (!restored) {
            throw new IllegalStateException("Fluid placement rollback could not be confirmed");
          }
        }
      } finally {
        for (var reserved : held) {
          if (charge) {
            for (var container : reserved.containers()) {
              PlayerInventoryItems.returnOrDrop(player, reserved.slot(), container.copy());
            }
          } else {
            PlayerInventoryItems.returnOrDrop(player, reserved.slot(), reserved.original());
          }
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
      }
    }
  }

}
