package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.machine.structure.AsyncStructureWork;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials;
import com.lw.modularmachine.util.minecraft.placement.PlacementMaterials.Choices;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal main-thread capability capture followed by detached material
 * matching.
 */
@ApiStatus.Internal
final class AssemblyMaterialPlan {
  record Resource(Item item, FluidStack fluid) {
    boolean accepts(Choices choices) {
      return item != null ? choices.items().contains(item) : choices.fluids().contains(fluid.getFluid());
    }
  }

  record Shortage(Choices choices, long positions, FluidStack fluid, long millibuckets) {
  }

  private record Supply(Resource resource, long quantity) {
  }

  record Replacement(Map<Choices, LinkedHashMap<Resource, Long>> assignments, Resource resource) {
  }

  record Advance(Result result, int work) {
  }

  static final class Result {
    final Map<Choices, LinkedHashMap<Resource, Long>> assignments;
    final List<Shortage> shortages;
    final boolean incomplete;
    final Set<Item> reservedBlocks;
    private final List<Resource> resources;
    private final long[] remaining;

    Result(Map<Choices, LinkedHashMap<Resource, Long>> assignments, List<Shortage> shortages, boolean incomplete) {
      this(assignments, shortages, incomplete, List.of());
    }

    Result(Map<Choices, LinkedHashMap<Resource, Long>> assignments, List<Shortage> shortages, boolean incomplete, List<Supply> supplies) {
      this.assignments = assignments;
      this.shortages = List.copyOf(shortages);
      this.incomplete = incomplete;
      reservedBlocks = assignments.keySet().stream().flatMap(choices -> choices.items().stream())
          .collect(java.util.stream.Collectors.toUnmodifiableSet());
      resources = supplies.stream().map(Supply::resource).toList();
      remaining = supplies.stream()
          .mapToLong(supply -> supply.quantity() / (supply.resource().item() == null ? FluidType.BUCKET_VOLUME : 1))
          .toArray();
    }

    Resource next(Choices choices) {
      var assigned = assignments.get(choices);
      return assigned == null || assigned.isEmpty() ? null : assigned.keySet().iterator().next();
    }

    void used(Choices choices, Resource resource) {
      var assigned = assignments.get(choices);
      if (assigned != null && resource != null) {
        assigned.computeIfPresent(resource, (key, count) -> count <= 1 ? null : count - 1);
        remaining[resources.indexOf(resource)]--;
      }
    }

    CompletableFuture<Replacement> retry(Choices choices, Set<Resource> rejected) {
      var groups = List.copyOf(assignments.keySet());
      var counts = groups.stream()
          .mapToLong(group -> assignments.get(group).values().stream().mapToLong(Long::longValue).sum()).toArray();
      var capacities = remaining.clone();
      var excluded = rejected.stream().map(resources::indexOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
      var capturedResources = resources;
      int target = groups.indexOf(choices);
      return AsyncStructureWork.submit(() -> {
        var demands = new ArrayList<MaterialAllocation.Demand>();
        for (int group = 0; group < groups.size(); group++) {
          var allowed = new ArrayList<Integer>();
          for (int resource = 0; resource < capturedResources.size(); resource++) {
            if (capturedResources.get(resource).accepts(groups.get(group))) {
              allowed.add(resource);
            }
          }
          demands.add(new MaterialAllocation.Demand(counts[group], allowed));
        }
        var replacement = MaterialAllocation.reassign(capacities, demands, target, excluded);
        if (replacement == null) {
          return null;
        }
        var result = new HashMap<Choices, LinkedHashMap<Resource, Long>>();
        for (int group = 0; group < groups.size(); group++) {
          var assigned = new LinkedHashMap<Resource, Long>();
          replacement.assignments().get(group)
              .forEach((resource, count) -> assigned.put(capturedResources.get(resource), count));
          result.put(groups.get(group), assigned);
        }
        return new Replacement(result, capturedResources.get(replacement.resource()));
      });
    }

    void install(Replacement replacement) {
      assignments.clear();
      assignments.putAll(replacement.assignments());
    }
  }

  private final Map<Choices, Integer> requested;
  private final Iterator<Choices> requestedChoices;
  private final Set<Item> requestedItems = new HashSet<>();
  private final Set<Fluid> requestedFluids = new HashSet<>();
  private final List<ItemStack> inventory;
  private final List<Supply> supplies = new ArrayList<>();
  private final List<Supply> slotSupplies = new ArrayList<>();
  private final long maximumFluid;
  private int slot, identity;
  private Choices indexingChoices;
  private int itemCandidate, fluidCandidate;
  private boolean choicesReady;
  private IFluidHandlerItem handler;
  private List<FluidStack> identities = List.of();
  private final List<FluidStack> tankIdentities = new ArrayList<>();
  private int tank, tankCount;
  private boolean enumeratingTanks;
  private boolean incomplete;
  private long extracted;
  private CompletableFuture<Result> future;

