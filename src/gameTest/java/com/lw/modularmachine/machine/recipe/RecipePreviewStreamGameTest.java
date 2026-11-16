package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceExecutionRegistry;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.CompiledRecipeSet;
import com.lw.modularmachine.machine.PortKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.ListTag;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Isolated regression for optional preview stacks in the registered recipe wire
 * codec.
 */
@GameTestHolder("mmcece_recipe_codec")
@PrefixGameTestTemplate(false)
public final class RecipePreviewStreamGameTest {
  private RecipePreviewStreamGameTest() {
  }

  private static JsonObject root() {
    return JsonParser.parseString("""
        {
          "type": "mmcece:machine",
          "machine": "mmcece:codec_test",
          "duration": 40,
          "failure_action": "still",
          "parallelized": true,
          "energy_output_per_tick": 16,
          "inputs": [
            {
              "stack": {"id": "minecraft:dirt"},
              "amount": 3000000000,
              "chance": 0.25,
              "selector": "input",
              "parallel_unaffected": true
            },
            {"stack": {"id": "minecraft:stone"}, "amount": 2},
            {"stack": {"id": "minecraft:gold_ingot"}, "amount": 4}
          ],
          "outputs": [
            {"stack": {"id": "minecraft:iron_ingot"}, "amount": 3},
            {"stack": {"id": "minecraft:redstone"}, "amount": 5}
          ]
        }
        """).getAsJsonObject();
  }

