package com.lw.modularmachine.machine.blueprint;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;

/** Client screen hook keeps client classes out of the shared item. */
public final class BlueprintPreviewActions {
  private static Consumer<ResourceLocation> opener = machine -> {
  };
  private BlueprintPreviewActions() {
  }
  public static void setClientOpener(Consumer<ResourceLocation> value) {
    opener = Objects.requireNonNull(value);
  }
  public static void openClient(ResourceLocation machine) {
    opener.accept(machine);
  }
}
