package com.lw.modularmachine.gametest;

import com.google.gson.JsonParser;
import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class ItemRunLifecycleGameTests {
  private static int savedBudget = -1;
  private static Map<ResourceLocation, MachineDefinition> savedDefinitions;
  private static final ResourceLocation MACHINE = id("test_recipe_lifecycle");

  @AfterBatch(batch = "paid_run_lifecycle")
  public static void cleanup(ServerLevel level) {
    if (savedBudget >= 0) {
      MachineConfig.RECIPE_OUTPUT_WORK.set(savedBudget);
      savedBudget = -1;
    }
    if (savedDefinitions != null) {
      var restore = savedDefinitions;
      savedDefinitions = null;
      StructureService.replaceDefinitions(restore);
    }
  }

  @GameTest(template = "empty16", batch = "paid_run_lifecycle", timeoutTicks = 2400)
  public static void paid_item_run_progress_finish_pending_save_rotation_and_shared_output_matrix(GameTestHelper h) {
    reload(
        h,
        () -> simple(
            h,
            () -> zeroChanceOutput(
                h,
                () -> multiAndPortable(
                    h,
                    () -> large(
                        h,
                        () -> paused(
                            h,
                            () -> untracked(
                                h,
                                () -> orientations(
                                    h,
                                    0,
                                    () -> rotationDuringPaidRun(
                                        h,
                                        () -> pendingBindingChange(
                                            h,
                                            () -> sharedOutput(h, () -> changedBindingsAndRecipe(h))))))))))));
  }

  private static void zeroChanceOutput(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    start(h, controller, Items.CLAY_BALL);
    var output = port(h, controller, "output");
    after(h, 8, () -> {
      h.assertTrue(
          controller.runningRecipe().isEmpty() && count(output, Items.DIAMOND) == 0,
          "A run with only zero-chance outputs did not finish cleanly");
      next.run();
    });
  }

  private static void after(GameTestHelper h, int ticks, Runnable next) {
    h.runAfterDelay(ticks, () -> {
      try {
        next.run();
      } catch (RuntimeException | AssertionError failure) {
        cleanup(h.getLevel());
        ModularMachine.LOGGER.error("Paid run lifecycle callback failed", failure);
        throw failure;
      }
    });
  }

  private static void reload(GameTestHelper h, Runnable next) {
    var server = h.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> after(h, 1, () -> {
          if (failure != null) {
            h.fail("Paid run reload failed: " + failure);
          }
          next.run();
        })));
  }

  private static void simple(GameTestHelper h, Runnable next) {
    var c = build(h, MachineOrientation.IDENTITY);
    start(h, c, Items.DIRT);
    var input = port(h, c, "input");
    var output = port(h, c, "output");
    for (int i = 0; i < 10; i++) {
      c.serverTick();
    }
    h.assertTrue(
        c.runningRecipe().orElseThrow().progress() == 0,
        "Repeated same-tick calls manufactured paid progress");
    after(h, 14, () -> {
      h.assertTrue(
          c.runningRecipe().isEmpty()
              && count(output, Items.IRON_INGOT) == 1
              && input.inventory().getStackInSlot(0).isEmpty()
              && c.recipeRunStatus() == ItemRunEngine.Result.IDLE,
          "Simple paid run did not finish: " + c.recipeRunStatus());
      for (int i = 0; i < 5; i++) {
        c.serverTick();
      }
      h.assertTrue(count(output, Items.IRON_INGOT) == 1, "Repeated finish generated another output");
      seamlessNextRecipe(h, () -> runningRunEntityRestore(h, () -> pendingRunEntityRestore(h, next)));
    });
  }

  private static void seamlessNextRecipe(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    var input = port(h, controller, "input");
    var output = port(h, controller, "output");
    start(h, controller, Items.DIRT, 2);
    var firstRun = controller.runningRecipe().orElseThrow().runId();
    awaitSeamlessNextRecipe(h, controller, input, output, firstRun, 40, next);
  }

  private static void awaitSeamlessNextRecipe(GameTestHelper h, MachineControllerBlockEntity controller, MachinePortBlockEntity input, MachinePortBlockEntity output, UUID firstRun, int remaining, Runnable next) {
    after(h, 1, () -> {
      var current = controller.runningRecipe().orElse(null);
      if (count(output, Items.IRON_INGOT) == 1
          && input.inventory().getStackInSlot(0).isEmpty()
          && current != null
          && !current.pendingOutput()
          && !current.runId().equals(firstRun)) {
        h.assertTrue(
            current.progress() == 0 && controller.recipeRunStatus() == ItemRunEngine.Result.ADVANCED,
            "Next ordinary recipe was not started in the output-completion tick");
        next.run();
      } else if (remaining <= 0) {
        h.fail(
            "Ordinary controller inserted an idle tick after output commit: run=" + current + ", status=" + controller
                .recipeRunStatus());
      } else {
        awaitSeamlessNextRecipe(h, controller, input, output, firstRun, remaining - 1, next);
      }
    });
  }

  private static void runningRunEntityRestore(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    start(h, controller, Items.DIRT);
    var input = port(h, controller, "input");
    var output = port(h, controller, "output");
    after(h, 4, () -> {
      var run = controller.runningRecipe().orElseThrow();
      var saved = controller.saveWithFullMetadata(h.getLevel().registryAccess());
      var pos = controller.getBlockPos();
      var state = controller.getBlockState();
      h.getLevel().removeBlockEntity(pos);
      var replacement = (MachineControllerBlockEntity) BlockEntity
          .loadStatic(pos, state, saved, h.getLevel().registryAccess());
      h.getLevel().setBlockEntity(replacement);
      replacement.onLoad();
      confirm(h, replacement);
      var restored = replacement.runningRecipe().orElseThrow();
      h.assertTrue(
          restored.runId().equals(run.runId()) && restored.progress() == run.progress() && !restored.pendingOutput(),
          "Fresh controller entity did not restore the same running recipe state");
      h.assertTrue(input.inventory().getStackInSlot(0).isEmpty(), "Restoring the run charged input again");
      replacement.serverTick();
      replacement.serverTick();
      h.assertTrue(
          replacement.runningRecipe().orElseThrow().progress() <= restored.progress() + 1,
          "Restored engine advanced a paid run more than once in one game tick");
      after(h, 12, () -> {
        h.assertTrue(
            replacement.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 1,
            "Restored running recipe did not finish exactly once: " + replacement.recipeRunStatus());
        next.run();
      });
    });
  }

  private static void pendingRunEntityRestore(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    start(h, controller, Items.SAND);
    var output = port(h, controller, "output");
    fill(output, new ItemStack(Items.STONE, 64));
    output.inventory().setStackInSlot(0, ItemStack.EMPTY);
    after(h, 14, () -> {
      var pending = controller.runningRecipe().orElseThrow();
      h.assertTrue(pending.pendingOutput(), "Blocked run did not persist as OUTPUT_PENDING");
      var saved = controller.saveWithFullMetadata(h.getLevel().registryAccess());
      var pos = controller.getBlockPos();
      var state = controller.getBlockState();
      h.getLevel().removeBlockEntity(pos);
      var replacement = (MachineControllerBlockEntity) BlockEntity
          .loadStatic(pos, state, saved, h.getLevel().registryAccess());
      h.getLevel().setBlockEntity(replacement);
      replacement.onLoad();
      confirm(h, replacement);
      var restored = replacement.runningRecipe().orElseThrow();
      h.assertTrue(
          restored.pendingOutput() && restored.runId().equals(pending.runId()),
          "Fresh controller entity lost its frozen pending output");
      var input = port(h, replacement, "input");
      h.assertTrue(input.inventory().getStackInSlot(0).isEmpty(), "Pending restore charged inputs again");
      output.inventory().setStackInSlot(1, ItemStack.EMPTY);
      after(h, 14, () -> {
        h.assertTrue(
            replacement.runningRecipe().isEmpty()
                && count(output, Items.DIAMOND) == 1
                && count(output, Items.EMERALD) == 1,
            "Restored pending output did not settle exactly once: " + replacement.recipeRunStatus());
        next.run();
      });
    });
  }

  private static void multiAndPortable(GameTestHelper h, Runnable next) {
    var c = build(h, MachineOrientation.IDENTITY);
    start(h, c, Items.SAND);
    var output = port(h, c, "output");
    fill(output, new ItemStack(Items.STONE, 64));
    output.inventory().setStackInSlot(0, ItemStack.EMPTY);
    after(h, 14, () -> {
      var pending = c.runningRecipe().orElseThrow();
      h.assertTrue(
          pending.pendingOutput()
              && pending.progress() == 8
              && count(output, Items.DIAMOND) == 0
              && count(output, Items.EMERALD) == 0,
          "Two outputs reused one empty slot or failed to become pending");
      var saved = c.saveWithoutMetadata(h.getLevel().registryAccess());
      c.loadWithComponents(saved, h.getLevel().registryAccess());
      h.assertTrue(
          c.runningRecipe().orElseThrow().runId().equals(pending.runId()),
          "Pending save/load regenerated run identity");
      var drops = Block.getDrops(c.getBlockState(), h.getLevel(), c.getBlockPos(), c);
      var p = c.getBlockPos();
      h.assertTrue(drops.size() == 1, "Pending controller did not produce one carrier");
      h.getLevel().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
      h.getLevel().setBlockAndUpdate(p.below(), Blocks.STONE.defaultBlockState());
      var player = FakePlayerFactory.get(h.getLevel(), new GameProfile(new UUID(0, 0x240500L), "mmcece_finish"));
      player.setGameMode(GameType.SURVIVAL);
      player.setPos(p.getX() + 0.5, p.getY() + 2, p.getZ() + 0.5);
      player.setYRot(0);
      player.setXRot(90);
      player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst());
      player.gameMode.useItemOn(
          player,
          h.getLevel(),
          player.getMainHandItem(),
          InteractionHand.MAIN_HAND,
          new BlockHitResult(new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5), Direction.UP, p.below(), false));
      var placed = (MachineControllerBlockEntity) h.getLevel().getBlockEntity(p);
      h.assertTrue(
          placed != null
              && placed.runningRecipe().orElseThrow().pendingOutput()
              && placed.runningRecipe().orElseThrow().runId().equals(pending.runId())
              && player.getMainHandItem().isEmpty(),
          "Pending drop/place lost or replayed the frozen output buffer");
      StructureService.reorient(placed, MachineOrientation.IDENTITY);
      confirm(h, placed);
      var replacementInput = port(h, placed, "input");
      output.inventory().setStackInSlot(1, ItemStack.EMPTY);
      replacementInput.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 5));
      after(h, 14, () -> {
        h.assertTrue(
            placed.runningRecipe().isEmpty()
                && count(output, Items.DIAMOND) == 1
                && count(output, Items.EMERALD) == 1
                && count(output, Items.IRON_INGOT) == 0,
            "Pending run changed recipe or duplicated products after placement: " + placed.recipeRunStatus());
        h.assertTrue(
            replacementInput.inventory().getStackInSlot(0).getCount() == 5,
            "Pending output charged replacement input");
        next.run();
      });
    });
  }

  private static void large(GameTestHelper h, Runnable next) {
    var c = build(h, MachineOrientation.IDENTITY);
    start(h, c, Items.GRAVEL);
    var output = port(h, c, "output");
    fill(output, new ItemStack(Items.STONE, 64));
    output.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 63));
    after(h, 14, () -> {
      h.assertTrue(
          c.runningRecipe().orElseThrow().pendingOutput() && count(output, Items.IRON_INGOT) == 63,
          "Insufficient aggregate space caused partial insertion");
      output.inventory().setStackInSlot(0, ItemStack.EMPTY);
      output.inventory().setStackInSlot(1, ItemStack.EMPTY);
      after(h, 14, () -> {
        h.assertTrue(
            c.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 70,
            "Long output quantity was lost or duplicated");
        for (int i = 0; i < output.inventory().getSlots(); i++) {
          h.assertTrue(output.inventory().getStackInSlot(i).getCount() <= 64, "Output exceeded native stack size");
        }
        next.run();
      });
    });
  }

  private static void paused(GameTestHelper h, Runnable next) {
    var c = build(h, MachineOrientation.IDENTITY);
    start(h, c, Items.DIRT);
    var output = port(h, c, "output");
    var p = c.getBlockPos();
    var inputPos = port(h, c, "input").getBlockPos();
    after(h, 4, () -> {
      int progress = c.runningRecipe().orElseThrow().progress();
      h.getLevel().setBlockAndUpdate(
          p.north(),
          Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
              .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
              .setValue(BlockStateProperties.POWERED, true));
      h.assertTrue(h.getLevel().getDirectSignalTo(p) > 0, "Pause fixture did not supply redstone");
      after(h, 5, () -> {
        h.assertTrue(c.runningRecipe().orElseThrow().progress() == progress, "Redstone pause granted free progress");
        h.getLevel().setBlockAndUpdate(p.north(), Blocks.AIR.defaultBlockState());
        h.getLevel().setBlockAndUpdate(inputPos, Blocks.AIR.defaultBlockState());
        after(h, 5, () -> {
          h.assertTrue(
              c.runningRecipe().orElseThrow().progress() == progress && !c.canCommitProduction(),
              "Pending/Invalid zero-fee run advanced");
          h.getLevel().setBlockAndUpdate(
              inputPos,
              MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
          confirm(h, c);
          var repairedInput = (MachinePortBlockEntity) h.getLevel().getBlockEntity(inputPos);
          after(h, 14, () -> {
            h.assertTrue(
                c.runningRecipe().isEmpty()
                    && count(output, Items.IRON_INGOT) == 1
                    && repairedInput.inventory().getStackInSlot(0).isEmpty(),
                "Repair recharged input or lost the paid run");
            next.run();
          });
        });
      });
    });
  }

  private static void untracked(GameTestHelper h, Runnable next) {
    var c = build(h, MachineOrientation.IDENTITY);
    start(h, c, Items.DIRT);
    var output = port(h, c, "output");
    after(h, 3, () -> {
      int progress = c.runningRecipe().orElseThrow().progress();
      var pos = output.getBlockPos();
      var old = h.getLevel().getBlockState(pos);
      var chunk = h.getLevel().getChunkAt(pos);
      var section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
      section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, Blocks.STONE.defaultBlockState());
      after(h, 4, () -> {
        h.assertTrue(
            c.runningRecipe().orElseThrow().progress() == progress && !c.canCommitProduction(),
            "Conservative run guard missed a direct section write");
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, old);
        StructureService.requestAudit(c);
        confirm(h, c);
        after(h, 14, () -> {
          h.assertTrue(
              c.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 1,
              "Direct-write repair did not resume paid work");
          next.run();
        });
      });
    });
  }

  private static void orientations(GameTestHelper h, int index, Runnable next) {
    if (index == 24) {
      next.run();
      return;
    }
    var pose = MachineOrientation.all().get(index);
    var c = build(h, pose);
    var output = port(h, c, "output");
    start(h, c, Items.DIRT);
    after(h, 14, () -> {
      h.assertTrue(
          c.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 1,
          "Paid completion failed for " + pose + ": " + c.recipeRunStatus());
      orientations(h, index + 1, next);
    });
  }

  private static void rotationDuringPaidRun(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    var input = port(h, controller, "input");
    var output = port(h, controller, "output");
    start(h, controller, Items.DIRT);
    after(h, 3, () -> {
      var before = controller.runningRecipe().orElseThrow();
      int progress = before.progress();
      h.assertTrue(
          StructureService
              .reorient(controller, MachineOrientation.of(Direction.NORTH, 1)) == StructureService.TurnResult.CHANGED,
          "Paid-run rotation was not applied");
      h.assertTrue(!controller.canCommitProduction(), "Asymmetric structure remained valid after rotation");
      after(h, 4, () -> {
        var paused = controller.runningRecipe().orElseThrow();
        h.assertTrue(
            paused.runId().equals(before.runId()) && paused.progress() == progress && !controller.canCommitProduction(),
            "Invalid structure advanced or replaced its paid run");
        h.assertTrue(
            StructureService.reorient(controller, MachineOrientation.IDENTITY) == StructureService.TurnResult.CHANGED,
            "Paid-run pose could not be restored");
        confirm(h, controller);
        after(h, 14, () -> {
          h.assertTrue(
              controller.runningRecipe().isEmpty()
                  && count(output, Items.IRON_INGOT) == 1
                  && input.inventory().getStackInSlot(0).isEmpty(),
              "Restored paid run did not resume and finish exactly once: " + controller.recipeRunStatus());
          next.run();
        });
      });
    });
  }

  private static void pendingBindingChange(GameTestHelper h, Runnable next) {
    var controller = build(h, MachineOrientation.IDENTITY);
    start(h, controller, Items.SAND);
    var output = port(h, controller, "output");
    fill(output, new ItemStack(Items.STONE, 64));
    output.inventory().setStackInSlot(0, ItemStack.EMPTY);
    after(h, 14, () -> {
      var pending = controller.runningRecipe().orElseThrow();
      h.assertTrue(pending.pendingOutput(), "Binding fixture did not reach OUTPUT_PENDING");
      savedDefinitions = StructureService.definitionsSnapshot();
      var changed = new HashMap<>(savedDefinitions);
      changed.put(
          MACHINE,
          MachineDefinition.parse(
              MACHINE,
              JsonParser.parseString(
                  "{\"orientation\":{\"policy\":\"all_24\"},\"parts\":[{\"part_id\":\"input\",\"offset\":[-1,0,1],\"port\":\"item_input\"},{\"part_id\":\"replacement_output\",\"offset\":[1,0,1],\"port\":\"item_output\"}]}")
                  .getAsJsonObject()));
      StructureService.replaceDefinitions(changed);
      confirm(h, controller);
      after(h, 3, () -> {
        h.assertTrue(
            controller.runningRecipe().orElseThrow().runId().equals(pending.runId())
                && controller.runningRecipe().orElseThrow().pendingOutput()
                && count(output, Items.DIAMOND) == 0
                && count(output, Items.EMERALD) == 0
                && controller.recipeRunStatus() == ItemRunEngine.Result.BINDING_CHANGED,
            "Pending products were committed through a changed logical output role");
        var restore = savedDefinitions;
        savedDefinitions = null;
        StructureService.replaceDefinitions(restore);
        confirm(h, controller);
        reload(h, () -> {
          confirm(h, controller);
          output.inventory().setStackInSlot(1, ItemStack.EMPTY);
          after(h, 14, () -> {
            h.assertTrue(
                controller.runningRecipe().isEmpty()
                    && count(output, Items.DIAMOND) == 1
                    && count(output, Items.EMERALD) == 1,
                "Frozen pending products did not settle after the original role returned");
            next.run();
          });
        });
      });
    });
  }

  private static void sharedOutput(GameTestHelper h, Runnable next) {
    savedBudget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    MachineConfig.RECIPE_OUTPUT_WORK.set(1);
    var p = origin(h);
    clear(h, p);
    var a = buildAt(h, p.offset(-1, 0, 0), MachineOrientation.IDENTITY);
    var b = buildAt(h, p.offset(1, 0, 2), MachineOrientation.of(Direction.SOUTH, 0));
    confirm(h, a);
    confirm(h, b);
    var output = port(h, a, "output");
    var aInput = port(h, a, "input");
    var bInput = port(h, b, "input");
    h.assertTrue(output == port(h, b, "output"), "Finish fixture does not share a physical output");
    fill(output, new ItemStack(Items.STONE, 64));
    output.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 63));
    start(h, a, Items.DIRT);
    start(h, b, Items.DIRT);
    after(h, 50, () -> {
      int active = (a.runningRecipe().isPresent() ? 1 : 0) + (b.runningRecipe().isPresent() ? 1 : 0);
      h.assertTrue(
          active == 1 && count(output, Items.IRON_INGOT) == 64,
          "Shared output duplicated capacity, lost a buffer, or restarted output planning on input changes");
      aInput.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
      bInput.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
      output.inventory().extractItem(0, 1, false);
      after(h, 40, () -> {
        h.assertTrue(
            a.runningRecipe().isEmpty() && b.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 64,
            "Shared pending output did not settle once capacity returned");
        MachineConfig.RECIPE_OUTPUT_WORK.set(savedBudget);
        savedBudget = -1;
        next.run();
      });
    });
  }

  private static void changedBindingsAndRecipe(GameTestHelper h) {
    var c = build(h, MachineOrientation.IDENTITY);
    var output = port(h, c, "output");
    start(h, c, Items.DIRT);
    int progress = c.runningRecipe().orElseThrow().progress();
    var paidId = c.runningRecipe().orElseThrow().runId();
    savedDefinitions = StructureService.definitionsSnapshot();
    var changed = new HashMap<>(savedDefinitions);
    changed.put(
        MACHINE,
        MachineDefinition.parse(
            MACHINE,
            JsonParser.parseString(
                "{\"orientation\":{\"policy\":\"all_24\"},\"parts\":[{\"part_id\":\"input\",\"offset\":[-1,0,1],\"port\":\"item_input\"},{\"part_id\":\"different_output\",\"offset\":[1,0,1],\"port\":\"item_output\"}]}")
                .getAsJsonObject()));
    StructureService.replaceDefinitions(changed);
    confirm(h, c);
    after(h, 5, () -> awaitLogicalBindingChange(h, c, output, paidId, progress, 80, () -> {
      var restore = savedDefinitions;
      savedDefinitions = null;
      StructureService.replaceDefinitions(restore);
      confirm(h, c);
      var manager = h.getLevel().getServer().getRecipeManager();
      manager.replaceRecipes(
          manager.getRecipes().stream().filter(holder -> !holder.id().equals(id("mmcece/test_lifecycle_simple")))
              .toList());
      after(h, 14, () -> {
        h.assertTrue(
            c.runningRecipe().isEmpty() && count(output, Items.IRON_INGOT) == 1,
            "Removed live recipe replaced or discarded frozen paid results");
        reload(h, () -> {
          confirm(h, c);
          h.assertTrue(
              MachineRecipeService.catalogFor(c).isPresent(),
              "Lifecycle cleanup did not restore coordinated catalogs");
          h.succeed();
        });
      });
    }));
  }

  private static void awaitLogicalBindingChange(GameTestHelper h, MachineControllerBlockEntity controller, MachinePortBlockEntity output, UUID paidId, int progress, int remaining, Runnable next) {
    after(h, 1, () -> {
      var run = controller.runningRecipe().orElse(null);
      h.assertTrue(
          run != null
              && run.runId().equals(paidId)
              && run.progress() == progress
              && count(output, Items.IRON_INGOT) == 0,
          "Changed logical ports advanced, replaced, or output the frozen paid run");
      if (controller.canCommitProduction() && controller.recipeRunStatus() == ItemRunEngine.Result.BINDING_CHANGED) {
        next.run();
      } else if (remaining == 0) {
        h.fail(
            "Logical binding gate did not settle: " + controller.statusMessage()
                .getString() + "; run status=" + controller.recipeRunStatus());
      } else {
        awaitLogicalBindingChange(h, controller, output, paidId, progress, remaining - 1, next);
      }
    });
  }

  private static MachineControllerBlockEntity build(GameTestHelper h, MachineOrientation pose) {
    var p = origin(h);
    clear(h, p);
    return buildAt(h, p, pose);
  }

  private static MachineControllerBlockEntity buildAt(GameTestHelper h, BlockPos p, MachineOrientation pose) {
    h.getLevel().setBlockAndUpdate(
        p,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    h.getLevel().setBlockAndUpdate(
        p.offset(pose.toWorldOffset(new BlockPos(-1, 0, 1))),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    h.getLevel().setBlockAndUpdate(
        p.offset(pose.toWorldOffset(new BlockPos(1, 0, 1))),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var c = (MachineControllerBlockEntity) h.getLevel().getBlockEntity(p);
    c.bindMachine(MACHINE);
    confirm(h, c);
    return c;
  }

  private static void start(GameTestHelper h, MachineControllerBlockEntity c, Item input) {
    start(h, c, input, 1);
  }

  private static void start(GameTestHelper h, MachineControllerBlockEntity c, Item input, int count) {
    port(h, c, "input").inventory().setStackInSlot(0, new ItemStack(input, count));
    var search = ItemRecipeSearch.begin(c);
    h.assertTrue(search.isPresent(), "No coordinated lifecycle catalog: " + c.statusMessage().getString());
    var plan = search.orElseThrow().advance(10_000).plan();
    h.assertTrue(plan.isPresent(), "Lifecycle recipe not admitted");
    h.assertTrue(plan.orElseThrow().start() == AtomicRecipeStarter.Result.STARTED, "Lifecycle start did not commit");
  }

  private static MachinePortBlockEntity port(GameTestHelper h, MachineControllerBlockEntity c, String id) {
    return RecipeResourceTicketGameTests.port(h, c, id);
  }

  private static void fill(MachinePortBlockEntity p, ItemStack value) {
    for (int i = 0; i < p.inventory().getSlots(); i++) {
      p.inventory().setStackInSlot(i, value.copy());
    }
  }

  private static int count(MachinePortBlockEntity p, Item item) {
    int count = 0;
    for (int i = 0; i < p.inventory().getSlots(); i++) {
      var stack = p.inventory().getStackInSlot(i);
      if (stack.is(item)) {
        count += stack.getCount();
      }
    }
    return count;
  }

  private static void confirm(GameTestHelper h, MachineControllerBlockEntity c) {
    for (int i = 0; i < 200 && !c.canCommitProduction(); i++) {
      StructureService.advance(h.getLevel(), 512, 64, 50_000_000L);
    }
    h.assertTrue(c.canCommitProduction(), "Lifecycle structure failed: " + c.statusMessage().getString());
  }

  private static void clear(GameTestHelper h, BlockPos p) {
    for (int x = -2; x <= 2; x++) {
      for (int y = -1; y <= 2; y++) {
        for (int z = -2; z <= 3; z++) {
          h.getLevel().setBlockAndUpdate(p.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
      }
    }
  }

  private static BlockPos origin(GameTestHelper h) {
    return h.absolutePos(new BlockPos(7, 5, 7));
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
