package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.machine.controller.ControllerQueryService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Read-only loaded-instance queries; neither entry point loads a chunk. */
public final class ControllerQueryApi {
  private ControllerQueryApi() {
  }

  public static Optional<ControllerSnapshot> inspect(ServerLevel level, BlockPos position) {
    return ControllerQueryService.inspect(level, position);
  }

  /**
   * Caller advances the capture using its remaining work budget and closes it.
   */
  public static Optional<ControllerPatternQuery> beginPatternQuery(ServerLevel level, BlockPos position) {
    return ControllerQueryService.beginPatternQuery(level, position);
  }
}
