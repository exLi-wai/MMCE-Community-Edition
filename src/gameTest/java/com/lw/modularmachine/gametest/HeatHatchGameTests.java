package com.lw.modularmachine.gametest;

import com.lw.modularmachine.integration.mekanism.HeatHatchBlockEntity;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.util.Mods;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_resource_regression")
@PrefixGameTestTemplate(false)
public final class HeatHatchGameTests {
  private HeatHatchGameTests() {
  }

  @GameTest(template = "empty16")
  public static void heat_output_conducts_one_milli_heat_to_input(GameTestHelper helper) {
    if (!Mods.MEKANISM.isLoaded()) {
      helper.succeed();
      return;
    }
    var level = helper.getLevel();
    var outputPos = helper.absolutePos(new BlockPos(6, 5, 6));
    var inputPos = outputPos.east();
    level.setBlockAndUpdate(outputPos, MachineRegistries.HEAT_OUTPUT.get().defaultBlockState());
    level.setBlockAndUpdate(inputPos, MachineRegistries.HEAT_INPUT.get().defaultBlockState());
    var output = (HeatHatchBlockEntity) level.getBlockEntity(outputPos);
    var input = (HeatHatchBlockEntity) level.getBlockEntity(inputPos);
    helper.assertTrue(output.getTemperature(0) == input.getTemperature(0), "Expected equal ambient temperatures");
    try (var lease = output.acquireRecipeBuffer()) {
      helper.assertTrue(lease.insertExact(1), "Heat output rejected one milli-heat");
      lease.resolve(true);
    }
    output.tickHatch();
    helper.assertTrue(output.stored() == 0 && input.stored() == 1, "One milli-heat was stalled, lost or duplicated");
    helper.succeed();
  }
}
