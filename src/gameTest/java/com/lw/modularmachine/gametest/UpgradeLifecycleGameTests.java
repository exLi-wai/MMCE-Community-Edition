package com.lw.modularmachine.gametest;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.recipe.FactoryOutputEngine;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
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
public final class UpgradeLifecycleGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_compatibility_run");
  private static final ResourceLocation OBSERVER = id("test_upgrade_observer");
  private static final ResourceLocation FAILING = id("test_upgrade_failing_observer");
  private static final ResourceLocation HEALTHY = id("test_upgrade_healthy_observer");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  private record Fixture(
      FactoryControllerBlockEntity factory,
      UpgradeBusBlockEntity bus,
      MachinePortBlockEntity input,
      MachinePortBlockEntity output) {
  }

  @AfterBatch(batch = "upgrade_lifecycle_notifications")
  public static void cleanup(ServerLevel level) {
    FactoryUpgradeEvents.remove(OBSERVER);
    FactoryUpgradeEvents.remove(FAILING);
    FactoryUpgradeEvents.remove(HEALTHY);
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_lifecycle_notifications", timeoutTicks = 800)
  public static void events_follow_atomic_single_and_parallel_commits_without_replay_or_reentry(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Upgrade event reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          beginSingleRun(helper);
        })));
  }

  private static void beginSingleRun(GameTestHelper helper) {
    var loaded = savedDefinitions.stream()
        .filter(definition -> definition.effect().id().equals(id("test_lifecycle_upgrade"))).findFirst().orElseThrow();
    helper.assertTrue(OBSERVER.equals(loaded.lifecycleHandler()), "Lifecycle field was not loaded from resources");
    FactoryUpgradeService
        .replaceDefinitions(List.of(loaded, definition("paper", FAILING, 0), definition("iron_nugget", HEALTHY, 0)));
    var fixture = build(helper);
    var current = new AtomicReference<>(fixture);
    var stack = new ItemStack(Items.GLOWSTONE_DUST, 2);
    var data = new CompoundTag();
    data.putInt("sentinel", 4);
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    fixture.bus().inventory().setStackInSlot(0, stack);
    fixture.bus().inventory().setStackInSlot(1, new ItemStack(Items.PAPER));
    fixture.bus().inventory().setStackInSlot(2, new ItemStack(Items.IRON_NUGGET));
    UpgradeBusGameTests.confirm(helper, fixture.factory());
    fixture.factory().serverTick();
    helper.assertTrue(
        !fixture.bus().upgradeCatalogValid() && fixture.factory().upgradeSources().isEmpty(),
        "Missing event handler retained upgrade effects");

    var events = new ArrayList<FactoryUpgradeEvents.Context>();
    var healthy = new ArrayList<FactoryUpgradeEvents.Context>();
    var failures = new AtomicInteger();
    FactoryUpgradeEvents.Listener stable = (context, upgrade) -> {
      var active = current.get();
      helper.assertTrue(
          active.input().inventory().getStackInSlot(0).isEmpty(),
          "Callback ran before input lease resolved");
      if (context.type() == FactoryUpgradeEvents.Type.STARTED) {
        helper.assertTrue(
            active.factory().runSet(THREAD).runs().size() == context.runs().size(),
            "Start callback ran before run installation");
      } else {
        helper.assertTrue(
            active.factory().runSet(THREAD).runs().isEmpty() && countIron(active.output()) == context.runs().size(),
            "Finish callback ran before all outputs and run cleanup committed");
      }
      events.add(context);
    };
    FactoryUpgradeEvents.Listener first = (context, upgrade) -> {
      helper.assertTrue(
          context.type() == FactoryUpgradeEvents.Type.STARTED && upgrade.slot() == 0 && upgrade.count() == 2,
          "Initial callback type or whole-slot identity differs");
      helper.assertTrue(
          context.machine().equals(MACHINE)
              && context.threadId().equals(THREAD)
              && context.controllerPosition().equals(current.get().factory().getBlockPos())
              && context.dimension().equals(helper.getLevel().dimension().location()),
          "Upgrade event lost its factory context");
      upgrade.itemData().getCompound("components").getCompound("minecraft:custom_data").putInt("sentinel", 99);
      helper.assertTrue(
          upgrade.itemData().getCompound("components").getCompound("minecraft:custom_data").getInt("sentinel") == 4,
          "Event exposed mutable item snapshot data");
      try {
        fixture.bus().inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND));
        helper.fail("Event callback changed upgrade inventory");
      } catch (IllegalStateException expected) {
      }
      try {
        fixture.bus().unbindFactory();
        helper.fail("Event callback changed source ownership");
      } catch (IllegalStateException expected) {
      }
      helper.assertTrue(!fixture.factory().beginRunStart(THREAD), "Event reentered factory startup");
      helper.assertTrue(
          fixture.factory().advanceOutput(THREAD, fixture.factory().runSet(THREAD)) == FactoryOutputEngine.Result.STALE,
          "Event reentered output submission");
      fixture.factory().serverTick();
      stable.onEvent(context, upgrade);
      FactoryUpgradeEvents.register(OBSERVER, stable);
    };
    FactoryUpgradeEvents.register(FAILING, (context, upgrade) -> {
      failures.incrementAndGet();
      throw new IllegalStateException("expected observer failure");
    });
    FactoryUpgradeEvents.register(HEALTHY, (context, upgrade) -> healthy.add(context));
    FactoryUpgradeEvents.register(OBSERVER, first);
    var published = FactoryUpgradeService.snapshot();
    FactoryUpgradeEvents.register(OBSERVER, first);
    FactoryUpgradeEvents.remove(id("unknown_event_handler"));
    helper.assertTrue(FactoryUpgradeService.snapshot() == published, "No-op observer registration changed epoch");
    boolean rejected = CompletableFuture.supplyAsync(() -> {
      try {
        FactoryUpgradeEvents.register(OBSERVER, stable);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(rejected && FactoryUpgradeService.snapshot() == published, "Worker changed observer registry");
    fixture.bus().serverTick();
    fixture.factory().serverTick();
    helper.assertTrue(fixture.bus().upgradeCatalogValid(), "Registering event handlers did not recover source");

    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    var plan = plan(fixture);
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, plan) != FactoryRecipeStarter.Result.STARTED
            && events.isEmpty()
            && healthy.isEmpty()
            && failures.get() == 0
            && fixture.factory().run(THREAD).isEmpty(),
        "Rejected single startup emitted an event or installed a run");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    fixture.factory().serverTick();
    helper.assertTrue(
        events.size() == 1 && healthy.size() == 1 && failures.get() == 1,
        "Start event was repeated, multiplied by stack count, or stopped at a failing listener");
    fillOutput(fixture, new ItemStack(Items.DIRT, 64));
    var restored = restoreFactory(helper, fixture);
    current.set(restored);
    helper.assertTrue(events.size() == 1, "Loading a paid factory replayed its start event");
    awaitBlocked(helper, restored, current, events, healthy, failures, stable, 120);
  }

  private static void awaitBlocked(GameTestHelper helper, Fixture fixture, AtomicReference<Fixture> current, List<FactoryUpgradeEvents.Context> events, List<FactoryUpgradeEvents.Context> healthy, AtomicInteger failures, FactoryUpgradeEvents.Listener stable, int remaining) {
    helper.runAfterDelay(1, () -> {
      fixture.bus().serverTick();
      fixture.factory().serverTick();
      var run = fixture.factory().run(THREAD).orElseThrow();
      if (run.pendingOutput()) {
        for (int attempt = 0; attempt < 100; attempt++) {
          fixture.factory().serverTick();
        }
        helper.assertTrue(
            events.size() == 1 && healthy.size() == 1 && failures.get() == 1 && countIron(fixture.output()) == 0,
            "Blocked or repeated output attempt emitted completion");
        fillOutput(fixture, ItemStack.EMPTY);
        awaitSingleCompletion(helper, fixture, current, events, healthy, failures, stable, 120);
      } else if (remaining == 0) {
        helper.fail("Event single run never reached pending outputs");
      } else {
        awaitBlocked(helper, fixture, current, events, healthy, failures, stable, remaining - 1);
      }
    });
  }

  private static void awaitSingleCompletion(GameTestHelper helper, Fixture fixture, AtomicReference<Fixture> current, List<FactoryUpgradeEvents.Context> events, List<FactoryUpgradeEvents.Context> healthy, AtomicInteger failures, FactoryUpgradeEvents.Listener stable, int remaining) {
    helper.runAfterDelay(1, () -> {
      fixture.bus().serverTick();
      fixture.factory().serverTick();
      if (fixture.factory().run(THREAD).isEmpty() && countIron(fixture.output()) == 1) {
        helper.assertTrue(
            events.size() == 2
                && events.get(1).type() == FactoryUpgradeEvents.Type.FINISHED
                && events.get(0).runs().getFirst().runId().equals(events.get(1).runs().getFirst().runId())
                && healthy.size() == 2
                && failures.get() == 2,
            "Committed single run did not notify each slot exactly once or use replacement handler");
        beginParallel(helper, fixture, current, events, stable);
      } else if (remaining == 0) {
        helper.fail("Event single output did not commit");
      } else {
        awaitSingleCompletion(helper, fixture, current, events, healthy, failures, stable, remaining - 1);
      }
    });
  }

  private static void beginParallel(GameTestHelper helper, Fixture fixture, AtomicReference<Fixture> current, List<FactoryUpgradeEvents.Context> events, FactoryUpgradeEvents.Listener stable) {
    FactoryUpgradeEvents.remove(OBSERVER);
    fixture.bus().serverTick();
    helper.assertTrue(
        !fixture.bus().upgradeCatalogValid() && fixture.factory().upgradeSources().isEmpty(),
        "Removing observer retained its upgrade source");
    FactoryUpgradeEvents.register(OBSERVER, stable);
    FactoryUpgradeService.replaceDefinitions(List.of(definition("glowstone_dust", OBSERVER, 1)));
    fixture.bus().inventory().setStackInSlot(0, new ItemStack(Items.GLOWSTONE_DUST));
    fixture.bus().inventory().setStackInSlot(1, ItemStack.EMPTY);
    fixture.bus().inventory().setStackInSlot(2, ItemStack.EMPTY);
    fillOutput(fixture, ItemStack.EMPTY);
    fixture.bus().serverTick();
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().scheduler().channels().getFirst().parallelLimit() == 2,
        "Parallel observer fixture did not apply upgrade");
    events.clear();
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    var plan = plan(fixture);
    helper.assertTrue(
        FactoryParallelStarter
            .commit(fixture.factory(), THREAD, Collections.nCopies(2, plan)) != FactoryParallelStarter.Result.STARTED
            && fixture.input().inventory().getStackInSlot(0).getCount() == 1
            && events.isEmpty()
            && fixture.factory().runSet(THREAD).runs().isEmpty(),
        "Rejected parallel startup consumed input or emitted notification");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    fixture.factory().serverTick();
    helper.assertTrue(
        events.size() == 1
            && events.getFirst().type() == FactoryUpgradeEvents.Type.STARTED
            && events.getFirst().runs().size() == 2
            && fixture.factory().runSet(THREAD).runs().size() == 2,
        "Parallel start was not one batch notification with two independent runs");
    current.set(fixture);
    awaitParallelCompletion(helper, fixture, events, 120);
  }

  private static void awaitParallelCompletion(GameTestHelper helper, Fixture fixture, List<FactoryUpgradeEvents.Context> events, int remaining) {
    helper.runAfterDelay(1, () -> {
      fixture.bus().serverTick();
      fixture.factory().serverTick();
      if (fixture.factory().run(THREAD).isEmpty() && countIron(fixture.output()) == 2) {
        helper.assertTrue(
            events.size() == 2
                && events.get(1).type() == FactoryUpgradeEvents.Type.FINISHED
                && events.get(1).runs().size() == 2
                && events.get(0).runs().stream().map(FactoryUpgradeEvents.RunSnapshot::runId).toList()
                    .equals(events.get(1).runs().stream().map(FactoryUpgradeEvents.RunSnapshot::runId).toList()),
            "Parallel completion lost run identities or emitted more than once");
        for (int attempt = 0; attempt < 100; attempt++) {
          fixture.factory().serverTick();
        }
        helper.assertTrue(
            events.size() == 2 && !UpgradeLifecyclePublisher.isDispatching(fixture.factory()),
            "Idle tick replayed events or leaked notification guard");
        helper.succeed();
      } else if (remaining == 0) {
        helper.fail("Parallel event outputs did not commit");
      } else {
        awaitParallelCompletion(helper, fixture, events, remaining - 1);
      }
    });
  }

  private static Fixture build(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
    helper.getLevel().setBlockAndUpdate(
        origin.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        origin.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");

    return new Fixture(
        factory,
        bus,
        (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(-1, 0, 1)),
        (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(1, 0, 1)));
  }

  private static Fixture restoreFactory(GameTestHelper helper, Fixture before) {
    var level = helper.getLevel();
    var position = before.factory().getBlockPos();
    var state = before.factory().getBlockState();
    var saved = before.factory().saveWithFullMetadata(level.registryAccess());
    level.removeBlockEntity(position);
    var restored = (FactoryControllerBlockEntity) BlockEntity
        .loadStatic(position, state, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();
    UpgradeBusGameTests.confirm(helper, restored);
    before.bus().refreshBinding();

    return new Fixture(restored, before.bus(), before.input(), before.output());
  }

  private static RecipeStartPlan plan(Fixture fixture) {
    var search = RecipeSearch.begin(fixture.factory(), null).orElseThrow();

    return search.advance(65_536).plan().orElseThrow();
  }

  private static int countIron(MachinePortBlockEntity output) {
    int total = 0;
    for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
      var stack = output.inventory().getStackInSlot(slot);
      if (stack.is(Items.IRON_INGOT)) {
        total += stack.getCount();
      }
    }

    return total;
  }

  private static void fillOutput(Fixture fixture, ItemStack stack) {
    for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
      fixture.output().inventory().setStackInSlot(slot, stack.copy());
    }
  }

  private static FactoryUpgradeDefinition definition(String item, ResourceLocation observer, int parallelAdd) {
    return new FactoryUpgradeDefinition(
        ResourceLocation.fromNamespaceAndPath("minecraft", item),
        new FactoryUpgradeEntry(id("lifecycle_" + item), parallelAdd, 1, 1, 1, 1, 1, 1),
        new FactoryUpgradeCompatibility(Set.of(MACHINE), Set.of()),
        null,
        null,
        observer);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
