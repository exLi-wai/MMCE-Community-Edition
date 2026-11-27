package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization.NativeResource;
import com.lw.modularmachine.api.recipe.resource.RecipeResources;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceBuilderDeclarations;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_kubejs_adapter_resources")
@PrefixGameTestTemplate(false)
public final class RecipeAdapterNativeResourcesGameTest {
  private static RecipeResources.Entry<?> entry(GameTestHelper helper, String id, String fields) {
    return RecipeResourceBuilderDeclarations.decode(
        ResourceLocation.parse("mmcece:" + id),
        JsonParser.parseString(fields).getAsJsonObject(),
        helper.getLevel().registryAccess());
  }

  @GameTest(template = "empty16")
  public static void stylingOneMachineDisplayKeepsSourceAndSiblingProductsUnchanged(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var named = new ItemStack(Items.DIAMOND);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("Original"));
    var source = MachineRecipeBuilder
        .create(ResourceLocation.parse("test:recipe"), ResourceLocation.parse("test:source"), 20).energyPerTick(1)
        .itemOutput(named, 1).build(registries);
    var settings = new RecipeAdapterImport("copy", source.machineId(), List.of(), true, 0, 0, 0);
    var additions = new RecipeAdapterCustomizations(settings.customization());
    var first = MachineRecipeCompiler
        .compile(MachineRecipeCopies.copy(source, ResourceLocation.parse("test:first"), settings, additions));
    var second = MachineRecipeCompiler
        .compile(MachineRecipeCopies.copy(source, ResourceLocation.parse("test:second"), settings, additions));
    var name = (MutableComponent) first.outputs().getFirst().displayStack().get(DataComponents.CUSTOM_NAME);
    name.setStyle(name.getStyle().withItalic(true));
    for (var recipe : List.of(first, second, MachineRecipeCompiler.compile(source))) {
      var output = recipe.outputs().getFirst();
      helper.assertFalse(
          output.prototype(registries).getHoverName().getStyle().isItalic(),
          "Actual output for " + recipe.machineId());
      helper.assertFalse(
          output.ingredientStack().getHoverName().getStyle().isItalic(),
          "Ingredient view for " + recipe.machineId());
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void addsNativeAmountsAndConditionsWithoutReplacingOtherFields(GameTestHelper helper) {
    var source = new RecipeResources(
        List.of(
            entry(helper, "mana", "{\"mana_input\":5,\"mana_output\":20}"),
            entry(helper, "compressed_air", "{\"compressed_air_input\":{\"pressure\":2,\"air\":12}}"),
            entry(helper, "essentia", "{\"essentia_input\":{\"test:one\":4,\"test:two\":2}}"),
            entry(helper, "constellation", "{\"constellations\":[\"test:one\"]}"),
            entry(
                helper,
                "environment",
                "{\"environment\":[{\"type\":\"dimension\",\"dimensions\":[\"minecraft:overworld\"]}]}")));
    var additions = Map.of(
        "mana",
        "{\"mana_input\":7}",
        "compressed_air",
        "{\"compressed_air_input\":{\"pressure\":2,\"air\":6}}",
        "essentia",
        "{\"essentia_input\":{\"test:one\":3}}",
        "constellation",
        "{\"constellations\":[\"test:two\"]}",
        "environment",
        "{\"environment\":[{\"type\":\"time\",\"begin\":0,\"end\":1200}]}");
    var entries = new ArrayList<RecipeResources.Entry<?>>();
    additions.forEach(
        (id, fields) -> entries
            .add(RecipeAdapterNativeResources.apply(source, new NativeResource(entry(helper, id, fields), false))));
    var merged = new RecipeResources(entries);
    helper.assertValueEqual(merged.get(RecipeResourceDefinitions.MANA).input(), 12L, "Combined mana input");
    helper.assertValueEqual(merged.get(RecipeResourceDefinitions.MANA).output(), 20L, "Original mana output");
    helper.assertValueEqual(
        merged.get(RecipeResourceDefinitions.COMPRESSED_AIR).input().orElseThrow().air(),
        18L,
        "Combined air");
    var aspects = merged.get(RecipeResourceDefinitions.ESSENTIA).input();
    helper.assertValueEqual(aspects.get(ResourceLocation.parse("test:one")), 7L, "Combined aspect");
    helper.assertValueEqual(aspects.get(ResourceLocation.parse("test:two")), 2L, "Original aspect");
    helper.assertValueEqual(merged.get(RecipeResourceDefinitions.ENVIRONMENT).requirements().size(), 2, "Conditions");
    helper.assertValueEqual(merged.get(RecipeResourceDefinitions.CONSTELLATION).active().size(), 2, "Constellations");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void refusesConflictingClaimsAndOnlyReplacesWhenExplicit(GameTestHelper helper) {
    var source = new RecipeResources(
        List.of(
            entry(helper, "will", "{\"will_input\":{\"type\":\"raw\",\"amount\":1000}}"),
            entry(helper, "heat", "{\"heat_input\":1000,\"heat_output\":2000}")));
    var will = entry(helper, "will", "{\"will_input\":{\"type\":\"ruina\",\"amount\":2000}}");
    boolean rejected = false;
    try {
      RecipeAdapterNativeResources.apply(source, new NativeResource(will, false));
    } catch (IllegalArgumentException conflict) {
      rejected = true;
    }
    helper.assertTrue(rejected, "The adapter overwrote a conflicting claim");
    var replaced = new RecipeResources(
        List.of(RecipeAdapterNativeResources.apply(source, new NativeResource(will, true))));
    helper.assertValueEqual(
        replaced.get(RecipeResourceDefinitions.WILL).input().orElseThrow().kind(),
        "ruina",
        "Replacement");
    helper.assertValueEqual(source.get(RecipeResourceDefinitions.HEAT).output(), 2000L, "Unrelated heat output");
    helper.succeed();
  }
}
