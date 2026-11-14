package com.lw.modularmachine.machine.selection;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Internal client screen hook; shared item code never loads client GUI classes.
 */
@ApiStatus.Internal
public final class RangedConstructToolActions {
  private static Consumer<BlockPos> clientUse = ignored -> {
  };

  private RangedConstructToolActions() {
  }

  public static void setClientUse(Consumer<BlockPos> value) {
    clientUse = Objects.requireNonNull(value);
  }

  public static void useClient(@Nullable BlockPos position) {
    clientUse.accept(position == null ? null : position.immutable());
  }
}
