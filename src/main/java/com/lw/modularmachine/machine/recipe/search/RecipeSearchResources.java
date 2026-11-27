package com.lw.modularmachine.machine.recipe.search;

import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.machine.recipe.RecipePortSnapshot;
import com.lw.modularmachine.api.component.ResourceType;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemSlotRef;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeItemInputAllocation;
import com.lw.modularmachine.machine.recipe.search.index.RequiredInputQuery;
import com.lw.modularmachine.machine.recipe.search.index.fluid.FluidRecipeIndex;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.Iterator;

/**
 * Internal server-owned views for one captured search and its input groups.
 * Never reuse this cache for a newly captured ticket or another search attempt.
 */
@ApiStatus.Internal
public final class RecipeSearchResources {
  private final RecipeCatalog catalog;
  final RecipeSearch.LockedRestart restart;
  final RecipeItemInputAllocation.MatchingSlots matchingSlots;
  private final Set<ItemResourceKey> targetInputKeys;
  private Map<RecipePortSnapshot, PortInputs> items;
  private Map<RecipePortSnapshot, StoredFluid> fluids;
  private Map<RecipePortSnapshot, ChemicalResourceKey> chemicals;
  private final Map<ItemSlotDomain, ResourceSnapshot.SlotLimit<ItemResourceKey>> limits = new HashMap<>();
  private final List<ItemSlotRef> outputRefs = new ArrayList<>();
  private final List<ResourceSnapshot.OutputSlot<ItemResourceKey>> buildingOutputs = new ArrayList<>();
  private List<ResourceSnapshot.OutputSlot<ItemResourceKey>> outputSpaces;

  RecipeSearchResources(RecipeCatalog catalog) {
    this(catalog, null, null);
  }

