package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Immutable machine recipe catalog and resource search indexes, published by
 * identity.
 */
@ApiStatus.Internal
public final class RecipeCatalog {
  private final ResourceLocation machineId;
  private final CompiledRecipeSet<ItemResourceKey> recipes;
  private final Map<String, List<FluidStack>> fluidInputs;
  private final Map<String, List<FluidStack>> fluidOutputs;
  private final BooleanSupplier authority;
  private final RecipeCatalog root;
  private final Map<String, MachineRecipe> definitions;
  private final List<RecipeModifier> contextRules;
  private final Map<String, RecipeSelectors> selectors;
  private final Set<ItemResourceKey> itemInputAliases;
  private final FluidRecipeIndex fluidIndex;

  public RecipeCatalog(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates) {
    this(machineId, new CompiledRecipeSet<>(candidates), Map.of(), Map.of(), () -> true);
  }

  public RecipeCatalog(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates, BooleanSupplier authority) {
    this(machineId, new CompiledRecipeSet<>(candidates), Map.of(), Map.of(), authority);
  }

  public RecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, BooleanSupplier authority) {
    this(machineId, recipes, Map.of(), Map.of(), authority);
  }

  public RecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority) {
    this(machineId, recipes, fluidInputs, fluidOutputs, authority, null, Map.of(), List.of());
  }

  public RecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority, RecipeCatalog root, Map<String, MachineRecipe> definitions, List<RecipeModifier> contextRules) {
    this(machineId, recipes, fluidInputs, fluidOutputs, authority, root, definitions, contextRules, Map.of());
  }

  public RecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority, RecipeCatalog root, Map<String, MachineRecipe> definitions, List<RecipeModifier> contextRules, Map<String, RecipeSelectors> selectors) {
    this.machineId = Objects.requireNonNull(machineId, "machineId");
    this.recipes = Objects.requireNonNull(recipes, "recipes");
    var copied = new HashMap<String, List<FluidStack>>();
    fluidInputs.forEach((id, values) -> copied.put(id, values.stream().map(FluidStack::copy).toList()));
    this.fluidInputs = Map.copyOf(copied);
    var outputCopies = new HashMap<String, List<FluidStack>>();
    fluidOutputs.forEach((id, values) -> outputCopies.put(id, values.stream().map(FluidStack::copy).toList()));
    this.fluidOutputs = Map.copyOf(outputCopies);
    this.authority = Objects.requireNonNull(authority, "authority");
    this.root = root == null ? this : root.root();
    this.definitions = Map.copyOf(definitions);
    this.contextRules = List.copyOf(contextRules);
    this.selectors = Map.copyOf(selectors);
    var aliases = new HashSet<ItemResourceKey>();
    for (var candidate : recipes.candidates()) {
      for (var input : candidate.inputs()) {
        if (input.key().ignoresComponents()) {
          if (candidate.inputs().size() != 1) {
            throw new IllegalArgumentException("Item-only matching requires a single item input row");
          }
          aliases.add(input.key());
        }
      }
    }
    itemInputAliases = Set.copyOf(aliases);
    fluidIndex = new FluidRecipeIndex(recipes.candidates(), this.fluidInputs, this.selectors);
  }

  Set<ItemResourceKey> itemInputAliases() {
    return itemInputAliases;
  }

  List<RecipeCandidate<ItemResourceKey>> candidatesForInputs(Map<ItemResourceKey, Long> items, Set<FluidRecipeIndex.Key> fluids, Set<String> allowedIds) {
    int fluidCount = fluidIndex.candidateCount(fluids);
    if (fluidCount == Integer.MAX_VALUE) {
      return recipes.candidatesFor(items, allowedIds);
    }
    // Start with the narrower index. A few fluid recipes must not cause a copy of
    // every unanchored item recipe, nor should pure-fluid searches scan them all.
    if ((allowedIds == null || allowedIds.size() > 1) && fluidCount < recipes.inputCandidateCount(items)) {
      return recipes.candidatesFor(items, fluidIndex.candidatesFor(fluids, allowedIds));
    }

    return recipes.candidatesFor(items, allowedIds).stream()
        .filter(candidate -> fluidIndex.matches(candidate.id(), fluids)).toList();
  }

  boolean fluidsAvailable(String recipeId, RecipeSearchResources.Fluids resources) {
    var selector = selectors(recipeId);

    return resources.inputsAvailable(fluidInputs.getOrDefault(recipeId, List.of()), selector.fluidInput())
        && resources.outputsAvailable(fluidOutputs.getOrDefault(recipeId, List.of()), selector.fluidOutput());
  }

  public RecipeSelectors selectors(String recipeId) {
    return selectors.getOrDefault(recipeId, RecipeSelectors.NONE);
  }

  public RecipeCatalog root() {
    return root;
  }

  public MachineRecipe definition(String id) {
    return definitions.get(id);
  }

  public List<RecipeModifier> contextRules() {
    return contextRules;
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public CompiledRecipeSet<ItemResourceKey> recipes() {
    return recipes;
  }

  public List<FluidStack> fluidInputs(String recipeId) {
    return fluidInputs.getOrDefault(recipeId, List.of()).stream().map(FluidStack::copy).toList();
  }

  public List<FluidStack> fluidOutputs(String recipeId) {
    return fluidOutputs.getOrDefault(recipeId, List.of()).stream().map(FluidStack::copy).toList();
  }

  public boolean isCurrent() {
    return authority.getAsBoolean();
  }
}
