package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.appearance.MachineComponentVisibility;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.smart.SmartInterfaceAssignment;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.selection.RangedSelectionTask;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-thread ownership, one coalesced request per controller, and budgeted
 * immutable generations.
 */
public final class StructureService {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final Map<ServerLevel, LevelState> LEVELS = new IdentityHashMap<>();
  private static final int INLINE_PREPARE_PARTS = 512;

  private record Catalog(long epoch, Map<ResourceLocation, MachineDefinition> definitions) {
  }

  private static volatile Catalog catalog = new Catalog(0, Map.of());

  private StructureService() {
  }

  /** Structure work mode shown by the controller panels. */
  public static String workMode() {
    return gameTestMode() ? "INLINE" : "ASYNC";
  }

  public static long definitionEpoch() {
    return catalog.epoch();
  }

  public static MachineDefinition definition(ResourceLocation id) {
    return catalog.definitions().get(id);
  }

  public static Map<ResourceLocation, MachineDefinition> definitionsSnapshot() {
    return catalog.definitions();
  }

  /**
   * Read-only context token; querying it never registers or binds a controller.
   */
  @ApiStatus.Internal
  public static long controllerProjectionRevision(StructureSubject controller) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      throw new IllegalArgumentException("Controller context requires a server level");
    }
    ServerThreadGuard.require(level, "Controller context read");
    var state = LEVELS.get(level);
    var anchor = state == null ? null : state.anchors.get(controller.structurePosition().asLong());
    return anchor == null || anchor.controller != controller || !anchor.alive ? -1 : anchor.projectionRevision;
  }

  /** Coarse invalidation for unbound generic discovery, main-thread only. */
  public static long worldRevision(ServerLevel level) {
    ServerThreadGuard.require(level, "Structure revision read");
    return LEVELS.computeIfAbsent(level, ignored -> new LevelState()).revision;
  }

  public static synchronized void replaceDefinitions(Map<ResourceLocation, MachineDefinition> candidate) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && !server.isSameThread()) {
      throw new IllegalStateException("Definition apply outside server thread");
    }
    var next = new Catalog(Math.incrementExact(catalog.epoch()), Map.copyOf(candidate));
    catalog = next;
    AsyncStructureWork.clearTemplates();
    // Epoch revocation is immediate; expensive rebuilding is deferred to the level
    // queue.
    for (LevelState state : LEVELS.values()) {
      for (Anchor anchor : List.copyOf(state.anchors.values())) {
        request(anchor.controller, true, true);
      }
    }
  }

  public static void register(MachineControllerBlockEntity controller) {
    register((StructureSubject) controller);
  }

  public static void register(StructureSubject controller) {
    request(controller, false, false);
  }

  /**
   * Revoke selection-dependent proofs before a blueprint edit can be observed by
   * production.
   */
  public static void selectionChanged(StructureSubject controller) {
    request(controller, true, true);
  }

  private static void request(StructureSubject controller, boolean force, boolean resetEpisode) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.computeIfAbsent(level, ignored -> new LevelState());
    long key = controller.structurePosition().asLong();
    Anchor previous = state.anchors.get(key);
    if (!liveController(level, controller)) {
      if (previous != null && previous.controller == controller) {
        removeAnchor(state, previous);
      }
      return;
    }
    if (previous != null && previous.controller != controller) {
      removeAnchor(state, previous);
    }
    Anchor anchor = state.anchors.computeIfAbsent(key, ignored -> new Anchor(controller));
    if (anchor.turning) {
      return;
    }
    var snapshot = catalog;
    var orientation = controller.structureOrientation(level);
    var definition = snapshot.definitions().get(controller.structureMachineId());
    boolean changed = anchor.epoch != snapshot.epoch()
        || anchor.definition != definition
        || anchor.orientation != orientation
        || !controller.structureMachineId().equals(anchor.machineId)
        || anchor.schemaSupported != controller.structureSchemaSupported();
    if (!changed && !force) {
      return;
    }
    if (anchor.projectionRevision == 0
        || resetEpisode
        || anchor.epoch != snapshot.epoch()
        || anchor.orientation != orientation
        || anchor.schemaSupported != controller.structureSchemaSupported()) {
      anchor.projectionRevision = state.nextProjectionRevision = Math.incrementExact(state.nextProjectionRevision);
    }
    state.scriptCleanup.remove(controller);
    if (anchor.definition != definition || anchor.orientation != orientation) {
      clearDynamicWatches(state, anchor);
    }

    long now = level.getGameTime();
    boolean wasPending = anchor.builder != null
        || anchor.draft != null
        || anchor.registration != null && anchor.registration.verifier.state() == VerificationState.PENDING;
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.dynamicPreparation = null;
    anchor.dynamicMatcher = null;
    anchor.layoutDefinition = null;
    anchor.actualRevision = Long.MIN_VALUE;
    anchor.preparing = false;
    anchor.generation = state.nextGeneration = Math.incrementExact(state.nextGeneration);
    anchor.epoch = snapshot.epoch();
    anchor.definition = definition;
    anchor.machineId = controller.structureMachineId();
    anchor.orientation = orientation;
    anchor.schemaSupported = controller.structureSchemaSupported();
    if (!wasPending || resetEpisode) {
      anchor.roundStarted = now;
    }
    controller.attach(null);
    if (!anchor.schemaSupported) {
      fail(state, anchor, VerificationFailure.UNSUPPORTED_SCHEMA, now);
      return;
    }
    if (definition == null) {
      fail(state, anchor, VerificationFailure.UNKNOWN_DEFINITION, now);
      return;
    }
    if (!controller.allowsStructureDefinition(definition)) {
      fail(state, anchor, VerificationFailure.MISMATCH, now);
      return;
    }
    if (!definition.allows(orientation)) {
      fail(state, anchor, VerificationFailure.ORIENTATION_NOT_ALLOWED, now);
      return;
    }
    anchor.failure = VerificationFailure.UNVERIFIED;
    anchor.preparing = true;
    state.enqueue(anchor);
  }

  public static void unregister(MachineControllerBlockEntity controller) {
    unregister((StructureSubject) controller);
  }

  public static void unregister(StructureSubject controller) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return;
    }
    Anchor anchor = state.anchors.get(controller.structurePosition().asLong());
    if (anchor != null && anchor.controller == controller) {
      removeAnchor(state, anchor);
    }
  }

  public enum TurnResult {
    CHANGED,
    UNCHANGED,
    NOT_ALLOWED,
    STALE_CONTROLLER,
    WRITE_FAILED,
    PERMISSION_DENIED
  }

  /**
   * Player-facing adapters must use this overload or perform equivalent
   * permission checks.
   */
  public static TurnResult reorient(StructureSubject controller, MachineOrientation target, ServerPlayer player) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return TurnResult.STALE_CONTROLLER;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    if (player == null
        || player.level() != level
        || player.isSpectator()
        || !player.getAbilities().mayBuild
        || !player.canInteractWithBlock(controller.structurePosition(), 0)
        || !level.mayInteract(player, controller.structurePosition())) {
      return TurnResult.PERMISSION_DENIED;
    }
    return reorient(controller, target);
  }

  /**
   * Trusted server API. It rotates the controller state, never other blocks or
   * inventories.
   */
  public static TurnResult reorient(StructureSubject controller, MachineOrientation target) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return TurnResult.STALE_CONTROLLER;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    if (!liveController(level, controller)) {
      return TurnResult.STALE_CONTROLLER;
    }
    var definition = definition(controller.structureMachineId());
    if (target == null || !controller.structureSchemaSupported() || definition == null || !definition.allows(target)) {
      return TurnResult.NOT_ALLOWED;
    }
    var position = controller.structurePosition();
    var before = level.getBlockState(position);
    if (controller.structureOrientation(level) == target) {
      return TurnResult.UNCHANGED;
    }
    register(controller);
    LevelState state = LEVELS.get(level);
    Anchor anchor = state.anchors.get(position.asLong());
    if (anchor == null || anchor.turning) {
      return TurnResult.STALE_CONTROLLER;
    }
    // Revoke before any world callback; standard state-change notifications are
    // coalesced below.
    boolean wasPending = anchor.builder != null
        || anchor.draft != null
        || anchor.registration != null && anchor.registration.verifier.state() == VerificationState.PENDING;
    long started = anchor.roundStarted;
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.actualRevision = Long.MIN_VALUE;
    controller.attach(null);
    anchor.turning = true;
    boolean written;
    try {
      written = level.setBlock(position, rotated(before, target), Block.UPDATE_ALL);
    } catch (RuntimeException exception) {
      LOGGER.warn("Controller rotation callback failed at {}", position, exception);
      written = false;
    } finally {
      anchor.turning = false;
      request(controller, true, false);
      if (wasPending && anchor.alive) {
        anchor.roundStarted = started;
      }
    }
    if (!liveController(level, controller)) {
      return TurnResult.STALE_CONTROLLER;
    }
    return written && controller.structureOrientation(level) == target ? TurnResult.CHANGED : TurnResult.WRITE_FAILED;
  }

  private static BlockState rotated(BlockState state, MachineOrientation orientation) {
    return state.getBlock() instanceof FactoryControllerBlock ? FactoryControllerBlock
        .withOrientation(state, orientation) : MachineControllerBlock.withOrientation(state, orientation);
  }

  public static void blockChanged(ServerLevel level, BlockPos pos) {
    ServerThreadGuard.require(level, "Structure state accessed");
    RangedSelectionTask.blockChanged(level, pos);
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return;
    }
    state.revision = Math.incrementExact(state.revision);
    invalidateDynamic(state, level, SectionPos.asLong(pos), pos, false);
    Anchor anchor = state.anchors.get(pos.asLong());
    if (anchor != null && !anchor.turning && !matchesActual(level, anchor)) {
      request(anchor.controller, false, false);
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
    LevelState state = LEVELS.get(level);
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

  private static void markDirty(LevelState state, Registration registration, int group, boolean all, boolean resetColors, long now) {
    Anchor anchor = registration.owner;
    state.scriptCleanup.remove(anchor.controller);
    registration.suspendVisibility();
    if (resetColors) {
      registration.resetColors();
    }
    registration.interfacesReady = registration.interfaceParts.length == 0;
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
    refreshAppearance(anchor.controller);
    state.enqueue(anchor);
  }

  public static void requestAudit(StructureSubject controller) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
    Anchor anchor = state == null ? null : state.anchors.get(controller.structurePosition().asLong());
    if (anchor == null || anchor.controller != controller) {
      register(controller);
      return;
    }
    if (anchor.definition != null && anchor.definition.hasDynamicPatterns()) {
      request(controller, true, false);
      return;
    }
    long now = level.getGameTime();
    if (anchor.registration != null && matchesActual(level, anchor)) {
      markDirty(state, anchor.registration, -1, true, true, now);
    } else {
      request(controller, true, false);
    }
  }

  private static void chunkChanged(ServerLevel level, ChunkPos chunk, boolean loaded) {
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
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
        registration.suspendVisibility();
        registration.modifiers.markAllDirty(now);
        registration.verifier.unavailable(now);
        registration.discardCapture();
        refreshAppearance(registration.controller);
      }
    }
  }

  /**
   * Shared round-robin budget for pure preparation, subscription
   * cleanup/publication, and checks.
   */
  public static int advance(ServerLevel level, int budget, int perController, long timeBudgetNanos) {
    ServerThreadGuard.require(level, "Structure state accessed");
    if (budget <= 0 || perController <= 0 || timeBudgetNanos <= 0) {
      return 0;
    }
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return 0;
    }
    state.drainReady();
    long deadline = System.nanoTime() + timeBudgetNanos;
    int used = advanceScriptCleanup(state, level, Math.min(budget, 32), deadline);
    int idle = 0;
    while (used < budget && System.nanoTime() < deadline && !state.queue.isEmpty()) {
      var iterator = state.queue.iterator();
      Work work = iterator.next();
      iterator.remove();
      int slice = Math.min(perController, budget - used);
      int before = used;
      if (work instanceof Registration retired) {
        used += clean(state, retired, slice);
      } else {
        if (work instanceof Anchor anchor && anchor.alive) {
          try {
            used += advanceAnchor(state, level, anchor, slice);
          } catch (RuntimeException exception) {
            LOGGER.warn("Controller preparation failed at {}", anchor.controller.structurePosition(), exception);
            fail(state, anchor, VerificationFailure.CHECK_ERROR, level.getGameTime());
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

  private static boolean inlinePrepare(MachineDefinition definition) {
    // GameTest servers must not race the shared worker pool: a slow or loaded
    // runner made structure confirmation nondeterministic.
    return gameTestMode() || definition.parts().size() <= INLINE_PREPARE_PARTS;
  }

  private static boolean gameTestMode() {
    return !System.getProperty("neoforge.enabledGameTestNamespaces", "").isBlank();
  }

  private static int advanceDynamic(LevelState state, ServerLevel level, Anchor anchor, int budget) {
    if (anchor.dynamicMatcher == null) {
      if (anchor.dynamicPreparation == null) {
        var definition = anchor.definition;
        var origin = anchor.controller.structurePosition();
        var orientation = anchor.orientation;
        anchor.dynamicPreparation = AsyncStructureWork
            .submit(() -> new DynamicStructureMatcher(definition, origin, orientation));
        if (anchor.dynamicPreparation != null) {
          anchor.dynamicPreparation.whenComplete((ignored, failure) -> state.ready.add(anchor));
        }
      }
      if (anchor.dynamicPreparation == null || !anchor.dynamicPreparation.isDone()) {
        return 0;
      }
      anchor.dynamicMatcher = anchor.dynamicPreparation.join();
      anchor.dynamicPreparation = null;
      clearDynamicWatches(state, anchor);
    }
    int used = anchor.dynamicWatches.advanceCleanup(
        budget,
        section -> removeDynamicWatch(state.dynamicSections, section, anchor),
        chunk -> removeDynamicWatch(state.dynamicChunks, chunk, anchor));
    if (!anchor.dynamicWatches.ready() || used >= budget) {
      return used;
    }
    used += anchor.dynamicMatcher.advance(level, budget - used, (segment, position, contents) -> {
      long section = SectionPos.asLong(position);
      long chunk = ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
      if (!anchor.dynamicWatches.sections().contains(section)) {
        state.dynamicSections.computeIfAbsent(section, ignored -> new HashSet<>()).add(anchor);
      }
      if (!anchor.dynamicWatches.chunks().contains(chunk)) {
        state.dynamicChunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(anchor);
      }
      anchor.dynamicWatches.add(segment, position, contents);
    });
    if (anchor.dynamicMatcher.complete() && !anchor.dynamicMatcher.matched()) {
      var reason = anchor.dynamicMatcher.failure().reason();
      fail(state, anchor, reason, level.getGameTime());
      if (reason == VerificationFailure.MISMATCH) {
        definiteStructureMismatch(anchor.controller);
      }
    }
    return used;
  }

  private static void invalidateDynamic(LevelState state, ServerLevel level, long section, BlockPos position, boolean contents) {
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

  private static void restartDynamic(LevelState state, ServerLevel level, Anchor anchor, long affected) {
    if (!anchor.alive || affected == 0) {
      return;
    }
    state.scriptCleanup.remove(anchor.controller);
    if (anchor.dynamicMatcher == null || !matchesActual(level, anchor)) {
      request(anchor.controller, true, false);
      return;
    }
    long now = level.getGameTime();
    if (!anchor.preparing
        && (anchor.registration == null || anchor.registration.verifier.state() != VerificationState.PENDING)) {
      anchor.roundStarted = now;
    }
    anchor.dynamicWatches.invalidate(anchor.dynamicMatcher.invalidate(affected));
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.layoutDefinition = null;
    anchor.preparing = true;
    anchor.failure = VerificationFailure.UNVERIFIED;
    if (anchor.registration != null) {
      anchor.registration.suspendVisibility();
      anchor.registration.verifier.invalidateDependency(now);
      anchor.registration.discardCapture();
    } else {
      anchor.controller.attach(null);
    }
    refreshAppearance(anchor.controller);
    state.enqueue(anchor);
  }

  private static void removeDynamicWatch(Map<Long, Set<Anchor>> index, long key, Anchor anchor) {
    var watched = index.get(key);
    if (watched != null && watched.remove(anchor) && watched.isEmpty()) {
      index.remove(key);
    }
  }

  private static void clearDynamicWatches(LevelState state, Anchor anchor) {
    for (long section : anchor.dynamicWatches.sections()) {
      removeDynamicWatch(state.dynamicSections, section, anchor);
    }
    for (long chunk : anchor.dynamicWatches.chunks()) {
      removeDynamicWatch(state.dynamicChunks, chunk, anchor);
    }
    anchor.dynamicWatches.clear();
  }

  /**
   * Internal diagnostic for a dynamic segment that failed before layout
   * publication.
   */
  public static DynamicStructureMatcher.Failure dynamicFailure(StructureSubject controller) {
    if (!(controller.structureLevel() instanceof ServerLevel level)) {
      return null;
    }
    ServerThreadGuard.require(level, "Dynamic structure diagnostic");
    var state = LEVELS.get(level);
    var anchor = state == null ? null : state.anchors.get(controller.structurePosition().asLong());
    return anchor != null && anchor.controller == controller && anchor.dynamicMatcher != null ? anchor.dynamicMatcher
        .failure() : null;
  }

  private static int advanceAnchor(LevelState state, ServerLevel level, Anchor anchor, int budget) {
    if (!anchor.needsWork()) {
      return 0;
    }
    if (!matchesActual(level, anchor)) {
      request(anchor.controller, false, false);
      return 0;
    }
    long now = level.getGameTime();
    int used = 0;
    if (anchor.preparing) {
      if (anchor.definition.hasDynamicPatterns() && anchor.layoutDefinition == null) {
        used += advanceDynamic(state, level, anchor, budget);
        if (anchor.dynamicMatcher == null || !anchor.dynamicMatcher.matched() || used >= budget) {
          return used;
        }
        if (anchor.registration != null) {
          if (anchor.registration.dynamicLengths().equals(anchor.dynamicMatcher.lengths())) {
            // Preserve the layout, subscriptions and bindings when only predicates changed.
            anchor.layoutDefinition = anchor.registration.pattern.definition;
            anchor.preparing = false;
          } else {
            retire(state, anchor.registration);
            anchor.generation = state.nextGeneration = Math.incrementExact(state.nextGeneration);
            anchor.controller.attach(null);
          }
        }
      }
      if (anchor.preparing && anchor.builder == null) {
        long started = anchor.roundStarted;
        if (anchor.definition.hasDynamicPatterns()) {
          var source = anchor.definition;
          var lengths = anchor.dynamicMatcher.lengths();
          var origin = anchor.controller.structurePosition();
          var orientation = anchor.orientation;
          anchor.builder = AsyncStructureWork
              .prepare(source, lengths, origin, orientation, pattern -> new Prepared(pattern, started));
          if (anchor.builder != null) {
            anchor.builder.whenComplete((ignored, failure) -> state.ready.add(anchor));
          }
        } else if (inlinePrepare(anchor.definition)) {
          anchor.draft = new Registration(
              anchor,
              new Prepared(
                  new CompiledStructure(anchor.definition, anchor.controller.structurePosition(), anchor.orientation),
                  started));
          anchor.preparing = false;
          used++;
        } else {
          anchor.builder = AsyncStructureWork.prepare(
              anchor.definition,
              anchor.controller.structurePosition(),
              anchor.orientation,
              pattern -> new Prepared(pattern, started));
          var builder = anchor.builder;
          if (builder != null) {
            builder.whenComplete((ignored, failure) -> state.ready.add(anchor));
          }
        }
      }
      if (anchor.builder != null && anchor.builder.isDone() && anchor.retired == null) {
        anchor.draft = new Registration(anchor, anchor.builder.join());
        anchor.layoutDefinition = anchor.draft.pattern.definition;
        anchor.builder = null;
        anchor.preparing = false;
        used++;
      }
    }
    if (anchor.draft != null && anchor.retired == null) {
      Registration draft = anchor.draft;
      while (used < budget && draft.subscribed < draft.sections.size()) {
        long section = draft.sections.get(draft.subscribed++);
        state.sections.computeIfAbsent(section, ignored -> new HashSet<>()).add(draft);
        long chunk = ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
        state.chunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(draft);
        draft.chunkReferences.merge(chunk, 1, Integer::sum);
        used++;
      }
      if (used < budget && draft.subscribed == draft.sections.size()) {
        if (!matchesActual(level, anchor)) {
          request(anchor.controller, false, false);
          return used;
        }
        anchor.registration = draft;
        anchor.draft = null;
        draft.published = true;
        anchor.controller.attach(draft);
        if (!draft.pattern.chunksAvailable(level)) {
          draft.verifier.unavailable(now);
        }
        used++;
      }
    }
    Registration registration = anchor.registration;
    if (registration != null && used < budget && registration.verifier.state() == VerificationState.PENDING) {
      used += registration.advanceSnapshot(level, budget - used, now);
      if (registration.verifier.canCommit() && registration.modifiers.canCommit() && !registration.interfacesReady) {
        try {
          registration.establishInterfaces(level);
        } catch (RuntimeException invalid) {
          registration.verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
        }
      }
    }
    if (registration != null
        && registration.valid
        && registration.verifier.canCommit()
        && registration.modifiers.canCommit()
        && registration.interfacesReady
        && used < budget
        && !registration.colorsComplete) {
      used += registration.advanceColors(level, budget - used);
    }
    if (registration != null && used < budget && registration.canCommit(level) && !registration.visibilityComplete) {
      used += registration.advanceVisibility(level, budget - used);
    }
    if (registration != null && registration.visibility != null) {
      registration.visibility.active(registration.canCommit(level));
    }
    refreshAppearance(anchor.controller);
    return used;
  }

  private static void fail(LevelState state, Anchor anchor, VerificationFailure failure, long now) {
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.dynamicPreparation = null;
    anchor.preparing = false;
    anchor.failure = failure;
    anchor.controller.failPreparation(failure);
  }

  private static void retire(LevelState state, Registration registration) {
    if (registration == null || !registration.valid) {
      return;
    }
    registration.valid = false;
    registration.resetVisibility();
    registration.discardCapture();
    registration.verifier.rejectProof(VerificationFailure.STALE_BINDING, -1, registration.owner.roundStarted);
    Anchor owner = registration.owner;
    if (owner.registration == registration) {
      owner.registration = null;
    }
    if (owner.draft == registration) {
      owner.draft = null;
    }
    if (registration.subscribed > 0) {
      if (owner.retired != null && owner.retired != registration) {
        throw new IllegalStateException("Multiple retired subscription generations");
      }
      owner.retired = registration;
      state.queue.add(registration);
    }
  }

  private static int clean(LevelState state, Registration registration, int budget) {
    int used = 0;
    while (used < budget && registration.cleaned < registration.subscribed) {
      long section = registration.sections.get(registration.cleaned++);
      Set<Registration> watched = state.sections.get(section);
      if (watched != null) {
        watched.remove(registration);
        if (watched.isEmpty()) {
          state.sections.remove(section);
        }
      }
      long chunk = ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
      int remaining = registration.chunkReferences.getOrDefault(chunk, 0) - 1;
      if (remaining <= 0) {
        registration.chunkReferences.remove(chunk);
        Set<Registration> chunkWatchers = state.chunks.get(chunk);
        if (chunkWatchers != null) {
          chunkWatchers.remove(registration);
          if (chunkWatchers.isEmpty()) {
            state.chunks.remove(chunk);
          }
        }
      } else {
        registration.chunkReferences.put(chunk, remaining);
      }
      used++;
    }
    if (registration.cleaned < registration.subscribed) {
      state.queue.add(registration);
    } else {
      if (registration.owner.retired == registration) {
        registration.owner.retired = null;
        state.enqueue(registration.owner);
      }
    }
    return used;
  }

  private static void removeAnchor(LevelState state, Anchor anchor) {
    state.scriptCleanup.remove(anchor.controller);
    state.anchors.remove(anchor.controller.structurePosition().asLong(), anchor);
    anchor.alive = false;
    state.queue.remove(anchor);
    clearDynamicWatches(state, anchor);
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.dynamicPreparation = null;
    anchor.preparing = false;
    anchor.controller.failPreparation(VerificationFailure.UNVERIFIED);
  }

  private static boolean liveController(ServerLevel level, StructureSubject controller) {
    return !controller.structureRemoved()
        && controller.structureLevel() == level
        && level.hasChunkAt(controller.structurePosition())
        && level.getBlockEntity(controller.structurePosition()) == controller
        && controller.matchesStructureBlock(level.getBlockState(controller.structurePosition()));
  }

  private static void refreshAppearance(StructureSubject controller) {
    if (controller instanceof MachineControllerBlockEntity ordinary) {
      ordinary.refreshAppearanceState();
    } else if (controller instanceof FactoryControllerBlockEntity factory) {
      factory.refreshAppearanceState();
    }
  }

  private static void definiteStructureMismatch(StructureSubject controller) {
    if (!MachineConfig.cleanCustomDataOnStructureFailure()
        || !(controller.structureLevel() instanceof ServerLevel level)
        || !liveController(level, controller)) {
      return;
    }
    var state = LEVELS.get(level);
    if (state != null && !clearScriptState(controller)) {
      var anchor = state.anchors.get(controller.structurePosition().asLong());
      if (anchor != null) {
        var registration = anchor.registration;
        state.scriptCleanup.put(
            controller,
            new ScriptCleanup(
                MachineControllerScriptDataService.revision(controller),
                anchor,
                anchor.generation,
                registration,
                registration == null ? -1 : registration.verifier.revision()));
      }
    }
  }

  private static boolean clearScriptState(StructureSubject controller) {
    if (controller instanceof MachineControllerBlockEntity ordinary) {
      return ordinary.clearControllerScriptState() || ordinary.scriptState().isEmpty();
    }
    if (controller instanceof FactoryControllerBlockEntity factory) {
      return factory.clearControllerScriptState() || factory.scriptState().isEmpty();
    }

    return true;
  }

  private static int advanceScriptCleanup(LevelState state, ServerLevel level, int budget, long deadline) {
    int used = 0;
    int visits = Math.min(budget, state.scriptCleanup.size());
    while (used < visits && System.nanoTime() < deadline) {
      var iterator = state.scriptCleanup.entrySet().iterator();
      var pending = iterator.next();
      var controller = pending.getKey();
      var cleanup = pending.getValue();
      iterator.remove();
      used++;
      var anchor = state.anchors.get(controller.structurePosition().asLong());
      if (!MachineConfig.cleanCustomDataOnStructureFailure()
          || !liveController(level, controller)
          || MachineControllerScriptDataService.revision(controller) != cleanup.scriptRevision()
          || anchor != cleanup.anchor()
          || anchor.generation != cleanup.generation()
          || anchor.registration != cleanup.registration()
          || anchor.registration != null
              && (anchor.registration.verifier.revision() != cleanup.structureRevision()
                  || anchor.registration.verifier.failure() != VerificationFailure.MISMATCH)
          || anchor.registration == null && anchor.failure != VerificationFailure.MISMATCH) {
        continue;
      }
      if (!clearScriptState(controller)) {
        state.scriptCleanup.put(controller, cleanup);
      }
    }

    return used;
  }

  private static boolean matchesActual(ServerLevel level, Anchor anchor) {
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return false;
    }
    if (anchor.actualRevision == state.revision) {
      return anchor.actual;
    }
    var current = catalog;
    boolean result = anchor.alive
        && liveController(level, anchor.controller)
        && anchor.epoch == current.epoch()
        && anchor.definition == current.definitions().get(anchor.controller.structureMachineId())
        && anchor.schemaSupported == anchor.controller.structureSchemaSupported()
        && anchor.controller.structureMachineId().equals(anchor.machineId)
        && anchor.orientation == anchor.controller.structureOrientation(level);
    anchor.actualRevision = state.revision;
    anchor.actual = result;
    return result;
  }

  public static int pendingWorkCount(MachineControllerBlockEntity controller) {
    if (!(controller.getLevel() instanceof ServerLevel level)) {
      return 0;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return 0;
    }
    int count = 0;
    for (Work work : state.queue) {
      if (work instanceof Anchor anchor && anchor.controller == controller
          || work instanceof Registration registration && registration.controller == controller) {
        count++;
      }
    }
    return count;
  }

  @SubscribeEvent
  public static void addReloadListener(AddReloadListenerEvent event) {
    event.addListener(new MachineDefinitionReloadListener());
  }

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (event.getLevel() instanceof ServerLevel level) {
      chunkChanged(level, event.getChunk().getPos(), true);
    }
  }

  @SubscribeEvent
  public static void onChunkUnload(ChunkEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level) {
      RangedSelectionTask.chunkUnloaded(level, event.getChunk().getPos());
      chunkChanged(level, event.getChunk().getPos(), false);
    }
  }

  @SubscribeEvent
  public static void onLevelUnload(LevelEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level) {
      LevelState state = LEVELS.remove(level);
      if (state != null) {
        state.clear();
      }
    }
  }

  @SubscribeEvent
  public static void onServerStopped(ServerStoppedEvent event) {
    RangedSelectionTask.stop();
    LEVELS.values().forEach(LevelState::clear);
    LEVELS.clear();
    AsyncStructureWork.stop();
  }

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level) {
      // Internal time slicing protects the server; work can revisit the same
      // controller while time remains instead of imposing a per-tick count cap.
      advance(level, Integer.MAX_VALUE, 512, 3_000_000L);
    }
  }

  public interface StructureSubject {
    BlockPos structurePosition();

    Level structureLevel();

    ResourceLocation structureMachineId();

    boolean structureSchemaSupported();

    default boolean allowsStructureDefinition(MachineDefinition definition) {
      return true;
    }

    boolean structureRemoved();

    MachineOrientation structureOrientation(ServerLevel level);

    boolean matchesStructureBlock(BlockState state);

    void attach(Registration registration);

    void failPreparation(VerificationFailure failure);

    boolean revalidateStructure(Registration registration);

    Registration structureRegistration();
  }

  private sealed interface Work permits Anchor, Registration {
  }

  private static final class Anchor implements Work {
    final StructureSubject controller;
    boolean alive = true, turning, schemaSupported, preparing;
    long epoch = -1, generation, roundStarted, projectionRevision;
    long actualRevision = Long.MIN_VALUE;
    boolean actual;
    ResourceLocation machineId;
    MachineDefinition definition;
    MachineDefinition layoutDefinition;
    MachineOrientation orientation;
    CompletableFuture<Prepared> builder;
    CompletableFuture<DynamicStructureMatcher> dynamicPreparation;
    DynamicStructureMatcher dynamicMatcher;
    final DynamicStructureWatches dynamicWatches = new DynamicStructureWatches();
    Registration registration, draft, retired;
    VerificationFailure failure = VerificationFailure.UNVERIFIED;

    Anchor(StructureSubject controller) {
      this.controller = controller;
    }

    boolean needsWork() {
      boolean waitingForBuilder = preparing && builder != null && !builder.isDone();
      boolean waitingForDynamic = dynamicPreparation != null && !dynamicPreparation.isDone();
      boolean waitingForMatch = !preparing
          && registration != null
          && registration.matching != null
          && !registration.matching.isDone();
      return !waitingForBuilder
          && !waitingForDynamic
          && !waitingForMatch
          && (preparing
              || draft != null
              || registration != null
                  && (registration.verifier.state() == VerificationState.PENDING
                      || registration.valid
                          && registration.verifier.canCommit()
                          && registration.modifiers.canCommit()
                          && registration.interfacesReady
                          && (!registration.colorsComplete || !registration.visibilityComplete)));
    }
  }

  /**
   * Pure metadata built alongside the layout; transferred to the main thread
   * once.
   */
  private static final class Prepared {
    final CompiledStructure pattern;
    final IncrementalVerifier verifier;
    final StructureModifierTracker modifiers;
    final int[] interfaceParts, parallelParts;
    final Map<BlockPos, Integer> interfaceIndices;

    Prepared(CompiledStructure pattern, long started) {
      this.pattern = pattern;
      verifier = new IncrementalVerifier(pattern.groups(), started);
      modifiers = new StructureModifierTracker(pattern, started);
      interfaceParts = IntStream.range(0, pattern.definition.parts().size())
          .filter(index -> !pattern.definition.parts().get(index).interfaceType().isEmpty()).toArray();
      var indices = new HashMap<BlockPos, Integer>();
      for (int index : interfaceParts) {
        indices.put(pattern.position(index), index);
      }
      interfaceIndices = Map.copyOf(indices);
      parallelParts = IntStream.range(0, pattern.definition.parts().size())
          .filter(
              index -> pattern.definition.parts().get(index).rule().alternatives().stream()
                  .anyMatch(alternative -> alternative.block() instanceof ParallelControllerBlock))
          .toArray();
    }
  }

  public static final class Registration implements Work {
    public final StructureSubject controller;
    public final CompiledStructure pattern;
    public final IncrementalVerifier verifier;
    private final StructureModifierTracker modifiers;
    private final int[] interfaceParts;
    private final int[] parallelParts;
    private final Map<BlockPos, Integer> interfaceIndices;
    private final Anchor owner;
    private final long epoch, generation;
    private final List<Long> sections;
    private final Map<Long, Integer> chunkReferences = new HashMap<>();
    private boolean valid = true, published;
    private boolean interfacesReady;
    private Map<Integer, String> interfaceAssignments = Map.of();
    private int subscribed, cleaned;
    private int colorCursor;
    private boolean colorsComplete;
    private int visibilityCursor;
    private boolean visibilityComplete;
    private MachineComponentVisibility.Session visibility;
    private long conservativeModifierRevision = Long.MIN_VALUE;
    private boolean conservativeModifiersValidated;
    private StructureSnapshot.Capture capture;
    private AsyncStructureWork.Permit capturePermit;
    private CompletableFuture<StructureSnapshot.Result> matching;
    private long captureRevision;

    private Registration(Anchor owner, Prepared prepared) {
      var pattern = prepared.pattern;
      this.owner = owner;
      controller = owner.controller;
      this.pattern = pattern;
      epoch = owner.epoch;
      generation = owner.generation;
      sections = List.copyOf(pattern.sections());
      verifier = prepared.verifier;
      modifiers = prepared.modifiers;
      interfaceParts = prepared.interfaceParts;
      interfaceIndices = prepared.interfaceIndices;
      interfacesReady = interfaceParts.length == 0;
      parallelParts = prepared.parallelParts;
    }

    public int[] parallelParts() {
      return parallelParts.clone();
    }

    /** Lengths frozen by this immutable structure generation. */
    public Map<String, Integer> dynamicLengths() {
      return pattern.definition.dynamicLengths();
    }

    private void resetColors() {
      colorCursor = 0;
      colorsComplete = false;
      resetVisibility();
    }

    private void suspendVisibility() {
      if (visibility != null) {
        visibility.active(false);
      }
    }

    private void resetVisibility() {
      if (visibility != null) {
        visibility.close();
        visibility = null;
      }
      visibilityCursor = 0;
      visibilityComplete = false;
    }

    private int advanceVisibility(ServerLevel level, int budget) {
      var definition = pattern.definition;
      if (!definition.hideComponentsWhenFormed()
          || definition.hiddenComponentTypes().stream().noneMatch(type -> type != MachineComponentType.CONTROLLER)) {
        visibilityComplete = true;
        return 1;
      }
      if (visibility == null) {
        visibility = MachineComponentVisibility.create(level, controller.structurePosition());
      }
      visibility.active(true);
      int used = 0;
      while (used < budget && visibilityCursor < definition.parts().size()) {
        var position = pattern.position(visibilityCursor++);
        var type = MachineComponentType.forBlock(level.getBlockState(position).getBlock());
        if (type != MachineComponentType.CONTROLLER && definition.hidesComponent(type)) {
          visibility.add(position);
        }
        used++;
      }
      visibility.flush();
      visibilityComplete = visibilityCursor == definition.parts().size();

      return used;
    }

    private void discardCapture() {
      capture = null;
      if (capturePermit != null) {
        capturePermit.close();
        capturePermit = null;
      }
      // A running pure task owns its permit until completion. Never cancel a
      // future and release its memory allowance while its worker still runs.
    }

    private int advanceSnapshot(ServerLevel level, int budget, long now) {
      if (matching != null) {
        if (!matching.isDone()) {
          return 0;
        }
        var completed = matching;
        matching = null;
        if (!valid || captureRevision != verifier.revision() || !matchesActual(level, owner)) {
          return 1;
        }
        try {
          var result = completed.join();
          if (result.matched()) {
            modifiers.acceptSnapshot(result.modifiers());
          }
          boolean accepted = verifier.applySnapshot(captureRevision, result.failure(), result.failedIndex(), now);
          if (accepted && result.failure() == VerificationFailure.MISMATCH) {
            definiteStructureMismatch(controller);
          }
        } catch (RuntimeException failure) {
          LOGGER.warn("Async structure matching failed at {}", controller.structurePosition(), failure);
          verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
        }
        return 1;
      }
      if (capture != null && captureRevision != verifier.revision()) {
        discardCapture();
      }
      if (capture == null) {
        capturePermit = AsyncStructureWork.acquireSnapshot();
        if (capturePermit == null) {
          return 0;
        }
        captureRevision = verifier.revision();
        capture = new StructureSnapshot.Capture(pattern, verifier.pendingIndices());
      }
      var currentCapture = capture;
      int used = currentCapture.advance(level, budget);
      if (!valid || capture != currentCapture || captureRevision != verifier.revision()) {
        discardCapture();
        return used;
      }
      if (currentCapture.complete()) {
        var detached = currentCapture.finish();
        if (gameTestMode()) {
          try {
            var result = detached.match();
            if (result.matched()) {
              modifiers.acceptSnapshot(result.modifiers());
            }
            boolean accepted = verifier.applySnapshot(captureRevision, result.failure(), result.failedIndex(), now);
            if (accepted && result.failure() == VerificationFailure.MISMATCH) {
              definiteStructureMismatch(controller);
            }
          } catch (RuntimeException failure) {
            LOGGER.warn("Structure matching failed at {}", controller.structurePosition(), failure);
            verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
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
          LevelState state = LEVELS.get(level);
          if (state != null) {
            future.whenComplete((result, failure) -> state.ready.add(owner));
          }
          capturePermit = null;
          capture = null;
          matching = future;
          used = Math.max(used, 1);
        }
      }
      return used;
    }

    private int advanceColors(ServerLevel level, int budget) {
      int used = 0;
      while (used < budget && colorCursor < pattern.definition.parts().size()) {
        var position = pattern.position(colorCursor++);
        if (level.getBlockEntity(position) instanceof MachineColorable colorable
            && (!colorable.machineColorAssigned() || colorable.machineColor() != pattern.definition.color())) {
          colorable.setMachineColor(pattern.definition.color());
        }
        used++;
      }
      colorsComplete = colorCursor == pattern.definition.parts().size();
      return used;
    }

    public int[] interfaceParts() {
      return interfaceParts.clone();
    }

    public long generation() {
      return generation;
    }

    /**
     * Resolved explicit/automatic membership; publishing this map does not grant
     * commit authority.
     */
    public String interfaceType(int partIndex) {
      return interfaceAssignments.getOrDefault(partIndex, "");
    }

    public int interfacePart(BlockPos position) {
      return interfaceIndices.getOrDefault(position, -1);
    }

    private void establishInterfaces(ServerLevel level) {
      var slots = new ArrayList<SmartInterfaceAssignment.Slot>();
      var interfaces = new HashMap<Integer, SmartInterfaceBlockEntity>();
      for (int index : interfaceParts) {
        var position = pattern.position(index);
        if (!level.hasChunkAt(position)
            || !(level.getBlockEntity(position) instanceof SmartInterfaceBlockEntity smart)
            || !smart.schemaSupported()) {
          throw new IllegalStateException("Unavailable smart interface during type assignment");
        }
        var part = pattern.definition.parts().get(index);
        slots.add(
            new SmartInterfaceAssignment.Slot(
                index,
                part.id(),
                part.automaticInterface() ? "" : part.interfaceType(),
                smart.assignmentHint(controller).orElse("")));
        interfaces.put(index, smart);
      }
      var assignment = SmartInterfaceAssignment.assign(pattern.definition.interfaceTypes(), slots);
      interfaceAssignments = assignment;
      for (int index : interfaceParts) {
        interfaces.get(index)
            .establish(controller, pattern.definition.interfaceTypes().get(assignment.get(index)), index);
      }
      interfacesReady = true;
      for (int index : interfaceParts) {
        interfaces.get(index).publishBindingChange(controller);
      }
    }

    public long epoch() {
      return epoch;
    }

    private CheckResult check(ServerLevel level, int index) {
      var result = pattern.checkBase(level, index);

      return result == CheckResult.MISMATCH && modifiers.allows(index) ? CheckResult.MATCH : result;
    }

    public List<RecipeModifier> activeRecipeModifiers(ServerLevel level) {
      if (!canCommit(level)) {
        throw new IllegalStateException("Structure modifier proof is not current");
      }

      return modifiers.rules();
    }

    public boolean verifyModifierReference(ServerLevel level, StructureModifierTracker.Reference reference) {
      if (!canCommit(level)
          || !reference.belongsTo(pattern.modifierPlan())
          || reference.failure() != VerificationFailure.NONE) {
        return false;
      }
      if (!reference.rules().equals(modifiers.rules())) {
        var state = LEVELS.get(level);
        markDirty(state, this, -1, true, true, level.getGameTime());
        return false;
      }

      return true;
    }

    public boolean revalidateModifiers(ServerLevel level) {
      if (!canCommit(level)) {
        return false;
      }
      if (pattern.definition.structureModifiers().isEmpty() || !MachineConfig.CONSERVATIVE_COMMIT_CHECK.get()) {
        return true;
      }
      long revision = verifier.revision();
      if (conservativeModifierRevision == revision) {
        return conservativeModifiersValidated && canCommit(level);
      }
      var reference = pattern.modifierReference(level);
      var full = pattern.fullScan(level, reference);
      if (full.state() != VerificationState.CONFIRMED) {
        modifiers.markAllDirty(level.getGameTime());
        rejectProof(full.failure(), full.failedIndex(), level.getGameTime());
        conservativeModifierRevision = verifier.revision();
        conservativeModifiersValidated = false;
        return false;
      }
      boolean validReference = verifyModifierReference(level, reference);
      conservativeModifierRevision = verifier.revision();
      conservativeModifiersValidated = validReference;
      return validReference;
    }

    /**
     * Internal proof revocation also immediately restores the visual components.
     */
    public void rejectProof(VerificationFailure failure, int failedIndex, long now) {
      if (controller.structureLevel() instanceof ServerLevel level) {
        ServerThreadGuard.require(level, "Structure proof rejected");
      }
      suspendVisibility();
      verifier.rejectProof(failure, failedIndex, now);
      if (failure == VerificationFailure.MISMATCH && failedIndex >= 0) {
        definiteStructureMismatch(controller);
      }
      refreshAppearance(controller);
    }

    public boolean canCommit(ServerLevel level) {
      ServerThreadGuard.require(level, "Structure state accessed");
      LevelState state = LEVELS.get(level);
      return valid
          && published
          && !owner.preparing
          && !SmartInterfaceEventPublisher.isDispatching(controller)
          && !RecipeLifecyclePublisher.isDispatching()
          && epoch == catalog.epoch()
          && verifier.canCommit()
          && modifiers.canCommit()
          && interfacesReady
          && controller.structureSchemaSupported()
          && controller.allowsStructureDefinition(pattern.definition)
          && state != null
          && state.anchors.get(controller.structurePosition().asLong()) == owner
          && owner.registration == this
          && owner.generation == generation
          && matchesActual(level, owner)
          && pattern.definition.id().equals(controller.structureMachineId())
          && pattern.definition.sourceDefinition() == catalog.definitions().get(controller.structureMachineId())
          && pattern.orientation == owner.orientation
          && pattern.chunksAvailable(level);
    }
  }

  private static final class LevelState {
    final Map<Long, Anchor> anchors = new HashMap<>();
    final Map<Long, Set<Registration>> sections = new HashMap<>();
    final Map<Long, Set<Registration>> chunks = new HashMap<>();
    final Map<Long, Set<Anchor>> dynamicSections = new HashMap<>();
    final Map<Long, Set<Anchor>> dynamicChunks = new HashMap<>();
    final LinkedHashSet<Work> queue = new LinkedHashSet<>();
    final Map<StructureSubject, ScriptCleanup> scriptCleanup = new LinkedHashMap<>();
    final ConcurrentLinkedQueue<Anchor> ready = new ConcurrentLinkedQueue<>();
    long nextGeneration, nextProjectionRevision, revision;

    void enqueue(Anchor anchor) {
      if (anchor.alive && anchor.needsWork()) {
        queue.add(anchor);
      }
    }

    void drainReady() {
      Anchor anchor;
      while ((anchor = ready.poll()) != null) {
        enqueue(anchor);
      }
    }

    void clear() {
      scriptCleanup.clear();
      for (Anchor anchor : anchors.values()) {
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
          anchor.registration.discardCapture();
        }
        if (anchor.draft != null) {
          anchor.draft.discardCapture();
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
  }

  private record ScriptCleanup(
      long scriptRevision,
      Anchor anchor,
      long generation,
      Registration registration,
      long structureRevision) {
  }
}
