package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRecipeChecks;
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
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Server-owned bridge to a pure resumable planner. No method consumes or
 * inserts an item.
 */
public final class ItemRecipeSearch {
  private final RecipeExecutionTicket ticket;
  private final ItemRecipeCatalog catalog;
  private final List<InputSlot> inputs;
  private final List<ItemSlotRef> outputs;
  private final RecipePlanner.Session<ItemResourceKey> session;
  private ItemRecipePlan selected;
  private final FluidOutputLayout fluidLayout;
  private FluidOutputLayout.Planning fluidPlan;
  private long fluidWork;
  private final FactoryUpgradeRecipeChecks.Scope factoryScope;
  private final UpgradeBusOwner factory;
  private final String factoryThread;
  private ItemRecipePlan pendingFactoryPlan;
  private long checkWork;
  private List<Integer> groups;
  private Set<String> allowedIds;
  private int groupCursor;
  private ItemRecipeSearch groupSearch;
  private ItemRecipePlan groupBest;
  private long completedGroupWork;

  private ItemRecipeSearch(RecipeExecutionTicket ticket, ItemRecipeCatalog catalog, Set<String> allowedIds, String factoryThread, List<Integer> groups) {
    this.ticket = ticket;
    this.catalog = catalog;
    this.allowedIds = allowedIds == null ? null : Set.copyOf(allowedIds);
    this.factoryThread = startThread(ticket, factoryThread);
    this.groups = List.copyOf(groups);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : FactoryUpgradeRecipeChecks.capture(factory, this.factoryThread);
    inputs = List.of();
    outputs = List.of();
    session = null;
    fluidLayout = null;
  }

  private ItemRecipeSearch(RecipeExecutionTicket ticket, ItemRecipeCatalog catalog, Set<String> allowedIds, String factoryThread) {
    this.ticket = ticket;
    this.catalog = catalog;
    this.factoryThread = startThread(ticket, factoryThread);
    factory = this.factoryThread != null && ticket.owner() instanceof UpgradeBusOwner owner ? owner : null;
    factoryScope = factory == null ? null : FactoryUpgradeRecipeChecks.capture(factory, this.factoryThread);
    var inputSlots = new ArrayList<InputSlot>();
    var outputSlots = new ArrayList<ItemSlotRef>();
    var counts = new LinkedHashMap<ItemResourceKey, Long>();
    var spaces = new ArrayList<ItemResourceSnapshot.OutputSlot<ItemResourceKey>>();
    // All core item buses use the same validity rules; reuse one limit map for each
    // slot capacity.
    var limitCache = new HashMap<ItemSlotDomain, Map<ItemResourceKey, Long>>();
    for (var port : ticket.ports()) {
      if (port.kind().resource() != PortKind.Resource.ITEM) {
        continue;
      }
      var stacks = port.items();
      for (int slot = 0; slot < stacks.size(); slot++) {
        var stack = stacks.get(slot);
        var key = stack.isEmpty() ? null : ItemResourceKey.of(stack).withSelector(port.selector());
        var ref = new ItemSlotRef(port.partId(), slot);
        if (port.kind() == PortKind.ITEM_INPUT) {
          if (key != null) {
            counts.merge(key, (long) stack.getCount(), Math::addExact);
            inputSlots.add(new InputSlot(ref, key, stack.getCount()));
          }
        } else {
          int slotLimit = port.slotLimits().get(slot);
          var domain = new ItemSlotDomain(slotLimit, port.selector());
          var limits = limitCache.computeIfAbsent(domain, current -> current.limits(catalog.recipes().outputKeys()));
          outputSlots.add(ref);
          spaces.add(new ItemResourceSnapshot.OutputSlot<>(key, stack.getCount(), slotLimit, limits));
        }
      }
    }
    inputs = List.copyOf(inputSlots);
    outputs = List.copyOf(outputSlots);
    // This label is not an aggregate resource revision or a commit proof. The
    // original ticket owns
    // all per-port stamps.
    var snapshot = new ItemResourceSnapshot<>(ticket.structureRevision(), counts, spaces);
    var fluidCandidates = new HashSet<String>();
    for (var candidate : catalog.recipes().candidates()) {
      if (allowedIds != null && !allowedIds.contains(candidate.id())) {
        continue;
      }
      if (ticket.interfacesMatch(candidate.interfaceInputs())
          && fluidInputsAvailable(
              catalog.fluidInputs(candidate.id()),
              ticket,
              catalog.selectors(candidate.id()).fluidInput())
          && fluidOutputsAvailable(
              catalog.fluidOutputs(candidate.id()),
              ticket,
              catalog.selectors(candidate.id()).fluidOutput())) {
        fluidCandidates.add(candidate.id());
      }
    }
    session = RecipePlanner.prepare(snapshot, catalog.recipes(), fluidCandidates);
    fluidLayout = new FluidOutputLayout(ticket, ticket.level().registryAccess());
  }

