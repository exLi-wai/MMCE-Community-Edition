package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.component.ResourceType;
import com.lw.modularmachine.api.recipe.resource.RecipeResourcePlanning;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemSlotRef;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.machine.recipe.RecipeItemPreparation;
import com.lw.modularmachine.machine.recipe.RecipeIngredientArrayResolution;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecution;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import com.lw.modularmachine.machine.upgrade.UpgradeRecipeCheckHandlers;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;

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
  private List<RecipeCandidate<ItemResourceKey>> screeningCandidates;
  private RecipeResourceRequirements screeningRequirements;
  private int screeningCursor;
  private final List<RecipeCandidate<ItemResourceKey>> inputPreparationCandidates = new ArrayList<>();
  private final List<RecipeCandidate<ItemResourceKey>> preparedCandidates = new ArrayList<>();
  private final Map<String, PreparedInputs> preparedDefinitions = new HashMap<>();
  private int inputPreparationCursor;
  private RecipeItemCallbackService.InputPreparation inputPreparation;
  private RecipeIngredientArrayPreparation ingredientPreparation;
  private long inputPreparationEpoch = -1;
  private long inputPreparationWork;
  private RecipePreparationHandlers.Session preChecks;
  private boolean preChecked;
  private final Map<String, Integer> preparationLimits = new HashMap<>();

  private record PreparedInputs(MachineRecipe definition, RecipeCandidate<ItemResourceKey> original, long epoch) {
  }
  private RecipeStartPlan selected;
  private final List<RecipeSearchResource.Captured> resourceSearches;
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
  private String interfaceFailure = "";

  private record MissingResource(RecipeCandidate<ItemResourceKey> candidate, PortKind kind) {
  }

  private record OutputSlot(ItemSlotRef ref, ResourceSnapshot.OutputSlot<ItemResourceKey> snapshot) {
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

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, List<RecipeExecutionTicket> groups) {
    this.ticket = ticket;
    this.catalog = catalog;
    resources = new RecipeSearchResources(catalog);
    this.allowedIds = allowedIds == null ? null : Set.copyOf(allowedIds);
    this.factoryThread = startThread(ticket, factoryThread);
    this.groups = List.copyOf(groups);
    pendingInputs = new PendingInputsQueue();
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeCheckHandlers.capture(factory, this.factoryThread);
    inputs = List.of();
    outputs = List.of();
    session = null;
    resourceSearches = List.of();
  }

  private RecipeSearch(RecipeExecutionTicket ticket, RecipeCatalog catalog, Set<String> allowedIds, String factoryThread, RecipeSearchResources resources, PendingInputsQueue pendingInputs) {
    this.ticket = ticket;
    this.catalog = catalog;
    this.resources = resources;
    this.pendingInputs = pendingInputs;
    this.allowedIds = allowedIds == null ? null : Set.copyOf(allowedIds);
    this.factoryThread = startThread(ticket, factoryThread);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : UpgradeRecipeCheckHandlers.capture(factory, this.factoryThread);
    var inputSlots = new ArrayList<InputSlot>();
    var outputSlots = new ArrayList<OutputSlot>();
    var counts = new LinkedHashMap<ItemResourceKey, Long>();
    var spaces = new ArrayList<ResourceSnapshot.OutputSlot<ItemResourceKey>>();
    for (var port : ticket.ports()) {
      if (port.kind().resource() != ResourceType.ITEM) {
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
    screeningRequirements = RecipeResourceRequirements.capture(ticket);
    resourceSearches = catalog.captureResources(ticket, resources);
    screeningCandidates = catalog.candidatesForPreparation(counts, resourceSearches, allowedIds);
    // Logical aliases are counted once per physical slot and are needed only by
    // single-row item-only candidates. The ticket retains exact physical stamps.
    preparationSnapshot = new ResourceSnapshot<>(ticket.structureRevision(), counts, spaces);
  }

  private int advanceCandidateScreening(int budget) {
    int used = 0;
    while (screeningCursor < screeningCandidates.size() && used < budget) {
      var candidate = screeningCandidates.get(screeningCursor++);
      used++;
      resourceWork++;
      var definition = catalog.definition(candidate.id());
      boolean transformsInput = definition != null
          && (!definition.ingredientArrays().isEmpty()
              || definition.scriptOptions().itemCallbacks().stream()
                  .anyMatch(binding -> binding.io() == RecipeModifier.Io.INPUT && !binding.modifiers().isEmpty()));
      boolean needsPreparation = transformsInput
          || definition != null
              && RecipePreparationHandlers.has(
                  ticket.owner().structureMachineId(),
                  ResourceLocation.parse(candidate.id()),
                  RecipePreparationEvents.Type.PRE_CHECK);
      var missing = definition == null ? Optional.<PortKind>empty() : screeningRequirements.missing(definition);
      if (!transformsInput && missing.isPresent()
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
      boolean interfacesMatch = ticket.interfacesMatch(candidate.interfaceInputs());
      if (!interfacesMatch && interfaceFailure.isEmpty()) {
        interfaceFailure = ticket.interfaceFailureMessage(candidate.interfaceInputs());
      }
      if (interfacesMatch && (transformsInput || resourcesFeasible(candidate.id(), definition, resourceSearches))) {
        if (needsPreparation) {
          inputPreparationCandidates.add(candidate);
          inputPreparationEpoch = itemCallbackEpoch;
        } else {
          preparedCandidates.add(RecipeItemPreparation.inputCandidate(candidate, definition));
        }
      }
    }
    if (screeningCursor == screeningCandidates.size()) {
      screeningCandidates = null;
      screeningRequirements = null;
    }
    return used;
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
              ResourceLocation.parse(original.id()),
              factoryThread,
              RecipePreparationEvents.Type.PRE_CHECK,
              RecipePreparationHandlers.parallelLimit(ticket, factoryThread),
              null);
        }
        int work = preChecks.advance(budget - used);
        used += work;
        inputPreparationWork += work;
        if (!preChecks.current() || !ticket.dependenciesCurrent() || !catalog.isCurrent()) {
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
              resourceSearches,
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
              .compilePrepared(nativeRecipe, ResourceLocation.parse(original.id()), ticket.level().registryAccess());
          preparedCandidates.add(RecipeItemPreparation.inputCandidate(candidate, nativeRecipe));
          preparedDefinitions
              .put(original.id(), new PreparedInputs(nativeRecipe, original, ingredientPreparation.epoch()));
        }
        ingredientPreparation = null;
        preChecked = false;
        inputPreparationCursor++;
        continue;
      }
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
        if (RecipeResourceRequirements.missing(ticket, nativeRecipe).isEmpty()
            && resourcesFeasible(original.id(), nativeRecipe, resourceSearches)
            && PatternProductionService.matches(ticket, nativeRecipe, factoryThread)) {
          var candidate = RecipeItemCallbackService
              .compilePrepared(nativeRecipe, ResourceLocation.parse(original.id()), ticket.level().registryAccess());
          preparedCandidates.add(RecipeItemPreparation.inputCandidate(candidate, nativeRecipe));
          preparedDefinitions.put(original.id(), new PreparedInputs(nativeRecipe, original, value.epoch()));
        }
      }
      inputPreparation = null;
      preChecked = false;
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
        new RecipeSearchResources(catalog),
        new PendingInputsQueue()) : new RecipeSearch(ticket, catalog, allowedIds, factoryThread, groups);
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
    return MachineRecipeService.catalogForFactory(factory, threadId, catalog)
        .flatMap(effective -> begin(factory, effective, finalAllowed, threadId));
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
    var result = advanceSearch(budget);
    if (result.status() != RecipePlanner.Status.NO_MATCH) {
      return result;
    }
    int used = result.workUsed();
    while (pendingCheck != null || !pendingInputs.queue.isEmpty()) {
      if (used >= budget) {
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
      var inputs = pendingCheck.inputs().advance(budget - used);
      used += inputs.workUsed();
      addResourceWork(inputs.workUsed());
      if (inputs.status() == RecipeResourcePlanning.Status.PENDING) {
        return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
      }
      if (pendingCheck.inputs().waiting()) {
        var outputs = pendingCheck.outputs().advance(budget - used);
        used += outputs.workUsed();
        addResourceWork(outputs.workUsed());
        if (outputs.status() == RecipeResourcePlanning.Status.PENDING) {
          return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
        }
        if (outputs.status() == RecipeResourcePlanning.Status.READY) {
          if (used == budget) {
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
          var verification = pendingVerification.advance(budget - used);
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
                  output -> output instanceof RecipeSearchResource.Recheckable || output.searchCurrent(current))
              && pendingProof.current()) {
            int liveWork = pendingProof.liveWork();
            if (liveWork >= budget - used) {
              if (used > 0) {
                return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
              }
              used++;
              addResourceWork(1);
            } else {
              used += liveWork;
              addResourceWork(liveWork);
              if (pendingProof.liveCurrent()) {
                var published = pendingCheck.inputs().advanceRequests(budget - used, pendingProof::current);
                used += published.workUsed();
                addResourceWork(published.workUsed());
                if (published.status() == RecipeResourcePlanning.Status.PENDING) {
                  return new Result(RecipePlanner.Status.BUDGET_EXHAUSTED, Optional.empty(), used);
                }
              }
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
    return new Result(RecipePlanner.Status.NO_MATCH, Optional.empty(), used);
  }

  private void addResourceWork(int work) {
    if (groups == null) {
      resourceWork += work;
    } else {
      completedGroupWork += work;
    }
  }

  private PendingCheck preparePendingInputs(PendingInputs pending) {
    var current = pending.ticket().refreshIfUnchanged().orElse(null);
    var definition = pending.definition();
    var candidate = pending.candidate();
    if (current == null
        || !current.interfacesMatch(candidate.interfaceInputs())
        || !PatternProductionService.matches(current, definition, factoryThread)
        || RecipeResourceRequirements.missing(current, definition).isPresent()) {
      return null;
    }
    var captured = catalog.captureResources(current, new RecipeSearchResources(catalog));
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
    var recipeId = ResourceLocation.parse(pending.candidate().id());
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
    if (screeningCandidates != null) {
      used += advanceCandidateScreening(budget);
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
        resourceInputs = new RecipeInputPlanning(generic.candidate().id(), definition, resourceSearches);
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
        var steps = new ArrayList<Supplier<RecipeResourcePlanning>>(resourceSearches.size());
        for (var resource : resourceSearches) {
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
        var candidate = prepared == null ? catalog.recipes().candidate(generic.candidate().id())
            .orElseThrow() : RecipeItemCallbackService.compilePrepared(
                prepared.definition(),
                ResourceLocation.parse(generic.candidate().id()),
                ticket.level().registryAccess());
        pendingFactoryPlan = RecipeStartPlan
            .prepared(ticket, catalog, candidate, deductions, placements, inputChanceSeed, factoryScope)
            .withOutputPreparationContext(factoryThread)
            .withPreparationLimit(preparationLimits.getOrDefault(generic.candidate().id(), Integer.MAX_VALUE));
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
        groupSearch = new RecipeSearch(
            groups.get(groupCursor),
            catalog,
            competitive,
            factoryThread,
            resources,
            pendingInputs);
      }
      var result = groupSearch.advanceSearch(budget - used);
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
      if (interfaceFailure.isEmpty()) {
        interfaceFailure = groupSearch.interfaceFailure();
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

  public String interfaceFailure() {
    return interfaceFailure;
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
