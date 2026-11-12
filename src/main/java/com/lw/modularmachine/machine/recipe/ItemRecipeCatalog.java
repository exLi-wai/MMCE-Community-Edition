package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Immutable exact-component item-only slice. The future reload owner publishes
 * a new catalog by identity.
 */
public final class ItemRecipeCatalog {
  private final ResourceLocation machineId;
  private final CompiledRecipeSet<ItemResourceKey> recipes;
  private final Map<String, List<FluidStack>> fluidInputs;
  private final Map<String, List<FluidStack>> fluidOutputs;
  private final BooleanSupplier authority;
  private final ItemRecipeCatalog root;
  private final Map<String, MachineRecipe> definitions;
  private final List<RecipeModifier> contextRules;
  private final Map<String, RecipeSelectors> selectors;

  public ItemRecipeCatalog(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates) {
    this(machineId, new CompiledRecipeSet<>(candidates), Map.of(), Map.of(), () -> true);
  }

  ItemRecipeCatalog(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates, BooleanSupplier authority) {
    this(machineId, new CompiledRecipeSet<>(candidates), Map.of(), Map.of(), authority);
  }

  ItemRecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, BooleanSupplier authority) {
    this(machineId, recipes, Map.of(), Map.of(), authority);
  }

  ItemRecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority) {
    this(machineId, recipes, fluidInputs, fluidOutputs, authority, null, Map.of(), List.of());
  }

  ItemRecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority, ItemRecipeCatalog root, Map<String, MachineRecipe> definitions, List<RecipeModifier> contextRules) {
    this(machineId, recipes, fluidInputs, fluidOutputs, authority, root, definitions, contextRules, Map.of());
  }

  ItemRecipeCatalog(ResourceLocation machineId, CompiledRecipeSet<ItemResourceKey> recipes, Map<String, List<FluidStack>> fluidInputs, Map<String, List<FluidStack>> fluidOutputs, BooleanSupplier authority, ItemRecipeCatalog root, Map<String, MachineRecipe> definitions, List<RecipeModifier> contextRules, Map<String, RecipeSelectors> selectors) {
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
  }

  RecipeSelectors selectors(String recipeId) {
    return selectors.getOrDefault(recipeId, RecipeSelectors.NONE);
  }

  ItemRecipeCatalog root() {
    return root;
  }

  MachineRecipe definition(String id) {
    return definitions.get(id);
  }

  List<RecipeModifier> contextRules() {
    return contextRules;
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public CompiledRecipeSet<ItemResourceKey> recipes() {
    return recipes;
  }

  List<FluidStack> fluidInputs(String recipeId) {
    return fluidInputs.getOrDefault(recipeId, List.of()).stream().map(FluidStack::copy).toList();
  }

  List<FluidStack> fluidOutputs(String recipeId) {
    return fluidOutputs.getOrDefault(recipeId, List.of()).stream().map(FluidStack::copy).toList();
  }

  public boolean isCurrent() {
    return authority.getAsBoolean();
  }
}
