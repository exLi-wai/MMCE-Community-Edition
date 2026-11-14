package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;
import com.lw.modularmachine.api.controller.ControllerScriptData;
import com.lw.modularmachine.api.controller.ControllerScriptDataApi;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.api.client.controller.ControllerClientEvents;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.google.gson.JsonElement;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.smart.SmartInterfaceApi;
import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeBusData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeItemData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeKubeJSHandlers;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeStartCost;
import com.lw.modularmachine.machine.recipe.FrozenItemRun.State;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterScriptSources;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.api.recipe.RecipeCommandActions;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.smart.SmartInterfaceEvents;
import dev.latvian.mods.kubejs.core.RecipeManagerKJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.NamespaceFunction;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.recipe.schema.RecipeNamespace;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import dev.latvian.mods.kubejs.script.ScriptType;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;

/**
 * KubeJS-only bridge for fixed upgrade definitions and runtime upgrade
 * handlers.
 */
public final class MMCECEKubeJSPlugin implements KubeJSPlugin {
  public static final EventGroup EVENTS = EventGroup.of("MMCECEUpgradeEvents");
  public static final EventGroup SMART_INTERFACE_EVENTS = EventGroup.of("MMCECESmartInterfaceEvents");
  public static final EventGroup RECIPE_LIFECYCLE_EVENTS = EventGroup.of("MMCECERecipeEvents");
  public static final EventGroup RECIPE_ADAPTER_EVENTS = EventGroup.of("MMCECERecipeAdapterEvents");
  public static final EventGroup MACHINE_EVENTS = EventGroup.of("MMEvents");
  private static final EventHandler REGISTER_MACHINE_HANDLERS = MACHINE_EVENTS
      .common("register", () -> KubeJSMachineRegistration.class);
  private static final EventHandler REGISTER_HANDLERS = EVENTS
      .server("register", () -> KubeJSUpgradeHandlerRegistration.class);
  private static final EventHandler REGISTER_SMART_INTERFACE_HANDLERS = SMART_INTERFACE_EVENTS
      .server("register", () -> KubeJSSmartInterfaceRegistration.class);
  private static final EventHandler REGISTER_RECIPE_LIFECYCLE_HANDLERS = RECIPE_LIFECYCLE_EVENTS
      .server("register", () -> KubeJSRecipeLifecycleRegistration.class);
  private static final EventHandler REGISTER_RECIPE_ADAPTERS = RECIPE_ADAPTER_EVENTS
      .server("register", () -> KubeJSRecipeAdapterRegistration.class);

  @Override
  public void init() {
    KubeJSServerHandlers.register();
  }

  @Override
  public void registerBindings(BindingRegistry registry) {
    if (registry.type() == ScriptType.SERVER) {
      registry.add("RecipeBuilder", KubeJSRecipeBuilder.class);
      registry.add("RecipeModifierBuilder", KubeJSRecipeModifierBuilder.class);
    }
  }

  @Override
  public void beforeRecipeLoading(RecipesKubeEvent event, RecipeManagerKJS manager, Map<ResourceLocation, JsonElement> recipes) {
    hideMachineRecipeFunction(event);
    MachineItemRecipeKubeJSSources.capture(recipes);
  }

  private static void hideMachineRecipeFunction(RecipesKubeEvent event) {
    var id = MachineRecipeRegistries.ITEM_SERIALIZER.getId();
    var namespace = event.recipeSchemaStorage.namespaces.get(id.getNamespace());
    if (namespace == null) {
      return;
    }
    // KubeJSRecipesEventMixin filters the script-facing flat map. Omit the
    // namespace function too, while preserving the schema namespace and internal
    // type lookup for native JSON parsing and RecipeBuilder.build().
    var visibleNamespace = new RecipeNamespace(namespace.storage, namespace.name);
    var functions = new LinkedHashMap<String, RecipeTypeFunction>();
    for (var entry : namespace.entrySet()) {
      if (entry.getValue().serializerKey.location().equals(id)) {
        continue;
      }
      visibleNamespace.put(entry.getKey(), entry.getValue());
      functions.put(entry.getValue().id.getPath(), event.getRecipeFunction(entry.getValue().id.toString()));
    }
    event.getRecipes().put(namespace.name, new NamespaceFunction(visibleNamespace, functions));
  }

