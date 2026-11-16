package com.lw.modularmachine.api.recipe.resource;

import com.lw.modularmachine.machine.component.ResourceTypeRegistry;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.Objects;

/**
 * Java startup registration of typed recipe fields for an already registered
 * resource identity.
 */
public final class MachineRecipeResources {
  private MachineRecipeResources() {
  }

  public static <D> void register(RecipeResourceType<D> type) {
    Objects.requireNonNull(type, "type");
    if (ResourceTypeRegistry.find(type.id()).isEmpty()) {
      throw new IllegalArgumentException("Register the resource identity before its recipe definition: " + type.id());
    }
    RecipeResourceDefinitions.register(type);
  }
}