  AssemblyMaterialPlan(Map<Choices, Integer> requested, ServerPlayer player) {
    this.requested = Collections.unmodifiableMap(new LinkedHashMap<>(requested));
    requestedChoices = this.requested.keySet().iterator();
    inventory = player.getInventory().items.stream().map(ItemStack::copy).toList();
    maximumFluid = requested.values().stream().mapToLong(Integer::longValue).sum() * FluidType.BUCKET_VOLUME;
  }

  boolean inventoryMatches(ServerPlayer player) {
    var items = player.getInventory().items;
    if (items.size() != inventory.size()) {
      return false;
    }
    for (int index = 0; index < items.size(); index++) {
      if (!ItemStack.matches(items.get(index), inventory.get(index))) {
        return false;
      }
    }
    return true;
  }

  Result advance() {
    return advance(System.nanoTime() + 1_000_000L, 64).result();
  }

  boolean waitingForAllocation() {
    return choicesReady && slot >= inventory.size() && (future == null || !future.isDone());
  }

  /**
   * Capture shares its caller's deadline and operation quota; worker polling
   * costs no capture work.
   */
  Advance advance(long deadline, int workBudget) {
    if (requested.isEmpty()) {
      return new Advance(new Result(Map.of(), List.of(), false), 0);
    }
    int work = 0;
    while (!choicesReady && work < workBudget && System.nanoTime() < deadline) {
      captureRequested();
      work++;
    }
    while (choicesReady && slot < inventory.size() && work < workBudget && System.nanoTime() < deadline) {
      try {
        capture();
      } catch (RuntimeException failure) {
        // Unreliable item capabilities are not counted as spendable inventory.
        incomplete = true;
        slotSupplies.clear();
        nextSlot();
      }
      work++;
    }
    if (!choicesReady || slot < inventory.size()) {
      return new Advance(null, work);
    }
    if (future == null && work < workBudget && System.nanoTime() < deadline) {
      var demands = requested;
      var captured = List.copyOf(supplies);
      boolean limited = incomplete;
      future = AsyncStructureWork.submit(() -> solve(demands, captured, limited));
      work++;
    }

    return new Advance(future != null && future.isDone() ? future.join() : null, work);
  }

  private void captureRequested() {
    if (indexingChoices == null) {
      if (!requestedChoices.hasNext()) {
        choicesReady = true;
        return;
      }
      indexingChoices = requestedChoices.next();
      itemCandidate = 0;
      fluidCandidate = 0;
    }
    if (itemCandidate < indexingChoices.items().size()) {
      requestedItems.add(indexingChoices.items().get(itemCandidate++));
    } else if (fluidCandidate < indexingChoices.fluids().size()) {
      requestedFluids.add(indexingChoices.fluids().get(fluidCandidate++));
    } else {
      indexingChoices = null;
    }
  }

  void cancel() {
    if (future != null) {
      future.cancel(false);
    }
    handler = null;
  }

  private void nextSlot() {
    for (var supply : slotSupplies) {
      merge(supplies, supply.resource(), supply.quantity());
    }
    slotSupplies.clear();
    slot++;
    handler = null;
    identities = List.of();
    tankIdentities.clear();
    tank = 0;
    tankCount = 0;
    enumeratingTanks = false;
    identity = 0;
    extracted = 0;
  }

