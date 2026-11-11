package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.api.controller.ControllerOwnership;
import com.lw.modularmachine.api.controller.ControllerOwnershipApi;

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
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
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
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * KubeJS-only bridge for fixed upgrade definitions and runtime upgrade
 * handlers.
 */
public final class MMCECEKubeJSPlugin implements KubeJSPlugin {
  public static final EventGroup EVENTS = EventGroup.of("MMCECEUpgradeEvents");
  public static final EventGroup SMART_INTERFACE_EVENTS = EventGroup.of("MMCECESmartInterfaceEvents");
  public static final EventGroup RECIPE_LIFECYCLE_EVENTS = EventGroup.of("MMCECERecipeEvents");
  public static final EventGroup RECIPE_ADAPTER_EVENTS = EventGroup.of("MMCECERecipeAdapterEvents");
  private static final EventHandler REGISTER_HANDLERS = EVENTS
      .server("register", () -> KubeJSUpgradeHandlerRegistration.class);
  private static final EventHandler REGISTER_SMART_INTERFACE_HANDLERS = SMART_INTERFACE_EVENTS
      .server("register", () -> KubeJSSmartInterfaceRegistration.class);
  private static final EventHandler REGISTER_RECIPE_LIFECYCLE_HANDLERS = RECIPE_LIFECYCLE_EVENTS
      .server("register", () -> KubeJSRecipeLifecycleRegistration.class);
  private static final EventHandler REGISTER_RECIPE_ADAPTERS = RECIPE_ADAPTER_EVENTS
      .server("register", () -> KubeJSRecipeAdapterRegistration.class);
  private static final AtomicLong SCRIPT_GENERATION = new AtomicLong();

  @Override
  public void registerBindings(BindingRegistry registry) {
    if (registry.type() == ScriptType.SERVER) {
      registry.add("RecipeBuilder", KubeJSRecipeBuilder.class);
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
  }

  @Override
  public void registerClasses(ClassFilter filter) {
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
    filter.allow(KubeJSRecipeAdapterRegistration.RecipeAdapterBuilder.class);
    filter.allow(SmartInterfaceApi.class);
    filter.allow(ControllerOwnershipApi.class);
    filter.allow(ControllerOwnership.class);
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
    if (!manager.scriptType.isServer()) {
      return;
    }
    long generation = SCRIPT_GENERATION.incrementAndGet();
    var registrations = new KubeJSUpgradeHandlerRegistration();
    var result = REGISTER_HANDLERS.post(manager.scriptType, registrations);
    var next = registrations.valid() && result.pass() ? registrations
        .snapshot() : FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY;
    var smartRegistrations = new KubeJSSmartInterfaceRegistration();
    var smartResult = REGISTER_SMART_INTERFACE_HANDLERS.post(manager.scriptType, smartRegistrations);
    var smartNext = smartRegistrations.valid() && smartResult.pass() ? smartRegistrations
        .snapshot() : List.<KubeJSSmartInterfaceRegistration.Handler>of();
    var recipeRegistrations = new KubeJSRecipeLifecycleRegistration();
    var recipeResult = REGISTER_RECIPE_LIFECYCLE_HANDLERS.post(manager.scriptType, recipeRegistrations);
    recipeRegistrations.seal();
    var recipeNext = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .snapshot() : List.<KubeJSRecipeLifecycleRegistration.Handler>of();
    var failurePolicies = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .failurePolicies() : List.<KubeJSRecipeLifecycleRegistration.FailurePolicy>of();
    var recipeCommands = recipeRegistrations.valid() && recipeResult.pass() ? recipeRegistrations
        .commands() : List.<RecipeCommandActions.Action>of();
    var adapterRegistrations = new KubeJSRecipeAdapterRegistration();
    var adapterResult = REGISTER_RECIPE_ADAPTERS.post(manager.scriptType, adapterRegistrations);
    adapterRegistrations.seal();
    boolean adaptersValid = adapterRegistrations.valid() && adapterResult.pass();
    RecipeAdapterScriptSources
        .install(generation, adaptersValid ? adapterRegistrations.snapshot() : Map.of(), adaptersValid);
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null) {
      ModularMachine.LOGGER.warn("KubeJS server handlers were not installed without a running server");
      return;
    }
    Runnable install = () -> {
      if (ServerLifecycleHooks.getCurrentServer() != server || SCRIPT_GENERATION.get() != generation) {
        return;
      }
      try {
        FactoryUpgradeKubeJSHandlers.install(next);
      } catch (RuntimeException failure) {
        FactoryUpgradeKubeJSHandlers.install(FactoryUpgradeKubeJSHandlers.Snapshot.EMPTY);
        ModularMachine.LOGGER.warn("KubeJS runtime upgrade handler registration failed", failure);
      }
      try {
        KubeJSSmartInterfaceHandlers.install(smartNext);
      } catch (RuntimeException failure) {
        KubeJSSmartInterfaceHandlers.install(List.of());
        ModularMachine.LOGGER.warn("KubeJS smart interface handler registration failed", failure);
      }
      try {
        KubeJSRecipeLifecycleHandlers.install(recipeNext, failurePolicies);
      } catch (RuntimeException failure) {
        KubeJSRecipeLifecycleHandlers.install(List.of(), List.of());
        ModularMachine.LOGGER.warn("KubeJS recipe lifecycle handler registration failed", failure);
      }
      try {
        RecipeCommandService.installKubeJS(recipeCommands);
      } catch (RuntimeException failure) {
        RecipeCommandService.installKubeJS(List.of());
        ModularMachine.LOGGER.warn("KubeJS recipe command registration failed", failure);
      }
    };
    if (server.isSameThread()) {
      install.run();
    } else {
      server.execute(install);
    }
  }
}
