package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization.NativeResource;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_kubejs_adapter_resources")
@PrefixGameTestTemplate(false)
public final class RecipeAdapterNativeResourcesGameTest {
  @GameTest(templateNamespace = "mmcece_structure_nbt", template = "empty16")
  public static void addsNativeAmountsAndConditionsWithoutReplacingOtherFields(GameTestHelper helper) {
    var source = JsonParser.parseString("""
        {"mana_input":5,"mana_output":20,"compressed_air_input":{"pressure":2,"air":12},
         "essentia_input":{"test:one":4,"test:two":2},"constellations":["test:one"],
         "environment":[{"type":"dimension","dimensions":["minecraft:overworld"]}],"priority":9}
        """).getAsJsonObject();
    RecipeAdapterNativeResources.apply(
        source,
        Map.of(
            ResourceLocation.parse("mmcece:mana"),
            new NativeResource("{\"mana_input\":7}", false),
            ResourceLocation.parse("mmcece:compressed_air"),
            new NativeResource("{\"compressed_air_input\":{\"pressure\":2,\"air\":6}}", false),
            ResourceLocation.parse("mmcece:essentia"),
            new NativeResource("{\"essentia_input\":{\"test:one\":3}}", false),
            ResourceLocation.parse("mmcece:constellation"),
            new NativeResource("{\"constellations\":[\"test:two\"]}", false),
            ResourceLocation.parse("mmcece:environment"),
            new NativeResource("{\"environment\":[{\"type\":\"time\",\"begin\":0,\"end\":1200}]}", false)),
        helper.getLevel().registryAccess());
    helper.assertValueEqual(source.get("mana_input").getAsLong(), 12L, "Combined mana input");
    helper.assertValueEqual(source.get("mana_output").getAsLong(), 20L, "Original mana output");
    helper.assertValueEqual(source.getAsJsonObject("compressed_air_input").get("air").getAsLong(), 18L, "Combined air");
    helper
        .assertValueEqual(source.getAsJsonObject("essentia_input").get("test:one").getAsLong(), 7L, "Combined aspect");
    helper
        .assertValueEqual(source.getAsJsonObject("essentia_input").get("test:two").getAsLong(), 2L, "Original aspect");
    helper.assertValueEqual(2, source.getAsJsonArray("environment").size(), "Captured resource");
    helper.assertValueEqual(2, source.getAsJsonArray("constellations").size(), "Captured resource");
    helper.assertValueEqual(9, source.get("priority").getAsInt(), "Captured resource");
    helper.succeed();
  }

  @GameTest(templateNamespace = "mmcece_structure_nbt", template = "empty16")
  public static void refusesConflictingClaimsAndOnlyReplacesWhenExplicit(GameTestHelper helper) {
    var source = JsonParser.parseString("""
        {"will_input":{"type":"raw","amount":1000},"heat_input":1000,"heat_output":2000}
        """).getAsJsonObject();
    var will = ResourceLocation.parse("mmcece:will");
    var declaration = "{\"will_input\":{\"type\":\"ruina\",\"amount\":2000}}";
    boolean rejected = false;
    try {
      RecipeAdapterNativeResources
          .apply(source, Map.of(will, new NativeResource(declaration, false)), helper.getLevel().registryAccess());
    } catch (IllegalArgumentException conflict) {
      rejected = true;
    }
    helper.assertTrue(rejected, "The adapter overwrote a conflicting claim");
    RecipeAdapterNativeResources
        .apply(source, Map.of(will, new NativeResource(declaration, true)), helper.getLevel().registryAccess());
    helper
        .assertValueEqual("ruina", source.getAsJsonObject("will_input").get("type").getAsString(), "Captured resource");
    helper.assertValueEqual(source.get("heat_output").getAsLong(), 2000L, "Unrelated heat output");
    helper.succeed();
  }
}
