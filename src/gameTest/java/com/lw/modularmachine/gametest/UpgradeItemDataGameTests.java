package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryDynamicUpgrades;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeItemData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.ItemRecipePlan;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeItemDataGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_start_costs");
  private static final ResourceLocation RECIPE = id("mmcece/test_upgrade_start_costs");
  private static final ResourceLocation HANDLER = id("test_upgrade_item_data");
  private static final ResourceLocation EFFECT = id("test_item_data_effect");
  private static final ResourceLocation OBSERVER = id("test_item_data_observer");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  private record Fixture(
      FactoryControllerBlockEntity factory,
      UpgradeBusBlockEntity busA,
      UpgradeBusBlockEntity busB,
      MachinePortBlockEntity input,
      MachinePortBlockEntity output) {
  }

  @AfterBatch(batch = "upgrade_item_data")
  public static void cleanupData(ServerLevel level) {
    cleanup();
  }

  @AfterBatch(batch = "upgrade_item_data_failures")
  public static void cleanupFailures(ServerLevel level) {
    cleanup();
  }

  private static void cleanup() {
    FactoryUpgradeItemData.remove(HANDLER);
    FactoryDynamicUpgrades.remove(EFFECT);
    FactoryUpgradeEvents.remove(OBSERVER);
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_item_data", timeoutTicks = 800)
  public static void item_data_and_costs_commit_once_per_batch_and_survive_be_restore(GameTestHelper helper) {
    begin(helper, true, fixture -> verifySuccess(helper, fixture));
  }

  private static void verifySuccess(GameTestHelper helper, Fixture fixture) {
    var current = new AtomicReference<>(fixture);
    var observations = new ArrayList<Long>();
    FactoryUpgradeEvents.register(OBSERVER, (context, upgrade) -> {
      if (context.type() == FactoryUpgradeEvents.Type.STARTED) {
        long uses = FactoryUpgradeItemData.read(current.get().busB().inventory().getStackInSlot(0)).getLong("uses");
        helper.assertTrue(
            uses == upgrade.itemData().getCompound("components").getCompound("minecraft:custom_data")
                .getCompound(FactoryUpgradeItemData.DATA_KEY).getLong("uses"),
            "Startup observer saw item data from before the commit");
        observations.add(uses);
      }
    });
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.factory().scheduler().channels().getFirst().reason() == FactoryThreadState.Reason.CHECK_ERROR,
        "Missing data preparer fell back to an unpaid identity source");
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    var calls = new AtomicInteger();
    var retained = new AtomicReference<CompoundTag>();
    FactoryUpgradeItemData.register(HANDLER, (context, upgrade, data) -> {
      calls.incrementAndGet();
      helper.assertTrue(
          context.machine().equals(MACHINE)
              && context.recipeId().equals(RECIPE)
              && context.threadId().equals(THREAD)
              && context.instances() == 2
              && context.dimension().equals(helper.getLevel().dimension().location())
              && FactoryUpgradeEvents.isDispatching(fixture.factory()),
          "Data preparer lost its batch context or read-only guard");
      try {
        fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND));
        helper.fail("Data preparation modified a live upgrade slot");
      } catch (IllegalStateException expected) {
      }
      helper.assertTrue(!fixture.factory().beginRunStart(THREAD), "Data preparation reentered startup");
      fixture.factory().serverTick();
      data.putLong("uses", Math.addExact(data.getLong("uses"), context.instances()));
      data.putString("id", "minecraft:diamond");
      retained.set(data);

      return new FactoryUpgradeItemData.Update(data);
    });
    synchronize(helper, fixture);
    for (int attempt = 0; attempt < 100; attempt++) {
      tick(fixture);
      UpgradeBusPayloads.capture(7, fixture.busA());
    }
    helper.assertTrue(calls.get() == 0, "Idle ticks or menus executed startup data transforms");
    try {
      FactoryUpgradeStartCosts.prepare(fixture.factory(), 2);
      helper.fail("Context-free startup preparation bypassed a data declaration");
    } catch (IllegalArgumentException expected) {
    }
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 4));
    helper.assertTrue(
        FactoryParallelStarter.commit(
            fixture.factory(),
            THREAD,
            Collections.nCopies(4, plan(fixture))) == FactoryParallelStarter.Result.UPGRADE_COST_MISSING
            && calls.get() == 0
            && fixture.input().inventory().getStackInSlot(0).getCount() == 4,
        "Unpayable startup called data preparers or consumed input");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(fixture);
    long revisionA = fixture.busA().upgradeRevision();
    long revisionB = fixture.busB().upgradeRevision();
    int oldBudget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    try {
      MachineConfig.RECIPE_OUTPUT_WORK.set(1);
      helper.assertTrue(
          FactoryParallelStarter.commit(
              fixture.factory(),
              THREAD,
              Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.CHECK_PENDING
              && calls.get() == 1
              && fixture.factory().run(THREAD).isEmpty()
              && fixture.input().inventory().getStackInSlot(0).getCount() == 2
              && fixture.busA().inventory().getStackInSlot(0).getCount() == 3
              && FactoryUpgradeItemData.read(fixture.busA().inventory().getStackInSlot(0)).isEmpty()
              && fixture.busA().upgradeRevision() == revisionA
              && fixture.busB().upgradeRevision() == revisionB,
          "Partial data budget published prepared copies or charged costs");
      retained.get().putLong("uses", 999);
    } catch (RuntimeException | Error failure) {
      MachineConfig.RECIPE_OUTPUT_WORK.set(oldBudget);
      throw failure;
    }
    helper.runAfterDelay(1, () -> {
      try {
        var resumed = FactoryParallelStarter.commit(fixture.factory(), THREAD, Collections.nCopies(2, plan));
        helper.assertTrue(
            resumed == FactoryParallelStarter.Result.STARTED && calls.get() == 2,
            "Data budget did not resume at the next slot across ticks: " + resumed + ", calls=" + calls
                .get() + ", revisions=" + revisionA + "/" + fixture.busA()
                    .upgradeRevision() + "," + revisionB + "/" + fixture.busB()
                        .upgradeRevision() + ", runs=" + fixture.factory().runSet(THREAD).runs().size());
      } finally {
        MachineConfig.RECIPE_OUTPUT_WORK.set(oldBudget);
      }
      retained.get().putLong("uses", 999);
      helper.assertTrue(
          fixture.busA().upgradeRevision() == revisionA + 1
              && fixture.busB().upgradeRevision() == revisionB + 1
              && uses(fixture.busA()) == 2
              && uses(fixture.busB()) == 2
              && fixture.busA().inventory().getStackInSlot(0).is(Items.ECHO_SHARD)
              && fixture.busA().inventory().getStackInSlot(0).getCount() == 1
              && fixture.busA().inventory().getStackInSlot(0).getHoverName().getString().equals("Data A")
              && fixture.busA().inventory().getStackInSlot(0).get(DataComponents.CUSTOM_DATA).copyTag()
                  .getString("foreign").equals("NA")
              && fixture.factory().runSet(THREAD).runs().stream().allMatch(run -> run.duration() == 20)
              && observations.equals(List.of(2L)),
          "Data merged with costs incorrectly, lost components, or retroactively changed runs");
      helper.assertTrue(
          FactoryParallelStarter
              .commit(fixture.factory(), THREAD, Collections.nCopies(2, plan)) != FactoryParallelStarter.Result.STARTED
              && calls.get() == 2,
          "A repeated paid startup replayed data transforms");
      var restored = restoreBus(helper, fixture);
      current.set(restored);
      helper.assertTrue(
          uses(restored.busB()) == 2 && restored.busB().upgradeRevision() == revisionB + 1,
          "Restoring upgrade inventory lost data or replayed its transaction");
      awaitOutput(helper, restored, 2, 120, () -> {
        helper.assertTrue(calls.get() == 2, "Paid tick, output, or BE restoration repeated a data transform");
        beginNoopAndClear(helper, restored, calls, observations);
      });
    });
  }

  private static void beginNoopAndClear(GameTestHelper helper, Fixture fixture, AtomicInteger calls, List<Long> observations) {
    clearOutput(fixture);
    FactoryUpgradeItemData.register(HANDLER, (context, upgrade, data) -> {
      calls.incrementAndGet();
      return new FactoryUpgradeItemData.Update(data);
    });
    synchronize(helper, fixture);
    long revision = fixture.busB().upgradeRevision();
    int before = calls.get();
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
            && calls.get() == before + 1
            && fixture.busB().upgradeRevision() == revision
            && fixture.busA().inventory().getStackInSlot(0).isEmpty()
            && uses(fixture.busB()) == 2,
        "No-op data changed revision or a fully consumed item ran its data transform");
    awaitOutput(helper, fixture, 1, 120, () -> {
      clearOutput(fixture);
      FactoryUpgradeItemData
          .register(HANDLER, (context, upgrade, data) -> new FactoryUpgradeItemData.Update(new CompoundTag()));
      synchronize(helper, fixture);
      long beforeClear = fixture.busB().upgradeRevision();
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      helper.assertTrue(
          FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
              && fixture.busB().upgradeRevision() == beforeClear + 1
              && uses(fixture.busB()) == 0
              && !fixture.busB().inventory().getStackInSlot(0).get(DataComponents.CUSTOM_DATA).copyTag()
                  .contains(FactoryUpgradeItemData.DATA_KEY)
              && fixture.busB().inventory().getStackInSlot(0).get(DataComponents.CUSTOM_DATA).copyTag()
                  .getString("foreign").equals("NA")
              && observations.equals(List.of(2L, 2L, 0L)),
          "Empty result erased unrelated data or did not remove the owned field");
      awaitOutput(helper, fixture, 1, 120, () -> helper.succeed());
    });
  }

  @GameTest(template = "empty16", batch = "upgrade_item_data_failures", timeoutTicks = 800)
  public static void invalid_or_stale_preparations_never_commit_partial_data_or_costs(GameTestHelper helper) {
    begin(helper, false, fixture -> verifyFailures(helper, fixture));
  }

  private static void verifyFailures(GameTestHelper helper, Fixture fixture) {
    var firstCalls = new AtomicInteger();
    var lastCalls = new AtomicInteger();
    FactoryUpgradeItemData.Preparer failing = (context, upgrade, data) -> {
      if (upgrade.itemId().equals(ResourceLocation.fromNamespaceAndPath("minecraft", "phantom_membrane"))) {
        lastCalls.incrementAndGet();
        throw new IllegalStateException("expected later data preparation failure");
      }
      firstCalls.incrementAndGet();
      data.putLong("uses", context.instances());
      return new FactoryUpgradeItemData.Update(data);
    };
    FactoryUpgradeItemData.register(HANDLER, failing);
    var published = FactoryUpgradeService.snapshot();
    FactoryUpgradeItemData.register(HANDLER, failing);
    FactoryUpgradeItemData.remove(id("unknown_item_data_handler"));
    boolean workerRejected = CompletableFuture.supplyAsync(() -> {
      try {
        FactoryUpgradeItemData.register(HANDLER, failing);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        workerRejected && FactoryUpgradeService.snapshot() == published,
        "Worker or no-op data registration changed the catalog");
    synchronize(helper, fixture);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(fixture);
    long revisionA = fixture.busA().upgradeRevision();
    long revisionB = fixture.busB().upgradeRevision();
    for (int attempt = 0; attempt < 100; attempt++) {
      helper.assertTrue(
          FactoryParallelStarter.commit(
              fixture.factory(),
              THREAD,
              Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.CHECK_ERROR,
          "Failed preparation was not cached");
    }
    helper.assertTrue(
        firstCalls.get() == 1
            && lastCalls.get() == 1
            && fixture.busA().upgradeRevision() == revisionA
            && fixture.busB().upgradeRevision() == revisionB,
        "Later failure reran the first transform or published a partial revision");
    assertUnpaid(helper, fixture, 2);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan) == FactoryRecipeStarter.Result.CHECK_ERROR,
        "Single startup used a separate unsafe data path");
    assertUnpaid(helper, fixture, 2);
    FactoryUpgradeItemData.Preparer stable = (context, upgrade, data) -> {
      data.putLong("uses", Math.addExact(data.getLong("uses"), context.instances()));
      return new FactoryUpgradeItemData.Update(data);
    };
    FactoryUpgradeItemData.register(HANDLER, (context, upgrade, data) -> {
      FactoryUpgradeItemData.register(HANDLER, stable);
      return new FactoryUpgradeItemData.Update(data);
    });
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STALE,
        "Self-replacing preparer committed an old result");
    assertUnpaid(helper, fixture, 2);
    FactoryUpgradeItemData.register(HANDLER, (context, upgrade, data) -> {
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      return new FactoryUpgradeItemData.Update(data);
    });
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STALE,
        "Resource mutation inside a preparer retained a startup proof");
    assertUnpaid(helper, fixture, 1);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    FactoryUpgradeItemData.register(HANDLER, (context, upgrade, data) -> null);
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.CHECK_ERROR,
        "Null data result was accepted");
    assertUnpaid(helper, fixture, 2);
    FactoryUpgradeItemData.register(HANDLER, stable);
    synchronize(helper, fixture);
    verifyLateRollback(helper, fixture);
    var malformed = shard(3);
    var malformedData = malformed.get(DataComponents.CUSTOM_DATA).copyTag();
    malformedData.putInt(FactoryUpgradeItemData.DATA_KEY, 42);
    malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(malformedData));
    fixture.busA().inventory().setStackInSlot(0, malformed);
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.CHECK_ERROR
            && fixture.busA().inventory().getStackInSlot(0).get(DataComponents.CUSTOM_DATA).copyTag()
                .getInt(FactoryUpgradeItemData.DATA_KEY) == 42,
        "Malformed existing data was silently normalized or overwritten");
    fixture.busA().inventory().setStackInSlot(0, shard(3));
    FactoryUpgradeItemData.remove(HANDLER);
    fixture.busA().serverTick();
    fixture.busB().serverTick();
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.factory().scheduler().channels().getFirst().reason() == FactoryThreadState.Reason.CHECK_ERROR,
        "Removed preparer did not close required startup sources");
    FactoryUpgradeItemData.register(HANDLER, stable);
    synchronize(helper, fixture);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
            && uses(fixture.busA()) == 1
            && uses(fixture.busB()) == 1
            && fixture.busA().inventory().getStackInSlot(0).getCount() == 2,
        "A corrected preparer did not recover the atomic startup path");
    awaitOutput(helper, fixture, 1, 120, () -> helper.succeed());
  }

  private static void verifyLateRollback(GameTestHelper helper, Fixture fixture) {
    var prepared = FactoryUpgradeStartCosts.prepare(fixture.factory(), THREAD, RECIPE, 2);
    helper.assertTrue(
        prepared.advance(65_536).status() == FactoryUpgradeStartCosts.Status.READY,
        "Rollback fixture did not prepare data");
    var same = NonNullList.withSize(fixture.busB().inventory().getSlots(), ItemStack.EMPTY);
    for (int slot = 0; slot < same.size(); slot++) {
      same.set(slot, fixture.busB().inventory().getStackInSlot(slot).copy());
    }
    var held = fixture.busB()
        .acquireStartCost(fixture.factory(), fixture.busB().upgradeRevision(), fixture.busB().upgradeCatalog(), same);
    var leases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    try {
      try {
        prepared.acquireInto(leases);
        helper.fail("Busy data source accepted a second lease");
      } catch (IllegalStateException expected) {
        helper.assertTrue(leases.size() == 1, "Rollback fixture did not apply the first prepared source");
      }
    } finally {
      leases.forEach(lease -> lease.resolve(false));
      held.resolve(false);
    }
    FactoryUpgradeStartCosts.publish(fixture.factory(), leases);
    assertUnpaid(helper, fixture, 2);
  }

  private static void assertUnpaid(GameTestHelper helper, Fixture fixture, int input) {
    helper.assertTrue(
        fixture.input().inventory().getStackInSlot(0).getCount() == input
            && fixture.busA().inventory().getStackInSlot(0).getCount() == 3
            && uses(fixture.busA()) == 0
            && uses(fixture.busB()) == 0
            && fixture.factory().run(THREAD).isEmpty(),
        "Uncommitted startup changed owned data, upgrade quantity, inputs, or runs");
  }

  private static void begin(GameTestHelper helper, boolean observer, Consumer<Fixture> next) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Item data resource reload failed: " + failure);
            return;
          }
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          var echo = savedDefinitions.stream()
              .filter(definition -> definition.effect().id().equals(id("test_upgrade_item_data"))).findFirst()
              .orElseThrow();
          helper
              .assertTrue(HANDLER.equals(echo.startDataHandler()), "Startup data field was not loaded from resources");
          FactoryUpgradeItemData.remove(HANDLER);
          FactoryDynamicUpgrades.register(EFFECT, context -> {
            long uses = context.itemData().getCompound("components").getCompound("minecraft:custom_data")
                .getCompound(FactoryUpgradeItemData.DATA_KEY).getLong("uses");
            return new FactoryUpgradeEntry(
                context.baseEffect().id(),
                context.baseEffect().parallelAdd(),
                Math.toIntExact(Math.min(10L, uses + 1)),
                1,
                1,
                1,
                1,
                1);
          });
          var membrane = new FactoryUpgradeDefinition(
              ResourceLocation.fromNamespaceAndPath("minecraft", "phantom_membrane"),
              new FactoryUpgradeEntry(id("membrane_data"), 0, 1, 1, 1, 1, 1, 1),
              new FactoryUpgradeCompatibility(Set.of(MACHINE), Set.of()),
              null,
              null,
              observer ? OBSERVER : null,
              null,
              null,
              HANDLER);
          FactoryUpgradeService.replaceDefinitions(List.of(echo, membrane));
          var origin = helper.absolutePos(new BlockPos(7, 5, 7));
          var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
          helper.getLevel().setBlockAndUpdate(
              origin.offset(-1, 0, 1),
              MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
          helper.getLevel().setBlockAndUpdate(
              origin.offset(1, 0, 1),
              MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
          var busA = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
          var busB = UpgradeBusGameTests.placeBus(helper, origin.south().east(2), "normal");
          busA.inventory().setStackInSlot(0, shard(3));
          var item = new ItemStack(Items.PHANTOM_MEMBRANE);
          item.set(DataComponents.CUSTOM_DATA, foreignData());
          item.set(DataComponents.CUSTOM_NAME, Component.literal("Data B"));
          busB.inventory().setStackInSlot(0, item);
          var fixture = new Fixture(
              factory,
              busA,
              busB,
              (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(-1, 0, 1)),
              (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(1, 0, 1)));
          // Fresh port onLoad notifications must settle before a cross-tick ticket is
          // captured.
          helper.runAfterDelay(1, () -> {
            UpgradeBusGameTests.confirm(helper, factory);
            next.accept(fixture);
          });
        })));
  }

  private static Fixture restoreBus(GameTestHelper helper, Fixture fixture) {
    var level = helper.getLevel();
    var bus = fixture.busB();
    var saved = bus.saveWithFullMetadata(level.registryAccess());
    var position = bus.getBlockPos();
    var state = bus.getBlockState();
    level.removeBlockEntity(position);
    var restored = (UpgradeBusBlockEntity) BlockEntity.loadStatic(position, state, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();

    return new Fixture(fixture.factory(), fixture.busA(), restored, fixture.input(), fixture.output());
  }

  private static void synchronize(GameTestHelper helper, Fixture fixture) {
    fixture.busA().serverTick();
    fixture.busB().serverTick();
    if (fixture.factory().upgradeCallbackBuses().isEmpty()) {
      fixture.factory().serverTick();
    }
    helper.assertTrue(
        fixture.factory().beginRunStart(THREAD),
        "Item data fixture could not publish its upgrade snapshot");
    fixture.factory().finishRunStart(THREAD, false);
  }

  private static void tick(Fixture fixture) {
    fixture.busA().serverTick();
    fixture.busB().serverTick();
    fixture.factory().serverTick();
  }

  private static long uses(UpgradeBusBlockEntity bus) {
    return FactoryUpgradeItemData.read(bus.inventory().getStackInSlot(0)).getLong("uses");
  }

  private static ItemRecipePlan plan(Fixture fixture) {
    return ItemRecipeSearch.begin(fixture.factory(), (Set<String>) null).orElseThrow().advance(65_536).plan()
        .orElseThrow();
  }

  private static void awaitOutput(GameTestHelper helper, Fixture fixture, int expected, int remaining, Runnable complete) {
    helper.runAfterDelay(1, () -> {
      tick(fixture);
      int count = 0;
      for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
        var stack = fixture.output().inventory().getStackInSlot(slot);
        if (stack.is(Items.IRON_INGOT)) {
          count += stack.getCount();
        }
      }
      if (fixture.factory().run(THREAD).isEmpty() && count == expected) {
        complete.run();
      } else if (remaining == 0) {
        helper.fail("Item data paid runs did not complete exactly once: " + count);
      } else {
        awaitOutput(helper, fixture, expected, remaining - 1, complete);
      }
    });
  }

  private static void clearOutput(Fixture fixture) {
    for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
      fixture.output().inventory().setStackInSlot(slot, ItemStack.EMPTY);
    }
  }

  private static ItemStack shard(int count) {
    var stack = new ItemStack(Items.ECHO_SHARD, count);
    stack.set(DataComponents.CUSTOM_DATA, foreignData());
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Data A"));

    return stack;
  }

  private static CustomData foreignData() {
    var data = new CompoundTag();
    data.putString("foreign", "NA");
    data.putInt("sentinel", 7);

    return CustomData.of(data);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
