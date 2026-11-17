package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.machine.recipe.RecipeScriptCallbacks;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.upgrade.UpgradeScriptHandlers;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterScriptSources;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ordered script declaration capture, sealing and generation staging;
 * publication stays in the server handlers.
 */
@ApiStatus.Internal
final class KubeJSScriptRegistration {
  private final EventHandler machineHandlers;
  private final EventHandler upgradeHandlers;
  private final EventHandler smartHandlers;
  private final EventHandler recipeHandlers;
  private final EventHandler recipeAdapters;
  private KubeJSMachineBuilder.Session machineDeclarations;

  KubeJSScriptRegistration(EventHandler machineHandlers, EventHandler upgradeHandlers, EventHandler smartHandlers, EventHandler recipeHandlers, EventHandler recipeAdapters) {
    this.machineHandlers = machineHandlers;
    this.upgradeHandlers = upgradeHandlers;
    this.smartHandlers = smartHandlers;
    this.recipeHandlers = recipeHandlers;
    this.recipeAdapters = recipeAdapters;
  }

  void afterScriptsLoaded(ScriptManager manager) {
    if (manager.scriptType == ScriptType.CLIENT) {
      installClient(manager);
      return;
    }
    if (manager.scriptType.isServer()) {
      try {
        stageServer(manager);
      } finally {
        if (machineDeclarations != null) {
          machineDeclarations.abort();
          machineDeclarations = null;
        }
      }
    }
  }

  void beforeScriptsLoaded(ScriptManager manager) {
    if (manager.scriptType.isServer()) {
      if (machineDeclarations != null) {
        machineDeclarations.abort();
      }
      machineDeclarations = KubeJSMachineBuilder.begin();
    }
  }

  private void installClient(ScriptManager manager) {
    var clientRegistration = new KubeJSMachineRegistration(true);
    var clientResult = machineHandlers.post(manager.scriptType, clientRegistration);
    var clientNext = clientRegistration.clientSnapshot();
    KubeJSControllerClientBridge
        .install(clientResult.pass() ? clientNext : KubeJSControllerClientRegistration.Snapshot.EMPTY);
  }

  private void stageServer(ScriptManager manager) {
    long generation = KubeJSServerHandlers.begin();
    var machineRegistration = new KubeJSMachineRegistration(false);
    var machineResult = machineHandlers.post(manager.scriptType, machineRegistration);
    var machineSnapshot = machineRegistration.serverSnapshot();
    var machineNext = machineResult.pass() ? machineSnapshot : List.<KubeJSMachineEventRegistration.Handler>of();
    var machineSmartHandlers = machineRegistration.smartSnapshot();
    var registrations = new KubeJSUpgradeHandlerRegistration();
    var result = upgradeHandlers.post(manager.scriptType, registrations);
    var upgradeSnapshot = registrations.snapshot();
    var next = registrations.valid() && result.pass() ? upgradeSnapshot : UpgradeScriptHandlers.Snapshot.EMPTY;
    var smartRegistrations = new KubeJSSmartInterfaceRegistration();
    var smartResult = smartHandlers.post(manager.scriptType, smartRegistrations);
    smartRegistrations.seal();
    var smartNext = new ArrayList<KubeJSSmartInterfaceRegistration.Handler>();
    if (smartRegistrations.valid() && smartResult.pass()) {
      smartNext.addAll(smartRegistrations.snapshot());
    }
    if (machineResult.pass()) {
      smartNext.addAll(machineSmartHandlers);
    }
    if (smartNext.stream().map(KubeJSSmartInterfaceRegistration.Handler::id).distinct().count() != smartNext.size()) {
      ModularMachine.LOGGER
          .warn("Duplicate smart interface handler IDs across MMEvents and MMCECESmartInterfaceEvents");
      smartNext.clear();
    }
    var recipeRegistrations = new KubeJSRecipeLifecycleRegistration();
    var recipeResult = recipeHandlers.post(manager.scriptType, recipeRegistrations);
    recipeRegistrations.seal();
    var recipeNext = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .snapshot() : List.<KubeJSRecipeLifecycleRegistration.Handler>of();
    var failurePolicies = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .failurePolicies() : List.<KubeJSRecipeLifecycleRegistration.FailurePolicy>of();
    var recipeCommands = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .commands() : List.<RecipeCommandActions.Action>of();
    var adapterRegistrations = new KubeJSRecipeAdapterRegistration(manager.getRegistries().access());
    RecipeScriptCallbacks.beginAdapters();
    var adapterResult = recipeAdapters.post(manager.scriptType, adapterRegistrations);
    adapterRegistrations.seal();
    boolean adaptersValid = adapterRegistrations.valid() && adapterResult.pass();
    if (!adaptersValid) {
      RecipeScriptCallbacks.beginAdapters();
    }
    RecipeAdapterScriptSources
        .install(generation, adaptersValid ? adapterRegistrations.snapshot() : Map.of(), adaptersValid);
    KubeJSServerHandlers.stage(
        new KubeJSServerHandlers.Snapshot(
            generation,
            machineNext,
            next,
            registrations.valid() && result.pass() && manager.scriptType.console.errors.isEmpty(),
            smartNext,
            recipeNext,
            failurePolicies,
            recipeCommands));
    if (machineDeclarations != null
        && manager.scriptType.console.errors.isEmpty()
        && machineResult.pass()
        && result.pass()
        && smartResult.pass()
        && recipeResult.pass()
        && adapterResult.pass()) {
      machineDeclarations.closeAndPublish();
    }
  }
}
