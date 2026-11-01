package com.lw.modularmachine.gametest;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.registry.MachineRegistries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Opt-in two-process save/restart coverage for a hybrid FE/fluid probabilistic
 * paid run.
 */
@GameTestHolder("mmcece_restart")
@PrefixGameTestTemplate(false)
public final class PaidRunRestartGameTests {
  private static final BlockPos ORIGIN = new BlockPos(100_008, 80, 100_008);
  private static final Path RUN_ID_FILE = Path.of("paid-run-restart-id.txt");

  private PaidRunRestartGameTests() {
  }

  @GameTest(template = "empty16", batch = "paid_run_restart", timeoutTicks = 600)
  public static void running_paid_run_survives_a_full_server_restart(GameTestHelper helper) {
    String phase = System.getProperty("mmcece.lifecycle.restartPhase", "");
    if (phase.isEmpty()) {
      helper.succeed();
      return;
    }
    if (!phase.equals("write") && !phase.equals("read")) {
      helper.fail("Unknown paid-run restart phase: " + phase);
      return;
    }
    Runnable phaseWork = () -> {
      try {
        if (phase.equals("write")) {
          prepareAndSave(helper);
        } else {
          restoreAndFinish(helper);
        }
      } catch (IOException | RuntimeException failure) {
        helper.fail("Paid-run restart " + phase + " phase failed: " + failure);
      }
    };
    helper.runAfterDelay(1, phaseWork);
  }

