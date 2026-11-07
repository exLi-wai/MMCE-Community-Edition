package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeKubeJSHandlers;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;
import dev.latvian.mods.kubejs.event.KubeEvent;

/**
 * Mutable builder used only while KubeJS evaluates its server script
 * registration event.
 */
public final class KubeJSUpgradeHandlerRegistration implements KubeEvent {
  private final FactoryUpgradeKubeJSHandlers.Registration handlers = FactoryUpgradeKubeJSHandlers.registration();

  public FactoryUpgradeKubeJSHandlers.FixedUpgradeBuilder fixedUpgrade(String id, String item) {
    return handlers.fixedUpgrade(id, item);
  }

  public void runtimeData(String id, FactoryUpgradeRuntimeData.Preparer handler) {
    handlers.runtimeData(id, handler);
  }

  public void dynamicEffect(String id, FactoryDynamicUpgrades.Evaluator handler) {
    handlers.dynamic(id, handler);
  }

  public void recipeCheck(String id, FactoryUpgradeRecipeChecks.Checker handler) {
    handlers.recipeCheck(id, handler);
  }

  public void lifecycle(String id, FactoryUpgradeEvents.Listener handler) {
    handlers.lifecycle(id, handler);
  }

  public void startItemData(String id, FactoryUpgradeItemData.Preparer handler) {
    handlers.startItemData(id, handler);
  }

  public void startBusData(String id, FactoryUpgradeBusData.Preparer handler) {
    handlers.startBusData(id, handler);
  }

  boolean valid() {
    return handlers.valid();
  }

  FactoryUpgradeKubeJSHandlers.Snapshot snapshot() {
    return handlers.snapshot();
  }
}
