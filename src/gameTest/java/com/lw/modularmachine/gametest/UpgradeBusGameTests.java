package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState.State;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeBusGameTests {
  private static final ResourceLocation MACHINE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "test_upgrade_bus_binding");

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 100)
  public static void upgrade_sources_refresh_suspend_restore_and_withdraw(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var busPos = origin.south();
    var framePos = origin.south().east();
    level.setBlockAndUpdate(origin, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    var bus = placeBus(helper, busPos, "normal");
    level.setBlockAndUpdate(framePos, Blocks.STONE_BRICKS.defaultBlockState());
    var factory = (FactoryControllerBlockEntity) level.getBlockEntity(origin);
    factory.bindMachine(MACHINE);
    factory.replaceThreads(
        List.of(
            new FactoryThreadState(
                "upgrade_thread",
                MACHINE,
                null,
                true,
                0,
                1,
                0,
                State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                FactoryUpgradeSnapshot.identity(0))));
    confirm(helper, factory);

    var unrelated = placeBus(helper, origin.east(3), "normal");
    helper.assertTrue(!unrelated.bindFactory(factory), "Nearby bus outside the pattern was allowed to bind");
    helper.assertTrue(bus.bindFactory(factory), "Structure member upgrade bus could not bind");
    helper.assertTrue(factory.upgradeSources().size() == 1, "Bound bus did not publish a source");
    helper.assertTrue(
        bus.upgradeCatalog().snapshot().equals(FactoryUpgradeSnapshot.identity(bus.upgradeRevision())),
        "Empty bus did not publish identity");

    var marked = markedUpgrade();
    var before = marked.copy();
    bus.inventory().setStackInSlot(0, marked);
    long revision = bus.upgradeRevision();
    var source = factory.upgradeSources().get(0);
    helper.assertTrue(
        source.catalog().entries().size() == 1
            && source.catalog().snapshot().revision() == revision
            && source.catalog().snapshot().parallelLimit() == 3
            && ItemStack.matches(marked, before),
        "Slot change did not refresh the source or mutated item components");
    factory.serverTick();
    var upgradedThread = factory.scheduler().channels().getFirst();
    helper.assertTrue(
        upgradedThread.upgradeSnapshot().parallelLimit() == 3
            && upgradedThread.upgradeSnapshot().speedNumerator() == 9
            && upgradedThread.upgradeSnapshot().speedDenominator() == 4,
        "Published upgrade catalog was not applied: " + upgradedThread.upgradeSnapshot() + ", sources=" + factory
            .upgradeSources().size() + ", state=" + upgradedThread.state() + ", revision=" + upgradedThread.revision());
    var malformed = markedUpgrade();
    var malformedData = malformed.get(DataComponents.CUSTOM_DATA).copyTag();
    malformedData.putInt("parallel_add", -1);
    malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(malformedData));
    bus.inventory().setStackInSlot(0, malformed);
    helper.assertTrue(
        !bus.upgradeCatalogValid() && factory.upgradeSources().isEmpty(),
        "Malformed effect kept publishing an old catalog");
    bus.inventory().setStackInSlot(0, markedUpgrade());
    helper.assertTrue(
        bus.upgradeCatalogValid() && factory.upgradeSources().size() == 1,
        "Corrected upgrade did not republish");
    revision = bus.upgradeRevision();
    bus.inventory().extractItem(0, 2, false);
    helper.assertTrue(
        factory.upgradeSources().get(0).catalog().entries().isEmpty()
            && bus.upgradeCatalog().snapshot().equals(FactoryUpgradeSnapshot.identity(revision + 1)),
        "Removing the last upgrade retained a stale catalog");
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().upgradeSnapshot().parallelLimit() == 1,
        "Removing the last upgrade did not clear the published thread snapshot");

    level.setBlockAndUpdate(framePos, Blocks.AIR.defaultBlockState());
    bus.refreshBinding();
    helper.assertTrue(
        factory.upgradeSources().isEmpty() && bus.boundFactory().isPresent(),
        "Broken or pending proof retained authority or erased the binding intent");
    level.setBlockAndUpdate(framePos, Blocks.STONE_BRICKS.defaultBlockState());
    confirm(helper, factory);
    bus.refreshBinding();
    helper.assertTrue(factory.upgradeSources().size() == 1, "Structure repair did not restore the source");

    bus.onChunkUnloaded();
    helper.assertTrue(factory.upgradeSources().isEmpty(), "Bus unload retained a published source");
    bus.onLoad();
    helper.assertTrue(factory.upgradeSources().size() == 1, "Bus load did not revalidate the source");

    bus.inventory().setStackInSlot(0, markedUpgrade());
    var saved = bus.saveWithFullMetadata(level.registryAccess());
    long savedRevision = bus.upgradeRevision();
    var busState = bus.getBlockState();
    level.removeBlockEntity(busPos);
    var restored = (UpgradeBusBlockEntity) BlockEntity.loadStatic(busPos, busState, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();
    helper.assertTrue(
        restored.schemaSupported()
            && restored.upgradeRevision() == savedRevision
            && restored.boundFactory().orElseThrow().equals(origin)
            && factory.upgradeSources().get(0).catalog().entries().size() == 1,
        "Bus rebuild changed revision, lost binding, or retained an old source");

    factory.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "missing_upgrade_machine"));
    restored.refreshBinding();
    helper.assertTrue(
        restored.boundFactory().isEmpty() && factory.upgradeSources().isEmpty(),
        "Factory rebind retained an upgrade source");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 80)
  public static void five_upgrade_bus_tiers_restore_inventory_without_revision_drift(GameTestHelper helper) {
    var level = helper.getLevel();
    int offset = 0;
    for (var tier : new String[]{"normal", "reinforced", "elite", "super", "ultimate"}) {
      var position = helper.absolutePos(new BlockPos(3 + offset++, 5, 7));
      var bus = placeBus(helper, position, tier);
      int slots = bus.tier().slots();
      bus.inventory().setStackInSlot(slots - 1, new ItemStack(Items.STONE, 7));
      long revision = bus.upgradeRevision();
      var saved = bus.saveWithFullMetadata(level.registryAccess());
      var state = bus.getBlockState();
      level.removeBlockEntity(position);
      var restored = (UpgradeBusBlockEntity) BlockEntity.loadStatic(position, state, saved, level.registryAccess());
      level.setBlockEntity(restored);
      restored.onLoad();
      helper.assertTrue(
          restored.schemaSupported()
              && restored.inventory().getSlots() == slots
              && restored.inventory().getStackInSlot(slots - 1).getCount() == 7
              && restored.upgradeRevision() == revision,
          "Upgrade bus tier or revision changed during restore: " + tier);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 80)
  public static void corrupt_upgrade_inventory_is_preserved_and_worker_mutation_is_rejected(GameTestHelper helper) {
    var level = helper.getLevel();
    var bus = placeBus(helper, helper.absolutePos(new BlockPos(7, 5, 7)), "normal");
    bus.inventory().setStackInSlot(0, new ItemStack(Items.STONE, 3));
    boolean rejected = CompletableFuture.supplyAsync(() -> {
      try {
        bus.inventory().setStackInSlot(0, ItemStack.EMPTY);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper
        .assertTrue(rejected && bus.inventory().getStackInSlot(0).getCount() == 3, "Worker mutated upgrade inventory");
    var good = bus.saveWithoutMetadata(level.registryAccess());
    var bad = good.copy();
    var wrongItems = new ListTag();
    wrongItems.add(StringTag.valueOf("wrong"));
    bad.getCompound("inventory").put("Items", wrongItems);
    bus.loadWithComponents(bad, level.registryAccess());
    helper.assertTrue(
        !bus.schemaSupported()
            && bus.saveWithoutMetadata(level.registryAccess()).getCompound("inventory")
                .equals(bad.getCompound("inventory")),
        "Invalid item list was silently filtered or overwritten");
    try {
      bus.inventory().extractItem(0, 1, false);
      helper.fail("Invalid upgrade inventory remained writable");
    } catch (IllegalStateException expected) {
      // Recovery below uses the last valid complete image.
    }
    bus.loadWithComponents(good, level.registryAccess());
    helper.assertTrue(
        bus.schemaSupported()
            && bus.inventory().getStackInSlot(0).getCount() == 3
            && bus.upgradeRevision() == good.getLong("upgrade_revision"),
        "Valid inventory image did not restore exactly");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 80)
  public static void unavailable_upgrade_binding_does_not_force_load_factory_chunk(GameTestHelper helper) {
    var level = helper.getLevel();
    var bus = placeBus(helper, helper.absolutePos(new BlockPos(7, 5, 7)), "normal");
    var unavailable = bus.getBlockPos().offset(20000, 0, 20000);
    helper.assertTrue(!level.hasChunkAt(unavailable), "Unavailable binding fixture was already loaded");
    var saved = bus.saveWithoutMetadata(level.registryAccess());
    saved.putLong("bound_factory", unavailable.asLong());
    saved.putString("bound_machine", MACHINE.toString());
    bus.loadWithComponents(saved, level.registryAccess());
    bus.refreshBinding();
    helper.assertTrue(
        !level.hasChunkAt(unavailable) && bus.boundFactory().orElseThrow().equals(unavailable),
        "Binding refresh force-loaded an unavailable chunk or lost its suspended intent");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 100)
  public static void factory_rebuild_drops_withdrawn_source_effects(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = buildUpgradeFactory(helper, origin, MACHINE);
    var bus = placeBus(helper, origin.south(), "normal");
    level.setBlockAndUpdate(origin.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    confirm(helper, factory);
    helper.assertTrue(bus.bindFactory(factory), "Could not bind rebuild fixture");
    bus.inventory().setStackInSlot(0, markedUpgrade());
    factory.serverTick();
    helper.assertTrue(factory.scheduler().channels().getFirst().parallelLimit() == 3, "Upgrade was not applied");
    var saved = factory.saveWithFullMetadata(level.registryAccess());
    var state = factory.getBlockState();
    bus.unbindFactory();
    level.setBlockAndUpdate(bus.getBlockPos(), Blocks.STONE_BRICKS.defaultBlockState());
    level.removeBlockEntity(origin);
    var restored = (FactoryControllerBlockEntity) BlockEntity.loadStatic(origin, state, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();
    confirm(helper, restored);
    restored.serverTick();
    var snapshot = restored.scheduler().channels().getFirst().upgradeSnapshot();
    helper.assertTrue(
        restored.upgradeSources().isEmpty()
            && snapshot.equals(FactoryUpgradeSnapshot.identity(snapshot.revision()))
            && snapshot.revision() > factory.scheduler().channels().getFirst().upgradeRevision(),
        "Rebuild retained a withdrawn upgrade source or rolled back its revision");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 100)
  public static void overflowing_upgrade_sources_gate_starts_and_recover(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_upgrade_bus_pair");
    var factory = buildUpgradeFactory(helper, origin, machine);
    var first = placeBus(helper, origin.south(), "normal");
    var second = placeBus(helper, origin.south().east(), "normal");
    confirm(helper, factory);
    helper.assertTrue(first.bindFactory(factory) && second.bindFactory(factory), "Could not bind both sources");
    var stack = markedUpgrade().copyWithCount(1);
    var data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
    data.putInt("speed_numerator", 128);
    data.putInt("speed_denominator", 1);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    first.inventory().setStackInSlot(0, stack.copy());
    second.inventory().setStackInSlot(0, stack.copy());
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().reason() == FactoryThreadState.Reason.CHECK_ERROR
            && !factory.beginRunStart("upgrade_thread"),
        "Invalid composed speed did not gate new starts");
    second.inventory().setStackInSlot(0, ItemStack.EMPTY);
    factory.serverTick();
    var recovered = factory.scheduler().channels().getFirst();
    helper.assertTrue(
        recovered.upgradeSnapshot().speedNumerator() == 128
            && recovered.reason() != FactoryThreadState.Reason.CHECK_ERROR
            && factory.beginRunStart("upgrade_thread"),
        "Correcting the source did not recover publication");
    factory.finishRunStart("upgrade_thread", false);
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 100)
  public static void confirmed_factory_discovers_upgrade_parts_and_restores_their_binding(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = buildUpgradeFactory(helper, origin, MACHINE);
    var bus = placeBus(helper, origin.south(), "normal");
    var unrelated = placeBus(helper, origin.east(3), "normal");
    var frame = origin.south().east();
    level.setBlockAndUpdate(frame, Blocks.STONE_BRICKS.defaultBlockState());
    bus.inventory().setStackInSlot(0, markedUpgrade());
    factory.serverTick();
    helper.assertTrue(bus.boundFactory().isEmpty(), "Pending structure acquired an upgrade source");
    confirm(helper, factory);
    factory.serverTick();
    helper.assertTrue(
        bus.boundFactory().orElseThrow().equals(origin)
            && unrelated.boundFactory().isEmpty()
            && factory.upgradeSources().size() == 1
            && factory.scheduler().channels().getFirst().parallelLimit() == 3,
        "Confirmed factory did not discover and apply only its own upgrade bus");
    long revision = factory.scheduler().channels().getFirst().upgradeRevision();
    for (int attempt = 0; attempt < 100; attempt++) {
      factory.serverTick();
    }
    helper.assertTrue(
        factory.scheduler().channels().getFirst().upgradeRevision() == revision,
        "Unchanged discovery repeatedly republished upgrade revisions");
    level.setBlockAndUpdate(frame, Blocks.AIR.defaultBlockState());
    factory.serverTick();
    helper.assertTrue(
        factory.upgradeSources().isEmpty() && bus.boundFactory().isPresent(),
        "Pending proof retained a source or discarded its repairable binding");
    level.setBlockAndUpdate(frame, Blocks.STONE_BRICKS.defaultBlockState());
    confirm(helper, factory);
    factory.serverTick();
    helper.assertTrue(factory.upgradeSources().size() == 1, "Repair did not rediscover the upgrade bus");
    var saved = factory.saveWithFullMetadata(level.registryAccess());
    var state = factory.getBlockState();
    level.removeBlockEntity(origin);
    var restored = (FactoryControllerBlockEntity) BlockEntity.loadStatic(origin, state, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();
    confirm(helper, restored);
    restored.serverTick();
    helper.assertTrue(
        restored.upgradeSources().size() == 1
            && bus.isBoundTo(restored)
            && restored.scheduler().channels().getFirst().parallelLimit() == 3,
        "Factory rebuild did not revalidate and rediscover its upgrade source");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_lifecycle", timeoutTicks = 100)
  public static void automatic_upgrade_discovery_never_steals_another_factory_binding(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var first = buildUpgradeFactory(helper, origin, MACHINE);
    var bus = placeBus(helper, origin.south(), "normal");
    level.setBlockAndUpdate(origin.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    confirm(helper, first);
    first.serverTick();
    var second = buildUpgradeFactory(
        helper,
        origin.east(2),
        ResourceLocation.fromNamespaceAndPath("mmcece", "test_upgrade_bus_shared_binding"));
    confirm(helper, second);
    second.serverTick();
    helper.assertTrue(
        bus.isBoundTo(first) && first.upgradeSources().size() == 1 && second.upgradeSources().isEmpty(),
        "Automatic discovery stole a valid source from another factory");
    helper.assertTrue(bus.bindFactory(second), "Trusted explicit binding could not change source ownership");
    helper.runAfterDelay(21, () -> {
      first.serverTick();
      second.serverTick();
      helper.assertTrue(
          bus.isBoundTo(second) && first.upgradeSources().isEmpty() && second.upgradeSources().size() == 1,
          "Periodic discovery oscillated the explicitly rebound source");
      helper.succeed();
    });
  }

  static FactoryControllerBlockEntity buildUpgradeFactory(GameTestHelper helper, BlockPos origin, ResourceLocation machine) {
    helper.getLevel().setBlockAndUpdate(origin, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    var factory = (FactoryControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    factory.bindMachine(machine);
    factory.replaceThreads(
        List.of(
            new FactoryThreadState(
                "upgrade_thread",
                machine,
                null,
                true,
                0,
                1,
                0,
                State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                FactoryUpgradeSnapshot.identity(0))));

    return factory;
  }

  static UpgradeBusBlockEntity placeBus(GameTestHelper helper, BlockPos position, String tier) {
    helper.getLevel().setBlockAndUpdate(
        position,
        MachineRegistries.UPGRADE_BUSES.get("upgrade_bus_" + tier).get().defaultBlockState());

    return (UpgradeBusBlockEntity) helper.getLevel().getBlockEntity(position);
  }

  private static ItemStack markedUpgrade() {
    var stack = new ItemStack(Items.STONE, 2);
    var data = new CompoundTag();
    data.putBoolean("mmcece:factory_upgrade", true);
    data.putInt("parallel_add", 1);
    data.putInt("speed_numerator", 3);
    data.putInt("speed_denominator", 2);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));

    return stack;
  }

  static void confirm(GameTestHelper helper, FactoryControllerBlockEntity factory) {
    for (int index = 0; index < 200 && !factory.canCommitStructure(); index++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(factory.canCommitStructure(), "Upgrade bus fixture did not confirm its structure");
  }
}
