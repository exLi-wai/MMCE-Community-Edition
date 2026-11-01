package com.lw.modularmachine.gametest;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Test-only opaque legacy rotation callback. Neither this block nor its
 * fixtures enter the release JAR.
 */
@EventBusSubscriber(modid = "mmcece")
public final class TestPredicateBlock extends Block {
  public static final AtomicReference<Runnable> CALLBACK = new AtomicReference<>();
  public static TestPredicateBlock INSTANCE;
  public static Item EXTERNAL_WRENCH;

  public TestPredicateBlock() {
    super(Properties.of());
  }

  @SubscribeEvent
  public static void register(RegisterEvent event) {
    event.register(Registries.ITEM, registry -> {
      EXTERNAL_WRENCH = new Item(new Item.Properties().stacksTo(1));
      registry.register(ResourceLocation.fromNamespaceAndPath("mmcece", "test_external_wrench"), EXTERNAL_WRENCH);
    });
    event.register(Registries.BLOCK, registry -> {
      INSTANCE = new TestPredicateBlock();
      registry.register(ResourceLocation.fromNamespaceAndPath("mmcece", "test_reentrant_predicate"), INSTANCE);
    });
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.HORIZONTAL_FACING);
  }

  @Override
  protected BlockState rotate(BlockState state, Rotation rotation) {
    Runnable callback = CALLBACK.getAndSet(null);
    if (callback != null) {
      callback.run();
    }
    return state.setValue(
        BlockStateProperties.HORIZONTAL_FACING,
        rotation.rotate(state.getValue(BlockStateProperties.HORIZONTAL_FACING)));
  }
}
