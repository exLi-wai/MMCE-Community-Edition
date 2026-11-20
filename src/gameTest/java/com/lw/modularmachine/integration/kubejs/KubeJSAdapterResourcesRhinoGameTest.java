package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import dev.latvian.mods.rhino.ContextFactory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_kubejs_adapter_resources")
@PrefixGameTestTemplate(false)
public final class KubeJSAdapterResourcesRhinoGameTest {
  @GameTest(templateNamespace = "mmcece_adapter", template = "empty16")
  public static void triggerKeepsOnlyRetainedAppendedOutputBindings(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var registration = new KubeJSRecipeAdapterRegistration(registries);
    var stack = new ItemStack(Items.GOLD_INGOT);
    registration.create("mmcece:ae", "minecraft:furnace").addItemOutputModifier(1, "kubejs:source").addItemOutput(stack)
        .setTriggerTime(2).addItemOutput(stack).setTriggerTime(0).setPreviewStack(new ItemStack(Items.DIAMOND))
        .setMinMaxAmount(1, 2).addItemModifier("kubejs:kept").build();
    registration.seal();
    var result = registration.snapshot().get(ResourceLocation.parse("mmcece:ae")).getFirst().customization();
    var additions = JsonParser.parseString(result.resourceAdditions().encoded()).getAsJsonObject();
    helper
        .assertValueEqual(additions.getAsJsonArray("triggered_resources").size(), 1, "Only positive ticks move a row");
    helper.assertValueEqual(
        additions.getAsJsonArray("output_ranges").get(0).getAsJsonObject().get("row").getAsInt(),
        0,
        "Range follows retained output");
    helper.assertTrue(
        result.itemOutputs().size() == 1 && result.itemOutputs().getFirst().encodedPreview().isPresent(),
        "Retained output keeps preview");
    var source = result.itemCallbacks().getFirst();
    var kept = result.itemCallbacks().getLast();
    helper.assertTrue(!source.addedOutput() && source.callback().row() == 1, "Source callback index must not shift");
    helper.assertTrue(kept.addedOutput() && kept.callback().row() == 0, "Appended callback must shift with its output");

    var preview = new KubeJSRecipeAdapterRegistration(registries).create("mmcece:ae", "minecraft:furnace");
    assertBuildRejected(
        helper,
        preview.addItemOutput(stack).setPreViewNBT(new CompoundTag()).setTriggerTime(2),
        "preview");
    var callback = new KubeJSRecipeAdapterRegistration(registries).create("mmcece:ae", "minecraft:furnace");
    assertBuildRejected(
        helper,
        callback.addItemOutput(stack).setTriggerTime(2).addItemModifier("kubejs:conflict"),
        "item callbacks");
    helper.succeed();
  }

  private static void assertBuildRejected(GameTestHelper helper, KubeJSRecipeAdapterBuilder builder, String reason) {
    try {
      builder.build();
    } catch (IllegalArgumentException rejected) {
      helper.assertTrue(rejected.getMessage().contains(reason), rejected.getMessage());
      return;
    }
    helper.fail("Expected adapter build to reject " + reason);
  }

  @GameTest(templateNamespace = "mmcece_adapter", template = "empty16")
  public static void ingredientGroupFreezesOnlyReferencedInlineCallbacks(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    RecipeItemCallbacks.Checker first = (context, stack) -> false;
    RecipeItemCallbacks.Checker second = (context, stack) -> true;
    var primer = KubeJSIngredientArrayBuilder.newBuilder().addIngredient(new ItemStack(Items.IRON_INGOT))
        .setChecker(first);
    var firstGroup = primer.snapshot(registries);
    var firstCallbacks = primer.callbacksFor(firstGroup);
    primer.setChecker(second).addItemModifier("kubejs:named");
    var secondGroup = primer.snapshot(registries);
    var secondCallbacks = primer.callbacksFor(secondGroup);
    primer.setChecker("kubejs:named");
    helper.assertTrue(
        firstCallbacks.checkers().size() == 1
            && firstCallbacks.checkers().get(firstGroup.alternatives().getFirst().checker().orElseThrow()) == first,
        "Previously attached group must keep its checker");
    helper.assertTrue(
        secondCallbacks.checkers().size() == 1
            && secondCallbacks.checkers().get(secondGroup.alternatives().getFirst().checker().orElseThrow()) == second,
        "Replaced checkers must not occupy callback slots");
    helper.assertTrue(
        secondCallbacks.modifiers().isEmpty() && primer.callbacksFor(primer.snapshot(registries)).checkers().isEmpty(),
        "Named callbacks must not install inline functions");
    helper.succeed();
  }

