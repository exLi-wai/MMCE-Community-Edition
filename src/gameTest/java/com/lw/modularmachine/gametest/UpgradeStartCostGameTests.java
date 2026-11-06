package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCost;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.ItemRecipePlan;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.Unbreakable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeStartCostGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_start_costs");
  private static final ResourceLocation OBSERVER = id("start_cost_observer");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;
  private static List<FactoryUpgradeDefinition> definitions;

  private record Fixture(
      FactoryControllerBlockEntity factory,
      UpgradeBusBlockEntity busA,
      UpgradeBusBlockEntity busB,
      MachinePortBlockEntity input,
      MachinePortBlockEntity output) {
  }

  @AfterBatch(batch = "upgrade_start_costs")
  public static void cleanup(ServerLevel level) {
    FactoryUpgradeEvents.remove(OBSERVER);
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
    definitions = null;
  }

  @GameTest(template = "empty16", batch = "upgrade_start_costs", timeoutTicks = 900)
  public static void start_costs_are_atomic_bounded_persistent_and_frozen_per_paid_batch(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Start cost resource reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          var current = new AtomicReference<Fixture>();
          var observations = new AtomicInteger();
          FactoryUpgradeEvents.register(OBSERVER, (context, upgrade) -> {
            if (context.type() == FactoryUpgradeEvents.Type.STARTED) {
              var fixture = current.get();
              helper.assertTrue(
                  fixture.input().inventory().getStackInSlot(0).isEmpty()
                      && fixture.busA().inventory().getStackInSlot(0).isEmpty()
                      && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 14
                      && context.runs().size() == 2
                      && upgrade.itemData().getCompound("components").getInt("minecraft:damage") == 14,
                  "Cost notification ran before every inventory resolved or used an old item snapshot");
              observations.incrementAndGet();
            }
          });
          definitions = List.of(
              definition("sugar", 1, 1, null, new FactoryUpgradeStartCost(0, 1)),
              definition("iron_pickaxe", 1, 1, OBSERVER, new FactoryUpgradeStartCost(2, 0)));
          FactoryUpgradeService.replaceDefinitions(definitions);
          var fixture = build(helper);
          current.set(fixture);
          verifyArithmetic(helper);
          verifyTransactions(helper, fixture);
          beginPaidBatches(helper, fixture, current, observations);
        })));
  }

  private static void verifyArithmetic(GameTestHelper helper) {
    var cost = new FactoryUpgradeStartCost(2, 0);
    var tool = tool(247);
    helper.assertTrue(
        cost.maxRuns(tool) == 2 && cost.apply(tool, 2).isEmpty() && tool.getDamageValue() == 247,
        "Final durability payment overran the number of available uses or mutated its input");
    try {
      cost.apply(tool, 3);
      helper.fail("Broken upgrade paid an extra parallel run");
    } catch (IllegalArgumentException expected) {
    }
    var huge = tool(0);
    huge.set(DataComponents.MAX_DAMAGE, Integer.MAX_VALUE);
    var unit = new FactoryUpgradeStartCost(1, 0);
    helper.assertTrue(
        unit.maxRuns(huge) == Integer.MAX_VALUE && unit.apply(huge, Integer.MAX_VALUE).isEmpty(),
        "Durability arithmetic overflowed or introduced a fixed parallel cap");
    huge.set(DataComponents.UNBREAKABLE, new Unbreakable(true));
    helper.assertTrue(
        unit.maxRuns(huge) == 0 && cost.maxRuns(new ItemStack(Items.SUGAR)) == 0,
        "Unbreakable or non-damageable upgrades supplied free durability");
  }

  private static void verifyTransactions(GameTestHelper helper, Fixture fixture) {
    fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 4));
    fixture.busB().inventory().setStackInSlot(0, tool(10));
    synchronize(helper, fixture);
    long revisionA = fixture.busA().upgradeRevision();
    long revisionB = fixture.busB().upgradeRevision();
    var leases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    var prepared = FactoryUpgradeStartCosts.prepare(fixture.factory(), 2);
    try {
      prepared.acquireInto(leases);
      helper.assertTrue(
          leases.size() == 2 && !fixture.busA().upgradeCatalogValid(),
          "Cost preparation did not lock both sources");
      try {
        fixture.busA().inventory().getStackInSlot(0);
        helper.fail("Inventory read observed a half-applied cost transaction");
      } catch (IllegalStateException expected) {
      }
    } finally {
      leases.forEach(lease -> lease.resolve(false));
    }
    FactoryUpgradeStartCosts.publish(fixture.factory(), leases);
    helper.assertTrue(
        fixture.busA().upgradeRevision() == revisionA
            && fixture.busB().upgradeRevision() == revisionB
            && fixture.busA().inventory().getStackInSlot(0).getCount() == 4
            && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 10,
        "Rolling back costs changed items or published a new revision");
    leases.clear();
    FactoryUpgradeStartCosts.prepare(fixture.factory(), 2).acquireInto(leases);
    leases.forEach(lease -> lease.resolve(true));
    FactoryUpgradeStartCosts.publish(fixture.factory(), leases);
    FactoryUpgradeStartCosts.publish(fixture.factory(), leases);
    helper.assertTrue(
        fixture.busA().upgradeRevision() == revisionA + 1
            && fixture.busB().upgradeRevision() == revisionB + 1
            && fixture.busA().inventory().getStackInSlot(0).getCount() == 2
            && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 14,
        "Cost commit repeated revision publication or lost a source payment");

    var partial = FactoryUpgradeStartCosts.prepare(fixture.factory(), 1);
    var same = NonNullList.withSize(fixture.busB().inventory().getSlots(), ItemStack.EMPTY);
    for (int slot = 0; slot < same.size(); slot++) {
      same.set(slot, fixture.busB().inventory().getStackInSlot(slot).copy());
    }
    var held = fixture.busB()
        .acquireStartCost(fixture.factory(), fixture.busB().upgradeRevision(), fixture.busB().upgradeCatalog(), same);
    leases.clear();
    try {
      try {
        partial.acquireInto(leases);
        helper.fail("Cost acquisition ignored a busy second source");
      } catch (IllegalStateException expected) {
        helper.assertTrue(leases.size() == 1, "Partial acquisition fixture did not reach the first source");
      }
    } finally {
      leases.forEach(lease -> lease.resolve(false));
      held.resolve(false);
    }
    helper.assertTrue(
        fixture.busA().inventory().getStackInSlot(0).getCount() == 2
            && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 14,
        "Failure at a later cost source did not roll back the first");
    var stale = FactoryUpgradeStartCosts.prepare(fixture.factory(), 1);
    FactoryUpgradeService.replaceDefinitions(definitions);
    helper.assertTrue(!stale.current(), "Definition reload retained a prepared cost payment");
    try {
      stale.acquireInto(new ArrayList<>());
      helper.fail("Stale cost plan acquired inventory");
    } catch (IllegalStateException expected) {
    }
    fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR));
    fixture.busB().inventory().setStackInSlot(0, tool(10));
    synchronize(helper, fixture);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(fixture);
    helper.assertTrue(
        FactoryParallelStarter.commit(
            fixture.factory(),
            THREAD,
            Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.UPGRADE_COST_MISSING
            && fixture.input().inventory().getStackInSlot(0).getCount() == 2
            && fixture.busA().inventory().getStackInSlot(0).getCount() == 1
            && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 10,
        "Direct over-budget batch consumed inputs or another affordable source");
    var invalid = new ItemStack(Items.STONE);
    var data = new CompoundTag();
    data.putBoolean("mmcece:factory_upgrade", true);
    data.putInt("parallel_add", -1);
    invalid.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    fixture.busA().inventory().setStackInSlot(1, invalid);
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryUpgradeStartCosts.maxRuns(fixture.factory()) == 0
            && FactoryRecipeStarter
                .start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.UPGRADE_COST_MISSING
            && fixture.input().inventory().getStackInSlot(0).getCount() == 2,
        "An invalid but bound cost source disappeared into an unpaid identity fallback");
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    fixture.busA().inventory().setStackInSlot(1, ItemStack.EMPTY);
  }

  private static void beginPaidBatches(GameTestHelper helper, Fixture fixture, AtomicReference<Fixture> current, AtomicInteger observations) {
    fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 2));
    fixture.busB().inventory().setStackInSlot(0, tool(10));
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    awaitBatch(helper, fixture, 2, 120, () -> {
      helper.assertTrue(
          fixture.busA().inventory().getStackInSlot(0).isEmpty()
              && fixture.busB().inventory().getStackInSlot(0).getDamageValue() == 14
              && fixture.busB().inventory().getStackInSlot(0).get(DataComponents.CUSTOM_DATA).copyTag()
                  .getInt("sentinel") == 7
              && observations.get() == 1,
          "Paid batch did not preserve components, aggregate costs, or notify after commit");
      var paidIds = fixture.factory().runSet(THREAD).runs().stream().map(run -> run.runId()).toList();
      long revision = fixture.busB().upgradeRevision();
      var saved = fixture.busB().saveWithFullMetadata(helper.getLevel().registryAccess());
      var position = fixture.busB().getBlockPos();
      var state = fixture.busB().getBlockState();
      helper.getLevel().removeBlockEntity(position);
      var bus = (UpgradeBusBlockEntity) BlockEntity
          .loadStatic(position, state, saved, helper.getLevel().registryAccess());
      helper.getLevel().setBlockEntity(bus);
      bus.onLoad();
      var restored = new Fixture(fixture.factory(), fixture.busA(), bus, fixture.input(), fixture.output());
      current.set(restored);
      helper.assertTrue(
          bus.upgradeRevision() == revision
              && bus.inventory().getStackInSlot(0).getDamageValue() == 14
              && restored.factory().runSet(THREAD).runs().stream().map(run -> run.runId()).toList().equals(paidIds),
          "Upgrade BE restore replayed costs or changed paid run identities");
      awaitOutput(helper, restored, 2, 160, () -> beginLastDurability(helper, restored));
    });
  }

  private static void beginLastDurability(GameTestHelper helper, Fixture fixture) {
    clearOutput(fixture);
    fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 5));
    fixture.busB().inventory().setStackInSlot(0, tool(247));
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    awaitBatch(helper, fixture, 2, 120, () -> {
      helper.assertTrue(
          fixture.busB().inventory().getStackInSlot(0).isEmpty()
              && fixture.busA().inventory().getStackInSlot(0).getCount() == 3
              && fixture.input().inventory().getStackInSlot(0).getCount() == 1,
          "Automatic batch exceeded the final available durability payments");
      fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
      awaitOutput(helper, fixture, 2, 160, () -> {
        clearOutput(fixture);
        fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR));
        fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
        awaitBatch(helper, fixture, 1, 120, () -> {
          helper.assertTrue(
              fixture.busA().inventory().getStackInSlot(0).isEmpty()
                  && fixture.input().inventory().getStackInSlot(0).getCount() == 1,
              "Automatic batch did not shrink to the affordable item count");
          fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
          awaitOutput(helper, fixture, 1, 160, () -> beginSharedThreads(helper, fixture));
        });
      });
    });
  }

  private static void beginSharedThreads(GameTestHelper helper, Fixture fixture) {
    clearOutput(fixture);
    definitions = List.of(definition("sugar", 0, 2, null, new FactoryUpgradeStartCost(0, 1)));
    FactoryUpgradeService.replaceDefinitions(definitions);
    fixture.factory().replaceThreads(List.of(thread("alpha"), thread("beta")));
    fixture.busA().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 2));
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    awaitSharedThreads(helper, fixture, new HashMap<>(), 120);
  }

  private static void awaitSharedThreads(GameTestHelper helper, Fixture fixture, Map<String, Integer> durations, int remaining) {
    helper.runAfterDelay(1, () -> {
      tick(fixture);
      for (String thread : List.of("alpha", "beta")) {
        fixture.factory().run(thread).ifPresent(run -> durations.putIfAbsent(thread, run.duration()));
      }
      if (durations.size() == 2) {
        helper.assertTrue(
            durations.get("alpha") == 5
                && durations.get("beta") == 10
                && fixture.input().inventory().getStackInSlot(0).isEmpty()
                && fixture.busA().inventory().getStackInSlot(0).isEmpty(),
            "A later thread restored a stale upgrade snapshot after the first thread paid its cost");
        awaitOutput(helper, fixture, 2, 160, () -> helper.succeed());
      } else if (remaining == 0) {
        helper.fail("Shared upgrade cost threads did not start: " + durations);
      } else {
        awaitSharedThreads(helper, fixture, durations, remaining - 1);
      }
    });
  }

  private static Fixture build(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
    var level = helper.getLevel();
    level.setBlockAndUpdate(
        origin.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        origin.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var busA = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    var busB = UpgradeBusGameTests.placeBus(helper, origin.south().east(2), "normal");
    UpgradeBusGameTests.confirm(helper, factory);

    return new Fixture(
        factory,
        busA,
        busB,
        (MachinePortBlockEntity) level.getBlockEntity(origin.offset(-1, 0, 1)),
        (MachinePortBlockEntity) level.getBlockEntity(origin.offset(1, 0, 1)));
  }

  private static void tick(Fixture fixture) {
    fixture.busA().serverTick();
    fixture.busB().serverTick();
    fixture.factory().serverTick();
  }

  private static void synchronize(GameTestHelper helper, Fixture fixture) {
    fixture.busA().serverTick();
    fixture.busB().serverTick();
    if (fixture.factory().upgradeCallbackBuses().isEmpty()) {
      fixture.factory().serverTick();
    }
    helper.assertTrue(fixture.factory().beginRunStart(THREAD), "Could not synchronize upgrade cost fixture");
    fixture.factory().finishRunStart(THREAD, false);
  }

  private static void awaitBatch(GameTestHelper helper, Fixture fixture, int expected, int remaining, Runnable complete) {
    helper.runAfterDelay(1, () -> {
      tick(fixture);
      if (fixture.factory().runSet(THREAD).runs().size() == expected) {
        complete.run();
      } else if (remaining == 0) {
        helper
            .fail("Upgrade cost batch did not start: " + fixture.factory().scheduler().channels().getFirst().reason());
      } else {
        awaitBatch(helper, fixture, expected, remaining - 1, complete);
      }
    });
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
      boolean idle = fixture.factory().scheduler().channels().stream()
          .allMatch(thread -> fixture.factory().runSet(thread.threadId()).runs().isEmpty());
      if (idle && count == expected) {
        complete.run();
      } else if (remaining == 0) {
        helper.fail("Paid upgrade cost runs did not complete exactly once: " + count);
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

  private static ItemRecipePlan plan(Fixture fixture) {
    return ItemRecipeSearch.begin(fixture.factory(), (Set<String>) null).orElseThrow().advance(65_536).plan()
        .orElseThrow();
  }

  private static ItemStack tool(int damage) {
    var stack = new ItemStack(Items.IRON_PICKAXE);
    stack.setDamageValue(damage);
    var data = new CompoundTag();
    data.putInt("sentinel", 7);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));

    return stack;
  }

  private static FactoryThreadState thread(String name) {
    return new FactoryThreadState(
        name,
        MACHINE,
        null,
        true,
        0,
        1,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        FactoryUpgradeSnapshot.identity(0));
  }

  private static FactoryUpgradeDefinition definition(String item, int parallel, int speed, ResourceLocation observer, FactoryUpgradeStartCost cost) {
    return new FactoryUpgradeDefinition(
        ResourceLocation.fromNamespaceAndPath("minecraft", item),
        new FactoryUpgradeEntry(id("cost_" + item), parallel, speed, 1, 1, 1, 1, 1),
        new FactoryUpgradeCompatibility(Set.of(MACHINE), Set.of()),
        null,
        null,
        observer,
        null,
        cost);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
