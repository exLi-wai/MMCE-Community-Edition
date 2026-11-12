package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.network.MachinePayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Payload codecs initialize Minecraft registries and belong in the game
 * runtime.
 */
@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class ChemicalResourceGameTests {
  @GameTest(template = "empty16", batch = "chemical_resource")
  public static void chemical_status_preserves_stock_after_capacity_reduction(GameTestHelper helper) {
    var chemical = new ChemicalStackData(ResourceLocation.parse("mekanism:hydrogen"), Long.MAX_VALUE);
    var status = new MachinePayloads.ResourcePortStatusPayload(
        MachinePayloads.RESOURCE_PROTOCOL_VERSION,
        BlockPos.ZERO,
        PortKind.CHEMICAL_OUTPUT,
        "tiny",
        Long.MAX_VALUE,
        100,
        0,
        FluidStack.EMPTY,
        chemical);
    helper.assertTrue(
        status.chemical().amount() == Long.MAX_VALUE && status.capacity() == 100,
        "Chemical status truncated stored quantity after capacity reduction");
    boolean rejected = false;
    try {
      new MachinePayloads.ResourcePortStatusPayload(
          MachinePayloads.RESOURCE_PROTOCOL_VERSION,
          BlockPos.ZERO,
          PortKind.CHEMICAL_OUTPUT,
          "tiny",
          1,
          100,
          0,
          FluidStack.EMPTY,
          chemical);
    } catch (IllegalArgumentException invalid) {
      rejected = true;
    }
    helper.assertTrue(rejected, "Chemical payload accepted inconsistent quantities");
    helper.succeed();
  }
}
