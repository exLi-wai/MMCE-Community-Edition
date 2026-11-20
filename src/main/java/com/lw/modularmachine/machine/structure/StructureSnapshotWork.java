package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.controller.VerificationFailure;
import com.mojang.logging.LogUtils;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration-owned main-thread capture and detached matching work. Running
 * workers retain their permits.
 */
@ApiStatus.Internal
final class StructureSnapshotWork {
  private static final Logger LOGGER = LogUtils.getLogger();
  private final StructureService.Registration registration;
  private StructureSnapshot.Capture capture;
  private AsyncStructureWork.Permit capturePermit;
  private CompletableFuture<StructureSnapshot.Result> matching;
  private long captureRevision;

  StructureSnapshotWork(StructureService.Registration registration) {
    this.registration = registration;
  }
  boolean waiting() {
    return matching != null && !matching.isDone();
  }

  void discard() {
    capture = null;
    if (capturePermit != null) {
      capturePermit.close();
      capturePermit = null;
    }
    // A running pure task owns its permit until completion. Never cancel a
    // future and release its memory allowance while its worker still runs.
  }

  int advance(ServerLevel level, int budget, long now) {
    if (matching != null) {
      if (!matching.isDone()) {
        return 0;
      }
      var completed = matching;
      matching = null;
      if (!registration.valid
          || captureRevision != registration.verifier.revision()
          || !StructureService.matchesActual(level, registration.owner)) {
        return 1;
      }
      try {
        applyMatchResult(completed.join(), now);
      } catch (RuntimeException failure) {
        LOGGER.warn("Async structure matching failed at {}", registration.controller.structurePosition(), failure);
        registration.verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
      }
      return 1;
    }
    if (capture != null && captureRevision != registration.verifier.revision()) {
      discard();
    }
    if (capture == null) {
      capturePermit = AsyncStructureWork.acquireSnapshot();
      if (capturePermit == null) {
        return 0;
      }
      captureRevision = registration.verifier.revision();
      capture = new StructureSnapshot.Capture(registration.pattern, registration.verifier.pendingIndices());
    }
    var currentCapture = capture;
    int used = currentCapture.advance(level, budget);
    if (!registration.valid || capture != currentCapture || captureRevision != registration.verifier.revision()) {
      discard();
      return used;
    }
    if (currentCapture.complete()) {
      var detached = currentCapture.finish();
      if (StructureService.gameTestMode()) {
        try {
          applyMatchResult(detached.match(), now);
        } catch (RuntimeException failure) {
          LOGGER.warn("Structure matching failed at {}", registration.controller.structurePosition(), failure);
          registration.verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
        }
        capturePermit.close();
        capturePermit = null;
        capture = null;
        return Math.max(used, 1);
      }
      var future = AsyncStructureWork.submit(detached::match);
      if (future != null) {
        var permit = capturePermit;
        future.whenComplete((result, failure) -> permit.close());
        StructureLevelState state = StructureService.levelState(level);
        if (state != null) {
          future.whenComplete((result, failure) -> state.ready.add(registration.owner));
        }
        capturePermit = null;
        capture = null;
        matching = future;
        used = Math.max(used, 1);
      }
    }
    return used;
  }

  private void applyMatchResult(StructureSnapshot.Result result, long now) {
    if (result.matched()) {
      registration.modifiers.acceptSnapshot(result.modifiers());
    }
    boolean accepted = registration.verifier
        .applySnapshot(captureRevision, result.failure(), result.failedIndex(), now);
    if (accepted && result.failure() == VerificationFailure.MISMATCH) {
      StructureService.definiteStructureMismatch(registration.controller);
    }
  }
}
