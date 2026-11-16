package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementOutcome;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Survival traversal and material retries; every visit consumes the caller's
 * remaining budget.
 */
@ApiStatus.Internal
final class SurvivalAssembly {
  interface Host extends AssemblyPlacement.Host {
    AssemblyJob job(UUID player);
    void cancel(ServerPlayer player);
    void finish(ServerPlayer player, AssemblyJob job);
  }
  private final Host host;
  private final AssemblyPlacement placement;

  SurvivalAssembly(Host host) {
    this.host = host;
    placement = new AssemblyPlacement(host);
  }

  int advance(ServerPlayer player, int workBudget, long deadline) {
    var job = host.job(player.getUUID());
    if (job == null || workBudget <= 0 || System.nanoTime() >= deadline) {
      return 0;
    }
    long serverTick = player.server.getTickCount();
    if (!job.current(player)) {
      host.cancel(player);
      return 1;
    }
    if (job.level.getGameTime() < job.nextPass || job.waitedTick == serverTick) {
      return 0;
    }
    int used = 0;
    if (job.materials == null) {
      used += prepareMaterials(player, job, workBudget, deadline);
      if (job.materials == null || host.job(player.getUUID()) != job) {
        if (used == 0) {
          job.waitedTick = serverTick;
        }
        return used;
      }
    }
    if (job.materialAttempt == null && !job.materials.hasAvailableMaterials()) {
      host.finish(player, job);
      return Math.max(1, used);
    }
    while (used < workBudget && System.nanoTime() < deadline) {
      if (!job.current(player)) {
        host.cancel(player);
        return Math.max(1, used);
      }
      used++;
      var part = job.pattern.parts().get(job.cursor);
      var pos = part.position();
      boolean missing = false;
      if (job.level.isOutsideBuildHeight(pos) || !job.level.getWorldBorder().isWithinBounds(pos)) {
        job.report.problem(pos, PlacementOutcome.OUTSIDE_WORLD);
      } else if (!job.level.hasChunkAt(pos)) {
        job.report.problem(pos, PlacementOutcome.UNLOADED);
      } else {
        var actual = job.level.getBlockState(pos);
        if (part.matches(actual)) {
          job.matched++;
        } else if (!PlacementMaterials.canReplace(part.states(), actual)) {
          job.report.occupied(pos, actual);
        } else if (!job.level.mayInteract(player, pos)) {
          job.report.problem(pos, PlacementOutcome.NO_PERMISSION);
        } else {
          AssemblyPlacement.begin();
          try {
            var result = place(player, job, part);
            if (host.job(player.getUUID()) != job) {
              return used;
            }
            if (result == null) {
              // A detached reassignment is pending; retain this exact position.
              job.waitedTick = serverTick;
              return used;
            }
            // Observe only after the placement's rollback/refund scope has resolved.
            if (!job.level.hasChunkAt(pos)) {
              job.report.problem(pos, PlacementOutcome.UNLOADED);
            } else {
              var after = job.level.getBlockState(pos);
              if (part.matches(after)) {
                job.matched++;
                job.passPlaced++;
                if (result == PlacementOutcome.PLACED) {
                  job.placed++;
                }
              } else if (!PlacementMaterials.canReplace(part.states(), after)) {
                job.report.occupied(pos, after);
              } else if (result == PlacementOutcome.MISSING) {
                missing = true;
              } else {
                job.report.problem(pos, result == PlacementOutcome.PLACED ? PlacementOutcome.REJECTED : result);
              }
            }
          } finally {
            AssemblyPlacement.end();
          }
        }
      }
      if (host.job(player.getUUID()) != job) {
        return used;
      }
      if (job.materialAttempt != null) {
        job.materialAttempt.cancel();
        job.materialAttempt = null;
      }
      if (!missing) {
        releaseDemand(job, job.cursor);
      }
      if (job.refreshMaterials) {
        job.refreshMaterials = false;
        job.materials = null;
        if (job.materialPlanning != null) {
          job.materialPlanning.cancel();
        }
        job.materialPlanning = null;
        job.cursor = 0;
        job.matched = 0;
        job.passPlaced = 0;
        return used;
      }
      if (!job.materials.hasAvailableMaterials()) {
        host.finish(player, job);
        return used;
      }
      if (++job.cursor == job.pattern.parts().size()) {
        boolean complete = job.matched == job.pattern.parts().size();
        if (complete || job.passPlaced == 0) {
          host.finish(player, job);
          return used;
        }
        job.cursor = 0;
        job.matched = 0;
        job.passPlaced = 0;
        job.report.nextPass();
        job.materials = null;
        job.materialPlanning = null;
        job.materialCursor = 0;
        job.materialDemand.clear();
        job.demandPositions.clear();
        job.fluidOnlyDemand.clear();
        job.nextPass = job.level.getGameTime() + 20;
        return used;
      }
    }

    return used;
  }

  private void releaseDemand(AssemblyJob job, int index) {
    if (!job.demandPositions.get(index)) {
      return;
    }
    var choices = job.report.choices(job.pattern.parts().get(index));
    if (job.fluidOnlyDemand.get(index)) {
      choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
    }
    job.materialDemand.computeIfPresent(choices, (key, count) -> count <= 1 ? null : count - 1);
    job.demandPositions.clear(index);
    job.fluidOnlyDemand.clear(index);
  }

