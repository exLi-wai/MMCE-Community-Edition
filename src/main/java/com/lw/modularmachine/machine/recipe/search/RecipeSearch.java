package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.machine.recipe.ChemicalTransactions;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemSlotRef;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
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

/**
 * Server-owned multi-resource search with resumable candidate and output
 * planning. It does not consume resources or commit production.
 */
@ApiStatus.Internal
public final class RecipeSearch {
  private final RecipeExecutionTicket ticket;
  private final RecipeCatalog catalog;
  private final RecipeSearchResources resources;
  private final List<InputSlot> inputs;
  private final List<OutputSlot> outputs;
  private final RecipePlanner.Session<ItemResourceKey> session;
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
  private List<Integer> groups;
  private Set<String> allowedIds;
  private int groupCursor;
  private RecipeSearch groupSearch;
  private RecipeStartPlan groupBest;
  private long completedGroupWork;
  private final Map<String, MissingResource> missingResources = new HashMap<>();
  private final Set<String> componentReady = new HashSet<>();
  private boolean resourcesDiagnosed;

  private record MissingResource(RecipeCandidate<ItemResourceKey> candidate, PortKind kind) {
  }

  private record OutputSlot(ItemSlotRef ref, ResourceSnapshot.OutputSlot<ItemResourceKey> snapshot) {
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, List<Integer> groups) {
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
            if (!catalog.itemInputAliases().isEmpty()) {
              var alias = key.ignoringComponents();
              if (catalog.itemInputAliases().contains(alias)) {
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
    var fluidCandidates = new HashSet<String>();
    var requirements = RecipeResourceRequirements.capture(ticket);
    var fluids = resources.fluids(ticket);
    for (var candidate : catalog.candidatesForInputs(counts, fluids.inputKeys(), allowedIds)) {
      var definition = catalog.definition(candidate.id());
      var missing = definition == null ? Optional.<PortKind>empty() : requirements.missing(definition);
      if (missing.isPresent()) {
        continue;
      }
      if (ticket.interfacesMatch(candidate.interfaceInputs())
          && (definition == null
              || definition.chemicals().inputs().isEmpty()
              || ChemicalTransactions.inputsAvailable(ticket, definition.chemicals().inputs()))
          && catalog.fluidsAvailable(candidate.id(), fluids)) {
        fluidCandidates.add(candidate.id());
      }
    }
    // Logical aliases are counted once per physical slot and are needed only by
    // single-row item-only candidates. The ticket retains exact physical stamps.
    var snapshot = new ResourceSnapshot<>(ticket.structureRevision(), counts, spaces);
    session = RecipePlanner.prepare(snapshot, catalog.recipes(), fluidCandidates);
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
    var groups = ticket.inputGroups();
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
    if (groups != null) {
      return advanceGroups(budget);
    }
    if (selected != null) {
      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), 0);
    }
    int used = 0;
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
          deductions.add(new RecipeStartPlan.Deduction(slot.ref(), slot.key(), Math.toIntExact(amount)));
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
        pendingFactoryPlan = RecipeStartPlan
            .prepared(ticket, catalog, generic.candidate(), deductions, placements, inputChanceSeed, factoryScope);
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
    return session.totalWork() + fluidWork + checkWork;
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
        // Charge even an empty group, so a long list cannot bypass the tick budget.
        used++;
        completedGroupWork++;
        groupSearch = new RecipeSearch(
            ticket.withInputGroup(groups.get(groupCursor)),
            catalog,
            competitive,
            factoryThread,
            resources);
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
    return ticket.dependenciesCurrent() && catalog.isCurrent() && (factoryScope == null || factoryScope.current());
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
        for (int group : groups) {
          diagnoseResources(ticket.withInputGroup(group), candidates);
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
      var missing = definition == null ? Optional.<PortKind>empty() : requirements.missing(definition);
      if (missing.isPresent()) {
        missingResources.put(candidate.id(), new MissingResource(candidate, missing.orElseThrow()));
      } else {
        componentReady.add(candidate.id());
      }
    }
  }

  private record InputSlot(ItemSlotRef ref, ItemResourceKey key, int count) {
  }
}