  private static String startThread(RecipeExecutionTicket ticket, String threadId) {
    if (threadId == null && ticket.owner() instanceof MachineControllerBlockEntity) {
      return "main";
    }

    return threadId;
  }

  private static boolean fluidInputsAvailable(List<FluidStack> required, RecipeExecutionTicket ticket, String selector) {
    var reserved = new HashMap<String, Integer>();
    for (var demand : required) {
      int remaining = demand.getAmount();
      for (var port : ticket.ports()) {
        if (port.kind() == PortKind.FLUID_INPUT
            && port.selector().equals(selector)
            && FluidStack.isSameFluidSameComponents(port.fluid(), demand)) {
          int already = reserved.getOrDefault(port.partId(), 0);
          int available = Math.max(0, port.fluid().getAmount() - already);
          int amount = Math.min(remaining, available);
          reserved.put(port.partId(), already + amount);
          remaining -= amount;
          if (remaining == 0) {
            break;
          }
        }
      }
      if (remaining > 0) {
        return false;
      }
    }
    return true;
  }

  private static boolean fluidOutputsAvailable(List<FluidStack> required, RecipeExecutionTicket ticket, String selector) {
    var reserved = new HashMap<String, Integer>();
    for (var demand : required) {
      int remaining = demand.getAmount();
      for (var port : ticket.ports()) {
        if (port.kind() != PortKind.FLUID_OUTPUT || !port.selector().equals(selector)) {
          continue;
        }
        var stored = port.fluid();
        if (!stored.isEmpty() && !FluidStack.isSameFluidSameComponents(stored, demand)) {
          continue;
        }
        int already = reserved.getOrDefault(port.partId(), 0);
        int available = Math.max(0, Math.toIntExact(port.capacity()) - stored.getAmount() - already);
        int amount = Math.min(remaining, available);
        reserved.put(port.partId(), already + amount);
        remaining -= amount;
        if (remaining == 0) {
          break;
        }
      }
      if (remaining > 0) {
        return false;
      }
    }
    return true;
  }

  public static Optional<ItemRecipeSearch> begin(MachineControllerBlockEntity controller, ItemRecipeCatalog catalog) {
    if (controller.runningRecipe().isPresent() || controller.recipeStartInProgress()) {
      return Optional.empty();
    }
    return begin((StructureService.StructureSubject) controller, catalog, null);
  }

  public static Optional<ItemRecipeSearch> begin(StructureService.StructureSubject owner, ItemRecipeCatalog catalog, Set<String> allowedIds) {
    return begin(owner, catalog, allowedIds, null);
  }

  private static Optional<ItemRecipeSearch> begin(StructureService.StructureSubject owner, ItemRecipeCatalog catalog, Set<String> allowedIds, String factoryThread) {
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
    var search = groups.size() == 1 ? new ItemRecipeSearch(
        ticket,
        catalog,
        allowedIds,
        factoryThread) : new ItemRecipeSearch(ticket, catalog, allowedIds, factoryThread, groups);
    return catalog.isCurrent() ? Optional.of(search) : Optional.empty();
  }

  /**
   * Native data-pack entry point. Returns no authority while catalogs are
   * unvalidated or stale.
   */
  public static Optional<ItemRecipeSearch> begin(MachineControllerBlockEntity controller) {
    return MachineRecipeService.catalogFor(controller).flatMap(catalog -> begin(controller, catalog));
  }

