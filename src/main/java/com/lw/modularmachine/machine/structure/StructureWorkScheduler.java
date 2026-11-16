package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.machine.structure.StructureService.Registration;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Shared work/time budget and round-robin traversal; publication and proof
 * checks stay in the service.
 */
@ApiStatus.Internal
final class StructureWorkScheduler {
  private static final Logger LOGGER = LogUtils.getLogger();

  /**
   * Shared round-robin budget for pure preparation, subscription
   * cleanup/publication, and checks.
   */
  int advance(ServerLevel level, StructureLevelState state, int budget, int perController, long timeBudgetNanos) {
    if (budget <= 0 || perController <= 0 || timeBudgetNanos <= 0) {
      return 0;
    }
    if (state == null) {
      return 0;
    }
    state.drainReady();
    long deadline = System.nanoTime() + timeBudgetNanos;
    int used = StructureService.advanceScriptCleanup(state, level, Math.min(budget, 32), deadline);
    int idle = 0;
    while (used < budget && System.nanoTime() < deadline && !state.queue.isEmpty()) {
      var iterator = state.queue.iterator();
      StructureWork work = iterator.next();
      iterator.remove();
      int slice = Math.min(perController, budget - used);
      int before = used;
      if (work instanceof Registration retired) {
        used += StructureService.clean(state, retired, slice);
      } else {
        if (work instanceof StructureAnchor anchor && anchor.alive) {
          try {
            used += StructureService.advanceAnchor(state, level, anchor, slice);
          } catch (RuntimeException exception) {
            LOGGER.warn("Controller preparation failed at {}", anchor.controller.structurePosition(), exception);
            StructureService.fail(state, anchor, VerificationFailure.CHECK_ERROR, level.getGameTime());
            used += slice;
          }
          state.enqueue(anchor);
        }
      }
      idle = used == before ? idle + 1 : 0;
      if (idle >= state.queue.size()) {
        break;
      }
    }
    return used;
  }

}
