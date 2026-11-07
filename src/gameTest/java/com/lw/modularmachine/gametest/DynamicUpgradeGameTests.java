package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.api.upgrade.FactoryDynamicUpgrades;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntryResolver;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class DynamicUpgradeGameTests {
  private static final ResourceLocation HANDLER = id("test_dynamic_effect");
  private static final ResourceLocation RUN_HANDLER = id("test_dynamic_run_effect");
  private static final ResourceLocation MACHINE = id("test_upgrade_bus_binding");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  @AfterBatch(batch = "dynamic_upgrade_effects")
  public static void cleanup(ServerLevel level) {
    FactoryDynamicUpgrades.remove(HANDLER);
    FactoryDynamicUpgrades.remove(RUN_HANDLER);
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "dynamic_upgrade_effects", timeoutTicks = 800)
  public static void dynamic_effects_are_cached_guarded_and_frozen_until_the_next_paid_run(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Dynamic upgrade reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          try {
            verifyEffects(helper);
            beginPaidRuns(helper);
          } catch (RuntimeException | AssertionError error) {
            cleanup(helper.getLevel());
            throw error;
          }
        })));
  }

  private static void verifyEffects(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
    var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    level.setBlockAndUpdate(origin.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    var stack = new ItemStack(Items.BLAZE_POWDER, 2);
    var data = new CompoundTag();
    data.putInt("charge", 3);
    data.putString("sentinel", "unchanged");
    stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    var before = stack.copy();
    bus.inventory().setStackInSlot(0, stack);
    UpgradeBusGameTests.confirm(helper, factory);
    factory.serverTick();
    helper.assertTrue(
        !bus.upgradeCatalogValid() && factory.upgradeSources().isEmpty(),
        "Missing dynamic handler fell back to static or stale effects");

    var calls = new AtomicInteger();
    var multiplier = new AtomicInteger(1);
    var observed = new AtomicReference<FactoryDynamicUpgrades.Context>();
    FactoryDynamicUpgrades.register(HANDLER, context -> {
      calls.incrementAndGet();
      observed.set(context);
      var itemData = context.itemData();
      int charge = itemData.getCompound("components").getCompound("minecraft:custom_data").getInt("charge");
      itemData.getCompound("components").getCompound("minecraft:custom_data").putInt("charge", 999);
      return new FactoryUpgradeEntry(
          context.baseEffect().id(),
          charge * context.count() * multiplier.get(),
          2,
          1,
          1,
          1,
          1,
          1);
    });
    bus.serverTick();
    factory.serverTick();
    var context = observed.get();
    helper
        .assertTrue(
            bus.upgradeCatalogValid()
                && factory.scheduler().channels().getFirst().parallelLimit() == 7
                && context.definitionId().equals(id("test_dynamic_charge"))
                && context.itemId().equals(ResourceLocation.fromNamespaceAndPath("minecraft", "blaze_powder"))
                && context.machine().equals(MACHINE)
                && context.busPosition().equals(origin.south())
                && context.slot() == 0
                && context.count() == 2
                && context.baseEffect().parallelAdd() == 2
                && context.itemData().getCompound("components").getCompound("minecraft:custom_data")
                    .getInt("charge") == 3
                && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
            "Dynamic context was not independent or the whole-slot effect was scaled twice");
    int resolvedCalls = calls.get();
    for (int attempt = 0; attempt < 100; attempt++) {
      bus.serverTick();
      factory.serverTick();
      UpgradeBusPayloads.capture(7, bus);
    }
    helper.assertTrue(
        calls.get() == resolvedCalls && FactoryUpgradeEntryResolver.resolve(stack).isEmpty(),
        "Unchanged menus/ticks repeated callbacks or raw resolution bypassed the dynamic context");
    multiplier.set(2);
    FactoryDynamicUpgrades.invalidate(HANDLER);
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 13,
        "Explicit invalidation did not recompute effects");

    long savedRevision = bus.upgradeRevision();
    var saved = bus.saveWithFullMetadata(level.registryAccess());
    var busPosition = bus.getBlockPos();
    var state = bus.getBlockState();
    level.removeBlockEntity(busPosition);
    bus = (UpgradeBusBlockEntity) BlockEntity.loadStatic(busPosition, state, saved, level.registryAccess());
    level.setBlockEntity(bus);
    bus.onLoad();
    factory.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid()
            && bus.upgradeRevision() == savedRevision
            && factory.scheduler().channels().getFirst().parallelLimit() == 13
            && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
        "Dynamic bus rebuild lost its handler context, items, or saved revision");

    var protectedBus = bus;
    List<FactoryDynamicUpgrades.Evaluator> invalid = List.of(
        value -> null,
        value -> new FactoryUpgradeEntry(id("wrong_identity"), 1, 1, 1, 1, 1, 1, 1),
        value -> new FactoryUpgradeEntry(value.baseEffect().id(), 1, 300, 1, 1, 1, 1, 1),
        value -> {
          throw new IllegalArgumentException("test callback failure");
        },
        value -> {
          protectedBus.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND));
          return value.baseEffect();
        });
    for (var evaluator : invalid) {
      FactoryDynamicUpgrades.register(HANDLER, evaluator);
      bus.serverTick();
      factory.serverTick();
      helper.assertTrue(
          !bus.upgradeCatalogValid()
              && factory.upgradeSources().isEmpty()
              && !UpgradeBusPayloads.capture(7, bus).valid()
              && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
          "Invalid callback retained a source or changed inventory during evaluation");
    }

    FactoryDynamicUpgrades.Evaluator stable = value -> value.baseEffect();
    var replaced = new AtomicBoolean();
    FactoryDynamicUpgrades.register(HANDLER, value -> {
      replaced.set(true);
      FactoryDynamicUpgrades.register(HANDLER, stable);
      return new FactoryUpgradeEntry(value.baseEffect().id(), 100, 1, 1, 1, 1, 1, 1);
    });
    bus.serverTick();
    helper.assertTrue(
        replaced.get() && !bus.upgradeCatalogValid() && factory.upgradeSources().isEmpty(),
        "Callback result survived a reentrant handler replacement");
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid() && factory.scheduler().channels().getFirst().parallelLimit() == 3,
        "New handler generation did not recover the source");
    var published = FactoryUpgradeService.snapshot();
    boolean workerRejected = CompletableFuture.supplyAsync(() -> {
      try {
        FactoryDynamicUpgrades.register(HANDLER, stable);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        workerRejected && FactoryUpgradeService.snapshot() == published,
        "Worker published a handler generation");
    FactoryDynamicUpgrades.remove(HANDLER);
    bus.serverTick();
    helper.assertTrue(
        !bus.upgradeCatalogValid() && factory.upgradeSources().isEmpty(),
        "Removed handler retained effects");
    FactoryDynamicUpgrades.register(HANDLER, stable);
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid() && factory.scheduler().channels().getFirst().parallelLimit() == 3,
        "Re-registering the handler did not recover the source");
  }

  private static void beginPaidRuns(GameTestHelper helper) {
    var level = helper.getLevel();
    var machine = id("test_upgrade_compatibility_run");
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var effect = new FactoryUpgradeEntry(id("dynamic_run_speed"), 0, 1, 1, 1, 1, 1, 1);
    FactoryDynamicUpgrades
        .register(RUN_HANDLER, value -> new FactoryUpgradeEntry(value.baseEffect().id(), 0, 2, 1, 1, 1, 1, 1));
    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(
                ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard"),
                effect,
                new FactoryUpgradeCompatibility(Set.of(machine), Set.of()),
                null,
                RUN_HANDLER)));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, machine);
    level.setBlockAndUpdate(
        origin.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        origin.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    bus.inventory().setStackInSlot(0, new ItemStack(Items.AMETHYST_SHARD));
    var input = (MachinePortBlockEntity) level.getBlockEntity(origin.offset(-1, 0, 1));
    var output = (MachinePortBlockEntity) level.getBlockEntity(origin.offset(1, 0, 1));
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    UpgradeBusGameTests.confirm(helper, factory);
    for (int attempt = 0; attempt < 20 && factory.run(THREAD).isEmpty(); attempt++) {
      factory.serverTick();
    }
    var paid = factory.run(THREAD).orElseThrow();
    helper.assertTrue(paid.duration() == 10, "Dynamic speed did not freeze into the first paid run");
    FactoryDynamicUpgrades.register(RUN_HANDLER, FactoryDynamicUpgrades.Context::baseEffect);
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid()
            && factory.run(THREAD).orElseThrow().runId().equals(paid.runId())
            && factory.run(THREAD).orElseThrow().duration() == 10,
        "Handler replacement retroactively changed the paid run");
    UpgradeCompatibilityRunGameTests.awaitNextRun(helper, factory, bus, input, output, paid.runId(), 80);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
