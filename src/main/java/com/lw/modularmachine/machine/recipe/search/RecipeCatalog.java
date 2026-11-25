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
import com.lw.modularmachine.api.recipe.FluidRequirement;
import java.util.Collection;
import java.util.ArrayList;
import java.util.BitSet;
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
  private static final int DIAGNOSTIC_PORT_SLICE = 32;
  private final ResourceLocation machineId;
  private final CompiledRecipeSet<ItemResourceKey> recipes;
  private final Map<String, List<FluidRequirement>> fluidInputs;
  private final Map<String, List<FluidRequirement>> fluidOutputs;
  private final BooleanSupplier authority;
  private final RecipeCatalog root;
  private final Map<String, MachineRecipe> definitions;
  private final List<RecipeModifier> contextRules;
  private final Map<String, RecipeSelectors> selectors;
  private final ItemRecipeIndex itemIndex;
  private final RecipeSearchResourceRegistry.Suite resourceSearches;
  private final BitSet dynamicInputRanks;
  private final Set<RecipeResourceRequirements.RequiredPort> requiredPorts;
  record SearchEntry(ResourceLocation id, boolean transformsInputs, CompiledRecipeSet.Entry<ItemResourceKey> inputs) {
  }

  private final Map<String, SearchEntry> searchEntries;

  /**
   * Both base and controller-specific catalogs index the effective definitions
   * for their candidates.
   */
  public static RecipeCatalog fromDefinitions(ResourceLocation machineId, Collection<RecipeCandidate<ItemResourceKey>> candidates, Map<String, MachineRecipe> definitions, BooleanSupplier authority, RecipeCatalog root, List<RecipeModifier> contextRules) {
    return new RecipeCatalog(
        machineId,
        new CompiledRecipeSet<>(candidates),
        Map.of(),
        Map.of(),
        authority,
        root,
        definitions,
        contextRules);
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
    var inputRows = fluidRows(fluidInputs);
    var outputRows = fluidRows(fluidOutputs);
    definitions.forEach((id, recipe) -> {
      inputRows.put(id, recipe.fluidInputRequirements());
      outputRows.put(id, recipe.guaranteedFluidOutputRequirements());
    });
    this.fluidInputs = Map.copyOf(inputRows);
    this.fluidOutputs = Map.copyOf(outputRows);
    this.authority = Objects.requireNonNull(authority, "authority");
    this.root = root == null ? this : root.root();
    this.definitions = Map.copyOf(definitions);
    this.contextRules = List.copyOf(contextRules);
    this.selectors = Map.copyOf(selectors);
    itemIndex = new ItemRecipeIndex(recipes);
    dynamicInputRanks = new BitSet(recipes.size());
    var ports = new HashSet<RecipeResourceRequirements.RequiredPort>();
    var planningEntries = new HashMap<String, SearchEntry>();
    for (int rank = 0; rank < recipes.candidates().size(); rank++) {
      var entry = recipes.entries().get(rank);
      var definition = this.definitions.get(entry.recipe.id());
      if (definition != null) {
        ports.addAll(definition.requiredPorts());
      }
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
    requiredPorts = Set.copyOf(ports);
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
    requiredPorts = prepared.requiredPorts;
  }

  private static Map<String, List<FluidRequirement>> fluidRows(Map<String, List<FluidStack>> stacks) {
    var result = new HashMap<String, List<FluidRequirement>>();
    stacks.forEach(
        (id, values) -> result.put(
            id,
            values.stream().filter(stack -> !stack.isEmpty()).map(stack -> new FluidRequirement(stack, 1)).toList()));
    return result;
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

  boolean allDiagnosticRolesPresent(RecipeResourceRequirements roles, CandidateFilter filter) {
    if (filter.allowedIds != null && filter.allowedIds.size() == 1) {
      var definition = definitions.get(filter.allowedIds.iterator().next());
      return definition == null
          || definition.requiredPorts().size() <= DIAGNOSTIC_PORT_SLICE
              && roles.containsAll(definition.requiredPorts());
    }
    // Large selector sets keep the normal resumable diagnostic pass.
    return requiredPorts.size() <= DIAGNOSTIC_PORT_SLICE && roles.containsAll(requiredPorts);
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
    private ResourceInputQuery fluidInputs;
    private boolean otherInputAnchors;

    CapturedResources(String lockedRecipe) {
      selectedStrategies = lockedRecipe == null ? null : resourceSearches.byRecipe().get(lockedRecipe);
    }

    boolean complete() {
      return all
          .size() == (selectedStrategies == null ? resourceSearches.strategies().size() : selectedStrategies.size());
    }

    void captureNext(RecipeExecutionTicket ticket, RecipeSearchResources resources) {
      int index = selectedStrategies == null ? all.size() : selectedStrategies.get(all.size());
      var strategy = resourceSearches.strategies().get(index);
      var captured = strategy.capture(ticket, resources);
      all.add(captured);
      if (strategy instanceof FluidRecipeSearchResource) {
        fluidInputs = captured.inputs();
      } else if (!resourceSearches.externalStrategies()) {
        otherInputAnchors |= captured.inputs().hasAnchors();
      }
    }

    List<RecipeSearchResource.Captured> all() {
      return all;
    }

    boolean missingPhysicalInputs(Map<ItemResourceKey, Long> items, CandidateFilter filter) {
      if (!filter.dynamicRanks.isEmpty()
          || resourceSearches.externalStrategies()
          || filter.firstRank >= recipes.size()) {
        return false;
      }
      int singleRank = recipes.size() == 1
          || filter.allowedIds != null && filter.allowedIds.size() == 1 ? filter.firstRank : -1;
      return physicalInputsExcluded(
          new RequiredInputQuery<>(itemIndex.inputs(), items),
          fluidInputs,
          otherInputAnchors,
          singleRank);
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

  /**
   * Called only after the complete, unpruned static candidate intersection was
   * empty.
   */
  static boolean physicalInputsExcluded(ResourceInputQuery items, ResourceInputQuery fluids, boolean otherInputAnchors, int singleRank) {
    if (!items.hasAnchors() && (fluids == null || !fluids.hasAnchors())) {
      return false;
    }
    if (!otherInputAnchors) {
      return true;
    }
    // With competing indexes an empty intersection has ambiguous provenance. A
    // locked rank
    // costs at most two key lookups. Broader filters keep normal retries.
    return singleRank >= 0 && (!items.matchesRank(singleRank) || fluids != null && !fluids.matchesRank(singleRank));
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
    var recipe = definitions.get(recipeId);
    return recipe == null ? selectors.getOrDefault(recipeId, RecipeSelectors.NONE) : recipe.selectors();
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
    return fluidInputRequirements(recipeId).stream().map(FluidRequirement::stack).toList();
  }

  public List<FluidStack> fluidOutputs(String recipeId) {
    return guaranteedFluidOutputs(recipeId).stream().map(FluidRequirement::stack).toList();
  }

  List<FluidRequirement> fluidInputRequirements(String recipeId) {
    return fluidInputs.getOrDefault(recipeId, List.of());
  }

  List<FluidRequirement> guaranteedFluidOutputs(String recipeId) {
    return fluidOutputs.getOrDefault(recipeId, List.of());
  }

  public boolean isCurrent() {
    return authority.getAsBoolean();
  }
}
