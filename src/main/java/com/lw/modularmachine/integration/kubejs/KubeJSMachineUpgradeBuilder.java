package com.lw.modularmachine.integration.kubejs;

/**
 * Original CRT type builder entry point, used inside the upgrade registration
 * event.
 */
public final class KubeJSMachineUpgradeBuilder {
  private KubeJSMachineUpgradeBuilder() {
  }

  public static KubeJSUpgradeDeclarations.FixedUpgradeBuilder newBuilder(String name, String localizedName, float level, int maxStack) {
    return KubeJSUpgradeDeclarations.active().newType(name, localizedName, level, maxStack, false);
  }
}
