package com.lw.modularmachine.machine.smart;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Committed, read-only numerical interface notifications for ordinary and
 * factory controllers.
 */
public final class SmartInterfaceEvents {
  public enum Reason {
    BOUND,
    REBOUND,
    VALUE_CHANGED
  }

  @FunctionalInterface
  public interface Listener {
    void onUpdate(Context context);
  }

  public record Change(
      Reason reason,
      Optional<SmartInterfaceBlockEntity.Binding> before,
      SmartInterfaceBlockEntity.Binding after) {
    public Change {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      if ((reason == Reason.BOUND) != before.isEmpty()
          || before.isPresent()
              && (!before.orElseThrow().controller().equals(after.controller())
                  || before.orElseThrow().revision() >= after.revision())) {
        throw new IllegalArgumentException("Invalid smart interface change");
      }
      if (reason == Reason.VALUE_CHANGED
          && (!before.orElseThrow().machine().equals(after.machine())
              || !before.orElseThrow().type().equals(after.type())
              || Double.compare(before.orElseThrow().value(), after.value()) == 0)) {
        throw new IllegalArgumentException("Invalid smart interface value change");
      }
    }
  }

  public record Context(
      ResourceLocation dimension,
      BlockPos interfacePosition,
      UUID interfaceInstance,
      boolean factory,
      long tick,
      Optional<UUID> actor,
      Change change) {
    public Context {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(interfaceInstance, "interfaceInstance");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(change, "change");
      interfacePosition = interfacePosition.immutable();
      if (tick < 0 || actor.isPresent() && change.reason() != Reason.VALUE_CHANGED) {
        throw new IllegalArgumentException("Invalid smart interface event context");
      }
    }

    public BlockPos controllerPosition() {
      return change.after().controller();
    }

    public ResourceLocation machine() {
      return change.after().machine();
    }
  }

  private static final ServerCallbackRegistry<Listener> HANDLERS = new ServerCallbackRegistry<>(() -> {
  });
  private static final Map<ResourceLocation, Listener> REPORTED_FAILURES = new HashMap<>();
  private static StructureService.StructureSubject activeOwner;

  private SmartInterfaceEvents() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    if (HANDLERS.register(id, listener)) {
      REPORTED_FAILURES.remove(id);
    }
  }

  public static void remove(ResourceLocation id) {
    if (HANDLERS.remove(id)) {
      REPORTED_FAILURES.remove(id);
    }
  }

  public static void invalidate(ResourceLocation id) {
    HANDLERS.invalidate(id);
    REPORTED_FAILURES.remove(id);
  }

  public static boolean hasListeners() {
    return !HANDLERS.isEmpty();
  }

  public static boolean isDispatching() {
    return activeOwner != null;
  }

  public static boolean isDispatching(StructureService.StructureSubject owner) {
    return activeOwner != null && activeOwner == owner;
  }

  /** Internal post-commit entry; callers must not manufacture binding changes. */
  public static void publish(SmartInterfaceBlockEntity source, StructureService.StructureSubject owner, Change change, Optional<UUID> actor) {
    if (!(source.getLevel() instanceof ServerLevel level)) {
      return;
    }
    ServerThreadGuard.require(level, "Smart interface event delivery");
    var registration = owner.structureRegistration();
    if (HANDLERS.isEmpty()
        || isDispatching()
        || source.isRemoved()
        || !level.hasChunkAt(source.getBlockPos())
        || level.getBlockEntity(source.getBlockPos()) != source
        || owner.structureLevel() != level
        || !level.hasChunkAt(owner.structurePosition())
        || level.getBlockEntity(owner.structurePosition()) != owner
        || !change.after().controller().equals(owner.structurePosition())
        || !change.after().machine().equals(owner.structureMachineId())
        || !source.matchesBinding(change.after())
        || registration == null
        || !registration.canCommit(level)) {
      return;
    }
    var context = new Context(
        level.dimension().location(),
        source.getBlockPos(),
        source.instance(),
        owner instanceof FactoryControllerBlockEntity,
        level.getGameTime(),
        actor,
        change);
    var deliveries = HANDLERS.orderedSnapshot();
    activeOwner = owner;
    try {
      for (var delivery : deliveries) {
        try {
          delivery.getValue().onUpdate(context);
        } catch (RuntimeException failure) {
          if (HANDLERS.find(delivery.getKey()) == delivery.getValue()
              && REPORTED_FAILURES.get(delivery.getKey()) != delivery.getValue()) {
            REPORTED_FAILURES.put(delivery.getKey(), delivery.getValue());
            ModularMachine.LOGGER.warn(
                "Smart interface {} notification failed for {} at {}",
                change.reason(),
                delivery.getKey(),
                source.getBlockPos(),
                failure);
          }
        }
      }
    } finally {
      activeOwner = null;
    }
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    HANDLERS.clear();
    REPORTED_FAILURES.clear();
    activeOwner = null;
  }
}
