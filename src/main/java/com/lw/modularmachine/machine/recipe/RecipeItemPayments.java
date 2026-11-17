package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.machine.PortKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal server-thread tool/fuel proofs and detached startup replacements.
 * Full ordinary demands reserve their slices before tool/fuel payment, even for
 * probability zero. The caller owns final revalidation and inventory leases;
 * item hooks must finish before those leases are acquired.
 */
@ApiStatus.Internal
public final class RecipeItemPayments {
  private static final long TOOL_SEED = 0x544F4F4C00000000L;
  private static final long FUEL_SEED = 0x4655454C00000000L;

  private RecipeItemPayments() {
  }

  private record PhysicalSlot(Object storage, int slot) {
  }

  /**
   * Per-invocation maximum-demand ledger. Its detached remaining balance is
   * independent of the sampled replacements installed in the caller's map.
   */
  private static final class Slot {
    private final RecipePortSnapshot port;
    private final int index;
    private ItemStack remainingProof;
    private boolean toolReserved;
    private boolean fuelContainer;

    private Slot(RecipePortSnapshot port, int index, ItemStack remainingProof) {
      this.port = port;
      this.index = index;
      this.remainingProof = remainingProof;
    }
  }

  private enum ParallelMode {
    AGGREGATED,
    INDEPENDENT_RUNS
  }

  private record Batch(int instances, ParallelMode mode) {
    int attempts(boolean parallelUnaffected) {
      return mode == ParallelMode.AGGREGATED && parallelUnaffected ? 1 : instances;
    }

    long quantity(long amount, boolean parallelUnaffected) {
      return Math.multiplyExact(amount, attempts(parallelUnaffected));
    }
  }

  private record DebitTarget(long seed, Map<String, NonNullList<ItemStack>> replacements) {
  }

  private record ToolOperation(Slot slot, long amount, int durability) {
  }

  private record FuelOperation(Slot slot, long count, int burnTime) {
  }

  /** Tracks whole-fuel rounding separately for each independent paid run. */
  static final class FuelDemand {
    private final long perRun;
    private int runs;
    private long remaining;

    FuelDemand(long perRun, int runs) {
      if (perRun < 1 || runs < 0) {
        throw new IllegalArgumentException("Invalid fuel demand");
      }
      this.perRun = perRun;
      this.runs = runs;
      remaining = runs == 0 ? 0 : perRun;
    }

    int consume(int available, int burnTime) {
      if (available < 0 || burnTime < 1) {
        throw new IllegalArgumentException("Invalid fuel supply");
      }
      if (paid() || available == 0) {
        return 0;
      }
      long first = 1 + (remaining - 1) / burnTime;
      if (first > available) {
        remaining -= Math.multiplyExact((long) available, burnTime);
        return available;
      }
      int taken = Math.toIntExact(first);
      runs--;
      if (runs == 0) {
        remaining = 0;
        return taken;
      }
      long perCompleteRun = 1 + (perRun - 1) / burnTime;
      int completed = (int) Math.min(runs, (available - taken) / perCompleteRun);
      taken += Math.toIntExact(Math.multiplyExact((long) completed, perCompleteRun));
      runs -= completed;
      if (runs == 0) {
        remaining = 0;
        return taken;
      }
      remaining = perRun - Math.multiplyExact((long) available - taken, burnTime);

      return available;
    }

    boolean paid() {
      return runs == 0;
    }
  }

  public static boolean available(RecipeExecutionTicket ticket, MachineRecipe recipe, int instances, boolean aggregate) {
    return recipe == null
        || recipe.itemPayments().isEmpty()
        || prepare(
            ticket,
            recipe,
            new Batch(instances, aggregate ? ParallelMode.AGGREGATED : ParallelMode.INDEPENDENT_RUNS),
            Optional.empty()).isPresent();
  }

  static boolean apply(RecipeExecutionTicket ticket, MachineRecipe recipe, int instances, boolean aggregate, long seed, Map<String, NonNullList<ItemStack>> replacements) {
    return recipe.itemPayments().isEmpty()
        || prepare(
            ticket,
            recipe,
            new Batch(instances, aggregate ? ParallelMode.AGGREGATED : ParallelMode.INDEPENDENT_RUNS),
            Optional.of(new DebitTarget(seed, replacements))).isPresent();
  }

