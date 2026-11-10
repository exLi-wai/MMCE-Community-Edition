package com.lw.modularmachine.api.recipe.adapter;

import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterRegistry;
import com.lw.modularmachine.util.Mods;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/**
 * Startup-only adapter registration; imports still use native reload
 * validation.
 */
public final class MachineRecipeAdapters {
  private MachineRecipeAdapters() {
  }

  /** Register a factory before the first adapter import freezes the registry. */
  public static void register(ResourceLocation id, Supplier<MachineRecipeAdapter> factory) {
    RecipeAdapterRegistry.register(id, factory);
  }

  /**
   * Registers optional metadata without creating the adapter. Resolution calls
   * {@link Mods#isLoaded()} before invoking the factory. Optional integrations
   * keep their own dependent class initialization behind that loading gate.
   */
  public static void registerOptional(ResourceLocation id, Mods requiredMod, Supplier<MachineRecipeAdapter> factory) {
    RecipeAdapterRegistry.registerOptional(id, Objects.requireNonNull(requiredMod, "requiredMod"), factory);
  }
}
