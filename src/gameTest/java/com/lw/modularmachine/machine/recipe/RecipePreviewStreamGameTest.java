package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
}
