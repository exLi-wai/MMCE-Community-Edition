package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal immutable default values captured once for an entire definition
 * reload.
 */
@ApiStatus.Internal
public record MachineDefinitionDefaults(
    int casingColor,
    boolean parallelizable,
    int maximumParallelism,
    int maximumFactoryThreads) {
  public MachineDefinitionDefaults {
    if ((casingColor & ~0xFFFFFF) != 0
        || maximumParallelism < 1
        || maximumFactoryThreads < 1
        || maximumFactoryThreads > FactoryThreadStateCodec.MAX_THREADS) {
      throw new IllegalArgumentException("Invalid machine definition defaults");
    }
  }

  public static MachineDefinitionDefaults capture() {
    return new MachineDefinitionDefaults(
        MachineConfig.defaultCasingColor(),
        MachineConfig.machineParallelDefault(),
        MachineConfig.defaultMaxMachineParallelism(),
        MachineConfig.defaultFactoryMaxThreads());
  }
}
