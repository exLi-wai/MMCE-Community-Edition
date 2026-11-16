package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import com.lw.modularmachine.util.minecraft.inventory.PlayerInventoryItems;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Resolves one item/fluid payment, placement and refund before returning to the
 * scheduler.
 */
@ApiStatus.Internal
final class AssemblyPlacement {
  interface Host {
    void removeJob(UUID player);
    void removeJob(UUID player, AssemblyJob job);
  }
  private final Host host;
  private static boolean active;
  AssemblyPlacement(Host host) {
    this.host = host;
  }
  static boolean active() {
    return active;
  }
  static void begin() {
    active = true;
  }
  static void end() {
    active = false;
  }

  PlacementOutcome place(ServerPlayer player, AssemblyJob job, AssemblyPattern.Part part, PlacementMaterials.Choices choices, AssemblyMaterialPlan.Resource resource) {
    var inventory = player.getInventory();
    if (job.level.captureBlockSnapshots) {
      return PlacementOutcome.BUSY;
    }
    if (resource.item() == null) {
      var result = placeFluid(player, job, part, resource.fluid());
      if (result == PlacementOutcome.PLACED) {
        job.materials.used(choices, resource);
      }
      return result;
    }
    var outcome = PlacementOutcome.MISSING;
    for (int slot = 0; slot < inventory.items.size(); slot++) {
      var material = inventory.getItem(slot);
      // Existing saved inventories/run data and custom placement implementations need
      // dedicated adapters.
      if (job.level.captureBlockSnapshots
          || material.isEmpty()
          || material.getItem() != resource.item()
          || !PlacementMaterials.automaticItem(material.getItem())
          || !material.getComponentsPatch().isEmpty()) {
        continue;
      }
      var blockItem = (BlockItem) material.getItem();
      var candidate = part.states().stream().filter(state -> state.getBlock() == blockItem.getBlock()).findFirst()
          .orElse(null);
      if (candidate == null) {
        continue;
      }
      ItemStack expected = material.copy();
      ItemStack reserved = material.copyWithCount(1);
      var properties = BlockItemStateProperties.EMPTY;
      for (var property : part.rule().alternatives()) {
        if (property.block() == candidate.getBlock()) {
          for (var key : property.properties().keySet()) {
            properties = properties.with(key, candidate);
          }
          break;
        }
      }
      if (!properties.isEmpty()) {
        reserved.set(DataComponents.BLOCK_STATE, properties);
      }
      var hit = new BlockHitResult(Vec3.atCenterOf(part.position()), Direction.UP, part.position(), false);
      var context = new BlockPlaceContext(job.level, player, job.hand, reserved, hit);
      var initial = blockItem.getBlock().getStateForPlacement(context);
      var desired = initial == null ? null : properties.apply(initial);
      if (!context.getClickedPos().equals(part.position()) || desired == null || !part.matches(desired)) {
        outcome = PlacementOutcome.INVALID_STATE;
        continue;
      }
      if (!desired.canSurvive(job.level, part.position())) {
        outcome = PlacementOutcome.SUPPORT;
        continue;
      }
      if (!job.level.isUnobstructed(desired, part.position(), CollisionContext.of(player))) {
        outcome = PlacementOutcome.COLLISION;
        continue;
      }
      if (!job.current(player)
          || inventory.getItem(slot).isEmpty()
          || !ItemStack.matches(inventory.getItem(slot), expected)
          || !job.level.getBlockState(part.position()).isAir()) {
        return PlacementOutcome.CHANGED;
      }
      ItemStack taken = inventory.removeItem(slot, 1);
      if (taken.isEmpty()) {
        return PlacementOutcome.CHANGED;
      }
      boolean rollback = false;
      boolean failed = false;
      boolean actionCommitted = false;
      try {
        // ItemStack.useOn owns NeoForge snapshots, place events, cancelled placement
        // rollback and consumption.
        var result = reserved.useOn(context);
        actionCommitted = result.consumesAction();
        if (actionCommitted) {
          job.materials.used(choices, resource);
        }
        return actionCommitted
            && part.matches(
                job.level.getBlockState(part.position())) ? PlacementOutcome.PLACED : PlacementOutcome.REJECTED;
      } catch (RuntimeException failure) {
        failed = true;
        host.removeJob(player.getUUID());
        ModularMachine.LOGGER.error("Assembly placement failed at {}", part.position(), failure);
        player.sendSystemMessage(AssemblyReport.problemMessage(part.position(), PlacementOutcome.ERROR));
        return PlacementOutcome.ERROR;
      } finally {
        if (job.level.captureBlockSnapshots) {
          job.level.captureBlockSnapshots = false;
          var snapshots = new ArrayList<>(job.level.capturedBlockSnapshots);
          job.level.capturedBlockSnapshots.clear();
          job.level.restoringBlockSnapshots = true;

          try {
            for (int index = snapshots.size() - 1; index >= 0; index--) {
              snapshots.get(index).restore();
            }
            rollback = job.level.getBlockState(part.position()).isAir();
          } finally {
            job.level.restoringBlockSnapshots = false;
          }
        }
        // Never overwrite a callback-edited slot. Return the actual unused reservation
        // to inventory or drop it. A successful action stays paid if a callback enabled
        // infinite materials before the native stack consumption.
        if (!taken.isEmpty()
            && (rollback
                || !actionCommitted
                    && !reserved.isEmpty()
                    && (!failed || job.level.getBlockState(part.position()).isAir()))) {
          PlayerInventoryItems.returnOrDrop(player, slot, taken);
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
      }
    }
    return outcome;
  }

  private PlacementOutcome placeFluid(ServerPlayer player, AssemblyJob job, AssemblyPattern.Part part, FluidStack fluid) {
    try {
      return AssemblyFluidPlacement.place(player, part, fluid, job.materials.reservedBlocks, () -> job.current(player));
    } catch (RuntimeException failure) {
      host.removeJob(player.getUUID(), job);
      ModularMachine.LOGGER.error("Fluid assembly failed at {}", part.position(), failure);
      player.sendSystemMessage(AssemblyReport.problemMessage(part.position(), PlacementOutcome.ERROR));
      return PlacementOutcome.ERROR;
    }
  }
}
