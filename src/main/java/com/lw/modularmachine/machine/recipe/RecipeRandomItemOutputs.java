package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RecipeScriptOptions;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import java.util.Optional;

/** Internal weighted output selection captured once before the paid start. */
@ApiStatus.Internal
final class RecipeRandomItemOutputs {
  private static final long OUTPUT_SEED = 0x696E677265646965L;

  private RecipeRandomItemOutputs() {
  }

  record Choice<K>(K key, long minimum, long maximum, double weight) {
    Choice {
      Objects.requireNonNull(key, "key");
      if (minimum < 0 || maximum < minimum || maximum < 1 || !Double.isFinite(weight) || weight < 0 || weight > 1) {
        throw new IllegalArgumentException("Invalid weighted output quantity");
      }
    }
  }

  record Group<K>(List<Choice<K>> alternatives, double chance, boolean parallelUnaffected) {
    Group {
      alternatives = List.copyOf(alternatives);
      if (alternatives.isEmpty()
          || !Double.isFinite(chance)
          || chance < 0
          || chance > 1
          || totalWeight(alternatives) <= 0) {
        throw new IllegalArgumentException(
            "Random item outputs need candidates, a probability and positive total weight");
      }
    }
  }

  private record StackIdentity(ItemStack prototype, String selector) {
    @Override
    public int hashCode() {
      return 31 * (31 * System.identityHashCode(prototype.getItem()) + prototype.getComponents().hashCode()) + selector
          .hashCode();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof StackIdentity key
          && selector.equals(key.selector)
          && ItemStack.isSameItemSameComponents(prototype, key.prototype);
    }
  }

  static int arithmeticLimit(MachineRecipe definition, int limit, boolean aggregate) {
    var outputs = definition.outputs().stream()
        .map(
            output -> new RecipeCandidate.Output<>(
                new StackIdentity(output.ingredientStack(), output.selector()),
                output.amount(),
                output.parallelUnaffected(),
                output.ignoreOutputCheck()))
        .toList();
    var groups = definition.ingredientArrays().randomOutputs().stream()
        .map(group -> describe(group, choice -> new StackIdentity(choice.item().orElseThrow(), group.selector())))
        .toList();
    return arithmeticLimit(outputs, groups, limit, aggregate);
  }

  /**
   * A group selects at most one candidate per attempt, including duplicate keys.
   */
  static <K> int arithmeticLimit(List<RecipeCandidate.Output<K>> outputs, List<Group<K>> groups, int limit, boolean aggregate) {
    var fixed = new HashMap<K, Long>();
    var scaled = new HashMap<K, Long>();
    try {
      for (var output : outputs) {
        if (output.ignoreOutputCheck()) {
          if (aggregate && !output.parallelUnaffected()) {
            limit = (int) Math.min(limit, Long.MAX_VALUE / output.count());
          }
          continue;
        }
        (aggregate && output.parallelUnaffected() ? fixed : scaled).merge(output.key(), output.count(), Math::addExact);
      }
      for (var group : groups) {
        if (group.chance() == 0) {
          continue;
        }
        var totals = aggregate && group.parallelUnaffected() ? fixed : scaled;
        largestAmountsByKey(group).forEach((key, amount) -> totals.merge(key, amount, Math::addExact));
      }
      for (var entry : scaled.entrySet()) {
        limit = (int) Math.min(limit, (Long.MAX_VALUE - fixed.getOrDefault(entry.getKey(), 0L)) / entry.getValue());
      }
      return limit;
    } catch (ArithmeticException overflow) {
      return 0;
    }
  }

  private static <K> Map<K, Long> largestAmountsByKey(Group<K> group) {
    var maxima = new HashMap<K, Long>();
    for (var choice : group.alternatives()) {
      if (choice.weight() > 0) {
        maxima.merge(choice.key(), choice.maximum(), Math::max);
      }
    }

    return maxima;
  }

  /**
   * Uses the caller's stream in group order: chance, weighted choice, then
   * amount. Zero-chance groups and fixed quantities consume no corresponding
   * draw.
   */
  static <K> long[] sample(Group<K> group, int instances, RandomSource random) {
    if (instances < 1) {
      throw new IllegalArgumentException("Invalid random output instance count");
    }
    var amounts = new long[group.alternatives().size()];
    if (group.chance() == 0) {
      return amounts;
    }
    double weight = totalWeight(group.alternatives());
    int attempts = group.parallelUnaffected() ? 1 : instances;
    for (int attempt = 0; attempt < attempts; attempt++) {
      if (group.chance() < 1 && random.nextDouble() >= group.chance()) {
        continue;
      }
      int selectedIndex = selectAlternative(group.alternatives(), weight, random);
      var choice = group.alternatives().get(selectedIndex);
      amounts[selectedIndex] = Math
          .addExact(amounts[selectedIndex], RecipeItemAmounts.sample(choice.minimum(), choice.maximum(), random));
    }
    return amounts;
  }

