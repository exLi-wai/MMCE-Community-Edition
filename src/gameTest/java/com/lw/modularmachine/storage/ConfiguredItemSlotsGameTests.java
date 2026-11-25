package com.lw.modularmachine.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class ConfiguredItemSlotsGameTests {
  private static final int MAXIMUM_CONFIGURED_SLOTS = 1024;
  private static final int MAXIMUM_SAVED_SLOTS = 8192;

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void increasingConfiguredSlotsPreservesStockAndAcceptsNewMaterials(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var old = inventory(2);
    old.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 12));
    old.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT, 7));

    var grown = inventory(4);
    grown.deserializeConfiguredSlots(
        registries,
        old.serializeNBT(registries),
        MAXIMUM_CONFIGURED_SLOTS,
        MAXIMUM_SAVED_SLOTS);

    helper.assertTrue(
        grown.writableSlots() == 4 && grown.getSlots() == 4,
        "Expansion did not expose all configured slots");
    assertStack(helper, grown, 0, Items.IRON_INGOT, 12);
    assertStack(helper, grown, 1, Items.GOLD_INGOT, 7);
    helper.assertTrue(grown.getStackInSlot(2).isEmpty(), "Expansion populated an empty slot");
    helper.assertTrue(
        grown.insertItem(3, new ItemStack(Items.DIAMOND, 64), false).isEmpty(),
        "Expanded slot rejected new stock");
    helper.assertTrue(
        grown.insertItem(3, new ItemStack(Items.DIAMOND), false).getCount() == 1,
        "Expansion changed the ordinary stack limit");
    assertStack(helper, grown, 3, Items.DIAMOND, 64);
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void loweringConfiguredSlotsKeepsOverflowRecoverableAcrossSaves(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var reduced = reducedInventory(registries);
    helper.assertTrue(
        reduced.writableSlots() == 2 && reduced.getSlots() == 4,
        "Reduction lost occupied tail slots or retained receiving capacity");
    assertStack(helper, reduced, 0, Items.IRON_INGOT, 12);
    assertStack(helper, reduced, 3, Items.GOLD_INGOT, 9);
    helper.assertTrue(
        reduced.getSlotLimit(2) == 0 && reduced.getSlotLimit(3) == 9,
        "Retained slots exposed additional output capacity");

    var restored = inventory(2);
    restored.deserializeConfiguredSlots(
        registries,
        reduced.serializeNBT(registries),
        MAXIMUM_CONFIGURED_SLOTS,
        MAXIMUM_SAVED_SLOTS);
    helper.assertTrue(restored.getSlots() == 4, "Saving and loading lost the occupied tail slot");
    assertStack(helper, restored, 3, Items.GOLD_INGOT, 9);
    helper.assertTrue(restored.extractItem(3, 64, false).getCount() == 9, "Retained stock could not be recovered");
    helper.assertTrue(restored.getStackInSlot(3).isEmpty(), "Extraction left retained stock behind");
    helper.assertTrue(restored.getSlots() == 4, "Extraction changed live slot indices");

    var reopened = inventory(2);
    reopened.deserializeConfiguredSlots(
        registries,
        restored.serializeNBT(registries),
        MAXIMUM_CONFIGURED_SLOTS,
        MAXIMUM_SAVED_SLOTS);
    helper.assertTrue(reopened.getSlots() == 2, "Reload did not trim the empty retained tail");
    assertStack(helper, reopened, 0, Items.IRON_INGOT, 12);
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void loweringTheConfiguredMaximumPreservesPreviouslySavedHighSlots(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var previous = inventory(1025);
    previous.setStackInSlot(1024, new ItemStack(Items.DIAMOND));

    var capped = inventory(1024);
    capped.deserializeConfiguredSlots(
        registries,
        previous.serializeNBT(registries),
        MAXIMUM_CONFIGURED_SLOTS,
        MAXIMUM_SAVED_SLOTS);

    helper.assertTrue(
        capped.writableSlots() == 1024 && capped.getSlots() == 1025,
        "The lower configured limit discarded the previous overflow slot");
    assertStack(helper, capped, 1024, Items.DIAMOND, 1);
    helper
        .assertTrue(!capped.isItemValid(1024, new ItemStack(Items.DIAMOND)), "The old overflow slot remained writable");
    helper.assertTrue(
        capped.extractItem(1024, 1, false).is(Items.DIAMOND),
        "Previous overflow stock was not recoverable");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void overflowRejectsNewStockAndPreparedOutputWhileInputPaymentCanRollBack(GameTestHelper helper) {
    var reduced = reducedInventory(helper.getLevel().registryAccess());
    helper.assertTrue(!reduced.isItemValid(3, new ItemStack(Items.GOLD_INGOT)), "Retained slot accepted new stock");
    helper.assertTrue(
        reduced.insertItem(3, new ItemStack(Items.GOLD_INGOT), true).getCount() == 1
            && reduced.insertItem(3, new ItemStack(Items.GOLD_INGOT), false).getCount() == 1,
        "Retained slot allowed simulated or actual insertion");
    rejects(helper, () -> reduced.setStackInSlot(3, new ItemStack(Items.GOLD_INGOT, 10)), "Retained stock increased");
    rejects(helper, () -> reduced.setStackInSlot(3, new ItemStack(Items.DIAMOND, 9)), "Retained resource changed");

    var increased = contents(reduced);
    increased.set(3, new ItemStack(Items.GOLD_INGOT, 10));
    var replaced = contents(reduced);
    replaced.set(3, new ItemStack(Items.DIAMOND, 9));
    var payment = contents(reduced);
    payment.set(3, new ItemStack(Items.GOLD_INGOT, 4));
    try (var lease = reduced.acquire()) {
      rejects(helper, () -> lease.replacePrepared(increased), "Prepared output increased retained stock");
      rejects(helper, () -> lease.replacePrepared(replaced), "Prepared output replaced retained stock");
      lease.replacePrepared(payment);
    }
    assertStack(helper, reduced, 3, Items.GOLD_INGOT, 9);

    try (var lease = reduced.acquire()) {
      lease.replacePrepared(payment);
      lease.resolve(true);
    }
    assertStack(helper, reduced, 3, Items.GOLD_INGOT, 4);
    reduced.setStackInSlot(3, ItemStack.EMPTY);
    helper.assertTrue(reduced.getSlotLimit(3) == 0, "Empty retained slot regained output capacity");
    helper.assertTrue(
        !reduced.insertItem(3, new ItemStack(Items.GOLD_INGOT), false).isEmpty(),
        "Empty retained slot accepted new stock");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void malformedRestoresLeaveExistingInventoryIntact(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var reduced = reducedInventory(registries);
    CompoundTag original = reduced.serializeNBT(registries);
    var oversized = original.copy();
    oversized.putInt("Size", MAXIMUM_SAVED_SLOTS + 1);
    rejects(
        helper,
        () -> reduced.deserializeConfiguredSlots(registries, oversized, MAXIMUM_CONFIGURED_SLOTS, MAXIMUM_SAVED_SLOTS),
        "Oversized inventory data was accepted");
    helper.assertTrue(original.equals(reduced.serializeNBT(registries)), "Rejected oversized data changed inventory");

    var duplicated = original.copy();
    var entries = duplicated.getList("Items", Tag.TAG_COMPOUND);
    entries.add(entries.getCompound(0).copy());
    rejects(
        helper,
        () -> reduced.deserializeConfiguredSlots(registries, duplicated, MAXIMUM_CONFIGURED_SLOTS, MAXIMUM_SAVED_SLOTS),
        "Duplicate inventory slot was accepted");
    helper.assertTrue(original.equals(reduced.serializeNBT(registries)), "Rejected duplicate slot changed inventory");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "configured_item_slots", timeoutTicks = 40)
  public static void configuredRestoreDoesNotOpenAnArbitraryResizeEntryPoint(GameTestHelper helper) {
    var reduced = reducedInventory(helper.getLevel().registryAccess());
    rejects(helper, () -> reduced.setSize(2), "Live inventory allowed shrinking");
    rejects(helper, () -> reduced.setSize(5), "Live inventory allowed expansion");
    helper.assertTrue(reduced.getSlots() == 4, "Rejected resizing changed live slot indices");
    assertStack(helper, reduced, 3, Items.GOLD_INGOT, 9);
    helper.succeed();
  }

  private static TransactionalItemStackHandler reducedInventory(HolderLookup.Provider registries) {
    var old = inventory(4);
    old.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 12));
    old.setStackInSlot(3, new ItemStack(Items.GOLD_INGOT, 9));
    var reduced = inventory(2);
    reduced.deserializeConfiguredSlots(
        registries,
        old.serializeNBT(registries),
        MAXIMUM_CONFIGURED_SLOTS,
        MAXIMUM_SAVED_SLOTS);
    return reduced;
  }

  private static TransactionalItemStackHandler inventory(int slots) {
    return new TransactionalItemStackHandler(slots, () -> {
    }, () -> {
    }, () -> {
    });
  }

  private static NonNullList<ItemStack> contents(TransactionalItemStackHandler inventory) {
    var result = NonNullList.withSize(inventory.getSlots(), ItemStack.EMPTY);
    for (int slot = 0; slot < result.size(); slot++) {
      result.set(slot, inventory.getStackInSlot(slot).copy());
    }
    return result;
  }

  private static void assertStack(GameTestHelper helper, TransactionalItemStackHandler inventory, int slot, Item item, int count) {
    var stack = inventory.getStackInSlot(slot);
    helper.assertTrue(stack.is(item) && stack.getCount() == count, "Slot " + slot + " changed its resource or count");
  }

  private static void rejects(GameTestHelper helper, Runnable action, String message) {
    boolean rejected = false;
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    helper.assertTrue(rejected, message);
  }
}
