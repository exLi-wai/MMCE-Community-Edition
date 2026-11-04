package com.lw.modularmachine.api;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.structure.MachineDefinition.Rule;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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

  public record InputShortage(String key, long required, long available, long missing) {
    public InputShortage {
      Objects.requireNonNull(key, "key");
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
                  ItemResourceKey.of(stack).withSelector(port.selector()),
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
              shortages.add(new InputShortage(entry.getKey().debugName(), entry.getValue(), have, amount));
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
    return structureDiagnostic(controller.registration(), level);
  }

  /** Shared by the ordinary and factory Jade diagnostic displays. */
  public static Optional<StructureDiagnostic> structureDiagnostic(StructureService.Registration registration, ServerLevel level) {
    if (registration == null || registration.verifier.failurePosition() < 0) {
      return Optional.empty();
    }
    int index = registration.verifier.failurePosition();
    if (index >= registration.pattern.definition.parts().size()) {
      return Optional.empty();
    }
    var part = registration.pattern.definition.parts().get(index);
    String expected = expected(part.rule());
    var state = level.getBlockState(registration.pattern.position(index));
    var values = state.getValues().entrySet().stream().map(entry -> entry.getKey().getName() + "=" + entry.getValue())
        .sorted().toList();
    String actual = bounded(state.getBlock().getDescriptionId() + (values.isEmpty() ? "" : values.toString()));
    return Optional.of(new StructureDiagnostic(registration.pattern.position(index), part.id(), expected, actual));
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

  /** Resolves stored description ids to client-localized names for display. */
  public static Component localizedDiagnostic(String value) {
    MutableComponent result = Component.empty();
    String[] alternatives = value.split("; ");
    for (int index = 0; index < alternatives.length; index++) {
      if (index > 0) {
        result.append(Component.literal(", "));
      }
      result.append(localizedAlternative(alternatives[index]));
    }
    return result;
  }

  private static Component localizedAlternative(String value) {
    if (value.startsWith("port:")) {
      return Component.literal(value);
    }
    int bracket = value.indexOf('[');
    String key = bracket < 0 ? value : value.substring(0, bracket);
    String suffix = bracket < 0 ? "" : value.substring(bracket);
    if (!key.contains(".")) {
      key = "block." + key.replace(':', '.');
    }
    return Component.translatable(key).append(Component.literal(suffix));
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
