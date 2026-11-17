package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import java.util.stream.Collectors;

/** Internal Java registration, frozen before the first catalog is compiled. */
@ApiStatus.Internal
public final class RecipeSearchResourceRegistry {
  private static final Map<ResourceLocation, Factory> FACTORIES = new LinkedHashMap<>();
  private static List<Registration> frozen;

  static {
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "item"), ItemPaymentSearchResource::new);
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "fluid"), FluidRecipeSearchResource::new);
    register(ResourceLocation.fromNamespaceAndPath("mmcece", "chemical"), ChemicalRecipeSearchResource::new);
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

  static synchronized List<RecipeSearchResource> compile(RecipeCatalog catalog) {
    if (frozen == null) {
      frozen = FACTORIES.entrySet().stream().map(entry -> new Registration(entry.getKey(), entry.getValue())).toList();
    }
    var supported = frozen.stream().map(Registration::id).collect(Collectors.toUnmodifiableSet());
    requireSupported(catalog, supported);
    var compiled = new ArrayList<RecipeSearchResource>(frozen.size());
    for (var registration : frozen) {
      compiled.add(Objects.requireNonNull(registration.factory().compile(catalog), "compiled resource search"));
    }

    return List.copyOf(compiled);
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
