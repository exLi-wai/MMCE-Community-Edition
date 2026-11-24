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
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.structure.StructureService.Registration;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;

/**
 * Routes block, resource and chunk invalidation into the current level's
 * subscriptions.
 */
@ApiStatus.Internal
public final class StructureChangeRouter {
  private record WorkingPublication(
      ServerLevel level,
      Registration registration,
      int part,
      BlockState before,
      BlockState after) {
    boolean matches(ServerLevel changedLevel, BlockPos position, BlockState previous, BlockState next) {
      return level == changedLevel
          && registration.pattern.position(part).equals(position)
          && before == previous
          && after == next;
    }
  }

  /**
   * Only held around one main-thread block write, never across a budget yield.
   */
  private static WorkingPublication workingPublication;

  private StructureChangeRouter() {
  }

  public static void blockChanged(ServerLevel level, BlockPos pos) {
    blockChanged(level, pos, null, null);
  }

  public static void blockChanged(ServerLevel level, BlockPos pos, BlockState previous, BlockState next) {
    ServerThreadGuard.require(level, "Structure state accessed");
    var publication = workingPublication;
    if (publication != null && !publication.matches(level, pos, previous, next)) {
      publication = null;
    }
    RangedSelectionTask.blockChanged(level, pos);
    StructureLevelState state = StructureService.levelState(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    invalidateDynamic(state, level, SectionPos.asLong(pos), pos, false, publication);
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
      if (publication != null
          && publication.registration() == registration
          && !registration.pattern.watchesWorkingState(publication.part())) {
        continue;
      }
      int group = registration.pattern.groupFor(pos);
      if (group >= 0) {
        markDirty(state, registration, group, false, true, level.getGameTime());
      }
    }
  }

  static void publishWorkingState(ServerLevel level, Registration registration, int part, BlockState before, BlockState after) {
    ServerThreadGuard.require(level, "Structure working appearance");
    var previous = workingPublication;
    workingPublication = new WorkingPublication(level, registration, part, before, after);
    try {
      level.setBlock(registration.pattern.position(part), after, 2);
    } finally {
      workingPublication = previous;
    }
  }

  public static void resourceChanged(ServerLevel level, BlockPos pos) {
    resourceChanged(level, pos, null);
  }

  public static void resourceChanged(ServerLevel level, MachinePortBlockEntity port) {
    resourceChanged(level, port.getBlockPos(), port);
  }

  private static void resourceChanged(ServerLevel level, BlockPos pos, MachinePortBlockEntity port) {
    ServerThreadGuard.require(level, "Structure state accessed");
    StructureLevelState state = StructureService.levelState(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    invalidateDynamic(state, level, SectionPos.asLong(pos), pos, true, null);
    Set<Registration> watched = state.sections.get(SectionPos.asLong(pos));
    if (watched == null) {
      return;
    }
    for (Registration registration : watched) {
      if (registration.valid && registration.pattern.hasRecipePartAt(pos)) {
        if (port != null) {
          registration.controller.wakeRecipeSearch(port);
        } else {
          registration.controller.wakeRecipeSearch();
        }
      }
      if (registration.valid && registration.pattern.watchesContentsAt(pos)) {
        markDirty(state, registration, registration.pattern.groupFor(pos), false, false, level.getGameTime());
      }
    }
  }

  /**
   * Vanilla and external BE dirty notifications cover only subscribed content
   * dependencies. Typed ports publish configuration changes explicitly and
   * resource changes after their transactions resolve.
   */
  public static void blockEntityChanged(ServerLevel level, BlockPos pos) {
    ServerThreadGuard.require(level, "Structure block entity contents changed");
    var state = StructureService.levelState(level);
    if (state == null
        || !level.hasChunkAt(pos)
        || !watchesContents(state, pos)
        || level.getBlockEntity(pos) instanceof MachinePortBlockEntity) {
      return;
    }
    resourceChanged(level, pos);
  }

  private static boolean watchesContents(StructureLevelState state, BlockPos pos) {
    long section = SectionPos.asLong(pos);
    var fixed = state.sections.get(section);
    if (fixed != null
        && fixed.stream().anyMatch(registration -> registration.valid && registration.pattern.watchesContentsAt(pos))) {
      return true;
    }
    var dynamic = state.dynamicSections.get(section);
    return dynamic != null
        && dynamic.stream().anyMatch(
            anchor -> anchor.alive
                && observesDynamicChange(anchor.controller.structurePosition(), pos)
                && anchor.dynamicWatches.affected(pos, true) != 0);
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

  private static void invalidateDynamic(StructureLevelState state, ServerLevel level, long section, BlockPos position, boolean contents, WorkingPublication publication) {
    var watched = state.dynamicSections.get(section);
    if (watched == null) {
      return;
    }
    for (var anchor : List.copyOf(watched)) {
      if (observesDynamicChange(anchor.controller.structurePosition(), position)) {
        long affected = anchor.dynamicWatches.affected(position, contents);
        if (publication != null
            && publication.registration().owner == anchor
            && anchor.dynamicMatcher != null
            && !anchor.dynamicMatcher.watchesWorkingState(affected)) {
          continue;
        }
        restartDynamic(state, level, anchor, affected);
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
