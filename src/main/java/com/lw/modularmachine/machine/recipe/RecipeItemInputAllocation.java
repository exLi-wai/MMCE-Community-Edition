package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.RandomAccess;
import org.jetbrains.annotations.ApiStatus;

/** Full ordinary input reservations, including rows that can sample zero. */
@ApiStatus.Internal
public final class RecipeItemInputAllocation {
  private record PhysicalSlot(Object storage, int slot) {
  }
  private record Slice(RecipePortSnapshot port, int slot, ItemResourceKey key, long count) {
  }
  private record Row(int index, boolean checked, List<Slice> slices) {
  }
  private final Row[] rows;
  private final int instances;
  private final MatchingSlots matchingSlots;

  private RecipeItemInputAllocation(Row[] rows, int instances, MatchingSlots matchingSlots) {
    this.rows = rows;
    this.instances = instances;
    this.matchingSlots = matchingSlots;
  }

  public static boolean required(MachineRecipe recipe) {
    return recipe != null
        && (recipe.inputs().size() - recipe.itemPayments().tools().size() > 1
            || RecipeItemPreparation.hasInputChecker(recipe));
  }

  public static Cursor begin(MachineRecipe recipe, RecipeExecutionTicket ticket, int instances, boolean aggregate) {
    return new Cursor(recipe, null, ticket, instances, aggregate, null);
  }

  public static Cursor begin(RecipeCandidate<ItemResourceKey> candidate, RecipeExecutionTicket ticket, int instances, boolean aggregate) {
    return new Cursor(null, candidate, ticket, instances, aggregate, null);
  }

  /**
   * Physical matches for a locked search and its batch reservations on the same
   * port snapshots.
   */
  public static final class MatchingSlots {
    private record Match(int index, ItemResourceKey key) {
    }
    private final Map<RecipePortSnapshot, List<Match>> byPort = new IdentityHashMap<>();

    public void add(RecipePortSnapshot port, int index, ItemResourceKey key) {
      byPort.computeIfAbsent(port, ignored -> new ArrayList<>()).add(new Match(index, key));
    }
  }

  public static Cursor beginSelected(RecipeCandidate<ItemResourceKey> candidate, MachineRecipe definition, RecipeExecutionTicket ticket, MatchingSlots matches) {
    return new Cursor(definition, candidate, ticket, 1, false, matches);
  }

  static Cursor beginBatch(RecipeCandidate<ItemResourceKey> candidate, MachineRecipe definition, RecipeExecutionTicket ticket, int count, boolean aggregate, RecipeItemInputAllocation selected) {
    return new Cursor(
        definition,
        candidate,
        ticket,
        count,
        aggregate,
        selected == null ? null : selected.matchingSlots);
  }

  public static Optional<RecipeItemInputAllocation> capture(MachineRecipe recipe, RecipeExecutionTicket ticket, int instances, boolean aggregate) {
    var cursor = begin(recipe, ticket, instances, aggregate);
    while (!cursor.complete()) {
      cursor.advance(Integer.MAX_VALUE);
    }
    return cursor.allocation();
  }

  /**
   * Scans each input slot once. Exact and item-only views draw from the same
   * physical balance.
   */
  public static final class Cursor {
    private record Slot(RecipePortSnapshot port, int index, ItemResourceKey key, PhysicalSlot physical) {
    }

    private final MachineRecipe recipe;
    private final RecipeCandidate<ItemResourceKey> candidate;
    private final RecipeExecutionTicket ticket;
    private final int instances;
    private final boolean aggregate;
    private final MatchingSlots matchingSlots;
    private final BitSet checked = new BitSet();
    private final Row[] rows;
    private final ItemResourceKey[] keys;
    private final long[] amounts;
    private final Map<ItemResourceKey, ArrayDeque<Slot>> slots = new HashMap<>();
    private final Map<PhysicalSlot, Long> reserved = new HashMap<>();
    private int callbackIndex;
    private int declarationIndex;
    private int paymentIndex;
    private int portIndex;
    private int slotIndex;
    private boolean hasAlias;
    private int currentRow = -1;
    private long remaining;
    private boolean complete;
    private RecipeItemInputAllocation result;

