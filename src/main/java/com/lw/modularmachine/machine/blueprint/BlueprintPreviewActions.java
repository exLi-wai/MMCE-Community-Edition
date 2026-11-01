package com.lw.modularmachine.machine.blueprint;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** Client screen hook keeps client classes out of the shared item. */
public final class BlueprintPreviewActions {
  private static BiConsumer<ResourceLocation, InteractionHand> opener = (machine, hand) -> {
  };
  private BlueprintPreviewActions() {
  }
  public static void setClientOpener(BiConsumer<ResourceLocation, InteractionHand> value) {
    opener = Objects.requireNonNull(value);
  }
  public static void openClient(ResourceLocation machine, InteractionHand hand) {
    opener.accept(machine, hand);
  }
}
