package com.lw.modularmachine.registry;

import com.lw.modularmachine.machine.blueprint.MachineBlueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MachineDataComponents {
  private static final DeferredRegister<DataComponentType<?>> TYPES = DeferredRegister
      .create(Registries.DATA_COMPONENT_TYPE, "mmcece");
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<MachineBlueprint>> BLUEPRINT = TYPES
      .register(
          "blueprint_orientation",
          () -> DataComponentType.<MachineBlueprint>builder().persistent(MachineBlueprint.CODEC)
              .networkSynchronized(MachineBlueprint.STREAM_CODEC).build());
  /**
   * Ranged construct tool corners; the second one is absent until both are set.
   */
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> SELECTION_FIRST = TYPES
      .register(
          "selection_first",
          () -> DataComponentType.<BlockPos>builder().persistent(BlockPos.CODEC)
              .networkSynchronized(BlockPos.STREAM_CODEC).build());
  public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> SELECTION_SECOND = TYPES
      .register(
          "selection_second",
          () -> DataComponentType.<BlockPos>builder().persistent(BlockPos.CODEC)
              .networkSynchronized(BlockPos.STREAM_CODEC).build());
  private MachineDataComponents() {
  }
  public static void register(IEventBus bus) {
    TYPES.register(bus);
  }
}
