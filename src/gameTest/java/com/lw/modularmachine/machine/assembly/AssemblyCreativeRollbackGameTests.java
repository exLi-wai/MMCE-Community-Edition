package com.lw.modularmachine.machine.assembly;

import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_assembly_rollback")
@PrefixGameTestTemplate(false)
public final class AssemblyCreativeRollbackGameTests {
  private AssemblyCreativeRollbackGameTests() {
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void failed_batch_restores_all_resources_and_partial_write_without_drops(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 3, 3));
    var item = port(helper, origin, "item_input_bus_normal");
    item.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 23));
    var fluid = port(helper, origin.east(), "fluid_input_hatch_normal");
    fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 2345));
    var energy = port(helper, origin.east(2), "energy_input_hatch_normal");
    energy.energy().load(12345);
    var partial = port(helper, origin.east(3), "fluid_input_hatch_normal");
    partial.fluidTank().setFluid(new FluidStack(Fluids.LAVA, 321));
    var originals = List.of(item, fluid, energy, partial);
    var saved = new ArrayList<CompoundTag>();
    var parts = new ArrayList<AssemblyPattern.Part>();
    for (var original : originals) {
      original.setMachineColor(0x38A6D2);
      original.getPersistentData().putString("assembly_test", "preserve");
      saved.add(original.saveWithFullMetadata(level.registryAccess()));
      parts.add(part(original.getBlockPos(), Blocks.STONE.defaultBlockState()));
    }
    parts.set(3, part(partial.getBlockPos(), TestCreativeAssemblyBlock.INSTANCE.defaultBlockState()));
    boolean[] attempted = {false};
    TestCreativeAssemblyBlock.CALLBACK.set((world, position) -> {
      attempted[0] = true;
      helper.assertTrue(world.getBlockState(origin).is(Blocks.STONE), "Failure happened before the first write");
      // Recreate the original state with an empty BE before throwing. The failing
      // write itself must be included, even though setBlock never returns.
      world.setBlock(position, partial.getBlockState(), Block.UPDATE_KNOWN_SHAPE);
      throw new IllegalStateException("Injected creative write failure");
    });
    try {
      expectFailure(() -> AssemblyCreativePlacement.build(player(helper, origin), pattern(origin, parts)));
    } finally {
      TestCreativeAssemblyBlock.CALLBACK.set(null);
    }
    helper.assertTrue(attempted[0], "The test never reached the injected partial-write failure");
    for (int index = 0; index < originals.size(); index++) {
      var original = originals.get(index);
      var restored = level.getBlockEntity(original.getBlockPos());
      helper.assertTrue(restored != null, "Rollback omitted a block entity");
      helper.assertTrue(
          saved.get(index).equals(restored.saveWithFullMetadata(level.registryAccess())),
          "Rollback lost saved resources, color, or persistent data at " + original.getBlockPos());
    }
    helper.assertTrue(items(helper, origin).isEmpty(), "Failed creative rollback duplicated inventory drops");
    helper.assertTrue(!AssemblyPlacement.active() && !level.restoringBlockSnapshots, "Rollback leaked active flags");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void unknown_container_is_rejected_before_any_batch_write(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 3, 3));
    level.setBlockAndUpdate(origin, Blocks.DIRT.defaultBlockState());
    level.setBlockAndUpdate(origin.east(), Blocks.CHEST.defaultBlockState());
    var chest = (ChestBlockEntity) level.getBlockEntity(origin.east());
    chest.setItem(0, new ItemStack(Items.DIAMOND, 23));
    var saved = chest.saveWithFullMetadata(level.registryAccess());
    var failure = expectFailure(
        () -> AssemblyCreativePlacement.build(
            player(helper, origin),
            pattern(
                origin,
                List.of(
                    part(origin, Blocks.STONE.defaultBlockState()),
                    part(origin.east(), Blocks.STONE.defaultBlockState())))));
    helper.assertTrue(
        failure instanceof AssemblyCreativePlacement.Failure && failure.getMessage().contains("unsafe_block_entity"),
        "Unknown container rejection did not identify the unsafe replacement");
    helper.assertTrue(
        level.getBlockState(origin).is(Blocks.DIRT),
        "Batch wrote an earlier part before rejecting a container");
    helper.assertTrue(
        level.getBlockEntity(origin.east()) == chest
            && saved.equals(chest.saveWithFullMetadata(level.registryAccess())),
        "Rejected container replacement changed its original data");
    helper.assertTrue(items(helper, origin).isEmpty(), "Rejected container replacement dropped its contents");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void piston_head_is_rejected_before_any_batch_write(GameTestHelper helper) {
    rejectsPistonHead(helper, Blocks.PISTON, PistonType.DEFAULT, false);
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void sticky_piston_head_is_rejected_before_any_batch_write(GameTestHelper helper) {
    rejectsPistonHead(helper, Blocks.STICKY_PISTON, PistonType.STICKY, false);
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void new_piston_head_is_rejected_before_rollback_can_remove_external_base(GameTestHelper helper) {
    rejectsPistonHead(helper, Blocks.PISTON, PistonType.DEFAULT, true);
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void new_sticky_piston_head_is_rejected_before_rollback_can_remove_external_base(GameTestHelper helper) {
    rejectsPistonHead(helper, Blocks.STICKY_PISTON, PistonType.STICKY, true);
  }

  private static void rejectsPistonHead(GameTestHelper helper, Block piston, PistonType type, boolean placingHead) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 3, 3));
    var headPosition = origin.east();
    var basePosition = origin.east(2);
    var laterPosition = origin.north();
    var base = piston.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST)
        .setValue(PistonBaseBlock.EXTENDED, true);
    var head = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.WEST)
        .setValue(PistonHeadBlock.TYPE, type);
    var before = placingHead ? Blocks.AIR.defaultBlockState() : head;
    var after = placingHead ? head : Blocks.STONE.defaultBlockState();
    level.setBlock(origin, Blocks.DIRT.defaultBlockState(), Block.UPDATE_KNOWN_SHAPE);
    level.setBlock(basePosition, base, Block.UPDATE_KNOWN_SHAPE);
    level.setBlock(headPosition, before, Block.UPDATE_KNOWN_SHAPE);
    helper.assertTrue(level.getBlockEntity(headPosition) == null, "Piston head fixture unexpectedly has a BE");
    boolean[] attempted = {false};
    TestCreativeAssemblyBlock.CALLBACK.set((world, position) -> {
      attempted[0] = true;
      throw new IllegalStateException("Injected later creative write failure");
    });
    RuntimeException failure;
    try {
      failure = expectFailure(
          () -> AssemblyCreativePlacement.build(
              player(helper, origin),
              pattern(
                  origin,
                  List.of(
                      part(origin, Blocks.STONE.defaultBlockState()),
                      part(headPosition, after),
                      part(laterPosition, TestCreativeAssemblyBlock.INSTANCE.defaultBlockState())))));
    } finally {
      TestCreativeAssemblyBlock.CALLBACK.set(null);
    }
    helper.assertTrue(
        failure instanceof AssemblyCreativePlacement.Failure && failure.getMessage().contains("unsafe_replacement"),
        "Piston head was not rejected during whole-batch validation");
    helper.assertTrue(!attempted[0], "Unsafe replacement reached the later failing write");
    helper.assertTrue(level.getBlockState(origin).is(Blocks.DIRT), "Unsafe batch wrote an earlier part");
    helper
        .assertTrue(level.getBlockState(headPosition).equals(before), "Rejected replacement changed the head position");
    helper.assertTrue(level.getBlockState(basePosition).equals(base), "Rejected replacement changed the external base");
    helper.assertTrue(level.getBlockState(laterPosition).isAir(), "Unsafe batch wrote the later part");
    helper.assertTrue(items(helper, origin).isEmpty(), "Rejected piston replacement produced drops");
    helper.assertTrue(!AssemblyPlacement.active() && !level.restoringBlockSnapshots, "Rejection leaked active flags");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void matching_piston_head_is_untouched_by_creative_assembly(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 3, 3));
    var headPosition = origin.east();
    var basePosition = origin.east(2);
    var base = Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST)
        .setValue(PistonBaseBlock.EXTENDED, true);
    var head = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.WEST);
    level.setBlock(origin, Blocks.DIRT.defaultBlockState(), Block.UPDATE_KNOWN_SHAPE);
    level.setBlock(basePosition, base, Block.UPDATE_KNOWN_SHAPE);
    level.setBlock(headPosition, head, Block.UPDATE_KNOWN_SHAPE);
    var result = AssemblyCreativePlacement.build(
        player(helper, origin),
        pattern(origin, List.of(part(origin, Blocks.STONE.defaultBlockState()), part(headPosition, head))));
    helper.assertTrue(result.matched() == 2 && result.placed() == 1, "Matching piston head blocked safe assembly");
    helper.assertTrue(level.getBlockState(headPosition).equals(head), "Matching piston head was changed");
    helper.assertTrue(level.getBlockState(basePosition).equals(base), "Matching piston base was changed");
    helper.assertTrue(items(helper, origin).isEmpty(), "Matching piston head produced drops");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void successful_batch_drops_displaced_items_once_and_matching_container_is_untouched(GameTestHelper helper) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(3, 3, 3));
    var item = port(helper, origin, "item_input_bus_normal");
    item.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 23));
    level.setBlockAndUpdate(origin.east(), Blocks.CHEST.defaultBlockState());
    var chest = (ChestBlockEntity) level.getBlockEntity(origin.east());
    chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
    var saved = chest.saveWithFullMetadata(level.registryAccess());
    var result = AssemblyCreativePlacement.build(
        player(helper, origin),
        pattern(
            origin,
            List.of(part(origin, Blocks.STONE.defaultBlockState()), part(origin.east(), chest.getBlockState()))));
    helper.assertTrue(result.matched() == 2 && result.placed() == 1, "Successful batch counts changed");
    helper.assertTrue(
        level.getBlockEntity(origin.east()) == chest
            && saved.equals(chest.saveWithFullMetadata(level.registryAccess())),
        "Already matching container was replaced");
    var drops = items(helper, origin);
    helper.assertTrue(
        drops.size() == 1
            && drops.getFirst().getItem().is(Items.DIAMOND)
            && drops.getFirst().getItem().getCount() == 23,
        "Successful batch did not preserve exactly one displaced inventory drop");
    helper.assertTrue(
        !AssemblyPlacement.active() && !level.restoringBlockSnapshots,
        "Successful batch leaked active flags");
    helper.succeed();
  }

  private static MachinePortBlockEntity port(GameTestHelper helper, BlockPos position, String name) {
    helper.getLevel().setBlockAndUpdate(position, MachineRegistries.PORTS.get(name).get().defaultBlockState());

    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(position);
  }

  private static AssemblyPattern.Part part(BlockPos position, BlockState state) {
    return new AssemblyPattern.Part(position, new MachineDefinition.Rule(null, List.of()), List.of(state));
  }

  private static AssemblyPattern pattern(BlockPos origin, List<AssemblyPattern.Part> parts) {
    return new AssemblyPattern(
        ResourceLocation.fromNamespaceAndPath("mmcece", "test_creative_rollback"),
        0,
        origin,
        MachineOrientation.IDENTITY,
        null,
        Map.of(),
        parts);
  }

  private static FakePlayer player(GameTestHelper helper, BlockPos position) {
    var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(new UUID(0, 0xACEDL), "assembly_test"));
    player.setGameMode(GameType.CREATIVE);
    player.setPos(position.getX(), position.getY(), position.getZ());

    return player;
  }

  private static List<ItemEntity> items(GameTestHelper helper, BlockPos origin) {
    return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(6));
  }

  private static RuntimeException expectFailure(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException expected) {
      return expected;
    }
    throw new AssertionError("Creative assembly unexpectedly succeeded");
  }
}
