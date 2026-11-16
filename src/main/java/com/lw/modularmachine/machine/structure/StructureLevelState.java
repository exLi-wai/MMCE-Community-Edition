package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.controller.VerificationFailure;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.structure.StructureService.Registration;
import com.lw.modularmachine.machine.structure.StructureService.StructureSubject;

/**
 * Level-scoped registration indices and work queues; never shared between
 * server levels.
 */
@ApiStatus.Internal
final class StructureLevelState {
  final Map<Long, StructureAnchor> anchors = new HashMap<>();
  final Map<Long, Set<Registration>> sections = new HashMap<>();
  final Map<Long, Set<Registration>> chunks = new HashMap<>();
  final Map<Long, Set<StructureAnchor>> dynamicSections = new HashMap<>();
  final Map<Long, Set<StructureAnchor>> dynamicChunks = new HashMap<>();
  final LinkedHashSet<StructureWork> queue = new LinkedHashSet<>();
  final Map<StructureSubject, ScriptCleanup> scriptCleanup = new LinkedHashMap<>();
  final ConcurrentLinkedQueue<StructureAnchor> ready = new ConcurrentLinkedQueue<>();
  long nextGeneration, nextProjectionRevision, revision;

  void enqueue(StructureAnchor anchor) {
    if (anchor.alive && anchor.needsWork()) {
      queue.add(anchor);
    }
  }

  void drainReady() {
    StructureAnchor anchor;
    while ((anchor = ready.poll()) != null) {
      enqueue(anchor);
    }
  }

  void clear() {
    scriptCleanup.clear();
    for (StructureAnchor anchor : anchors.values()) {
      anchor.alive = false;
      if (anchor.registration != null) {
        anchor.registration.valid = false;
      }
      if (anchor.draft != null) {
        anchor.draft.valid = false;
      }
      anchor.builder = null;
      anchor.dynamicPreparation = null;
      anchor.preparing = false;
      if (anchor.registration != null) {
        anchor.registration.snapshotWork.discard();
      }
      if (anchor.draft != null) {
        anchor.draft.snapshotWork.discard();
      }
      anchor.controller.failPreparation(VerificationFailure.UNVERIFIED);
    }
    anchors.clear();
    sections.clear();
    chunks.clear();
    dynamicSections.clear();
    dynamicChunks.clear();
    queue.clear();
    ready.clear();
  }
  record ScriptCleanup(
      long scriptRevision,
      StructureAnchor anchor,
      long generation,
      Registration registration,
      long structureRevision) {
  }
}
