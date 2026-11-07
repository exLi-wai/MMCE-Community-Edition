package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.controller.MachineControllerApi;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.item.WrenchInteractions;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Native ServerPlayerGameMode interactions, not direct calls to the trusted
 * rotation API.
 */
@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class ControllerPlayerInteractionGameTests {
  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void controller_api_exposes_immutable_snapshot_and_checked_turn_command(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    confirm(helper, controller);
    var snapshot = MachineControllerApi.inspect(controller).orElseThrow();
    helper.assertTrue(
        snapshot.position().equals(pos)
            && snapshot.machineId().equals(id("test_schema2_axis"))
            && snapshot.orientation() == MachineOrientation.IDENTITY
            && snapshot.definitionEpoch() == StructureService.definitionEpoch()
            && snapshot.productionAllowed()
            && snapshot.runningRun().isEmpty(),
        "Controller API snapshot omitted live machine state");

    var player = player(helper, pos, 7);
    helper.assertTrue(
        MachineControllerApi.reorient(
            controller,
            MachineOrientation.of(Direction.NORTH, 1),
            player) == MachineControllerApi.ReorientationResult.CHANGED,
        "Controller API did not route through checked reorientation");
    helper.assertTrue(
        snapshot.orientation() == MachineOrientation.IDENTITY,
        "Controller API snapshot changed after the live controller turned");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void controller_menu_opens_with_server_identity_and_closes_out_of_range(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    confirm(helper, controller);
    var player = player(helper, pos, 1);
    var menu = (MachineControllerMenu) controller.createMenu(7, player.getInventory(), player);
    helper.assertTrue(
        menu.controller() == controller && menu.stillValid(player),
        "Controller menu did not bind the authoritative server entity");
    player.containerMenu = menu;
    player.setPos(pos.getX() + 20.5, pos.getY() + .5, pos.getZ() + .5);
    helper.assertTrue(!player.containerMenu.stillValid(player), "Controller menu remained valid out of range");
    player.closeContainer();
    helper.assertTrue(player.containerMenu == player.inventoryMenu, "Controller menu did not close cleanly");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void one_main_hand_click_rolls_once_shift_reverses_and_offhand_does_not_repeat(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    confirm(helper, controller);
    var old = controller.registration();
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
    player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 1);
    helper.assertTrue(!old.canCommit(helper.getLevel()), "Player wrench retained old proof");
    click(helper, player, pos, Direction.NORTH, InteractionHand.OFF_HAND);
    checkPose(helper, pos, Direction.NORTH, 1);
    player.setShiftKeyDown(true);
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    helper.assertTrue(
        player.getMainHandItem().getCount() == 1 && player.getOffhandItem().getCount() == 1,
        "Wrench use consumed items");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void other_faces_change_front_and_legacy_policy_rejects_roll_without_mutation(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
    click(helper, player, pos, Direction.EAST, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.EAST, 0);
    click(helper, player, pos, Direction.UP, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.UP, 0);
    helper.getLevel().setBlockAndUpdate(pos, MachineRegistries.CONTROLLER.get().defaultBlockState());
    controller.bindMachine(id("test_axis"));
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void wrench_denies_far_spectator_and_adventure_players(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    controller(helper, pos, "test_schema2_axis");
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
    player.setPos(pos.getX() + 100, pos.getY(), pos.getZ());
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    position(player, pos);
    player.setGameMode(GameType.SPECTATOR);
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    player.setGameMode(GameType.ADVENTURE);
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    player.setGameMode(GameType.SURVIVAL);
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 1);
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void cancelled_and_denied_block_interaction_hooks_cannot_fall_through_to_rotation(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    controller(helper, pos, "test_schema2_axis");
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
    Consumer<PlayerInteractEvent.RightClickBlock> cancel = event -> {
      if (event.getEntity() == player && event.getPos().equals(pos)) {
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
      }
    };
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, cancel);
    try {
      click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
      checkPose(helper, pos, Direction.NORTH, 0);
    } finally {
      NeoForge.EVENT_BUS.unregister(cancel);
    }
    Consumer<PlayerInteractEvent.RightClickBlock> deny = event -> {
      if (event.getEntity() == player && event.getPos().equals(pos)) {
        event.setUseBlock(TriState.FALSE);
      }
    };
    NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, deny);
    try {
      player.setShiftKeyDown(true);
      click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
      checkPose(helper, pos, Direction.NORTH, 0);
    } finally {
      NeoForge.EVENT_BUS.unregister(deny);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void two_players_compute_actions_from_current_server_state(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    controller(helper, pos, "test_schema2_axis");
    for (int slot = 1; slot <= 2; slot++) {
      var player = player(helper, pos, slot);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.WRENCH.get()));
      click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    }
    checkPose(helper, pos, Direction.NORTH, 2);
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void native_floor_ceiling_and_horizontal_placement_follow_view_and_yaw(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    float[] yaw = {180, -90, 0, 90};
    Direction[] horizontal = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    int[] floorRoll = {2, 3, 0, 1}, ceilingRoll = {0, 3, 2, 1};
    for (int i = 0; i < yaw.length; i++) {
      for (int mode = 0; mode < 3; mode++) {
        cleanPlacement(helper, pos);
        var player = player(helper, pos, 1);
        var stack = bound("test_schema2_axis");
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        place(helper, player, pos, yaw[i], mode == 0 ? 30 : mode == 1 ? 90 : -90);
        var entity = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(entity instanceof MachineControllerBlockEntity, "Native placement did not create controller");
        helper.assertTrue(
            ((MachineControllerBlockEntity) entity).machineId().equals(id("test_schema2_axis")),
            "Portable binding was lost");
        if (mode == 0) {
          checkPose(helper, pos, horizontal[i].getOpposite(), 0);
        } else {
          checkPose(helper, pos, mode == 1 ? Direction.UP : Direction.DOWN, mode == 1 ? floorRoll[i] : ceilingRoll[i]);
        }
        helper.assertTrue(stack.isEmpty(), "Successful survival placement did not consume exactly one item");
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void default_binding_allows_vertical_placement_unknown_keeps_horizontal(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(MachineRegistries.CONTROLLER.get()));
    place(helper, player, pos, 0, 90);
    checkPose(helper, pos, Direction.UP, 0);
    cleanPlacement(helper, pos);
    var unknown = bound("unknown_for_placement");
    player.setItemInHand(InteractionHand.MAIN_HAND, unknown);
    place(helper, player, pos, 0, 90);
    checkPose(helper, pos, Direction.NORTH, 0);
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(pos);
    helper.assertTrue(
        controller.machineId().equals(id("unknown_for_placement"))
            && !controller.canCommitProduction()
            && controller.verificationFailure() == VerificationFailure.UNKNOWN_DEFINITION,
        "Unknown item became default machine");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void carried_forbidden_pose_and_foreign_type_fail_before_item_consumption(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var player = player(helper, pos, 1);
    var forbidden = bound("test_recipe_reentrant");
    forbidden.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of("facing", "up", "roll", "1")));
    player.setItemInHand(InteractionHand.MAIN_HAND, forbidden);
    place(helper, player, pos, 0, 90);
    helper.assertTrue(
        helper.getLevel().getBlockState(pos).isAir() && forbidden.getCount() == 1,
        "Forbidden carried pose was placed/consumed");
    var foreign = bound("test_schema2_axis");
    var data = foreign.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
    data.putString("id", "minecraft:chest");
    foreign.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(data));
    player.setItemInHand(InteractionHand.MAIN_HAND, foreign);
    place(helper, player, pos, 0, 90);
    helper.assertTrue(
        helper.getLevel().getBlockState(pos).isAir() && foreign.getCount() == 1,
        "Foreign BE type was placed/consumed");
    var valid = bound("test_schema2_axis");
    valid.set(DataComponents.BLOCK_STATE, new BlockItemStateProperties(Map.of("facing", "up", "roll", "2")));
    player.setItemInHand(InteractionHand.MAIN_HAND, valid);
    place(helper, player, pos, 0, 90);
    checkPose(helper, pos, Direction.UP, 2);
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void dropped_controller_keeps_binding_but_not_runtime_counters_or_runs(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    var tag = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
    tag.getCompound("mmcece").putLong("successful_transfers", 42);
    controller.loadWithComponents(tag, helper.getLevel().registryAccess());
    List<ItemStack> drops = Block.getDrops(controller.getBlockState(), helper.getLevel(), pos, controller);
    helper.assertTrue(drops.size() == 1, "Controller drop count wrong");
    var portable = drops.getFirst().get(DataComponents.BLOCK_ENTITY_DATA).copyTag().getCompound("mmcece");
    helper.assertTrue(
        portable.getString("machine").equals("mmcece:test_schema2_axis") && !portable.contains("successful_transfers"),
        "Portable whitelist copied run/counter data or lost binding");
    cleanPlacement(helper, pos);
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst());
    place(helper, player, pos, 0, 90);
    checkPose(helper, pos, Direction.UP, 0);
    helper.assertTrue(
        ((MachineControllerBlockEntity) helper.getLevel().getBlockEntity(pos)).machineId()
            .equals(id("test_schema2_axis")),
        "Dropped controller binding did not re-place");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void corrupt_and_future_controller_data_stay_read_only_and_preserved(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    CompoundTag root = new CompoundTag(), data = new CompoundTag();
    data.putInt("schema", 1);
    data.putString("machine", "INVALID ID");
    root.put("mmcece", data);
    controller.loadWithComponents(root, helper.getLevel().registryAccess());
    helper.assertTrue(
        !controller.schemaSupported() && !controller.canCommitProduction(),
        "Corrupt saved ID became executable default");
    helper.assertTrue(
        controller.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("mmcece").equals(data),
        "Corrupt saved data was rewritten");
    data.putInt("schema", 99);
    data.putString("machine", "future:machine");
    data.putString("future_payload", "preserve this");
    controller.loadWithComponents(root, helper.getLevel().registryAccess());
    var drops = Block.getDrops(controller.getBlockState(), helper.getLevel(), pos, controller);
    cleanPlacement(helper, pos);
    var player = player(helper, pos, 1);
    player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst());
    place(helper, player, pos, 0, 90);
    var restored = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(pos);
    helper.assertTrue(
        !restored.schemaSupported() && !restored.canCommitProduction(),
        "Future shell gained production permission");
    helper.assertTrue(
        restored.saveWithoutMetadata(helper.getLevel().registryAccess()).getCompound("mmcece")
            .getString("future_payload").equals("preserve this"),
        "Future payload lost");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void tagged_external_tools_share_normal_and_sneak_actions(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 4, 7));
    controller(helper, pos, "test_schema2_axis");
    var player = player(helper, pos, 1);
    var external = new ItemStack(TestPredicateBlock.EXTERNAL_WRENCH);
    helper.assertTrue(WrenchInteractions.isWrench(external), "Test-only optional extension tag did not load");
    player.setItemInHand(InteractionHand.MAIN_HAND, external);
    helper.assertTrue(
        !WrenchInteractions.isWrench(new ItemStack(Items.STICK)),
        "Compatibility fixture changed vanilla stick behavior");
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 1);
    player.setShiftKeyDown(true);
    click(helper, player, pos, Direction.NORTH, InteractionHand.MAIN_HAND);
    checkPose(helper, pos, Direction.NORTH, 0);
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void wrench_is_craftable_in_the_minimum_runtime_and_has_no_dependency_tool_requirement(GameTestHelper helper) {
    var holder = helper.getLevel().getRecipeManager().byKey(id("machine_wrench")).orElseThrow();
    helper.assertTrue(holder.value() instanceof CraftingRecipe, "Wrench recipe missing");
    var recipe = (CraftingRecipe) holder.value();
    var cells = new ArrayList<ItemStack>(
        List.of(
            new ItemStack(Items.IRON_INGOT),
            ItemStack.EMPTY,
            new ItemStack(Items.IRON_INGOT),
            ItemStack.EMPTY,
            new ItemStack(Items.IRON_INGOT),
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            new ItemStack(Items.STICK),
            ItemStack.EMPTY));
    var input = CraftingInput.of(3, 3, cells);
    helper.assertTrue(recipe.matches(input, helper.getLevel()), "Native wrench ingredients do not match");
    var result = recipe.assemble(input, helper.getLevel().registryAccess());
    helper.assertTrue(
        result.is(MachineRegistries.WRENCH.get()) && result.getCount() == 1 && result.getMaxStackSize() == 1,
        "Wrench result wrong");
    cells.set(4, ItemStack.EMPTY);
    helper
        .assertTrue(!recipe.matches(CraftingInput.of(3, 3, cells), helper.getLevel()), "Recipe accepted missing iron");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void controller_palette_and_full_nbt_restore_binding_without_restoring_old_proof(GameTestHelper helper) {
    BlockPos pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var controller = controller(helper, pos, "test_schema2_axis");
    helper.getLevel().setBlockAndUpdate(
        pos,
        MachineControllerBlock.withOrientation(controller.getBlockState(), MachineOrientation.of(Direction.NORTH, 1)));
    helper.getLevel().setBlockAndUpdate(
        pos.above(),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
    confirm(helper, controller);
    var old = controller.registration();
    var saved = controller.saveWithFullMetadata(helper.getLevel().registryAccess());
    var palette = NbtUtils.writeBlockState(controller.getBlockState());
    helper.getLevel().removeBlockEntity(pos);
    var state = NbtUtils.readBlockState(helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), palette);
    var restored = (MachineControllerBlockEntity) BlockEntity
        .loadStatic(pos, state, saved, helper.getLevel().registryAccess());
    helper.getLevel().setBlockEntity(restored);
    restored.onLoad();
    helper.assertTrue(
        restored.machineId().equals(id("test_schema2_axis"))
            && !restored.canCommitProduction()
            && !old.canCommit(helper.getLevel()),
        "Saved controller restored an old permission");
    checkPose(helper, pos, Direction.NORTH, 1);
    confirm(helper, restored);
    helper.succeed();
  }

  private static MachineControllerBlockEntity controller(GameTestHelper helper, BlockPos pos, String machine) {
    helper.getLevel().setBlockAndUpdate(pos, MachineRegistries.CONTROLLER.get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        pos.east(),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(pos);
    controller.bindMachine(id(machine));
    return controller;
  }

  private static FakePlayer player(GameTestHelper helper, BlockPos pos, int slot) {
    var player = FakePlayerFactory
        .get(helper.getLevel(), new GameProfile(new UUID(0, 0x24040L + slot), "mmcece_t" + slot));
    player.setGameMode(GameType.SURVIVAL);
    player.setShiftKeyDown(false);
    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
    position(player, pos);
    return player;
  }

  private static void position(FakePlayer player, BlockPos pos) {
    player.setPos(pos.getX() + 0.5, pos.getY() - 1, pos.getZ() - 3);
    player.setYRot(0);
    player.setYHeadRot(0);
    player.setXRot(0);
  }

  private static InteractionResult click(GameTestHelper helper, FakePlayer player, BlockPos pos, Direction face, InteractionHand hand) {
    var hit = new BlockHitResult(
        Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5),
        face,
        pos,
        false);
    return player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(hand), hand, hit);
  }

  private static void place(GameTestHelper helper, FakePlayer player, BlockPos pos, float yaw, float pitch) {
    player.setShiftKeyDown(false);
    player.setYRot(yaw);
    player.setYHeadRot(yaw);
    player.setXRot(pitch);
    boolean ceiling = pitch < 0;
    BlockPos support = ceiling ? pos.above() : pos.below();
    helper.getLevel().setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
    Direction horizontal = Direction.fromYRot(yaw);
    if (pitch == 90) {
      player.setPos(pos.getX() + 0.5, pos.getY() + 2, pos.getZ() + 0.5);
    } else {
      if (ceiling) {
        player.setPos(pos.getX() + 0.5, pos.getY() - 3, pos.getZ() + 0.5);
      } else {
        player.setPos(
            pos.getX() + 0.5 - horizontal.getStepX() * 3,
            pos.getY(),
            pos.getZ() + 0.5 - horizontal.getStepZ() * 3);
      }
    }
    var hit = new BlockHitResult(
        new Vec3(pos.getX() + 0.5, ceiling ? pos.getY() + 1 : pos.getY(), pos.getZ() + 0.5),
        ceiling ? Direction.DOWN : Direction.UP,
        support,
        false);
    player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
  }

  private static void cleanPlacement(GameTestHelper helper, BlockPos pos) {
    for (var p : List.of(pos, pos.above(), pos.below())) {
      helper.getLevel().setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
    }
  }

  private static ItemStack bound(String machine) {
    var stack = new ItemStack(MachineRegistries.CONTROLLER.get());
    CompoundTag root = new CompoundTag(), data = new CompoundTag();
    data.putInt("schema", 1);
    data.putString("machine", id(machine).toString());
    root.put("mmcece", data);
    BlockItem.setBlockEntityData(stack, MachineRegistries.CONTROLLER_ENTITY.get(), root);
    return stack;
  }

  private static void checkPose(GameTestHelper helper, BlockPos pos, Direction front, int roll) {
    var state = helper.getLevel().getBlockState(pos);
    helper.assertTrue(
        state.getBlock() instanceof MachineControllerBlock
            && state.getValue(MachineControllerBlock.FACING) == front
            && state.getValue(MachineControllerBlock.ROLL) == roll,
        "Unexpected pose: " + state + " expected " + front + "/" + roll);
  }

  private static void confirm(GameTestHelper helper, MachineControllerBlockEntity controller) {
    for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(controller.canCommitProduction(), controller.statusMessage().getString());
  }
}
