package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.machine.controller.ControllerInspection;
import com.lw.modularmachine.api.recipe.RecipeRunState;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
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

  public record RunSnapshot(UUID runId, ResourceLocation recipeId, RecipeRunState state, int progress, int duration) {
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
      RecipeRunStatus recipeRunStatus,
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

  /**
   * Reads a loaded ordinary controller on its owning server thread, without
   * loading chunks. Returns empty for an unavailable or non-controller position.
   */
  public static Optional<Snapshot> inspect(ServerLevel level, BlockPos position) {
    return ControllerInspection.inspect(level, position);
  }

  /** Reads a loaded ordinary or factory controller without loading chunks. */
  public static Optional<StructureDiagnostic> structureDiagnostic(ServerLevel level, BlockPos position) {
    return ControllerInspection.structureDiagnostic(level, position);
  }

  /**
   * Reorientation always goes through the player permission and current-structure
   * checks on the owning server thread. Resolves a loaded ordinary or factory
   * controller at the position without loading chunks.
   */
  public static ReorientationResult reorient(ServerLevel level, BlockPos position, MachineOrientation target, ServerPlayer player) {
    return switch (StructureService.reorient(level, position, target, player)) {
      case CHANGED -> ReorientationResult.CHANGED;
      case UNCHANGED -> ReorientationResult.UNCHANGED;
      case NOT_ALLOWED -> ReorientationResult.NOT_ALLOWED;
      case STALE_CONTROLLER -> ReorientationResult.STALE_CONTROLLER;
      case WRITE_FAILED -> ReorientationResult.WRITE_FAILED;
      case PERMISSION_DENIED -> ReorientationResult.PERMISSION_DENIED;
    };
  }

}
