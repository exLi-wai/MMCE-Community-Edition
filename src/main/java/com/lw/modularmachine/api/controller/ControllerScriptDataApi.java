package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Main-thread controller script data access with revisions and callback
 * reentrancy gates.
 */
public final class ControllerScriptDataApi {
  public enum Result {
    APPLIED,
    UNCHANGED,
    STALE,
    BUSY,
    UNAVAILABLE,
    INVALID
  }

  private ControllerScriptDataApi() {
  }

  public static Optional<ControllerScriptData> inspect(ServerLevel level, BlockPos position) {
    return MachineControllerScriptDataService.inspect(level, position);
  }

  public static Result replace(ServerLevel level, BlockPos position, UUID expectedStateId, long expectedRevision, String customData, Map<ResourceLocation, RecipeModifier> customModifiers) {
    return MachineControllerScriptDataService
        .replace(level, position, expectedStateId, expectedRevision, customData, customModifiers);
  }
}
