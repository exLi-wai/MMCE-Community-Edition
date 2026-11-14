package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable dispatch; proposals resolve after all callback gates end.
 */
@ApiStatus.Internal
public final class MachineEventPublisher {
  private record Handler(
      Set<MachineEvents.Type> types,
      MachineEvents.Listener listener,
      MachineEvents.ScriptAction action) {
    Handler {
      types = Set.copyOf(types);
      if (types.isEmpty() || (listener == null) == (action == null)) {
        throw new IllegalArgumentException("Invalid machine event subscription");
      }
    }
  }

  private static final ServerCallbackRegistry<Handler> HANDLERS = new ServerCallbackRegistry<>(() -> {
  });
  private static final Map<StructureService.StructureSubject, MachineEvents.Structure> STRUCTURES = new WeakHashMap<>();
  private static final Map<StructureService.StructureSubject, Map<MachineEvents.Type, Long>> TICKS = new WeakHashMap<>();
  private static final Map<ResourceLocation, Handler> REPORTED_FAILURES = new HashMap<>();
  private static StructureService.StructureSubject activeOwner;

  private MachineEventPublisher() {
  }

  public static void register(ResourceLocation id, Set<MachineEvents.Type> types, MachineEvents.Listener listener) {
    HANDLERS.register(id, new Handler(types, Objects.requireNonNull(listener, "listener"), null));
    REPORTED_FAILURES.remove(id);
  }

  public static void registerAction(ResourceLocation id, Set<MachineEvents.Type> types, MachineEvents.ScriptAction action) {
    HANDLERS.register(id, new Handler(types, null, Objects.requireNonNull(action, "action")));
    REPORTED_FAILURES.remove(id);
  }

  public static void remove(ResourceLocation id) {
    HANDLERS.remove(id);
    REPORTED_FAILURES.remove(id);
  }

  public static boolean isDispatching() {
    return activeOwner != null;
  }

  public static boolean isDispatching(StructureService.StructureSubject owner) {
    return activeOwner == owner;
  }

  public static void clear() {
    HANDLERS.clear();
    STRUCTURES.clear();
    TICKS.clear();
    REPORTED_FAILURES.clear();
    activeOwner = null;
  }

  private static MachineEvents.Structure structure(StructureService.StructureSubject owner, ServerLevel level) {
    VerificationState state;
    VerificationFailure failure;
    StructureService.Registration registration;
    if (owner instanceof MachineControllerBlockEntity ordinary) {
      state = ordinary.verificationState();
      failure = ordinary.verificationFailure();
      registration = ordinary.structureRegistration();
    } else if (owner instanceof FactoryControllerBlockEntity factory) {
      state = factory.verificationState();
      failure = factory.verificationFailure();
      registration = factory.structureRegistration();
    } else {
      throw new IllegalArgumentException("Unsupported machine event owner");
    }
    boolean formed = registration != null && registration.canCommit(level);
    return new MachineEvents.Structure(
        state,
        failure,
        formed,
        registration == null ? -1 : registration.generation(),
        registration == null ? Map.of() : registration.dynamicLengths());
  }

  public static void observeStructure(StructureService.StructureSubject owner) {
    if (HANDLERS.isEmpty()
        || RecipeLifecyclePublisher.isDispatching()
        || !(owner.structureLevel() instanceof ServerLevel level)
        || !current(owner, level)) {
      return;
    }
    var next = structure(owner, level);
    if (next.state() == VerificationState.PENDING
        || next.state() == VerificationState.CONFIRMED && !next.formed()
        || next.failure() == VerificationFailure.CHUNK_UNAVAILABLE
        || next.failure() == VerificationFailure.UNVERIFIED
        || next.failure() == VerificationFailure.VERIFICATION_TIMEOUT) {
      return;
    }
    var before = STRUCTURES.put(owner, next);
    if (next.equals(before)) {
      return;
    }
    dispatch(owner, level, MachineEvents.Type.STRUCTURE_UPDATE, Optional.ofNullable(before), next);
    if (next.formed() && (before == null || !before.formed())) {
      dispatch(owner, level, MachineEvents.Type.STRUCTURE_FORMED, Optional.ofNullable(before), next);
    }
  }

  public static void tick(StructureService.StructureSubject owner, MachineEvents.Type type) {
    if (HANDLERS.isEmpty()
        || RecipeLifecyclePublisher.isDispatching()
        || !(owner.structureLevel() instanceof ServerLevel level)
        || !current(owner, level)) {
      return;
    }
    ServerThreadGuard.require(level, "Machine event tick");
    var ticks = TICKS.computeIfAbsent(owner, ignored -> new EnumMap<>(MachineEvents.Type.class));
    if (Objects.equals(ticks.put(type, level.getGameTime()), level.getGameTime())) {
      return;
    }
    observeStructure(owner);
    var current = structure(owner, level);
    if (current.formed()) {
      dispatch(owner, level, type, Optional.of(current), current);
    }
  }

  private static boolean current(StructureService.StructureSubject owner, ServerLevel level) {
    return !owner.structureRemoved()
        && level.hasChunkAt(owner.structurePosition())
        && level.getBlockEntity(owner.structurePosition()) == owner;
  }

  private static void dispatch(StructureService.StructureSubject owner, ServerLevel level, MachineEvents.Type type, Optional<MachineEvents.Structure> before, MachineEvents.Structure after) {
    ServerThreadGuard.require(level, "Machine event dispatch");
    var deliveries = HANDLERS.orderedSnapshot().stream().filter(entry -> entry.getValue().types().contains(type))
        .toList();
    if (deliveries.isEmpty()) {
      return;
    }
    for (var delivery : deliveries) {
      if (!current(owner, level) || HANDLERS.find(delivery.getKey()) != delivery.getValue()) {
        continue;
      }
      var data = MachineControllerScriptDataService.inspect(level, owner.structurePosition());
      var context = new MachineEvents.Context(
          type,
          level.dimension().location(),
          owner.structurePosition(),
          owner.structureMachineId(),
          owner instanceof FactoryControllerBlockEntity,
          level.getGameTime(),
          before,
          after,
          data,
          ControllerOwnershipService.snapshot(level, (ControllerOwnershipSubject) owner));
      MachineEvents.ScriptProposal proposal = null;
      activeOwner = owner;
      try {
        if (delivery.getValue().listener() != null) {
          delivery.getValue().listener().onEvent(context);
        } else {
          proposal = delivery.getValue().action().propose(context);
        }
      } catch (RuntimeException failure) {
        if (REPORTED_FAILURES.get(delivery.getKey()) != delivery.getValue()) {
          REPORTED_FAILURES.put(delivery.getKey(), delivery.getValue());
          ModularMachine.LOGGER.warn("Machine event {} failed for {}", type, delivery.getKey(), failure);
        }
      } finally {
        activeOwner = null;
      }
      if (proposal != null
          && data.isPresent()
          && current(owner, level)
          && HANDLERS.find(delivery.getKey()) == delivery.getValue()
          && structure(owner, level).equals(after)) {
        var captured = data.orElseThrow();
        MachineControllerScriptDataService.replace(
            level,
            owner.structurePosition(),
            captured.stateId(),
            captured.revision(),
            proposal.customData(),
            proposal.customModifiers());
      }
    }
  }
}
