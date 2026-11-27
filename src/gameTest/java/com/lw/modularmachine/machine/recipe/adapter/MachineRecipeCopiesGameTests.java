package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.lw.modularmachine.api.recipe.FluidRequirement;
import com.lw.modularmachine.api.recipe.ItemAmount;
import com.lw.modularmachine.api.recipe.ItemPaymentData;
import com.lw.modularmachine.api.recipe.MachineRecipeDeclaration;
import com.lw.modularmachine.api.recipe.RecipeModifier;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.RecipeIngredientArrayResolution;
import com.lw.modularmachine.machine.recipe.RecipeResourceRegressionGameTests;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class MachineRecipeCopiesGameTests {
  private static final ResourceLocation TARGET = ResourceLocation.parse("test:a");
  private static final ResourceLocation SOURCE = ResourceLocation.parse("test:b");

  private static MachineRecipeDeclaration decode(GameTestHelper helper, String source) {
    return MachineRecipeJson
        .decode(JsonParser.parseString(source).getAsJsonObject(), helper.getLevel().registryAccess());
  }

  private static MachineRecipeDeclaration copy(MachineRecipeDeclaration source, RecipeAdapterImport settings) {
    return MachineRecipeCopies
        .copy(source, TARGET, settings, new RecipeAdapterCustomizations(settings.customization()));
  }

  private static RecipeAdapterCustomization edits(List<RecipeModifier> rules, List<FluidRequirement> inputs, List<FluidRequirement> outputs) {
    return new RecipeAdapterCustomization(
        rules,
        List.of(),
        inputs,
        outputs,
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Optional.empty(),
        Optional.empty());
  }

  @GameTest(template = "empty16")
  public static void adapterSettingsReadsKeepCompiledOutputComponentsIsolated(GameTestHelper helper) {
    var source = decode(helper, """
        {"type":"mmcece:machine","machine":"test:b","duration":10,"energy_per_tick":1,
         "inputs":[],"outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
        """);
    var stack = new ItemStack(Items.DIAMOND);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("output"));
    var preview = new ItemStack(Items.EMERALD);
    preview.set(DataComponents.CUSTOM_NAME, Component.literal("preview"));
    var fluid = new FluidStack(Fluids.WATER, 25);
    fluid.set(DataComponents.CUSTOM_NAME, Component.literal("water"));
    var extra = new RecipeAdapterCustomization(
        List.of(),
        List.of(),
        List.of(),
        List.of(new FluidRequirement(fluid, 1)),
        Optional.empty(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new ItemAmount(stack, 1, 1, "", "exact", preview, false, 0, 0, false)),
        Map.of(),
        Optional.empty(),
        Optional.empty());
    var settings = new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0, extra);
    var context = new RecipeAdapterContext(TARGET, settings, helper.getLevel().registryAccess(), List.of());
    var view = context.settings().customization();
    var output = view.itemOutputs().getFirst();
    var compiled = MachineRecipeCompiler.compile(copy(source, settings));
    ((MutableComponent) output.ingredientStack().get(DataComponents.CUSTOM_NAME)).append(" changed");
    ((MutableComponent) output.displayStack().get(DataComponents.CUSTOM_NAME)).append(" changed");
    ((MutableComponent) view.fluidOutputs().getFirst().stack().get(DataComponents.CUSTOM_NAME)).append(" changed");
    var stored = compiled.outputs().getLast();
    helper.assertValueEqual(stored.ingredientStack().getHoverName().getString(), "output", "Compiled prototype");
    helper.assertValueEqual(stored.previewStack().getHoverName().getString(), "preview", "Compiled preview");
    helper.assertValueEqual(
        compiled.fluidOutputRequirements().getFirst().stack().get(DataComponents.CUSTOM_NAME).getString(),
        "water",
        "Compiled fluid");
    var reread = context.settings().customization().itemOutputs().getFirst();
    helper.assertValueEqual(reread.ingredientStack().getHoverName().getString(), "output", "Next adapter read");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void copiesOriginalDeclarationsAndAppliesEachLocalModifierOnce(GameTestHelper helper) {
    var source = decode(helper, """
        {"type":"mmcece:machine","machine":"test:b","duration":20,"energy_per_tick":10,
         "parallelized":true,"failure_action":"still",
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":2,"chance":0.5}],
         "outputs":[{"stack":{"id":"minecraft:gold_ingot"},"amount":1,"chance":0.25}],
         "modifiers":[{"target":"item","io":"input","operation":"multiply","value":2}],
         "item_payments":{"fuels":[{"burn_time":100,"chance":0.5,"selector":"fuel"}]},
         "item_callbacks":[{"io":"input","row":0,"checker":"kubejs:check"}],
         "neoforge:conditions":[]}
        """);
    var extra = edits(
        List.of(
            new RecipeModifier(
                RecipeModifier.Target.ITEM,
                RecipeModifier.Io.INPUT,
                RecipeModifier.Operation.MULTIPLY,
                3,
                false)),
        List.of(),
        List.of());
    var target = copy(source, new RecipeAdapterImport("copy", SOURCE, List.of(), true, 30, 5, 7, extra));
    helper.assertValueEqual(source.modifiers().size(), 1, "Source is unchanged");
    helper.assertValueEqual(target.machineId(), TARGET, "Target machine");
    helper.assertValueEqual(target.duration(), 30, "Duration override");
    helper.assertValueEqual(
        target.resources().get(RecipeResourceDefinitions.ENERGY).inputPerTick(),
        15L,
        "Energy addition");
    helper.assertValueEqual(target.modifiers().size(), 2, "Both original modifiers");
    helper.assertValueEqual(source.scriptOptions(), target.scriptOptions(), "Callbacks");
    var effective = MachineRecipeCompiler.compile(target);
    helper.assertValueEqual(effective.inputs().getFirst().amount(), 12L, "Quantity modified once");
    helper.assertValueEqual(effective.inputs().getFirst().chance(), 0.5, "Input chance");
    helper.assertValueEqual(effective.itemPayments().fuels().getFirst().burnTime(), 600L, "Fuel modified once");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void defaultCopyPreservesDurabilityPaymentWithFluidIngredientGroup(GameTestHelper helper) {
    var source = decode(
        helper,
        """
            {"type":"mmcece:machine","machine":"test:b","duration":20,
             "inputs":[{"stack":{"id":"minecraft:iron_pickaxe"},"amount":1,"match":"item"}],
             "item_payments":{"tools":[{"row":0,"durability":3}]},
             "item_callbacks":[{"io":"input","row":0,"checker":"kubejs:check_tool"}],
             "ingredient_arrays":[{"alternatives":[{"resource":"fluid","fluid":{"id":"minecraft:water","amount":1},"amount":100}]}],
             "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
            """);
    var original = MachineRecipeCompiler.compile(source);
    var settings = new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0);
    var copied = MachineRecipeCompiler.compile(copy(source, settings));
    helper.assertValueEqual(copied.machineId(), TARGET, "Target machine");
    helper.assertValueEqual(copied.inputs(), original.inputs(), "Item-only tool input");
    helper.assertValueEqual(copied.itemPayments(), original.itemPayments(), "Indexed durability payment");
    helper.assertValueEqual(copied.scriptOptions(), original.scriptOptions(), "Tool callback row");
    helper.assertValueEqual(copied.ingredientArrays(), original.ingredientArrays(), "Original fluid group");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void copiedDurabilityToolPaysWithOrWithoutDiamondCatalyst(GameTestHelper helper) {
    var source = decode(
        helper,
        """
            {"type":"mmcece:machine","machine":"test:b","duration":20,
             "inputs":[{"stack":{"id":"minecraft:iron_pickaxe"},"amount":1,"match":"item"}],
             "item_payments":{"tools":[{"row":0,"durability":3}]},
             "item_callbacks":[{"io":"input","row":0,"checker":"kubejs:check_tool"}],
             "ingredient_arrays":[{"optional":true,"alternatives":[{"resource":"item","item":{"id":"minecraft:diamond"},"amount":1}]}],
             "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
            """);
    var settings = new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0);
    var copied = MachineRecipeCompiler.compile(copy(source, settings));
    var registries = helper.getLevel().registryAccess();
    var skipped = new RecipeIngredientArrayResolution.Selection(-1, Optional.empty());
    var selected = new RecipeIngredientArrayResolution.Selection(0, Optional.empty());
    var without = MachineRecipeCompiler.compile(
        MachineRecipeJson
            .decode(RecipeIngredientArrayResolution.resolve(copied, List.of(skipped), registries), registries));
    var with = MachineRecipeCompiler.compile(
        MachineRecipeJson
            .decode(RecipeIngredientArrayResolution.resolve(copied, List.of(selected), registries), registries));
    helper.assertValueEqual(without.inputs().size(), 1, "Skipped diamond");
    helper.assertValueEqual(with.inputs().size(), 2, "Selected diamond");
    helper.assertTrue(with.inputs().get(1).ingredientStack().is(Items.DIAMOND), "Catalyst input");
    for (var resolved : List.of(without, with)) {
      helper.assertTrue(resolved.inputs().getFirst().ignoresComponents(), "Preserved tool row match");
      helper.assertValueEqual(resolved.itemPayments().tools(), List.of(new ItemPaymentData.Tool(0, 3)), "Tool payment");
      helper.assertValueEqual(resolved.scriptOptions(), source.scriptOptions(), "Indexed tool checker");
    }
    RecipeResourceRegressionGameTests.assertOptionalDiamondToolPayments(helper, without, with);
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void copiedSourceResolvesExistingItemGroupAndItemOnlyChecker(GameTestHelper helper) {
    var source = decode(helper, """
        {"type":"mmcece:machine","machine":"test:b","duration":20,
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":2,"match":"item"}],
         "item_callbacks":[{"io":"input","row":0,"checker":"kubejs:check_iron"}],
         "ingredient_arrays":[{"alternatives":[{"resource":"item","item":{"id":"minecraft:coal"},"amount":1}]}],
         "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
        """);
    var plain = copy(source, new RecipeAdapterImport("copy", SOURCE, List.of(), true, 40, 4, 7));
    helper.assertValueEqual(plain.ingredientArrays(), source.ingredientArrays(), "Original group declarations");
    helper.assertValueEqual(plain.scriptOptions(), source.scriptOptions(), "Original checker row");
    helper.assertTrue(
        MachineRecipeCompiler.compile(plain).inputs().getFirst().ignoresComponents(),
        "Original input match");
    var extra = edits(List.of(), List.of(), List.of(new FluidRequirement(new FluidStack(Fluids.WATER, 25), 1)));
    var copied = MachineRecipeCompiler
        .compile(copy(source, new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0, extra)));
    var registries = helper.getLevel().registryAccess();
    helper
        .assertValueEqual(copied.ingredientArrays(), source.ingredientArrays(), "An added output retains input groups");
    var selected = List.of(new RecipeIngredientArrayResolution.Selection(0, Optional.empty()));
    var expanded = RecipeIngredientArrayResolution.resolve(copied, selected, registries);
    var resolved = MachineRecipeJson.compileResolved(expanded, registries.createSerializationContext(JsonOps.INSTANCE));
    helper.assertValueEqual(resolved.inputs().size(), 2, "Expanded item rows");
    helper.assertTrue(resolved.inputs().get(1).ingredientStack().is(Items.COAL), "Selected coal alternative");
    var ironInput = resolved.inputs().getFirst();
    helper.assertTrue(
        ironInput.ingredientStack().is(Items.IRON_INGOT) && ironInput.ignoresComponents(),
        "Original iron match");
    helper.assertValueEqual(ironInput.amount(), 2L, "Item-only input amount");
    var checker = resolved.scriptOptions().itemCallbacks().getFirst();
    helper.assertValueEqual(checker.row(), 0, "Original iron checker row");
    helper
        .assertValueEqual(checker.checker().orElseThrow(), ResourceLocation.parse("kubejs:check_iron"), "Iron checker");
    helper.assertValueEqual(resolved.fluidOutputRequirements().getFirst().stack().getAmount(), 25, "Added output");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void copiedFluidRowsKeepExplicitEmptyAndInheritOnlyWhenOmitted(GameTestHelper helper) {
    var source = decode(
        helper,
        """
            {"type":"mmcece:machine","machine":"test:b","duration":10,"selectors":{"fluid_input":"source_in","fluid_output":"source_out"},
             "inputs":[],"outputs":[],
             "fluid_inputs":[{"id":"minecraft:water","amount":1},{"id":"minecraft:lava","amount":2,"selector":"hot"}],
             "fluid_outputs":[{"id":"minecraft:water","amount":1,"selector":""}]}
            """);
    var unmarked = new FluidRequirement(new FluidStack(Fluids.WATER, 3), 1, false, "");
    var inherited = new FluidRequirement(new FluidStack(Fluids.WATER, 4), 1);
    var target = copy(
        source,
        new RecipeAdapterImport(
            "copy",
            SOURCE,
            List.of(),
            true,
            0,
            0,
            0,
            edits(List.of(), List.of(unmarked), List.of(inherited))));
    var rows = target.resources().get(RecipeResourceDefinitions.FLUID);
    helper.assertValueEqual(
        source.resources().get(RecipeResourceDefinitions.FLUID).inputs().size(),
        2,
        "Unmodified source");
    helper.assertValueEqual(target.selectors(), source.selectors(), "Phase selectors");
    helper.assertValueEqual(rows.inputs().size(), 3, "Combined inputs");
    helper.assertFalse(rows.inputs().getFirst().selectorSpecified(), "Source input inherits default");
    helper.assertValueEqual(rows.inputs().get(1).selector(), "hot", "Original explicit selector");
    helper.assertValueEqual(rows.inputs().getLast().selector("source_in"), "", "Appended unmarked input");
    helper.assertValueEqual(rows.outputs().getFirst().selector("source_out"), "", "Source unmarked output");
    helper.assertFalse(rows.outputs().getLast().selectorSpecified(), "Appended output inherits default");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void resourceOnlyMachineCopiesAcceptCustomizationWithoutItemRows(GameTestHelper helper) {
    var source = decode(helper, """
        {"type":"mmcece:machine","machine":"test:b","duration":20,"energy_per_tick":10,
         "inputs":[],"outputs":[],"fluid_outputs":[{"id":"minecraft:water","amount":1000}]}
        """);
    var extra = edits(
        List.of(
            new RecipeModifier(
                RecipeModifier.Target.FLUID,
                RecipeModifier.Io.OUTPUT,
                RecipeModifier.Operation.MULTIPLY,
                2,
                false)),
        List.of(),
        List.of());
    var effective = MachineRecipeCompiler
        .compile(copy(source, new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0, extra)));
    helper.assertTrue(effective.inputs().isEmpty() && effective.outputs().isEmpty(), "No item rows");
    helper.assertValueEqual(
        effective.fluidOutputRequirements().getFirst().stack().getAmount(),
        2000,
        "Normalized fluid amount");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void defaultCopyKeepsSourcePriorityAndExplicitZeroCanClearIt(GameTestHelper helper) {
    var source = decode(helper, """
        {"type":"mmcece:machine","machine":"test:b","duration":20,"priority":9,"energy_per_tick":10,
         "inputs":[],"outputs":[],"fluid_outputs":[{"id":"minecraft:water","amount":1000}]}
        """);
    helper.assertValueEqual(
        copy(source, new RecipeAdapterImport("copy", SOURCE, List.of(), true, 0, 0, 0)).priority(),
        9,
        "Retained priority");
    var explicit = new RecipeAdapterImport(
        "copy",
        SOURCE,
        List.of(),
        true,
        0,
        0,
        0,
        RecipeAdapterCustomization.NONE,
        true);
    helper.assertValueEqual(copy(source, explicit).priority(), 0, "Explicit zero priority");
    helper.succeed();
  }
}