  public static Optional<ItemRecipeSearch> begin(StructureService.StructureSubject owner, Set<String> allowedIds) {
    return MachineRecipeService.catalogFor(owner).flatMap(catalog -> begin(owner, catalog, allowedIds));
  }

  public static Optional<ItemRecipeSearch> beginFactory(FactoryControllerBlockEntity factory, String threadId, ItemRecipeCatalog catalog, Set<String> allowedIds) {
    Objects.requireNonNull(threadId, "threadId");
    return MachineRecipeService.catalogForFactory(factory, threadId, catalog)
        .flatMap(effective -> begin(factory, effective, allowedIds, threadId));
  }

  public static Optional<ItemRecipeSearch> beginFactory(FactoryControllerBlockEntity factory, String threadId) {
    return MachineRecipeService.catalogFor(factory).flatMap(catalog -> {
      var thread = factory.scheduler().channel(threadId);
      if (thread == null) {
        return Optional.empty();
      }
      var allowed = thread.lockedRecipe() == null ? null : Set.of(thread.lockedRecipe().toString());

      return beginFactory(factory, threadId, catalog, allowed);
    });
  }

  public record Result(RecipePlanner.Status status, Optional<ItemRecipePlan> plan, int workUsed) {
  }

  public Result advance(int budget) {
    ticket.requireServerThread();
    if (budget < 0) {
      throw new IllegalArgumentException("Negative planning budget");
    }
    if (factoryScope != null && !factoryScope.current()) {
      throw new FactoryUpgradeRecipeChecks.StaleException();
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
      if (pendingFactoryPlan == null) {
        var definition = catalog.definition(generic.candidate().id());
        if (definition == null) {
          throw new IllegalStateException("Selected recipe has no native input chance definition");
        }
        long inputChanceSeed = ItemRecipePlan.inputChanceSeed(definition);
        var needed = new HashMap<>(ItemRecipePlan.sampledInputs(generic.candidate(), inputChanceSeed, 1));
        var deductions = new ArrayList<ItemRecipePlan.Deduction>();
        for (var slot : inputs) {
          long amount = Math.min(needed.getOrDefault(slot.key(), 0L), slot.count());
          if (amount == 0) {
            continue;
          }
          deductions.add(new ItemRecipePlan.Deduction(slot.ref(), slot.key(), Math.toIntExact(amount)));
          needed.put(slot.key(), needed.get(slot.key()) - amount);
        }
        if (needed.values().stream().anyMatch(count -> count != 0)) {
          throw new IllegalStateException("Input slot accounting differs from exact-key plan");
        }
        var placements = generic.insertions().stream()
            .map(
                insertion -> new ItemRecipePlan.Placement(
                    outputs.get(insertion.slot()),
                    insertion.key(),
                    Math.toIntExact(insertion.count())))
            .toList();
        pendingFactoryPlan = new ItemRecipePlan(
            ticket,
            catalog,
            generic.candidate(),
            deductions,
            placements,
            inputChanceSeed,
            factoryScope);
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
            session.rejectSelected();
            continue;
          }
          case STALE -> throw new FactoryUpgradeRecipeChecks.StaleException();
          case ERROR -> throw new FactoryUpgradeRecipeChecks.FailedException();
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
          competitive = new HashSet<>();
          for (var candidate : catalog.recipes().candidates()) {
            if (precedes(candidate, groupBest.recipe())
                && (allowedIds == null || allowedIds.contains(candidate.id()))) {
              competitive.add(candidate.id());
            }
          }
          if (competitive.isEmpty()) {
            // Later groups cannot beat a complete, higher-priority match.
            groupCursor = groups.size();
            break;
          }
        }
        // Charge even an empty group, so a long list cannot bypass the tick budget.
        used++;
        completedGroupWork++;
        groupSearch = new ItemRecipeSearch(
            ticket.withInputGroup(groups.get(groupCursor)),
            catalog,
            competitive,
            factoryThread);
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

  private record InputSlot(ItemSlotRef ref, ItemResourceKey key, int count) {
  }
}
