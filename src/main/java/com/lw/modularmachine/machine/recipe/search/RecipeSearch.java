package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeInputMiss;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.machine.recipe.RecipeItemPreparation;
import com.lw.modularmachine.machine.recipe.RecipeIngredientArrayResolution;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import java.util.Iterator;
import java.util.LinkedHashSet;

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
  private final RecipeSearchResources.GroupItems groupItems;
  private RecipePlanner.Session<ItemResourceKey> session;
  private ResourceSnapshot<ItemResourceKey> preparationSnapshot;
  private List<RecipeCandidate<ItemResourceKey>> screeningCandidates;
  private RecipeResourceRequirements screeningRequirements;
  private int screeningCursor;
  private List<RecipeSearchResource.Captured> screeningResources;
  private int screeningResourceCursor;
  private boolean screeningNeedsPreparation;
  private final List<RecipeCandidate<ItemResourceKey>> inputPreparationCandidates = new ArrayList<>();
  private final Map<String, CompiledRecipeSet.Entry<ItemResourceKey>> preparedCandidates = new HashMap<>();
  private final List<CompiledRecipeSet.Entry<ItemResourceKey>> orderedEntries = new ArrayList<>();
  private List<RecipeCandidate<ItemResourceKey>> screeningOrder;
  private int entryCursor;
  private final Map<String, PreparedInputs> preparedDefinitions = new HashMap<>();
  private int inputPreparationCursor;
  private RecipeItemCallbackService.InputPreparation inputPreparation;
  private RecipeIngredientArrayPreparation ingredientPreparation;
  private long inputPreparationEpoch = -1;
  private long inputPreparationWork;
  private RecipePreparationHandlers.Session preChecks;
  private boolean preChecked;
  private final Map<String, Integer> preparationLimits = new HashMap<>();

  private record PreparedInputs(
      MachineRecipe definition,
      RecipeCandidate<ItemResourceKey> original,
      RecipeCandidate<ItemResourceKey> effective,
      long epoch) {
  }
  private RecipeStartPlan selected;
  private final RecipeCatalog.CapturedResources resourceSearches;
  private boolean bootstrapComplete;
  private ResourcePlanningSequence resourcePlans;
  private RecipeInputPlanning resourceInputs;
  private long resourceWork;
  private final UpgradeRecipeCheckHandlers.Scope factoryScope;
  private final UpgradeBusOwner factory;
  private final String factoryThread;
  private RecipeStartPlan pendingFactoryPlan;
  private final PendingInputsQueue pendingInputs;
  private PendingCheck pendingCheck;
  private ResourcePlanningSequence pendingVerification;
  private RecipeSearchResource.ReplenishmentProof pendingProof;
  private long checkWork;
  private List<RecipeExecutionTicket> groups;
  private final RecipeCatalog.CandidateFilter filter;
  private int rankLimit = Integer.MAX_VALUE;
  private BitSet rejectedRanks;
  private int groupCursor;
  private RecipeSearch groupSearch;
  private RecipeStartPlan groupBest;
  private int groupBestIndex = -1;
  private final Map<Integer, BitSet> rejectedGroupRecipes = new HashMap<>();
  private long completedGroupWork;
  private final LinkedHashSet<RecipeResourceRequirements> roles = new LinkedHashSet<>();
  private List<RecipeCandidate<ItemResourceKey>> diagnosticCandidates;
  private int diagnosticCursor;
  private Iterator<RecipeResourceRequirements> diagnosticRoles;
  private PortKind missingResource;
  private boolean resourcesDiagnosed;
  private boolean physicalInputsMissing;
  private boolean allGroupsMissingInputs = true;
  private boolean allDiagnosticRolesPresent;
  private RecipeInputMiss inputMiss;
  private RecipeInputMiss.Capture inputMissCapture;
  private String interfaceFailure = "";

  /** Static recipe metadata retained after a successful locked factory start. */
  public static final class LockedRestart {
    private final RecipeCatalog catalog;
    private final RecipeCatalog.CandidateFilter filter;
    private final String threadId;
    final String recipeId;
    private final long generation;
    private final long definitionEpoch;
    private final long structureRevision;
    private final long configurationRevision;
    private final RecipeCatalog.SearchEntry entry;
    private final List<RecipeCandidate<ItemResourceKey>> candidates;
    private final List<CompiledRecipeSet.Entry<ItemResourceKey>> plannerEntries;
    private final ItemResourceKey itemAnchor;
    final ItemResourceKey singleInput;
    private final Set<ItemResourceKey> itemKeys;

    private LockedRestart(RecipeSearch search, String recipeId, long configurationRevision) {
      this.catalog = search.catalog;
      filter = search.filter;
      threadId = search.factoryThread;
      this.recipeId = recipeId;
      this.configurationRevision = configurationRevision;
      generation = search.ticket.generation();
      definitionEpoch = search.ticket.definitionEpoch();
      structureRevision = search.ticket.structureRevision();
      entry = catalog.searchEntry(recipeId);
      candidates = List.of(catalog.recipes().candidates().get(filter.firstRank));
      plannerEntries = List.of(entry.inputs());
      itemAnchor = catalog.recipes().inputIndex().anchorKey(filter.firstRank);
      itemKeys = entry.transformsInputs() ? null : entry.inputs().input.keySet();
      singleInput = itemKeys != null && itemKeys.size() == 1 ? itemKeys.iterator().next() : null;
    }

    Set<ItemResourceKey> itemKeys() {
      return itemKeys;
    }

    private List<RecipeCandidate<ItemResourceKey>> candidates(ResourceSnapshot<ItemResourceKey> snapshot, List<RecipeSearchResource.Captured> currentResources) {
      if (!entry.transformsInputs()) {
        if (itemAnchor != null && !snapshot.inputs().containsKey(itemAnchor)) {
          return List.of();
        }
        for (var resource : currentResources) {
          if (!resource.inputs().matchesRank(filter.firstRank)) {
            return List.of();
          }
        }
      }
      return candidates;
    }

    private boolean matches(RecipeCatalog currentCatalog, RecipeExecutionTicket ticket, Set<String> allowedIds, FactoryControllerBlockEntity controller) {
      return catalog == currentCatalog
          && allowedIds != null
          && allowedIds.size() == 1
          && allowedIds.contains(recipeId)
          && generation == ticket.generation()
          && definitionEpoch == ticket.definitionEpoch()
          && structureRevision == ticket.structureRevision()
          && configurationRevision == controller.threadConfiguration().revision();
    }
  }

  private record PendingInputs(
      RecipeCandidate<ItemResourceKey> candidate,
      MachineRecipe definition,
      RecipeExecutionTicket ticket) {
  }

  private record PendingKey(String recipeId, RecipeExecutionTicket ticket) {
  }

  private static final class PendingInputsQueue {
    private final PriorityQueue<PendingInputs> queue = new PriorityQueue<>((left, right) -> {
      int priority = Integer.compare(right.candidate().priority(), left.candidate().priority());
      return priority != 0 ? priority : left.candidate().id().compareTo(right.candidate().id());
    });
    private final Set<PendingKey> seen = new HashSet<>();

    private void add(PendingInputs pending) {
      if (seen.add(new PendingKey(pending.candidate().id(), pending.ticket()))) {
        queue.add(pending);
      }
    }
  }

  private record PendingCheck(
      PendingInputs pending,
      RecipeExecutionTicket ticket,
      RecipeInputPlanning inputs,
      ResourcePlanningSequence outputs,
      List<RecipeResourceExecution.Output> liveOutputs) {
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, RecipeCatalog.CandidateFilter filter, String factoryThread, List<RecipeExecutionTicket> groups) {
    this.ticket = ticket;
    this.catalog = catalog;
    resources = new RecipeSearchResources(catalog);
    this.filter = filter;
    this.factoryThread = startThread(ticket, factoryThread);
    this.groups = groups;
    pendingInputs = new PendingInputsQueue();
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeCheckHandlers.capture(factory, this.factoryThread);
    groupItems = null;
    session = null;
    resourceSearches = null;
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, RecipeCatalog.CandidateFilter filter, String factoryThread, RecipeSearchResources resources, PendingInputsQueue pendingInputs) {
    this.ticket = ticket;
    this.catalog = catalog;
    this.resources = resources;
    this.pendingInputs = pendingInputs;
    this.filter = filter;
    this.factoryThread = startThread(ticket, factoryThread);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeCheckHandlers.capture(factory, this.factoryThread);
    groupItems = resources.new GroupItems(ticket);
    resourceSearches = catalog.new CapturedResources(resources.restart == null ? null : resources.restart.recipeId);
  }

  private int advanceBootstrap(int budget) {
    int used = groupItems.advance(budget);
    if (groupItems.complete() && preparationSnapshot == null && used < budget) {
      preparationSnapshot = groupItems.snapshot();
      screeningRequirements = RecipeResourceRequirements.capture(ticket);
      used++;
    }
    while (preparationSnapshot != null && !resourceSearches.complete() && used < budget) {
      resourceSearches.captureNext(ticket, resources);
      used++;
    }
    if (preparationSnapshot != null && resourceSearches.complete() && used < budget) {
      screeningCandidates = resources.restart != null ? resources.restart
          .candidates(preparationSnapshot, resourceSearches.all()) : catalog.candidatesForPreparation(
              preparationSnapshot.inputs(),
              resourceSearches.all(),
              filter,
              rankLimit,
              rejectedRanks);
      physicalInputsMissing = factoryThread != null
          && ticket.owner() instanceof FactoryControllerBlockEntity
          && screeningCandidates.isEmpty()
          && rankLimit == Integer.MAX_VALUE
          && rejectedRanks == null
          && resourceSearches.missingPhysicalInputs(preparationSnapshot.inputs(), filter);
      bootstrapComplete = true;
      used++;
    }
    resourceWork += used;
    return used;
  }

  private int advanceCandidateScreening(int budget) {
    int used = 0;
    while (screeningCursor < screeningCandidates.size() && used < budget) {
      var candidate = screeningCandidates.get(screeningCursor);
      used++;
      resourceWork++;
      var definition = catalog.definition(candidate.id());
      if (screeningResources != null) {
        var resource = screeningResources.get(screeningResourceCursor++);
        if (!resource.available(candidate.id(), definition)) {
          screeningResources = null;
          screeningCursor++;
        } else if (screeningResourceCursor == screeningResources.size()) {
          acceptScreened(candidate);
        }
        continue;
      }
      var entry = catalog.searchEntry(candidate.id());
      boolean transformsInput = entry.transformsInputs();
      screeningNeedsPreparation = needsInputPreparation(entry);
      if (!transformsInput && definition != null && screeningRequirements.missing(definition).isPresent()
          || !transformsInput && !PatternProductionService.matches(ticket, definition, this.factoryThread)
          || !threadAllows(entry, definition, screeningNeedsPreparation)) {
        screeningCursor++;
        continue;
      }
      boolean interfacesMatch = ticket.interfacesMatch(candidate.interfaceInputs());
      if (!interfacesMatch && interfaceFailure.isEmpty()) {
        interfaceFailure = ticket.interfaceFailureMessage(candidate.interfaceInputs());
      }
      if (!interfacesMatch) {
        screeningCursor++;
      } else {
        screeningResources = transformsInput ? List.of() : resourceSearches.forRecipe(candidate.id());
        screeningResourceCursor = 0;
        if (screeningResources.isEmpty()) {
          acceptScreened(candidate);
        }
      }
    }
    if (screeningCursor == screeningCandidates.size()) {
      screeningOrder = screeningCandidates;
      screeningCandidates = null;
      if (resources.restart != null && session == null && inputPreparationCandidates.isEmpty()) {
        session = RecipePlanner.prepareEntries(preparationSnapshot, List.of());
      }
    }
    return used;
  }

  private void acceptScreened(RecipeCandidate<ItemResourceKey> candidate) {
    if (screeningNeedsPreparation) {
      inputPreparationCandidates.add(candidate);
      inputPreparationEpoch = itemCallbackEpoch;
    } else if (resources.restart != null) {
      session = RecipePlanner.prepareEntries(preparationSnapshot, resources.restart.plannerEntries);
    } else {
      preparedCandidates.put(candidate.id(), catalog.searchEntry(candidate.id()).inputs());
    }
    screeningCursor++;
    screeningResources = null;
  }

  private boolean needsInputPreparation(RecipeCatalog.SearchEntry entry) {
    return entry.transformsInputs()
        || entry.id() != null
            && RecipePreparationHandlers
                .has(ticket.owner().structureMachineId(), entry.id(), RecipePreparationEvents.Type.PRE_CHECK);
  }

  private boolean threadAllows(RecipeCatalog.SearchEntry entry, MachineRecipe definition, boolean needsPreparation) {
    if (definition == null) {
      return true;
    }
    if (ticket.owner() instanceof FactoryControllerBlockEntity controller) {
      // Raw factory plans can defer admission to a starter, but input preparation
      // already needs the real thread's parallel limit and callback context.
      return factoryThread == null ? !needsPreparation : controller
          .recipeThreadAllowed(factoryThread, entry.id(), definition.executionOptions());
    }
    return !(ticket.owner() instanceof MachineControllerBlockEntity)
        || definition.executionOptions().threadName().isEmpty();
  }

  static boolean resourcesFeasible(String recipeId, MachineRecipe definition, List<RecipeSearchResource.Captured> resources) {
    for (var resource : resources) {
      if (!resource.available(recipeId, definition)) {
        return false;
      }
    }

    return true;
  }

  private int advanceInputPreparations(int budget) {
    int used = 0;
    while (inputPreparationCursor < inputPreparationCandidates.size() && used < budget) {
      var original = inputPreparationCandidates.get(inputPreparationCursor);
      var definition = catalog.definition(original.id());
      if (!preChecked) {
        if (preChecks == null) {
          preChecks = RecipePreparationHandlers.begin(
              ticket.owner(),
              catalog.searchEntry(original.id()).id(),
              factoryThread,
              RecipePreparationEvents.Type.PRE_CHECK,
              RecipePreparationHandlers.parallelLimit(ticket, factoryThread),
              null);
        }
        int work = preChecks.advance(budget - used);
        used += work;
        inputPreparationWork += work;
        if (!preChecks.current() || !ticket.searchContextCurrent() || !catalog.isCurrent()) {
          throw new UpgradeRecipeCheckHandlers.StaleException();
        }
        if (!preChecks.complete()) {
          return used;
        }
        if (preChecks.rejected()) {
          preChecks = null;
          inputPreparationCursor++;
          continue;
        }
        preparationLimits.put(original.id(), preChecks.parallelism());
        preChecks = null;
        preChecked = true;
      }
      if (!definition.ingredientArrays().isEmpty()) {
        if (ingredientPreparation == null) {
          ingredientPreparation = new RecipeIngredientArrayPreparation(
              ticket,
              definition,
              original,
              preparationSnapshot,
              resourceSearches.forRecipe(original.id()),
              factoryThread,
              catalog);
        }
        ingredientPreparation.advance(budget - used);
        used += ingredientPreparation.workUsed();
        inputPreparationWork += ingredientPreparation.workUsed();
        if (!ingredientPreparation.finished()) {
          return used;
        }
        if (ingredientPreparation.prepared().isPresent()) {
          var nativeRecipe = ingredientPreparation.prepared().orElseThrow();
          var candidate = RecipeItemCallbackService
              .compilePrepared(nativeRecipe, catalog.searchEntry(original.id()).id(), ticket.level().registryAccess());
          recordPreparedCandidate(original, candidate, nativeRecipe);
          preparedDefinitions
              .put(original.id(), new PreparedInputs(nativeRecipe, original, candidate, ingredientPreparation.epoch()));
        }
        ingredientPreparation = null;
        preChecked = false;
        inputPreparationCursor++;
        continue;
      }
      if (inputPreparation == null) {
        var registries = ticket.level().registryAccess();
        var thread = ticket.owner() instanceof FactoryControllerBlockEntity ? Optional.of(factoryThread) : Optional
            .<String>empty();
        inputPreparation = RecipeItemCallbackService.beginInputs(
            definition,
            () -> MachineRecipe.CODEC.codec()
                .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), definition).getOrThrow()
                .getAsJsonObject(),
            RecipeItemPreparation.context(ticket, catalog.searchEntry(original.id()).id(), thread, 1),
            registries);
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
        var nativeRecipe = value.definition();
        if (RecipeResourceRequirements.missing(ticket, nativeRecipe).isEmpty()
            && resourcesFeasible(original.id(), nativeRecipe, resourceSearches.forRecipe(original.id()))
            && PatternProductionService.matches(ticket, nativeRecipe, factoryThread)) {
          var candidate = RecipeItemCallbackService
              .compilePrepared(nativeRecipe, catalog.searchEntry(original.id()).id(), ticket.level().registryAccess());
          recordPreparedCandidate(original, candidate, nativeRecipe);
          preparedDefinitions.put(original.id(), new PreparedInputs(nativeRecipe, original, candidate, value.epoch()));
        }
      }
      inputPreparation = null;
      preChecked = false;
      inputPreparationCursor++;
    }
    if (inputPreparationCursor == inputPreparationCandidates.size()) {
      while (entryCursor < screeningOrder.size() && used < budget) {
        var entry = preparedCandidates.get(screeningOrder.get(entryCursor++).id());
        if (entry != null) {
          orderedEntries.add(entry);
        }
        used++;
        inputPreparationWork++;
      }
      if (entryCursor == screeningOrder.size()) {
        session = RecipePlanner.prepareEntries(preparationSnapshot, orderedEntries);
      }
    }
    return used;
  }

  private void recordPreparedCandidate(RecipeCandidate<ItemResourceKey> original, RecipeCandidate<ItemResourceKey> candidate, MachineRecipe definition) {
    preparedCandidates.put(
        original.id(),
        CompiledRecipeSet.compileCandidate(RecipeItemPreparation.inputCandidate(candidate, definition)));
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
    return begin(owner, catalog, allowedIds, null, null);
  }

  private static Optional<RecipeSearch> begin(StructureService.StructureSubject owner, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, LockedRestart restart) {
    Objects.requireNonNull(catalog, "catalog");
    if (catalog.recipes().size() == 0) {
      return Optional.empty();
    }
    // capture enforces server affinity before the search reads any inventory or
    // compiles slot
    // limits.
    var captured = RecipeExecutionTicket.captureForSearch(owner);
    if (captured.isEmpty() || !owner.structureMachineId().equals(catalog.machineId()) || !catalog.isCurrent()) {
      return Optional.empty();
    }
    var ticket = captured.orElseThrow();
    PatternProductionService.observe(owner, catalog, factoryThread);
    var groups = ticket.searchContexts();
    if (restart != null
        && (groups.size() != 1
            || !(owner instanceof FactoryControllerBlockEntity controller)
            || !restart.matches(catalog, ticket, allowedIds, controller))) {
      restart = null;
    }
    var filter = restart == null ? catalog.candidateFilter(allowedIds) : restart.filter;
    var search = groups.size() == 1 ? new RecipeSearch(
        ticket,
        catalog,
        filter,
        factoryThread,
        new RecipeSearchResources(catalog, restart),
        new PendingInputsQueue()) : new RecipeSearch(ticket, catalog, filter, factoryThread, groups);
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
    return beginFactory(factory, threadId, catalog, allowedIds, null);
  }

  public static Optional<RecipeSearch> beginFactory(FactoryControllerBlockEntity factory, String threadId, RecipeCatalog catalog, Set<String> allowedIds, LockedRestart restart) {
    Objects.requireNonNull(threadId, "threadId");
    var thread = factory.scheduler().channel(threadId);
    if (thread == null)
      return Optional.empty();
    var coreAllowed = FactoryThreadPreset.allowedRecipes(factory.structureMachineId(), threadId, thread.lockedRecipe());
    if (coreAllowed != null) {
      if (allowedIds == null)
        allowedIds = coreAllowed;
      else {
        var intersection = new HashSet<>(allowedIds);
        intersection.retainAll(coreAllowed);
        allowedIds = Set.copyOf(intersection);
      }
    }
    var finalAllowed = allowedIds;
    var locked = restart != null
        && threadId.equals(restart.threadId)
        && thread.lockedRecipe() != null
        && thread.lockedRecipe().toString().equals(restart.recipeId) ? restart : null;
    return MachineRecipeService.catalogForFactory(factory, threadId, catalog)
        .flatMap(effective -> begin(factory, effective, finalAllowed, threadId, locked));
  }

  public LockedRestart lockedRestart(String recipeId, long configurationRevision) {
    if (!catalog.supportsLockedRestart()
        || groups != null
        || selected == null
        || !selected.recipe().id().equals(recipeId)
        || filter.allowedIds == null
        || filter.allowedIds.size() != 1
        || !filter.allowedIds.contains(recipeId)) {
      return null;
    }
    return resources.restart == null ? new LockedRestart(this, recipeId, configurationRevision) : resources.restart;
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

  public static long tickDeadline(long startedNanos) {
    return startedNanos + 2_000_000L;
  }

  public Result advance(int budget) {
    return advance(budget, Long.MAX_VALUE);
  }

  public Result advance(int budget, long deadline) {
    int used = 0;
    while (true) {
      int slice = deadline == Long.MAX_VALUE ? budget - used : Math.min(32, budget - used);
      var result = advanceSearch(slice);
      used += result.workUsed();
      if (result.status() == RecipePlanner.Status.NO_MATCH) {
        break;
      }
      if (result.status() == RecipePlanner.Status.SELECTED
          || used >= budget
          || result.workUsed() == 0
          || deadlineReached(deadline)) {
        return new Result(result.status(), result.plan(), used);
      }
    }
    while (pendingCheck != null || !pendingInputs.queue.isEmpty()) {
      if (used >= budget || used > 0 && deadlineReached(deadline)) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (pendingCheck == null) {
        used++;
        addResourceWork(1);
        pendingCheck = preparePendingInputs(pendingInputs.queue.remove());
        if (pendingCheck == null) {
          continue;
        }
      }
      var inputs = pendingCheck.inputs().advance(pendingBudget(budget, used, deadline));
      used += inputs.workUsed();
      addResourceWork(inputs.workUsed());
      if (inputs.status() == RecipeResourcePlanning.Status.PENDING) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (pendingCheck.inputs().waiting()) {
        var outputs = pendingCheck.outputs().advance(pendingBudget(budget, used, deadline));
        used += outputs.workUsed();
        addResourceWork(outputs.workUsed());
        if (outputs.status() == RecipeResourcePlanning.Status.PENDING) {
          return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
        }
        if (outputs.status() == RecipeResourcePlanning.Status.READY) {
          if (pendingBudget(budget, used, deadline) == 0) {
            return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
          }
          if (pendingVerification == null || !pendingProof.current()) {
            var proof = new RecipeSearchResource.ReplenishmentProof();
            var steps = new ArrayList<Supplier<RecipeResourcePlanning>>();
            pendingCheck.inputs().addRechecks(steps, proof);
            for (var output : pendingCheck.liveOutputs()) {
              if (output instanceof RecipeSearchResource.Recheckable recheckable) {
                steps.add(() -> recheckable.recheck(proof));
              }
            }
            pendingProof = proof;
            pendingVerification = new ResourcePlanningSequence(steps);
          }
          var verification = pendingVerification.advance(pendingBudget(budget, used, deadline));
          used += verification.workUsed();
          addResourceWork(verification.workUsed());
          if (verification.status() == RecipeResourcePlanning.Status.PENDING
              || verification.status() == RecipeResourcePlanning.Status.READY && used == budget) {
            return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
          }
          var current = verification.status() == RecipeResourcePlanning.Status.READY ? pendingCheck.ticket()
              .refreshIfUnchanged().orElse(null) : null;
          if (current != null
              && catalog.isCurrent()
              && RecipeItemCallbackService.isCurrent(itemCallbackEpoch)
              && checkPendingItems(current, pendingCheck.pending())
              && pendingCheck.inputs().current()
              && pendingCheck.liveOutputs().stream().allMatch(
                  output -> output instanceof RecipeSearchResource.Recheckable || output.searchCurrent(current))) {
            var published = pendingProof.publishOne(
                pendingCheck.inputs().nextRequestKey(),
                budget - used,
                budget,
                () -> !deadlineReached(deadline),
                stillCurrent -> pendingCheck.inputs().advanceRequests(1, stillCurrent));
            used += published.workUsed();
            addResourceWork(published.workUsed());
            if (published.status() == RecipeResourcePlanning.Status.PENDING) {
              if (published.workUsed() > 0 && used < budget && !deadlineReached(deadline)) {
                continue;
              }
              return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
            }
          } else if (used < budget) {
            used++;
            addResourceWork(1);
          }
          if (pendingCheck.inputs().requested()) {
            pendingInputs.queue.clear();
          }
        }
      }
      pendingCheck = null;
      pendingVerification = null;
      pendingProof = null;
    }
    return advanceDiagnostics(used, budget, deadline);
  }

  private static int pendingBudget(int budget, int used, long deadline) {
    if (deadline == Long.MAX_VALUE) {
      return budget - used;
    }
    return used > 0 && deadlineReached(deadline) ? 0 : Math.min(32, budget - used);
  }

  private static boolean deadlineReached(long deadline) {
    return deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0;
  }

  private void addResourceWork(int work) {
    if (groups == null) {
      resourceWork += work;
    } else {
      completedGroupWork += work;
    }
  }

  private PendingCheck preparePendingInputs(PendingInputs pending) {
    var current = catalog.ticketForRecipe(pending.ticket(), pending.candidate(), pending.definition())
        .refreshIfUnchanged().orElse(null);
    var definition = pending.definition();
    var candidate = pending.candidate();
    if (current == null
        || !current.interfacesMatch(candidate.interfaceInputs())
        || !PatternProductionService.matches(current, definition, factoryThread)
        || RecipeResourceRequirements.missing(current, definition).isPresent()) {
      return null;
    }
    var captured = catalog.captureForRecipe(candidate.id(), current, new RecipeSearchResources(catalog));
    var liveOutputs = new ArrayList<RecipeResourceExecution.Output>();
    var steps = new ArrayList<Supplier<RecipeResourcePlanning>>(captured.size());
    for (var resource : captured) {
      steps.add(() -> {
        var output = resource.outputs(candidate.id(), definition);
        if (output instanceof RecipeResourceExecution.Output live) {
          liveOutputs.add(live);
        }
        return output;
      });
    }
    return new PendingCheck(
        pending,
        current,
        new RecipeInputPlanning(candidate.id(), definition, captured),
        new ResourcePlanningSequence(steps),
        liveOutputs);
  }

  private boolean checkPendingItems(RecipeExecutionTicket current, PendingInputs pending) {
    var definition = pending.definition();
    if (definition.scriptOptions().itemCallbacks().stream().noneMatch(callback -> callback.checker().isPresent())) {
      return true;
    }
    if (current.owner() instanceof FactoryControllerBlockEntity && factoryThread == null) {
      return false;
    }
    var recipeId = catalog.searchEntry(pending.candidate().id()).id();
    var thread = current.owner() instanceof FactoryControllerBlockEntity ? Optional.of(factoryThread) : Optional
        .<String>empty();
    var check = RecipeItemCallbackService.checkInputs(
        definition.scriptOptions(),
        RecipeItemPreparation.context(current, recipeId, thread, 1),
        RecipeIngredientArrayResolution.checkerSlices(definition, current, 1),
        current.level().registryAccess(),
        itemCallbackEpoch);
    return check == RecipeItemCallbackService.InputCheck.ALLOWED && current.dependenciesCurrent();
  }

  private Result advanceSearch(int budget) {
    ticket.requireServerThread();
    if (budget < 0) {
      throw new IllegalArgumentException("Negative planning budget");
    }
    if (factoryScope != null && !factoryScope.current()) {
      throw new UpgradeRecipeCheckHandlers.StaleException();
    }
    if (inputPreparationEpoch >= 0 && !RecipeItemCallbackService.isCurrent(inputPreparationEpoch)) {
      throw new UpgradeRecipeCheckHandlers.StaleException();
    }
    if (!RecipeItemCallbackService.isCurrent(itemCallbackEpoch)) {
      throw new UpgradeRecipeCheckHandlers.StaleException();
    }
    if (groups != null) {
      return advanceGroups(budget);
    }
    if (selected != null) {
      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), 0);
    }
    int used = 0;
    if (!bootstrapComplete) {
      used += advanceBootstrap(budget);
      if (!bootstrapComplete) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
    }
    if (screeningCandidates != null) {
      used += advanceCandidateScreening(budget - used);
      if (screeningCandidates != null) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
    }
    if (session == null) {
      used += advanceInputPreparations(budget - used);
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
      var prepared = preparedDefinitions.get(generic.candidate().id());
      var definition = prepared == null ? catalog.definition(generic.candidate().id()) : prepared.definition();
      if (resourceInputs == null) {
        resourceInputs = new RecipeInputPlanning(
            generic.candidate().id(),
            definition,
            resourceSearches.forRecipe(generic.candidate().id()));
      }
      var checkedInputs = resourceInputs.advance(budget - used);
      used += checkedInputs.workUsed();
      resourceWork += checkedInputs.workUsed();
      if (checkedInputs.status() == RecipeResourcePlanning.Status.PENDING) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (checkedInputs.status() == RecipeResourcePlanning.Status.REJECTED) {
        resourceInputs = null;
        session.rejectSelected();
        continue;
      }
      if (resourcePlans == null) {
        var candidateResources = resourceSearches.forRecipe(generic.candidate().id());
        var steps = new ArrayList<Supplier<RecipeResourcePlanning>>(candidateResources.size());
        for (var resource : candidateResources) {
          steps.add(() -> resource.outputs(generic.candidate().id(), definition));
        }
        resourcePlans = new ResourcePlanningSequence(steps);
      }
      var planned = resourcePlans.advance(budget - used);
      used += planned.workUsed();
      resourceWork += planned.workUsed();
      if (planned.status() == RecipeResourcePlanning.Status.PENDING) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (planned.status() == RecipeResourcePlanning.Status.REJECTED) {
        resourceInputs = null;
        resourcePlans = null;
        session.rejectSelected();
        continue;
      }
      if (pendingFactoryPlan == null) {
        long inputChanceSeed = RecipeStartPlan.newInputChanceSeed();
        var needed = new HashMap<>(RecipeStartPlan.sampledInputs(generic.candidate(), inputChanceSeed, 1));
        var deductions = new ArrayList<RecipeStartPlan.Deduction>();
        for (var port : groupItems.inputs()) {
          for (var slot : port) {
            var expected = RecipeStartPlan.inputKey(generic.candidate(), slot.key());
            long amount = Math.min(needed.getOrDefault(expected, 0L), slot.count());
            if (amount > 0) {
              deductions.add(new RecipeStartPlan.Deduction(slot.ref(), slot.key(), amount));
              needed.put(expected, needed.get(expected) - amount);
            }
          }
        }
        if (needed.values().stream().anyMatch(count -> count != 0)) {
          throw new IllegalStateException("Input slot accounting differs from exact-key plan");
        }
        var placements = generic.insertions().stream()
            .map(
                insertion -> RecipeStartPlan.Placement.inSlot(
                    resources.outputRef(insertion.slot()),
                    preparationSnapshot.outputs().get(insertion.slot()),
                    insertion.key(),
                    Math.toIntExact(insertion.count())))
            .toList();
        var candidate = prepared == null ? catalog.recipes().candidate(generic.candidate().id())
            .orElseThrow() : prepared.effective();
        var recipeTicket = catalog.ticketForRecipe(ticket, candidate, definition);
        pendingFactoryPlan = RecipeStartPlan
            .prepared(recipeTicket, catalog, candidate, deductions, placements, inputChanceSeed, factoryScope)
            .withOutputPreparationContext(factoryThread)
            .withPreparationLimit(preparationLimits.getOrDefault(generic.candidate().id(), Integer.MAX_VALUE));
        if (prepared != null) {
          pendingFactoryPlan.withPreparedInputs(prepared.definition(), prepared.original(), prepared.epoch());
        }
      }
      if (!pendingFactoryPlan.resourcesCurrent()) {
        resourceInputs = null;
        pendingFactoryPlan = null;
        resourcePlans = null;
        session.rejectSelected();
        continue;
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
            resourceInputs = null;
            pendingFactoryPlan = null;
            resourcePlans = null;
            session.rejectSelected();
            continue;
          }
          case STALE -> throw new UpgradeRecipeCheckHandlers.StaleException();
          case ERROR -> throw new UpgradeRecipeCheckHandlers.FailedException();
          case ALLOWED -> {
          }
        }
      }
      if (resourceInputs.waiting()) {
        pendingInputs.add(new PendingInputs(generic.candidate(), definition, ticket));
        resourceInputs = null;
        pendingFactoryPlan = null;
        resourcePlans = null;
        session.rejectSelected();
        continue;
      }
      selected = pendingFactoryPlan;

      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), used);
    }
  }

  public long totalWork() {
    if (groups != null) {
      return completedGroupWork + (groupSearch == null ? 0 : groupSearch.totalWork());
    }
    return (session == null ? 0 : session.totalWork()) + inputPreparationWork + resourceWork + checkWork;
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
      resourceInputs = null;
      pendingFactoryPlan = null;
      resourcePlans = null;
    } else {
      rejectGroupBest();
    }
    selected = null;
    return true;
  }

  private void rejectGroupBest() {
    // Revisit groups pruned by this plan, keeping the shared physical scans.
    rejectedGroupRecipes.computeIfAbsent(groupBestIndex, ignored -> new BitSet()).set(rankLimit);
    rankLimit = Integer.MAX_VALUE;
    groupCursor = 0;
    groupSearch = null;
    groupBest = null;
    groupBestIndex = -1;
  }

  private Result advanceGroups(int budget) {
    if (selected != null) {
      return new Result(RecipePlanner.Status.SELECTED, Optional.of(selected), 0);
    }
    int used = 0;
    if (groupBest != null && !groupBest.resourcesCurrent()) {
      rejectGroupBest();
    }
    while (groupCursor < groups.size()) {
      if (used >= budget) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (groupSearch == null) {
        if (rankLimit <= filter.firstRank) {
          // No allowed recipe in a later group can outrank the current best.
          groupCursor = groups.size();
          break;
        }
        // Charge even an empty group, so a long list cannot bypass the tick budget.
        used++;
        completedGroupWork++;
        groupSearch = new RecipeSearch(
            groups.get(groupCursor),
            catalog,
            filter,
            factoryThread,
            resources,
            pendingInputs);
        groupSearch.rankLimit = rankLimit;
        groupSearch.rejectedRanks = rejectedGroupRecipes.get(groupCursor);
      }
      var result = groupSearch.advanceSearch(budget - used);
      used += result.workUsed();
      if (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return new Result(result.status(), Optional.empty(), used);
      }
      if (result.plan().isPresent()) {
        var candidate = result.plan().orElseThrow();
        int rank = catalog.recipes().rankOf(candidate.recipe().id());
        if (rank < rankLimit) {
          groupBest = candidate;
          groupBestIndex = groupCursor;
          rankLimit = rank;
        }
      }
      if (interfaceFailure.isEmpty()) {
        interfaceFailure = groupSearch.interfaceFailure();
      }
      // Preserve the last group's missing-role reason when several groups have
      // identical roles.
      roles.remove(groupSearch.screeningRequirements);
      roles.add(groupSearch.screeningRequirements);
      allGroupsMissingInputs &= groupSearch.physicalInputsMissing;
      if (!allDiagnosticRolesPresent) {
        allDiagnosticRolesPresent = catalog.allDiagnosticRolesPresent(groupSearch.screeningRequirements, filter);
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

  public boolean dependenciesCurrent() {
    return (selected == null ? ticket.searchContextCurrent() : selected.resourcesCurrent())
        && catalog.isCurrent()
        && RecipeItemCallbackService.isCurrent(itemCallbackEpoch)
        && (factoryScope == null || factoryScope.current())
        && (inputPreparationEpoch < 0 || RecipeItemCallbackService.isCurrent(inputPreparationEpoch));
  }

  /** Known absent roles after a complete search, never inventory shortages. */
  public Optional<PortKind> missingResource() {
    ticket.requireServerThread();
    return Optional.ofNullable(missingResource);
  }

  public String interfaceFailure() {
    return interfaceFailure;
  }

  public RecipeInputMiss missingInputs() {
    return inputMiss;
  }

  public boolean buildingInputMiss() {
    return inputMissCapture != null;
  }

  private Result advanceDiagnostics(int used, int budget, long deadline) {
    if (diagnosticCandidates == null) {
      if (groups == null) {
        roles.add(screeningRequirements);
        allDiagnosticRolesPresent = catalog.allDiagnosticRolesPresent(screeningRequirements, filter);
      }
      diagnosticCandidates = allDiagnosticRolesPresent ? List.of() : catalog.recipes().candidatesFor(filter.allowedIds);
      if (allDiagnosticRolesPresent && (groups == null ? physicalInputsMissing : allGroupsMissingInputs)) {
        inputMissCapture = ticket.beginMissingFactoryInputs(factoryThread, catalog);
      }
    }
    if (inputMissCapture != null) {
      int captured = inputMissCapture.advance(budget - used, deadline);
      addResourceWork(captured);
      used += captured;
      inputMiss = inputMissCapture.result();
      if (inputMiss == null) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      inputMissCapture = null;
    }
    int before = used;
    while (!resourcesDiagnosed
        && diagnosticCursor < diagnosticCandidates.size()
        && used < budget
        && (used == 0 || !deadlineReached(deadline))) {
      var candidate = diagnosticCandidates.get(diagnosticCursor);
      var definition = catalog.definition(candidate.id());
      used++;
      if (diagnosticRoles == null) {
        var entry = catalog.searchEntry(candidate.id());
        if (definition == null || !threadAllows(entry, definition, needsInputPreparation(entry))) {
          diagnosticCursor++;
          continue;
        }
        diagnosticRoles = roles.iterator();
      }
      var missing = diagnosticRoles.next().missing(definition);
      if (missing.isEmpty()) {
        diagnosticCursor++;
        diagnosticRoles = null;
      } else if (!diagnosticRoles.hasNext()) {
        // Candidate order is priority then ID. One fully equipped group suppresses its
        // diagnosis.
        missingResource = missing.orElseThrow();
        resourcesDiagnosed = true;
      }
    }
    addResourceWork(used - before);
    resourcesDiagnosed |= diagnosticCursor == diagnosticCandidates.size();
    return new Result(
        resourcesDiagnosed ? RecipePlanner.Status.NO_MATCH : RecipePlanner.Status.BUDGET_EXHAUSTED,
        Optional.empty(),
        used);
  }

}
