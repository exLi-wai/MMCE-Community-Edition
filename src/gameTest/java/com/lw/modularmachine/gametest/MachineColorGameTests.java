package com.lw.modularmachine.gametest;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import com.lw.modularmachine.blockentity.MachineColorable;
import com.lw.modularmachine.blockentity.ParallelControllerBlockEntity;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
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
    var state = MachineRegistries.PARALLEL_CONTROLLERS.get("parallel_controller_normal").get().defaultBlockState();
    level.setBlockAndUpdate(position, state);
    var parallel = (ParallelControllerBlockEntity) level.getBlockEntity(position);
    parallel.setMachineColor(0x38A6D2);
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
        restoredColor(parallel, position, state, level)
            && restoredColor(upgrade, upgradePos, level.getBlockState(upgradePos), level)
            && restoredColor(smart, smartPos, level.getBlockState(smartPos), level),
        "A tinted machine component lost its color during save/load");
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
    if (!update.contains(MachineColorable.COLOR_TAG)) {
      return false;
    }
    var restored = BlockEntity.loadStatic(position, state, saved, level.registryAccess());
    if (!(restored instanceof MachineColorable colorable)) {
      return false;
    }
    if (!colorable.machineColorAssigned()) {
      colorable.setMachineColor(0xFFFFFF);
    }
    return colorable.machineColor() == 0x38A6D2;
  }

  private MachineColorGameTests() {
  }
}
