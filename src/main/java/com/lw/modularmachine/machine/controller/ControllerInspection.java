package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.structure.MachineDefinition.Rule;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import com.lw.modularmachine.api.controller.MachineControllerApi.Snapshot;
import com.lw.modularmachine.api.controller.MachineControllerApi.RunSnapshot;
import com.lw.modularmachine.api.controller.MachineControllerApi.StructureDiagnostic;
import com.lw.modularmachine.api.controller.MachineControllerApi.RecipeInputDiagnostic;
import com.lw.modularmachine.api.controller.MachineControllerApi.InputShortage;
import org.jetbrains.annotations.ApiStatus;

/**
 * Main-thread read-only controller snapshots and bounded structure/input
 * diagnostics.
 */
@ApiStatus.Internal
public final class ControllerInspection {
  private ControllerInspection() {
  }

  /** Reads the current ordinary controller without loading chunks. */
  public static Optional<Snapshot> inspect(ServerLevel level, BlockPos position) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(position, "position");
    ServerThreadGuard.require(level, "Controller inspection");
    if (!level.hasChunkAt(position)
        || !(level.getBlockEntity(position) instanceof MachineControllerBlockEntity controller)) {
      return Optional.empty();
    }

    return inspect(controller, true);
  }

  /**
   * Lightweight menu/observer view omits inventory-based missing-input analysis.
   */
  public static Optional<Snapshot> inspectForMenu(MachineControllerBlockEntity controller) {
    return inspect(controller, false);
  }

  private static Optional<Snapshot> inspect(MachineControllerBlockEntity controller, boolean includeInputDiagnostic) {
    Objects.requireNonNull(controller, "controller");
    if (!(controller.getLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("Controller API inspected outside server thread");
    }
    BlockPos position = controller.getBlockPos();
    if (controller.isRemoved() || !level.hasChunkAt(position) || level.getBlockEntity(position) != controller) {
      return Optional.empty();
    }
    var run = controller.runningRecipe().map(ControllerInspection::snapshot);
    var diagnostic = structureDiagnostic(controller, level);
    Optional<RecipeInputDiagnostic> inputDiagnostic = includeInputDiagnostic ? recipeInputDiagnostic(
        controller) : Optional.empty();
    return Optional.of(
        new Snapshot(
            position,
            controller.machineId(),
            StructureService.definitionEpoch(),
            MachineControllerBlock.orientationOf(level.getBlockState(position)),
            controller.verificationState(),
            controller.verificationFailure(),
            diagnostic,
            inputDiagnostic,
            controller.canCommitProduction(),
            controller.successfulTransfers(),
            controller.bindingRebuilds(),
            controller.recipeRunStatus(),
            run,
            controller.recipeInterfaceFailure()));
  }

  private static Optional<RecipeInputDiagnostic> recipeInputDiagnostic(MachineControllerBlockEntity controller) {
    if (controller.runningRecipe().isPresent()) {
      return Optional.empty();
    }
    var catalog = MachineRecipeService.catalogFor(controller).orElse(null);
    var ticket = RecipeExecutionTicket.capture(controller).orElse(null);
    if (catalog == null || ticket == null || !catalog.isCurrent()) {
      return Optional.empty();
    }
    for (var candidate : catalog.recipes().candidates()) {
      var required = new LinkedHashMap<ItemResourceKey, Long>();
      for (var input : candidate.inputs()) {
        required.merge(input.key(), input.count(), Math::addExact);
      }
      List<InputShortage> closest = List.of();
      BigInteger minimum = null;
      for (int group : ticket.inputGroups()) {
        var available = new HashMap<ItemResourceKey, Long>();
        for (var port : ticket.withInputGroup(group).ports()) {
          if (port.kind() != PortKind.ITEM_INPUT) {
            continue;
          }
          for (var stack : port.items()) {
            if (!stack.isEmpty()) {
              available.merge(
                  RecipeStartPlan.inputKey(candidate, ItemResourceKey.of(stack).withSelector(port.selector())),
                  (long) stack.getCount(),
                  Math::addExact);
            }
          }
        }
        var shortages = new ArrayList<InputShortage>();
        var missing = BigInteger.ZERO;
        for (var entry : required.entrySet()) {
          long have = available.getOrDefault(entry.getKey(), 0L);
          if (have < entry.getValue()) {
            long amount = entry.getValue() - have;
            missing = missing.add(BigInteger.valueOf(amount));
            if (shortages.size() < 8) {
              shortages.add(
                  new InputShortage(
                      entry.getKey().debugName(),
                      entry.getValue(),
                      have,
                      amount,
                      entry.getKey().descriptionId()));
            }
          }
        }
        if (minimum == null || missing.compareTo(minimum) < 0) {
          minimum = missing;
          closest = shortages;
        }
        if (missing.signum() == 0) {
          break;
        }
      }
      return Optional.of(new RecipeInputDiagnostic(ResourceLocation.parse(candidate.id()), closest));
    }
    return Optional.empty();
  }

  private static Optional<StructureDiagnostic> structureDiagnostic(MachineControllerBlockEntity controller, ServerLevel level) {
    return structureDiagnostic(level, controller.getBlockPos());
  }

  /** Reads a loaded ordinary or factory controller without loading chunks. */
  public static Optional<StructureDiagnostic> structureDiagnostic(ServerLevel level, BlockPos position) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(position, "position");
    ServerThreadGuard.require(level, "Controller structure diagnostic");
    if (!level.hasChunkAt(position)) {
      return Optional.empty();
    }
    var entity = level.getBlockEntity(position);
    if ((entity instanceof MachineControllerBlockEntity || entity instanceof FactoryControllerBlockEntity)
        && entity instanceof StructureService.StructureSubject controller
        && !controller.structureRemoved()) {
      var dynamic = StructureService.dynamicFailure(controller);
      if (dynamic != null && level.hasChunkAt(dynamic.position())) {
        return Optional.of(diagnostic(level, dynamic.position(), dynamic.partId(), dynamic.expected()));
      }
      return structureDiagnostic(controller.structureRegistration(), level);
    }
    return Optional.empty();
  }

  private static Optional<StructureDiagnostic> structureDiagnostic(StructureService.Registration registration, ServerLevel level) {
    if (registration == null || registration.verifier.failurePosition() < 0) {
      return Optional.empty();
    }
    int index = registration.verifier.failurePosition();
    if (index >= registration.pattern.definition.parts().size()) {
      return Optional.empty();
    }
    var position = registration.pattern.position(index);
    if (!level.hasChunkAt(position)) {
      return Optional.empty();
    }
    var part = registration.pattern.definition.parts().get(index);
    return Optional.of(diagnostic(level, position, part.id(), part.rule()));
  }

  private static StructureDiagnostic diagnostic(ServerLevel level, BlockPos position, String partId, Rule rule) {
    String expected = expected(rule);
    var state = level.getBlockState(position);
    var values = state.getValues().entrySet().stream().map(entry -> entry.getKey().getName() + "=" + entry.getValue())
        .sorted().toList();
    String actual = bounded(state.getBlock().getDescriptionId() + (values.isEmpty() ? "" : values.toString()));
    return new StructureDiagnostic(position, partId, expected, actual);
  }

  private static String expected(Rule rule) {
    if (rule.port() != null) {
      return "port:" + rule.port().path();
    }
    var alternatives = rule.alternatives().stream().map(alternative -> {
      var properties = alternative.properties().entrySet().stream()
          .map(entry -> entry.getKey().getName() + "=" + entry.getValue()).sorted().toList();
      return alternative.block().getDescriptionId() + (properties.isEmpty() ? "" : properties.toString());
    }).sorted().toList();
    return bounded(String.join("; ", alternatives));
  }

  private static String bounded(String value) {
    return value.length() <= 512 ? value : value.substring(0, 509) + "...";
  }

  private static RunSnapshot snapshot(FrozenRecipeRun run) {
    return new RunSnapshot(run.runId(), run.recipeId(), run.state(), run.progress(), run.duration());
  }
}