  private static void prepareAndSave(GameTestHelper helper) throws IOException {
    var level = helper.getLevel();
    level.setChunkForced(ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4, true);
    level.getChunkAt(ORIGIN);
    clearFixture(helper);
    var pose = MachineOrientation.IDENTITY;
    level.setBlockAndUpdate(
        ORIGIN,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    level.setBlockAndUpdate(
        ORIGIN.offset(pose.toWorldOffset(new BlockPos(-1, 0, 1))),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    placePort(level, ORIGIN, pose, new BlockPos(1, 0, 1), "item_output_bus_normal");
    placePort(level, ORIGIN, pose, new BlockPos(0, 1, 1), "energy_input_hatch_normal");
    placePort(level, ORIGIN, pose, new BlockPos(0, -1, 1), "energy_input_hatch_normal");
    placePort(level, ORIGIN, pose, new BlockPos(1, 1, 1), "fluid_input_hatch_normal");
    placePort(level, ORIGIN, pose, new BlockPos(1, -1, 1), "fluid_input_hatch_normal");
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(ORIGIN);
    controller.bindMachine(PaidRunRestartGameTests.machineId());
    confirmStructure(helper, controller);
    helper.assertTrue(MachineRecipeService.catalogFor(controller).isPresent(), "Restart recipe catalog unavailable");
    var input = port(helper, ORIGIN.offset(new BlockPos(-1, 0, 1)));
    input.inventory().setStackInSlot(0, new ItemStack(Items.COBBLESTONE));
    var energy = port(helper, ORIGIN.offset(new BlockPos(0, 1, 1)));
    var fluid = port(helper, ORIGIN.offset(new BlockPos(1, 1, 1)));
    var fluidB = port(helper, ORIGIN.offset(new BlockPos(1, -1, 1)));
    energy.energy().receiveEnergy(1_000, false);
    fluid.fluidTank().fill(new FluidStack(Fluids.WATER, 2_000), FluidAction.EXECUTE);
    fluidB.fluidTank().fill(new FluidStack(Fluids.WATER, 2_000), FluidAction.EXECUTE);
    var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
    helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Restart fixture did not start a paid run");
    var output = port(helper, ORIGIN.offset(new BlockPos(1, 0, 1)));
    for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
      output.inventory().setStackInSlot(slot, new ItemStack(Items.STONE, 64));
    }
    awaitPendingOutput(helper, controller, output, energy, fluid, fluidB, 60);
  }

  private static void awaitPendingOutput(GameTestHelper helper, MachineControllerBlockEntity controller, MachinePortBlockEntity output, MachinePortBlockEntity energy, MachinePortBlockEntity fluid, MachinePortBlockEntity fluidB, int remainingTicks) {
    helper.runAfterDelay(1, () -> {
      controller.serverTick();
      var run = controller.runningRecipe().orElseThrow();
      if (!run.pendingOutput()) {
        if (remainingTicks == 0) {
          helper.fail("Run did not freeze its result behind the full output hatch");
          return;
        }
        awaitPendingOutput(helper, controller, output, energy, fluid, fluidB, remainingTicks - 1);
        return;
      }
      try {
        helper.assertTrue(
            run.progress() == run.duration(),
            "Pending result has incomplete progress: " + run.progress() + "/" + run.duration());
        helper.assertTrue(
            output.inventory().getStackInSlot(0).is(Items.STONE),
            "Output blockage fixture did not retain its occupied output hatch");
        boolean diamondSelected = containsOutput(run, "minecraft:diamond");
        Files.writeString(
            RUN_ID_FILE,
            run.runId() + "\n" + run.progress() + "\n" + energy.energy().stored() + "\n" + fluid.fluidTank()
                .getFluidAmount() + "\n" + fluidB.fluidTank()
                    .getFluidAmount() + "\n" + run.randomSeed() + "\n" + diamondSelected);
        helper.succeed();
      } catch (IOException | RuntimeException failure) {
        helper.fail("Could not persist restart run marker: " + failure);
      }
    });
  }

  private static void restoreAndFinish(GameTestHelper helper) throws IOException {
    var level = helper.getLevel();
    level.setChunkForced(ORIGIN.getX() >> 4, ORIGIN.getZ() >> 4, true);
    level.getChunkAt(ORIGIN);
    helper.assertTrue(Files.exists(RUN_ID_FILE), "Missing write-phase run identity marker");
    String[] savedState = Files.readString(RUN_ID_FILE).trim().split("\\R");
    UUID expectedRun = UUID.fromString(savedState[0]);
    int expectedProgress = Integer.parseInt(savedState[1]);
    long expectedEnergy = Long.parseLong(savedState[2]);
    int expectedFluid = Integer.parseInt(savedState[3]);
    int expectedFluidB = Integer.parseInt(savedState[4]);
    long expectedSeed = Long.parseLong(savedState[5]);
    boolean expectedDiamond = Boolean.parseBoolean(savedState[6]);
    if (!(level.getBlockEntity(ORIGIN) instanceof MachineControllerBlockEntity controller)) {
      helper.fail("Server restart did not restore the persistent controller block entity");
      return;
    }
    helper.runAfterDelay(8, () -> {
      try {
        confirmStructure(helper, controller);
        helper
            .assertTrue(MachineRecipeService.catalogFor(controller).isPresent(), "Restart recipe catalog unavailable");
        var run = controller.runningRecipe().orElseThrow();
        var energy = port(helper, ORIGIN.offset(new BlockPos(0, 1, 1)));
        var fluid = port(helper, ORIGIN.offset(new BlockPos(1, 1, 1)));
        var fluidB = port(helper, ORIGIN.offset(new BlockPos(1, -1, 1)));
        helper.assertTrue(
            run.runId().equals(expectedRun)
                && run.progress() == expectedProgress
                && run.pendingOutput()
                && run.randomSeed() == expectedSeed
                && energy.energy().stored() == expectedEnergy
                && fluid.fluidTank().getFluidAmount() == expectedFluid
                && fluidB.fluidTank().getFluidAmount() == expectedFluidB
                && count(port(helper, ORIGIN.offset(new BlockPos(1, 0, 1))), Items.STONE) > 0,
            "Server restart did not restore the blocked pending run, seed, and resource balances");
        var input = port(helper, ORIGIN.offset(new BlockPos(-1, 0, 1)));
        helper
            .assertTrue(input.inventory().getStackInSlot(0).isEmpty(), "Restart recovery charged input a second time");
        var output = port(helper, ORIGIN.offset(new BlockPos(1, 0, 1)));
        for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
          output.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        expectedDiamondAfterRestart = expectedDiamond;
        awaitRestoredRun(helper, controller, 100);
      } catch (RuntimeException failure) {
        helper.fail("Could not validate restart recovery: " + failure);
      }
    });
  }

  private static boolean expectedDiamondAfterRestart;

  private static void awaitRestoredRun(GameTestHelper helper, MachineControllerBlockEntity controller, int remainingTicks) {
    helper.runAfterDelay(1, () -> {
      var level = helper.getLevel();
      controller.serverTick();
      if (controller.runningRecipe().isEmpty()) {
        finishRestoredRun(helper, controller);
      } else if (remainingTicks == 0) {
        helper.fail("Restarted paid run did not finish: " + controller.recipeRunStatus());
      } else {
        awaitRestoredRun(helper, controller, remainingTicks - 1);
      }
    });
  }

  private static void finishRestoredRun(GameTestHelper helper, MachineControllerBlockEntity controller) {
    try {
      var input = port(helper, ORIGIN.offset(new BlockPos(-1, 0, 1)));
      var output = port(helper, ORIGIN.offset(new BlockPos(1, 0, 1)));
      var energy = port(helper, ORIGIN.offset(new BlockPos(0, 1, 1)));
      var fluid = port(helper, ORIGIN.offset(new BlockPos(1, 1, 1)));
      var fluidB = port(helper, ORIGIN.offset(new BlockPos(1, -1, 1)));
      int outputCount = count(output, Items.EMERALD);
      int diamondCount = count(output, Items.DIAMOND);
      helper.assertTrue(
          controller.runningRecipe().isEmpty()
              && outputCount == 1
              && diamondCount == (expectedDiamondAfterRestart ? 1 : 0)
              && energy.energy().stored() == 312
              && fluid.fluidTank().getFluidAmount() == 0
              && fluidB.fluidTank().getFluidAmount() == 0,
          "Restarted paid run did not resume and finish exactly once: " + controller
              .recipeRunStatus() + ", output=" + outputCount + ", diamond=" + diamondCount + ", energy=" + energy
                  .energy().stored() + ", fluids=" + fluid.fluidTank().getFluidAmount() + "/" + fluidB.fluidTank()
                      .getFluidAmount() + ", inputSlots=" + contents(input) + ", outputSlots=" + contents(output));
      Files.deleteIfExists(RUN_ID_FILE);
      helper.succeed();
    } catch (IOException | RuntimeException failure) {
      helper.fail("Could not complete restart recovery: " + failure);
    }
  }

  private static boolean containsOutput(FrozenItemRun run, String itemId) {
    var outputs = run.save().getList("outputs", Tag.TAG_COMPOUND);
    for (int i = 0; i < outputs.size(); i++) {
      if (outputs.getCompound(i).getCompound("stack").getString("id").equals(itemId)) {
        return true;
      }
    }
    return false;
  }

  private static void clearFixture(GameTestHelper helper) {
    for (int x = -2; x <= 2; x++) {
      for (int y = -1; y <= 2; y++) {
        for (int z = -2; z <= 3; z++) {
          helper.getLevel().setBlockAndUpdate(ORIGIN.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
      }
    }
  }

  private static void placePort(ServerLevel level, BlockPos origin, MachineOrientation pose, BlockPos local, String port) {
    level.setBlockAndUpdate(
        origin.offset(pose.toWorldOffset(local)),
        MachineRegistries.PORTS.get(port).get().defaultBlockState());
  }

  private static void confirm(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(controller.canCommitProduction(), "Restart fixture structure did not confirm");
    helper.assertTrue(MachineRecipeService.catalogFor(controller).isPresent(), "Restart recipe catalog unavailable");
  }

  private static void confirmStructure(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int i = 0; i < 200 && controller.verificationState() != VerificationState.CONFIRMED; i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(
        controller.verificationState() == VerificationState.CONFIRMED,
        "Restarted structure did not confirm: " + controller.statusMessage().getString());
  }

  private static MachinePortBlockEntity port(GameTestHelper helper, BlockPos pos) {
    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(pos);
  }

  private static int count(MachinePortBlockEntity port, Item item) {
    int count = 0;
    for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
      var stack = port.inventory().getStackInSlot(slot);
      if (stack.is(item)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  private static String contents(MachinePortBlockEntity port) {
    var contents = new ArrayList<String>();
    for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
      var stack = port.inventory().getStackInSlot(slot);
      if (!stack.isEmpty()) {
        contents.add(slot + "=" + stack);
      }
    }
    return port.kind() + contents.toString();
  }

  private static ResourceLocation machineId() {
    return ResourceLocation.fromNamespaceAndPath("mmcece", "test_recipe_energy_lifecycle");
  }
}
