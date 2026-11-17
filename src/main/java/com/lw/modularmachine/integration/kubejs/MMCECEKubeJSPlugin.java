package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import dev.latvian.mods.kubejs.core.RecipeManagerKJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroupRegistry;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/**
 * KubeJS plugin lifecycle and stable event identities; implementation lives in
 * focused bridges.
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

  private static final KubeJSScriptRegistration SCRIPT_REGISTRATION = new KubeJSScriptRegistration(
      REGISTER_MACHINE_HANDLERS,
      REGISTER_HANDLERS,
      REGISTER_SMART_INTERFACE_HANDLERS,
      REGISTER_RECIPE_LIFECYCLE_HANDLERS,
      REGISTER_RECIPE_ADAPTERS);

  @Override
  public void init() {
    KubeJSServerHandlers.register();
  }

  @Override
  public void registerBindings(BindingRegistry registry) {
    KubeJSScriptBindings.registerBindings(registry);
  }

  @Override
  public void beforeRecipeLoading(RecipesKubeEvent event, RecipeManagerKJS manager, Map<ResourceLocation, JsonElement> recipes) {
    KubeJSRecipeLoading.beforeRecipeLoading(event, recipes);
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
    KubeJSScriptBindings.registerClasses(filter);
  }

  @Override
  public void beforeScriptsLoaded(ScriptManager manager) {
    SCRIPT_REGISTRATION.beforeScriptsLoaded(manager);
  }

  @Override
  public void afterScriptsLoaded(ScriptManager manager) {
    SCRIPT_REGISTRATION.afterScriptsLoaded(manager);
  }
}
