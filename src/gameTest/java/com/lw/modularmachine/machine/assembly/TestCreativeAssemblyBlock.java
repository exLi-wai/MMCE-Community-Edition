package com.lw.modularmachine.machine.assembly;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Test-only native placement callback for failures after the chunk write. */
@EventBusSubscriber(modid = "mmcece")
public final class TestCreativeAssemblyBlock extends Block {
  static final AtomicReference<BiConsumer<Level, BlockPos>> CALLBACK = new AtomicReference<>();
  static TestCreativeAssemblyBlock INSTANCE;

  private TestCreativeAssemblyBlock() {
    super(Properties.of());
  }

  @SubscribeEvent
  public static void register(RegisterEvent event) {
    event.register(Registries.BLOCK, registry -> {
      INSTANCE = new TestCreativeAssemblyBlock();
      registry.register(ResourceLocation.fromNamespaceAndPath("mmcece", "test_creative_assembly_failure"), INSTANCE);
    });
  }

  @Override
  protected void onPlace(BlockState state, Level level, BlockPos position, BlockState before, boolean moving) {
    var callback = CALLBACK.getAndSet(null);
    if (callback != null) {
      callback.accept(level, position);
    }
  }
}
