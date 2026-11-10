package com.lw.modularmachine.machine.recipe.adapter;

import com.lw.modularmachine.api.recipe.adapter.MachineRecipeAdapter;
import com.lw.modularmachine.util.Mods;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal startup metadata; no adapter instances or world state are retained.
 */
@ApiStatus.Internal
public final class RecipeAdapterRegistry {
  private static final int MAX_ADAPTERS = 128;
  private static final Map<ResourceLocation, Entry> REGISTRATIONS = new LinkedHashMap<>();
  private static Map<ResourceLocation, Entry> frozen;

  static {
    register(SmeltingRecipeAdapter.ID, SmeltingRecipeAdapter::new);
  }

  private record Entry(Mods requiredMod, Supplier<MachineRecipeAdapter> factory) {
    private Entry {
      Objects.requireNonNull(factory, "factory");
    }
  }

  private RecipeAdapterRegistry() {
  }

  public static void register(ResourceLocation id, Supplier<MachineRecipeAdapter> factory) {
    register(id, new Entry(null, factory));
  }

  public static void registerOptional(ResourceLocation id, Mods requiredMod, Supplier<MachineRecipeAdapter> factory) {
    register(id, new Entry(Objects.requireNonNull(requiredMod, "requiredMod"), factory));
  }

  private static synchronized void register(ResourceLocation id, Entry entry) {
    Objects.requireNonNull(id, "id");
    if (frozen != null) {
      throw new IllegalStateException("Recipe adapter registration is already frozen");
    }
    if (REGISTRATIONS.containsKey(id)) {
      throw new IllegalArgumentException("Duplicate recipe adapter: " + id);
    }
    if (REGISTRATIONS.size() >= MAX_ADAPTERS) {
      throw new IllegalArgumentException("Too many recipe adapters");
    }
    REGISTRATIONS.put(id, entry);
  }

  public static synchronized boolean known(ResourceLocation id) {
    Objects.requireNonNull(id, "id");

    return REGISTRATIONS.containsKey(id);
  }

  public static synchronized void freeze() {
    if (frozen == null) {
      frozen = Map.copyOf(REGISTRATIONS);
    }
  }

  public static Optional<MachineRecipeAdapter> resolve(ResourceLocation id) {
    Objects.requireNonNull(id, "id");
    final Entry entry;
    synchronized (RecipeAdapterRegistry.class) {
      freeze();
      entry = frozen.get(id);
    }
    if (entry == null) {
      throw new IllegalArgumentException("Unknown recipe adapter: " + id);
    }
    if (entry.requiredMod() != null && !entry.requiredMod().isLoaded()) {
      return Optional.empty();
    }

    return Optional.of(Objects.requireNonNull(entry.factory().get(), "Recipe adapter factory returned null: " + id));
  }
}
