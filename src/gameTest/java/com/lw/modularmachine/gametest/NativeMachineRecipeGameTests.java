package com.lw.modularmachine.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState.State;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.MachineItemRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipePlanner;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class NativeMachineRecipeGameTests {
  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  private static JsonObject root() {
    return JsonParser.parseString(
        "{\"type\":\"mmcece:machine_item\",\"schema_version\":2,\"machine\":\"mmcece:test_recipe_resources\",\"priority\":7,\"duration\":40,\"inputs\":[{\"stack\":{\"id\":\"minecraft:dirt\"},\"amount\":2}],\"outputs\":[{\"stack\":{\"id\":\"minecraft:iron_ingot\"},\"amount\":3}]}")
        .getAsJsonObject();
  }

  private static MachineItemRecipe decode(GameTestHelper helper, JsonObject root) {
    return (MachineItemRecipe) Recipe.CODEC
        .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), root).getOrThrow();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void registered_json_codec_roundtrips_components_long_amounts_and_defensive_previews(GameTestHelper helper) {
    var root = root();
    root.addProperty("schema_version", 2);
    root.addProperty("energy_per_tick", 128);
    root.add("fluid_per_tick", JsonParser.parseString("{\"id\":\"minecraft:water\",\"amount\":250}"));
    root.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":40}]"));
    root.add("fluid_outputs", JsonParser.parseString("[{\"id\":\"minecraft:lava\",\"amount\":20}]"));
    root.addProperty("random_seed", 123456789L);
    root.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("chance", 0.375D);
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("chance", 0.25D);
    var stack = new ItemStack(Items.IRON_INGOT);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("native component"));
    root.getAsJsonArray("outputs").get(0).getAsJsonObject().add(
        "stack",
        ItemStack.SINGLE_ITEM_CODEC
            .encodeStart(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), stack)
            .getOrThrow());
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", Long.MAX_VALUE);
    var recipe = decode(helper, root);
    var encoded = Recipe.CODEC
        .encodeStart(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), recipe)
        .getOrThrow();
    var copy = (MachineItemRecipe) Recipe.CODEC
        .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), encoded).getOrThrow();
    helper.assertTrue(
        copy.inputs().getFirst().amount() == Long.MAX_VALUE
            && copy.duration() == 40
            && copy.priority() == 7
            && copy.energyPerTick() == 128
            && copy.fluidPerTick().getFluid() == Fluids.WATER
            && copy.fluidPerTick().getAmount() == 250,
        "Native JSON lost timing/priority/long amount");
    var preview = copy.getResultItem(helper.getLevel().registryAccess());
    helper.assertTrue(
        preview.getCount() == 3 && preview.get(DataComponents.CUSTOM_NAME).getString().equals("native component"),
        "Native preview lost component data");
    ((MutableComponent) preview.get(DataComponents.CUSTOM_NAME)).append("mutated view");
    helper.assertTrue(
        copy.getResultItem(helper.getLevel().registryAccess()).get(DataComponents.CUSTOM_NAME).getString()
            .equals("native component"),
        "Preview exposes native recipe prototype");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void native_codec_rejects_unknown_items_components_fields_and_fractional_counts(GameTestHelper helper) {
    var cases = new ArrayList<JsonObject>();
    var missing = root();
    missing.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack")
        .addProperty("id", "mmcece:absent_native_item");
    cases.add(missing);
    var unknown = root();
    unknown.addProperty("ignoreOutputCheck", true);
    cases.add(unknown);
    var fraction = root();
    fraction.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", 1.5);
    cases.add(fraction);
    var count = root();
    count.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack").addProperty("count", 64);
    cases.add(count);
    var component = root();
    component.getAsJsonArray("inputs").get(0).getAsJsonObject().getAsJsonObject("stack")
        .add("components", JsonParser.parseString("{\"mmcece:absent_component\":1}"));
    cases.add(component);
    var overflow = root();
    var requirement = overflow.getAsJsonArray("inputs").get(0).getAsJsonObject();
    requirement.addProperty("amount", Long.MAX_VALUE);
    var duplicate = requirement.deepCopy();
    duplicate.addProperty("amount", 1);
    overflow.getAsJsonArray("inputs").add(duplicate);
    cases.add(overflow);
    for (var bad : cases) {
      helper.assertTrue(
          Recipe.CODEC.parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), bad)
              .error().isPresent(),
          "Invalid native definition accepted: " + bad);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void registered_recipe_stream_codec_roundtrips_long_counts_and_metadata(GameTestHelper helper) {
    var root = root();
    root.addProperty("schema_version", 2);
    root.addProperty("energy_per_tick", 128);
    root.add("fluid_per_tick", JsonParser.parseString("{\"id\":\"minecraft:water\",\"amount\":250}"));
    root.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":40}]"));
    root.add("fluid_outputs", JsonParser.parseString("[{\"id\":\"minecraft:lava\",\"amount\":20}]"));
    root.addProperty("random_seed", 123456789L);
    root.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("chance", 0.375D);
    root.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("amount", Long.MAX_VALUE);
    var recipe = decode(helper, root);
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    try {
      Recipe.STREAM_CODEC.encode(buffer, recipe);
      var copy = (MachineItemRecipe) Recipe.STREAM_CODEC.decode(buffer);
      helper.assertTrue(
          copy.inputs().getFirst().amount() == Long.MAX_VALUE
              && copy.priority() == 7
              && copy.duration() == 40
              && copy.energyPerTick() == 128
              && copy.fluidPerTick().getFluid() == Fluids.WATER
              && copy.fluidPerTick().getAmount() == 250
              && copy.fluidInputs().size() == 1
              && copy.fluidInputs().getFirst().getAmount() == 40
              && copy.fluidOutputs().size() == 1
              && copy.fluidOutputs().getFirst().getFluid() == Fluids.LAVA
              && copy.fluidOutputs().getFirst().getAmount() == 20
              && copy.outputs().getFirst().chance() == 0.375D
              && copy.inputs().getFirst().chance() == 0.25D
              && copy.randomSeed() == 123456789L
              && copy.machineId().equals(recipe.machineId())
              && buffer.readableBytes() == 0,
          "Registered wire codec changed data or left unread bytes");
      helper.succeed();
    } finally {
      buffer.release();
    }
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void native_stream_codec_rejects_outdated_wire_schemas(GameTestHelper helper) {
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    try {
      buffer.writeVarInt(7);
      try {
        MachineItemRecipe.STREAM_CODEC.decode(buffer);
        helper.fail("Outdated recipe wire schema was accepted");
      } catch (DecoderException expected) {
      }
      helper.succeed();
    } finally {
      buffer.release();
    }
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void native_wire_rejects_future_schema_unbounded_lists_and_negative_amounts(GameTestHelper helper) {
    for (int mode = 0; mode < 3; mode++) {
      var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
      try {
        buffer.writeVarInt(mode == 0 ? 9 : 1);
        if (mode != 0) {
          buffer.writeResourceLocation(id("test_recipe_resources"));
          buffer.writeVarInt(0);
          buffer.writeVarInt(20);
          buffer.writeVarInt(mode == 1 ? 257 : 1);
          if (mode == 2) {
            ItemStack.STREAM_CODEC.encode(buffer, new ItemStack(Items.DIRT));
            buffer.writeVarLong(-1);
          }
        }
        try {
          MachineItemRecipe.STREAM_CODEC.decode(buffer);
          helper.fail("Invalid native packet accepted");
        } catch (DecoderException expected) {
        }
      } finally {
        buffer.release();
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void native_machine_recipe_never_bypasses_machine_admission_via_a_crafting_grid(GameTestHelper helper) {
    var recipe = decode(helper, root());
    var input = new SingleRecipeInput(new ItemStack(Items.DIRT, 2));
    helper.assertTrue(
        !recipe.matches(input, helper.getLevel())
            && !recipe.canCraftInDimensions(3, 3)
            && recipe.assemble(input, helper.getLevel().registryAccess()).isEmpty()
            && input.getItem(0).getCount() == 2,
        "Native recipe produced or consumed through vanilla crafting API");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "native_recipe_lifecycle", timeoutTicks = 600)
  public static void real_reload_publishes_complete_catalogs_and_reentrant_recipe_replacement_revokes_old_plans(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.fail("Initial native reload failed: " + failure);
            return;
          }
          try {
            var origin = helper.absolutePos(new BlockPos(9, 5, 9));
            var controller = RecipeResourceTicketGameTests.build(helper, origin, MachineOrientation.IDENTITY);
            var input = RecipeResourceTicketGameTests.port(helper, controller, "item_in");
            var output = RecipeResourceTicketGameTests.port(helper, controller, "item_out");
            input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
            for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
              output.inventory().setStackInSlot(slot, new ItemStack(Items.DIAMOND, 63));
            }
            var catalog = MachineRecipeService.catalogFor(controller).orElseThrow();
            var plan = ItemRecipeSearch.begin(controller).orElseThrow().advance(10_000).plan().orElseThrow();
            helper.assertTrue(
                plan.recipe().id().equals(id("mmcece/test_native_b").toString()) && plan.validateForStart(),
                "Native A/B priority/output semantics failed");
            helper.assertTrue(
                MachineRecipeService.definition(server, id("mmcece/test_native_b")).orElseThrow().duration() == 20,
                "Native duration definition unavailable");
            helper.assertTrue(
                server.getRecipeManager().byKey(id("mmcece/test_native_disabled")).isEmpty(),
                "False NeoForge condition did not disable unknown optional item");

            var pose = MachineOrientation.of(Direction.EAST, 0);
            var legacyOrigin = helper.absolutePos(new BlockPos(3, 5, 3));
            helper.getLevel().setBlockAndUpdate(
                legacyOrigin,
                MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
            var inPos = legacyOrigin.offset(pose.toWorldOffset(new BlockPos(-1, 0, 1)));
            helper.getLevel().setBlockAndUpdate(
                inPos,
                MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
            helper.getLevel().setBlockAndUpdate(
                legacyOrigin.offset(pose.toWorldOffset(new BlockPos(1, 0, 1))),
                MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
            helper.getLevel().setBlockAndUpdate(
                legacyOrigin.offset(pose.toWorldOffset(new BlockPos(0, 1, 1))),
                TestPredicateBlock.INSTANCE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
            var legacy = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(legacyOrigin);
            legacy.bindMachine(id("test_recipe_reentrant"));
            for (int i = 0; i < 200 && !legacy.canCommitProduction(); i++) {
              StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
            }
            ((MachinePortBlockEntity) helper.getLevel().getBlockEntity(inPos)).inventory()
                .setStackInSlot(0, new ItemStack(Items.DIRT, 1));
            var legacyPlan = ItemRecipeSearch.begin(legacy).orElseThrow().advance(10_000).plan().orElseThrow();
            var manager = server.getRecipeManager();
            var recipes = List.copyOf(manager.getRecipes());
            var view = manager.getRecipes();
            helper.assertTrue(view == manager.getRecipes(), "Locked native recipe view is not identity-stable");
            var invoked = new AtomicBoolean();
            TestPredicateBlock.CALLBACK.set(() -> {
              invoked.set(true);
              manager.replaceRecipes(recipes);
            });
            try {
              helper.assertTrue(
                  !legacyPlan.validateForStart() && invoked.get(),
                  "Catalog changed in full-scan callback but admission stayed valid");
            } finally {
              TestPredicateBlock.CALLBACK.set(null);
            }
            helper.assertTrue(
                manager == server.getRecipeManager()
                    && view != manager.getRecipes()
                    && !plan.validateForStart()
                    && MachineRecipeService.catalogFor(controller).isEmpty(),
                "Same-manager replacement reused stale catalog authority");
            helper.assertTrue(
                input.inventory().getStackInSlot(0).getCount() == 2
                    && output.inventory().getStackInSlot(0).getCount() == 63,
                "Native lookup or validation wrote resources");
            server.reloadResources(server.getPackRepository().getSelectedIds())
                .whenComplete((again, secondFailure) -> server.execute(() -> {
                  if (secondFailure != null) {
                    helper.fail("Catalog recovery reload failed: " + secondFailure);
                    return;
                  }
                  try {
                    var restored = RecipeResourceTicketGameTests.build(helper, origin, MachineOrientation.IDENTITY);
                    RecipeResourceTicketGameTests.port(helper, restored, "item_in").inventory()
                        .setStackInSlot(0, new ItemStack(Items.DIRT, 2));
                    var next = MachineRecipeService.catalogFor(restored).orElseThrow();
                    helper.assertTrue(
                        next != catalog && !catalog.isCurrent() && !plan.validateForStart(),
                        "Old native catalog survived reload generation");
                    helper.assertTrue(
                        ItemRecipeSearch.begin(restored).orElseThrow().advance(10_000)
                            .status() == RecipePlanner.Status.SELECTED,
                        "Reload did not recover native recipe search");
                    helper.succeed();
                  } catch (RuntimeException | AssertionError error) {
                    helper.fail("Native recovery assertion: " + error);
                  }
                }));
          } catch (RuntimeException | AssertionError error) {
            TestPredicateBlock.CALLBACK.set(null);
            helper.fail("Native lifecycle assertion: " + error);
          }
        }));
  }

  @GameTest(template = "empty16", batch = "factory_run_persistence", timeoutTicks = 600)
  public static void factory_channels_keep_independent_paid_runs_across_block_entity_rebuild(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.fail("Factory recipe reload failed: " + failure);
            return;
          }
          try {
            var level = helper.getLevel();
            var origin = helper.absolutePos(new BlockPos(7, 5, 7));
            var machine = id("test_factory_parallel_paid");
            var recipe = id("mmcece/test_factory_parallel_paid");
            level.setBlockAndUpdate(
                origin,
                MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState()
                    .setValue(FactoryControllerBlock.FACING, Direction.NORTH).setValue(FactoryControllerBlock.ROLL, 0));
            var inputPos = origin.offset(-1, 0, 1);
            var outputPos = origin.offset(1, 0, 1);
            level.setBlockAndUpdate(
                inputPos,
                MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
            level.setBlockAndUpdate(
                outputPos,
                MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
            level.setBlockAndUpdate(
                origin.above().offset(0, 0, 1),
                MachineRegistries.PORTS.get("energy_input_hatch_normal").get().defaultBlockState());
            var factory = (FactoryControllerBlockEntity) level.getBlockEntity(origin);
            factory.bindMachine(machine);
            var upgrades = new FactoryUpgradeSnapshot(0, 2, 2, 1, 3, 1, 1, 1);
            factory.replaceThreads(
                List.of(
                    factoryThread("channel_a", machine, recipe, upgrades),
                    factoryThread("channel_b", machine, recipe, upgrades)));
            var input = (MachinePortBlockEntity) level.getBlockEntity(inputPos);
            input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 4));
            var energy = (MachinePortBlockEntity) level.getBlockEntity(origin.above().offset(0, 0, 1));
            energy.energy().receiveEnergy(128, false);
            for (int i = 0; i < 200 && !factory.canCommitStructure(); i++) {
              StructureService.advance(level, 512, 64, 50_000_000L);
            }
            helper.assertTrue(factory.canCommitStructure(), "Factory test structure was not confirmed");
            for (int tick = 0; tick < 3; tick++) {
              factory.serverTick();
            }
            var runA = factory.run("channel_a").orElse(null);
            var runB = factory.run("channel_b").orElse(null);
            helper.assertTrue(
                runA != null && runB != null,
                "Factory batch start missing run: A=" + factory.runSet("channel_a").runs().size() + ", B=" + factory
                    .runSet("channel_b").runs().size() + ", threads=" + factory.scheduler()
                        .channels() + ", input=" + input.inventory().getStackInSlot(0));
            helper.assertTrue(
                !runA.runId().equals(runB.runId())
                    && factory.runSet("channel_a").runs().size() == 2
                    && factory.runSet("channel_b").runs().size() == 2
                    && runA.energyPerTick() == 3
                    && runB.energyPerTick() == 3
                    && runA.duration() == 3
                    && runB.duration() == 3
                    && input.inventory().getStackInSlot(0).isEmpty(),
                "Factory channels did not atomically start parallel paid runs");

            var saved = factory.saveWithFullMetadata(level.registryAccess());
            var state = factory.getBlockState();
            level.removeBlockEntity(origin);
            var restored = (FactoryControllerBlockEntity) BlockEntity
                .loadStatic(origin, state, saved, level.registryAccess());
            level.setBlockEntity(restored);
            restored.onLoad();
            for (int i = 0; i < 200 && !restored.canCommitStructure(); i++) {
              StructureService.advance(level, 512, 64, 50_000_000L);
            }
            var restoredA = restored.run("channel_a").orElse(null);
            var restoredB = restored.run("channel_b").orElse(null);
            helper.assertTrue(
                restoredA != null
                    && restoredB != null
                    && runA != null
                    && runB != null
                    && restoredA.runId().equals(runA.runId())
                    && restoredB.runId().equals(runB.runId())
                    && restored.runSet("channel_a").runs().size() == 2
                    && restored.runSet("channel_b").runs().size() == 2
                    && restored.scheduler().channels().stream().allMatch(thread -> thread.state() == State.RUNNING),
                "Factory paid runs or channel bindings did not survive BlockEntity rebuild");
            helper.runAfterDelay(8, () -> {
              var output = (MachinePortBlockEntity) level.getBlockEntity(outputPos);
              int iron = 0;
              for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
                var stack = output.inventory().getStackInSlot(slot);
                if (stack.is(Items.IRON_INGOT)) {
                  iron += stack.getCount();
                }
              }
              helper.assertTrue(
                  iron == 4
                      && energy.energy().stored() < 128
                      && restored.run("channel_a").isEmpty()
                      && restored.run("channel_b").isEmpty(),
                  "Factory outputs were lost, duplicated, or runs were not cleared");
              helper.succeed();
            });
          } catch (RuntimeException | AssertionError error) {
            helper.runAfterDelay(1, () -> helper.fail("Factory channel persistence: " + error));
          }
        }));
  }

  private static FactoryThreadState factoryThread(String threadId, ResourceLocation machine, ResourceLocation recipe, FactoryUpgradeSnapshot upgrades) {
    return new FactoryThreadState(
        threadId,
        machine,
        recipe,
        true,
        0,
        upgrades.parallelLimit(),
        upgrades.revision(),
        State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        upgrades);
  }
}
