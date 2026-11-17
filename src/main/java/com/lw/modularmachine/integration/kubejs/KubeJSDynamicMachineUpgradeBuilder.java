package com.lw.modularmachine.integration.kubejs;

/**
 * Dynamic item-data capable upgrade type builder. Effects use the existing
 * dynamic evaluator.
 */
public final class KubeJSDynamicMachineUpgradeBuilder {
  private KubeJSDynamicMachineUpgradeBuilder() {
  }

  public static KubeJSUpgradeDeclarations.FixedUpgradeBuilder newBuilder(String name, String localizedName, float level, int maxStack) {
    return KubeJSUpgradeDeclarations.active().newType(name, localizedName, level, maxStack, true);
  }
}
