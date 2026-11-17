package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.search.index.item.ItemRecipeIndex;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.ResourceCandidateSelection;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import java.util.Collection;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
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
  private final ItemRecipeIndex itemIndex;
  private final List<RecipeSearchResource> resourceSearches;
  private final BitSet dynamicInputRanks;

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
    itemIndex = new ItemRecipeIndex(recipes);
    dynamicInputRanks = new BitSet(recipes.size());
    for (int rank = 0; rank < recipes.candidates().size(); rank++) {
      var definition = this.definitions.get(recipes.candidates().get(rank).id());
      if (definition != null
          && (!definition.ingredientArrays().isEmpty()
              || definition.scriptOptions().itemCallbacks().stream()
                  .anyMatch(binding -> binding.io() == RecipeModifier.Io.INPUT && !binding.modifiers().isEmpty()))) {
        dynamicInputRanks.set(rank);
      }
    }
    resourceSearches = RecipeSearchResourceRegistry.compile(this);
  }

  private RecipeCatalog(RecipeCatalog prepared, BooleanSupplier authority) {
    machineId = prepared.machineId;
    recipes = prepared.recipes;
    fluidInputs = prepared.fluidInputs;
    fluidOutputs = prepared.fluidOutputs;
    this.authority = Objects.requireNonNull(authority, "authority");
    root = this;
    definitions = prepared.definitions;
    contextRules = prepared.contextRules;
    selectors = prepared.selectors;
    itemIndex = prepared.itemIndex;
    resourceSearches = prepared.resourceSearches;
    dynamicInputRanks = prepared.dynamicInputRanks;
  }

  /** Fresh publication identity over immutable, already compiled base data. */
  public RecipeCatalog publish(BooleanSupplier authority) {
    if (root != this || !contextRules.isEmpty()) {
      throw new IllegalStateException("Only a base recipe catalog can be published");
    }
    return new RecipeCatalog(this, authority);
  }

  boolean hasDynamicInputRecipes() {
    return !dynamicInputRanks.isEmpty();
  }

  List<RecipeCandidate<ItemResourceKey>> candidatesForPreparation(Map<ItemResourceKey, Long> items, List<RecipeSearchResource.Captured> resources, Set<String> allowedIds) {
    var indexed = candidatesForInputs(items, resources, allowedIds);
    if (dynamicInputRanks.isEmpty()) {
      return indexed;
    }
    var selected = recipes.ranksOf(indexed);
    for (int rank = dynamicInputRanks.nextSetBit(0); rank >= 0; rank = dynamicInputRanks.nextSetBit(rank + 1)) {
      if (allowedIds == null || allowedIds.contains(recipes.candidates().get(rank).id())) {
        selected.set(rank);
      }
    }
    return recipes.candidatesAt(selected);
  }

  Set<ItemResourceKey> itemInputAliases() {
    return itemIndex.aliases();
  }

  List<RecipeSearchResource.Captured> captureResources(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    return resourceSearches.stream().map(resource -> resource.capture(ticket, resources)).toList();
  }

  List<RecipeCandidate<ItemResourceKey>> candidatesForInputs(Map<ItemResourceKey, Long> items, List<RecipeSearchResource.Captured> resources, Set<String> allowedIds) {
    var queries = new ArrayList<ResourceInputQuery>(resources.size() + 1);
    queries.add(new RequiredInputQuery<>(itemIndex.inputs(), items.keySet()));
    for (var resource : resources) {
      queries.add(resource.inputs());
    }

    return recipes.candidatesAt(ResourceCandidateSelection.select(queries, allowedIds));
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
