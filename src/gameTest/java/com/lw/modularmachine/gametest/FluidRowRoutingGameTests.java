package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_resource_regression")
@PrefixGameTestTemplate(false)
public final class FluidRowRoutingGameTests {
  private static final String THREAD = "fluid_routes";

  @GameTest(template = "empty16", timeoutTicks = 280)
  public static void ordinary_fluid_rows_route_independently(GameTestHelper helper) {
    checkRouting(helper, false);
  }

  @GameTest(template = "empty16", timeoutTicks = 280)
  public static void factory_fluid_rows_route_independently(GameTestHelper helper) {
    checkRouting(helper, true);
  }

  private static void checkRouting(GameTestHelper helper, boolean factory) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var machine = ResourceLocation.parse("mmcece:test_fluid_row_routes");
    var feed = port(helper, origin.offset(-1, 0, 1), "fluid_input_hatch_normal");
    var coolant = port(helper, origin.offset(1, 0, 1), "fluid_input_hatch_normal");
    var product = port(helper, origin.offset(0, 1, 1), "fluid_output_hatch_normal");
    var untagged = port(helper, origin.offset(0, -1, 1), "fluid_output_hatch_normal");
    if (factory) {
      level.setBlockAndUpdate(origin, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
      var controller = (FactoryControllerBlockEntity) level.getBlockEntity(origin);
      controller.bindMachine(machine);
      controller.threadConfiguration().replaceThreads(
          List.of(
              new FactoryThreadState(
                  THREAD,
                  machine,
                  ResourceLocation.parse("mmcece:mmcece/test_fluid_row_routes"),
                  true,
                  0,
                  2,
                  0,
                  FactoryThreadState.State.IDLE,
                  FactoryThreadState.Reason.NONE,
                  0,
                  new UpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 1))));
    } else {
      level.setBlockAndUpdate(origin, MachineRegistries.CONTROLLER.get().defaultBlockState());
      ((MachineControllerBlockEntity) level.getBlockEntity(origin)).bindMachine(machine);
    }
    // Enough water overall for two runs, all in the wrong distribution.
    feed.setFluid(new FluidStack(Fluids.WATER, 200));
    helper.runAfterDelay(40, () -> {
      helper.assertTrue(
          feed.getFluidAmount() == 200 && product.isEmpty() && untagged.isEmpty(),
          "The machine borrowed the missing coolant from the feed selector");
      feed.setFluid(new FluidStack(Fluids.WATER, 60));
      coolant.setFluid(new FluidStack(Fluids.WATER, 140));
      var sequence = helper.startSequence();
      if (factory) {
        var controller = (FactoryControllerBlockEntity) level.getBlockEntity(origin);
        sequence.thenWaitUntil(
            () -> helper.assertTrue(
                !controller.runSet(THREAD).runs().isEmpty(),
                "The factory did not start after both tanks were filled"))
            .thenExecute(
                () -> helper.assertTrue(
                    controller.runSet(THREAD).runs().size() == 2,
                    "The first factory start must admit both runs together"));
      }
      sequence.thenWaitUntil(() -> {
        helper.assertTrue(feed.isEmpty() && coolant.isEmpty(), "Both input rows must be paid in full");
        helper.assertTrue(
            product.getFluidAmount() == 26 && untagged.getFluidAmount() == 54,
            "Completion did not route each frozen row to its own tank");
      }).thenSucceed();
    });
  }

  private static FluidTank port(GameTestHelper helper, BlockPos pos, String block) {
    var level = helper.getLevel();
    level.setBlockAndUpdate(pos, MachineRegistries.PORTS.get(block).get().defaultBlockState());
    return ((MachinePortBlockEntity) level.getBlockEntity(pos)).fluidTank();
  }
}
