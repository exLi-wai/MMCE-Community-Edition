package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ChemicalTransactions;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemSlotRef;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.machine.recipe.RecipeItemPreparation;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.UpgradeBusOwner;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-owned multi-resource search with resumable candidate and output
 * planning. It does not consume resources or commit production.
 */
@ApiStatus.Internal
public final class RecipeSearch {
  private final RecipeExecutionTicket ticket;
  private final RecipeCatalog catalog;
  private final RecipeSearchResources resources;
  private final long itemCallbackEpoch = RecipeItemCallbackService.epoch();
  private final List<InputSlot> inputs;
  private final List<OutputSlot> outputs;
  private RecipePlanner.Session<ItemResourceKey> session;
  private ResourceSnapshot<ItemResourceKey> preparationSnapshot;
  private List<RecipeCandidate<ItemResourceKey>> inputPreparationCandidates = List.of();
  private final List<RecipeCandidate<ItemResourceKey>> preparedCandidates = new ArrayList<>();
  private final Map<String, PreparedInputs> preparedDefinitions = new HashMap<>();
  private int inputPreparationCursor;
  private RecipeItemCallbackService.InputPreparation inputPreparation;
  private long inputPreparationEpoch = -1;
  private long inputPreparationWork;

  private record PreparedInputs(MachineRecipe definition, RecipeCandidate<ItemResourceKey> original, long epoch) {
  }
  private RecipeStartPlan selected;
  private FluidOutputLayout fluidLayout;
  private FluidOutputLayout.Planning fluidPlan;
  private ChemicalOutputLayout.Planning chemicalPlan;
  private long fluidWork;
  private final UpgradeRecipeChecks.Scope factoryScope;
  private final UpgradeBusOwner factory;
  private final String factoryThread;
  private RecipeStartPlan pendingFactoryPlan;
  private long checkWork;
  private List<RecipeExecutionTicket> groups;
  private Set<String> allowedIds;
  private int groupCursor;
  private RecipeSearch groupSearch;
  private RecipeStartPlan groupBest;
  private int groupBestIndex = -1;
  private final Map<Integer, Set<String>> rejectedGroupRecipes = new HashMap<>();
  private long completedGroupWork;
  private final Map<String, MissingResource> missingResources = new HashMap<>();
  private final Set<String> componentReady = new HashSet<>();
  private boolean resourcesDiagnosed;

  private record MissingResource(RecipeCandidate<ItemResourceKey> candidate, PortKind kind) {
  }

