package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeItemPreparation;
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
  private final RecipeSearchResourceRegistry.Suite resourceSearches;
  private final BitSet dynamicInputRanks;
  record SearchEntry(ResourceLocation id, boolean transformsInputs, CompiledRecipeSet.Entry<ItemResourceKey> inputs) {
  }

  private final Map<String, SearchEntry> searchEntries;

  /**
   * Both base and controller-specific catalogs index the effective definitions
   * for their candidates.
   */
  public static RecipeCatalog fromDefinitions(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates, Map<String, MachineRecipe> definitions, BooleanSupplier authority, RecipeCatalog root, List<RecipeModifier> contextRules) {
    var inputs = new HashMap<String, List<FluidStack>>();
    var outputs = new HashMap<String, List<FluidStack>>();
    var selectors = new HashMap<String, RecipeSelectors>();
    definitions.forEach((id, recipe) -> {
      inputs.put(id, recipe.fluidInputs());
      outputs.put(id, recipe.guaranteedFluidOutputs());
      selectors.put(id, recipe.selectors());
    });
    return new RecipeCatalog(
        machineId,
        new CompiledRecipeSet<>(candidates),
        inputs,
        outputs,
        authority,
        root,
        definitions,
        contextRules,
        selectors);
  }

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
    var planningEntries = new HashMap<String, SearchEntry>();
    for (int rank = 0; rank < recipes.candidates().size(); rank++) {
      var entry = recipes.entries().get(rank);
      var definition = this.definitions.get(entry.recipe.id());
      var candidate = RecipeItemPreparation.inputCandidate(entry.recipe, definition);
      boolean transforms = definition != null
          && (!definition.ingredientArrays().isEmpty()
              || definition.scriptOptions().itemCallbacks().stream()
                  .anyMatch(binding -> binding.io() == RecipeModifier.Io.INPUT && !binding.modifiers().isEmpty()));
      planningEntries.put(
          candidate.id(),
          new SearchEntry(
              definition == null ? null : ResourceLocation.parse(candidate.id()),
              transforms,
              candidate == entry.recipe ? entry : CompiledRecipeSet.compileCandidate(candidate)));
      if (transforms) {
        dynamicInputRanks.set(rank);
      }
    }
    searchEntries = Map.copyOf(planningEntries);
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
    searchEntries = prepared.searchEntries;
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

  boolean supportsLockedRestart() {
    return !resourceSearches.externalStrategies();
  }

  /**
   * Shared by every group in one search. Dynamic inputs can match even when their
   * original anchors are absent.
   */
  static final class CandidateFilter {
    final Set<String> allowedIds;
    final int firstRank;
    private final BitSet dynamicRanks;

    CandidateFilter(CompiledRecipeSet<?> recipes, BitSet dynamic, Set<String> allowed) {
      allowedIds = allowed == null ? null : Set.copyOf(allowed);
      if (allowedIds == null) {
        firstRank = 0;
        dynamicRanks = dynamic;
      } else {
        int first = recipes.size();
        dynamicRanks = new BitSet();
        for (var id : allowedIds) {
          int rank = recipes.rankOf(id);
          if (rank >= 0) {
            first = Math.min(first, rank);
            if (dynamic.get(rank)) {
              dynamicRanks.set(rank);
            }
          }
        }
        firstRank = first;
      }
    }

    void includeDynamic(BitSet selected, int rankLimit, BitSet rejected) {
      selected.or(dynamicRanks);
      if (rankLimit < selected.length()) {
        selected.clear(rankLimit, selected.length());
      }
      if (rejected != null) {
        selected.andNot(rejected);
      }
    }
  }

  CandidateFilter candidateFilter(Set<String> allowed) {
    return new CandidateFilter(recipes, dynamicInputRanks, allowed);
  }

  Set<ItemResourceKey> itemInputAliases() {
    return itemIndex.aliases();
  }

  SearchEntry searchEntry(String id) {
    return searchEntries.get(id);
  }

  final class CapturedResources {
    private final List<RecipeSearchResource.Captured> all = new ArrayList<>();
    private final Map<List<Integer>, List<RecipeSearchResource.Captured>> subsets = new HashMap<>();
    private final List<Integer> selectedStrategies;

    CapturedResources(String lockedRecipe) {
      selectedStrategies = lockedRecipe == null ? null : resourceSearches.byRecipe().get(lockedRecipe);
    }

    boolean complete() {
      return all
          .size() == (selectedStrategies == null ? resourceSearches.strategies().size() : selectedStrategies.size());
    }

    void captureNext(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
      int index = selectedStrategies == null ? all.size() : selectedStrategies.get(all.size());
      all.add(resourceSearches.strategies().get(index).capture(ticket, resources));
    }

    List<RecipeSearchResource.Captured> all() {
      return all;
    }

    List<RecipeSearchResource.Captured> forRecipe(String id) {
      if (selectedStrategies != null) {
        return all;
      }
      var indices = resourceSearches.byRecipe().getOrDefault(id, List.of());
      return indices.size() == all.size() ? all : subsets
          .computeIfAbsent(indices, selected -> selected.stream().map(all::get).toList());
    }
  }

  List<RecipeSearchResource.Captured> captureForRecipe(String id, RecipeExecutionTicket ticket, RecipeSearchResources resources) {
    return resourceSearches.byRecipe().getOrDefault(id, List.of()).stream()
        .map(index -> resourceSearches.strategies().get(index).capture(ticket, resources)).toList();
  }

  RecipeExecutionTicket ticketForRecipe(RecipeExecutionTicket ticket, RecipeCandidate<ItemResourceKey> candidate, MachineRecipe definition) {
    // An extension may inspect any physical port, including undeclared roles.
    return resourceSearches.externalStrategies() ? ticket : ticket
        .forRecipePorts(RecipeResourceRequirements.forCandidate(this, candidate, definition));
  }

  List<RecipeCandidate<ItemResourceKey>> candidatesForPreparation(Map<ItemResourceKey, Long> items, List<RecipeSearchResource.Captured> resources, CandidateFilter filter, int rankLimit, BitSet rejected) {
    var queries = new ArrayList<ResourceInputQuery>(resources.size() + 1);
    queries.add(new RequiredInputQuery<>(itemIndex.inputs(), items));
    for (var resource : resources) {
      queries.add(resource.inputs());
    }

    var selected = ResourceCandidateSelection.select(queries, filter.allowedIds, rankLimit);
    filter.includeDynamic(selected, rankLimit, rejected);
    return recipes.candidatesAt(selected);
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
