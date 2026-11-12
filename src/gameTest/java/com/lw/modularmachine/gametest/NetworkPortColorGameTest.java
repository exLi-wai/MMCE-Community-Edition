package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class NetworkPortColorGameTest {
  private static final int COLOR = 0x38A6D2;

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 80)
  public static void network_ports_persist_and_sync_machine_color(GameTestHelper helper) {
    if (!Mods.AE2.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 5, 3));
    int index = 0;
    for (String resource : new String[]{"item", "fluid", "chemical"}) {
      if (!available(resource)) {
        continue;
      }
      for (String direction : new String[]{"input", "output"}) {
        String id = "me_" + resource + "_" + direction + "_bus";
        var registered = MachineRegistries.NETWORK_PORTS.get(id);
        helper.assertTrue(registered != null, "Missing network port registration: " + id);
        var position = origin.offset(index % 3, 0, index / 3);
        var state = registered.get().defaultBlockState();
        level.setBlockAndUpdate(position, state);
        var port = (MachinePortBlockEntity) level.getBlockEntity(position);
        port.setMachineColor(COLOR);
        helper.assertTrue(restoredColor(port, position, state, level), "Network port lost saved/synced color: " + id);
        index++;
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void network_port_colors_resume_after_structure_work_budget(GameTestHelper helper) {
    if (!Mods.AE2.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    int row = 0;
    for (String resource : new String[]{"item", "fluid", "chemical"}) {
      if (!available(resource)) {
        continue;
      }
      var origin = helper.absolutePos(new BlockPos(7, 5, 3 + row * 4));
      level.setBlockAndUpdate(
          origin,
          MachineControllerBlock
              .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
      var inputPos = origin.offset(-1, 0, 1);
      var outputPos = origin.offset(1, 0, 1);
      level.setBlockAndUpdate(
          inputPos,
          MachineRegistries.NETWORK_PORTS.get("me_" + resource + "_input_bus").get().defaultBlockState());
      level.setBlockAndUpdate(
          outputPos,
          MachineRegistries.NETWORK_PORTS.get("me_" + resource + "_output_bus").get().defaultBlockState());
      var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
      controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_network_" + resource + "_color"));
      var input = (MachinePortBlockEntity) level.getBlockEntity(inputPos);
      var output = (MachinePortBlockEntity) level.getBlockEntity(outputPos);
      for (int round = 0; round < 2; round++) {
        input.setMachineColor(0xFFFFFF);
        output.setMachineColor(0xFFFFFF);
        StructureService.requestAudit(controller);
        for (int attempt = 0; attempt < 200
            && (!controller.canCommitProduction()
                || input.machineColor() != COLOR
                || output.machineColor() != COLOR); attempt++) {
          StructureService.advance(level, 512, 1, 50_000_000L);
        }
        helper.assertTrue(
            controller.canCommitProduction() && input.machineColor() == COLOR && output.machineColor() == COLOR,
            "Network port color distribution stopped at a work budget boundary: " + resource);
      }
      row++;
    }
    helper.succeed();
  }

  private static boolean available(String resource) {
    return !resource.equals("chemical") || Mods.MEKANISM.isLoaded() && Mods.APPMEK.isLoaded();
  }

  private static boolean restoredColor(BlockEntity original, BlockPos position, BlockState state, ServerLevel level) {
    var saved = original.saveWithFullMetadata(level.registryAccess());
    var update = original.getUpdateTag(level.registryAccess());
    if (!update.contains(MachineColorable.COLOR_TAG) || update.getInt(MachineColorable.COLOR_TAG) != COLOR) {
      return false;
    }
    var restored = BlockEntity.loadStatic(position, state, saved, level.registryAccess());

    return restored instanceof MachineColorable colorable
        && colorable.machineColorAssigned()
        && colorable.machineColor() == COLOR;
  }

  private NetworkPortColorGameTest() {
  }
}
