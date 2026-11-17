package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;

/** Captured core-thread recipe allowlist; no live scheduler is exposed. */
@ApiStatus.Internal
public final class KubeJSFactoryRecipeThread {
  private final Thread owner = Thread.currentThread();
  private final String name;
  private final LinkedHashSet<ResourceLocation> recipes = new LinkedHashSet<>();

  private KubeJSFactoryRecipeThread(String name) {
    if (!RecipeExecutionOptions.validThreadName(name)) {
      throw new IllegalArgumentException("Invalid core thread name");
    }
    this.name = name;
  }

  public static KubeJSFactoryRecipeThread createCoreThread(String name) {
    return new KubeJSFactoryRecipeThread(name);
  }

  public KubeJSFactoryRecipeThread addRecipe(String recipeName) {
    mutable();
    var id = KubeJSMachineBuilder.identifier(recipeName);
    if (recipes.size() >= 1024 && !recipes.contains(id)) {
      throw new IllegalArgumentException("Core thread recipe allowlist exceeds 1024 entries");
    }
    recipes.add(id);
    return this;
  }

  private void mutable() {
    if (Thread.currentThread() != owner) {
      throw new IllegalStateException("Core thread declaration used outside its creating thread");
    }
  }

  JsonObject capture() {
    mutable();
    var result = new JsonObject();
    result.addProperty("id", name);
    result.addProperty("core", true);
    var allowed = new JsonArray();
    recipes.forEach(recipe -> allowed.add(recipe.toString()));
    result.add("allowed_recipes", allowed);
    return result;
  }
}
