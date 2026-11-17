package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeRandomItemOutputsTest {
  private record Key(String item, String selector) {
  }

  private static RecipeRandomItemOutputs.Choice<String> choice(String key, long amount, double weight) {
    return new RecipeRandomItemOutputs.Choice<>(key, amount, amount, weight);
  }

  @Test
  void weightsSelectOneCandidateWhileGroupChanceControlsWhetherAnythingIsSelected() {
    var alternatives = List.of(choice("disabled", 1, 0), choice("common", 1, 1), choice("rare", 1, 0.5));
    var weighted = RecipeRandomItemOutputs
        .sample(new RecipeRandomItemOutputs.Group<>(alternatives, 1, false), 10_000, RandomSource.create(73));
    assertEquals(0, weighted[0]);
    assertEquals(10_000, Arrays.stream(weighted).sum());
    assertTrue(weighted[1] > 6_300 && weighted[1] < 7_000);
    var probabilistic = RecipeRandomItemOutputs
        .sample(new RecipeRandomItemOutputs.Group<>(alternatives, 0.25, false), 10_000, RandomSource.create(73));
    assertEquals(0, probabilistic[0]);
    assertTrue(Arrays.stream(probabilistic).sum() > 2_000 && Arrays.stream(probabilistic).sum() < 3_000);
    assertArrayEquals(
        new long[3],
        RecipeRandomItemOutputs
            .sample(new RecipeRandomItemOutputs.Group<>(alternatives, 0, false), 100, RandomSource.create(73)));
  }

  @Test
  void quantitiesUseInclusiveLongRangesAndTheSameSeedKeepsSelections() {
    var group = new RecipeRandomItemOutputs.Group<>(
        List.of(new RecipeRandomItemOutputs.Choice<>("near_limit", Long.MAX_VALUE - 1, Long.MAX_VALUE, 1)),
        1,
        false);
    var endpoints = new HashSet<Long>();
    for (int seed = 0; seed < 100; seed++) {
      var sampled = RecipeRandomItemOutputs.sample(group, 1, RandomSource.create(seed));
      assertArrayEquals(sampled, RecipeRandomItemOutputs.sample(group, 1, RandomSource.create(seed)));
      endpoints.add(sampled[0]);
    }
    assertEquals(Set.of(Long.MAX_VALUE - 1, Long.MAX_VALUE), endpoints);
    var zeroRange = new RecipeRandomItemOutputs.Group<>(
        List.of(new RecipeRandomItemOutputs.Choice<>("possibly_empty", 0, 1, 1)),
        1,
        false);
    var zeroEndpoints = new HashSet<Long>();
    for (int seed = 0; seed < 100; seed++) {
      zeroEndpoints.add(RecipeRandomItemOutputs.sample(zeroRange, 1, RandomSource.create(seed))[0]);
    }
    assertEquals(Set.of(0L, 1L), zeroEndpoints);
  }

  @Test
  void fixedGroupsRollOnceAndFrozenCountsAreNotMultipliedAgain() {
    var fixed = new RecipeRandomItemOutputs.Group<>(List.of(choice("fixed", 7, 1)), 1, true);
    assertArrayEquals(new long[]{7}, RecipeRandomItemOutputs.sample(fixed, Integer.MAX_VALUE, RandomSource.create(3)));
    var scaled = new RecipeRandomItemOutputs.Group<>(List.of(choice("scaled", 7, 1)), 1, false);
    long amount = RecipeRandomItemOutputs.sample(scaled, 12, RandomSource.create(3))[0];
    assertEquals(84, amount);
    var row = new CompoundTag();
    row.putLong("amount", amount);
    row.putBoolean("parallel_unaffected", true);
    var rows = new ListTag();
    rows.add(row);
    var frozen = new CompoundTag();
    frozen.put("outputs", rows);
    var unchanged = frozen.copy();
    RecipeRandomItemOutputs.freeze(frozen, 7, 12, null);
    RecipeRandomItemOutputs.freeze(frozen, 99, 12, null);
    assertEquals(unchanged, frozen);
    var completed = FrozenRecipeSampling.sampledOutputs(rows, 3, 12);
    assertEquals(1, completed.size());
    assertEquals(84, completed.getCompound(0).getLong("amount"));
  }

  @Test
  void admissionCombinesGroupsWithOrdinaryRowsButTakesOnlyTheLargestDuplicateCandidate() {
    long quarter = Long.MAX_VALUE / 4;
    var duplicate = new RecipeRandomItemOutputs.Group<>(
        List.of(choice("same", quarter, 1), choice("same", quarter + 1, 1)),
        1,
        false);
    assertEquals(3, RecipeRandomItemOutputs.arithmeticLimit(List.of(), List.of(duplicate), 100, true));
    var ordinary = List.of(new RecipeCandidate.Output<>("same", quarter));
    assertEquals(2, RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(duplicate), 100, true));
    var other = new RecipeRandomItemOutputs.Group<>(List.of(choice("same", quarter, 1)), 1, false);
    assertEquals(1, RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(duplicate, other), 100, true));
    var overflowing = new RecipeRandomItemOutputs.Group<>(List.of(choice("same", Long.MAX_VALUE, 1)), 1, false);
    assertEquals(0, RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(overflowing), 100, true));
  }

  @Test
  void fixedOutputsScaleAcrossFactoryRunsWhileSelectorsAndDisabledChoicesRemainIndependent() {
    long quarter = Long.MAX_VALUE / 4;
    var fixed = new RecipeRandomItemOutputs.Group<>(List.of(choice("same", quarter, 1)), 1, true);
    var ordinary = List.of(new RecipeCandidate.Output<>("same", 1));
    assertEquals(
        Integer.MAX_VALUE,
        RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(fixed), Integer.MAX_VALUE, true));
    assertEquals(3, RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(fixed), Integer.MAX_VALUE, false));
    var ignored = new RecipeRandomItemOutputs.Group<>(
        List.of(choice("same", Long.MAX_VALUE, 0), choice("small", 1, 1)),
        1,
        false);
    var disabled = new RecipeRandomItemOutputs.Group<>(List.of(choice("same", Long.MAX_VALUE, 1)), 0, false);
    assertEquals(
        Integer.MAX_VALUE,
        RecipeRandomItemOutputs.arithmeticLimit(ordinary, List.of(ignored, disabled), Integer.MAX_VALUE, true));
    var a = new Key("same exact item and components", "a");
    var b = new Key("same exact item and components", "b");
    var selected = new RecipeRandomItemOutputs.Group<>(
        List.of(new RecipeRandomItemOutputs.Choice<>(b, Long.MAX_VALUE, Long.MAX_VALUE, 1)),
        1,
        true);
    assertEquals(
        30,
        RecipeRandomItemOutputs.arithmeticLimit(
            List.of(new RecipeCandidate.Output<>(a, Long.MAX_VALUE, true)),
            List.of(selected),
            30,
            true));
    var sameSelector = new RecipeRandomItemOutputs.Group<>(
        List.of(new RecipeRandomItemOutputs.Choice<>(a, 1, 1, 1)),
        1,
        true);
    assertEquals(
        0,
        RecipeRandomItemOutputs.arithmeticLimit(
            List.of(new RecipeCandidate.Output<>(a, Long.MAX_VALUE, true)),
            List.of(sameSelector),
            30,
            true));
  }

  @Test
  void emptySelectionsNeedOnlyOnePlaceholderWhenAllResourceOutputsAreAbsent() {
    var definition = new CompoundTag();
    var empty = new ListTag();
    assertTrue(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, empty));
    definition.putLong("energy_output_per_tick", 1);
    assertFalse(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, empty));
    definition.remove("energy_output_per_tick");
    var other = new ListTag();
    other.add(new CompoundTag());
    for (String key : List.of("fluid_outputs", "fluid_per_tick_outputs")) {
      definition.put(key, other);
      assertFalse(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, empty));
      definition.remove(key);
    }
    var chemicals = new CompoundTag();
    definition.put("chemicals", chemicals);
    for (String key : List.of("outputs", "per_tick_outputs")) {
      chemicals.put(key, other);
      assertFalse(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, empty));
      chemicals.remove(key);
    }
    var trigger = new CompoundTag();
    trigger.putString("io", "output");
    var triggers = new ListTag();
    triggers.add(trigger);
    definition.put("triggered_resources", triggers);
    assertFalse(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, empty));
    definition.remove("triggered_resources");
    assertFalse(RecipeRandomItemOutputs.needsOutputPlaceholder(definition, other));
  }

  @Test
  void invalidWeightsAndProbabilitiesCannotCreateAmbiguousSelections() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecipeRandomItemOutputs.Group<>(List.of(choice("zero", 1, 0)), 1, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecipeRandomItemOutputs.Group<>(List.of(choice("valid", 1, 1)), Double.NaN, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeRandomItemOutputs.sample(
            new RecipeRandomItemOutputs.Group<>(List.of(choice("valid", 1, 1)), 1, false),
            0,
            RandomSource.create(3)));
  }
}