  @Override
  public void registerEvents(EventGroupRegistry registry) {
    registry.register(EVENTS);
    registry.register(SMART_INTERFACE_EVENTS);
    registry.register(RECIPE_LIFECYCLE_EVENTS);
    registry.register(RECIPE_ADAPTER_EVENTS);
    registry.register(MACHINE_EVENTS);
  }

  @Override
  public void registerClasses(ClassFilter filter) {
    filter.allow(KubeJSMachineRegistration.class);
    filter.allow(MachineEvents.Context.class);
    filter.allow(MachineEvents.Type.class);
    filter.allow(MachineEvents.Structure.class);
    filter.allow(MachineEvents.Listener.class);
    filter.allow(MachineEvents.ScriptAction.class);
    filter.allow(MachineEvents.ScriptProposal.class);
    filter.allow(RecipeItemCallbacks.Context.class);
    filter.allow(RecipeItemCallbacks.Checker.class);
    filter.allow(RecipeItemCallbacks.Modifier.class);
    filter.allow(RecipeItemCallbacks.InputModifier.class);
    filter.allow(RecipeItemCallbacks.ItemRequirement.class);
    filter.allow(ControllerClientEvents.Context.class);
    filter.allow(ControllerClientEvents.GuiListener.class);
    filter.allow(ControllerClientEvents.GuiEvent.class);
    filter.allow(ControllerClientEvents.ModelListener.class);
    filter.allow(ControllerClientEvents.ModelEvent.class);
    filter.allow(ControllerClientEvents.AnimationListener.class);
    filter.allow(ControllerClientEvents.AnimationEvent.class);
    filter.allow(ControllerClientEvents.Animation.class);
    filter.allow(KubeJSUpgradeHandlerRegistration.class);
    filter.allow(FactoryUpgradeKubeJSHandlers.FixedUpgradeBuilder.class);
    filter.allow(KubeJSSmartInterfaceRegistration.class);
    filter.allow(KubeJSRecipeLifecycleRegistration.class);
    filter.allow(KubeJSRecipeLifecycleRegistration.CommandBuilder.class);
    filter.allow(RecipeCommandActions.Action.class);
    filter.allow(RecipeCommandActions.Source.class);
    filter.allow(RecipeCommandActions.Status.class);
    filter.allow(KubeJSRecipeAdapterRegistration.class);
    filter.allow(KubeJSRecipeBuilder.class);
    filter.allow(KubeJSRecipeBuilder.RecipeBuilder.class);
    filter.allow(KubeJSRecipeModifierBuilder.class);
    filter.allow(KubeJSRecipeModifierBuilder.Builder.class);
    filter.allow(RecipeModifier.class);
    filter.allow(RecipeModifier.Target.class);
    filter.allow(RecipeModifier.Io.class);
    filter.allow(RecipeModifier.Operation.class);
    filter.allow(KubeJSRecipeAdapterRegistration.RecipeAdapterBuilder.class);
    filter.allow(SmartInterfaceApi.class);
    filter.allow(ControllerOwnershipApi.class);
    filter.allow(ControllerOwnership.class);
    filter.allow(ControllerScriptDataApi.class);
    filter.allow(ControllerScriptDataApi.Result.class);
    filter.allow(ControllerScriptData.class);
    filter.allow(SmartInterfaceApi.Snapshot.class);
    filter.allow(SmartInterfaceApi.Value.class);
    filter.allow(SmartInterfaceEvents.Listener.class);
    filter.allow(SmartInterfaceEvents.Context.class);
    filter.allow(SmartInterfaceEvents.Change.class);
    filter.allow(SmartInterfaceEvents.Reason.class);
    filter.allow(SmartInterfaceBinding.class);
    filter.allow(RecipeLifecycleEvents.Listener.class);
    filter.allow(RecipeLifecycleEvents.FailurePolicy.class);
    filter.allow(RecipeLifecycleEvents.FailureContext.class);
    filter.allow(RecipeLifecycleEvents.FailureDecision.class);
    filter.allow(RecipeLifecycleEvents.Context.class);
    filter.allow(RecipeLifecycleEvents.Type.class);
    filter.allow(RecipeLifecycleEvents.ResourceFailure.class);
    filter.allow(RecipeLifecycleEvents.RunSnapshot.class);
    filter.allow(State.class);
    filter.allow(FactoryUpgradeRuntimeData.Preparer.class);
    filter.allow(FactoryUpgradeRuntimeData.Phase.class);
    filter.allow(FactoryUpgradeRuntimeData.Context.class);
    filter.allow(FactoryUpgradeRuntimeData.Run.class);
    filter.allow(FactoryUpgradeRuntimeData.Item.class);
    filter.allow(FactoryUpgradeRuntimeData.ItemUpdate.class);
    filter.allow(FactoryUpgradeRuntimeData.Update.class);
    filter.allow(FactoryDynamicUpgrades.Context.class);
    filter.allow(FactoryDynamicUpgrades.Evaluator.class);
    filter.allow(FactoryUpgradeEntry.class);
    filter.allow(FactoryUpgradeRecipeChecks.Context.class);
    filter.allow(FactoryUpgradeRecipeChecks.Checker.class);
    filter.allow(FactoryUpgradeRecipeChecks.Decision.class);
    filter.allow(FactoryUpgradeEvents.Context.class);
    filter.allow(FactoryUpgradeEvents.Upgrade.class);
    filter.allow(FactoryUpgradeEvents.RunSnapshot.class);
    filter.allow(FactoryUpgradeEvents.Listener.class);
    filter.allow(FactoryUpgradeEvents.Type.class);
    filter.allow(FactoryUpgradeItemData.Context.class);
    filter.allow(FactoryUpgradeItemData.Preparer.class);
    filter.allow(FactoryUpgradeItemData.Update.class);
    filter.allow(FactoryUpgradeBusData.Context.class);
    filter.allow(FactoryUpgradeBusData.Preparer.class);
    filter.allow(FactoryUpgradeBusData.Update.class);
    filter.allow(FactoryUpgradeStartCost.class);
  }

