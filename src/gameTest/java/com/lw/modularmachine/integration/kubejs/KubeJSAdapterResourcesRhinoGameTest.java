package com.lw.modularmachine.integration.kubejs;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.BufferedAmountRecipeData;
import com.lw.modularmachine.api.recipe.LaserRecipeData;
import com.lw.modularmachine.api.recipe.WorldPoolRecipeData;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import dev.latvian.mods.rhino.ContextFactory;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_kubejs_adapter_resources")
@PrefixGameTestTemplate(false)
public final class KubeJSAdapterResourcesRhinoGameTest {
  @GameTest(template = "empty16")
  public static void triggerKeepsOnlyRetainedAppendedOutputBindings(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var registration = new KubeJSRecipeAdapterRegistration(registries);
    var stack = new ItemStack(Items.GOLD_INGOT);
    registration.create("mmcece:ae", "minecraft:furnace").addItemOutputModifier(1, "kubejs:source").addItemOutput(stack)
        .setTriggerTime(2).addItemOutput(stack).setTriggerTime(0).setPreviewStack(new ItemStack(Items.DIAMOND))
        .setMinMaxAmount(1, 2).addItemModifier("kubejs:kept").build();
    registration.seal();
    var result = registration.snapshot().get(ResourceLocation.parse("mmcece:ae")).getFirst().customization();
    helper.assertValueEqual(result.resourceAdditions().triggers().size(), 1, "Only positive ticks move a row");
    helper.assertValueEqual(result.itemOutputs().size(), 1, "Only one retained output");
    var output = result.itemOutputs().getFirst();
    helper.assertValueEqual(output.minAmount(), 1L, "Minimum follows retained output");
    helper.assertValueEqual(output.maxAmount(), 2L, "Maximum follows retained output");
    helper.assertFalse(output.previewStack().isEmpty(), "Retained output keeps preview");
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

  @GameTest(template = "empty16")
  public static void typedAdapterSnapshotsComponentValuesAndEnforcesCaptureSize(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var source = new ItemStack(Items.IRON_INGOT);
    var data = new CompoundTag();
    data.putString("marker", "original");
    source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    source.set(DataComponents.CUSTOM_NAME, Component.literal("product"));
    var preview = new ItemStack(Items.EMERALD);
    preview.set(DataComponents.CUSTOM_NAME, Component.literal("preview"));
    var owner = new KubeJSRecipeAdapterRegistration(registries);
    var builder = owner.create("mmcece:ae", "minecraft:furnace").addItemOutput(source).setPreviewStack(preview);
    data.putString("marker", "modified");
    source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    builder.build();
    owner.seal();
    var captured = owner.snapshot().get(ResourceLocation.parse("mmcece:ae")).getFirst().customization();
    var output = captured.itemOutputs().getFirst();
    var item = output.ingredientStack();
    helper.assertValueEqual(
        item.get(DataComponents.CUSTOM_DATA).copyTag().getString("marker"),
        "original",
        "Detached component");
    ((MutableComponent) item.get(DataComponents.CUSTOM_NAME)).append(" changed");
    ((MutableComponent) output.displayStack().get(DataComponents.CUSTOM_NAME)).append(" changed");
    helper.assertValueEqual(output.prototype(registries).getHoverName().getString(), "product", "Adapter output");
    helper.assertValueEqual(output.previewStack().getHoverName().getString(), "preview", "Adapter preview");

    data.putString("marker", "x".repeat(66_000));
    source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    var oversized = new KubeJSRecipeAdapterRegistration(registries).create("mmcece:ae", "minecraft:furnace");
    assertBuildRejected(helper, oversized.addItemOutput(source), "too large");

    data.putString("marker", "x".repeat(34_000));
    source.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    var total = new KubeJSRecipeAdapterRegistration(registries).create("mmcece:ae", "minecraft:furnace");
    assertBuildRejected(helper, total.addItemInput(source).addItemInput(source), "too large");
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

  @GameTest(template = "empty16")
  public static void builtAdapterGroupsDetachMutableComponents(GameTestHelper helper) {
    var name = Component.literal("original");
    var item = new ItemStack(Items.EMERALD);
    item.set(DataComponents.CUSTOM_NAME, name);
    var fluid = new FluidStack(Fluids.WATER, 100);
    fluid.set(DataComponents.CUSTOM_NAME, name);
    var items = KubeJSIngredientArrayBuilder.newBuilder().addIngredient(item);
    var registration = new KubeJSRecipeAdapterRegistration(helper.getLevel().registryAccess());
    registration.create("mmcece:ae", "minecraft:furnace").addIngredientArrayInput(items)
        .addCatalystInput(fluid, List.of(), List.of()).addRandomItemOutput(items).addItemOutput(item).setTriggerTime(2)
        .addFluidOutput(fluid).setTriggerTime(3).build();
    name.append(" changed after build");
    registration.seal();
    var additions = registration.snapshot().get(ResourceLocation.parse("mmcece:ae")).getFirst().customization()
        .resourceAdditions();
    var groups = additions.groups();
    for (var group : List.of(groups.inputs().getFirst(), groups.randomOutputs().getFirst())) {
      var choice = group.alternatives().getFirst();
      ((MutableComponent) choice.item().orElseThrow().getHoverName()).append(" edited");
      helper.assertValueEqual(
          choice.item().orElseThrow().getHoverName().getString(),
          "original",
          "Item group after reading");
    }
    var catalyst = groups.inputs().getLast().alternatives().getFirst();
    ((MutableComponent) catalyst.fluid().orElseThrow().get(DataComponents.CUSTOM_NAME)).append(" edited");
    helper.assertValueEqual(
        catalyst.fluid().orElseThrow().get(DataComponents.CUSTOM_NAME).getString(),
        "original",
        "Fluid catalyst after reading");
    var itemTrigger = additions.triggers().getFirst().row();
    var fluidTrigger = additions.triggers().getLast().row();
    ((MutableComponent) itemTrigger.item().orElseThrow().getHoverName()).append(" edited");
    ((MutableComponent) fluidTrigger.fluid().orElseThrow().get(DataComponents.CUSTOM_NAME)).append(" edited");
    helper.assertValueEqual(
        itemTrigger.item().orElseThrow().getHoverName().getString(),
        "original",
        "Triggered item after reading");
    helper.assertValueEqual(
        fluidTrigger.fluid().orElseThrow().get(DataComponents.CUSTOM_NAME).getString(),
        "original",
        "Triggered fluid after reading");
    helper.succeed();
  }

  @GameTest(template = "empty16")
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

  @GameTest(template = "empty16")
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
    var will = (WorldPoolRecipeData) resources.get(RecipeResourceDefinitions.WILL.id()).entry().data();
    helper.assertValueEqual(will.input().orElseThrow().kind(), "raw", "Captured will type");
    helper.assertValueEqual(will.input().orElseThrow().amount(), 1000L, "Will amount");
    var heat = resources.get(RecipeResourceDefinitions.HEAT.id());
    helper.assertTrue(heat.replace(), "setResource must mark the resource as replaced");
    helper.assertValueEqual(((BufferedAmountRecipeData) heat.entry().data()).input(), 125L, "Heat amount");
    var laser = resources.get(RecipeResourceDefinitions.LASER.id());
    helper.assertFalse(laser.replace(), "addLaserInput must append to source requirements");
    helper.assertValueEqual(((LaserRecipeData) laser.entry().data()).input(), 9007199254740993L, "Exact laser amount");
    if (Boolean.getBoolean("mmcece.adapter.legacyScriptSmoke")) {
      assertPublishedProbe(helper);
    }
    helper.succeed();
  }

  private static void assertPublishedProbe(GameTestHelper helper) {
    var manager = helper.getLevel().getRecipeManager();
    var machine = ResourceLocation.parse("mmcece:test_missing_resource_ports");
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
        .assertValueEqual(resources.get(RecipeResourceDefinitions.EMBER).micros(), 1_250_000L, "Published ember input");
    helper.assertValueEqual(recipe.chemicals().inputs().getFirst().amount(), 1000L, "Published chemical input");
  }
}