  private int prepareMaterials(ServerPlayer player, AssemblyJob job, int workBudget, long deadline) {
    int used = 0;
    if (job.materialCursor < job.pattern.parts().size()) {
      while (used < workBudget && System.nanoTime() < deadline && job.materialCursor < job.pattern.parts().size()) {
        if (!job.current(player)) {
          host.cancel(player);
          return Math.max(1, used);
        }
        used++;
        int index = job.materialCursor++;
        var part = job.pattern.parts().get(index);
        var pos = part.position();
        if (job.level.isOutsideBuildHeight(pos) || !job.level.getWorldBorder().isWithinBounds(pos)) {
          job.report.problem(pos, PlacementOutcome.OUTSIDE_WORLD);
        } else if (!job.level.hasChunkAt(pos)) {
          job.report.problem(pos, PlacementOutcome.UNLOADED);
        } else {
          var actual = job.level.getBlockState(pos);
          if (part.matches(actual)) {
            continue;
          } else if (!PlacementMaterials.canReplace(part.states(), actual)) {
            job.report.occupied(pos, actual);
          } else if (!job.level.mayInteract(player, pos)) {
            job.report.problem(pos, PlacementOutcome.NO_PERMISSION);
          } else {
            var choices = job.report.choices(part);
            if (!actual.isAir()) {
              choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
            }
            if (choices.items().isEmpty() && choices.fluids().isEmpty()) {
              job.report.problem(pos, PlacementOutcome.MANUAL);
            } else {
              job.materialDemand.merge(choices, 1, Integer::sum);
              job.demandPositions.set(index);
              job.fluidOnlyDemand.set(index, !actual.isAir());
            }
          }
        }
      }
      if (job.materialCursor < job.pattern.parts().size()) {
        return used;
      }
    }
    if (used >= workBudget || System.nanoTime() >= deadline) {
      return used;
    }
    if (job.materialPlanning == null || !job.materialPlanning.inventoryMatches(player)) {
      if (job.materialPlanning != null) {
        job.materialPlanning.cancel();
      }
      job.materialPlanning = new AssemblyMaterialPlan(job.materialDemand, player);
      used++;
    }
    try {
      if (used < workBudget && System.nanoTime() < deadline) {
        var advanced = job.materialPlanning.advance(deadline, workBudget - used);
        used += advanced.work();
        if (advanced.result() != null) {
          job.materials = advanced.result();
          used = Math.min(workBudget, Math.max(1, used));
        } else if (job.materialPlanning.waitingForAllocation()) {
          job.waitedTick = player.server.getTickCount();
        }
      }
    } catch (RuntimeException failure) {
      host.cancel(player);
      ModularMachine.LOGGER.warn("Assembly material planning failed", failure);
      player.sendSystemMessage(Component.translatable("message.mmcece.assembly.material_planning_failed"));
      used = Math.max(1, used);
    }

    return used;
  }

  private PlacementOutcome place(ServerPlayer player, AssemblyJob job, AssemblyPattern.Part part) {
    var choices = job.report.choices(part);
    if (!job.level.getBlockState(part.position()).isAir()) {
      choices = new PlacementMaterials.Choices(List.of(), choices.fluids());
    }
    if (job.materialAttempt == null || !job.materialAttempt.choices.equals(choices)) {
      if (job.materialAttempt != null) {
        job.materialAttempt.cancel();
      }
      job.materialAttempt = new AssemblyJob.MaterialAttempt(choices, job.materials.next(choices));
    }
    var attempt = job.materialAttempt;
    if (attempt.failure != null) {
      try {
        if (attempt.retry == null) {
          attempt.retry = job.materials.retry(choices, attempt.rejected);
        }
        if (attempt.retry == null || !attempt.retry.isDone()) {
          return null;
        }
        var replacement = attempt.retry.join();
        attempt.retry = null;
        if (replacement == null) {
          return attempt.failure;
        }
        job.materials.install(replacement);
        attempt.resource = replacement.resource();
        attempt.failure = null;
      } catch (RuntimeException failure) {
        host.cancel(player);
        ModularMachine.LOGGER.warn("Assembly material reassignment failed", failure);
        player.sendSystemMessage(Component.translatable("message.mmcece.assembly.material_planning_failed"));
        return PlacementOutcome.ERROR;
      }
    }
    var resource = attempt.resource;
    if (resource == null) {
      return PlacementOutcome.MISSING;
    }
    var result = placement.place(player, job, part, choices, resource);
    if (result == PlacementOutcome.MISSING) {
      job.materials.unavailable(resource);
      if (job.materialPlanning != null && !job.materialPlanning.inventoryMatches(player)) {
        // Another actor changed the backpack since planning. Re-capture its
        // remaining partial supply instead of requiring a full group replacement.
        job.refreshMaterials = true;
        return result;
      }
    }
    boolean retryable = switch (result) {
      case MISSING, INVALID_STATE, SUPPORT, COLLISION, FLUID_VAPORIZES, FLUID_CONTAINER -> true;
      default -> false;
    };
    if (retryable && host.job(player.getUUID()) == job) {
      attempt.rejected.add(resource);
      attempt.failure = result;
      return null;
    }
    return result;
  }

}
