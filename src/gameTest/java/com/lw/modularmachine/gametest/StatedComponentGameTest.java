package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.component.MachineColorable;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.block.ColoredStatedMachineComponentBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.block.StatedMachineComponentBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.StatedMachineComponentBlockEntity;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;
import com.lw.modularmachine.machine.structure.StructureChangeRouter;
import java.util.List;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = "mmcece")
public final class StatedComponentGameTest {
  private static ColoredStatedMachineComponentBlock block;
  private static BlockEntityType<?> type;

  @SubscribeEvent
  public static void register(RegisterEvent event) {
    var id = ResourceLocation.parse("mmcece:test_stated_component");
    event.register(Registries.BLOCK, registry -> {
      block = new ColoredStatedMachineComponentBlock(Block.Properties.of(), () -> type);
      registry.register(id, block);
    });
    event.register(Registries.BLOCK_ENTITY_TYPE, registry -> {
      type = BlockEntityType.Builder.of((pos, state) -> new StatedMachineComponentBlockEntity(type, pos, state), block)
          .build(null);
      registry.register(id, type);
    });
  }

  @GameTest(template = "empty16", batch = "machine_color", timeoutTicks = 120)
  public static void stated_color_and_working_publication_resume(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(6, 5, 6));
    level.setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    var first = origin.offset(1, 0, 0);
    var second = origin.offset(2, 0, 0);
    level.setBlockAndUpdate(first, block.defaultBlockState());
    level.setBlockAndUpdate(second, block.defaultBlockState());
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.parse("mmcece:test_stated_color"));
    boolean partial = false;
    for (int attempt = 0; attempt < 1000; attempt++) {
      StructureService.advance(level, 1, 1, 50_000_000L);
      var a = (MachineColorable) level.getBlockEntity(first);
      var b = (MachineColorable) level.getBlockEntity(second);
      partial |= a.machineColor() == 0x38A6D2 && b.machineColor() != 0x38A6D2;
      if (controller.canCommitProduction() && a.machineColor() == 0x38A6D2 && b.machineColor() == 0x38A6D2) {
        break;
      }
    }
    helper.assertTrue(partial, "Color distribution never yielded between stated components");
    for (var position : List.of(first, second)) {
      var entity = level.getBlockEntity(position);
      var restored = BlockEntity.loadStatic(
          position,
          level.getBlockState(position),
          entity.saveWithFullMetadata(level.registryAccess()),
          level.registryAccess());
      helper.assertTrue(
          restored instanceof MachineColorable color
              && color.machineColor() == 0x38A6D2
              && entity.getUpdateTag(level.registryAccess()).getInt(MachineColorable.COLOR_TAG) == 0x38A6D2,
          "Stated component lost persisted or synced color");
    }
    StructureService.updateWorkingComponents(controller, true);
    StructureService.advance(level, 1, 1, 50_000_000L);
    helper.assertTrue(
        level.getBlockState(first)
            .getValue(StatedMachineComponentBlock.WORKING_STATE) == StatedMachineComponentBlock.WorkingState.WORKING,
        "Working state did not advance within its work budget");
    helper.assertTrue(
        level.getBlockState(second)
            .getValue(StatedMachineComponentBlock.WORKING_STATE) == StatedMachineComponentBlock.WorkingState.IDLE,
        "Working state ignored the shared one-work slice");
    helper.assertTrue(
        controller.canCommitProduction(),
        "Internal working publication invalidated its own structure proof");
    // An unrelated property notification restarts the color scan; clearing the
    // old working state must still visit components discovered before it.
    StructureChangeRouter.blockChanged(level, second);
    for (int attempt = 0; attempt < 1000; attempt++) {
      StructureService.advance(level, 1, 1, 50_000_000L);
      if (level.getBlockState(first)
          .getValue(StatedMachineComponentBlock.WORKING_STATE) == StatedMachineComponentBlock.WorkingState.IDLE) {
        break;
      }
    }
    helper.assertTrue(
        level.getBlockState(first)
            .getValue(StatedMachineComponentBlock.WORKING_STATE) == StatedMachineComponentBlock.WorkingState.IDLE,
        "Color rescan forgot a component whose working state needed clearing");
    helper.succeed();
  }
}