  static int limit(RecipeExecutionTicket ticket, MachineRecipe recipe, int limit, boolean aggregate) {
    if (recipe == null || recipe.itemPayments().isEmpty()) {
      return limit;
    }
    int low = 0, high = limit;
    while (low < high) {
      int middle = (int) (((long) low + high + 1) / 2);
      if (available(ticket, recipe, middle, aggregate)) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  public static Optional<List<RecipeItemCallbackService.InputItem>> toolSlices(RecipeExecutionTicket ticket, MachineRecipe recipe, int instances, boolean aggregate) {
    return prepare(
        ticket,
        recipe,
        new Batch(instances, aggregate ? ParallelMode.AGGREGATED : ParallelMode.INDEPENDENT_RUNS),
        Optional.empty());
  }

  private static Optional<List<RecipeItemCallbackService.InputItem>> prepare(RecipeExecutionTicket ticket, MachineRecipe recipe, Batch batch, Optional<DebitTarget> target) {
    ticket.requireServerThread();
    try {
      var slots = captureSlots(ticket);
      var slices = new ArrayList<RecipeItemCallbackService.InputItem>();
      if (!reserveOrdinaryInputs(recipe, batch, slots)
          || !prepareTools(recipe, batch, slots, target, slices)
          || !prepareFuels(recipe.itemPayments().fuels(), batch, slots, target)) {
        return Optional.empty();
      }

      return Optional.of(List.copyOf(slices));
    } catch (ArithmeticException overflow) {
      return Optional.empty();
    }
  }

  private static List<Slot> captureSlots(RecipeExecutionTicket ticket) {
    var slots = new ArrayList<Slot>();
    var seen = new HashSet<PhysicalSlot>();
    for (var port : ticket.ports()) {
      if (port.kind() != PortKind.ITEM_INPUT) {
        continue;
      }
      var stacks = port.items();
      for (int index = 0; index < stacks.size(); index++) {
        if (seen.add(new PhysicalSlot(port.physicalIdentity(), index))) {
          slots.add(new Slot(port, index, stacks.get(index)));
        }
      }
    }

    return slots;
  }

  private static boolean reserveOrdinaryInputs(MachineRecipe recipe, Batch batch, List<Slot> slots) {
    for (int row = 0; row < recipe.inputs().size(); row++) {
      if (recipe.itemPayments().isTool(row)) {
        continue;
      }
      var input = recipe.inputs().get(row);
      long remaining = batch.quantity(input.amount(), input.parallelUnaffected());
      var prototype = input.ingredientStack();
      for (var slot : slots) {
        if (!slot.port.selector().equals(input.selector())
            || slot.remainingProof.isEmpty()
            || !(input.ignoresComponents() ? slot.remainingProof.is(prototype.getItem()) : ItemStack
                .isSameItemSameComponents(prototype, slot.remainingProof))) {
          continue;
        }
        long count = Math.min(remaining, slot.remainingProof.getCount());
        slot.remainingProof = slot.remainingProof
            .copyWithCount(Math.toIntExact(slot.remainingProof.getCount() - count));
        remaining -= count;
        if (remaining == 0) {
          break;
        }
      }
      if (remaining != 0) {
        return false;
      }
    }

    return true;
  }

  private static boolean prepareTools(MachineRecipe recipe, Batch batch, List<Slot> slots, Optional<DebitTarget> target, List<RecipeItemCallbackService.InputItem> slices) {
    for (var tool : recipe.itemPayments().tools()) {
      var input = recipe.inputs().get(tool.row());
      long required = batch.quantity(input.amount(), input.parallelUnaffected());
      var operations = reserveTool(input, tool, required, slots, slices);
      if (operations.isEmpty()) {
        return false;
      }
      if (target.isPresent()) {
        var debit = target.orElseThrow();
        long count = sampledQuantity(
            input.amount(),
            batch.attempts(input.parallelUnaffected()),
            input.chance(),
            debit.seed() ^ TOOL_SEED ^ tool.row());
        applyTools(operations.orElseThrow(), count, debit.replacements());
      }
    }

    return true;
  }

  private static Optional<List<ToolOperation>> reserveTool(MachineRecipe.ItemAmount input, ItemPaymentData.Tool tool, long required, List<Slot> slots, List<RecipeItemCallbackService.InputItem> slices) {
    var operations = new ArrayList<ToolOperation>();
    var prototype = input.ingredientStack();
    for (var slot : slots) {
      if (!slot.port.selector().equals(input.selector())
          || slot.port.patternInput()
          || !toolMatches(prototype, slot.remainingProof)) {
        continue;
      }
      long take = Math.min(required, toolCapacity(slot.remainingProof, tool.durability()));
      if (take > 0) {
        slot.toolReserved = true;
        slices.add(new RecipeItemCallbackService.InputItem(tool.row(), slot.remainingProof, take));
        operations.add(new ToolOperation(slot, take, tool.durability()));
        slot.remainingProof = damaged(slot.remainingProof, take, tool.durability());
        required -= take;
      }
      if (required == 0) {
        break;
      }
    }

    return required == 0 ? Optional.of(operations) : Optional.empty();
  }

  private static boolean prepareFuels(List<ItemPaymentData.Fuel> fuels, Batch batch, List<Slot> slots, Optional<DebitTarget> target) {
    for (int row = 0; row < fuels.size(); row++) {
      var fuel = fuels.get(row);
      int attempts = batch.attempts(fuel.parallelUnaffected());
      var operations = reserveFuel(fuel, fuelDemand(fuel, batch, attempts), slots);
      if (operations.isEmpty()) {
        return false;
      }
      if (target.isPresent()) {
        var debit = target.orElseThrow();
        int successful = Math.toIntExact(sampledQuantity(1, attempts, fuel.chance(), debit.seed() ^ FUEL_SEED ^ row));
        applyFuels(operations.orElseThrow(), fuelDemand(fuel, batch, successful), debit.replacements());
      }
    }

    return true;
  }

  private static FuelDemand fuelDemand(ItemPaymentData.Fuel fuel, Batch batch, int attempts) {
    if (batch.mode() == ParallelMode.INDEPENDENT_RUNS || attempts == 0) {
      return new FuelDemand(fuel.burnTime(), attempts);
    }

    return new FuelDemand(Math.multiplyExact(fuel.burnTime(), attempts), 1);
  }

  private static Optional<List<FuelOperation>> reserveFuel(ItemPaymentData.Fuel fuel, FuelDemand demand, List<Slot> slots) {
    var operations = new ArrayList<FuelOperation>();
    for (var slot : slots) {
      if (slot.remainingProof.isEmpty()
          || slot.port.patternInput()
          || slot.toolReserved
          || slot.fuelContainer
          || !slot.port.selector().equals(fuel.selector())) {
        continue;
      }
      int burn = slot.remainingProof.getBurnTime(RecipeType.SMELTING);
      if (burn <= 0) {
        continue;
      }
      var remainder = slot.remainingProof.getCraftingRemainingItem();
      if (!remainder.isEmpty() && slot.remainingProof.getCount() != 1) {
        continue;
      }
      int take = demand.consume(slot.remainingProof.getCount(), burn);
      if (take == 0) {
        continue;
      }
      slot.fuelContainer = !remainder.isEmpty();
      operations.add(new FuelOperation(slot, take, burn));
      slot.remainingProof = consumedFuel(slot.remainingProof, take);
      if (demand.paid()) {
        break;
      }
    }

    return demand.paid() ? Optional.of(operations) : Optional.empty();
  }

  private static long sampledQuantity(long amount, int draws, double chance, long seed) {
    if (chance == 1) {
      return Math.multiplyExact(amount, draws);
    }
    if (chance == 0) {
      return 0;
    }
    var random = RandomSource.create(seed);
    long count = 0;
    for (int index = 0; index < draws; index++) {
      if (random.nextDouble() < chance) {
        count = Math.addExact(count, amount);
      }
    }

    return count;
  }

  private static void applyTools(List<ToolOperation> operations, long remaining, Map<String, NonNullList<ItemStack>> replacements) {
    for (var operation : operations) {
      if (remaining == 0) {
        break;
      }
      var slot = operation.slot();
      var contents = replacementContents(slot, replacements);
      long take = Math.min(operation.amount(), remaining);
      contents.set(slot.index, damaged(contents.get(slot.index), take, operation.durability()));
      remaining -= take;
    }
    if (remaining != 0) {
      throw new IllegalStateException("Tool payment differs from its full physical proof");
    }
  }

  private static void applyFuels(List<FuelOperation> operations, FuelDemand demand, Map<String, NonNullList<ItemStack>> replacements) {
    for (var operation : operations) {
      if (demand.paid()) {
        break;
      }
      var slot = operation.slot();
      var contents = replacementContents(slot, replacements);
      int take = demand.consume(Math.toIntExact(operation.count()), operation.burnTime());
      contents.set(slot.index, consumedFuel(contents.get(slot.index), take));
    }
    if (!demand.paid()) {
      throw new IllegalStateException("Fuel payment differs from its full physical proof");
    }
  }

  private static NonNullList<ItemStack> replacementContents(Slot slot, Map<String, NonNullList<ItemStack>> replacements) {
    return replacements.computeIfAbsent(slot.port.partId(), ignored -> {
      var values = slot.port.items();
      var copy = NonNullList.withSize(values.size(), ItemStack.EMPTY);
      for (int index = 0; index < values.size(); index++) {
        copy.set(index, values.get(index));
      }

      return copy;
    });
  }

  private static boolean toolMatches(ItemStack prototype, ItemStack actual) {
    if (actual.isEmpty() || !actual.isDamageableItem() || !actual.is(prototype.getItem())) {
      return false;
    }
    var expected = prototype.copy();
    var candidate = actual.copy();
    expected.remove(DataComponents.DAMAGE);
    candidate.remove(DataComponents.DAMAGE);
    return ItemStack.isSameItemSameComponents(expected, candidate);
  }

  private static long toolCapacity(ItemStack stack, int durability) {
    if (stack.isEmpty() || !stack.isDamageableItem() || durability <= 0) {
      return 0;
    }
    return toolCapacity(stack.getCount(), stack.getMaxDamage(), stack.getDamageValue(), durability);
  }

  static long toolCapacity(int count, int maximum, int damage, int durability) {
    if (count < 1 || maximum < 1 || damage < 0 || damage >= maximum || durability < 1) {
      throw new IllegalArgumentException("Invalid durable tool state");
    }
    long first = operationsUntilBreak(maximum, damage, durability);
    long rest = operationsUntilBreak(maximum, 0, durability);
    return Math.addExact(first, Math.multiplyExact(count - 1L, rest));
  }

  record ToolDamage(int count, int damage) {
  }

  static ToolDamage toolDamage(int count, int maximum, int damage, long operations, int durability) {
    long capacity = toolCapacity(count, maximum, damage, durability);
    if (operations < 0 || operations > capacity) {
      throw new IllegalArgumentException("Tool payment exceeds available durability");
    }
    long first = operationsUntilBreak(maximum, damage, durability);
    if (operations < first) {
      return new ToolDamage(count, Math.toIntExact(damage + Math.multiplyExact(operations, durability)));
    }
    operations -= first;
    long perTool = operationsUntilBreak(maximum, 0, durability);
    int remaining = Math.toIntExact(count - 1L - operations / perTool);
    return new ToolDamage(
        remaining,
        remaining == 0 ? 0 : Math.toIntExact(Math.multiplyExact(operations % perTool, durability)));
  }

  private static long operationsUntilBreak(int maximum, int damage, int durability) {
    return 1 + ((long) maximum - damage - 1) / durability;
  }

  private static ItemStack damaged(ItemStack before, long operations, int durability) {
    var damaged = toolDamage(before.getCount(), before.getMaxDamage(), before.getDamageValue(), operations, durability);
    var result = before.copy();
    result.setCount(damaged.count());
    if (!result.isEmpty()) {
      result.setDamageValue(damaged.damage());
    }
    return result;
  }

  private static ItemStack consumedFuel(ItemStack before, long count) {
    if (count < 1 || count > before.getCount()) {
      throw new IllegalArgumentException("Fuel payment exceeds its physical stack");
    }
    var remainder = before.getCraftingRemainingItem();
    if (!remainder.isEmpty()) {
      if (before.getCount() != 1 || count != 1) {
        throw new IllegalArgumentException("Container fuel requires one item in its slot");
      }
      return remainder.copy();
    }
    return before.copyWithCount(Math.toIntExact(before.getCount() - count));
  }
}
