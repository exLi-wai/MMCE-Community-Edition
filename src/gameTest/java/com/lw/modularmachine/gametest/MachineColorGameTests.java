package com.lw.modularmachine.gametest;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.HatchTier;
import com.lw.modularmachine.machine.ParallelControllerTier;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class MachineColorGameTests {
  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 80)
  public static void tinted_machine_components_persist_color_across_rebuild(GameTestHelper helper) {
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(7, 5, 7));
    var upgradePos = position.east();
    level.setBlockAndUpdate(
        upgradePos,
        MachineRegistries.UPGRADE_BUSES.get("upgrade_bus_normal").get().defaultBlockState());
    var upgrade = (UpgradeBusBlockEntity) level.getBlockEntity(upgradePos);
    upgrade.setMachineColor(0x38A6D2);
    var smartPos = position.west();
    level.setBlockAndUpdate(smartPos, MachineRegistries.SMART_INTERFACE.get().defaultBlockState());
    var smart = (SmartInterfaceBlockEntity) level.getBlockEntity(smartPos);
    smart.setMachineColor(0x38A6D2);

    helper.assertTrue(
        restoredColor(upgrade, upgradePos, level.getBlockState(upgradePos), level)
            && restoredColor(smart, smartPos, level.getBlockState(smartPos), level),
        "A tinted machine component lost its color during save/load");
    for (var tier : ParallelControllerTier.values()) {
      var state = MachineRegistries.PARALLEL_CONTROLLERS.get("parallel_controller_" + tier.path()).get()
          .defaultBlockState();
      level.setBlockAndUpdate(position, state);
      var parallel = (ParallelControllerBlockEntity) level.getBlockEntity(position);
      parallel.setMachineColor(0x38A6D2);
      helper.assertTrue(
          restoredColor(parallel, position, state, level),
          "Parallel controller lost saved/synced color: " + tier.path());
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void infinite_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.INFINITE);
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void transcend_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.TRANSCEND);
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void dark_extreme_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.DARK_EXTREME);
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void photon_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.PHOTON);
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void daydream_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.DAYDREAM);
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void pig_parallel_controller_color_resumes_after_structure_work_budget(GameTestHelper helper) {
    parallelControllerColorResumesAfterStructureWorkBudget(helper, ParallelControllerTier.PIG);
  }

  private static void parallelControllerColorResumesAfterStructureWorkBudget(GameTestHelper helper, ParallelControllerTier tier) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var inputPos = origin.offset(-1, 0, 1);
    var outputPos = origin.offset(1, 0, 1);
    var parallelPos = origin.offset(0, 1, 1);
    level.setBlockAndUpdate(inputPos, MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(outputPos, MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        parallelPos,
        MachineRegistries.PARALLEL_CONTROLLERS.get("parallel_controller_" + tier.path()).get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_parallel_color"));
    var input = (MachinePortBlockEntity) level.getBlockEntity(inputPos);
    var output = (MachinePortBlockEntity) level.getBlockEntity(outputPos);
    var parallel = (ParallelControllerBlockEntity) level.getBlockEntity(parallelPos);
    for (int round = 0; round < 2; round++) {
      input.setMachineColor(0xFFFFFF);
      output.setMachineColor(0xFFFFFF);
      parallel.setMachineColor(0xFFFFFF);
      StructureService.requestAudit(controller);
      boolean sawPartialDistribution = false;
      for (int attempt = 0; attempt < 1000
          && (!controller.canCommitProduction()
              || input.machineColor() != 0x38A6D2
              || output.machineColor() != 0x38A6D2
              || parallel.machineColor() != 0x38A6D2); attempt++) {
        StructureService.advance(level, 1, 1, 50_000_000L);
        sawPartialDistribution |= input.machineColor() == 0x38A6D2
            && output.machineColor() == 0x38A6D2
            && parallel.machineColor() != 0x38A6D2;
      }
      helper.assertTrue(sawPartialDistribution, "The color budget did not yield before the last parallel component");
      helper.assertTrue(
          controller.canCommitProduction()
              && input.machineColor() == 0x38A6D2
              && output.machineColor() == 0x38A6D2
              && parallel.machineColor() == 0x38A6D2,
          "Parallel controller color distribution stopped at a work budget boundary: " + tier.path());
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void structure_color_distribution_resumes_after_controller_work_budget(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var inputPos = origin.offset(-1, 0, 1);
    var outputPos = origin.offset(1, 0, 1);
    level.setBlockAndUpdate(inputPos, MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(outputPos, MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_recipe_lifecycle"));
    var input = (MachinePortBlockEntity) level.getBlockEntity(inputPos);
    var output = (MachinePortBlockEntity) level.getBlockEntity(outputPos);

    for (int attempt = 0; attempt < 200
        && (!controller.canCommitProduction()
            || input.machineColor() != 0x38A6D2
            || output.machineColor() != 0x38A6D2); attempt++) {
      StructureService.advance(level, 512, 1, 50_000_000L);
    }

    helper.assertTrue(
        controller.canCommitProduction() && input.machineColor() == 0x38A6D2 && output.machineColor() == 0x38A6D2,
        "Budgeted structure scan stopped before distributing color to every resource hatch");

    input.setMachineColor(0xFFFFFF);
    output.setMachineColor(0xFFFFFF);
    StructureService.requestAudit(controller);
    for (int attempt = 0; attempt < 200
        && (!controller.canCommitProduction()
            || input.machineColor() != 0x38A6D2
            || output.machineColor() != 0x38A6D2); attempt++) {
      StructureService.advance(level, 512, 1, 50_000_000L);
    }
    helper.assertTrue(
        controller.canCommitProduction() && input.machineColor() == 0x38A6D2 && output.machineColor() == 0x38A6D2,
        "Structure revalidation did not refresh component colors");
    helper.succeed();
  }

  private static boolean restoredColor(BlockEntity original, BlockPos position, BlockState state, ServerLevel level) {
    var saved = original.saveWithFullMetadata(level.registryAccess());
    var update = original.getUpdateTag(level.registryAccess());
    if (!update.contains(MachineColorable.COLOR_TAG) || update.getInt(MachineColorable.COLOR_TAG) != 0x38A6D2) {
      return false;
    }
    var restored = BlockEntity.loadStatic(position, state, saved, level.registryAccess());

    return restored instanceof MachineColorable colorable
        && colorable.machineColorAssigned()
        && colorable.machineColor() == 0x38A6D2;
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 80)
  public static void chemical_hatch_tiers_persist_machine_color(GameTestHelper helper) {
    if (!Mods.MEKANISM.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(2, 5, 2));
    int index = 0;
    for (String role : new String[]{"chemical_input_hatch_", "chemical_output_hatch_"}) {
      for (var tier : HatchTier.CHEMICALS) {
        var position = origin.offset(index % 4, 0, index / 4);
        var state = MachineRegistries.PORTS.get(role + tier.name()).get().defaultBlockState();
        level.setBlockAndUpdate(position, state);
        var port = (MachinePortBlockEntity) level.getBlockEntity(position);
        port.setMachineColor(0x38A6D2);
        helper.assertTrue(
            restoredColor(port, position, state, level),
            "Chemical hatch lost saved/synced color: " + role + tier.name());
        index++;
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void chemical_hatch_color_distribution_resumes_after_budget(GameTestHelper helper) {
    if (!Mods.MEKANISM.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var inputPos = origin.offset(-1, 0, 1);
    var outputPos = origin.offset(1, 0, 1);
    level.setBlockAndUpdate(
        inputPos,
        MachineRegistries.PORTS.get("chemical_input_hatch_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        outputPos,
        MachineRegistries.PORTS.get("chemical_output_hatch_normal").get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_chemical_color"));
    var input = (MachinePortBlockEntity) level.getBlockEntity(inputPos);
    var output = (MachinePortBlockEntity) level.getBlockEntity(outputPos);
    for (int round = 0; round < 2; round++) {
      input.setMachineColor(0xFFFFFF);
      output.setMachineColor(0xFFFFFF);
      StructureService.requestAudit(controller);
      for (int attempt = 0; attempt < 200
          && (!controller.canCommitProduction()
              || input.machineColor() != 0x38A6D2
              || output.machineColor() != 0x38A6D2); attempt++) {
        StructureService.advance(level, 512, 1, 50_000_000L);
      }
      helper.assertTrue(
          controller.canCommitProduction() && input.machineColor() == 0x38A6D2 && output.machineColor() == 0x38A6D2,
          "Chemical hatch color distribution stopped at a work budget boundary");
    }
    helper.succeed();
  }

  private MachineColorGameTests() {
  }
}
