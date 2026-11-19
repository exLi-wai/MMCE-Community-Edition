package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonParser;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import dev.latvian.mods.rhino.ContextFactory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_kubejs_adapter_resources")
@PrefixGameTestTemplate(false)
public final class KubeJSAdapterResourcesRhinoGameTest {
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