  @GameTest(templateNamespace = "mmcece_structure_nbt", template = "empty16")
  public static void adapterChainCapturesChemicalAndEachLegacyResourceFamily(GameTestHelper helper) {
    var registration = new KubeJSRecipeAdapterRegistration(helper.getLevel().registryAccess());
    var context = new ContextFactory().enter();
    var scope = context.initStandardObjects();
    context.addToScope(scope, "event", registration);
    context.addToScope(scope, "heat", JsonParser.parseString("{\"heat_output\":1000}").getAsJsonObject());
    context.evaluateString(scope, """
        event.create('mmcece:ae', 'minecraft:furnace')
          .addChemicalInput('mekanism:oxygen', 1000)
          .addManaInput(100).addManaOutput(50)
          .addAuraInput(100).addAuraOutput('naturesaura:overworld', 30)
          .addWillInput(1).addMultiChunkWillOutput('raw', 1, 0.25, 2)
          .addVisInput(1).addVisOutput(2, 1)
          .addFluxInput(1, 2, 0.5).addFluxOutput(0.01)
          .addEmberInput(1.25).addLaserInput('9007199254740993')
          .addLifeEssenceInput(100).addLifeEssenceOutput(50)
          .addAspectInput('thaumaturge:aqua', 2).addEssentiaOutput('thaumaturge:aqua', 1)
          .addStarlightInput(1.5).addStarlightOutput(0.25)
          .addConstellationInput('astralsorcery:discidia').addMeteorOutput('minecraft:iron_ingot')
          .addCompressedAirInput(2, 1000).addCompressedAirOutput(0, 100)
          .setResource('mmcece:heat', heat).addHeatInput(0.125)
          .setDimension('minecraft:overworld').setBiome('minecraft:plains')
          .setTime(240, 1200).setMoonPhase(0, 1).setAltitude(0, 100).setWeather('sunny')
          .build()
        """, "adapter-native-resources", 1, null);
    registration.seal();
    var customization = registration.snapshot().get(ResourceLocation.parse("mmcece:ae")).getFirst().customization();
    var resources = customization.resourceAdditions().nativeResources();
    helper.assertValueEqual(customization.chemicalInputs().getFirst().amount(), 1000L, "Chemical amount");
    helper.assertValueEqual(resources.size(), 16, "Native resource count");
    var will = JsonParser.parseString(resources.get(ResourceLocation.parse("mmcece:will")).encoded()).getAsJsonObject();
    helper.assertValueEqual("raw", will.getAsJsonObject("will_input").get("type").getAsString(), "Captured resource");
    helper.assertValueEqual(will.getAsJsonObject("will_input").get("amount").getAsLong(), 1000L, "Will amount");
    var heat = resources.get(ResourceLocation.parse("mmcece:heat"));
    helper.assertTrue(heat.replace(), "setResource must mark the resource as replaced");
    helper.assertValueEqual(
        JsonParser.parseString(heat.encoded()).getAsJsonObject().get("heat_input").getAsLong(),
        125L,
        "Heat amount");
    var laser = resources.get(ResourceLocation.parse("mmcece:laser"));
    helper.assertFalse(laser.replace(), "addLaserInput must append to source requirements");
    helper.assertValueEqual(
        9007199254740993L,
        JsonParser.parseString(laser.encoded()).getAsJsonObject().get("laser_input").getAsLong(),
        "Exact laser amount");
    if (Boolean.getBoolean("mmcece.adapter.legacyScriptSmoke")) {
      assertPublishedProbe(helper);
    }
    helper.succeed();
  }

  private static void assertPublishedProbe(GameTestHelper helper) {
    var manager = helper.getLevel().getRecipeManager();
    var machine = ResourceLocation.parse("mmcece:ae");
    var recipe = manager.getRecipes().stream().filter(holder -> holder.id().getPath().startsWith("mmcece/adapted/"))
        .map(holder -> holder.value())
        .filter(value -> value instanceof MachineRecipe candidate && candidate.machineId().equals(machine))
        .map(MachineRecipe.class::cast).findFirst()
        .orElseThrow(() -> new IllegalStateException("The real KubeJS script did not publish a recipe"));
    var resources = recipe.resourceData();
    helper.assertValueEqual(resources.get(RecipeResourceDefinitions.MANA).input(), 100L, "Published mana input");
    helper.assertValueEqual(resources.get(RecipeResourceDefinitions.AURA).input(), 100L, "Published aura input");
    helper.assertValueEqual(
        resources.get(RecipeResourceDefinitions.WILL).input().orElseThrow().amount(),
        1000L,
        "Published will input");
    helper.assertValueEqual(
        resources.get(RecipeResourceDefinitions.VIS).input().orElseThrow().amount(),
        1000L,
        "Published Vis input");
    helper
        .assertValueEqual(resources.get(RecipeResourceDefinitions.EMBER).micros(), 1_000_000L, "Published ember input");
    helper.assertValueEqual(recipe.chemicals().inputs().getFirst().amount(), 1000L, "Published chemical input");
  }
}
