package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.util.callback.ServerCallbackRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Replaces KubeJS recipe observers as one native listener snapshot. */
final class KubeJSRecipeLifecycleHandlers {
  private static final ResourceLocation DISPATCHER_ID = ResourceLocation
      .fromNamespaceAndPath("kubejs", "mmcece_recipe_lifecycle_dispatcher");
  private static final ResourceLocation FAILURE_POLICY_ID = ResourceLocation
      .fromNamespaceAndPath("kubejs", "mmcece_recipe_failure_policy_dispatcher");

  private KubeJSRecipeLifecycleHandlers() {
  }

  static void install(List<KubeJSRecipeLifecycleRegistration.Handler> handlers, List<KubeJSRecipeLifecycleRegistration.FailurePolicy> policies) {
    ServerCallbackRegistry.requireServer();
    if (handlers.isEmpty()) {
      RecipeLifecycleEvents.remove(DISPATCHER_ID);
    } else {
      var types = new HashSet<RecipeLifecycleEvents.Type>();
      handlers.forEach(handler -> types.addAll(handler.types()));
      RecipeLifecycleEvents.register(DISPATCHER_ID, Set.copyOf(types), new Dispatcher(handlers));
    }
    if (policies.isEmpty()) {
      RecipeLifecycleEvents.removeFailurePolicy(FAILURE_POLICY_ID);
    } else {
      RecipeLifecycleEvents.registerFailurePolicy(FAILURE_POLICY_ID, new FailureDispatcher(policies));
    }
  }

  private static final class Dispatcher implements RecipeLifecycleEvents.Listener {
    private final List<KubeJSRecipeLifecycleRegistration.Handler> handlers;
    private final Set<ResourceLocation> reported = new HashSet<>();

    private Dispatcher(List<KubeJSRecipeLifecycleRegistration.Handler> handlers) {
      this.handlers = List.copyOf(handlers);
    }

    @Override
    public void onEvent(RecipeLifecycleEvents.Context context) {
      for (var handler : handlers) {
        if (!handler.types().contains(context.type())) {
          continue;
        }
        try {
          handler.listener().onEvent(context);
        } catch (RuntimeException failure) {
          if (reported.add(handler.id())) {
            ModularMachine.LOGGER.warn(
                "KubeJS recipe lifecycle handler {} failed for {} at {}",
                handler.id(),
                context.type(),
                context.controllerPosition(),
                failure);
          }
        }
      }
    }
  }

  private static final class FailureDispatcher implements RecipeLifecycleEvents.FailurePolicy {
    private final List<KubeJSRecipeLifecycleRegistration.FailurePolicy> policies;
    private final Set<ResourceLocation> reported = new HashSet<>();

    private FailureDispatcher(List<KubeJSRecipeLifecycleRegistration.FailurePolicy> policies) {
      this.policies = List.copyOf(policies);
    }

    @Override
    public RecipeLifecycleEvents.FailureDecision decide(RecipeLifecycleEvents.FailureContext context) {
      for (var entry : policies) {
        try {
          var decision = entry.policy().decide(context);
          if (decision != null && decision != RecipeLifecycleEvents.FailureDecision.DEFAULT) {
            return decision;
          }
        } catch (RuntimeException failure) {
          if (reported.add(entry.id())) {
            ModularMachine.LOGGER.warn(
                "KubeJS recipe failure policy {} failed for {} at {}",
                entry.id(),
                context.resource(),
                context.controllerPosition(),
                failure);
          }
        }
      }

      return RecipeLifecycleEvents.FailureDecision.DEFAULT;
    }
  }
}
