package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.RegisterMachineRecipesEvent;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.resource.FluidRecipeData;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.Tag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "mmcece")
@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class MachineRecipeDeclarationGameTests {
  private static final ResourceLocation JAVA_ID = ResourceLocation.parse("typed_test:java");

  @SubscribeEvent
  public static void addJavaRecipe(RegisterMachineRecipesEvent event) {
    if (Boolean.getBoolean("mmcece.adapter.kubejsSmoke")) {
      event.add(
          MachineRecipeBuilder.create(JAVA_ID, ResourceLocation.parse("mmcece:test_missing_resource_ports"), 20)
              .energyPerTick(32).itemInput(new ItemStack(Items.DIRT), 1)
              .itemOutput(new ItemStack(Items.IRON_INGOT), 1));
    }
  }

  @GameTest(template = "empty16")
  public static void selectedCatalystSurvivesFrozenRunRestore(GameTestHelper helper) {
    var ticket = RecipeResourceRegressionGameTests.build(helper, false);
    var registries = helper.getLevel().registryAccess();
    var source = MachineRecipeJson.decode(
        JsonParser
            .parseString(
                """
                    {"machine":"mmcece:test_recipe_resources","duration":5,
                     "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":2,"match":"item"}],
                     "ingredient_arrays":[{"optional":true,"alternatives":[{"resource":"item","item":{"id":"minecraft:diamond"},"amount":1}]}],
                     "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
                    """)
            .getAsJsonObject(),
        registries);
    var selected = RecipeIngredientArrayResolution.resolve(
        MachineRecipeCompiler.compile(source),
        List.of(new RecipeIngredientArrayResolution.Selection(0, Optional.empty())),
        registries);
    boolean rejected = false;
    try {
      MachineRecipeJson.decode(selected, registries);
    } catch (JsonParseException invalidSource) {
      rejected = invalidSource.getMessage().contains("single item input row");
    }
    helper.assertTrue(rejected, "A raw declaration still requires a single direct item-only row");
    var resolved = MachineRecipeJson.compileResolved(selected, registries.createSerializationContext(JsonOps.INSTANCE));
    var run = FrozenRecipeFactory.freeze(JAVA_ID, resolved, 0, registries, ticket.ports(), -1);
    var rows = FrozenRecipeRun.read(run.save(), registries).save().getCompound("definition")
        .getList("inputs", Tag.TAG_COMPOUND);
    helper.assertValueEqual(rows.size(), 2, "Restored direct and selected inputs");
    helper.assertValueEqual(rows.getCompound(0).getString("match"), "item", "Restored direct matching");
    helper.assertValueEqual(
        rows.getCompound(1).getCompound("stack").getString("id"),
        "minecraft:diamond",
        "Restored selected catalyst");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void adapterReadingSourceComponentsCannotChangeAnotherContextOrTheCatalog(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var machine = ResourceLocation.parse("typed_test:machine");
    var fluid = new FluidStack(Fluids.WATER, 100);
    fluid.set(DataComponents.CUSTOM_NAME, Component.literal("feed"));
    var builder = MachineRecipeBuilder.create(JAVA_ID, machine, 5).fluidInput(fluid)
        .itemOutput(new ItemStack(Items.DIRT), 1);
    var original = MachineRecipeCompiler.compile(builder.build(registries));
    var sources = List.<RecipeHolder<?>>of(new RecipeHolder<>(JAVA_ID, original));
    var settings = new RecipeAdapterImport("read", machine, List.of(), true, 0, 0, 0);
    var first = new RecipeAdapterContext(machine, settings, registries, sources);
    var second = new RecipeAdapterContext(machine, settings, registries, sources);
    var firstRecipe = (MachineRecipe) first.sources().getFirst().value();
    var read = firstRecipe.fluidInputRequirements().getFirst().stack();
    ((MutableComponent) read.get(DataComponents.CUSTOM_NAME)).append(" edited");

    var secondRecipe = (MachineRecipe) second.sources().getFirst().value();
    for (var unchanged : List.of(original, secondRecipe)) {
      var stack = unchanged.fluidInputRequirements().getFirst().stack();
      helper.assertValueEqual(stack.get(DataComponents.CUSTOM_NAME).getString(), "feed", "Isolated source component");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void publishedFluidDisplaysCannotChangeModifierResults(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var coolant = new FluidStack(Fluids.WATER, 250);
    coolant.set(DataComponents.CUSTOM_NAME, Component.literal("Coolant"));
    var source = MachineRecipeBuilder.create(JAVA_ID, ResourceLocation.parse("typed_test:machine"), 20).energyPerTick(1)
        .fluidOutput(coolant).build(registries);
    var decoded = MachineRecipeJson.decode(MachineRecipeJson.encode(source, registries), registries);
    var defaults = MachineRecipeCompiler.currentDefaults();
    var rules = List.of(
        new RecipeModifier(
            RecipeModifier.Target.FLUID,
            RecipeModifier.Io.OUTPUT,
            RecipeModifier.Operation.MULTIPLY,
            2,
            false));
    for (var declaration : List.of(source, decoded)) {
      var template = new RecipeModifierTemplate(declaration, List.of(), null);
      var recipe = MachineRecipeCompiler.compile(declaration);
      for (int amount : List.of(250, 500, 500)) {
        var display = recipe.fluidOutputs().getFirst();
        var name = (MutableComponent) display.get(DataComponents.CUSTOM_NAME);
        helper.assertValueEqual(display.getAmount(), amount, "Fluid amount from original declaration");
        helper.assertValueEqual(name.getString(), "Coolant", "Fluid name from original declaration");
        helper.assertFalse(name.getStyle().isItalic(), "Previous display edit changed a modifier result");
        name.setStyle(name.getStyle().withItalic(true));
        recipe = template.resolve(rules, false, defaults);
      }
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void groupAndTriggerDisplaysCannotChangeUpgradeSources(GameTestHelper helper) {
    var json = JsonParser
        .parseString(
            """
                {"type":"mmcece:machine","machine":"typed_test:machine","duration":20,"energy_per_tick":1,
                "inputs":[],"outputs":[],
                "ingredient_arrays":[{"alternatives":[{"resource":"item","item":{"id":"minecraft:iron_ingot"},"amount":2}]},
                                     {"alternatives":[{"resource":"fluid","fluid":{"id":"minecraft:water","amount":1},"amount":50}]}],
                "random_item_outputs":[{"alternatives":[{"resource":"item","item":{"id":"minecraft:iron_ingot"},"amount":1}]}],
                "triggered_resources":[{"resource":"item","io":"output","tick":5,"amount":1,"item":{"id":"minecraft:iron_ingot"}},
                                       {"resource":"fluid","io":"output","tick":5,"amount":50,"fluid":{"id":"minecraft:water","amount":1}}]}
                """)
        .getAsJsonObject();
    var registries = helper.getLevel().registryAccess();
    var name = Component.literal("original");
    var stack = new ItemStack(Items.IRON_INGOT);
    stack.set(DataComponents.CUSTOM_NAME, name);
    var ops = registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
    var components = ItemStack.SINGLE_ITEM_CODEC.encodeStart(ops, stack).getOrThrow().getAsJsonObject()
        .get("components");
    for (var field : List.of("ingredient_arrays", "random_item_outputs", "triggered_resources")) {
      for (var entry : json.getAsJsonArray(field)) {
        var row = entry.getAsJsonObject();
        if (row.has("alternatives")) {
          row = row.getAsJsonArray("alternatives").get(0).getAsJsonObject();
        }
        row.getAsJsonObject(row.get("resource").getAsString()).add("components", components.deepCopy());
      }
    }
    var declaration = MachineRecipeJson.decode(json, registries);
    var template = new RecipeModifierTemplate(declaration, List.of(), null);
    var recipe = MachineRecipeCompiler.compile(declaration);
    var rules = List.of(
        new RecipeModifier(
            RecipeModifier.Target.DURATION,
            RecipeModifier.Io.INPUT,
            RecipeModifier.Operation.MULTIPLY,
            2,
            false));
    for (int tick : List.of(5, 10, 10)) {
      var groups = recipe.ingredientArrays();
      var triggers = recipe.triggeredResources().rows();
      helper.assertValueEqual(triggers.getFirst().tick(), tick, "Trigger tick scaled from its declaration");
      for (var displayed : List.of(
          groups.inputs().getFirst().alternatives().getFirst().item().orElseThrow().getHoverName(),
          groups.inputs().getLast().alternatives().getFirst().fluid().orElseThrow().get(DataComponents.CUSTOM_NAME),
          groups.randomOutputs().getFirst().alternatives().getFirst().item().orElseThrow().getHoverName(),
          triggers.getFirst().item().orElseThrow().getHoverName(),
          triggers.getLast().fluid().orElseThrow().get(DataComponents.CUSTOM_NAME))) {
        helper.assertValueEqual(displayed.getString(), "original", "Captured prototype name");
        helper.assertFalse(displayed.getStyle().isItalic(), "Display style escaped into upgrade resolution");
        ((MutableComponent) displayed).setStyle(displayed.getStyle().withItalic(true));
      }
      recipe = template.resolve(rules, false, MachineRecipeCompiler.currentDefaults());
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void explicitFluidResourceReplacesAllConvenienceRows(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var builder = MachineRecipeBuilder.create(
        ResourceLocation.parse("typed_test:mmcece/fluid_override"),
        ResourceLocation.parse("typed_test:machine"),
        5).itemOutput(new ItemStack(Items.DIRT), 1);
    var water = new FluidStack(Fluids.WATER, 1);
    for (int row = 0; row <= MachineRecipeJson.MAX_DEMANDS; row++) {
      builder.fluidInput(water);
    }
    var replacement = new FluidRecipeData(
        List.of(new FluidRequirement(new FluidStack(Fluids.LAVA, 250), 1)),
        List.of(),
        List.of(),
        List.of());
    builder.setResource(RecipeResourceDefinitions.FLUID, replacement).fluidOutput(water);

    var recipe = MachineRecipeCompiler.compile(builder.build(registries));
    helper.assertValueEqual(recipe.fluidInputRequirements().size(), 1, "Replacement input count");
    var input = recipe.fluidInputRequirements().getFirst().stack();
    helper.assertTrue(input.getFluid() == Fluids.LAVA && input.getAmount() == 250, "Replacement fluid and amount");
    helper.assertTrue(recipe.fluidOutputRequirements().isEmpty(), "Only the explicit resource supplies fluid rows");
    var exported = MachineRecipeJson.decode(builder.toJson(registries), registries);
    helper
        .assertTrue(recipe.sameDefinition(MachineRecipeCompiler.compile(exported)), "Export retained the replacement");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void java_kubejs_and_data_pack_publish_equivalent_recipes(GameTestHelper helper) {
    if (!Boolean.getBoolean("mmcece.adapter.kubejsSmoke")) {
      helper.succeed();
      return;
    }
    var server = helper.getLevel().getServer();
    var data = MachineRecipeService
        .definition(server, ResourceLocation.parse("mmcece:mmcece/test_missing_resource_energy")).orElseThrow();
    for (var id : java.util.List.of(JAVA_ID, ResourceLocation.parse("typed_test:kjs"))) {
      var definition = MachineRecipeService.definition(server, id).orElseThrow();
      helper.assertTrue(data.sameDefinition(definition), "Different compiled values for " + id);
      helper.assertTrue(
          server.getRecipeManager().byKey(id).orElseThrow().value() == definition,
          "Manager and execution catalog published different recipes for " + id);
    }
    helper.succeed();
  }
}
