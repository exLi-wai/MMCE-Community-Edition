package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.resource.world.WorldPoolResources;
import com.lw.modularmachine.machine.recipe.resource.world.MeteorExecution;
import com.lw.modularmachine.machine.recipe.resource.world.ConstellationExecution;
import com.lw.modularmachine.machine.recipe.resource.keyed.KeyedRecipeResources;
import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.api.recipe.EmberRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.machine.recipe.resource.buffered.BufferedRecipeResources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.HashSet;

/** Internal Java registration, frozen before the first catalog is compiled. */
@ApiStatus.Internal
public final class RecipeSearchResourceRegistry {
  private static final Map<ResourceLocation, Factory> FACTORIES = new LinkedHashMap<>();
  private static final Set<ResourceLocation> BUILT_INS;
  private static List<Registration> frozen;

  static {
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "item"), ItemPaymentSearchResource::new);
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "fluid"), FluidRecipeSearchResource::new);
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "chemical"), ChemicalRecipeSearchResource::new);
    register(RecipeResourceDefinitions.ENVIRONMENT, EnvironmentRecipeSearchResource::new);
    register(RecipeResourceDefinitions.COMPRESSED_AIR, CompressedAirRecipeSearchResource::new);
    register(RecipeResourceDefinitions.AURA_WORLD, WorldPoolResources.AURA::search);
    register(RecipeResourceDefinitions.WILL, WorldPoolResources.WILL::search);
    register(RecipeResourceDefinitions.VIS, WorldPoolResources.VIS::search);
    register(RecipeResourceDefinitions.FLUX, WorldPoolResources.FLUX::search);
    register(RecipeResourceDefinitions.METEOR, MeteorExecution::search);
    register(RecipeResourceDefinitions.ESSENTIA, KeyedRecipeResources.ESSENTIA::search);
    register(RecipeResourceDefinitions.STARLIGHT, KeyedRecipeResources.STARLIGHT::search);
    register(RecipeResourceDefinitions.CONSTELLATION, ConstellationExecution::search);
    register(
        RecipeResourceDefinitions.LIFE_ESSENCE,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.LIFE_ESSENCE,
            BufferedRecipeResources.LIFE_ESSENCE,
            BufferedAmountRecipeData::input));
    register(
        RecipeResourceDefinitions.LASER,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.LASER,
            BufferedRecipeResources.LASER,
            LaserRecipeData::input));
    register(
        RecipeResourceDefinitions.EMBER,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.EMBER,
            BufferedRecipeResources.EMBER,
            EmberRecipeData::micros));
    register(
        RecipeResourceDefinitions.MANA,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.MANA,
            BufferedRecipeResources.MANA,
            BufferedAmountRecipeData::input));
    register(
        RecipeResourceDefinitions.AURA,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.AURA,
            BufferedRecipeResources.AURA,
            BufferedAmountRecipeData::input));
    register(
        RecipeResourceDefinitions.HEAT,
        catalog -> new BufferedInputRecipeSearchResource<>(
            catalog,
            RecipeResourceDefinitions.HEAT,
            BufferedRecipeResources.HEAT,
            BufferedAmountRecipeData::input));
    BUILT_INS = Set.copyOf(FACTORIES.keySet());
  }

  private RecipeSearchResourceRegistry() {
  }

  @FunctionalInterface
  public interface Factory {
    /**
     * Compile immutable data only. The supplied catalog has no live authority;
     * published views share this strategy, while each capture owns its mutable
     * state.
     */
    RecipeSearchResource compile(RecipeCatalog catalog);
  }

  private record Registration(ResourceLocation id, Factory factory) {
  }

  public static <D> void register(RecipeResourceType<D> type, Factory factory) {
    RecipeResourceDefinitions.requireRegistered(type);
    register(type.id(), factory);
  }

  public static synchronized void register(ResourceLocation id, Factory factory) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(factory, "factory");
    if (frozen != null) {
      throw new IllegalStateException("Recipe search resource registration is closed");
    }
    if (FACTORIES.size() >= 256) {
      throw new IllegalArgumentException("Too many recipe search resource strategies");
    }
    if (FACTORIES.putIfAbsent(id, factory) != null) {
      throw new IllegalArgumentException("Duplicate recipe search resource: " + id);
    }
  }

  record Suite(List<RecipeSearchResource> strategies, Map<String, List<Integer>> byRecipe, boolean externalStrategies) {
  }

  static synchronized Suite compile(RecipeCatalog catalog) {
    if (frozen == null) {
      frozen = FACTORIES.entrySet().stream().map(entry -> new Registration(entry.getKey(), entry.getValue())).toList();
    }
    var supported = frozen.stream().map(Registration::id).collect(Collectors.toUnmodifiableSet());
    requireSupported(catalog, supported);
    var active = new HashSet<ResourceLocation>();
    var perRecipe = new LinkedHashMap<String, Set<ResourceLocation>>();
    for (var candidate : catalog.recipes().candidates()) {
      var ids = new HashSet<ResourceLocation>(supported);
      // External strategies can inspect any recipe, including empty declarations.
      ids.removeAll(BUILT_INS);
      var definition = catalog.definition(candidate.id());
      if (definition != null) {
        for (var entry : definition.resourceData().entries()) {
          if (used(entry) && entry.type() != RecipeResourceDefinitions.ITEM) {
            ids.add(entry.type().id());
          }
        }
        if (!definition.itemPayments().isEmpty()) {
          ids.add(RecipeResourceDefinitions.ITEM.id());
        }
        for (var group : definition.ingredientArrays().inputs()) {
          if (group.resource().equals("fluid")) {
            ids.add(RecipeResourceDefinitions.FLUID.id());
          } else if (group.resource().equals("chemical")) {
            ids.add(RecipeResourceDefinitions.CHEMICAL.id());
          }
        }
      }
      if (!catalog.fluidInputRequirements(candidate.id()).isEmpty()
          || !catalog.guaranteedFluidOutputs(candidate.id()).isEmpty()) {
        ids.add(RecipeResourceDefinitions.FLUID.id());
      }
      active.addAll(ids);
      perRecipe.put(candidate.id(), ids);
    }
    var compiled = new ArrayList<RecipeSearchResource>(frozen.size());
    var indices = new LinkedHashMap<ResourceLocation, Integer>();
    for (var registration : frozen) {
      if (active.contains(registration.id())) {
        indices.put(registration.id(), compiled.size());
        compiled.add(Objects.requireNonNull(registration.factory().compile(catalog), "compiled resource search"));
      }
    }
    var masks = new HashMap<Set<ResourceLocation>, List<Integer>>();
    var byRecipe = new LinkedHashMap<String, List<Integer>>();
    perRecipe.forEach(
        (id, mask) -> byRecipe.put(
            id,
            masks.computeIfAbsent(
                mask,
                keys -> indices.entrySet().stream().filter(entry -> keys.contains(entry.getKey()))
                    .map(Map.Entry::getValue).toList())));
    return new Suite(List.copyOf(compiled), Map.copyOf(byRecipe), !BUILT_INS.containsAll(supported));
  }

  private static <D> boolean used(RecipeResources.Entry<D> entry) {
    return !entry.type().isEmpty(entry.data());
  }

  private static void requireSupported(RecipeCatalog catalog, Set<ResourceLocation> supported) {
    for (var candidate : catalog.recipes().candidates()) {
      var definition = catalog.definition(candidate.id());
      if (definition != null) {
        for (var entry : definition.resourceData().entries()) {
          requireSupported(entry, supported);
        }
      }
    }
  }

  private static <D> void requireSupported(RecipeResources.Entry<D> entry, Set<ResourceLocation> supported) {
    if (!entry.type().isEmpty(entry.data())
        && entry.type() != RecipeResourceDefinitions.ITEM
        && entry.type() != RecipeResourceDefinitions.ENERGY
        && !supported.contains(entry.type().id())) {
      throw new IllegalArgumentException("Resource has no installed search and output strategy: " + entry.type().id());
    }
  }
}
