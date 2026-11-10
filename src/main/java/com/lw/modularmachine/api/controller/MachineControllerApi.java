package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemRecipePlan;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.structure.MachineDefinition.Rule;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Supported server-side entry points for inspecting and turning machine
 * controllers.
 */
public final class MachineControllerApi {
  private MachineControllerApi() {
  }

  public enum ReorientationResult {
    CHANGED,
    UNCHANGED,
    NOT_ALLOWED,
    STALE_CONTROLLER,
    WRITE_FAILED,
    PERMISSION_DENIED
  }

  public record RunSnapshot(
      UUID runId,
      ResourceLocation recipeId,
      FrozenItemRun.State state,
      int progress,
      int duration) {
  }

  public record StructureDiagnostic(BlockPos position, String partId, String expected, String actual) {
    public StructureDiagnostic {
      position = Objects.requireNonNull(position, "position").immutable();
      Objects.requireNonNull(partId, "partId");
      Objects.requireNonNull(expected, "expected");
      Objects.requireNonNull(actual, "actual");
    }
  }

  public record InputShortage(String key, long required, long available, long missing, String descriptionId) {
    public InputShortage(String key, long required, long available, long missing) {
      this(key, required, available, missing, "");
    }

    public InputShortage {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(descriptionId, "descriptionId");
      if (required <= 0 || available < 0 || missing <= 0 || missing != required - Math.min(required, available)) {
        throw new IllegalArgumentException("Invalid input shortage");
      }
    }
  }

  public record RecipeInputDiagnostic(ResourceLocation recipeId, List<InputShortage> shortages) {
    public RecipeInputDiagnostic {
      Objects.requireNonNull(recipeId, "recipeId");
      shortages = List.copyOf(shortages);
      if (shortages.size() > 8) {
        throw new IllegalArgumentException("Too many input shortages");
      }
    }
  }

  /**
   * Immutable view captured on the owning server thread; it does not expose the
   * live BE.
   */
  public record Snapshot(
      BlockPos position,
      ResourceLocation machineId,
      long definitionEpoch,
      MachineOrientation orientation,
      VerificationState structureState,
      VerificationFailure structureFailure,
      Optional<StructureDiagnostic> structureDiagnostic,
      Optional<RecipeInputDiagnostic> recipeInputDiagnostic,
      boolean productionAllowed,
      long successfulTransfers,
      long bindingRebuilds,
      ItemRunEngine.Result recipeRunStatus,
      Optional<RunSnapshot> runningRun) {
    public Snapshot {
      position = Objects.requireNonNull(position, "position").immutable();
      Objects.requireNonNull(machineId, "machineId");
      if (definitionEpoch < 0) {
        throw new IllegalArgumentException("definitionEpoch must be non-negative");
      }
      Objects.requireNonNull(orientation, "orientation");
      Objects.requireNonNull(structureState, "structureState");
      Objects.requireNonNull(structureFailure, "structureFailure");
      Objects.requireNonNull(structureDiagnostic, "structureDiagnostic");
      Objects.requireNonNull(recipeInputDiagnostic, "recipeInputDiagnostic");
      Objects.requireNonNull(recipeRunStatus, "recipeRunStatus");
      Objects.requireNonNull(runningRun, "runningRun");
    }
  }

  /** Returns empty for a removed, unloaded, or client-side controller. */
  public static Optional<Snapshot> inspect(MachineControllerBlockEntity controller) {
    return inspect(controller, true);
  }

  /** Lightweight menu view omits inventory-based missing-input analysis. */
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
    var run = controller.runningRecipe().map(MachineControllerApi::snapshot);
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
            run));
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
                  ItemRecipePlan.inputKey(candidate, ItemResourceKey.of(stack).withSelector(port.selector())),
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

  /**
   * Reorientation always goes through the player permission and current-structure
   * checks. Accepts ordinary and factory controllers.
   */
  public static ReorientationResult reorient(StructureService.StructureSubject controller, MachineOrientation target, ServerPlayer player) {
    Objects.requireNonNull(controller, "controller");
    return switch (StructureService.reorient(controller, target, player)) {
      case CHANGED -> ReorientationResult.CHANGED;
      case UNCHANGED -> ReorientationResult.UNCHANGED;
      case NOT_ALLOWED -> ReorientationResult.NOT_ALLOWED;
      case STALE_CONTROLLER -> ReorientationResult.STALE_CONTROLLER;
      case WRITE_FAILED -> ReorientationResult.WRITE_FAILED;
      case PERMISSION_DENIED -> ReorientationResult.PERMISSION_DENIED;
    };
  }

  private static RunSnapshot snapshot(FrozenItemRun run) {
    return new RunSnapshot(run.runId(), run.recipeId(), run.state(), run.progress(), run.duration());
  }
}
