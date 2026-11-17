package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientArrayContractTest {
  private static final RecipeModifier SPEED = new RecipeModifier(
      RecipeModifier.Target.DURATION,
      RecipeModifier.Io.INPUT,
      RecipeModifier.Operation.MULTIPLY,
      0.5,
      false);

  private static JsonObject recipe() {
    return JsonParser.parseString("""
        {"type":"mmcece:machine","machine":"mmcece:processor","duration":20,
         "inputs":[],"outputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1}],
         "ingredient_arrays":[{"alternatives":[
           {"resource":"item","item":{"id":"minecraft:iron_ore"},"amount":5},
           {"resource":"item","item":{"id":"minecraft:raw_iron"},"amount":3}]}]}
        """).getAsJsonObject();
  }

  @Test
  void requiredAlternativesCanSupplyTheOnlyInputButOptionalCatalystsCannot() {
    var source = recipe();
    assertDoesNotThrow(() -> MachineRecipeJson.validate(source));
    source.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject().addProperty("optional", true);
    assertThrows(RuntimeException.class, () -> MachineRecipeJson.validate(source));
    source.addProperty("energy_per_tick", 1);
    assertDoesNotThrow(() -> MachineRecipeJson.validate(source));
  }

  @Test
  void mixedResourcesAndNonItemQuantityRangesAreRejected() {
    var source = recipe();
    var alternatives = source.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject()
        .getAsJsonArray("alternatives");
    alternatives
        .set(1, JsonParser.parseString("{\"resource\":\"chemical\",\"chemical\":\"mekanism:hydrogen\",\"amount\":3}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeIngredientArrayResolution.validate(source.get("ingredient_arrays")));
    alternatives.remove(0);
    alternatives.get(0).getAsJsonObject().addProperty("min_amount", 1);
    alternatives.get(0).getAsJsonObject().addProperty("max_amount", 3);
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeIngredientArrayResolution.validate(source.get("ingredient_arrays")));
  }

  @Test
  void catalystRulesApplyOnlyForASelectedAlternative() {
    var choice = new IngredientArrayData.Choice(
        "chemical",
        Optional.empty(),
        Optional.empty(),
        Optional.of(ResourceLocation.parse("mekanism:hydrogen")),
        10,
        1,
        Optional.empty(),
        List.of());
    var catalyst = new IngredientArrayData.Group(
        List.of(choice),
        1,
        false,
        "",
        true,
        List.of("Optional speed catalyst"),
        List.of(SPEED));
    var data = new IngredientArrayData(List.of(catalyst));
    assertEquals(List.of(SPEED), RecipeIngredientArrayResolution.selectedRules(data, new int[]{0}));
    assertTrue(RecipeIngredientArrayResolution.selectedRules(data, new int[]{-1}).isEmpty());
    var required = new IngredientArrayData(List.of(new IngredientArrayData.Group(List.of(choice), 1, false, "")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeIngredientArrayResolution.selectedRules(required, new int[]{-1}));
  }

  @Test
  void quantityRangesAndChanceAreModifiedFromTheirSourceOnce() {
    var source = recipe();
    var choice = source.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject().getAsJsonArray("alternatives")
        .get(0).getAsJsonObject();
    choice.addProperty("min_amount", 2);
    choice.addProperty("max_amount", 5);
    choice.addProperty("chance", 0.5);
    var normalized = MachineRecipeJson.normalize(
        source,
        List.of(
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
                true)));
    var result = normalized.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject().getAsJsonArray("alternatives")
        .get(0).getAsJsonObject();
    assertEquals(4, result.get("min_amount").getAsLong());
    assertEquals(10, result.get("max_amount").getAsLong());
    assertEquals(10, result.get("amount").getAsLong());
    assertEquals(0.25, result.get("chance").getAsDouble());
    assertEquals(5, choice.get("amount").getAsLong());
  }

  @Test
  void conditionalRulesCannotBeAttachedToRequiredGroups() {
    var source = recipe();
    source.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject().add(
        "recipe_modifiers",
        JsonParser
            .parseString("[{\"target\":\"duration\",\"io\":\"input\",\"operation\":\"multiply\",\"value\":0.5}]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecipeIngredientArrayResolution.validate(source.get("ingredient_arrays")));
  }
}
