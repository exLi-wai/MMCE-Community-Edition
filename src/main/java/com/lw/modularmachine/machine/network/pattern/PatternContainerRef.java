package com.lw.modularmachine.machine.network.pattern;

import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal persistent physical source. A live ticket must additionally retain
 * the provider BE instance and the state's transient instanceId/revision.
 */
@ApiStatus.Internal
public record PatternContainerRef(UUID provider, int slot, long generation) {
  public PatternContainerRef {
    Objects.requireNonNull(provider, "provider");
    if (slot < 0 || slot >= PatternProviderState.SLOT_COUNT || generation < 1) {
      throw new IllegalArgumentException("Invalid pattern container address");
    }
  }
}
