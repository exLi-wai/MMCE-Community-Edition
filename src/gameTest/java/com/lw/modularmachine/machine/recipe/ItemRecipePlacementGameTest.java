package com.lw.modularmachine.machine.recipe;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Isolated regression namespace for converting bulk output plans into native
 * placements.
 */
@GameTestHolder("mmcece_placement")
@PrefixGameTestTemplate(false)
public final class ItemRecipePlacementGameTest {
  private static final ItemSlotRef SLOT = new ItemSlotRef("output", 0);

  private ItemRecipePlacementGameTest() {
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void accepts_planner_bulk_placements_for_128_items_and_int_limit(GameTestHelper helper) {
    var iron = key(helper, Items.IRON_INGOT);
    var output = output(iron, 0, Integer.MAX_VALUE, true);
    for (int amount : new int[]{128, Integer.MAX_VALUE}) {
      var recipe = RecipeCandidate.outputOnly("bulk", List.of(new RecipeCandidate.Output<>(iron, amount)));
      var result = RecipePlanner.prepareOutputs(new ItemResourceSnapshot<>(0, Map.of(), List.of(output)), recipe)
          .advance(4096);
      helper.assertTrue(result.status() == RecipePlanner.Status.SELECTED, "Bulk output was not planned");
      var insertions = result.plan().orElseThrow().insertions();
      helper.assertTrue(insertions.size() == 1, "Bulk output must fit a single physical slot");
      var insertion = insertions.getFirst();
      var placement = ItemRecipePlan.Placement
          .inSlot(SLOT, output, insertion.key(), Math.toIntExact(insertion.count()));
      helper.assertTrue(placement.count() == amount, "Native placement truncated the planned output");
      helper
          .assertTrue(placement.availableCapacity() == Integer.MAX_VALUE, "Native placement lost the ME slot capacity");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void rejects_placements_beyond_actual_remaining_space(GameTestHelper helper) {
    var iron = key(helper, Items.IRON_INGOT);
    var pearl = key(helper, Items.ENDER_PEARL);
    var output = output(iron, Integer.MAX_VALUE - 128, Integer.MAX_VALUE, true);
    helper.assertTrue(
        ItemRecipePlan.Placement.inSlot(SLOT, output, iron, 128).availableCapacity() == 128,
        "Existing contents were not deducted from free space");
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, output, iron, 129));
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, output, pearl, 1));
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, output, iron.ignoringComponents(), 1));
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, output, iron, 0));
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void ordinary_slots_keep_native_and_smaller_physical_limits(GameTestHelper helper) {
    var iron = key(helper, Items.IRON_INGOT);
    var pearl = key(helper, Items.ENDER_PEARL);
    var normal = output(iron, 0, 64, false);
    helper.assertTrue(
        ItemRecipePlan.Placement.inSlot(SLOT, normal, iron, 64).count() == 64,
        "Ordinary full stack was rejected");
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, normal, iron, 128));
    var smaller = output(iron, 0, 32, false);
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, smaller, iron, 33));
    var nativeLimit = output(pearl, 0, 64, false);
    helper.assertTrue(
        ItemRecipePlan.Placement.inSlot(SLOT, nativeLimit, pearl, 16).count() == 16,
        "Native pearl stack was rejected");
    reject(() -> ItemRecipePlan.Placement.inSlot(SLOT, nativeLimit, pearl, 17));
    helper.succeed();
  }

  private static ItemResourceKey key(GameTestHelper helper, Item item) {
    return ItemResourceKey.of(new ItemStack(item), helper.getLevel().registryAccess());
  }

  private static ItemResourceSnapshot.OutputSlot<ItemResourceKey> output(ItemResourceKey key, int count, int capacity, boolean bulk) {
    var domain = new ItemSlotDomain(capacity, key.selector(), bulk);
    return new ItemResourceSnapshot.OutputSlot<>(count == 0 ? null : key, count, capacity, domain.limits(Set.of(key)));
  }

  private static void reject(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Invalid placement was accepted");
  }
}
