package com.lw.modularmachine.gametest;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.RecipePlanner;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class RecipeEnergyLifecycleGameTests {
  private static final ResourceLocation MACHINE = id("test_recipe_energy_lifecycle");
  private static BlockPos failureActionOrigin;

  private RecipeEnergyLifecycleGameTests() {
  }

  @GameTest(template = "empty16", batch = "recipe_energy_lifecycle", timeoutTicks = 600)
  public static void fe_per_tick_is_atomic_waits_and_splits_across_input_hatches(GameTestHelper helper) {
    reload(helper, () -> {
      try {
        var controller = build(helper);
        var energyA = port(helper, "energy_a");
        var energyB = port(helper, "energy_b");
        energyA.energy().receiveEnergy(10, false);
        start(helper, controller, Items.DIRT);
        helper.runAfterDelay(2, () -> {
          try {
            controller.serverTick();
            controller.serverTick();
            helper.assertTrue(
                controller.runningRecipe().orElseThrow().progress() == 1 && energyA.energy().stored() == 5,
                "Same-tick run calls advanced or charged the FE fee more than once: progress=" + controller
                    .runningRecipe().map(run -> run.progress())
                    .orElse(-1) + ", energy=" + energyA.energy().stored() + ", status=" + controller.recipeRunStatus());
            helper.runAfterDelay(1, () -> {
              try {
                controller.serverTick();
                controller.serverTick();
                helper.assertTrue(
                    controller.runningRecipe().isEmpty()
                        && count(port(helper, "item_out"), Items.IRON_INGOT) == 1
                        && energyA.energy().stored() == 0,
                    "Paid FE run did not charge each progress tick and output exactly once");
                insufficientEnergyWaits(helper);
              } catch (RuntimeException failure) {
                helper.fail("FE run completion failed: " + failure);
              }
            });
          } catch (RuntimeException failure) {
            helper.fail("FE per-tick charge failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("FE recipe fixture failed: " + failure);
      }
    });
  }

  @GameTest(template = "empty16", batch = "recipe_failure_policy_lifecycle", timeoutTicks = 600)
  public static void reset_and_decrease_actions_apply_on_resource_shortage_and_preserve_fees(GameTestHelper helper) {
    reload(helper, () -> {
      try {
        var reset = buildFailureActionFixture(helper);
        var resetEngine = new ItemRunEngine();
        var resetEnergy = RecipeResourceTicketGameTests.port(helper, reset, "energy_a");
        resetEnergy.energy().receiveEnergy(5, false);
        start(helper, reset, Items.DIRT);
        afterWorldTick(helper, helper.getLevel().getGameTime(), () -> {
          try {
            resetEngine.tick(reset, helper.getLevel());
            helper
                .assertTrue(reset.runningRecipe().orElseThrow().progress() == 1, "Reset fixture did not advance once");
            afterWorldTick(helper, helper.getLevel().getGameTime(), () -> {
              try {
                resetEngine.tick(reset, helper.getLevel());
                var resetRun = reset.runningRecipe().orElseThrow();
                helper.assertTrue(
                    resetRun.progress() == 0
                        && resetEnergy.energy().stored() == 0
                        && resetEngine.status() == ItemRunEngine.Result.WAITING_ENERGY,
                    "reset did not clear progress: progress=" + resetRun.progress() + ", energy=" + resetEnergy.energy()
                        .stored() + ", status=" + resetEngine.status());
                startDecreaseFailureAction(helper);
              } catch (RuntimeException failure) {
                helper.fail("Reset failure-action validation failed: " + failure);
              }
            });
          } catch (RuntimeException failure) {
            helper.fail("Reset failure-action validation failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("Failure-action fixture failed: " + failure);
      }
    });
  }

  private static void startDecreaseFailureAction(GameTestHelper helper) {
    var decrease = buildFailureActionFixture(helper);
    var energy = RecipeResourceTicketGameTests.port(helper, decrease, "energy_a");
    energy.energy().receiveEnergy(15, false);
    RecipeResourceTicketGameTests.port(helper, decrease, "item_in").inventory()
        .setStackInSlot(0, new ItemStack(Items.MAGMA_BLOCK));
    var plan = ItemRecipeSearch.begin(decrease).orElseThrow().advance(10_000).plan().orElseThrow();
    helper.assertTrue(
        plan.recipe().id().endsWith("/test_energy_paid_sand_decrease"),
        "Decrease fixture selected unexpected recipe: " + plan.recipe().id());
    helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Decrease fixture did not start");
    advanceDecreaseFixture(helper, decrease, energy, new ItemRunEngine(), 1);
  }

  private static void advanceDecreaseFixture(GameTestHelper helper, MachineControllerBlockEntity decrease, MachinePortBlockEntity energy, ItemRunEngine engine, int attempt) {
    afterWorldTick(helper, helper.getLevel().getGameTime(), () -> {
      try {
        engine.tick(decrease, helper.getLevel());
        var waiting = decrease.runningRecipe().orElseThrow();
        if (attempt <= 3) {
          helper.assertTrue(
              waiting.progress() == attempt && energy.energy().stored() == 15 - attempt * 5,
              "Decrease fixture did not atomically pay one fee per world tick: " + attempt);
          advanceDecreaseFixture(helper, decrease, energy, engine, attempt + 1);
          return;
        }
        helper.assertTrue(
            waiting.progress() == 2
                && energy.energy().stored() == 0
                && engine.status() == ItemRunEngine.Result.WAITING_ENERGY,
            "decrease did not reduce progress once or charged the missing tick: progress=" + waiting
                .progress() + ", energy=" + energy.energy().stored() + ", status=" + engine.status());
        helper.succeed();
      } catch (RuntimeException failure) {
        helper.fail("Decrease failure-action validation failed: " + failure);
      }
    });
  }

  private static void afterWorldTick(GameTestHelper helper, long previousTick, Runnable action) {
    helper.runAfterDelay(1, () -> {
      if (helper.getLevel().getGameTime() <= previousTick) {
        afterWorldTick(helper, previousTick, action);
        return;
      }
      action.run();
    });
  }

  private static MachineControllerBlockEntity buildFailureActionFixture(GameTestHelper helper) {
    // Explicitly loaded outside the GameTest ticking area: only this fixture's
    // engine advances the run, so automatic BE ticks cannot consume a test step.
    failureActionOrigin = helper.absolutePos(new BlockPos(7, 5, 7)).offset(10000, 0, 10000);
    for (int x : new int[]{-2, 2}) {
      for (int z : new int[]{-2, 3}) {
        helper.getLevel().getChunkAt(failureActionOrigin.offset(x, 0, z));
      }
    }

    return buildAt(helper, failureActionOrigin);
  }

  @AfterBatch(batch = "recipe_failure_policy_lifecycle")
  public static void clearFailureActionFixture(ServerLevel level) {
    if (failureActionOrigin == null) {
      return;
    }
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        for (int z = -2; z <= 3; z++) {
          var position = failureActionOrigin.offset(x, y, z);
          if (level.hasChunkAt(position)) {
            level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
          }
        }
      }
    }
    failureActionOrigin = null;
  }

  @GameTest(template = "empty16", batch = "recipe_energy_lifecycle", timeoutTicks = 600)
  public static void fixed_fluid_input_is_required_during_search_and_consumed_at_start(GameTestHelper helper) {
    reload(helper, () -> {
      try {
        var controller = build(helper);
        var input = port(helper, "item_in");
        var fluid = port(helper, "fluid_a");
        input.inventory().setStackInSlot(0, new ItemStack(Items.ENDER_PEARL));
        var missing = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000);
        helper.assertTrue(missing.plan().isEmpty(), "Fluid-input recipe was selectable without its required fluid");
        fluid.fluidTank().fill(new FluidStack(Fluids.WATER, 40), FluidAction.EXECUTE);
        var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
        helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Fluid-input recipe did not start");
        helper.assertTrue(
            fluid.fluidTank().isEmpty() && input.inventory().getStackInSlot(0).isEmpty(),
            "Fluid-input startup did not atomically consume both resources");
        helper.runAfterDelay(2, () -> {
          try {
            controller.serverTick();
            helper.assertTrue(
                controller.runningRecipe().isEmpty() && count(port(helper, "item_out"), Items.QUARTZ) == 1,
                "Fluid-input run did not finish exactly once: status=" + controller
                    .recipeRunStatus() + ", run=" + controller.runningRecipe()
                        .map(run -> run.progress() + "/" + run.duration()) + ", output=" + count(
                            port(helper, "item_out"),
                            Items.QUARTZ));
            helper.succeed();
          } catch (RuntimeException failure) {
            helper.fail("Fluid-input run completion failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("Fluid-input startup validation failed: " + failure);
      }
    });
  }

  @GameTest(template = "empty16", batch = "recipe_energy_lifecycle", timeoutTicks = 600)
  public static void fixed_fluid_output_commits_with_item_output_atomically(GameTestHelper helper) {
    reload(helper, () -> {
      try {
        var controller = buildFluidOutput(helper);
        var input = port(helper, "item_in");
        input.inventory().setStackInSlot(0, new ItemStack(Items.CHORUS_FRUIT));
        var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
        helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Fluid-output recipe did not start");
        var fluid = port(helper, "fluid_out");
        helper.assertTrue(fluid.fluidTank().isEmpty(), "Fluid output was committed during startup");
        helper.runAfterDelay(2, () -> {
          try {
            controller.serverTick();
            helper.assertTrue(
                controller.runningRecipe().isEmpty()
                    && count(port(helper, "item_out"), Items.IRON_INGOT) == 1
                    && fluid.fluidTank().getFluid().getFluid() == Fluids.WATER
                    && fluid.fluidTank().getFluidAmount() == 40,
                "Item and fluid outputs were not committed exactly once");
            helper.succeed();
          } catch (RuntimeException failure) {
            helper.fail("Fluid-output completion failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("Fluid-output startup validation failed: " + failure);
      }
    });
  }

  @GameTest(template = "empty16", batch = "fluid_output_recovery", timeoutTicks = 1200)
  public static void fluid_outputs_check_admission_add_only_the_delta_and_resume_saved_pending_run(GameTestHelper helper) {
    reload(helper, () -> {
      var controller = buildFluidOutput(helper);
      var input = port(helper, "item_in");
      var fluid = port(helper, "fluid_out");
      input.inventory().setStackInSlot(0, new ItemStack(Items.CHORUS_FRUIT));
      fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 960));
      var search = ItemRecipeSearch.begin(controller).orElseThrow();
      helper.assertTrue(search.advance(0).status() == RecipePlanner.Status.BUDGET_EXHAUSTED, "Zero budget did work");
      ItemRecipeSearch.Result result;
      int calls = 0;
      do {
        result = search.advance(1);
        helper.assertTrue(result.workUsed() <= 1 && ++calls < 1000, "Fluid admission exceeded its work slice");
      } while (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED);
      var stale = result.plan().orElseThrow();
      helper.assertTrue(fluid.fluidTank().getFluidAmount() == 960, "Search changed existing fluid");
      fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 981));
      helper
          .assertTrue(stale.start() == AtomicRecipeStarter.Result.STALE, "Output mutation retained startup authority");
      helper.assertTrue(
          ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().isEmpty(),
          "Insufficient fluid output capacity admitted a recipe");
      helper.assertTrue(input.inventory().getStackInSlot(0).getCount() == 1, "Failed admission consumed input");
      fluid.fluidTank().setFluid(new FluidStack(Fluids.LAVA, 960));
      var fallback = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
      helper.assertTrue(
          fallback.recipe().id().endsWith("/test_fluid_output_fallback"),
          "Blocked water output did not fall through to the lower-priority lava recipe");
      fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 960));
      var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
      helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Water recipe failed to start");
      helper.runAfterDelay(2, () -> {
        controller.serverTick();
        helper.assertTrue(
            controller.runningRecipe().isEmpty()
                && fluid.fluidTank().getFluidAmount() == 1000
                && count(port(helper, "item_out"), Items.IRON_INGOT) == 1,
            "Output to an existing stack did not add exactly 40 mB: " + controller.recipeRunStatus());
        savedPendingFluidOutput(helper);
      });
    });
  }

  @GameTest(template = "empty16", batch = "fluid_output_allocation", timeoutTicks = 600)
  public static void mixed_fluids_share_capacity_and_backtrack_between_unequal_tanks(GameTestHelper helper) {
    reload(helper, () -> {
      var controller = buildFluidOutput(helper);
      var origin = controller.getBlockPos();
      place(helper, origin, new BlockPos(0, -1, 1), "fluid_output_hatch_tiny");
      controller.bindMachine(id("test_recipe_fluid_allocation"));
      confirm(helper, controller);
      var large = port(helper, "fluid_out");
      var small = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(0, -1, 1));
      port(helper, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.CHORUS_FRUIT));
      var search = ItemRecipeSearch.begin(controller).orElseThrow();
      ItemRecipeSearch.Result result;
      int calls = 0;
      do {
        result = search.advance(1);
        helper.assertTrue(result.workUsed() <= 1 && ++calls < 1000, "Exact fluid search failed to resume");
      } while (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED);
      helper.assertTrue(large.fluidTank().isEmpty() && small.fluidTank().isEmpty(), "Admission filled a tank");
      helper.assertTrue(
          result.plan().orElseThrow().start() == AtomicRecipeStarter.Result.STARTED,
          "Exact fluid-output assignment was incorrectly rejected");
      helper.runAfterDelay(2, () -> {
        controller.serverTick();
        helper.assertTrue(
            controller.runningRecipe().isEmpty()
                && large.fluidTank().getFluid().getFluid() == Fluids.LAVA
                && large.fluidTank().getFluidAmount() == 1000
                && small.fluidTank().getFluid().getFluid() == Fluids.WATER
                && small.fluidTank().getFluidAmount() == 100
                && count(port(helper, "item_out"), Items.DIAMOND) == 1,
            "Mixed fluids claimed capacity twice or used a greedy assignment");
        helper.succeed();
      });
    });
  }

  private static void savedPendingFluidOutput(GameTestHelper helper) {
    var controller = buildFluidOutput(helper);
    var input = port(helper, "item_in");
    var output = port(helper, "item_out");
    var fluid = port(helper, "fluid_out");
    input.inventory().setStackInSlot(0, new ItemStack(Items.CHORUS_FRUIT));
    var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
    helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Pending fixture failed to start");
    var runId = controller.runningRecipe().orElseThrow().runId();
    // Only occupancy introduced after startup may leave this run blocked.
    fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 980));
    helper.runAfterDelay(2, () -> {
      controller.serverTick();
      helper.assertTrue(
          controller.runningRecipe().orElseThrow().pendingOutput()
              && output.inventory().getStackInSlot(0).isEmpty()
              && fluid.fluidTank().getFluidAmount() == 980,
          "Blocked fluid output partially committed or lost its run: " + controller.recipeRunStatus());
      var restored = restoreController(helper, controller);
      helper.assertTrue(
          restored.runningRecipe().orElseThrow().runId().equals(runId)
              && restored.runningRecipe().orElseThrow().pendingOutput()
              && restored.runningRecipe().orElseThrow().fluidOutputClaims(helper.getLevel().registryAccess()).getFirst()
                  .getAmount() == 40,
          "Pending fluid claims were not restored with their run identity");
      fluid.fluidTank().drain(20, FluidAction.EXECUTE);
      helper.runAfterDelay(2, () -> {
        restored.serverTick();
        helper.assertTrue(
            restored.runningRecipe().isEmpty()
                && fluid.fluidTank().getFluidAmount() == 1000
                && count(output, Items.IRON_INGOT) == 1,
            "Restored pending output did not resume: " + restored.recipeRunStatus());
        restored.serverTick();
        helper.assertTrue(
            fluid.fluidTank().getFluidAmount() == 1000 && count(output, Items.IRON_INGOT) == 1,
            "Repeated finish duplicated output");
        helper.succeed();
      });
    });
  }

  private static void insufficientEnergyWaits(GameTestHelper helper) {
    var waitingController = build(helper);
    var waitingEnergy = port(helper, "energy_a");
    waitingEnergy.energy().receiveEnergy(4, false);
    start(helper, waitingController, Items.SAND);
    helper.runAfterDelay(3, () -> {
      try {
        waitingController.serverTick();
        helper.assertTrue(
            waitingController.runningRecipe().orElseThrow().progress() == 0 && waitingEnergy.energy().stored() == 4,
            "Insufficient FE changed progress or consumed a partial fee");
        waitingEnergy.energy().receiveEnergy(1, false);
        helper.runAfterDelay(2, () -> {
          try {
            waitingController.serverTick();
            helper.assertTrue(
                waitingController.runningRecipe().isEmpty()
                    && count(port(helper, "item_out"), Items.GOLD_INGOT) == 1
                    && waitingEnergy.energy().stored() == 0,
                "Run did not resume after the full FE fee became available");
            splitEnergyHatches(helper);
          } catch (RuntimeException failure) {
            helper.fail("FE wait recovery failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("FE wait-state validation failed: " + failure);
      }
    });
  }

  private static void splitEnergyHatches(GameTestHelper helper) {
    var controller = build(helper);
    var energyA = port(helper, "energy_a");
    var energyB = port(helper, "energy_b");
    energyA.energy().receiveEnergy(3, false);
    energyB.energy().receiveEnergy(5, false);
    start(helper, controller, Items.GRAVEL);
    helper.runAfterDelay(2, () -> {
      try {
        controller.serverTick();
        helper.assertTrue(
            controller.runningRecipe().isEmpty()
                && count(port(helper, "item_out"), Items.DIAMOND) == 1
                && energyA.energy().stored() == 0
                && energyB.energy().stored() == 0,
            "FE fee was not atomically split across energy input hatches");
        fluidFeeWaitsWithoutPartiallyCharging(helper);
      } catch (RuntimeException failure) {
        helper.fail("Multi-hatch FE payment failed: " + failure);
      }
    });
  }

  private static void fluidFeeWaitsWithoutPartiallyCharging(GameTestHelper helper) {
    var controller = build(helper);
    var energy = port(helper, "energy_a");
    var fluidA = port(helper, "fluid_a");
    var fluidB = port(helper, "fluid_b");
    energy.energy().receiveEnergy(5, false);
    fluidA.fluidTank().fill(new FluidStack(Fluids.WATER, 30), FluidAction.EXECUTE);
    fluidB.fluidTank().fill(new FluidStack(Fluids.LAVA, 20), FluidAction.EXECUTE);
    start(helper, controller, Items.NETHERRACK);
    helper.runAfterDelay(3, () -> {
      try {
        controller.serverTick();
        helper.assertTrue(
            controller.runningRecipe().orElseThrow().progress() == 0
                && energy.energy().stored() == 5
                && fluidA.fluidTank().getFluidAmount() == 30
                && fluidB.fluidTank().getFluidAmount() == 20,
            "Missing matching fluid partially charged a hybrid FE/fluid tick");
        var restored = restoreController(helper, controller);
        var restoredFluidFee = restored.runningRecipe().orElseThrow().fluidPerTick(helper.getLevel().registryAccess());
        helper.assertTrue(
            restoredFluidFee.getFluid() == Fluids.WATER && restoredFluidFee.getAmount() == 50,
            "Fluid fee was not restored from the frozen recipe definition");
        fluidA.fluidTank().fill(new FluidStack(Fluids.WATER, 20), FluidAction.EXECUTE);
        helper.runAfterDelay(2, () -> {
          try {
            restored.serverTick();
            helper.assertTrue(
                restored.runningRecipe().isEmpty()
                    && count(port(helper, "item_out"), Items.EMERALD) == 1
                    && energy.energy().stored() == 0
                    && fluidA.fluidTank().isEmpty()
                    && fluidB.fluidTank().getFluid().getFluid() == Fluids.LAVA,
                "Hybrid fee did not resume atomically or consumed the wrong fluid");
            splitFluidHatches(helper);
          } catch (RuntimeException failure) {
            helper.fail("Hybrid FE/fluid fee recovery failed: " + failure);
          }
        });
      } catch (RuntimeException failure) {
        helper.fail("Fluid shortage validation failed: " + failure);
      }
    });
  }

  private static void splitFluidHatches(GameTestHelper helper) {
    var controller = build(helper);
    var fluidA = port(helper, "fluid_a");
    var fluidB = port(helper, "fluid_b");
    fluidA.fluidTank().fill(new FluidStack(Fluids.WATER, 30), FluidAction.EXECUTE);
    fluidB.fluidTank().fill(new FluidStack(Fluids.WATER, 20), FluidAction.EXECUTE);
    var rollback = fluidA.acquireFluidStart(fluidA.resourceRevision());
    helper.assertTrue(rollback.drainExact(new FluidStack(Fluids.WATER, 10)), "Fluid lease could not prepare a debit");
    rollback.resolve(false);
    helper.assertTrue(fluidA.fluidTank().getFluidAmount() == 30, "Rolled back fluid lease changed the tank");
    start(helper, controller, Items.OBSIDIAN);
    helper.runAfterDelay(2, () -> {
      try {
        controller.serverTick();
        helper.assertTrue(
            controller.runningRecipe().isEmpty()
                && count(port(helper, "item_out"), Items.AMETHYST_SHARD) == 1
                && fluidA.fluidTank().isEmpty()
                && fluidB.fluidTank().isEmpty(),
            "Fluid fee was not split across matching input tanks");
        helper.succeed();
      } catch (RuntimeException failure) {
        helper.fail("Multi-hatch fluid fee failed: " + failure);
      }
    });
  }

  private static void reload(GameTestHelper helper, Runnable next) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.fail("FE recipe reload failed: " + failure);
            return;
          }
          next.run();
        }));
  }

  private static MachineControllerBlockEntity build(GameTestHelper helper) {
    return buildAt(helper, helper.absolutePos(new BlockPos(7, 5, 7)));
  }

  private static MachineControllerBlockEntity buildAt(GameTestHelper helper, BlockPos origin) {
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        for (int z = -2; z <= 3; z++) {
          helper.getLevel().setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
      }
    }
    var pose = MachineOrientation.IDENTITY;
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    place(helper, origin, new BlockPos(-1, 0, 1), "item_input_bus_normal");
    place(helper, origin, new BlockPos(1, 0, 1), "item_output_bus_normal");
    place(helper, origin, new BlockPos(0, 1, 1), "energy_input_hatch_normal");
    place(helper, origin, new BlockPos(0, -1, 1), "energy_input_hatch_normal");
    place(helper, origin, new BlockPos(1, 1, 1), "fluid_input_hatch_normal");
    place(helper, origin, new BlockPos(1, -1, 1), "fluid_input_hatch_normal");
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(MACHINE);
    confirm(helper, controller);

    return controller;
  }

  private static MachineControllerBlockEntity buildFluidOutput(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    for (int x = -2; x <= 2; x++) {
      for (int y = -2; y <= 2; y++) {
        for (int z = -2; z <= 3; z++) {
          helper.getLevel().setBlockAndUpdate(origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
      }
    }
    var pose = MachineOrientation.IDENTITY;
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    place(helper, origin, new BlockPos(-1, 0, 1), "item_input_bus_normal");
    place(helper, origin, new BlockPos(1, 0, 1), "item_output_bus_normal");
    place(helper, origin, new BlockPos(0, 1, 1), "fluid_output_hatch_normal");
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_recipe_fluid_output"));
    confirm(helper, controller);
    return controller;
  }

  private static MachineControllerBlockEntity restoreController(GameTestHelper helper, MachineControllerBlockEntity controller) {
    var level = helper.getLevel();
    var position = controller.getBlockPos();
    var state = controller.getBlockState();
    var saved = controller.saveWithFullMetadata(level.registryAccess());
    level.removeBlockEntity(position);
    var restored = (MachineControllerBlockEntity) BlockEntity
        .loadStatic(position, state, saved, level.registryAccess());
    level.setBlockEntity(restored);
    restored.onLoad();
    confirm(helper, restored);

    return restored;
  }

  private static void place(GameTestHelper helper, BlockPos origin, BlockPos local, String block) {
    helper.getLevel().setBlockAndUpdate(
        origin.offset(MachineOrientation.IDENTITY.toWorldOffset(local)),
        MachineRegistries.PORTS.get(block).get().defaultBlockState());
  }

  private static void start(GameTestHelper helper, MachineControllerBlockEntity controller, Item input) {
    RecipeResourceTicketGameTests.port(helper, controller, "item_in").inventory()
        .setStackInSlot(0, new ItemStack(input));
    var search = ItemRecipeSearch.begin(controller).orElseThrow();
    var plan = search.advance(10_000).plan().orElseThrow();
    helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "FE recipe did not start");
  }

  private static void confirm(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int index = 0; index < 200 && !controller.canCommitProduction(); index++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(controller.canCommitProduction(), "FE recipe structure failed to confirm");
  }

  private static MachinePortBlockEntity port(GameTestHelper helper, String part) {
    var anchor = findController(helper);
    var local = switch (part) {
      case "item_in" -> new BlockPos(-1, 0, 1);
      case "item_out" -> new BlockPos(1, 0, 1);
      case "energy_a" -> new BlockPos(0, 1, 1);
      case "energy_b" -> new BlockPos(0, -1, 1);
      case "fluid_a" -> new BlockPos(1, 1, 1);
      case "fluid_b" -> new BlockPos(1, -1, 1);
      case "fluid_out" -> new BlockPos(0, 1, 1);
      default -> throw new IllegalArgumentException("Unknown test port " + part);
    };

    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(anchor.offset(local));
  }

  private static BlockPos findController(GameTestHelper helper) {
    return helper.absolutePos(new BlockPos(7, 5, 7));
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

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
