package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.machine.upgrade.UpgradeScriptHandlers;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import dev.latvian.mods.kubejs.event.KubeEvent;

/**
 * Mutable builder used only while KubeJS evaluates its server script
 * registration event.
 */
public final class KubeJSUpgradeHandlerRegistration implements KubeEvent {
  private final KubeJSUpgradeDeclarations.Registration handlers = KubeJSUpgradeDeclarations.registration();

  public KubeJSUpgradeHandlerRegistration() {
    handlers.activate();
  }

  public KubeJSUpgradeDeclarations.FixedUpgradeBuilder fixedUpgrade(String item, String id) {
    return handlers.fixedUpgrade(item, id);
  }

  public void runtimeData(String id, UpgradeRuntimeData.Preparer handler) {
    handlers.runtimeData(id, handler);
  }

  public void dynamicEffect(String id, DynamicUpgrades.Evaluator handler) {
    handlers.dynamic(id, handler);
  }

  public void recipeCheck(String id, UpgradeRecipeChecks.Checker handler) {
    handlers.recipeCheck(id, handler);
  }

  public void lifecycle(String id, UpgradeEvents.Listener handler) {
    handlers.lifecycle(id, handler);
  }

  public void startItemData(String id, UpgradeItemData.Preparer handler) {
    handlers.startItemData(id, handler);
  }

  public void startBusData(String id, UpgradeBusData.Preparer handler) {
    handlers.startBusData(id, handler);
  }

  boolean valid() {
    return handlers.valid();
  }

  UpgradeScriptHandlers.Snapshot snapshot() {
    return handlers.snapshot();
  }
}
