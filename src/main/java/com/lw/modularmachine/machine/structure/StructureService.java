package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.ParallelControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.recipe.RecipeModifier;
import com.lw.modularmachine.machine.smart.SmartInterfaceAssignment;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * Server-thread ownership, one coalesced request per controller, and budgeted
 * immutable generations.
 */
public final class StructureService {
  private static final Logger LOGGER = LogUtils.getLogger();
  private static final Map<ServerLevel, LevelState> LEVELS = new IdentityHashMap<>();

  private record Catalog(long epoch, Map<ResourceLocation, MachineDefinition> definitions) {
  }

  private static volatile Catalog catalog = new Catalog(0, Map.of());

  private StructureService() {
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

  public static synchronized void replaceDefinitions(Map<ResourceLocation, MachineDefinition> candidate) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && !server.isSameThread()) {
      throw new IllegalStateException("Definition apply outside server thread");
    }
    var next = new Catalog(Math.incrementExact(catalog.epoch()), Map.copyOf(candidate));
    catalog = next;
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

    long now = level.getGameTime();
    boolean wasPending = anchor.builder != null
        || anchor.draft != null
        || anchor.registration != null && anchor.registration.verifier.state() == VerificationState.PENDING;
    boolean coolingDown = !resetEpisode && now < anchor.retryAfter;
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
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
    if (coolingDown) {
      controller.failPreparation(anchor.failure);
      return;
    }
    anchor.failure = VerificationFailure.UNVERIFIED;
    anchor.retryAfter = 0;
    anchor.builder = CompiledStructure.prepare(definition, controller.structurePosition(), orientation);
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
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return;
    }
    Anchor anchor = state.anchors.get(pos.asLong());
    if (anchor != null && !anchor.turning && !matchesActual(level, anchor)) {
      request(anchor.controller, false, false);
    }
    Set<Registration> watched = state.sections.get(SectionPos.asLong(pos));
    if (watched == null) {
      return;
    }
    for (Registration registration : watched) {
      if (!registration.valid || !registration.pattern.contains(pos)) {
        continue;
      }
      int group = registration.pattern.groupFor(pos);
      if (group >= 0) {
        markDirty(state, registration, group, false, level.getGameTime());
      }
    }
  }

  public static void resourceChanged(ServerLevel level, BlockPos pos) {
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
    if (state == null) {
      return;
    }
    Set<Registration> watched = state.sections.get(SectionPos.asLong(pos));
    if (watched == null) {
      return;
    }
    for (Registration registration : watched) {
      if (registration.valid && registration.pattern.watchesContentsAt(pos)) {
        markDirty(state, registration, registration.pattern.groupFor(pos), false, level.getGameTime());
      }
    }
  }

  private static void markDirty(LevelState state, Registration registration, int group, boolean all, long now) {
    Anchor anchor = registration.owner;
    registration.interfacesReady = registration.interfaceParts.length == 0;
    if (now < anchor.retryAfter) {
      return;
    }
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
    state.enqueue(anchor);
  }

  public static void requestAudit(MachineControllerBlockEntity controller) {
    if (!(controller.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Structure state accessed");
    LevelState state = LEVELS.get(level);
    Anchor anchor = state == null ? null : state.anchors.get(controller.getBlockPos().asLong());
    if (anchor == null || anchor.controller != controller) {
      register(controller);
      return;
    }
    long now = level.getGameTime();
    if (now < anchor.retryAfter) {
      return;
    }
    if (anchor.registration != null && matchesActual(level, anchor)) {
      markDirty(state, anchor.registration, -1, true, now);
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
        markDirty(state, registration, -1, true, now);
      } else {
        registration.modifiers.markAllDirty(now);
        registration.verifier.unavailable(now);
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
    int used = 0;
    int visits = state.queue.size();
    long deadline = System.nanoTime() + timeBudgetNanos;
    while (visits-- > 0 && used < budget && System.nanoTime() < deadline && !state.queue.isEmpty()) {
      var iterator = state.queue.iterator();
      Work work = iterator.next();
      iterator.remove();
      int slice = Math.min(perController, budget - used);
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
    }
    return used;
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
    if (now - anchor.roundStarted > MachineConfig.ROUND_TIMEOUT_TICKS.get()) {
      fail(state, anchor, VerificationFailure.VERIFICATION_TIMEOUT, now);
      return 1;
    }
    int used = 0;
    if (anchor.builder != null) {
      used += anchor.builder.advance(budget);
      if (anchor.builder.complete() && used < budget && anchor.retired == null) {
        anchor.draft = new Registration(anchor, anchor.builder.result());
        anchor.builder = null;
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
      used += registration.modifiers.advance(level, budget - used, now, MachineConfig.ROUND_TIMEOUT_TICKS.get());
      if (registration.modifiers.invalid()) {
        registration.verifier.rejectProof(registration.modifiers.failure(), registration.modifiers.failedPart(), now);
      } else if (registration.modifiers.canCommit() && used < budget) {
        used += registration.verifier.advance(
            budget - used,
            now,
            MachineConfig.ROUND_TIMEOUT_TICKS.get(),
            index -> registration.check(level, index));
      }
      if (anchor.registration == registration && registration.verifier.failure() == VerificationFailure.CHECK_ERROR) {
        anchor.retryAfter = now + MachineConfig.AUDIT_INTERVAL_TICKS.get();
      }
      if (registration.verifier.canCommit() && registration.modifiers.canCommit() && !registration.interfacesReady) {
        try {
          registration.establishInterfaces(level);
        } catch (RuntimeException invalid) {
          registration.verifier.rejectProof(VerificationFailure.CHECK_ERROR, -1, now);
        }
      }
    }
    return used;
  }

  private static void fail(LevelState state, Anchor anchor, VerificationFailure failure, long now) {
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.failure = failure;
    if (failure == VerificationFailure.VERIFICATION_TIMEOUT || failure == VerificationFailure.CHECK_ERROR) {
      anchor.retryAfter = now + MachineConfig.AUDIT_INTERVAL_TICKS.get();
    }
    anchor.controller.failPreparation(failure);
  }

  private static void retire(LevelState state, Registration registration) {
    if (registration == null || !registration.valid) {
      return;
    }
    registration.valid = false;
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
    state.anchors.remove(anchor.controller.structurePosition().asLong(), anchor);
    anchor.alive = false;
    state.queue.remove(anchor);
    retire(state, anchor.registration);
    retire(state, anchor.draft);
    anchor.builder = null;
    anchor.controller.failPreparation(VerificationFailure.UNVERIFIED);
  }

  private static boolean liveController(ServerLevel level, StructureSubject controller) {
    return !controller.structureRemoved()
        && controller.structureLevel() == level
        && level.hasChunkAt(controller.structurePosition())
        && level.getBlockEntity(controller.structurePosition()) == controller
        && controller.matchesStructureBlock(level.getBlockState(controller.structurePosition()));
  }

  private static boolean matchesActual(ServerLevel level, Anchor anchor) {
    var current = catalog;
    return anchor.alive
        && liveController(level, anchor.controller)
        && anchor.epoch == current.epoch()
        && anchor.definition == current.definitions().get(anchor.controller.structureMachineId())
        && anchor.schemaSupported == anchor.controller.structureSchemaSupported()
        && anchor.controller.structureMachineId().equals(anchor.machineId)
        && anchor.orientation == anchor.controller.structureOrientation(level);
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
    LEVELS.values().forEach(LevelState::clear);
    LEVELS.clear();
  }

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level) {
      advance(
          level,
          MachineConfig.CHECKS_PER_LEVEL.get(),
          MachineConfig.CHECKS_PER_CONTROLLER.get(),
          MachineConfig.TIME_BUDGET_MICROS.get() * 1000L);
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
    boolean alive = true, turning, schemaSupported;
    long epoch = -1, generation, roundStarted, retryAfter;
    ResourceLocation machineId;
    MachineDefinition definition;
    MachineOrientation orientation;
    CompiledStructure.Builder builder;
    Registration registration, draft, retired;
    VerificationFailure failure = VerificationFailure.UNVERIFIED;

    Anchor(StructureSubject controller) {
      this.controller = controller;
    }

    boolean needsWork() {
      return builder != null
          || draft != null
          || registration != null && registration.verifier.state() == VerificationState.PENDING;
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

    private Registration(Anchor owner, CompiledStructure pattern) {
      this.owner = owner;
      controller = owner.controller;
      this.pattern = pattern;
      epoch = owner.epoch;
      generation = owner.generation;
      sections = List.copyOf(pattern.sections());
      // Final bounded initialization: at most 4096 predicates.
      verifier = new IncrementalVerifier(pattern.groups(), owner.roundStarted);
      modifiers = new StructureModifierTracker(pattern, owner.roundStarted);
      interfaceParts = IntStream.range(0, pattern.definition.parts().size())
          .filter(index -> !pattern.definition.parts().get(index).interfaceType().isEmpty()).toArray();
      var indexedInterfaces = new HashMap<BlockPos, Integer>();
      for (int index : interfaceParts) {
        indexedInterfaces.put(pattern.position(index).immutable(), index);
      }
      interfaceIndices = Map.copyOf(indexedInterfaces);
      interfacesReady = interfaceParts.length == 0;
      parallelParts = IntStream.range(0, pattern.definition.parts().size())
          .filter(
              index -> pattern.definition.parts().get(index).rule().alternatives().stream()
                  .anyMatch(alternative -> alternative.block() instanceof ParallelControllerBlock))
          .toArray();
    }

    public int[] parallelParts() {
      return parallelParts.clone();
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
        markDirty(state, this, -1, true, level.getGameTime());
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
      var reference = pattern.modifierReference(level);
      var full = pattern.fullScan(level, reference);
      if (full.state() != VerificationState.CONFIRMED) {
        modifiers.markAllDirty(level.getGameTime());
        verifier.rejectProof(full.failure(), full.failedIndex(), level.getGameTime());
        return false;
      }

      return verifyModifierReference(level, reference);
    }

    public boolean canCommit(ServerLevel level) {
      ServerThreadGuard.require(level, "Structure state accessed");
      LevelState state = LEVELS.get(level);
      return valid
          && published
          && !SmartInterfaceEvents.isDispatching(controller)
          && !RecipeLifecycleEvents.isDispatching()
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
          && pattern.definition == catalog.definitions().get(controller.structureMachineId())
          && pattern.orientation == owner.orientation
          && pattern.chunksAvailable(level);
    }
  }

  private static final class LevelState {
    final Map<Long, Anchor> anchors = new HashMap<>();
    final Map<Long, Set<Registration>> sections = new HashMap<>();
    final Map<Long, Set<Registration>> chunks = new HashMap<>();
    final LinkedHashSet<Work> queue = new LinkedHashSet<>();
    long nextGeneration;

    void enqueue(Anchor anchor) {
      if (anchor.alive && anchor.needsWork()) {
        queue.add(anchor);
      }
    }

    void clear() {
      for (Anchor anchor : anchors.values()) {
        anchor.alive = false;
        if (anchor.registration != null) {
          anchor.registration.valid = false;
        }
        if (anchor.draft != null) {
          anchor.draft.valid = false;
        }
        anchor.builder = null;
        anchor.controller.failPreparation(VerificationFailure.UNVERIFIED);
      }
      anchors.clear();
      sections.clear();
      chunks.clear();
      queue.clear();
    }
  }
}