    private Cursor(MachineRecipe recipe, RecipeCandidate<ItemResourceKey> candidate, RecipeExecutionTicket ticket, int instances, boolean aggregate, MatchingSlots matchingSlots) {
      if (instances < 1) {
        throw new IllegalArgumentException("Positive input reservation count required");
      }
      this.recipe = recipe;
      this.candidate = candidate;
      this.ticket = ticket;
      this.instances = instances;
      this.aggregate = aggregate;
      this.matchingSlots = matchingSlots;
      rows = new Row[recipe != null ? recipe.inputs().size() : candidate.inputs().size()];
      keys = new ItemResourceKey[rows.length];
      amounts = new long[rows.length];
    }

    public boolean complete() {
      return complete;
    }

    public Optional<RecipeItemInputAllocation> allocation() {
      if (!complete) {
        throw new IllegalStateException("Input reservation is still in progress");
      }
      return Optional.ofNullable(result);
    }

    public int advance(int budget) {
      ticket.requireServerThread();
      if (budget < 0) {
        throw new IllegalArgumentException("Negative input reservation budget");
      }
      int used = 0;
      while (!complete && used < budget) {
        used++;
        if (recipe != null && callbackIndex < recipe.scriptOptions().itemCallbacks().size()) {
          var binding = recipe.scriptOptions().itemCallbacks().get(callbackIndex++);
          if (binding.io() == RecipeModifier.Io.INPUT && binding.checker().isPresent()) {
            checked.set(binding.row());
          }
        } else if (declarationIndex < rows.length) {
          prepareDeclaration(declarationIndex++);
        } else if (currentRow >= 0) {
          reserveNextSlice();
        } else if (paymentIndex < rows.length) {
          int row = paymentIndex++;
          if (keys[0] != null && keys[0].ignoresComponents()) {
            row = row + 1 == rows.length ? 0 : row + 1;
          }
          if (rows[row] != null) {
            remaining = amounts[row];
            currentRow = row;
          }
        } else {
          result = new RecipeItemInputAllocation(rows, instances, matchingSlots);
          complete = true;
        }
      }
      return used;
    }

    private void prepareDeclaration(int row) {
      ItemResourceKey key;
      long amount;
      boolean fixed;
      if (recipe == null) {
        var input = candidate.inputs().get(row);
        key = input.key();
        amount = input.count();
        fixed = input.parallelUnaffected();
      } else {
        if (recipe.itemPayments().isTool(row)) {
          return;
        }
        var input = recipe.inputs().get(row);
        key = ItemResourceKey.of(input.ingredientStack(), ticket.level().registryAccess())
            .withSelector(input.selector());
        if (input.ignoresComponents()) {
          key = key.ignoringComponents();
        }
        amount = input.amount();
        fixed = input.parallelUnaffected();
      }
      long count = fixed && aggregate ? 1L : instances;
      if (amount > Long.MAX_VALUE / count) {
        complete = true;
        return;
      }
      hasAlias |= key.ignoresComponents();
      amounts[row] = amount * count;
      keys[row] = key;
      slots.computeIfAbsent(key, ignored -> new ArrayDeque<>());
      rows[row] = new Row(row, checked.get(row), new ArrayList<>());
    }

    private void reserveNextSlice() {
      var candidates = slots.get(keys[currentRow]);
      if (candidates.isEmpty()) {
        if (portIndex == ticket.ports().size()) {
          complete = true;
        } else {
          scanNextSlot();
        }
        return;
      }
      var slot = candidates.getFirst();
      long used = reserved.getOrDefault(slot.physical(), 0L);
      long available = slot.port().itemAmount(slot.index()) - used;
      long take = Math.min(remaining, available);
      if (take > 0) {
        reserved.put(slot.physical(), used + take);
        rows[currentRow].slices().add(new Slice(slot.port(), slot.index(), slot.key(), take));
        remaining -= take;
      }
      if (available <= take) {
        candidates.removeFirst();
      }
      if (remaining == 0) {
        currentRow = -1;
      }
    }

    private void scanNextSlot() {
      var port = ticket.ports().get(portIndex);
      var matches = matchingSlots == null ? null : matchingSlots.byPort.getOrDefault(port, List.of());
      if (port.kind() != PortKind.ITEM_INPUT
          || slotIndex == (matches == null ? port.itemSlotCount() : matches.size())) {
        portIndex++;
        slotIndex = 0;
        return;
      }
      int index;
      ItemResourceKey key;
      if (matches == null) {
        index = slotIndex++;
        key = port.itemKey(index);
      } else {
        var match = matches.get(slotIndex++);
        index = match.index();
        key = match.key();
      }
      if (key == null) {
        return;
      }
      var exact = slots.get(key);
      var aliases = hasAlias ? slots.get(key.ignoringComponents()) : null;
      if (exact != null || aliases != null) {
        var slot = new Slot(port, index, key, new PhysicalSlot(port.physicalIdentity(), index));
        if (exact != null) {
          exact.addLast(slot);
        }
        if (aliases != null) {
          aliases.addLast(slot);
        }
      }
    }
  }

