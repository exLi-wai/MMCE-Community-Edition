package com.lw.modularmachine.integration.kubejs;

import org.jetbrains.annotations.ApiStatus;

/**
 * Original static modification entry points, captured with machine
 * declarations.
 */
@ApiStatus.Internal
public final class KubeJSMachineModifier {
  private KubeJSMachineModifier() {
  }

  public static void setMaxParallelism(String machine, int value) {
    KubeJSMachineBuilder.getBuilder(machine).setMaxParallelism(value);
  }

  public static void setInternalParallelism(String machine, int value) {
    KubeJSMachineBuilder.getBuilder(machine).setInternalParallelism(value);
  }

  public static void setMaxThreads(String machine, int value) {
    KubeJSMachineBuilder.getBuilder(machine).setMaxThreads(value);
  }

  public static void addCoreThread(String machine, KubeJSFactoryRecipeThread thread) {
    KubeJSMachineBuilder.getBuilder(machine).addCoreThread(thread);
  }

  public static void setMachinePrefix(String machine, String prefix) {
    KubeJSMachineBuilder.getBuilder(machine).setMachinePrefix(prefix);
  }

  public static void addSmartInterfaceType(String machine, KubeJSSmartInterfaceType type) {
    KubeJSMachineBuilder.getBuilder(machine).addSmartInterfaceType(type);
  }
}
