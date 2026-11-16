package com.lw.modularmachine.gametest;

import com.lw.modularmachine.machine.upgrade.UpgradeBusDataHandlers;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.machine.upgrade.UpgradeCompatibility;
import com.lw.modularmachine.machine.upgrade.UpgradeDefinition;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.machine.upgrade.UpgradeCatalogService;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeBusDataGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_start_costs");
  private static final ResourceLocation RECIPE = id("mmcece/test_upgrade_start_costs");
  private static final ResourceLocation DEFINITION_A = id("test_upgrade_bus_data");
  private static final ResourceLocation DEFINITION_B = id("test_bus_ink_data");
  private static final ResourceLocation BUS_HANDLER = id("test_upgrade_bus_data");
  private static final ResourceLocation ITEM_HANDLER = id("test_bus_item_data");
  private static final ResourceLocation EFFECT = id("test_bus_data_effect");
  private static final ResourceLocation OBSERVER = id("test_bus_data_observer");
  private static final String THREAD = "upgrade_thread";
  private static List<UpgradeDefinition> savedDefinitions;
  private static Integer savedBudget;

  private static final class Stats {
    private int items;
    private final List<UpgradeBusData.Context> groups = new ArrayList<>();
    private final List<Long> observations = new ArrayList<>();
    private CompoundTag retained;
    private Fixture current;
  }

  private record Fixture(
      FactoryControllerBlockEntity factory,
      UpgradeBusBlockEntity a,
      UpgradeBusBlockEntity b,
      MachinePortBlockEntity input,
      MachinePortBlockEntity output,
      Stats stats) {
  }

  @AfterBatch(batch = "upgrade_bus_data")
  public static void cleanupBusData(ServerLevel level) {
    cleanup();
  }

  @AfterBatch(batch = "upgrade_bus_data_failures")
  public static void cleanupFailures(ServerLevel level) {
    cleanup();
  }

  private static void cleanup() {
    UpgradeBusData.remove(BUS_HANDLER);
    UpgradeItemData.remove(ITEM_HANDLER);
    DynamicUpgrades.remove(EFFECT);
    UpgradeEvents.remove(OBSERVER);
    if (savedBudget != null) {
      MachineConfig.RECIPE_OUTPUT_WORK.set(savedBudget);
      savedBudget = null;
    }
    if (savedDefinitions != null) {
      UpgradeCatalogService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_data", timeoutTicks = 1000)
  public static void grouped_bus_data_merges_with_items_costs_and_persists_without_the_item(GameTestHelper helper) {
    begin(helper, true, fixture -> verifySuccess(helper, fixture));
  }

  private static void verifySuccess(GameTestHelper helper, Fixture fixture) {
    var stats = fixture.stats();
    UpgradeEvents.register(OBSERVER, (context, upgrade) -> {
      if (context.type() == UpgradeEvents.Type.STARTED) {
        long value = stats.current.b().upgradeCustomData(DEFINITION_B).getLong("runs");
        helper.assertTrue(
            upgrade.busData().getLong("runs") == value && stats.current.input().inventory().getStackInSlot(0).isEmpty(),
            "Observer saw a partial bus/inventory commit");
        stats.observations.add(value);
      }
    });
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.factory().scheduler().channels().getFirst().reason() == FactoryThreadState.Reason.CHECK_ERROR,
        "Missing bus data handler did not close startup eligibility");
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    UpgradeBusData.register(BUS_HANDLER, (context, data) -> {
      stats.groups.add(context);
      helper.assertTrue(
          context.startup().recipeId().equals(RECIPE)
              && context.startup().threadId().equals(THREAD)
              && UpgradeLifecyclePublisher.isDispatching(fixture.factory()),
          "Bus data preparation lost its batch or reentry guard");
      data.putLong("runs", Math.addExact(data.getLong("runs"), context.startup().instances()));
      data.putInt("source_slots", context.upgrades().size());
      stats.retained = data;

      return new UpgradeBusData.Update(data);
    });
    synchronize(helper, fixture);
    for (int attempt = 0; attempt < 100; attempt++) {
      tick(fixture);
      UpgradeBusPayloads.capture(7, fixture.a());
    }
    helper.assertTrue(stats.items == 0 && stats.groups.isEmpty(), "Idle/menu refresh ran startup data handlers");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    helper.assertTrue(
        FactoryParallelStarter.commit(
            fixture.factory(),
            THREAD,
            Collections.nCopies(3, plan(fixture))) == FactoryParallelStarter.Result.UPGRADE_COST_MISSING
            && stats.groups.isEmpty(),
        "Unpayable costs executed grouped bus preparation");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(fixture);
    long revisionA = fixture.a().upgradeRevision();
    long revisionB = fixture.b().upgradeRevision();
    savedBudget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    MachineConfig.RECIPE_OUTPUT_WORK.set(1);
    helper.assertTrue(
        FactoryParallelStarter.commit(
            fixture.factory(),
            THREAD,
            Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.CHECK_PENDING
            && stats.items == 1
            && stats.groups.isEmpty(),
        "Item preparation did not precede grouped bus work");
    assertOriginal(helper, fixture, 2);
    helper.assertTrue(
        FactoryParallelStarter.commit(
            fixture.factory(),
            THREAD,
            Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.CHECK_PENDING
            && stats.items == 1
            && stats.groups.size() == 1
            && stats.groups.getFirst().upgrades().size() == 2
            && stats.groups.getFirst().definitionId().equals(DEFINITION_A),
        "Repeated slots did not produce exactly one definition group");
    stats.retained.putLong("runs", 999);
    assertOriginal(helper, fixture, 2);
    helper.runAfterDelay(1, () -> {
      try {
        helper.assertTrue(
            FactoryParallelStarter.commit(
                fixture.factory(),
                THREAD,
                Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.STARTED
                && stats.items == 1
                && stats.groups.size() == 2,
            "Grouped preparation did not resume across ticks at the remaining bus");
      } finally {
        MachineConfig.RECIPE_OUTPUT_WORK.set(savedBudget);
        savedBudget = null;
      }
      stats.retained.putLong("runs", 999);
      helper.assertTrue(
          runs(fixture.a(), DEFINITION_A) == 2
              && runs(fixture.b(), DEFINITION_B) == 2
              && fixture.a().inventory().getStackInSlot(0).isEmpty()
              && fixture.a().inventory().getStackInSlot(1).getCount() == 1
              && itemUses(fixture.a().inventory().getStackInSlot(1)) == 2
              && fixture.a().upgradeRevision() == revisionA + 1
              && fixture.b().upgradeRevision() == revisionB + 1
              && fixture.factory().runSet(THREAD).runs().stream().allMatch(run -> run.duration() == 20)
              && stats.observations.equals(List.of(2L)),
          "Bus state, item data, costs, or frozen effects were not one atomic batch");
      fixture.a().upgradeCustomData(DEFINITION_A).putLong("runs", 1000);
      helper.assertTrue(runs(fixture.a(), DEFINITION_A) == 2, "Bus query exposed a mutable stored tag");
      var restored = restoreA(helper, fixture);
      stats.current = restored;
      helper.assertTrue(
          runs(restored.a(), DEFINITION_A) == 2 && restored.a().upgradeRevision() == revisionA + 1,
          "Bus restoration lost its grouped data or replayed a commit");
      awaitOutput(helper, restored, 2, 140, () -> {
        helper
            .assertTrue(stats.items == 1 && stats.groups.size() == 2, "Paid runs or BE restoration repeated preparers");
        verifyRetentionAndMoving(helper, restored);
      });
    });
  }

  private static void verifyRetentionAndMoving(GameTestHelper helper, Fixture fixture) {
    clearOutput(fixture);
    var moved = fixture.a().inventory().getStackInSlot(1).copy();
    fixture.a().inventory().setStackInSlot(1, ItemStack.EMPTY);
    fixture.b().inventory().setStackInSlot(0, moved);
    synchronize(helper, fixture);
    helper.assertTrue(
        runs(fixture.a(), DEFINITION_A) == 2
            && runs(fixture.b(), DEFINITION_A) == 0
            && runs(fixture.b(), DEFINITION_B) == 2
            && itemUses(moved) == 2,
        "Moving an item moved bus-owned data or deleted retained definition data");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
            && fixture.b().inventory().getStackInSlot(0).isEmpty()
            && runs(fixture.b(), DEFINITION_A) == 1
            && runs(fixture.a(), DEFINITION_A) == 2
            && fixture.stats().groups.size() == 3
            && fixture.stats().items == 1,
        "A fully consumed item did not record its bus data once or replayed item data");
    awaitOutput(helper, fixture, 1, 140, () -> verifyNoopAndClear(helper, fixture));
  }

  private static void verifyNoopAndClear(GameTestHelper helper, Fixture fixture) {
    clearOutput(fixture);
    fixture.b().inventory().setStackInSlot(0, new ItemStack(Items.INK_SAC));
    UpgradeBusData.register(BUS_HANDLER, (context, data) -> new UpgradeBusData.Update(data));
    synchronize(helper, fixture);
    long before = fixture.b().upgradeRevision();
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
            && fixture.b().upgradeRevision() == before
            && runs(fixture.b(), DEFINITION_B) == 2,
        "No-op bus data generated an unnecessary inventory revision");
    awaitOutput(helper, fixture, 1, 140, () -> {
      clearOutput(fixture);
      UpgradeBusData.register(BUS_HANDLER, (context, data) -> new UpgradeBusData.Update(new CompoundTag()));
      synchronize(helper, fixture);
      long revision = fixture.b().upgradeRevision();
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      helper.assertTrue(
          FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
              && fixture.b().upgradeRevision() == revision + 1
              && runs(fixture.b(), DEFINITION_B) == 0
              && runs(fixture.b(), DEFINITION_A) == 1
              && runs(fixture.a(), DEFINITION_A) == 2
              && fixture.stats().observations.equals(List.of(2L, 2L, 0L)),
          "Clearing one definition changed sibling data or preceded the committed observer state");
      awaitOutput(helper, fixture, 1, 140, () -> helper.succeed());
    });
  }

  @GameTest(template = "empty16", batch = "upgrade_bus_data_failures", timeoutTicks = 900)
  public static void late_bus_failures_revert_all_fields_and_invalid_saved_data_stays_read_only(GameTestHelper helper) {
    begin(helper, false, fixture -> verifyFailures(helper, fixture));
  }

  private static void verifyFailures(GameTestHelper helper, Fixture fixture) {
    var stats = fixture.stats();
    UpgradeBusData.Preparer failing = (context, data) -> {
      stats.groups.add(context);
      if (context.definitionId().equals(DEFINITION_B)) {
        throw new IllegalStateException("expected later bus data failure");
      }
      data.putLong("runs", context.startup().instances());
      return new UpgradeBusData.Update(data);
    };
    UpgradeBusData.register(BUS_HANDLER, failing);
    var snapshot = UpgradeCatalogService.snapshot();
    UpgradeBusData.register(BUS_HANDLER, failing);
    UpgradeBusData.remove(id("unknown_bus_data_handler"));
    boolean rejected = CompletableFuture.supplyAsync(() -> {
      try {
        UpgradeBusData.register(BUS_HANDLER, failing);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        rejected && UpgradeCatalogService.snapshot() == snapshot,
        "Worker or no-op registration changed bus providers");
    synchronize(helper, fixture);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(fixture);
    long revisionA = fixture.a().upgradeRevision();
    long revisionB = fixture.b().upgradeRevision();
    for (int attempt = 0; attempt < 100; attempt++) {
      helper.assertTrue(
          FactoryParallelStarter.commit(
              fixture.factory(),
              THREAD,
              Collections.nCopies(2, plan)) == FactoryParallelStarter.Result.CHECK_ERROR,
          "A failed bus preparation was not cached");
    }
    helper.assertTrue(
        stats.items == 1
            && stats.groups.size() == 2
            && fixture.a().upgradeRevision() == revisionA
            && fixture.b().upgradeRevision() == revisionB,
        "A later bus failure published earlier prepared fields or repeated callbacks");
    assertOriginal(helper, fixture, 2);
    UpgradeBusData.Preparer stable = (context, data) -> {
      data.putLong("runs", Math.addExact(data.getLong("runs"), context.startup().instances()));
      return new UpgradeBusData.Update(data);
    };
    UpgradeBusData.register(BUS_HANDLER, stable);
    synchronize(helper, fixture);
    verifyBusyRollback(helper, fixture);
    var payload = new CompoundTag();
    payload.putByteArray("payload", new byte[8000]);
    var value = new UpgradeBusData.Update(payload);
    var history = UpgradeBusDataHandlers.State.empty();
    for (int index = 0; index < 8; index++) {
      history = history.with(id("history_" + index), value);
    }
    seed(fixture, fixture.a(), history);
    UpgradeBusData.register(BUS_HANDLER, (context, data) -> value);
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.CHECK_ERROR
            && fixture.a().busDataSnapshot().save().size() == 8
            && fixture.a().inventory().getStackInSlot(0).getCount() == 2
            && fixture.a().inventory().getStackInSlot(1).getCount() == 3
            && fixture.input().inventory().getStackInSlot(0).getCount() == 2,
        "Aggregate bus data overflow committed a partial value or startup cost");
    seed(fixture, fixture.a(), UpgradeBusDataHandlers.State.empty());
    UpgradeBusData.register(BUS_HANDLER, (context, data) -> {
      UpgradeBusData.register(BUS_HANDLER, stable);
      return new UpgradeBusData.Update(data);
    });
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STALE,
        "Self-replacing bus preparer committed an old result");
    assertOriginal(helper, fixture, 2);
    UpgradeBusData.register(BUS_HANDLER, (context, data) -> {
      try {
        fixture.a().inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND));
        helper.fail("Bus preparer wrote a live inventory");
      } catch (IllegalStateException expected) {
      }
      helper.assertTrue(!fixture.factory().runState().beginRunStart(THREAD), "Bus preparer reentered production");
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      return new UpgradeBusData.Update(data);
    });
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STALE,
        "External resource changes retained a bus data startup proof");
    assertOriginal(helper, fixture, 1);
    verifyInvalidSave(helper);
    UpgradeBusData.register(BUS_HANDLER, stable);
    synchronize(helper, fixture);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan(fixture)) == FactoryRecipeStarter.Result.STARTED
            && runs(fixture.a(), DEFINITION_A) == 1
            && runs(fixture.b(), DEFINITION_B) == 1,
        "A corrected bus preparer did not recover single startup");
    awaitOutput(helper, fixture, 1, 140, () -> helper.succeed());
  }

  private static void verifyBusyRollback(GameTestHelper helper, Fixture fixture) {
    var prepared = UpgradeStartCosts.prepare(fixture.factory(), THREAD, RECIPE, 2);
    helper.assertTrue(
        prepared.advance(65_536).status() == UpgradeStartCosts.Status.READY,
        "Bus rollback preparation failed");
    var held = fixture.b().acquireStartup(
        fixture.factory(),
        fixture.b().upgradeRevision(),
        fixture.b().upgradeCatalog(),
        contents(fixture.b()),
        fixture.b().busDataSnapshot());
    var leases = new ArrayList<UpgradeBusBlockEntity.StartCostLease>();
    try {
      try {
        prepared.acquireInto(leases);
        helper.fail("Busy bus data source accepted a second lease");
      } catch (IllegalStateException expected) {
        helper.assertTrue(leases.size() == 1, "Bus rollback fixture did not reach the earlier source");
        try {
          fixture.a().upgradeCustomData(DEFINITION_A);
          helper.fail("Query observed uncommitted bus data");
        } catch (IllegalStateException unavailable) {
        }
      }
    } finally {
      leases.forEach(lease -> lease.resolve(false));
      held.resolve(false);
    }
    UpgradeStartCosts.publish(fixture.factory(), leases);
    assertOriginal(helper, fixture, 2);
  }

  private static void verifyInvalidSave(GameTestHelper helper) {
    var position = helper.absolutePos(new BlockPos(13, 5, 7));
    var bus = UpgradeBusGameTests.placeBus(helper, position, "normal");
    var saved = bus.saveWithFullMetadata(helper.getLevel().registryAccess());
    var invalid = new CompoundTag();
    invalid.putString(DEFINITION_A.toString(), "NA");
    saved.put(UpgradeBusDataHandlers.NBT_KEY, invalid);
    var state = bus.getBlockState();
    helper.getLevel().removeBlockEntity(position);
    var loaded = (UpgradeBusBlockEntity) BlockEntity
        .loadStatic(position, state, saved, helper.getLevel().registryAccess());
    helper.getLevel().setBlockEntity(loaded);
    loaded.onLoad();
    helper.assertTrue(
        !loaded.schemaSupported()
            && loaded.saveWithFullMetadata(helper.getLevel().registryAccess())
                .getCompound(UpgradeBusDataHandlers.NBT_KEY).equals(invalid),
        "Malformed saved bus data was normalized or discarded");
    try {
      loaded.inventory().setStackInSlot(0, new ItemStack(Items.STONE));
      helper.fail("Invalid bus data did not protect inventory from writes");
    } catch (IllegalStateException expected) {
    }
  }

  private static void seed(Fixture fixture, UpgradeBusBlockEntity bus, UpgradeBusDataHandlers.State state) {
    var lease = bus
        .acquireStartup(fixture.factory(), bus.upgradeRevision(), bus.upgradeCatalog(), contents(bus), state);
    lease.resolve(true);
    UpgradeStartCosts.publish(fixture.factory(), List.of(lease));
  }

  private static NonNullList<ItemStack> contents(UpgradeBusBlockEntity bus) {
    var values = NonNullList.withSize(bus.inventory().getSlots(), ItemStack.EMPTY);
    for (int slot = 0; slot < values.size(); slot++) {
      values.set(slot, bus.inventory().getStackInSlot(slot).copy());
    }

    return values;
  }

  private static void assertOriginal(GameTestHelper helper, Fixture fixture, int input) {
    helper.assertTrue(
        fixture.input().inventory().getStackInSlot(0).getCount() == input
            && fixture.a().inventory().getStackInSlot(0).getCount() == 2
            && fixture.a().inventory().getStackInSlot(1).getCount() == 3
            && itemUses(fixture.a().inventory().getStackInSlot(1)) == 0
            && fixture.a().busDataSnapshot().isEmpty()
            && fixture.b().busDataSnapshot().isEmpty()
            && fixture.factory().run(THREAD).isEmpty(),
        "Uncommitted bus startup changed data, costs, inputs, or runs");
  }

  private static void begin(GameTestHelper helper, boolean observer, Consumer<Fixture> next) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Bus data resource reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = UpgradeCatalogService.snapshot().definitions();
          var definition = savedDefinitions.stream().filter(value -> value.effect().id().equals(DEFINITION_A))
              .findFirst().orElseThrow();
          helper.assertTrue(
              BUS_HANDLER.equals(definition.startBusDataHandler()),
              "Bus data handler field was not loaded");
          UpgradeBusData.remove(BUS_HANDLER);
          var stats = new Stats();
          UpgradeItemData.register(ITEM_HANDLER, (context, upgrade, data) -> {
            stats.items++;
            data.putLong("uses", Math.addExact(data.getLong("uses"), context.instances()));
            return new UpgradeItemData.Update(data);
          });
          DynamicUpgrades.register(EFFECT, context -> {
            var busData = context.busData();
            long count = busData.getLong("runs");
            busData.putLong("runs", 999);
            return new UpgradeEntry(
                context.baseEffect().id(),
                context.baseEffect().parallelAdd(),
                Math.toIntExact(Math.max(1L, Math.min(10L, count + 1))),
                1,
                1,
                1,
                1,
                1);
          });
          var ink = new UpgradeDefinition(
              ResourceLocation.fromNamespaceAndPath("minecraft", "ink_sac"),
              new UpgradeEntry(DEFINITION_B, 0, 1, 1, 1, 1, 1, 1),
              new UpgradeCompatibility(Set.of(MACHINE), Set.of()),
              null,
              null,
              observer ? OBSERVER : null,
              null,
              null,
              null,
              BUS_HANDLER);
          UpgradeCatalogService.replaceDefinitions(List.of(definition, ink));
          var origin = helper.absolutePos(new BlockPos(7, 5, 7));
          var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
          helper.getLevel().setBlockAndUpdate(
              origin.offset(-1, 0, 1),
              MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
          helper.getLevel().setBlockAndUpdate(
              origin.offset(1, 0, 1),
              MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
          var a = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
          var b = UpgradeBusGameTests.placeBus(helper, origin.south().east(2), "normal");
          var marked = new ItemStack(Items.GLOW_INK_SAC, 2);
          var explicit = new CompoundTag();
          explicit.putBoolean("mmcece:factory_upgrade", true);
          marked.set(DataComponents.CUSTOM_DATA, CustomData.of(explicit));
          a.inventory().setStackInSlot(0, marked);
          a.inventory().setStackInSlot(1, new ItemStack(Items.GLOW_INK_SAC, 3));
          b.inventory().setStackInSlot(0, new ItemStack(Items.INK_SAC));
          var fixture = new Fixture(
              factory,
              a,
              b,
              (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(-1, 0, 1)),
              (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(1, 0, 1)),
              stats);
          stats.current = fixture;
          helper.runAfterDelay(1, () -> {
            UpgradeBusGameTests.confirm(helper, factory);
            next.accept(fixture);
          });
        })));
  }

  private static Fixture restoreA(GameTestHelper helper, Fixture fixture) {
    var bus = fixture.a();
    var saved = bus.saveWithFullMetadata(helper.getLevel().registryAccess());
    var position = bus.getBlockPos();
    var state = bus.getBlockState();
    helper.getLevel().removeBlockEntity(position);
    var restored = (UpgradeBusBlockEntity) BlockEntity
        .loadStatic(position, state, saved, helper.getLevel().registryAccess());
    helper.getLevel().setBlockEntity(restored);
    restored.onLoad();

    return new Fixture(fixture.factory(), restored, fixture.b(), fixture.input(), fixture.output(), fixture.stats());
  }

  private static void synchronize(GameTestHelper helper, Fixture fixture) {
    fixture.a().serverTick();
    fixture.b().serverTick();
    if (fixture.factory().upgradeCallbackBuses().isEmpty()) {
      fixture.factory().serverTick();
    }
    helper
        .assertTrue(fixture.factory().runState().beginRunStart(THREAD), "Bus data fixture could not publish upgrades");
    fixture.factory().runState().finishRunStart(THREAD, false);
  }

  private static void tick(Fixture fixture) {
    fixture.a().serverTick();
    fixture.b().serverTick();
    fixture.factory().serverTick();
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
        helper.fail("Bus data paid runs did not complete exactly once: " + count);
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

  private static long runs(UpgradeBusBlockEntity bus, ResourceLocation definition) {
    return bus.upgradeCustomData(definition).getLong("runs");
  }

  private static long itemUses(ItemStack stack) {
    return UpgradeItemData.read(stack).getLong("uses");
  }

  private static RecipeStartPlan plan(Fixture fixture) {
    return RecipeSearch.begin(fixture.factory(), (Set<String>) null).orElseThrow().advance(65_536).plan().orElseThrow();
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
