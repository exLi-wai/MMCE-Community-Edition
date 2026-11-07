package com.lw.modularmachine.gametest;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.RecipeCommitGuard;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class RecipeResourceTicketGameTests {
  private static final int[][] OFFSETS = {{-1, 0, 1}, {1, 0, 1}, {0, 1, 1}, {0, -1, 1}, {-1, 1, 1}, {1, -1, 1}};
  private static final String[] PORTS = {"item_input_bus_normal", "item_output_bus_normal", "fluid_input_hatch_normal",
      "fluid_output_hatch_normal", "energy_input_hatch_normal", "energy_output_hatch_normal"};

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void all_24_poses_keep_stable_part_ids_and_defensive_component_snapshots(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    for (var orientation : MachineOrientation.all()) {
      var controller = build(helper, origin, orientation);
      var input = port(helper, controller, "item_in");
      var stack = new ItemStack(Items.DIRT, 3);
      stack.set(DataComponents.CUSTOM_NAME, Component.literal("keep components"));
      input.inventory().setStackInSlot(0, stack);
      var ticket = capture(helper, controller);
      helper.assertTrue(
          ticket.ports().stream().map(RecipeExecutionTicket.PortSnapshot::partId).toList()
              .equals(List.of("energy_in", "energy_out", "fluid_in", "fluid_out", "item_in", "item_out")),
          "Part order depends on world axes");
      var view = view(ticket, "item_in");
      helper.assertTrue(ItemStack.matches(stack, view.items().getFirst()), "Snapshot lost item components");
      view.items().getFirst().setCount(1);
      ((MutableComponent) view.items().getFirst().get(DataComponents.CUSTOM_NAME)).append("mutated exported view");
      helper.assertTrue(
          view.items().getFirst().getCount() == 3 && input.inventory().getStackInSlot(0).getCount() == 3,
          "Snapshot exposes mutable storage");
      helper.assertTrue(
          input.inventory().getStackInSlot(0).get(DataComponents.CUSTOM_NAME).getString().equals("keep components"),
          "Snapshot shares a nested component with real storage");
      helper.assertTrue(RecipeCommitGuard.validate(ticket), "Unchanged snapshot rejected");
      clear(helper, origin, orientation);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void input_output_fluid_and_energy_changes_expire_only_the_resource_ticket(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    long structural = controller.registration().verifier.revision();
    for (String id : List.of("item_in", "item_out", "fluid_in", "fluid_out", "energy_in")) {
      var ticket = capture(helper, controller);
      var port = port(helper, controller, id);
      if (id.startsWith("item")) {
        port.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
      } else {
        if (id.startsWith("fluid")) {
          port.fluidTank().setFluid(new FluidStack(Fluids.WATER, 100));
        } else {
          port.energy().receiveEnergy(128, false);
        }
      }
      helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Changed resource retained ticket: " + id);
      helper.assertTrue(
          controller.canCommitProduction() && controller.registration().verifier.revision() == structural,
          "Ordinary resource change dirtied a structure that does not read contents");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void unnotified_stack_count_components_and_fluid_mutation_cannot_reuse_a_ticket(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    var input = port(helper, controller, "item_in");
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var ticket = capture(helper, controller);
    input.inventory().getStackInSlot(0).grow(1);
    helper.assertTrue(
        input.resourceRevision() == view(ticket, "item_in").revision() && !RecipeCommitGuard.validate(ticket),
        "Direct item count mutation escaped snapshot equality");
    ticket = capture(helper, controller);
    input.inventory().getStackInSlot(0).set(DataComponents.CUSTOM_NAME, Component.literal("changed without callback"));
    helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Direct component mutation retained ticket");
    var fluid = port(helper, controller, "fluid_in");
    fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 100));
    ticket = capture(helper, controller);
    fluid.fluidTank().getFluid().setAmount(101);
    helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Direct fluid mutation retained ticket");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void rotation_and_reconfirmation_never_reauthorize_the_old_resource_ticket(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    var ticket = capture(helper, controller);
    StructureService.reorient(controller, MachineOrientation.of(Direction.NORTH, 1));
    helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Pending rotation retained ticket");
    StructureService.reorient(controller, MachineOrientation.IDENTITY);
    confirm(helper, controller);
    helper.assertTrue(
        !RecipeCommitGuard.validate(ticket) && RecipeCommitGuard.validate(capture(helper, controller)),
        "Reconfirmed generation reused old resources");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void unnotified_tank_capacity_change_invalidates_the_resource_snapshot(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    var port = port(helper, controller, "fluid_out");
    var ticket = capture(helper, controller);
    int before = port.fluidTank().getCapacity();
    port.fluidTank().setCapacity(before - 1);
    helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Unnotified output capacity change retained ticket");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void same_position_port_or_controller_replacement_cannot_reuse_old_identity(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    var ticket = capture(helper, controller);
    var old = port(helper, controller, "item_out");
    var pos = old.getBlockPos();
    helper.getLevel().removeBlockEntity(pos);
    var replacement = new MachinePortBlockEntity(pos, old.getBlockState());
    helper.getLevel().setBlockEntity(replacement);
    confirm(helper, controller);
    helper.assertTrue(!RecipeCommitGuard.validate(ticket), "Same revision/position but new port identity accepted");
    var fresh = capture(helper, controller);
    helper.getLevel().removeBlockEntity(origin);
    var newController = new MachineControllerBlockEntity(origin, controller.getBlockState());
    helper.getLevel().setBlockEntity(newController);
    newController.onLoad();
    newController.bindMachine(id("test_recipe_resources"));
    confirm(helper, newController);
    helper.assertTrue(!RecipeCommitGuard.validate(fresh), "New controller instance inherited old resource ticket");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void redstone_pending_and_invalid_structure_deny_capture_and_validation(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    var ticket = capture(helper, controller);
    helper.getLevel().setBlockAndUpdate(
        origin.north(),
        Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.POWERED, true));
    helper.assertTrue(
        RecipeExecutionTicket.capture(controller).isEmpty() && !RecipeCommitGuard.validate(ticket),
        "Redstone pause bypassed");
    helper.getLevel().setBlockAndUpdate(origin.north(), Blocks.AIR.defaultBlockState());
    confirm(helper, controller);
    ticket = capture(helper, controller);
    helper.getLevel()
        .setBlockAndUpdate(port(helper, controller, "item_out").getBlockPos(), Blocks.AIR.defaultBlockState());
    helper.assertTrue(
        RecipeExecutionTicket.capture(controller).isEmpty() && !RecipeCommitGuard.validate(ticket),
        "Pending structure authorized resources");
    StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    helper.assertTrue(
        controller.verificationState() == VerificationState.INVALID
            && RecipeExecutionTicket.capture(controller).isEmpty(),
        "Invalid structure captured resources");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void conservative_preflight_catches_untracked_world_damage(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    var ticket = capture(helper, controller);
    var pos = port(helper, controller, "item_out").getBlockPos();
    var chunk = helper.getLevel().getChunkAt(pos);
    chunk.getSection(chunk.getSectionIndex(pos.getY()))
        .setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, Blocks.AIR.defaultBlockState());
    helper.assertTrue(
        !RecipeCommitGuard.validate(ticket) && !controller.canCommitProduction(),
        "Conservative recipe guard missed untracked block change");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void capture_and_validation_reject_workers_and_expire_across_ticks(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    var ticket = capture(helper, controller);
    boolean rejected = CompletableFuture.supplyAsync(() -> {
      try {
        RecipeExecutionTicket.capture(controller);
        return false;
      } catch (IllegalStateException expected) {
        try {
          RecipeCommitGuard.validate(ticket);
          return false;
        } catch (IllegalStateException alsoExpected) {
          return true;
        }
      }
    }).join();
    helper.assertTrue(rejected, "Worker reached live resources");
    helper.runAfterDelay(1, () -> {
      helper
          .assertTrue(!RecipeCommitGuard.validate(ticket), "Same-tick resource ticket survived FE tick-limit rollover");
      helper.succeed();
    });
  }

  @GameTest(template = "empty16", batch = "recipe_ticket_reentrant", timeoutTicks = 80)
  public static void full_scan_callback_resource_mutation_is_rechecked_before_authorizing(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var pose = MachineOrientation.of(Direction.EAST, 0);
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    var inputPos = origin.offset(pose.toWorldOffset(new BlockPos(-1, 0, 1)));
    helper.getLevel().setBlockAndUpdate(
        inputPos,
        MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        origin.offset(pose.toWorldOffset(new BlockPos(1, 0, 1))),
        MachineRegistries.PORTS.get("energy_output_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        origin.offset(pose.toWorldOffset(new BlockPos(0, 1, 1))),
        TestPredicateBlock.INSTANCE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_reentrant_machine"));
    confirm(helper, controller);
    var ticket = capture(helper, controller);
    var source = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(inputPos);
    var called = new AtomicBoolean();
    TestPredicateBlock.CALLBACK.set(() -> {
      called.set(true);
      source.energy().receiveEnergy(1, false);
    });
    try {
      helper.assertTrue(
          !RecipeCommitGuard.validate(ticket) && called.get() && source.energy().stored() == 1,
          "Post-callback resource fence failed or preflight changed resources itself");
      helper.succeed();
    } finally {
      TestPredicateBlock.CALLBACK.set(null);
    }
  }

  static MachineControllerBlockEntity build(GameTestHelper helper, BlockPos origin, MachineOrientation orientation) {
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), orientation));
    for (int i = 0; i < OFFSETS.length; i++) {
      helper.getLevel().setBlockAndUpdate(
          origin.offset(orientation.toWorldOffset(new BlockPos(OFFSETS[i][0], OFFSETS[i][1], OFFSETS[i][2]))),
          MachineRegistries.PORTS.get(PORTS[i]).get().defaultBlockState());
    }
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_recipe_resources"));
    confirm(helper, controller);
    return controller;
  }

  private static void clear(GameTestHelper helper, BlockPos origin, MachineOrientation orientation) {
    for (var p : OFFSETS) {
      helper.getLevel().setBlockAndUpdate(
          origin.offset(orientation.toWorldOffset(new BlockPos(p[0], p[1], p[2]))),
          Blocks.AIR.defaultBlockState());
    }
    helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void invalid_machine_binding_and_worker_status_queries_leave_the_live_controller_unchanged(GameTestHelper helper) {
    var controller = build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
    var machine = controller.machineId();
    var registration = controller.registration();
    try {
      controller.bindMachine(null);
      helper.fail("Null machine binding was accepted");
    } catch (NullPointerException expected) {
      helper.assertTrue(
          controller.machineId().equals(machine) && controller.registration() == registration,
          "Rejected binding changed machine/proof");
    }
    boolean rejected = CompletableFuture.supplyAsync(() -> {
      try {
        controller.verificationFailure();
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        rejected && controller.canCommitProduction(),
        "Worker status query bypassed guard or invalidated the controller");
    helper.succeed();
  }

  private static RecipeExecutionTicket capture(GameTestHelper helper, MachineControllerBlockEntity controller) {
    var ticket = RecipeExecutionTicket.capture(controller);
    helper.assertTrue(
        ticket.isPresent(),
        "Cannot capture confirmed resources: " + controller.statusMessage().getString());
    return ticket.orElseThrow();
  }

  private static RecipeExecutionTicket.PortSnapshot view(RecipeExecutionTicket ticket, String part) {
    return ticket.ports().stream().filter(p -> p.partId().equals(part)).findFirst().orElseThrow();
  }

  static MachinePortBlockEntity port(GameTestHelper helper, MachineControllerBlockEntity controller, String part) {
    var pattern = controller.registration().pattern;
    for (int i = 0; i < pattern.definition.parts().size(); i++) {
      if (pattern.definition.parts().get(i).id().equals(part)) {
        return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(pattern.position(i));
      }
    }
    throw new IllegalArgumentException(part);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void resource_status_payload_tracks_fluid_and_energy_changes(GameTestHelper helper) {
    BlockPos fluidPos = helper.absolutePos(new BlockPos(4, 3, 4));
    BlockPos energyPos = helper.absolutePos(new BlockPos(6, 3, 4));
    helper.getLevel()
        .setBlockAndUpdate(fluidPos, MachineRegistries.PORTS.get("fluid_input_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        energyPos,
        MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
    var fluidPort = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(fluidPos);
    var energyPort = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(energyPos);
    fluidPort.fluidTank().fill(new FluidStack(Fluids.WATER, 320), FluidAction.EXECUTE);
    energyPort.energy().receiveEnergy(512, false);

    var fluidStatus = MachinePayloads.resourceStatus(fluidPort);
    var energyStatus = MachinePayloads.resourceStatus(energyPort);
    helper.assertTrue(
        fluidStatus.kind() == PortKind.FLUID_INPUT
            && fluidStatus.amount() == 320
            && fluidStatus.capacity() == 1_000
            && fluidStatus.fluid().getFluid() == Fluids.WATER,
        "Fluid menu status did not capture its current server-side tank snapshot");
    helper.assertTrue(
        energyStatus.kind() == PortKind.ENERGY_INPUT
            && energyStatus.amount() == 512
            && energyStatus.capacity() == 8_192
            && energyStatus.transferLimit() == 512,
        "Energy menu status did not capture its current server-side long snapshot");
    fluidPort.fluidTank().fill(new FluidStack(Fluids.WATER, 80), FluidAction.EXECUTE);
    helper.assertTrue(
        MachinePayloads.resourceStatus(fluidPort).amount() == 400,
        "Fluid status snapshot did not update after a tank change");
    helper.succeed();
  }

  private static void confirm(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(controller.canCommitProduction(), "Not confirmed: " + controller.statusMessage().getString());
  }
}
