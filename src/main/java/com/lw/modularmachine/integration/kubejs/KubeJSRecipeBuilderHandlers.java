package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal generation-installed inline RecipeBuilder observers at existing
 * committed lifecycle boundaries.
 */
@ApiStatus.Internal
public final class KubeJSRecipeBuilderHandlers {
  public record Handler(
      ResourceLocation id,
      ResourceLocation machine,
      ResourceLocation recipe,
      RecipeLifecycleEvents.Type type,
      boolean factoryOnly,
      RecipeLifecycleEvents.Listener listener) {
  }

  private KubeJSRecipeBuilderHandlers() {
  }

  static void install(List<Handler> handlers) {
    if (handlers.isEmpty()) {
      RecipeLifecyclePublisher.installDeclaredObserver(Set.of(), null);
      return;
    }
    var types = new HashSet<RecipeLifecycleEvents.Type>();
    handlers.forEach(handler -> types.add(handler.type()));
    var reported = new HashSet<ResourceLocation>();
    RecipeLifecyclePublisher.installDeclaredObserver(Set.copyOf(types), context -> {
      for (var handler : handlers) {
        if (handler.type() != context.type()
            || !handler.machine().equals(context.machine())
            || handler.factoryOnly() && !context.factory()
            || context.before().stream().noneMatch(run -> run.recipeId().equals(handler.recipe()))
                && context.after().stream().noneMatch(run -> run.recipeId().equals(handler.recipe()))) {
          continue;
        }
        try {
          handler.listener().onEvent(context);
        } catch (RuntimeException failure) {
          if (reported.add(handler.id())) {
            ModularMachine.LOGGER.warn("Inline RecipeBuilder handler {} failed", handler.id(), failure);
          }
        }
      }
    });
  }
}
