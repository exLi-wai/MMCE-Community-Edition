package com.lw.modularmachine.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.structure.CheckResult;
import com.lw.modularmachine.machine.structure.CompiledStructure;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class OrientationDefinitionGameTests {
  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void default_transformer_allows_all_24_controller_poses(GameTestHelper helper) {
    var definition = StructureService.definition(ResourceLocation.fromNamespaceAndPath("mmcece", "transformer"));
    helper.assertTrue(
        definition != null && definition.allowedOrientationMask() == MachineOrientation.ALL_MASK,
        "Default transformer definition must allow all 24 poses");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "orientation_reload", timeoutTicks = 400)
  public static void real_server_hot_reload_publishes_a_complete_schema_two_generation(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mmcece", "test_schema2_axis");
    long epoch = StructureService.definitionEpoch();
    var previous = StructureService.definition(id);
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.fail("Real reload failed: " + failure);
            return;
          }
          var current = StructureService.definition(id);
          helper.assertTrue(
              current != null
                  && current != previous
                  && current.allowedOrientationMask() == 0xFFFFFF
                  && StructureService.definitionEpoch() > epoch,
              "Hot reload did not replace the complete schema two generation");
          helper.succeed();
        }));
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void registry_capture_rejects_background_calls_before_touching_live_registries(GameTestHelper helper) {
    helper.assertTrue(ServerLifecycleHooks.getCurrentServer() != null, "Missing lifecycle server in thread-guard test");
    JsonObject root = oneBlock("minecraft:oak_log", "{\"axis\":\"x\"}", "local");
    CompletableFuture.runAsync(() -> parse(root))
        .whenComplete((ignored, failure) -> helper.getLevel().getServer().execute(() -> {
          helper.assertTrue(
              failure != null
                  && failure.getCause() instanceof IllegalStateException
                  && failure.getCause().getMessage().contains("snapshot capture outside server thread"),
              "Background registry capture was not guarded");
          helper.succeed();
        }));
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void six_direction_axis_and_scalar_constraints_compile_for_every_orientation(GameTestHelper helper) {
    var facing = parse(oneBlock("minecraft:observer", "{\"facing\":\"north\",\"powered\":true}", "local"));
    var axis = parse(oneBlock("minecraft:oak_log", "{\"axis\":\"x\"}", "local"));
    helper.assertTrue(
        facing.allowedOrientationMask() == 0xFFFFFF && axis.allowedOrientationMask() == 0xFFFFFF,
        "Lost a representable orientation");
    for (var orientation : MachineOrientation.all()) {
      var rule = facing.rulesFor(orientation).getFirst();
      var observer = Blocks.OBSERVER.defaultBlockState().setValue(BlockStateProperties.FACING, orientation.front())
          .setValue(BlockStateProperties.POWERED, true);
      helper.assertTrue(rule.matches(observer), "Facing/scalar mismatch for " + orientation);
      helper.assertTrue(
          !rule.matches(observer.setValue(BlockStateProperties.POWERED, false)),
          "Scalar constraint was lost");
      helper.assertTrue(
          !rule.matches(observer.setValue(BlockStateProperties.FACING, orientation.back())),
          "Wrong facing accepted");
      var log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, orientation.right().getAxis());
      helper.assertTrue(axis.rulesFor(orientation).getFirst().matches(log), "Axis mismatch for " + orientation);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void horizontal_constraints_filter_only_the_unrepresentable_or_branch(GameTestHelper helper) {
    JsonObject root = oneBlock("minecraft:furnace", "{\"facing\":\"north\"}", "local");
    var limited = parse(root);
    helper
        .assertTrue(limited.allowedOrientationMask() == 0xFFFF, "Expected 16 horizontal-front poses, including rolls");
    helper.assertTrue(!limited.allows(MachineOrientation.of(Direction.UP, 0)), "Vertical furnace facing accepted");
    try {
      limited.rulesFor(MachineOrientation.of(Direction.UP, 0));
      helper.fail("Forbidden pose compiled");
    } catch (IllegalArgumentException expected) {
      helper.assertTrue(expected.getMessage().contains("ORIENTATION_NOT_ALLOWED"), "Wrong exclusion cause");
    }
    JsonObject part = root.getAsJsonArray("parts").get(0).getAsJsonObject();
    part.remove("block");
    part.add("block", JsonParser.parseString("[\"minecraft:furnace\",\"minecraft:dispenser\"]"));
    var alternatives = parse(root);
    helper.assertTrue(alternatives.allowedOrientationMask() == 0xFFFFFF, "Valid dispenser branch was discarded");
    var upRule = alternatives.rulesFor(MachineOrientation.of(Direction.UP, 0)).getFirst();
    helper.assertTrue(
        upRule.alternatives().size() == 1 && upRule.alternatives().getFirst().block() == Blocks.DISPENSER,
        "Invalid branch not isolated");
    helper.assertTrue(
        upRule.matches(Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)),
        "Remaining branch did not match");
    helper.assertTrue(!upRule.matches(Blocks.FURNACE.defaultBlockState()), "Excluded branch became a wildcard");
    helper.assertTrue(
        alternatives.rulesFor(MachineOrientation.IDENTITY).getFirst().alternatives().size() == 2,
        "Legal horizontal branch missing");
    helper.assertTrue(
        alternatives.orientationCompilation().diagnostics().size() == 8,
        "Expected one diagnostic per excluded furnace pose");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void world_properties_remain_absolute_and_local_checks_are_precompiled(GameTestHelper helper) {
    var local = parse(oneBlock("minecraft:oak_log", "{\"axis\":\"x\"}", "local"));
    var world = parse(oneBlock("minecraft:oak_log", "{\"axis\":\"x\"}", "world"));
    BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
    var localPattern = new CompiledStructure(local, origin, Direction.EAST);
    var worldPattern = new CompiledStructure(world, origin, Direction.EAST);
    BlockPos position = origin.south();
    helper.assertTrue(
        localPattern.position(0).equals(position),
        "Local +X must be world south for east-facing roll zero");
    helper.getLevel().setBlockAndUpdate(
        position,
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
    helper.assertTrue(
        localPattern.check(helper.getLevel(), 0) == CheckResult.MATCH
            && worldPattern.check(helper.getLevel(), 0) == CheckResult.MISMATCH,
        "World constraint was incorrectly inverse-rotated");
    helper.getLevel().setBlockAndUpdate(
        position,
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
    helper.assertTrue(
        localPattern.check(helper.getLevel(), 0) == CheckResult.MISMATCH
            && worldPattern.check(helper.getLevel(), 0) == CheckResult.MATCH,
        "Precompiled local/world predicates disagree");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void arbitrary_block_properties_match_declared_states(GameTestHelper helper) {
    JsonObject root = oneBlock(
        "minecraft:oak_stairs",
        "{\"facing\":\"north\",\"half\":\"bottom\",\"shape\":\"straight\"}",
        "local");
    var local = parse(root);
    helper.assertTrue(local.allows(MachineOrientation.IDENTITY), "Declared block state was rejected");
    BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
    var pattern = new CompiledStructure(local, origin, MachineOrientation.IDENTITY);
    helper.getLevel().setBlockAndUpdate(
        pattern.position(0),
        Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(BlockStateProperties.HALF, Half.BOTTOM)
            .setValue(BlockStateProperties.STAIRS_SHAPE, StairsShape.STRAIGHT)
            .setValue(BlockStateProperties.WATERLOGGED, false));
    helper.assertTrue(pattern.check(helper.getLevel(), 0) == CheckResult.MATCH, "Declared block state did not match");
    helper.assertTrue(
        parse(oneBlock("minecraft:oak_stairs", "{}", "local")).allowedOrientationMask() == 0xFFFFFF,
        "Block-ID-only rule gained hidden state requirements");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void liquid_source_and_flowing_block_states_match_by_block_id(GameTestHelper helper) {
    var source = parse(oneBlock("minecraft:water", "{}", "world"));
    BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
    var sourcePattern = new CompiledStructure(source, origin, MachineOrientation.IDENTITY);
    helper.getLevel().setBlockAndUpdate(sourcePattern.position(0), Blocks.WATER.defaultBlockState());
    helper.assertTrue(sourcePattern.check(helper.getLevel(), 0) == CheckResult.MATCH, "Water source did not match");
    helper.getLevel()
        .setBlockAndUpdate(sourcePattern.position(0), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
    helper.assertTrue(
        sourcePattern.check(helper.getLevel(), 0) == CheckResult.MATCH,
        "Flowing water did not match the block-only rule");

    JsonObject anyRoot = oneBlock("minecraft:lava", "{}", "world");
    var anyLava = new CompiledStructure(parse(anyRoot), origin.offset(1, 0, 0), MachineOrientation.IDENTITY);
    helper.getLevel().setBlockAndUpdate(anyLava.position(0), Blocks.LAVA.defaultBlockState());
    helper.assertTrue(anyLava.check(helper.getLevel(), 0) == CheckResult.MATCH, "Lava source did not match");
    helper.getLevel()
        .setBlockAndUpdate(anyLava.position(0), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 7));
    helper.assertTrue(
        anyLava.check(helper.getLevel(), 0) == CheckResult.MATCH,
        "Flowing lava did not match block-only rule");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void schema_two_reload_and_existing_controller_path_use_compiled_properties(GameTestHelper helper) {
    ResourceLocation id = ResourceLocation.fromNamespaceAndPath("mmcece", "test_schema2_axis");
    var definition = StructureService.definition(id);
    helper.assertTrue(
        definition != null && definition.allowedOrientationMask() == 0xFFFFFF,
        "Schema two resource did not pass the real reload pipeline");
    BlockPos origin = helper.absolutePos(new BlockPos(4, 3, 4));
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineRegistries.CONTROLLER.get().defaultBlockState().setValue(MachineControllerBlock.FACING, Direction.EAST));
    helper.getLevel().setBlockAndUpdate(
        origin.south(),
        Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    controller.bindMachine(id);
    helper.runAfterDelay(8, () -> {
      helper.assertTrue(controller.canCommitProduction(), "Schema two horizontal controller did not confirm");
      helper.getLevel().setBlockAndUpdate(
          origin.south(),
          Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
      helper.assertTrue(
          controller.verificationState() == VerificationState.PENDING,
          "Changed schema two property retained proof");
      helper.runAfterDelay(6, () -> {
        helper.assertTrue(
            controller.verificationFailure() == VerificationFailure.MISMATCH,
            "Wrong schema two axis not rejected");
        helper.succeed();
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void registry_snapshot_skips_unknown_blocks_properties_and_values(GameTestHelper helper) {
    // Unknown/unusable blocks are skipped instead of failing the whole reload;
    // the definition still loads and only the affected part disappears.
    expectFailure(helper, "Invalid fixed definition", () -> parse(oneBlock("mmcece:no_such_block", "{}", "local")));
    expectFailure(
        helper,
        "Invalid fixed definition",
        () -> parse(oneBlock("minecraft:oak_log", "{\"not_a_property\":\"x\"}", "local")));
    expectFailure(
        helper,
        "Invalid fixed definition",
        () -> parse(oneBlock("minecraft:oak_log", "{\"axis\":\"w\"}", "world")));
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void orientation_compilation_has_a_work_bound(GameTestHelper helper) {
    JsonObject root = repeatedParts(200, false);
    System.setProperty("mmcece.orientationWorkBudget", "1000");
    try {
      expectJson(helper, "work units", () -> parse(root));
    } finally {
      System.clearProperty("mmcece.orientationWorkBudget");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 40)
  public static void exclusion_diagnostics_are_bounded_without_changing_the_allowed_mask(GameTestHelper helper) {
    var definition = parse(repeatedParts(40, true));
    helper.assertTrue(definition.allowedOrientationMask() == 0xFFFF, "Diagnostic truncation changed mask");
    helper.assertTrue(
        definition.orientationCompilation().diagnostics().size() == 256
            && definition.orientationCompilation().diagnosticsTruncated(),
        "Diagnostics are not bounded and marked");
    helper.succeed();
  }

  private static JsonObject repeatedParts(int count, boolean furnace) {
    JsonObject root = oneBlock(
        furnace ? "minecraft:furnace" : "minecraft:stone_bricks",
        furnace ? "{\"facing\":\"north\"}" : "{}",
        "local");
    JsonObject prototype = root.getAsJsonArray("parts").get(0).getAsJsonObject();
    if (!furnace) {
      prototype.remove("block");
      JsonArray alternatives = new JsonArray();
      for (int i = 0; i < 256; i++) {
        alternatives.add("minecraft:stone_bricks");
      }
      prototype.add("block", alternatives);
    }
    JsonArray parts = new JsonArray();
    for (int i = 0; i < count; i++) {
      JsonObject part = prototype.deepCopy();
      part.add("offset", JsonParser.parseString("[" + (i - count / 2) + ",1,1]"));
      parts.add(part);
    }
    root.add("parts", parts);
    return root;
  }

  private static JsonObject oneBlock(String block, String properties, String space) {
    return JsonParser.parseString(
        "{\"orientation\":{\"policy\":\"all_24\"},\"parts\":[{\"offset\":[1,0,0],\"block\":\"" + block + "\",\"properties\":" + properties + ",\"properties_space\":\"" + space + "\"}]}")
        .getAsJsonObject();
  }

  private static MachineDefinition parse(JsonObject root) {
    return MachineDefinition.parse(ResourceLocation.fromNamespaceAndPath("mmcece", "orientation_contract"), root);
  }

  private static void expectFailure(GameTestHelper helper, String detail, Runnable call) {
    try {
      call.run();
      helper.fail("Invalid definition was accepted: " + detail);
    } catch (RuntimeException expected) {
      helper.assertTrue(
          expected.getMessage() != null && expected.getMessage().contains(detail),
          "Wrong diagnostic: " + expected.getMessage());
    }
  }

  private static void expectJson(GameTestHelper helper, String detail, Runnable call) {
    try {
      call.run();
      helper.fail("Invalid definition was accepted: " + detail);
    } catch (JsonParseException expected) {
      helper.assertTrue(expected.getMessage().contains(detail), "Wrong diagnostic: " + expected.getMessage());
    }
  }
}
