package com.lw.modularmachine.machine.recipe.search;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemSlotRef;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
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

/**
 * Internal server-owned views for one captured search and its input groups.
 * Never reuse this cache for a newly captured ticket or another search attempt.
 */
@ApiStatus.Internal
final class RecipeSearchResources {
  private final RecipeCatalog catalog;
  private final Map<RecipeExecutionTicket.PortSnapshot, List<ItemSlot>> items = new IdentityHashMap<>();
  private final Map<RecipeExecutionTicket.PortSnapshot, StoredFluid> fluids = new IdentityHashMap<>();
  private final Map<RecipeExecutionTicket.PortSnapshot, ChemicalResourceKey> chemicals = new IdentityHashMap<>();
  private final Map<ItemSlotDomain, Map<ItemResourceKey, Long>> limits = new HashMap<>();

  RecipeSearchResources(RecipeCatalog catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  List<ItemSlot> items(RecipeExecutionTicket.PortSnapshot port) {
    if (port.kind().resource() != PortKind.Resource.ITEM) {
      throw new IllegalArgumentException("Not an item port");
    }

    return items.computeIfAbsent(port, this::captureItems);
  }

  private List<ItemSlot> captureItems(RecipeExecutionTicket.PortSnapshot port) {
    var stacks = port.items();
    var result = new ArrayList<ItemSlot>(stacks.size());
    for (int slot = 0; slot < stacks.size(); slot++) {
      var stack = stacks.get(slot);
      var key = stack.isEmpty() ? null : ItemResourceKey.of(stack).withSelector(port.selector());
      var ref = new ItemSlotRef(port.partId(), slot);
      ResourceSnapshot.OutputSlot<ItemResourceKey> output = null;
      if (port.kind() == PortKind.ITEM_OUTPUT) {
        int capacity = port.slotLimits().get(slot);
        var domain = new ItemSlotDomain(capacity, port.selector(), port.bulkItems());
        var accepted = limits.computeIfAbsent(domain, current -> current.limits(catalog.recipes().outputKeys()));
        output = new ResourceSnapshot.OutputSlot<>(key, stack.getCount(), capacity, accepted);
      }
      result.add(new ItemSlot(ref, key, stack.getCount(), output));
    }

    return List.copyOf(result);
  }

  Fluids fluids(RecipeExecutionTicket ticket) {
    ticket.requireServerThread();
    var inputs = new HashMap<FluidRecipeIndex.Key, List<StoredFluid>>();
    var outputs = new HashMap<String, List<StoredFluid>>();
    for (var port : ticket.ports()) {
      if (port.kind().resource() != PortKind.Resource.FLUID) {
        continue;
      }
      var stored = fluids
          .computeIfAbsent(port, current -> new StoredFluid(current.partId(), current.fluid(), current.capacity()));
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
    if (!catalog.hasChemicalInputAnchors()) {
      return Set.of();
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

  record ItemSlot(
      ItemSlotRef ref,
      ItemResourceKey key,
      int count,
      ResourceSnapshot.OutputSlot<ItemResourceKey> output) {
  }

  private static final class StoredFluid {
    private final String partId;
    private final FluidStack stack;
    private final long capacity;

    private StoredFluid(String partId, FluidStack stack, long capacity) {
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

    Set<FluidRecipeIndex.Key> inputKeys() {
      return inputs.keySet();
    }

    boolean inputsAvailable(List<FluidStack> required, String selector) {
      if (required.isEmpty()) {
        return true;
      }
      var reserved = new HashMap<String, Integer>();
      for (var demand : required) {
        int remaining = demand.getAmount();
        var key = new FluidRecipeIndex.Key(demand.getFluid(), selector);
        for (var stored : inputs.getOrDefault(key, List.of())) {
          if (!FluidStack.isSameFluidSameComponents(stored.stack, demand)) {
            continue;
          }
          int already = reserved.getOrDefault(stored.partId, 0);
          int available = Math.max(0, stored.stack.getAmount() - already);
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

    boolean outputsAvailable(List<FluidStack> required, String selector) {
      if (required.isEmpty()) {
        return true;
      }
      var reserved = new HashMap<String, Integer>();
      var ports = outputs.getOrDefault(selector, List.of());
      for (var demand : required) {
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
