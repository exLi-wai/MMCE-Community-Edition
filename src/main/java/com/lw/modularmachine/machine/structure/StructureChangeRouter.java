package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.machine.selection.RangedSelectionTask;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.structure.StructureService.Registration;

/**
 * Routes block, resource and chunk invalidation into the current level's
 * subscriptions.
 */
@ApiStatus.Internal
public final class StructureChangeRouter {
  private StructureChangeRouter() {
  }

  public static void blockChanged(ServerLevel level, BlockPos pos) {
    ServerThreadGuard.require(level, "Structure state accessed");
    RangedSelectionTask.blockChanged(level, pos);
    StructureLevelState state = StructureService.levelState(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    invalidateDynamic(state, level, SectionPos.asLong(pos), pos, false);
    StructureAnchor anchor = state.anchors.get(pos.asLong());
    if (anchor != null && !anchor.turning && !StructureService.matchesActual(level, anchor)) {
      StructureService.request(anchor.controller, false, false);
    }
    Set<Registration> watched = state.sections.get(SectionPos.asLong(pos));
    if (watched == null) {
      return;
    }
    for (Registration registration : watched) {
      if (!registration.valid || !observesComponentChange(registration.pattern, pos)) {
        continue;
      }
      int group = registration.pattern.groupFor(pos);
      if (group >= 0) {
        markDirty(state, registration, group, false, true, level.getGameTime());
      }
    }
  }

  public static void resourceChanged(ServerLevel level, BlockPos pos) {
    ServerThreadGuard.require(level, "Structure state accessed");
    RangedSelectionTask.resourceChanged(level, pos);
    StructureLevelState state = StructureService.levelState(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    invalidateDynamic(state, level, SectionPos.asLong(pos), pos, true);
    Set<Registration> watched = state.sections.get(SectionPos.asLong(pos));
    if (watched == null) {
      return;
    }
    for (Registration registration : watched) {
      if (registration.valid && registration.pattern.watchesContentsAt(pos)) {
        markDirty(state, registration, registration.pattern.groupFor(pos), false, false, level.getGameTime());
      }
    }
  }

  /**
   * The owner origin has its own identity/orientation gate and is never a part.
   */
  static boolean observesComponentChange(CompiledStructure pattern, BlockPos position) {
    return observesDynamicChange(pattern.origin, position) && pattern.contains(position);
  }

  static boolean observesDynamicChange(BlockPos origin, BlockPos position) {
    return !position.equals(origin);
  }

  static void markDirty(StructureLevelState state, Registration registration, int group, boolean all, boolean resetColors, long now) {
    StructureAnchor anchor = registration.owner;
    state.scriptCleanup.remove(anchor.controller);
    registration.visuals.suspendVisibility();
    if (resetColors) {
      registration.visuals.resetColors();
    }
    registration.invalidateInterfaces();
    if (registration.verifier.state() != VerificationState.PENDING) {
      anchor.roundStarted = now;
    }
    if (all) {
      registration.modifiers.markAllDirty(now);
      registration.verifier.markAllDirty(now);
    } else {
      for (int affected : registration.modifiers.markDirty(group, now)) {
        if (affected != group) {
          registration.verifier.markDirty(affected, now);
        }
      }
      registration.verifier.markDirty(group, now);
    }
    StructureService.refreshAppearance(anchor.controller);
    state.enqueue(anchor);
  }

  static void chunkChanged(ServerLevel level, ChunkPos chunk, boolean loaded) {
    ServerThreadGuard.require(level, "Structure state accessed");
    StructureLevelState state = StructureService.levelState(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    var dynamic = state.dynamicChunks.get(chunk.toLong());
    if (dynamic != null) {
      for (var anchor : List.copyOf(dynamic)) {
        restartDynamic(state, level, anchor, anchor.dynamicWatches.affectedChunk(chunk.toLong()));
      }
    }
    Set<Registration> watched = state.chunks.get(chunk.toLong());
    if (watched == null) {
      return;
    }
    long now = level.getGameTime();
    for (Registration registration : watched) {
      if (!registration.valid) {
        continue;
      }
      if (loaded) {
        markDirty(state, registration, -1, true, true, now);
      } else {
        state.scriptCleanup.remove(registration.controller);
        registration.visuals.suspendVisibility();
        registration.modifiers.markAllDirty(now);
        registration.verifier.unavailable(now);
        registration.snapshotWork.discard();
        StructureService.refreshAppearance(registration.controller);
      }
    }
  }

  private static void invalidateDynamic(StructureLevelState state, ServerLevel level, long section, BlockPos position, boolean contents) {
    var watched = state.dynamicSections.get(section);
    if (watched == null) {
      return;
    }
    for (var anchor : List.copyOf(watched)) {
      if (observesDynamicChange(anchor.controller.structurePosition(), position)) {
        restartDynamic(state, level, anchor, anchor.dynamicWatches.affected(position, contents));
      }
    }
  }

  private static void restartDynamic(StructureLevelState state, ServerLevel level, StructureAnchor anchor, long affected) {
    if (!anchor.alive || affected == 0) {
      return;
    }
    state.scriptCleanup.remove(anchor.controller);
    if (anchor.dynamicMatcher == null || !StructureService.matchesActual(level, anchor)) {
      StructureService.request(anchor.controller, true, false);
      return;
    }
    long now = level.getGameTime();
    if (!anchor.preparing
        && (anchor.registration == null || anchor.registration.verifier.state() != VerificationState.PENDING)) {
      anchor.roundStarted = now;
    }
    anchor.dynamicWatches.invalidate(anchor.dynamicMatcher.invalidate(affected));
    StructureService.retire(state, anchor.draft);
    anchor.builder = null;
    anchor.layoutDefinition = null;
    anchor.preparing = true;
    anchor.failure = VerificationFailure.UNVERIFIED;
    if (anchor.registration != null) {
      anchor.registration.visuals.suspendVisibility();
      anchor.registration.verifier.invalidateDependency(now);
      anchor.registration.snapshotWork.discard();
    } else {
      anchor.controller.attach(null);
    }
    StructureService.refreshAppearance(anchor.controller);
    state.enqueue(anchor);
  }

}