  private void capture() {
    var stack = inventory.get(slot);
    if (stack.isEmpty() || requested.isEmpty()) {
      nextSlot();
      return;
    }
    if (handler == null) {
      // A physical stack gets one role. Plain placeable blocks are reserved as
      // blocks when requested, never counted again through their fluid capability.
      if (stack.getComponentsPatch().isEmpty()
          && PlacementMaterials.automaticItem(stack.getItem())
          && requestedItems.contains(stack.getItem())) {
        add(new Resource(stack.getItem(), FluidStack.EMPTY), stack.getCount());
        nextSlot();
        return;
      }
      handler = stack.copyWithCount(1).getCapability(Capabilities.FluidHandler.ITEM);
      if (handler == null) {
        nextSlot();
        return;
      }
      tankCount = handler.getTanks();
      if (tankCount < 0 || tankCount > 64) {
        incomplete = true;
        nextSlot();
        return;
      }
      tank = 0;
      enumeratingTanks = true;
      return;
    }
    if (enumeratingTanks) {
      if (tank < tankCount) {
        var fluid = handler.getFluidInTank(tank++).copy();
        if (!fluid.isEmpty()
            && requestedFluids.contains(fluid.getFluid())
            && tankIdentities.stream().noneMatch(other -> FluidStack.isSameFluidSameComponents(other, fluid))) {
          tankIdentities.add(fluid.copyWithAmount(1));
        }
        return;
      }
      identities = List.copyOf(tankIdentities);
      tankIdentities.clear();
      enumeratingTanks = false;
      return;
    }
    if (identity == identities.size()) {
      nextSlot();
      return;
    }
    var resource = identities.get(identity);
    if (extracted >= maximumFluid) {
      identity++;
      extracted = 0;
      return;
    }
    var wanted = resource.copyWithAmount((int) Math.min(Integer.MAX_VALUE, maximumFluid - extracted));
    var simulated = handler.drain(wanted, FluidAction.SIMULATE).copy();
    if (simulated.isEmpty() && wanted.getAmount() > FluidType.BUCKET_VOLUME) {
      wanted.setAmount(FluidType.BUCKET_VOLUME);
      simulated = handler.drain(wanted, FluidAction.SIMULATE).copy();
    }
    if (simulated.isEmpty()) {
      identity++;
      extracted = 0;
      return;
    }
    if (simulated.getAmount() > wanted.getAmount() || !FluidStack.isSameFluidSameComponents(resource, simulated)) {
      throw new IllegalStateException("Invalid simulated material drain");
    }
    var drained = handler.drain(simulated.copy(), FluidAction.EXECUTE);
    if (!FluidStack.matches(simulated, drained)) {
      throw new IllegalStateException("Material drain differs from simulation");
    }
    var container = handler.getContainer();
    if (!container.isEmpty() && container.getCount() != 1) {
      throw new IllegalStateException("Invalid material result container");
    }
    add(new Resource(null, resource), Math.min(maximumFluid, (long) drained.getAmount() * stack.getCount()));
    extracted += drained.getAmount();
    // Respect handlers which replace their container after each extraction.
    handler = container.copy().getCapability(Capabilities.FluidHandler.ITEM);
    if (handler == null) {
      nextSlot();
    }
  }

  private void add(Resource resource, long quantity) {
    merge(slotSupplies, resource, quantity);
  }

  private void merge(List<Supply> target, Resource resource, long quantity) {
    for (int index = 0; index < target.size(); index++) {
      var old = target.get(index);
      if (resource.item() != null ? resource.item() == old.resource().item() : old.resource().item() == null
          && FluidStack.isSameFluidSameComponents(resource.fluid(), old.resource().fluid())) {
        long total = old.quantity() + quantity;
        target.set(index, new Supply(old.resource(), resource.item() == null ? Math.min(maximumFluid, total) : total));
        return;
      }
    }
    target.add(new Supply(resource, quantity));
  }

  private static Result solve(Map<Choices, Integer> requested, List<Supply> supplies, boolean incomplete) {
    var groups = List.copyOf(requested.keySet());
    var demands = new ArrayList<MaterialAllocation.Demand>();
    var quantities = new long[supplies.size()];
    var units = new int[supplies.size()];
    for (int resource = 0; resource < supplies.size(); resource++) {
      var supply = supplies.get(resource);
      quantities[resource] = supply.quantity();
      units[resource] = supply.resource().item() == null ? FluidType.BUCKET_VOLUME : 1;
    }
    for (var choices : groups) {
      var allowed = new ArrayList<Integer>();
      for (int resource = 0; resource < supplies.size(); resource++) {
        if (supplies.get(resource).resource().accepts(choices)) {
          allowed.add(resource);
        }
      }
      demands.add(new MaterialAllocation.Demand(requested.get(choices), allowed));
    }
    var allocation = MaterialAllocation.plan(quantities, units, demands);
    var assignments = new HashMap<Choices, LinkedHashMap<Resource, Long>>();
    var shortages = new ArrayList<Shortage>();
    for (int group = 0; group < groups.size(); group++) {
      var assigned = new LinkedHashMap<Resource, Long>();
      for (var entry : allocation.full().get(group).entrySet()) {
        assigned.put(supplies.get(entry.getKey()).resource(), entry.getValue());
      }
      assignments.put(groups.get(group), assigned);
      for (var entry : allocation.partial().get(group).entrySet()) {
        var supply = supplies.get(entry.getKey());
        shortages.add(
            new Shortage(
                groups.get(group),
                1,
                supply.resource().fluid(),
                FluidType.BUCKET_VOLUME - supply.quantity() % FluidType.BUCKET_VOLUME));
      }
      if (allocation.remaining()[group] > 0) {
        shortages.add(
            new Shortage(
                groups.get(group),
                allocation.remaining()[group],
                FluidStack.EMPTY,
                allocation.remaining()[group] * FluidType.BUCKET_VOLUME));
      }
    }
    return new Result(assignments, shortages, incomplete, supplies);
  }
}
