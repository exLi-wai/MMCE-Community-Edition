package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeCompatibilityRunGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_compatibility_run");
  private static final ResourceLocation UPGRADE = id("compatibility_run_speed");
  private static final ResourceLocation ITEM = ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  @AfterBatch(batch = "upgrade_compatibility_run")
  public static void cleanup(ServerLevel level) {
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_compatibility_run", timeoutTicks = 400)
  public static void compatibility_reload_keeps_paid_run_frozen_and_updates_the_next_run(GameTestHelper helper) {
    var level = helper.getLevel();
    savedDefinitions = FactoryUpgradeService.snapshot().definitions();
    var effect = new FactoryUpgradeEntry(UPGRADE, 0, 2, 1, 1, 1, 1, 1);
    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(ITEM, effect, new FactoryUpgradeCompatibility(Set.of(MACHINE), Set.of()))));

    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
    level.setBlockAndUpdate(
        origin.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        origin.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    var input = (MachinePortBlockEntity) level.getBlockEntity(origin.offset(-1, 0, 1));
    var output = (MachinePortBlockEntity) level.getBlockEntity(origin.offset(1, 0, 1));
    bus.inventory().setStackInSlot(0, new ItemStack(Items.AMETHYST_SHARD));
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    UpgradeBusGameTests.confirm(helper, factory);
    for (int attempt = 0; attempt < 20 && factory.run(THREAD).isEmpty(); attempt++) {
      factory.serverTick();
    }
    var paid = factory.run(THREAD).orElseThrow();
    helper.assertTrue(
        paid.duration() == 10
            && factory.scheduler().channels().getFirst().upgradeSnapshot().speedNumerator() == 2
            && input.inventory().getStackInSlot(0).getCount() == 1,
        "Allowed upgrade did not freeze the first paid run");
    var paidId = paid.runId();

    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(ITEM, effect, new FactoryUpgradeCompatibility(Set.of(), Set.of(MACHINE)))));
    bus.serverTick();
    factory.serverTick();
    var stillPaid = factory.run(THREAD).orElseThrow();
    helper.assertTrue(
        stillPaid.runId().equals(paidId)
            && stillPaid.duration() == 10
            && factory.scheduler().channels().getFirst().upgradeSnapshot().speedNumerator() == 2
            && UpgradeBusPayloads.capture(7, bus).incompatible().equals(List.of(UPGRADE)),
        "Changing compatibility retroactively changed or discarded the paid run");
    awaitNextRun(helper, factory, bus, input, output, paidId, 80);
  }

  static void awaitNextRun(GameTestHelper helper, FactoryControllerBlockEntity factory, UpgradeBusBlockEntity bus, MachinePortBlockEntity input, MachinePortBlockEntity output, UUID paidId, int remaining) {
    helper.runAfterDelay(1, () -> {
      factory.serverTick();
      var current = factory.run(THREAD).orElse(null);
      if (current != null && !current.runId().equals(paidId)) {
        helper.assertTrue(
            current.duration() == 20
                && factory.scheduler().channels().getFirst().upgradeSnapshot().speedNumerator() == 1
                && countIron(output) == 1
                && input.inventory().getStackInSlot(0).isEmpty()
                && bus.inventory().getStackInSlot(0).getCount() == 1,
            "The next run retained an excluded upgrade or charged input incorrectly");
        awaitCompletion(helper, factory, output, 80);
      } else if (remaining == 0) {
        helper.fail("The new compatibility rules did not reach the next run");
      } else {
        awaitNextRun(helper, factory, bus, input, output, paidId, remaining - 1);
      }
    });
  }

  private static void awaitCompletion(GameTestHelper helper, FactoryControllerBlockEntity factory, MachinePortBlockEntity output, int remaining) {
    helper.runAfterDelay(1, () -> {
      factory.serverTick();
      if (factory.run(THREAD).isEmpty() && countIron(output) == 2) {
        helper.succeed();
      } else if (remaining == 0) {
        helper.fail("The old and new compatibility runs did not produce exactly once");
      } else {
        awaitCompletion(helper, factory, output, remaining - 1);
      }
    });
  }

  private static int countIron(MachinePortBlockEntity output) {
    int count = 0;
    for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
      var stack = output.inventory().getStackInSlot(slot);
      if (stack.is(Items.IRON_INGOT)) {
        count += stack.getCount();
      }
    }

    return count;
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