  private static double totalWeight(List<? extends Choice<?>> alternatives) {
    return alternatives.stream().mapToDouble(Choice::weight).sum();
  }

  private static <K> int selectAlternative(List<Choice<K>> alternatives, double weight, RandomSource random) {
    double selected = random.nextDouble() * weight;
    int selectedIndex = -1;
    for (int index = 0; index < alternatives.size(); index++) {
      var choice = alternatives.get(index);
      if (choice.weight() == 0) {
        continue;
      }
      selectedIndex = index;
      selected -= choice.weight();
      if (selected < 0) {
        break;
      }
    }

    return selectedIndex;
  }

  private static <K> Group<K> describe(IngredientArrayData.Group group, Function<IngredientArrayData.Choice, K> key) {
    return new Group<>(
        group.alternatives().stream()
            .map(choice -> new Choice<>(key.apply(choice), choice.minAmount(), choice.maxAmount(), choice.chance()))
            .toList(),
        group.chance(),
        group.parallelUnaffected());
  }

  static boolean needsOutputPlaceholder(CompoundTag definition, ListTag outputs) {
    var chemicals = definition.getCompound("chemicals");
    return outputs.isEmpty()
        && definition.getLong("energy_output_per_tick") == 0
        && definition.getList("fluid_outputs", Tag.TAG_COMPOUND).isEmpty()
        && definition.getList("fluid_per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()
        && chemicals.getList("outputs", Tag.TAG_COMPOUND).isEmpty()
        && chemicals.getList("per_tick_outputs", Tag.TAG_COMPOUND).isEmpty()
        && definition.getList("triggered_resources", Tag.TAG_COMPOUND).stream()
            .noneMatch(row -> ((CompoundTag) row).getString("io").equals("output"));
  }

  /** Mutates only the caller's detached prospective run, before paid start. */
  static void freeze(CompoundTag definition, long seed, int instances, HolderLookup.Provider registries) {
    if (!definition.contains("random_item_outputs", Tag.TAG_LIST)) {
      return;
    }
    var groups = IngredientArrayData.Group.CODEC.listOf()
        .parse(registries.createSerializationContext(NbtOps.INSTANCE), definition.get("random_item_outputs"))
        .getOrThrow();
    var outputs = definition.getList("outputs", Tag.TAG_COMPOUND);
    var callbacks = new ArrayList<>(
        RecipeScriptOptions.CODEC.codec().parse(NbtOps.INSTANCE, definition).getOrThrow().itemCallbacks());
    var random = RandomSource.create(seed ^ OUTPUT_SEED);
    for (var group : groups) {
      var amounts = sample(describe(group, Function.identity()), instances, random);
      appendSelectedOutputs(group, amounts, outputs, callbacks, registries);
    }
    if (!groups.isEmpty() && needsOutputPlaceholder(definition, outputs)) {
      var group = groups.getFirst();
      var row = frozenRow(group.alternatives().getFirst(), 1, group.selector(), registries);
      row.putDouble("chance", 0);
      outputs.add(row);
    }
    definition.put("outputs", outputs);
    definition.remove("random_item_outputs");
    if (!callbacks.isEmpty()) {
      definition.put(
          "item_callbacks",
          RecipeScriptOptions.ItemCallbacks.CODEC.listOf().encodeStart(NbtOps.INSTANCE, callbacks).getOrThrow());
    }
  }

  private static void appendSelectedOutputs(IngredientArrayData.Group group, long[] amounts, ListTag outputs, List<RecipeScriptOptions.ItemCallbacks> callbacks, HolderLookup.Provider registries) {
    for (int index = 0; index < amounts.length; index++) {
      long quantity = amounts[index];
      if (quantity == 0) {
        continue;
      }
      var choice = group.alternatives().get(index);
      var row = frozenRow(choice, quantity, group.selector(), registries);
      if (!choice.modifiers().isEmpty()) {
        callbacks.add(
            new RecipeScriptOptions.ItemCallbacks(
                RecipeModifier.Io.OUTPUT,
                outputs.size(),
                Optional.empty(),
                choice.modifiers()));
      }
      outputs.add(row);
      if (outputs.size() > MachineRecipeJson.MAX_DEMANDS) {
        throw new IllegalArgumentException("Random item outputs exceed the frozen output row budget");
      }
    }
  }

  private static CompoundTag frozenRow(IngredientArrayData.Choice choice, long amount, String selector, HolderLookup.Provider registries) {
    var row = new CompoundTag();
    row.put(
        "stack",
        ItemStack.SINGLE_ITEM_CODEC
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), choice.item().orElseThrow())
            .getOrThrow());
    row.putLong("amount", amount);
    // Amount already includes every selected instance; finish must not scale it
    // again.
    row.putBoolean("parallel_unaffected", true);
    row.putString("selector", selector);

    return row;
  }
}
