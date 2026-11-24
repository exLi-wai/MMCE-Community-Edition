package com.lw.modularmachine.gametest;

import com.lw.modularmachine.machine.recipe.search.ItemOutputLayout;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * A test-only component that writes to another slot during output
 * materialization.
 */
@EventBusSubscriber(modid = "mmcece")
public final class TestOutputComponent {
  static final ThreadLocal<Runnable> ON_OUTPUT_COPY = new ThreadLocal<>();
  static final DataComponentType<Integer> TYPE = DataComponentType.<Integer>builder()
      .persistent(Codec.INT.xmap(value -> value, TestOutputComponent::encode)).build();

  @SubscribeEvent
  public static void register(RegisterEvent event) {
    event.register(
        Registries.DATA_COMPONENT_TYPE,
        registry -> registry.register(ResourceLocation.fromNamespaceAndPath("mmcece", "test_output_component"), TYPE));
  }

  private static int encode(int value) {
    var callback = ON_OUTPUT_COPY.get();
    if (callback != null
        && StackWalker.getInstance().walk(
            frames -> frames.anyMatch(
                frame -> frame.getClassName().equals(ItemOutputLayout.class.getName())
                    && frame.getMethodName().equals("prepare")))) {
      ON_OUTPUT_COPY.remove();
      callback.run();
    }
    return value;
  }

  private TestOutputComponent() {
  }
}
