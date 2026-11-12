package com.lw.modularmachine.gametest;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class OrientationLifecycleGameTests {
  @GameTest(template = "empty16", timeoutTicks = 120)
  public static void preparation_never_force_loads_a_missing_dependency_and_recovers_after_explicit_load(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7)).offset(10000, 0, 10000);
    BlockPos dependency = origin.east(128);
    helper.getLevel().getChunkAt(origin); // Test setup explicitly loads only the controller chunk.
    helper.assertTrue(!helper.getLevel().hasChunkAt(dependency), "Missing-dependency fixture is already loaded");
    helper.getLevel().setBlockAndUpdate(origin, MachineRegistries.CONTROLLER.get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_far_dependency"));
    for (int i = 0; i < 100 && controller.verificationState() == VerificationState.PENDING; i++) {
      StructureService.advance(helper.getLevel(), 256, 64, 50_000_000L);
    }
    helper.assertTrue(
        controller.verificationFailure() == VerificationFailure.CHUNK_UNAVAILABLE
            && !helper.getLevel().hasChunkAt(dependency)
            && !controller.canCommitProduction(),
        "Preparation loaded an absent chunk or retained permission");
    helper.getLevel().getChunkAt(dependency); // Explicit test action, never performed by the verifier.
    helper.getLevel().setBlockAndUpdate(dependency, Blocks.STONE_BRICKS.defaultBlockState());
    confirm(helper, controller);
    helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
    helper.getLevel().setBlockAndUpdate(dependency, Blocks.AIR.defaultBlockState());
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void parts_outside_build_height_are_unavailable_instead_of_air_matches(GameTestHelper helper) {
    BlockPos at = helper.absolutePos(new BlockPos(7, 5, 7));
    BlockPos origin = new BlockPos(at.getX(), helper.getLevel().getMaxBuildHeight() - 1, at.getZ());
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(
            MachineRegistries.CONTROLLER.get().defaultBlockState(),
            MachineOrientation.of(Direction.NORTH, 1)));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_schema2_axis"));
    for (int i = 0; i < 100 && controller.verificationState() == VerificationState.PENDING; i++) {
      StructureService.advance(helper.getLevel(), 256, 64, 50_000_000L);
    }
    helper.assertTrue(
        controller.verificationFailure() == VerificationFailure.CHUNK_UNAVAILABLE && !controller.canCommitProduction(),
        "Out-of-height position was treated as a normal block");
    helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
    helper.succeed();
  }

  private static final ResourceLocation MACHINE = id("test_orientation_machine");
  private static final Direction[] FRONT = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
      Direction.UP, Direction.DOWN};
  private static final Direction[][] UP = {{Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST},
      {Direction.UP, Direction.NORTH, Direction.DOWN, Direction.SOUTH},
      {Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST},
      {Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH},
      {Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST},
      {Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST}};
  private static final int[][] INPUT = {{-1, 0, 1}, {0, -1, 1}, {1, 0, 1}, {0, 1, 1}, {-1, 0, -1}, {-1, -1, 0},
      {-1, 0, 1}, {-1, 1, 0}, {1, 0, -1}, {0, -1, -1}, {-1, 0, -1}, {0, 1, -1}, {1, 0, 1}, {1, -1, 0}, {1, 0, -1},
      {1, 1, 0}, {-1, -1, 0}, {0, -1, -1}, {1, -1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, 1, 1}, {1, 1, 0}, {0, 1, -1}};
  private static final int[][] OUTPUT = {{1, 0, 1}, {0, 1, 1}, {-1, 0, 1}, {0, -1, 1}, {-1, 0, 1}, {-1, 1, 0},
      {-1, 0, -1}, {-1, -1, 0}, {-1, 0, -1}, {0, 1, -1}, {1, 0, -1}, {0, -1, -1}, {1, 0, -1}, {1, 1, 0}, {1, 0, 1},
      {1, -1, 0}, {1, -1, 0}, {0, -1, 1}, {-1, -1, 0}, {0, -1, -1}, {1, 1, 0}, {0, 1, -1}, {-1, 1, 0}, {0, 1, 1}};

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void all_24_world_generations_match_literal_ports_and_transfer_exactly_once(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    StructureService.Registration previous = null;
    for (int index = 0; index < 24; index++) {
      var orientation = MachineOrientation.of(FRONT[index / 4], index % 4);
      var controller = build(helper, origin, orientation);
      confirm(helper, controller);
      if (previous != null) {
        helper.assertTrue(!previous.canCommit(helper.getLevel()), "Previous instance regained authority");
      }
      var ticket = controller.registration();
      helper.assertTrue(ticket.pattern.orientation == orientation, "Wrong complete orientation");
      helper.assertTrue(
          ticket.pattern.energyInputPosition().equals(offset(origin, INPUT[index]))
              && ticket.pattern.energyOutputPosition().equals(offset(origin, OUTPUT[index])),
          "Literal input/output geometry differs at " + orientation);
      helper.assertTrue(
          controller.fullScan().state() == VerificationState.CONFIRMED,
          "Full scan disagrees at " + orientation);
      var source = port(helper, offset(origin, INPUT[index]));
      var target = port(helper, offset(origin, OUTPUT[index]));
      helper.assertTrue(source.energy().receiveEnergy(128, false) == 128, "Input refused seed energy");
      controller.serverTick();
      helper.assertTrue(
          source.energy().stored() == 0 && target.energy().stored() == 128 && controller.successfulTransfers() == 1,
          "Conservation/duplicate transfer failure at " + orientation);
      previous = ticket;
      clear(helper, origin, orientation);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void same_front_roll_change_revokes_old_proof_and_restoring_pose_resumes_safely(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    confirm(helper, controller);
    var old = controller.registration();
    var source = port(helper, origin.offset(-1, 0, 1));
    var target = port(helper, origin.offset(1, 0, 1));
    source.energy().receiveEnergy(128, false);
    helper.assertTrue(
        StructureService
            .reorient(controller, MachineOrientation.of(Direction.NORTH, 1)) == StructureService.TurnResult.CHANGED,
        "Roll was not applied");
    helper.assertTrue(
        controller.verificationState() == VerificationState.PENDING && !old.canCommit(helper.getLevel()),
        "Old roll proof survived");
    controller.serverTick();
    helper.assertTrue(source.energy().stored() == 128 && target.energy().stored() == 0, "Pending consumed/produced");
    helper.assertTrue(
        helper.getLevel().getBlockEntity(origin.offset(-1, 0, 1)) == source,
        "Rotation moved physical storage");
    helper.assertTrue(
        StructureService.reorient(controller, MachineOrientation.IDENTITY) == StructureService.TurnResult.CHANGED,
        "Could not restore pose");
    confirm(helper, controller);
    helper.assertTrue(controller.registration().generation() > old.generation(), "Generation did not increase");
    controller.serverTick();
    helper.assertTrue(
        source.energy().stored() == 0 && target.energy().stored() == 128,
        "Restored pose did not transfer once");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void repeated_turns_coalesce_and_an_unchanged_request_does_not_rebind(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    confirm(helper, controller);
    var old = controller.registration();
    helper.assertTrue(
        StructureService.reorient(controller, MachineOrientation.IDENTITY) == StructureService.TurnResult.UNCHANGED
            && controller.registration() == old,
        "No-op request rebuilt a valid ticket");
    for (int i = 1; i <= 240; i++) {
      StructureService.reorient(controller, MachineOrientation.all().get(i % 24));
      helper
          .assertTrue(StructureService.pendingWorkCount(controller) <= 2, "Requests accumulated unbounded generations");
      helper.assertTrue(!old.canCommit(helper.getLevel()), "Retired ticket became usable");
    }
    confirm(helper, controller);
    helper.assertTrue(
        controller.registration().pattern.orientation == MachineOrientation.IDENTITY,
        "Latest target was lost");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void direct_section_orientation_change_and_same_position_replacement_reject_old_tickets(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    confirm(helper, controller);
    var old = controller.registration();
    var source = port(helper, origin.offset(-1, 0, 1));
    var target = port(helper, origin.offset(1, 0, 1));
    source.energy().receiveEnergy(128, false);
    var chunk = helper.getLevel().getChunkAt(origin);
    var section = chunk.getSection(chunk.getSectionIndex(origin.getY()));
    var base = helper.getLevel().getBlockState(origin);
    section.setBlockState(
        origin.getX() & 15,
        origin.getY() & 15,
        origin.getZ() & 15,
        MachineControllerBlock.withOrientation(base, MachineOrientation.of(Direction.NORTH, 1)));
    helper.assertTrue(
        !controller.canCommitProduction() && !old.canCommit(helper.getLevel()),
        "Actual-state fence missed untracked roll");
    controller.serverTick();
    helper.assertTrue(
        source.energy().stored() == 128 && target.energy().stored() == 0,
        "Untracked roll committed old resources");
    section.setBlockState(origin.getX() & 15, origin.getY() & 15, origin.getZ() & 15, base);
    controller.serverTick();
    confirm(helper, controller);
    var beforeReplacement = controller.registration();
    helper.getLevel().removeBlockEntity(origin);
    var replacement = new MachineControllerBlockEntity(origin, base);
    helper.getLevel().setBlockEntity(replacement);
    replacement.onLoad();
    replacement.bindMachine(MACHINE);
    confirm(helper, replacement);
    helper.assertTrue(
        !beforeReplacement.canCommit(helper.getLevel()) && !old.canCommit(helper.getLevel()),
        "Ticket crossed BE instance identity");
    replacement.serverTick();
    helper.assertTrue(
        source.energy().stored() == 0 && target.energy().stored() == 128,
        "Replacement failed conserved transfer");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void forbidden_pose_and_unknown_anchor_recover_without_waiting_for_a_periodic_audit(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    helper.getLevel().setBlockAndUpdate(origin, MachineRegistries.CONTROLLER.get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        origin.east(),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_axis"));
    confirm(helper, controller);
    var old = controller.registration();
    helper.assertTrue(
        StructureService
            .reorient(controller, MachineOrientation.of(Direction.UP, 0)) == StructureService.TurnResult.NOT_ALLOWED
            && controller.registration() == old,
        "Forbidden API request mutated the machine");
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(helper.getLevel().getBlockState(origin), MachineOrientation.of(Direction.UP, 0)));
    helper.assertTrue(
        controller.verificationState() == VerificationState.INVALID
            && controller.verificationFailure() == VerificationFailure.ORIENTATION_NOT_ALLOWED,
        "External forbidden state did not close production");
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(helper.getLevel().getBlockState(origin), MachineOrientation.IDENTITY));
    confirm(helper, controller);
    controller.bindMachine(id("temporarily_unknown"));
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(helper.getLevel().getBlockState(origin), MachineOrientation.of(Direction.NORTH, 1)));
    helper.assertTrue(
        controller.verificationFailure() == VerificationFailure.UNKNOWN_DEFINITION,
        "Unknown anchor lost its reason");
    helper.getLevel().setBlockAndUpdate(
        origin.above(),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
    controller.bindMachine(id("test_schema2_axis"));
    confirm(helper, controller);
    helper.assertTrue(
        controller.registration().pattern.orientation == MachineOrientation.of(Direction.NORTH, 1),
        "Recovered anchor used stale pose");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void one_unit_global_budget_is_fair_and_partial_preparation_never_commits(GameTestHelper helper) {
    var first = build(helper, helper.absolutePos(new BlockPos(3, 4, 3)), MachineOrientation.IDENTITY);
    var second = build(helper, helper.absolutePos(new BlockPos(11, 4, 11)), MachineOrientation.IDENTITY);
    helper.assertTrue(
        first.registration() == null
            && second.registration() == null
            && !first.canCommitProduction()
            && !second.canCommitProduction(),
        "Preparation was published synchronously");
    int calls = 0;
    while ((!first.canCommitProduction() || !second.canCommitProduction()) && calls++ < 5000) {
      int used = StructureService.advance(helper.getLevel(), 1, 1, 50_000_000L);
      helper.assertTrue(used >= 0 && used <= 1, "Global budget was exceeded");
    }
    helper.assertTrue(first.canCommitProduction() && second.canCommitProduction(), "Round robin starved a controller");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 120)
  public static void continuous_turns_revoke_old_async_results_and_final_pose_recovers(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = build(helper, origin, MachineOrientation.IDENTITY);
    for (int tick = 1; tick <= 9; tick++) {
      final int step = tick;
      helper.runAfterDelay(step, () -> {
        var old = controller.registration();
        StructureService.reorient(controller, MachineOrientation.all().get(step));
        helper.assertTrue(old == null || !old.canCommit(helper.getLevel()), "Retired async result retained authority");
        if (step == 9) {
          StructureService.reorient(controller, MachineOrientation.IDENTITY);
          helper.succeedWhen(() -> helper.assertTrue(controller.canCommitProduction(), "Final pose did not recover"));
        }
      });
    }
  }
  @GameTest(template = "empty16", batch = "orientation_reentrant", timeoutTicks = 80)
  public static void an_opaque_legacy_rotation_callback_cannot_commit_a_retired_resource_ticket(GameTestHelper helper) {
    BlockPos origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var orientation = MachineOrientation.of(Direction.EAST, 0);
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), orientation));
    BlockPos in = literal(origin, -1, 0, 1, orientation), out = literal(origin, 1, 0, 1, orientation),
        callback = literal(origin, 0, 1, 1, orientation);
    helper.getLevel()
        .setBlockAndUpdate(in, MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
    helper.getLevel()
        .setBlockAndUpdate(out, MachineRegistries.PORTS.get("energy_output_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        callback,
        TestPredicateBlock.INSTANCE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id("test_reentrant_machine"));
    confirm(helper, controller);
    var ticket = controller.registration();
    var source = port(helper, in);
    var target = port(helper, out);
    source.energy().receiveEnergy(128, false);
    var invoked = new AtomicBoolean();
    TestPredicateBlock.CALLBACK.set(() -> {
      invoked.set(true);
      StructureService.reorient(controller, MachineOrientation.of(Direction.SOUTH, 0));
    });
    try {
      controller.serverTick();
      helper.assertTrue(invoked.get(), "Conservative proof did not reach the armed predicate");
      helper.assertTrue(
          !ticket.canCommit(helper.getLevel()) && source.energy().stored() == 128 && target.energy().stored() == 0,
          "Reentrant callback committed the old source/output plan");
      helper.succeed();
    } finally {
      TestPredicateBlock.CALLBACK.set(null);
    }
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void old_palette_without_roll_and_all_new_states_serialize_correctly(GameTestHelper helper) {
    var old = new CompoundTag();
    old.putString("Name", "mmcece:machine_controller");
    var properties = new CompoundTag();
    properties.putString("facing", "east");
    old.put("Properties", properties);
    var lookup = helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
    var decoded = NbtUtils.readBlockState(lookup, old);
    helper.assertTrue(
        decoded.getValue(MachineControllerBlock.FACING) == Direction.EAST
            && decoded.getValue(MachineControllerBlock.ROLL) == 0,
        "Missing old roll was not zero");
    for (var orientation : MachineOrientation.all()) {
      var state = MachineControllerBlock
          .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), orientation);
      helper.assertTrue(
          NbtUtils.readBlockState(lookup, NbtUtils.writeBlockState(state)) == state,
          "State palette round trip failed");
      var spun = state;
      for (int i = 0; i < 4; i++) {
        spun = spun.rotate(Rotation.CLOCKWISE_90);
      }
      helper.assertTrue(spun == state, "Four world-Y rotations did not restore complete pose");
      helper.assertTrue(
          state.mirror(Mirror.LEFT_RIGHT).mirror(Mirror.LEFT_RIGHT) == state,
          "Mirror axes did not round trip");
    }
    var up = MachineControllerBlock.withOrientation(decoded, MachineOrientation.of(Direction.UP, 0))
        .rotate(Rotation.CLOCKWISE_90);
    helper.assertTrue(
        MachineControllerBlock.orientationOf(up) == MachineOrientation.of(Direction.UP, 1),
        "World-Y rotation lost a vertical front's roll");
    helper.succeed();
  }

  private static MachineControllerBlockEntity build(GameTestHelper helper, BlockPos origin, MachineOrientation orientation) {
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), orientation));
    helper.getLevel().setBlockAndUpdate(
        literal(origin, -1, 0, 1, orientation),
        MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        literal(origin, 1, 0, 1, orientation),
        MachineRegistries.PORTS.get("energy_output_hatch_normal").get().defaultBlockState());
    int[] right = right(orientation);
    Direction.Axis axis = right[0] != 0 ? Direction.Axis.X : right[1] != 0 ? Direction.Axis.Y : Direction.Axis.Z;
    helper.getLevel().setBlockAndUpdate(
        literal(origin, 0, 1, 1, orientation),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis));
    helper.getLevel().setBlockAndUpdate(literal(origin, 1, 2, 2, orientation), Blocks.STONE_BRICKS.defaultBlockState());
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(MACHINE);
    return controller;
  }

  private static void clear(GameTestHelper helper, BlockPos origin, MachineOrientation orientation) {
    for (int[] p : new int[][]{{-1, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 2, 2}}) {
      helper.getLevel()
          .setBlockAndUpdate(literal(origin, p[0], p[1], p[2], orientation), Blocks.AIR.defaultBlockState());
    }
    helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
  }

  private static void confirm(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(controller.canCommitProduction(), "Did not confirm: " + controller.statusMessage().getString());
  }

  private static MachinePortBlockEntity port(GameTestHelper helper, BlockPos position) {
    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(position);
  }

  private static BlockPos offset(BlockPos origin, int[] p) {
    return origin.offset(p[0], p[1], p[2]);
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  private static int[] vector(Direction d) {
    return new int[]{d.getStepX(), d.getStepY(), d.getStepZ()};
  }

  private static int[] right(MachineOrientation orientation) {
    int index = orientation.stableIndex();
    int[] f = vector(FRONT[index / 4]), u = vector(UP[index / 4][index % 4]);
    return new int[]{f[1] * u[2] - f[2] * u[1], f[2] * u[0] - f[0] * u[2], f[0] * u[1] - f[1] * u[0]};
  }

  private static BlockPos literal(BlockPos origin, int x, int y, int z, MachineOrientation orientation) {
    int index = orientation.stableIndex();
    int[] f = vector(FRONT[index / 4]), u = vector(UP[index / 4][index % 4]), r = right(orientation);
    return origin
        .offset(r[0] * x + u[0] * y - f[0] * z, r[1] * x + u[1] * y - f[1] * z, r[2] * x + u[2] * y - f[2] * z);
  }
}
