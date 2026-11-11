package com.lw.modularmachine.api.controller;

import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Main-thread ownership query for loaded ordinary and factory controllers. */
public final class ControllerOwnershipApi {
  private ControllerOwnershipApi() {
  }

  public static Optional<ControllerOwnership> inspect(ServerLevel level, BlockPos position) {
    return ControllerOwnershipService.inspect(level, position);
  }
}
