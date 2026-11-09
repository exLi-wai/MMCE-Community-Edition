package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.controller.ControllerScriptDataApi.Result;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal loaded-controller lookup, revision checks and controlled state
 * replacement.
 */
@ApiStatus.Internal
public final class MachineControllerScriptDataService {
  private MachineControllerScriptDataService() {
  }

  private static ControllerScriptStateOwner owner(ServerLevel level, BlockPos position) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(position, "position");
    ServerThreadGuard.require(level, "Controller script state accessed");
    if (!level.hasChunkAt(position)) {
      return null;
    }
    var entity = level.getBlockEntity(position);

    return entity instanceof ControllerScriptStateOwner owner
        && !owner.structureRemoved()
        && owner.matchesStructureBlock(level.getBlockState(position))
        && owner.scriptStateAvailable() ? owner : null;
  }

  public static Optional<ControllerScriptData> inspect(ServerLevel level, BlockPos position) {
    var owner = owner(level, position);

    return owner == null ? Optional.empty() : Optional.of(owner.scriptState().snapshot());
  }

  public static Result replace(ServerLevel level, BlockPos position, UUID expectedStateId, long expectedRevision, String customData, Map<ResourceLocation, RecipeModifier> customModifiers) {
    var owner = owner(level, position);
    if (owner == null) {
      return Result.UNAVAILABLE;
    }
    var current = owner.scriptState();
    if (expectedRevision < 0
        || current.revision() != expectedRevision
        || !current.snapshot().stateId().equals(expectedStateId)) {
      return Result.STALE;
    }
    if (!owner.scriptStateEditingAllowed()) {
      return Result.BUSY;
    }
    ControllerScriptState replacement;
    try {
      replacement = current.with(customData, customModifiers);
    } catch (RuntimeException invalid) {
      return Result.INVALID;
    }
    if (replacement == current) {
      return Result.UNCHANGED;
    }

    return owner.replaceControllerScriptState(current, replacement) ? Result.APPLIED : Result.BUSY;
  }

  public static List<RecipeModifier> modifiers(StructureService.StructureSubject owner) {
    return owner instanceof ControllerScriptStateOwner scripted ? scripted.scriptState().modifiers() : List.of();
  }

  public static long revision(StructureService.StructureSubject owner) {
    return owner instanceof ControllerScriptStateOwner scripted ? scripted.scriptState().revision() : 0;
  }

  public static boolean clear(StructureService.StructureSubject owner) {
    return owner instanceof ControllerScriptStateOwner scripted && scripted.clearControllerScriptState();
  }
}
