package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonElement;
import com.lw.modularmachine.machine.recipe.MachineRecipeRegistries;
import dev.latvian.mods.kubejs.recipe.NamespaceFunction;
import dev.latvian.mods.kubejs.recipe.RecipeTypeFunction;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.kubejs.recipe.schema.RecipeNamespace;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.jetbrains.annotations.ApiStatus;

/**
 * Native recipe visibility and capture at the existing before-loading callback.
 */
@ApiStatus.Internal
final class KubeJSRecipeLoading {
  private KubeJSRecipeLoading() {
  }

  static void beforeRecipeLoading(RecipesKubeEvent event, RecipeManager manager, Map<ResourceLocation, JsonElement> recipes) {
    hideMachineRecipeFunction(event);
    var replacements = (MachineItemRecipeKubeJSSources.Replacements) event;
    MachineItemRecipeKubeJSSources.capture(manager, recipes, replacements::MMCECE$replacement);
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
}
