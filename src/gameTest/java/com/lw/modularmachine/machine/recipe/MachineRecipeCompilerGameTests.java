package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.mojang.serialization.JsonOps;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class MachineRecipeCompilerGameTests {
  @GameTest(template = "empty16")
  public static void energy_modifier_can_supply_the_only_output(GameTestHelper helper) {
    var json = JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:test","duration":20,"inputs":[],"outputs":[],"laser_input":100,
         "modifiers":[{"target":"energy","io":"output","operation":"add","value":10}]}
        """).getAsJsonObject();
    var ops = helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
    var codec = MachineRecipe.CODEC.codec();
    helper.assertValueEqual(codec.parse(ops, json).getOrThrow().energyOutputPerTick(), 10L, "FE/t with laser input");

    json.remove("laser_input");
    json.add("inputs", JsonParser.parseString("""
        [{"stack":{"id":"minecraft:coal"},"amount":1}]
        """));
    helper.assertValueEqual(codec.parse(ops, json).getOrThrow().energyOutputPerTick(), 10L, "FE/t with item input");

    json.getAsJsonArray("modifiers").get(0).getAsJsonObject().addProperty("value", 0);
    helper.assertTrue(codec.parse(ops, json).error().isPresent(), "A recipe still needs an effective output");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void zero_quantity_bounds_and_catalyst_flags_survive_native_serialization(GameTestHelper helper) {
    var json = JsonParser
        .parseString(
            """
                {"type":"mmcece:machine","machine":"mmcece:test","duration":20,"inputs":[],"energy_per_tick":5,
                 "outputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":3,"min_amount":0,"max_amount":3}],
                 "ingredient_arrays":[{"optional":true,"parallel_unaffected":true,
                   "recipe_modifiers":[{"target":"item","io":"input","operation":"add","value":0.1,"affect_chance":true}],
                   "alternatives":[{"resource":"item","item":{"id":"minecraft:coal"},"amount":2,"min_amount":0,"max_amount":2}]}]}
                """)
        .getAsJsonObject();
    var registries = helper.getLevel().registryAccess();
    var declaration = MachineRecipeJson.decode(json, registries);
    var recipe = MachineRecipeCompiler.compile(declaration);
    var exported = MachineRecipeJson.encode(declaration, registries);
    var restored = MachineRecipeCompiler.compile(MachineRecipeJson.decode(exported, registries));
    helper.assertTrue(recipe.sameDefinition(restored), "Data-pack export changed quantity bounds or catalyst flags");
    var ops = registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
    var encoded = MachineRecipe.CODEC.codec().encodeStart(ops, recipe).getOrThrow();
    helper.assertTrue(
        recipe.sameDefinition(MachineRecipe.CODEC.codec().parse(ops, encoded).getOrThrow()),
        "Native NBT changed quantity bounds or catalyst flags");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void chemical_modifiers_preserve_exact_original_amounts(GameTestHelper helper) {
    var json = JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:test","duration":20,"inputs":[],"outputs":[],
         "chemicals":{"inputs":[{"chemical":"mekanism:hydrogen","amount":9007199254740993,"selector":"feed"}],
          "outputs":[{"chemical":"mekanism:oxygen","amount":9007199254740995,"selector":"product"}],
          "per_tick":[{"chemical":"mekanism:hydrogen","amount":2147483648,"selector":"feed"}]},
         "modifiers":[{"target":"chemical","io":"input","operation":"add","value":1}]}
        """).getAsJsonObject();
    var source = MachineRecipeJson.decode(json, helper.getLevel().registryAccess());
    var chemical = MachineRecipeCompiler.compile(source).chemicals();
    helper.assertTrue(chemical.inputs().getFirst().amount() == 9007199254740994L, "Input modifier lost precision");
    helper.assertTrue(chemical.perTick().getFirst().amount() == 2147483649L, "Per-tick input lost precision");
    helper.assertTrue(chemical.outputs().getFirst().amount() == 9007199254740995L, "Output was modified");
    helper.assertTrue(
        source.resources().get(RecipeResourceDefinitions.CHEMICAL).inputs().getFirst().amount() == 9007199254740993L,
        "Compiler modified the original declaration");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void ingredient_ranges_and_probabilities_start_from_original_values(GameTestHelper helper) {
    var json = JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:processor","duration":20,"inputs":[],
         "outputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1}],
         "ingredient_arrays":[{"alternatives":[{"resource":"item","item":{"id":"minecraft:iron_ore"},"amount":5,
         "min_amount":2,"max_amount":5,"chance":0.5}]}]}
        """).getAsJsonObject();
    var source = MachineRecipeJson.decode(json, helper.getLevel().registryAccess());
    var modifiers = List.of(
        new RecipeModifier(
            RecipeModifier.Target.ITEM,
            RecipeModifier.Io.INPUT,
            RecipeModifier.Operation.MULTIPLY,
            2,
            false),
        new RecipeModifier(
            RecipeModifier.Target.ITEM,
            RecipeModifier.Io.INPUT,
            RecipeModifier.Operation.MULTIPLY,
            0.5,
            true));
    var result = MachineRecipeCompiler.compile(source, modifiers, false, MachineRecipeCompiler.currentDefaults())
        .ingredientArrays().inputs().getFirst().alternatives().getFirst();
    helper.assertTrue(
        result.minAmount() == 4 && result.maxAmount() == 10 && result.amount() == 10,
        "Invalid quantity range");
    helper.assertTrue(result.chance() == 0.25, "Invalid alternative probability");
    helper.assertTrue(
        source.ingredientArrays().inputs().getFirst().alternatives().getFirst().amount() == 5,
        "Original choice was modified");
    helper.succeed();
  }
}