  private record OutputSlot(ItemSlotRef ref, ResourceSnapshot.OutputSlot<ItemResourceKey> snapshot) {
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, List<RecipeExecutionTicket> groups) {
    this.ticket = ticket;
    this.catalog = catalog;
    resources = new RecipeSearchResources(catalog);
    this.allowedIds = allowedIds == null ? null : Set.copyOf(allowedIds);
    this.factoryThread = startThread(ticket, factoryThread);
    this.groups = List.copyOf(groups);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeChecks.capture(factory, this.factoryThread);
    inputs = List.of();
    outputs = List.of();
    session = null;
    fluidLayout = null;
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, RecipeSearchResources resources) {
    this.ticket = ticket;
    this.catalog = catalog;
    this.resources = resources;
    this.allowedIds = allowedIds == null ? null : Set.copyOf(allowedIds);
    this.factoryThread = startThread(ticket, factoryThread);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeChecks.capture(factory, this.factoryThread);
    var inputSlots = new ArrayList<InputSlot>();
    var outputSlots = new ArrayList<OutputSlot>();
    var counts = new LinkedHashMap<ItemResourceKey, Long>();
    var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ItemResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind().resource() != PortKind.Resource.ITEM) {
        continue;
      }
      for (var slot : resources.items(port)) {
        var key = slot.key();
        if (port.kind() == PortKind.ITEM_INPUT) {
          if (key != null) {
            counts.merge(key, (long) slot.count(), Math::addExact);
            inputSlots.add(new InputSlot(slot.ref(), key, slot.count()));
            if (!catalog.itemInputAliases().isEmpty() || catalog.hasDynamicInputRecipes()) {
              var alias = key.ignoringComponents();
              if (catalog.itemInputAliases().contains(alias) || catalog.hasDynamicInputRecipes()) {
                counts.merge(alias, (long) slot.count(), Math::addExact);
              }
            }
          }
        } else {
          outputSlots.add(new OutputSlot(slot.ref(), slot.output()));
          spaces.add(slot.output());
        }
      }
    }
    inputs = List.copyOf(inputSlots);
    outputs = List.copyOf(outputSlots);
    var resourceCandidates = new HashSet<String>();
    var preparingCandidates = new ArrayList<RecipeCandidate<ItemResourceKey>>();
    var requirements = RecipeResourceRequirements.capture(ticket);
    var fluids = resources.fluids(ticket);
    var chemicals = resources.chemicalInputKeys(ticket);
    for (var candidate : catalog.candidatesForPreparation(counts, fluids.inputKeys(), chemicals, allowedIds)) {
      var definition = catalog.definition(candidate.id());
      boolean transformsInput = definition != null
          && definition.scriptOptions().itemCallbacks().stream()
              .anyMatch(binding -> binding.io() == RecipeModifier.Io.INPUT && !binding.modifiers().isEmpty());
      var missing = definition == null ? Optional.<PortKind>empty() : requirements.missing(definition);
      if (missing.isPresent()
          || !transformsInput && !PatternProductionService.matches(ticket, definition, this.factoryThread)
          || definition != null
              && ticket.owner() instanceof FactoryControllerBlockEntity controller
              && !controller.recipeThreadAllowed(
                  this.factoryThread,
                  ResourceLocation.parse(candidate.id()),
                  definition.executionOptions())
          || definition != null
              && ticket.owner() instanceof MachineControllerBlockEntity
              && !definition.executionOptions().threadName().isEmpty()) {
        continue;
      }
      if (ticket.interfacesMatch(candidate.interfaceInputs())
          && (definition == null
              || definition.chemicals().inputs().isEmpty()
              || ChemicalTransactions.inputsAvailable(ticket, definition.chemicals().inputs()))
          && catalog.fluidsAvailable(candidate.id(), fluids)) {
        if (transformsInput) {
          preparingCandidates.add(candidate);
        } else {
          resourceCandidates.add(candidate.id());
          preparedCandidates.add(RecipeItemPreparation.inputCandidate(candidate, definition));
        }
      }
    }
    // Logical aliases are counted once per physical slot and are needed only by
    // single-row item-only candidates. The ticket retains exact physical stamps.
    var snapshot = new ResourceSnapshot<>(ticket.structureRevision(), counts, spaces);
    if (preparingCandidates.isEmpty()) {
      session = RecipePlanner.prepare(snapshot, new CompiledRecipeSet<>(preparedCandidates), resourceCandidates);
    } else {
      preparationSnapshot = snapshot;
      inputPreparationCandidates = List.copyOf(preparingCandidates);
      inputPreparationEpoch = RecipeItemCallbackService.epoch();
    }
  }

  private int advanceInputPreparations(int budget) {
    int used = 0;
    while (inputPreparationCursor < inputPreparationCandidates.size() && used < budget) {
      var original = inputPreparationCandidates.get(inputPreparationCursor);
      var definition = catalog.definition(original.id());
      if (inputPreparation == null) {
        var ops = ticket.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var json = MachineRecipe.CODEC.codec().encodeStart(ops, definition).getOrThrow().getAsJsonObject();
        var thread = ticket.owner() instanceof FactoryControllerBlockEntity ? Optional.of(factoryThread) : Optional
            .<String>empty();
        inputPreparation = RecipeItemCallbackService.beginInputs(
            json,
            definition.scriptOptions(),
            RecipeItemPreparation.context(ticket, ResourceLocation.parse(original.id()), thread, 1),
            ticket.level().registryAccess());
      }
      inputPreparation.advance(budget - used);
      used += inputPreparation.workUsed();
      inputPreparationWork += inputPreparation.workUsed();
      if (!inputPreparation.rejected() && inputPreparation.prepared().isEmpty()) {
        return used;
      }
      var prepared = inputPreparation.prepared();
      if (prepared.isPresent()) {
        var value = prepared.orElseThrow();
        var ops = ticket.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
        var nativeRecipe = MachineRecipe.CODEC.codec().parse(ops, value.declaration()).getOrThrow();
        if (PatternProductionService.matches(ticket, nativeRecipe, factoryThread)) {
          var candidate = RecipeItemCallbackService
              .compilePrepared(nativeRecipe, ResourceLocation.parse(original.id()), ticket.level().registryAccess());
          preparedCandidates.add(RecipeItemPreparation.inputCandidate(candidate, nativeRecipe));
          preparedDefinitions.put(original.id(), new PreparedInputs(nativeRecipe, original, value.epoch()));
        }
      }
      inputPreparation = null;
      inputPreparationCursor++;
    }
    if (inputPreparationCursor == inputPreparationCandidates.size() && session == null) {
      session = RecipePlanner.prepare(preparationSnapshot, new CompiledRecipeSet<>(preparedCandidates), null);
    }
    return used;
  }

  private static String startThread(RecipeExecutionTicket ticket, String threadId) {
    if (threadId == null && ticket.owner() instanceof MachineControllerBlockEntity) {
      return "main";
    }

    return threadId;
  }

  public static Optional<RecipeSearch> begin(MachineControllerBlockEntity controller, RecipeCatalog catalog) {
    if (controller.runningRecipe().isPresent() || controller.recipeStartInProgress()) {
      return Optional.empty();
    }
    return begin((StructureService.StructureSubject) controller, catalog, null);
  }

  public static Optional<RecipeSearch> begin(StructureService.StructureSubject owner, RecipeCatalog catalog, Set<String> allowedIds) {
    return begin(owner, catalog, allowedIds, null);
  }

  private static Optional<RecipeSearch> begin(StructureService.StructureSubject owner, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread) {
    Objects.requireNonNull(catalog, "catalog");
    // capture enforces server affinity before the search reads any inventory or
    // compiles slot
    // limits.
    var captured = RecipeExecutionTicket.capture(owner);
    if (captured.isEmpty() || !owner.structureMachineId().equals(catalog.machineId()) || !catalog.isCurrent()) {
      return Optional.empty();
    }
    var ticket = captured.orElseThrow();
    PatternProductionService.observe(owner, catalog, factoryThread);
    var groups = ticket.searchContexts();
    var search = groups.size() == 1 ? new RecipeSearch(
        ticket,
        catalog,
        allowedIds,
        factoryThread,
        new RecipeSearchResources(catalog)) : new RecipeSearch(ticket, catalog, allowedIds, factoryThread, groups);
    return catalog.isCurrent() ? Optional.of(search) : Optional.empty();
  }

  /**
   * Native data-pack entry point. Returns no authority while catalogs are
   * unvalidated or stale.
   */
  public static Optional<RecipeSearch> begin(MachineControllerBlockEntity controller) {
    return MachineRecipeService.catalogFor(controller).flatMap(catalog -> begin(controller, catalog));
  }

  public static Optional<RecipeSearch> begin(StructureService.StructureSubject owner, Set<String> allowedIds) {
    return MachineRecipeService.catalogFor(owner).flatMap(catalog -> begin(owner, catalog, allowedIds));
  }

  public static Optional<RecipeSearch> beginFactory(FactoryControllerBlockEntity factory, String threadId, RecipeCatalog catalog, Set<String> allowedIds) {
    Objects.requireNonNull(threadId, "threadId");
    return MachineRecipeService.catalogForFactory(factory, threadId, catalog)
        .flatMap(effective -> begin(factory, effective, allowedIds, threadId));
  }

  public static Optional<RecipeSearch> beginFactory(FactoryControllerBlockEntity factory, String threadId) {
    return MachineRecipeService.catalogFor(factory).flatMap(catalog -> {
      var thread = factory.scheduler().channel(threadId);
      if (thread == null) {
        return Optional.empty();
      }
      var allowed = thread.lockedRecipe() == null ? null : Set.of(thread.lockedRecipe().toString());

      return beginFactory(factory, threadId, catalog, allowed);
    });
  }

  public record Result(RecipePlanner.Status status, Optional<RecipeStartPlan> plan, int workUsed) {
  }

  public Result advance(int budget) {
    ticket.requireServerThread();
    if (budget < 0) {
      throw new IllegalArgumentException("Negative planning budget");
    }
    if (factoryScope != null && !factoryScope.current()) {
      throw new UpgradeRecipeChecks.StaleException();
    }
    if (inputPreparationEpoch >= 0 && !RecipeItemCallbackService.isCurrent(inputPreparationEpoch)) {
      throw new UpgradeRecipeChecks.StaleException();
    }
    if (!RecipeItemCallbackService.isCurrent(itemCallbackEpoch)) {
      throw new UpgradeRecipeChecks.StaleException();
    }
    if (groups != null) {
      return advanceGroups(budget);
    }
    if (selected != null) {
      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), 0);
    }
    int used = 0;
    if (session == null) {
      used += advanceInputPreparations(budget);
      if (session == null) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
    }
    while (true) {
      var result = session.advance(budget - used);
      used += result.workUsed();
      if (result.status() != RecipePlanner.Status.SELECTED) {
        return new Result(result.status(), Optional.empty(), used);
      }
      var generic = result.plan().orElseThrow();
      if (fluidPlan == null) {
        if (fluidLayout == null) {
          fluidLayout = new FluidOutputLayout(ticket, ticket.level().registryAccess());
        }
        fluidPlan = fluidLayout.plan(
            catalog.fluidOutputs(generic.candidate().id()),
            catalog.selectors(generic.candidate().id()).fluidOutput());
      }
      if (fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        var fluidResult = fluidPlan.advance(budget - used);
        used += fluidResult.workUsed();
        fluidWork += fluidResult.workUsed();
      }
      if (fluidPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (fluidPlan.status() == RecipePlanner.Status.NO_MATCH) {
        fluidPlan = null;
        session.rejectSelected();
        continue;
      }
      if (chemicalPlan == null) {
        var definition = catalog.definition(generic.candidate().id());
        chemicalPlan = new ChemicalOutputLayout(ticket)
            .plan(definition == null ? List.of() : definition.chemicals().outputs());
      }
      if (chemicalPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        var chemicalResult = chemicalPlan.advance(budget - used);
        used += chemicalResult.workUsed();
        fluidWork += chemicalResult.workUsed();
      }
      if (chemicalPlan.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (chemicalPlan.status() == RecipePlanner.Status.NO_MATCH) {
        chemicalPlan = null;
        fluidPlan = null;
        session.rejectSelected();
        continue;
      }
      if (pendingFactoryPlan == null) {
        long inputChanceSeed = RecipeStartPlan.newInputChanceSeed();
        var needed = new HashMap<>(RecipeStartPlan.sampledInputs(generic.candidate(), inputChanceSeed, 1));
        var deductions = new ArrayList<RecipeStartPlan.Deduction>();
        for (var slot : inputs) {
          var expected = RecipeStartPlan.inputKey(generic.candidate(), slot.key());
          long amount = Math.min(needed.getOrDefault(expected, 0L), slot.count());
          if (amount == 0) {
            continue;
          }
          deductions.add(new RecipeStartPlan.Deduction(slot.ref(), slot.key(), amount));
          needed.put(expected, needed.get(expected) - amount);
        }
        if (needed.values().stream().anyMatch(count -> count != 0)) {
          throw new IllegalStateException("Input slot accounting differs from exact-key plan");
        }
        var placements = generic.insertions().stream()
            .map(
                insertion -> RecipeStartPlan.Placement.inSlot(
                    outputs.get(insertion.slot()).ref(),
                    outputs.get(insertion.slot()).snapshot(),
                    insertion.key(),
                    Math.toIntExact(insertion.count())))
            .toList();
        var prepared = preparedDefinitions.get(generic.candidate().id());
        var candidate = prepared == null ? catalog.recipes().candidate(generic.candidate().id())
            .orElseThrow() : RecipeItemCallbackService.compilePrepared(
                prepared.definition(),
                ResourceLocation.parse(generic.candidate().id()),
                ticket.level().registryAccess());
        pendingFactoryPlan = RecipeStartPlan
            .prepared(ticket, catalog, candidate, deductions, placements, inputChanceSeed, factoryScope)
            .withOutputPreparationContext(factoryThread);
        if (prepared != null) {
          pendingFactoryPlan.withPreparedInputs(prepared.definition(), prepared.original(), prepared.epoch());
        }
      }
      if (factoryScope != null) {
        var checked = pendingFactoryPlan.advanceFactoryChecks(factory, factoryThread, budget - used);
        used += checked.workUsed();
        checkWork += checked.workUsed();
        switch (checked.status()) {
          case PENDING -> {
            return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
          }
          case REJECTED -> {
            pendingFactoryPlan = null;
            fluidPlan = null;
            chemicalPlan = null;
            session.rejectSelected();
            continue;
          }
          case STALE -> throw new UpgradeRecipeChecks.StaleException();
          case ERROR -> throw new UpgradeRecipeChecks.FailedException();
          case ALLOWED -> {
          }
        }
      }
      selected = pendingFactoryPlan;

      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), used);
    }
  }

  public long totalWork() {
    if (groups != null) {
      return completedGroupWork + (groupSearch == null ? 0 : groupSearch.totalWork());
    }
    return (session == null ? 0 : session.totalWork()) + inputPreparationWork + fluidWork + checkWork;
  }

  /**
   * A current checker's rejection advances this snapshot, never a fresh search.
   */
  public boolean rejectSelected(RecipeStartPlan plan) {
    ticket.requireServerThread();
    if (selected != plan || !dependenciesCurrent()) {
      return false;
    }
    if (groups == null) {
      session.rejectSelected();
      pendingFactoryPlan = null;
      fluidPlan = null;
      chemicalPlan = null;
    } else {
      // At most one rejection per captured context/catalog ID pair. A rejection
      // in one numeric or pattern group must not suppress another group's input.
      rejectedGroupRecipes.computeIfAbsent(groupBestIndex, ignored -> new HashSet<>()).add(plan.recipe().id());
      groupCursor = 0;
      groupSearch = null;
      groupBest = null;
      groupBestIndex = -1;
    }
    selected = null;
    return true;
  }

  private Result advanceGroups(int budget) {
    if (selected != null) {
      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), 0);
    }
    int used = 0;
    while (groupCursor < groups.size()) {
      if (used >= budget) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (groupSearch == null) {
        Set<String> competitive = allowedIds;
        if (groupBest != null) {
          competitive = catalog.recipes().candidateIdsBefore(groupBest.recipe().id(), allowedIds);
          if (competitive.isEmpty()) {
            // Later groups cannot beat a complete, higher-priority match.
            groupCursor = groups.size();
            break;
          }
        }
        var rejected = rejectedGroupRecipes.get(groupCursor);
        if (rejected != null) {
          var remaining = new HashSet<String>();
          for (var candidate : catalog.recipes().candidatesFor(competitive)) {
            if (!rejected.contains(candidate.id())) {
              remaining.add(candidate.id());
            }
          }
          competitive = remaining;
        }
        // Charge even an empty group, so a long list cannot bypass the tick budget.
        used++;
        completedGroupWork++;
        groupSearch = new RecipeSearch(groups.get(groupCursor), catalog, competitive, factoryThread, resources);
      }
      var result = groupSearch.advance(budget - used);
      used += result.workUsed();
      if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return new Result(result.status(), Optional.empty(), used);
      }
      if (result.plan().isPresent()) {
        var candidate = result.plan().orElseThrow();
        if (groupBest == null || precedes(candidate.recipe(), groupBest.recipe())) {
          groupBest = candidate;
          groupBestIndex = groupCursor;
        }
      }
      completedGroupWork += groupSearch.totalWork();
      groupSearch = null;
      groupCursor++;
    }
    selected = groupBest;

    return new Result(
        selected == null ? RecipePlanner.Status.NO_MATCH : RecipePlanner.Status.SELECTED,
        Optional.ofNullable(selected),
        used);
  }

  private static boolean precedes(RecipeCandidate<ItemResourceKey> left, RecipeCandidate<ItemResourceKey> right) {
    return left.priority() > right.priority()
        || left.priority() == right.priority() && left.id().compareTo(right.id()) < 0;
  }

  public boolean dependenciesCurrent() {
    return ticket.dependenciesCurrent()
        && catalog.isCurrent()
        && RecipeItemCallbackService.isCurrent(itemCallbackEpoch)
        && (factoryScope == null || factoryScope.current())
        && (inputPreparationEpoch < 0 || RecipeItemCallbackService.isCurrent(inputPreparationEpoch));
  }

  /** Known absent roles after a complete search, never inventory shortages. */
  public Optional<PortKind> missingResource() {
    ticket.requireServerThread();
    if (selected != null) {
      return Optional.empty();
    }
    if (!resourcesDiagnosed) {
      // Diagnostics cover every allowed recipe even when an input anchor excludes
      // it. Successful searches never need this full component-role pass.
      var candidates = catalog.recipes().candidatesFor(allowedIds);
      if (groups == null) {
        diagnoseResources(ticket, candidates);
      } else {
        for (var group : groups) {
          diagnoseResources(group, candidates);
        }
      }
      resourcesDiagnosed = true;
    }
    MissingResource first = null;
    for (var missing : missingResources.values()) {
      if (!componentReady.contains(missing.candidate().id())
          && (first == null || precedes(missing.candidate(), first.candidate()))) {
        first = missing;
      }
    }
    return first == null ? Optional.empty() : Optional.of(first.kind());
  }

  private void diagnoseResources(RecipeExecutionTicket source, List<RecipeCandidate<ItemResourceKey>> candidates) {
    var requirements = RecipeResourceRequirements.capture(source);
    for (var candidate : candidates) {
      var definition = catalog.definition(candidate.id());
      if (definition != null
          && source.owner() instanceof FactoryControllerBlockEntity controller
          && !controller
              .recipeThreadAllowed(factoryThread, ResourceLocation.parse(candidate.id()), definition.executionOptions())
          || definition != null
              && source.owner() instanceof MachineControllerBlockEntity
              && !definition.executionOptions().threadName().isEmpty()) {
        continue;
      }
      var missing = definition == null ? Optional.<PortKind>empty() : requirements.missing(definition);
      if (missing.isPresent()) {
        missingResources.put(candidate.id(), new MissingResource(candidate, missing.orElseThrow()));
      } else {
        componentReady.add(candidate.id());
      }
    }
  }

  private record InputSlot(ItemSlotRef ref, ItemResourceKey key, long count) {
  }
}