  RecipeSearchResources(RecipeCatalog catalog, RecipeCatalog.CandidateFilter filter, RecipeSearch.LockedRestart restart) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.restart = restart;
    var target = filter != null && filter.allowedIds != null && filter.allowedIds.size() == 1 ? catalog
        .searchEntry(filter.allowedIds.iterator().next()) : null;
    matchingSlots = target == null ? null : new RecipeItemInputAllocation.MatchingSlots();
    targetInputKeys = target == null || target.transformsInputs() ? null : target.inputs().input.keySet();
  }

  /**
   * Shared physical item inputs are tallied once; each group merges distinct
   * keys.
   */
  final class GroupItems {
    private final RecipeExecutionTicket ticket;
    private final Map<ItemResourceKey, Long> counts = new HashMap<>();
    private int portIndex;
    private int slotIndex;
    private Iterator<Map.Entry<ItemResourceKey, Long>> portCounts;

    GroupItems(RecipeExecutionTicket ticket) {
      this.ticket = ticket;
    }

    int advance(int budget) {
      int used = 0;
      while (portIndex < ticket.ports().size() && used < budget) {
        var port = ticket.ports().get(portIndex);
        used++;
        if (port.kind() == PortKind.ITEM_INPUT && restart != null) {
          if ((restart.itemKeys() == null || !restart.itemKeys().isEmpty())
              && (restart.singleInput == null || restart.singleInput.selector().equals(port.selector()))
              && slotIndex < port.itemSlotCount()) {
            captureLockedSlot(port, slotIndex++);
            continue;
          }
        } else if (port.kind() == PortKind.ITEM_INPUT) {
          if (items == null) {
            items = new IdentityHashMap<>();
          }
          var input = items.computeIfAbsent(port, ignored -> new PortInputs());
          if (input.next < port.itemSlotCount()) {
            int slot = input.next++;
            var key = port.itemKey(slot);
            if (key != null) {
              long count = port.itemAmount(slot);
              var alias = inputAlias(key);
              if (matchingSlots != null
                  && (targetInputKeys == null
                      || targetInputKeys.contains(key)
                      || alias != null && targetInputKeys.contains(alias))) {
                matchingSlots.add(port, slot, key);
              }
              input.counts.merge(key, count, Math::addExact);
              if (alias != null) {
                input.counts.merge(alias, count, Math::addExact);
              }
            }
            continue;
          }
          if (portCounts == null) {
            portCounts = input.counts.entrySet().iterator();
          }
          if (portCounts.hasNext()) {
            var entry = portCounts.next();
            counts.merge(entry.getKey(), entry.getValue(), Math::addExact);
            continue;
          }
          portCounts = null;
        } else if (port.kind() == PortKind.ITEM_OUTPUT && outputSpaces == null && slotIndex < port.itemSlotCount()) {
          int slot = slotIndex++;
          int capacity = port.slotLimits().get(slot);
          var domain = new ItemSlotDomain(capacity, port.selector(), port.bulkItems());
          var accepted = limits
              .computeIfAbsent(domain, current -> current.outputLimits(catalog.recipes().outputKeys()));
          var output = new ResourceSnapshot.OutputSlot<>(port.itemKey(slot), port.itemAmount(slot), capacity, accepted);
          outputRefs.add(new ItemSlotRef(port.partId(), slot));
          buildingOutputs.add(output);
          continue;
        }
        portIndex++;
        slotIndex = 0;
      }
      if (complete() && outputSpaces == null) {
        // Every group has the same physical output ports, in the same order.
        outputSpaces = List.copyOf(buildingOutputs);
        buildingOutputs.clear();
      }
      return used;
    }

    boolean complete() {
      return portIndex == ticket.ports().size();
    }

    private void captureLockedSlot(RecipePortSnapshot port, int slot) {
      if (restart.singleInput != null) {
        var key = port.matchingItemKey(slot, restart.singleInput);
        if (key != null) {
          long amount = port.itemAmount(slot);
          counts.merge(restart.singleInput, amount, Math::addExact);
          matchingSlots.add(port, slot, key);
        }
        return;
      }
      var key = port.itemKey(slot);
      if (key == null) {
        return;
      }
      var declared = restart.itemKeys();
      var alias = inputAlias(key);
      if (declared != null && alias != null && !declared.contains(alias)) {
        alias = null;
      }
      boolean exact = declared == null || declared.contains(key);
      if (!exact && alias == null) {
        return;
      }
      matchingSlots.add(port, slot, key);
      long amount = port.itemAmount(slot);
      if (exact) {
        counts.merge(key, amount, Math::addExact);
      }
      if (alias != null) {
        counts.merge(alias, amount, Math::addExact);
      }
    }

    private ItemResourceKey inputAlias(ItemResourceKey key) {
      if (catalog.itemInputAliases().isEmpty() && !catalog.hasDynamicInputRecipes()) {
        return null;
      }
      var alias = key.ignoringComponents();
      return catalog.hasDynamicInputRecipes() || catalog.itemInputAliases().contains(alias) ? alias : null;
    }

    ResourceSnapshot<ItemResourceKey> snapshot() {
      return new ResourceSnapshot<>(ticket.structureRevision(), counts, outputSpaces);
    }
  }

  private static final class PortInputs {
    private final Map<ItemResourceKey, Long> counts = new HashMap<>();
    private int next;
  }

  ItemSlotRef outputRef(int index) {
    return outputRefs.get(index);
  }

  Fluids fluids(RecipeExecutionTicket ticket) {
    ticket.requireServerThread();
    if (fluids == null) {
      fluids = new IdentityHashMap<>();
    }
    var inputs = new HashMap<FluidRecipeIndex.Key, List<StoredFluid>>();
    var outputs = new HashMap<String, List<StoredFluid>>();
    for (var port : ticket.ports()) {
      if (port.kind().resource() != ResourceType.FLUID) {
        continue;
      }
      var stored = fluids.computeIfAbsent(
          port,
          current -> new StoredFluid(current.partId(), current.fluid(), current.capacity(), current.fluidAmount()));
      if (port.kind() == PortKind.FLUID_INPUT) {
        if (!stored.stack.isEmpty()) {
          var key = new FluidRecipeIndex.Key(stored.stack.getFluid(), port.selector());
          inputs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(stored);
        }
      } else {
        outputs.computeIfAbsent(port.selector(), ignored -> new ArrayList<>()).add(stored);
      }
    }

    return new Fluids(inputs, outputs);
  }

  Set<ChemicalResourceKey> chemicalInputKeys(RecipeExecutionTicket ticket) {
    ticket.requireServerThread();
    if (chemicals == null) {
      chemicals = new IdentityHashMap<>();
    }
    var keys = new HashSet<ChemicalResourceKey>();
    for (var port : ticket.ports()) {
      if (port.kind() == PortKind.CHEMICAL_INPUT && !port.chemical().isEmpty()) {
        keys.add(
            chemicals.computeIfAbsent(
                port,
                current -> new ChemicalResourceKey(current.chemical().chemical(), current.selector())));
      }
    }

    return Set.copyOf(keys);
  }

  private static final class StoredFluid {
    private final String partId;
    private final FluidStack stack;
    private final long capacity;
    private final long amount;

    private StoredFluid(String partId, FluidStack stack, long capacity, long amount) {
      this.amount = amount;
      this.partId = partId;
      this.stack = stack;
      this.capacity = capacity;
    }
  }

  static final class Fluids {
    private final Map<FluidRecipeIndex.Key, List<StoredFluid>> inputs;
    private final Map<String, List<StoredFluid>> outputs;

    private Fluids(Map<FluidRecipeIndex.Key, List<StoredFluid>> inputs, Map<String, List<StoredFluid>> outputs) {
      this.inputs = freeze(inputs);
      this.outputs = freeze(outputs);
    }

    RequiredInputQuery<FluidRecipeIndex.Key> inputQuery(FluidRecipeIndex index) {
      return new RequiredInputQuery<>(index.inputs(), inputs);
    }

    boolean inputsAvailable(List<FluidRequirement> required, String defaultSelector) {
      if (required.isEmpty()) {
        return true;
      }
      var reserved = new HashMap<String, Long>();
      for (var row : required) {
        var demand = row.stack();
        int remaining = demand.getAmount();
        var key = new FluidRecipeIndex.Key(demand.getFluid(), row.selector(defaultSelector));
        for (var stored : inputs.getOrDefault(key, List.of())) {
          if (!FluidStack.isSameFluidSameComponents(stored.stack, demand)) {
            continue;
          }
          long already = reserved.getOrDefault(stored.partId, 0L);
          long available = Math.max(0, stored.amount - already);
          int amount = (int) Math.min(remaining, available);
          reserved.put(stored.partId, already + amount);
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

    boolean outputsAvailable(List<FluidRequirement> required, String defaultSelector) {
      if (required.isEmpty()) {
        return true;
      }
      var reserved = new HashMap<String, Integer>();
      for (var row : required) {
        var demand = row.stack();
        var ports = outputs.getOrDefault(row.selector(defaultSelector), List.of());
        int remaining = demand.getAmount();
        for (var stored : ports) {
          if (!stored.stack.isEmpty() && !FluidStack.isSameFluidSameComponents(stored.stack, demand)) {
            continue;
          }
          int already = reserved.getOrDefault(stored.partId, 0);
          int available = Math.max(0, Math.toIntExact(stored.capacity) - stored.stack.getAmount() - already);
          int amount = Math.min(remaining, available);
          reserved.put(stored.partId, already + amount);
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

    private static <K> Map<K, List<StoredFluid>> freeze(Map<K, List<StoredFluid>> source) {
      var result = new HashMap<K, List<StoredFluid>>();
      source.forEach((key, value) -> result.put(key, List.copyOf(value)));

      return Map.copyOf(result);
    }
  }
}