  private static MachineRecipe roundTrip(GameTestHelper helper, JsonObject source) {
    var registries = helper.getLevel().registryAccess();
    var recipe = MachineRecipe.CODEC.codec().parse(registries.createSerializationContext(JsonOps.INSTANCE), source)
        .getOrThrow();
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    try {
      Recipe.STREAM_CODEC.encode(buffer, recipe);
      buffer.writeInt(0x13579BDF);
      var copy = (MachineRecipe) Recipe.STREAM_CODEC.decode(buffer);
      helper.assertTrue(recipe.sameDefinition(copy), "Registered recipe codec changed the complete declaration");
      helper.assertTrue(buffer.readInt() == 0x13579BDF, "Recipe codec consumed or misaligned the following data");
      helper.assertTrue(buffer.readableBytes() == 0, "Recipe codec left unread bytes");

      return copy;
    } finally {
      buffer.release();
    }
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void absent_preview_roundtrips_without_filling_the_optional_value(GameTestHelper helper) {
    var copy = roundTrip(helper, root());
    var input = copy.inputs().getFirst();
    helper.assertTrue(
        input.ingredientStack().is(Items.DIRT)
            && input.displayStack().is(Items.DIRT)
            && input.amount() == 3_000_000_000L
            && input.chance() == 0.25D
            && input.selector().equals("input")
            && !input.ignoresComponents()
            && input.parallelUnaffected(),
        "Absent preview lost the real input, metadata or display fallback");
    helper.assertTrue(
        copy.outputs().getFirst().displayStack().is(Items.IRON_INGOT),
        "Absent output preview no longer falls back to the output prototype");
    var json = MachineRecipe.CODEC.codec()
        .encodeStart(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), copy).getOrThrow()
        .getAsJsonObject();
    for (var row : json.getAsJsonArray("inputs")) {
      helper.assertTrue(!row.getAsJsonObject().has("preview_stack"), "Absent input preview became an explicit stack");
    }
    for (var row : json.getAsJsonArray("outputs")) {
      helper.assertTrue(!row.getAsJsonObject().has("preview_stack"), "Absent output preview became an explicit stack");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void mixed_preview_stacks_preserve_components_and_execution_prototypes(GameTestHelper helper) {
    var source = root();
    var preview = new ItemStack(Items.DIAMOND);
    preview.set(DataComponents.CUSTOM_NAME, Component.literal("display only"));
    var encoded = ItemStack.SINGLE_ITEM_CODEC
        .encodeStart(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), preview)
        .getOrThrow();
    source.getAsJsonArray("inputs").get(0).getAsJsonObject().add("preview_stack", encoded);
    source.getAsJsonArray("inputs").get(2).getAsJsonObject().add("preview_stack", encoded.deepCopy());
    source.getAsJsonArray("outputs").get(1).getAsJsonObject().add("preview_stack", encoded.deepCopy());
    var copy = roundTrip(helper, source);
    helper.assertTrue(
        copy.inputs().get(0).ingredientStack().is(Items.DIRT)
            && copy.inputs().get(2).ingredientStack().is(Items.GOLD_INGOT)
            && copy.outputs().get(1).ingredientStack().is(Items.REDSTONE),
        "Display preview replaced an execution prototype");
    helper.assertTrue(
        ItemStack.matches(preview, copy.inputs().get(0).displayStack())
            && ItemStack.matches(preview, copy.inputs().get(2).displayStack())
            && ItemStack.matches(preview, copy.outputs().get(1).displayStack()),
        "Explicit preview lost its item or custom name component");
    helper.assertTrue(
        copy.inputs().get(1).displayStack().is(Items.STONE)
            && copy.outputs().get(0).displayStack().is(Items.IRON_INGOT),
        "Empty previews between or before explicit previews changed alignment or fallback");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void mixed_resource_native_data_and_freeze_preserve_quantities(GameTestHelper helper) {
    var source = root();
    source.addProperty("energy_per_tick", 3_000_000_000L);
    source.add("fluid_per_tick", JsonParser.parseString("""
        {"id": "minecraft:water", "amount": 2}
        """));
    source.add("fluid_inputs", JsonParser.parseString("""
        [{"id": "minecraft:water", "amount": 100, "chance": 0.25, "parallel_unaffected": true}]
        """));
    source.add("fluid_outputs", JsonParser.parseString("""
        [{"id": "minecraft:lava", "amount": 10, "chance": 0.75}]
        """));
    source.add("chemicals", JsonParser.parseString("""
        {
          "inputs": [{"chemical": "mekanism:steam", "amount": 3000000000, "selector": "chemical"}],
          "outputs": [{"chemical": "mekanism:oxygen", "amount": 8}],
          "per_tick": [{"chemical": "mekanism:steam", "amount": 4}]
        }
        """));
    var copy = roundTrip(helper, source);
    var registries = helper.getLevel().registryAccess();
    var nbtOps = registries.createSerializationContext(NbtOps.INSTANCE);
    var saved = (CompoundTag) MachineRecipe.CODEC.codec().encodeStart(nbtOps, copy).getOrThrow();
    var restored = MachineRecipe.CODEC.codec().parse(nbtOps, saved).getOrThrow();
    helper.assertTrue(
        copy.sameDefinition(restored) && copy.resourceData().equals(restored.resourceData()),
        "Native NBT changed typed resource data");
    helper.assertTrue(
        restored.energyPerTick() == 3_000_000_000L
            && restored.fluidInputRequirements().getFirst().parallelUnaffected()
            && restored.fluidOutputRequirements().getFirst().chance() == 0.75D
            && restored.chemicals().inputs().getFirst().amount() == 3_000_000_000L,
        "Native resource quantities, probabilities or fixed-parallel flags changed");
    var frozen = saved.copy();
    RecipeResourceExecutionRegistry.freezeDefinition(frozen, 3, registries);
    var frozenData = RecipeResourceDefinitions.CODEC.codec().parse(nbtOps, frozen).getOrThrow();
    var chemical = frozenData.get(RecipeResourceDefinitions.CHEMICAL);
    helper.assertTrue(
        chemical.inputs().getFirst().amount() == 9_000_000_000L
            && chemical.outputs().getFirst().amount() == 24
            && chemical.perTick().getFirst().amount() == 12,
        "Resource freeze did not apply the run multiplier exactly once");
    helper.assertTrue(
        copy.chemicals().outputs().getFirst().amount() == 8,
        "Freezing a run mutated the source definition");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void published_catalogs_reuse_compilation_with_distinct_authority(GameTestHelper helper) {
    var recipe = roundTrip(helper, root());
    var id = ResourceLocation.fromNamespaceAndPath("mmcece", "catalog_publication");
    var prepared = new RecipeCatalog(
        recipe.machineId(),
        new CompiledRecipeSet<>(List.of(recipe.compile(id, helper.getLevel().registryAccess()))),
        Map.of(),
        Map.of(),
        () -> false,
        null,
        Map.of(id.toString(), recipe),
        List.of());
    // Bind each view to its own publication, never to a reusable config value.
    var generation = new AtomicReference<Object>(new Object());
    var firstGeneration = generation.get();
    var first = prepared.publish(() -> generation.get() == firstGeneration);
    helper.assertTrue(first.isCurrent() && !prepared.isCurrent(), "Prepared data gained live authority");
    generation.set(new Object());
    var secondGeneration = generation.get();
    var second = prepared.publish(() -> generation.get() == secondGeneration);
    helper.assertTrue(!first.isCurrent() && second.isCurrent(), "Old publication retained authority");
    helper.assertTrue(
        first != second && first.root() == first && second.root() == second,
        "Published catalog identity was reused");
    helper.assertTrue(
        first.recipes() == second.recipes() && first.definition(id.toString()) == recipe,
        "Publication rebuilt or changed prepared recipe data");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void saved_run_validates_native_resource_values_when_registries_are_available(GameTestHelper helper) {
    var source = root();
    source.remove("energy_output_per_tick");
    source.add("fluid_inputs", JsonParser.parseString("""
        [{"id": "minecraft:water", "amount": 10}]
        """));
    var registries = helper.getLevel().registryAccess();
    var recipe = roundTrip(helper, source);
    var saved = new CompoundTag();
    saved.putInt("schema", 3);
    saved.putUUID("run_id", UUID.randomUUID());
    saved.putString("machine", recipe.machineId().toString());
    saved.putString("recipe", "mmcece:saved_native_resource");
    saved.putInt("duration", recipe.duration());
    saved.putInt("progress", 0);
    saved.putString("state", "RUNNING");
    saved.putBoolean("inputs_committed", true);
    saved.putLong("definition_epoch", 0);
    saved.putLong("last_progress_tick", -1);
    saved.putLong("random_seed", 0);
    var definition = (CompoundTag) MachineRecipe.CODEC.codec()
        .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), recipe).getOrThrow();
    definition.putString("type", MachineRecipeJson.TYPE);
    saved.put("definition", definition);
    var ports = new ListTag();
    for (var kind : List.of(PortKind.ITEM_INPUT, PortKind.ITEM_OUTPUT, PortKind.FLUID_INPUT)) {
      var port = new CompoundTag();
      port.putString("id", kind.name().toLowerCase(java.util.Locale.ROOT));
      port.putString("kind", kind.name());
      ports.add(port);
    }
    saved.put("ports", ports);
    var restored = FrozenRecipeRun.read(saved, registries);
    helper.assertTrue(restored.resourceData(registries).equals(recipe.resourceData()), "Saved resource data changed");
    definition.getList("fluid_inputs", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
        .putString("id", "mmcece:unregistered_fluid");
    boolean rejected = false;
    try {
      FrozenRecipeRun.read(saved, registries);
    } catch (IllegalArgumentException invalid) {
      rejected = true;
    } catch (IllegalStateException invalid) {
      rejected = true;
    }
    helper.assertTrue(rejected, "Saved run deferred invalid native resource data until production");
    helper
        .assertTrue(restored.resourceData(registries).equals(recipe.resourceData()), "Bad source mutated restored run");
    helper.succeed();
  }
}
