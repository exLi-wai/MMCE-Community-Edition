package com.lw.modularmachine.api.smart;

import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Committed, read-only numerical interface notifications for ordinary and
 * factory controllers.
 */
public final class SmartInterfaceEvents {
  public enum Reason {
    BOUND,
    REBOUND,
    VALUE_CHANGED
  }

  @FunctionalInterface
  public interface Listener {
    void onUpdate(Context context);
  }

  public record Change(Reason reason, Optional<SmartInterfaceBinding> before, SmartInterfaceBinding after) {
    public Change {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(before, "before");
      Objects.requireNonNull(after, "after");
      if ((reason == Reason.BOUND) != before.isEmpty()
          || before.isPresent()
              && (!before.orElseThrow().controller().equals(after.controller())
                  || before.orElseThrow().revision() >= after.revision())) {
        throw new IllegalArgumentException("Invalid smart interface change");
      }
      if (reason == Reason.VALUE_CHANGED
          && (!before.orElseThrow().machine().equals(after.machine())
              || !before.orElseThrow().type().equals(after.type())
              || Double.compare(before.orElseThrow().value(), after.value()) == 0)) {
        throw new IllegalArgumentException("Invalid smart interface value change");
      }
    }
  }

  public record Context(
      ResourceLocation dimension,
      BlockPos interfacePosition,
      UUID interfaceInstance,
      boolean factory,
      long tick,
      Optional<UUID> actor,
      Change change) {
    public Context {
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(interfaceInstance, "interfaceInstance");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(change, "change");
      interfacePosition = interfacePosition.immutable();
      if (tick < 0 || actor.isPresent() && change.reason() != Reason.VALUE_CHANGED) {
        throw new IllegalArgumentException("Invalid smart interface event context");
      }
    }

    public BlockPos controllerPosition() {
      return change.after().controller();
    }

    public ResourceLocation machine() {
      return change.after().machine();
    }
  }

  private SmartInterfaceEvents() {
  }

  public static void register(ResourceLocation id, Listener listener) {
    SmartInterfaceEventPublisher.register(id, listener);
  }

  public static void remove(ResourceLocation id) {
    SmartInterfaceEventPublisher.remove(id);
  }

  public static void invalidate(ResourceLocation id) {
    SmartInterfaceEventPublisher.invalidate(id);
  }
}
