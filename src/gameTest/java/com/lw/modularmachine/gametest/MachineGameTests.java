package com.lw.modularmachine.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.api.MachineControllerApi;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class MachineGameTests {
  private static final ResourceLocation TRANSFORMER = ResourceLocation.fromNamespaceAndPath("mmcece", "transformer");

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void saturated_saved_transfer_count_does_not_throw_after_committing_energy(GameTestHelper helper) {
    var controller = build(helper, new BlockPos(3, 2, 3), Direction.NORTH);
    helper.runAfterDelay(8, () -> {
      for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
        StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
      }
      helper.assertTrue(controller.canCommitProduction(), "Counter saturation fixture did not form");
      var saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
      saved.getCompound("mmcece").putLong("successful_transfers", Long.MAX_VALUE);
      controller.loadWithComponents(saved, helper.getLevel().registryAccess());
      var source = input(helper, controller);
      var target = output(helper, controller);
      source.energy().receiveEnergy(128, false);
      controller.serverTick();
      controller.serverTick();
      helper.assertTrue(
          controller.successfulTransfers() == Long.MAX_VALUE
              && source.energy().stored() == 0
              && target.energy().stored() == 128,
          "Telemetry overflow broke or duplicated a successful transfer");
      helper.succeed();
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void strong_redstone_pauses_without_discarding_structure(GameTestHelper helper) {
    var controller = build(helper, new BlockPos(3, 2, 3), Direction.NORTH);
    helper.runAfterDelay(8, () -> {
      var source = input(helper, controller);
      var target = output(helper, controller);
      BlockPos lever = controller.getBlockPos().north();
      helper.getLevel().setBlockAndUpdate(
          lever,
          Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
              .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
              .setValue(BlockStateProperties.POWERED, true));
      helper.assertTrue(
          helper.getLevel().getDirectSignalTo(controller.getBlockPos()) > 0,
          "Test lever did not supply strong power");
      source.energy().receiveEnergy(128, false);
      controller.serverTick();
      helper.assertTrue(
          !controller.canCommitProduction() && source.energy().stored() == 128 && target.energy().stored() == 0,
          "Redstone pause consumed/produced");
      helper.getLevel().setBlockAndUpdate(lever, Blocks.AIR.defaultBlockState());
      helper.runAfterDelay(6, () -> {
        helper.assertTrue(
            controller.canCommitProduction() && target.energy().stored() == 128,
            "Removing strong power did not resume");
        helper.succeed();
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void every_core_port_tier_exposes_the_correct_capability(GameTestHelper helper) {
    helper.assertTrue(MachineRegistries.PORTS.size() == 46, "Tiered port catalog incomplete");
    BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
    for (var block : MachineRegistries.PORTS.values()) {
      helper.getLevel().setBlockAndUpdate(pos, block.get().defaultBlockState());
      var port = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(pos);
      switch (port.kind().resource()) {
        case ITEM -> {
          var cap = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
          helper.assertTrue(cap != null && cap.getSlots() == port.tier().capacity(), "Wrong item tier capability");
          helper.assertTrue(port.inventory().getSlots() == port.tier().capacity(), "Wrong tier slot count");
          ItemStack stack = new ItemStack(Items.DIAMOND, 2);
          if (port.kind().isInput()) {
            helper.assertTrue(
                cap.insertItem(0, stack, true).isEmpty() && port.inventory().getStackInSlot(0).isEmpty(),
                "Input simulation mutated items");
          } else {
            helper.assertTrue(cap.insertItem(0, stack, false).getCount() == 2, "Output accepted external insertion");
          }
        }
        case FLUID -> {
          var cap = helper.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.UP);
          helper.assertTrue(
              cap != null && cap.getTankCapacity(0) == port.tier().capacity(),
              "Wrong fluid tier capability");
          int expected = port.kind().isInput() ? 20 : 0;
          helper.assertTrue(
              cap.fill(new FluidStack(Fluids.WATER, 20), IFluidHandler.FluidAction.SIMULATE) == expected
                  && port.fluidTank().getFluidAmount() == 0,
              "Fluid direction/simulation failed");
        }
        case ENERGY -> {
          var cap = helper.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.UP);
          helper.assertTrue(
              cap != null && port.energy().capacity() == port.tier().capacity(),
              "Wrong energy tier capability");
          helper.assertTrue(
              cap.canReceive() == port.kind().isInput() && cap.canExtract() != port.kind().isInput(),
              "Wrong energy direction");
          helper.assertTrue(
              cap.receiveEnergy(128, true) == (port.kind().isInput() ? 128 : 0) && port.energy().stored() == 0,
              "Energy simulation mutated storage");
        }
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void directional_properties_rotate_with_the_controller(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(3, 2, 3));
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineRegistries.CONTROLLER.get().defaultBlockState().setValue(MachineControllerBlock.FACING, Direction.EAST));
    BlockPos part = origin.south();
    helper.getLevel().setBlockAndUpdate(
        part,
        Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_axis"));
    helper.runAfterDelay(7, () -> {
      helper.assertTrue(controller.canCommitProduction(), "Expected local X to rotate into world Z");
      helper.getLevel().setBlockAndUpdate(
          part,
          Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
      helper.runAfterDelay(5, () -> {
        helper.assertTrue(
            controller.verificationFailure() == VerificationFailure.MISMATCH,
            "Wrong rotated property was accepted");
        helper.succeed();
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 100, batch = "reload")
  public static void definition_reload_restores_previously_unknown_bindings(GameTestHelper helper) {
    var controller = build(helper, new BlockPos(3, 2, 3), Direction.NORTH);
    helper.runAfterDelay(8, () -> {
      helper.assertTrue(controller.canCommitProduction(), "Initial pattern did not form");
      var source = input(helper, controller);
      var target = output(helper, controller);
      source.energy().receiveEnergy(128, false);
      var snapshot = StructureService.definitionsSnapshot();
      var old = controller.registration();
      try {
        StructureService.replaceDefinitions(Map.of());
        helper.assertTrue(
            !controller.canCommitProduction()
                && controller.verificationFailure() == VerificationFailure.UNKNOWN_DEFINITION,
            "Removed definition retained permission");
        helper.assertTrue(!old.canCommit(helper.getLevel()), "Old generation remained authoritative");
        controller.serverTick();
        helper.assertTrue(
            source.energy().stored() == 128 && target.energy().stored() == 0,
            "Missing definition produced");
      } finally {
        StructureService.replaceDefinitions(snapshot);
      }
      helper.assertTrue(
          controller.verificationState() == VerificationState.PENDING
              && StructureService.pendingWorkCount(controller) > 0,
          "Restored definition was not queued for budgeted preparation");
      helper.runAfterDelay(8, () -> {
        helper.assertTrue(controller.canCommitProduction(), "Reload did not reconfirm");
        helper.assertTrue(
            source.energy().stored() == 0 && target.energy().stored() == 128,
            "Reload did not resume exactly once");
        helper.succeed();
      });
    });
  }

  private static MachineControllerBlockEntity build(GameTestHelper helper, BlockPos relativeOrigin, Direction facing) {
    var level = helper.getLevel();
    BlockPos origin = helper.absolutePos(relativeOrigin);
    level.setBlockAndUpdate(
        origin,
        MachineRegistries.CONTROLLER.get().defaultBlockState().setValue(MachineControllerBlock.FACING, facing));
    MachineDefinition definition = StructureService.definition(TRANSFORMER);
    helper.assertTrue(definition != null, "Transformer definition was not loaded");
    CompiledStructure pattern = new CompiledStructure(definition, origin, facing);
    for (int i = 0; i < definition.parts().size(); i++) {
      var rule = definition.parts().get(i).rule();
      BlockState state = rule.port() == null ? rule.alternatives().getFirst().block()
          .defaultBlockState() : MachineRegistries.PORTS.get(rule.port().path() + "_normal").get().defaultBlockState();
      level.setBlockAndUpdate(pattern.position(i), state);
    }
    var controller = (MachineControllerBlockEntity) level.getBlockEntity(origin);
    controller.bindMachine(TRANSFORMER);
    StructureService.advance(level, 512, 64, 50_000_000L);
    return controller;
  }

  private static MachinePortBlockEntity input(GameTestHelper helper, MachineControllerBlockEntity controller) {
    return (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(controller.registration().pattern.energyInputPosition());
  }

  private static MachinePortBlockEntity output(GameTestHelper helper, MachineControllerBlockEntity controller) {
    return (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(controller.registration().pattern.energyOutputPosition());
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void four_rotations_match_reference_and_transfer(GameTestHelper helper) {
    BlockPos[] origins = {new BlockPos(3, 2, 3), new BlockPos(10, 2, 3), new BlockPos(3, 2, 10),
        new BlockPos(10, 2, 10)};
    Direction[] directions = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    MachineControllerBlockEntity[] controllers = new MachineControllerBlockEntity[4];
    for (int i = 0; i < controllers.length; i++) {
      controllers[i] = build(helper, origins[i], directions[i]);
    }
    helper.runAfterDelay(6, () -> {
      // Literal expected legacy positions: independent from
      // CompiledStructure.rotate().
      BlockPos[] expectedInputs = {new BlockPos(1, 0, 1), new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1),
          new BlockPos(1, 0, -1)};
      BlockPos[] expectedOutputs = {new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1), new BlockPos(1, 0, -1),
          new BlockPos(1, 0, 1)};
      for (int i = 0; i < controllers.length; i++) {
        var controller = controllers[i];
        helper.assertTrue(
            controller.registration().pattern.energyInputPosition()
                .equals(controller.getBlockPos().offset(expectedInputs[i])),
            "Input rotation differs from legacy geometry");
        helper.assertTrue(
            controller.registration().pattern.energyOutputPosition()
                .equals(controller.getBlockPos().offset(expectedOutputs[i])),
            "Output rotation differs from legacy geometry");
        helper
            .assertTrue(input(helper, controller).energy().receiveEnergy(128, false) == 128, "Could not supply input");
      }
    });
    helper.runAfterDelay(20, () -> {
      for (var controller : controllers) {
        helper.assertTrue(
            controller.verificationState() == VerificationState.CONFIRMED,
            "Rotation did not form: " + controller.statusMessage().getString());
        helper.assertTrue(controller.fullScan().state() == VerificationState.CONFIRMED, "Incremental/full mismatch");
        helper.assertTrue(
            input(helper, controller).energy().stored() == 0 && output(helper, controller).energy().stored() == 128,
            "Energy not conserved");
        helper.assertTrue(controller.successfulTransfers() == 1, "Unexpected duplicate production");
      }
      helper.succeed();
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void pending_and_mismatch_block_production_until_repaired(GameTestHelper helper) {
    var controller = build(helper, new BlockPos(3, 2, 3), Direction.NORTH);
    helper.runAfterDelay(10, () -> {
      helper.assertTrue(controller.canCommitProduction(), "Structure not confirmed");
      var source = input(helper, controller);
      var target = output(helper, controller);
      long bindings = controller.bindingRebuilds();
      source.energy().receiveEnergy(128, false);
      helper.assertTrue(controller.canCommitProduction(), "Plain resource change incorrectly dirtied structure");
      BlockPos bad = controller.registration().pattern.position(0);
      BlockState expected = helper.getLevel().getBlockState(bad);
      helper.getLevel().setBlockAndUpdate(bad, Blocks.AIR.defaultBlockState());
      helper.assertTrue(
          controller.verificationState() == VerificationState.PENDING,
          "Block write did not immediately revoke proof");
      controller.serverTick();
      helper.assertTrue(source.energy().stored() == 128 && target.energy().stored() == 0, "Pending produced");
      helper.runAfterDelay(5, () -> {
        helper.assertTrue(
            controller.verificationFailure() == VerificationFailure.MISMATCH,
            "Missing block not diagnosed");
        var diagnostic = MachineControllerApi.inspect(controller).orElseThrow().structureDiagnostic().orElseThrow();
        helper.assertTrue(
            diagnostic.position().equals(bad)
                && !diagnostic.expected().isBlank()
                && diagnostic.actual().contains("minecraft:air"),
            "Read-only structure diagnostic omitted location, expectation, or actual state");
        helper.getLevel().setBlockAndUpdate(bad, expected);
        helper.runAfterDelay(8, () -> {
          helper.assertTrue(controller.canCommitProduction(), "Repair failed");
          helper.assertTrue(
              source.energy().stored() == 0 && target.energy().stored() == 128,
              "Repair did not resume exactly once");
          helper.assertTrue(controller.bindingRebuilds() == bindings, "Unchanged ports were unnecessarily rebound");
          helper.succeed();
        });
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void resource_dependent_field_invalidates_its_group(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(3, 2, 3));
    BlockPos portPos = origin.east();
    BlockPos targetPos = origin.west(2);
    helper.getLevel().setBlockAndUpdate(origin, MachineRegistries.CONTROLLER.get().defaultBlockState());
    helper.getLevel()
        .setBlockAndUpdate(portPos, MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        targetPos,
        MachineRegistries.PORTS.get("energy_output_hatch_normal").get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    var source = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(portPos);
    var target = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(targetPos);
    controller.bindMachine(ResourceLocation.fromNamespaceAndPath("mmcece", "test_energy_field"));
    helper.runAfterDelay(7, () -> {
      helper.assertTrue(controller.verificationState() == VerificationState.INVALID, "Empty field should fail");
      source.energy().receiveEnergy(1, false);
      helper.assertTrue(
          controller.verificationState() == VerificationState.PENDING,
          "Declared resource dependency did not invalidate");
      helper.runAfterDelay(6, () -> {
        helper.assertTrue(controller.canCommitProduction(), "Field did not confirm");
        helper.assertTrue(source.energy().transferExactTo(target.energy(), 1), "Could not drain test field");
        helper.assertTrue(!controller.canCommitProduction(), "Field change retained production permission");
        helper.runAfterDelay(6, () -> {
          helper.assertTrue(controller.verificationFailure() == VerificationFailure.MISMATCH, "Field mismatch missing");
          helper.succeed();
        });
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void untracked_section_write_is_caught_before_commit(GameTestHelper helper) {
    var controller = build(helper, new BlockPos(3, 2, 3), Direction.NORTH);
    helper.runAfterDelay(10, () -> {
      var source = input(helper, controller);
      var target = output(helper, controller);
      source.energy().receiveEnergy(128, false);
      BlockPos bad = controller.registration().pattern.position(0);
      var chunk = helper.getLevel().getChunkAt(bad);
      chunk.getSection(chunk.getSectionIndex(bad.getY()))
          .setBlockState(bad.getX() & 15, bad.getY() & 15, bad.getZ() & 15, Blocks.AIR.defaultBlockState());
      controller.serverTick();
      helper.assertTrue(
          controller.verificationState() == VerificationState.INVALID,
          "Conservative production guard missed untracked write");
      helper.assertTrue(
          source.energy().stored() == 128 && target.energy().stored() == 0,
          "Untracked broken structure produced");
      helper.succeed();
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void port_capabilities_persistence_and_carried_drop(GameTestHelper helper) {
    BlockPos relative = new BlockPos(3, 2, 3);
    helper.setBlock(relative, MachineRegistries.PORTS.get("item_output_bus_tiny").get());
    BlockPos pos = helper.absolutePos(relative);
    var port = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(pos);
    ItemStack diamonds = new ItemStack(Items.DIAMOND, 5);
    diamonds.set(DataComponents.CUSTOM_NAME, Component.literal("preserved"));
    port.inventory().setStackInSlot(0, diamonds);
    IItemHandler capability = helper.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.UP);
    helper.assertTrue(capability != null, "Item capability not registered");
    helper.assertTrue(
        !capability.insertItem(0, new ItemStack(Items.DIAMOND), true).isEmpty(),
        "Output allowed external insert");
    helper.assertTrue(
        capability.extractItem(0, 1, true).getCount() == 1 && port.inventory().getStackInSlot(0).getCount() == 5,
        "Simulation mutated output");
    var drops = Block.getDrops(port.getBlockState(), helper.getLevel(), pos, port);
    helper.assertTrue(
        drops.size() == 1 && drops.getFirst().has(DataComponents.BLOCK_ENTITY_DATA),
        "Mining did not carry port state");
    var restored = new MachinePortBlockEntity(pos, port.getBlockState());
    restored.loadWithComponents(
        drops.getFirst().get(DataComponents.BLOCK_ENTITY_DATA).copyTag(),
        helper.getLevel().registryAccess());
    helper.assertTrue(restored.inventory().getStackInSlot(0).getCount() == 5, "Inventory lost on carry");
    helper.assertTrue(
        restored.inventory().getStackInSlot(0).getHoverName().getString().equals("preserved"),
        "Item components lost");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void unsupported_definition_is_rejected(GameTestHelper helper) {
    JsonObject root = new JsonObject();
    root.addProperty("schema_version", 1);
    root.add("dynamic-patterns", new JsonObject());
    try {
      MachineDefinition.parse(ResourceLocation.fromNamespaceAndPath("mmcece", "invalid"), root);
      helper.fail("Unsupported feature was silently accepted");
    } catch (JsonParseException expected) {
      helper.succeed();
    }
  }
}
