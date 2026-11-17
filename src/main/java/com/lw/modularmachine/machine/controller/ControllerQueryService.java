package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.controller.ControllerPatternQuery;
import com.lw.modularmachine.api.controller.ControllerPatternSnapshot;
import com.lw.modularmachine.api.controller.ControllerSnapshot;
import com.lw.modularmachine.api.controller.ControllerSnapshot.DynamicPattern;
import com.lw.modularmachine.api.controller.ControllerSnapshot.MachineUpgrade;
import com.lw.modularmachine.api.controller.ControllerSnapshot.RecipeThread;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.RunSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Main-thread detached capture; explicit pattern captures own and release their
 * cursors.
 */
@ApiStatus.Internal
public final class ControllerQueryService {
  private ControllerQueryService() {
  }

  private static UpgradeBusOwner owner(ServerLevel level, BlockPos position) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(position, "position");
    ServerThreadGuard.require(level, "Controller queried");
    if (!level.hasChunkAt(position)) {
      return null;
    }
    var entity = level.getBlockEntity(position);

    return entity instanceof UpgradeBusOwner owner
        && (owner instanceof MachineControllerBlockEntity || owner instanceof FactoryControllerBlockEntity)
        && !owner.structureRemoved()
        && owner.matchesStructureBlock(level.getBlockState(position)) ? owner : null;
  }

  public static Optional<ControllerSnapshot> inspect(ServerLevel level, BlockPos position) {
    var owner = owner(level, position);
    if (owner == null) {
      return Optional.empty();
    }
    var registration = owner.structureRegistration();
    boolean formed = registration != null && registration.currentForObservation(level);
    var threads = threads(owner);
    var runs = threads.stream().flatMap(thread -> thread.activeRecipes().stream()).toList();
    var definition = StructureService.definition(owner.machineId());
    int extraThreads = owner instanceof FactoryControllerBlockEntity && definition != null ? Math.toIntExact(
        threads.stream().filter(thread -> !definition.factoryPreset().isCore(thread.threadId())).count()) : 0;
    var patterns = new LinkedHashMap<String, DynamicPattern>();
    if (formed) {
      for (var pattern : registration.pattern.definition.sourceDefinition().dynamicPatterns()) {
        patterns.put(
            pattern.id(),
            new DynamicPattern(
                pattern.id(),
                registration.dynamicLengths().getOrDefault(pattern.id(), pattern.minimum()),
                pattern.minimum(),
                pattern.maximum(),
                registration.pattern.orientation,
                pattern.startOffset(),
                pattern.step()));
      }
    }

    return Optional.of(
        new ControllerSnapshot(
            level.dimension().location(),
            position,
            owner.machineId(),
            owner instanceof FactoryControllerBlockEntity,
            formed,
            Math.max(0, level.getGameTime()),
            registration == null ? -1 : registration.generation(),
            runs,
            threads,
            extraThreads,
            formed ? registration.activeModifierIds(level) : List.of(),
            formed ? registration.observedRecipeModifiers(level) : List.of(),
            upgrades(owner, level),
            patterns));
  }

  private static List<RecipeThread> threads(UpgradeBusOwner owner) {
    if (owner instanceof FactoryControllerBlockEntity factory) {
      return factory.scheduler().channels().stream()
          .map(
              thread -> new RecipeThread(
                  thread.threadId(),
                  thread.enabled(),
                  thread.priority(),
                  thread.parallelLimit(),
                  Optional.ofNullable(thread.lockedRecipe()),
                  thread.state().name(),
                  thread.reason().name(),
                  thread.revision(),
                  factory.runSet(thread.threadId()).runs().stream().map(ControllerQueryService::run).toList()))
          .toList();
    }
    var controller = (MachineControllerBlockEntity) owner;
    var runs = controller.runningRecipe().map(ControllerQueryService::run).stream().toList();

    return List.of(
        new RecipeThread(
            "main",
            true,
            0,
            controller.upgradeSnapshot().parallelLimit(),
            Optional.empty(),
            runs.isEmpty() ? "IDLE" : "RUNNING",
            controller.recipeRunStatus().name(),
            0,
            runs));
  }

  private static RunSnapshot run(FrozenRecipeRun run) {
    return new RunSnapshot(
        run.runId(),
        run.recipeId(),
        run.state(),
        run.parallelInstances(),
        run.progress(),
        run.duration(),
        run.lastProgressTick());
  }

  private static List<MachineUpgrade> upgrades(UpgradeBusOwner owner, ServerLevel level) {
    var installed = new ArrayList<MachineUpgrade>();
    var registration = owner.structureRegistration();
    if (registration == null || !registration.currentForObservation(level)) {
      return List.of();
    }
    for (var position : owner.upgradeBusPositions()) {
      if (!level.hasChunkAt(position)
          || !(level.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus)
          || bus.isRemoved()) {
        continue;
      }
      for (var upgrade : bus.observedUpgradeRestrictions(owner)) {
        installed.add(new MachineUpgrade(upgrade.definition(), upgrade.count(), bus.getBlockPos()));
      }
    }

    return List.copyOf(installed);
  }

  public static Optional<ControllerPatternQuery> beginPatternQuery(ServerLevel level, BlockPos position) {
    var owner = owner(level, position);
    var registration = owner == null ? null : owner.structureRegistration();
    if (registration == null || !registration.currentForObservation(level)) {
      return Optional.empty();
    }

    return Optional.of(new PatternCapture(level, owner, registration));
  }

  private static final class PatternCapture implements ControllerPatternQuery {
    private ServerLevel level;
    private UpgradeBusOwner owner;
    private StructureService.Registration registration;
    private final long revision;
    private ControllerPatternSnapshot.Capture parts = new ControllerPatternSnapshot.Capture();
    private ControllerPatternSnapshot snapshot;
    private Status status = Status.PENDING;
    private int cursor;

    private PatternCapture(ServerLevel level, UpgradeBusOwner owner, StructureService.Registration registration) {
      this.level = level;
      this.owner = owner;
      this.registration = registration;
      revision = registration.verifier.revision();
    }

    @Override
    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative controller pattern query budget");
      }
      if (status != Status.PENDING) {
        return 0;
      }
      ServerThreadGuard.require(level, "Controller pattern query advanced");
      if (!current()) {
        release(Status.STALE);
        return 0;
      }
      int work = 0;
      int size = registration.pattern.definition.parts().size();
      while (cursor < size && work < budget) {
        var position = registration.pattern.position(cursor++);
        var state = level.getBlockState(position);
        if (!state.isAir()) {
          parts.add(position, state);
        }
        work++;
      }
      if (!current()) {
        release(Status.STALE);
      } else if (cursor == size) {
        snapshot = parts.finish(owner.machineId(), registration.generation());
        release(Status.COMPLETE);
      }

      return work;
    }

    private boolean current() {
      return !owner.isRemoved()
          && level.hasChunkAt(owner.getBlockPos())
          && level.getBlockEntity(owner.getBlockPos()) == owner
          && owner.structureRegistration() == registration
          && registration.verifier.revision() == revision
          && registration.currentForObservation(level);
    }

    private void release(Status next) {
      status = next;
      level = null;
      owner = null;
      registration = null;
      parts = null;
    }

    @Override
    public Status status() {
      return status;
    }

    @Override
    public Optional<ControllerPatternSnapshot> result() {
      return Optional.ofNullable(snapshot);
    }

    @Override
    public void close() {
      if (level != null) {
        ServerThreadGuard.require(level, "Controller pattern query closed");
      }
      snapshot = null;
      release(Status.CLOSED);
    }
  }
}
