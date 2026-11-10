package com.lw.modularmachine.api.assembly;

import com.lw.modularmachine.machine.assembly.AssemblyService;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * Read-only server projection layouts for integration tools. Calls require the
 * server thread and never place blocks, change inventory, or grant production
 * permission. Integrations retain responsibility for placement protection and
 * their resource transactions.
 */
public final class MachineAssemblyApi {
  public static final int MAX_PAGE_PARTS = 256;

  private MachineAssemblyApi() {
  }

  /**
   * Returns the player's prepared projection, or empty while preparing or when
   * its controller, definition, orientation or permissions are no longer valid.
   * Does not load chunks or prepare a new layout.
   */
  public static Optional<AssemblyPlan> inspectProjection(ServerPlayer player) {
    Objects.requireNonNull(player, "player");

    return AssemblyService.inspectProjection(player);
  }

  /**
   * Reads at most {@link #MAX_PAGE_PARTS} positions from the same current
   * projection. The stable request token must match; old layouts return empty.
   */
  public static Optional<AssemblyPage> readProjectionPage(ServerPlayer player, long expectedRequest, int start, int limit) {
    Objects.requireNonNull(player, "player");
    if (expectedRequest < 0 || start < 0 || limit < 1 || limit > MAX_PAGE_PARTS) {
      throw new IllegalArgumentException("Invalid assembly page request");
    }

    return AssemblyService.readProjectionPage(player, expectedRequest, start, limit);
  }

  /**
   * Rechecks the player's exact projection before an integration uses its layout.
   */
  public static boolean isCurrent(ServerPlayer player, long expectedRequest) {
    Objects.requireNonNull(player, "player");
    if (expectedRequest < 0) {
      throw new IllegalArgumentException("Invalid assembly projection request");
    }

    return AssemblyService.isCurrentProjection(player, expectedRequest);
  }
}
