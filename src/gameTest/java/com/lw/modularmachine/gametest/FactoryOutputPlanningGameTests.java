package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class FactoryOutputPlanningGameTests {
  private static final String THREAD = "output_thread";
  private static final ResourceLocation MACHINE = id("test_factory_output_planning");
  private static int savedBudget = -1;

  private static final class Fixture {
    FactoryControllerBlockEntity factory;
    MachinePortBlockEntity input;
    MachinePortBlockEntity output;
    MachinePortBlockEntity fluid;
  }

  @AfterBatch(batch = "factory_output_planning")
  public static void cleanup(ServerLevel level) {
    if (savedBudget >= 0) {
      MachineConfig.FACTORY_OUTPUT_WORK.set(savedBudget);
      savedBudget = -1;
    }
  }

  @GameTest(template = "empty16", batch = "factory_output_planning", timeoutTicks = 1000)
  public static void factory_output_sessions_resume_after_blockage_rebuild_and_input_changes(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> after(helper, () -> {
          if (failure != null) {
            helper.fail("Factory output reload failed: " + failure);
            return;
          }
          savedBudget = MachineConfig.FACTORY_OUTPUT_WORK.get();
          MachineConfig.FACTORY_OUTPUT_WORK.set(1);
          var fixture = build(helper);
          fixture.input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
          fixture.factory.serverTick();
          until(helper, 100, () -> {
            fixture.factory.serverTick();
            var runs = fixture.factory.runSet(THREAD).runs();

            return runs.size() == 2 && runs.stream().allMatch(run -> run.pendingOutput());
          }, () -> {
            var thread = fixture.factory.scheduler().channel(THREAD);
            helper.assertTrue(
                thread.state() == FactoryThreadState.State.WAITING
                    && thread.reason() == FactoryThreadState.Reason.OUTPUT_PLANNING,
                "Finished recipe was reported as output blocked before output planning");
            blockBothOutputs(helper, fixture);
          });
        })));
  }

  @GameTest(template = "empty16", batch = "factory_output_planning", timeoutTicks = 400)
  public static void available_outputs_commit_and_next_recipe_starts_in_the_completion_tick(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> after(helper, () -> {
          if (failure != null) {
            helper.fail("Factory output reload failed: " + failure);
            return;
          }
          savedBudget = MachineConfig.FACTORY_OUTPUT_WORK.get();
          MachineConfig.FACTORY_OUTPUT_WORK.set(65_536);
          var fixture = build(helper);
          var thread = fixture.factory.scheduler().channel(THREAD);
          helper.assertTrue(
              fixture.factory
                  .updateThread(THREAD, thread.revision(), false, null, null, 1, thread.upgradeRevision() + 1),
              "Could not set single-run factory throughput for seamless completion test");
          fixture.input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
          until(helper, 100, () -> {
            fixture.factory.serverTick();
            var runs = fixture.factory.runSet(THREAD).runs();
            return runs.size() == 1 && fixture.input.inventory().getStackInSlot(0).getCount() == 1;
          }, () -> until(helper, 100, () -> {
            fixture.factory.serverTick();
            var runs = fixture.factory.runSet(THREAD).runs();
            return countIron(fixture) == 1
                && fixture.fluid.fluidTank().getFluidAmount() == 40
                && runs.size() == 1
                && !runs.getFirst().pendingOutput()
                && fixture.input.inventory().getStackInSlot(0).isEmpty();
          }, () -> {
            helper.assertTrue(
                fixture.factory.scheduler().channel(THREAD).state() == FactoryThreadState.State.RUNNING,
                "Next recipe did not start after same-tick output commit");
            cleanup(helper.getLevel());
            helper.succeed();
          }));
        })));
  }

  private static void blockBothOutputs(GameTestHelper helper, Fixture fixture) {
    for (int index = 0; index < 200; index++) {
      fixture.factory.serverTick();
    }
    helper.assertTrue(
        countIron(fixture) == 0 && fixture.fluid.fluidTank().isEmpty(),
        "Repeated same-tick calls bypassed the output planning budget");
    for (int slot = 0; slot < fixture.output.inventory().getSlots(); slot++) {
      fixture.output.inventory().setStackInSlot(slot, new ItemStack(Items.STONE, 64));
    }
    fixture.fluid.fluidTank().fill(new FluidStack(Fluids.LAVA, 1000), FluidAction.EXECUTE);
    until(helper, 100, () -> {
      fixture.factory.serverTick();

      return reason(fixture) == FactoryThreadState.Reason.OUTPUT_BLOCKED;
    }, () -> rebuildBlockedFactory(helper, fixture));
  }

  private static void rebuildBlockedFactory(GameTestHelper helper, Fixture fixture) {
    var level = helper.getLevel();
    var factory = fixture.factory;
    var position = factory.getBlockPos();
    var state = factory.getBlockState();
    var identities = factory.runSet(THREAD).runs().stream().map(run -> run.runId()).toList();
    var saved = factory.saveWithFullMetadata(level.registryAccess());
    level.removeBlockEntity(position);
    fixture.factory = (FactoryControllerBlockEntity) BlockEntity
        .loadStatic(position, state, saved, level.registryAccess());
    level.setBlockEntity(fixture.factory);
    fixture.factory.onLoad();
    confirm(helper, fixture.factory);
    helper.assertTrue(
        fixture.factory.runSet(THREAD).runs().stream().map(run -> run.runId()).toList().equals(identities),
        "Rebuilding the output planner replaced frozen run identities");
    for (int slot = 0; slot < fixture.output.inventory().getSlots(); slot++) {
      fixture.output.inventory().setStackInSlot(slot, ItemStack.EMPTY);
    }
    until(helper, 100, () -> {
      fixture.factory.serverTick();

      return reason(fixture) == FactoryThreadState.Reason.OUTPUT_BLOCKED;
    }, () -> {
      helper.assertTrue(
          countIron(fixture) == 0
              && fixture.factory.runSet(THREAD).runs().size() == 2
              && fixture.fluid.fluidTank().getFluid().getFluid() == Fluids.LAVA,
          "Fluid blockage partially committed item outputs or discarded paid runs");
      fixture.fluid.fluidTank().drain(Integer.MAX_VALUE, FluidAction.EXECUTE);
      awaitCompletion(helper, fixture, 2, () -> startSingleRun(helper, fixture));
    });
  }

  private static void awaitCompletion(GameTestHelper helper, Fixture fixture, int expectedIron, Runnable next) {
    until(helper, 150, () -> {
      fixture.input.inventory()
          .setStackInSlot(0, new ItemStack(Items.COBBLESTONE, (int) (helper.getLevel().getGameTime() % 63) + 1));
      fixture.factory.serverTick();
      int iron = countIron(fixture);
      int water = fixture.fluid.fluidTank().getFluidAmount();
      int beforeIron = expectedIron == 2 ? 0 : 2;
      int beforeWater = beforeIron * 40;
      helper.assertTrue(
          iron == beforeIron && water == beforeWater || iron == expectedIron && water == expectedIron * 40,
          "Item and fluid outputs were not committed in the same transaction");

      return fixture.factory.runSet(THREAD).runs().isEmpty();
    }, () -> {
      helper.assertTrue(
          countIron(fixture) == expectedIron && fixture.fluid.fluidTank().getFluidAmount() == expectedIron * 40,
          "Budgeted output planning lost, duplicated, or starved frozen outputs");
      next.run();
    });
  }

  private static void startSingleRun(GameTestHelper helper, Fixture fixture) {
    var thread = fixture.factory.scheduler().channels().getFirst();
    helper.assertTrue(
        fixture.factory.updateThread(THREAD, thread.revision(), false, null, null, 1, thread.upgradeRevision() + 1),
        "Could not switch the same output engine to a single run");
    fixture.input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    until(helper, 100, () -> {
      fixture.factory.serverTick();

      return fixture.factory.runSet(THREAD).runs().size() == 1;
    }, () -> awaitCompletion(helper, fixture, 3, () -> {
      cleanup(helper.getLevel());
      helper.succeed();
    }));
  }

  private static Fixture build(GameTestHelper helper) {
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(position, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    level.setBlockAndUpdate(
        position.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        position.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        position.offset(0, 1, 1),
        MachineRegistries.PORTS.get("fluid_output_hatch_normal").get().defaultBlockState());
    var fixture = new Fixture();
    fixture.factory = (FactoryControllerBlockEntity) level.getBlockEntity(position);
    fixture.factory.bindMachine(MACHINE);
    fixture.factory.replaceThreads(
        List.of(
            new FactoryThreadState(
                THREAD,
                MACHINE,
                id("mmcece/test_factory_output_planning"),
                true,
                0,
                2,
                0,
                FactoryThreadState.State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                new FactoryUpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 1))));
    fixture.input = (MachinePortBlockEntity) level.getBlockEntity(position.offset(-1, 0, 1));
    fixture.output = (MachinePortBlockEntity) level.getBlockEntity(position.offset(1, 0, 1));
    fixture.fluid = (MachinePortBlockEntity) level.getBlockEntity(position.offset(0, 1, 1));
    confirm(helper, fixture.factory);

    return fixture;
  }

  private static void confirm(GameTestHelper helper, FactoryControllerBlockEntity factory) {
    for (int attempt = 0; attempt < 200 && !factory.canCommitStructure(); attempt++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(factory.canCommitStructure(), "Factory output test structure did not confirm");
  }

  private static int countIron(Fixture fixture) {
    int count = 0;
    for (int slot = 0; slot < fixture.output.inventory().getSlots(); slot++) {
      var stack = fixture.output.inventory().getStackInSlot(slot);
      if (stack.is(Items.IRON_INGOT)) {
        count += stack.getCount();
      }
    }

    return count;
  }

  private static FactoryThreadState.Reason reason(Fixture fixture) {
    return fixture.factory.scheduler().channels().getFirst().reason();
  }

  private static void until(GameTestHelper helper, int attempts, BooleanSupplier condition, Runnable next) {
    after(helper, () -> {
      if (condition.getAsBoolean()) {
        next.run();
      } else if (attempts == 0) {
        helper.fail("Factory output lifecycle did not settle within its tick budget");
      } else {
        until(helper, attempts - 1, condition, next);
      }
    });
  }

  private static void after(GameTestHelper helper, Runnable action) {
    helper.runAfterDelay(1, () -> {
      try {
        action.run();
      } catch (RuntimeException | AssertionError failure) {
        cleanup(helper.getLevel());
        throw failure;
      }
    });
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
