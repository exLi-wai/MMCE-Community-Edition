package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Runtime acceptance scenarios; kept separate from compilation of the feature.
 */
@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class PatternProviderColorGameTest {
  private static final int COLOR = 0x38A6D2;

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 80)
  public static void pattern_parts_persist_and_sync_machine_color(GameTestHelper helper) {
    if (!Mods.AE2.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 5, 3));
    var blocks = new Block[]{MachineRegistries.ME_PATTERN_PROVIDER.get(), MachineRegistries.ME_PATTERN_MIRROR.get()};
    for (int index = 0; index < blocks.length; index++) {
      var position = origin.offset(index * 2, 0, 0);
      var state = blocks[index].defaultBlockState();
      level.setBlockAndUpdate(position, state);
      var entity = level.getBlockEntity(position);
      helper.assertTrue(entity instanceof MachineColorable, "Pattern part must expose machine color");
      ((MachineColorable) entity).setMachineColor(COLOR);
      helper.assertTrue(
          restoredColor(entity, position, state, level),
          "Pattern part lost saved or synced color: " + index);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void pattern_part_colors_resume_after_structure_work_budget(GameTestHelper helper) {
    if (!Mods.AE2.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 3));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var providerPos = origin.offset(-1, 0, 1);
    var mirrorPos = origin.offset(1, 0, 1);
    level.setBlockAndUpdate(providerPos, MachineRegistries.ME_PATTERN_PROVIDER.get().defaultBlockState());
    level.setBlockAndUpdate(mirrorPos, MachineRegistries.ME_PATTERN_MIRROR.get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_pattern_color"));
    var provider = (MachineColorable) level.getBlockEntity(providerPos);
    var mirror = (MachineColorable) level.getBlockEntity(mirrorPos);
    for (int round = 0; round < 2; round++) {
      provider.setMachineColor(0xFFFFFF);
      mirror.setMachineColor(0xFFFFFF);
      StructureService.requestAudit(controller);
      for (int attempt = 0; attempt < 200
          && (!controller.canCommitProduction()
              || provider.machineColor() != COLOR
              || mirror.machineColor() != COLOR); attempt++) {
        StructureService.advance(level, 512, 1, 50_000_000L);
      }
      helper.assertTrue(
          controller.canCommitProduction() && provider.machineColor() == COLOR && mirror.machineColor() == COLOR,
          "Provider/mirror color distribution stopped at a work budget boundary");
    }
    helper.succeed();
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

  private PatternProviderColorGameTest() {
  }
}