  boolean matchesInstances(int count) {
    return instances == count;
  }

  void appendCheckerSlices(List<RecipeItemCallbackService.InputItem> target) {
    for (var row : rows) {
      if (row != null && row.checked()) {
        for (var slice : row.slices()) {
          target.add(
              new RecipeItemCallbackService.InputItem(row.index(), slice.port().copyItem(slice.slot()), slice.count()));
        }
      }
    }
  }

  public DeductionCursor beginDeductions(long[] sampled) {
    return new DeductionCursor(sampled);
  }

  List<RecipeStartPlan.Deduction> deductions(long[] sampled) {
    var cursor = beginDeductions(sampled);
    while (!cursor.complete()) {
      cursor.advance(Integer.MAX_VALUE);
    }
    return cursor.deductions();
  }

  /**
   * Converts one reserved slice or visits one declaration row per unit of work.
   */
  public final class DeductionCursor {
    private final long[] sampled;
    private final Map<ItemSlotRef, Integer> slotIndices = new HashMap<>();
    private final ArrayList<RecipeStartPlan.Deduction> debits = new ArrayList<>();
    private int rowIndex;
    private int ordinal;
    private int sliceIndex;
    private long remaining;
    private List<RecipeStartPlan.Deduction> result;

    private DeductionCursor(long[] sampled) {
      if (sampled.length > rows.length) {
        throw new IllegalArgumentException("Too many sampled input rows");
      }
      this.sampled = sampled.clone();
    }

    public boolean complete() {
      return result != null;
    }

    public List<RecipeStartPlan.Deduction> deductions() {
      if (!complete()) {
        throw new IllegalStateException("Input deductions are still in progress");
      }
      return result;
    }

    public int advance(int budget) {
      if (budget < 0) {
        throw new IllegalArgumentException("Negative input deduction budget");
      }
      int used = 0;
      while (!complete() && used < budget) {
        used++;
        if (remaining > 0) {
          takeNextSlice();
        } else if (rowIndex < rows.length) {
          if (rows[rowIndex++] != null) {
            if (ordinal == sampled.length || sampled[ordinal] < 0) {
              throw new IllegalArgumentException("Invalid sampled input row");
            }
            remaining = sampled[ordinal++];
            sliceIndex = 0;
          }
        } else {
          if (ordinal != sampled.length) {
            throw new IllegalArgumentException("Too many sampled input rows");
          }
          result = new FrozenDeductions(debits);
        }
      }
      return used;
    }

    private void takeNextSlice() {
      var slices = rows[rowIndex - 1].slices();
      if (sliceIndex == slices.size()) {
        throw new IllegalArgumentException("Sampled input exceeds its reserved row");
      }
      var slice = slices.get(sliceIndex++);
      long take = Math.min(remaining, slice.count());
      var slot = new ItemSlotRef(slice.port().partId(), slice.slot());
      var index = slotIndices.putIfAbsent(slot, debits.size());
      if (index == null) {
        debits.add(new RecipeStartPlan.Deduction(slot, slice.key(), take));
      } else {
        var previous = debits.get(index);
        debits.set(index, new RecipeStartPlan.Deduction(slot, slice.key(), Math.addExact(previous.count(), take)));
      }
      remaining -= take;
    }
  }

  /**
   * Completed cursors transfer their private lists directly to the start plan.
   */
  static List<RecipeStartPlan.Deduction> freezeDeductions(List<RecipeStartPlan.Deduction> deductions) {
    return deductions instanceof FrozenDeductions ? deductions : List.copyOf(deductions);
  }

  private static final class FrozenDeductions extends AbstractList<RecipeStartPlan.Deduction> implements RandomAccess {
    private final List<RecipeStartPlan.Deduction> entries;

    private FrozenDeductions(List<RecipeStartPlan.Deduction> entries) {
      this.entries = entries;
    }

    @Override
    public RecipeStartPlan.Deduction get(int index) {
      return entries.get(index);
    }

    @Override
    public int size() {
      return entries.size();
    }
  }
}