  @Override
  public void afterScriptsLoaded(ScriptManager manager) {
    if (manager.scriptType == ScriptType.CLIENT) {
      var clientRegistration = new KubeJSMachineRegistration(true);
      var clientResult = REGISTER_MACHINE_HANDLERS.post(manager.scriptType, clientRegistration);
      var clientNext = clientRegistration.clientSnapshot();
      KubeJSControllerClientBridge
          .install(clientResult.pass() ? clientNext : KubeJSControllerClientRegistration.Snapshot.EMPTY);
      return;
    }
    if (!manager.scriptType.isServer()) {
      return;
    }
    long generation = KubeJSServerHandlers.begin();
    var machineRegistration = new KubeJSMachineRegistration(false);
    var machineResult = REGISTER_MACHINE_HANDLERS.post(manager.scriptType, machineRegistration);
    var machineHandlers = machineRegistration.serverSnapshot();
    var machineNext = machineResult.pass() ? machineHandlers : List.<KubeJSMachineEventRegistration.Handler>of();
    var machineSmartHandlers = machineRegistration.smartSnapshot();
    var registrations = new KubeJSUpgradeHandlerRegistration();
    var result = REGISTER_HANDLERS.post(manager.scriptType, registrations);
    var next = registrations.valid() && result.pass() ? registrations
        .snapshot() : FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY;
    var smartRegistrations = new KubeJSSmartInterfaceRegistration();
    var smartResult = REGISTER_SMART_INTERFACE_HANDLERS.post(manager.scriptType, smartRegistrations);
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
    var recipeResult = REGISTER_RECIPE_LIFECYCLE_HANDLERS.post(manager.scriptType, recipeRegistrations);
    recipeRegistrations.seal();
    var recipeNext = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .snapshot() : List.<KubeJSRecipeLifecycleRegistration.Handler>of();
    var failurePolicies = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .failurePolicies() : List.<KubeJSRecipeLifecycleRegistration.FailurePolicy>of();
    var recipeCommands = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .commands() : List.<RecipeCommandActions.Action>of();
    var adapterRegistrations = new KubeJSRecipeAdapterRegistration(manager.getRegistries().access());
    KubeJSRecipeItemCallbacks.beginAdapters();
    var adapterResult = REGISTER_RECIPE_ADAPTERS.post(manager.scriptType, adapterRegistrations);
    adapterRegistrations.seal();
    boolean adaptersValid = adapterRegistrations.valid() && adapterResult.pass();
    if (!adaptersValid) {
      KubeJSRecipeItemCallbacks.beginAdapters();
    }
    RecipeAdapterScriptSources
        .install(generation, adaptersValid ? adapterRegistrations.snapshot() : Map.of(), adaptersValid);
    KubeJSServerHandlers.stage(
        new KubeJSServerHandlers.Snapshot(
            generation,
            machineNext,
            next,
            smartNext,
            recipeNext,
            failurePolicies,
            recipeCommands));
  }
}
