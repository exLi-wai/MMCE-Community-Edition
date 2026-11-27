package com.lw.modularmachine.machine.recipe.adapter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.IngredientArrayData;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterCustomization;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeCompiler;
import com.lw.modularmachine.machine.recipe.MachineRecipeJson;
import com.lw.modularmachine.machine.recipe.RecipeItemCallbackService;
import com.lw.modularmachine.machine.recipe.RecipeResourceRegressionGameTests;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class ItemOnlyCopyGameTests {
  private static final ResourceLocation TARGET = ResourceLocation.parse("mmcece:test_recipe_resources");
  private static final ResourceLocation ID = ResourceLocation.parse("test:mixed_iron");
  private static final RecipeAdapterCustomization TOOLTIP = new RecipeAdapterCustomization(
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      Optional.empty(),
      List.of(),
      List.of(),
      List.of(),
      List.of(),
      Map.of(),
      Optional.empty(),
      Optional.empty(),
      0,
      Optional.empty(),
      Optional.empty(),
      List.of("Copied"),
      List.of(),
      List.of());

  @GameTest(template = "empty16", timeoutTicks = 120)
  public static void directItemOnlyInputPaysWithAndWithoutDiamond(GameTestHelper helper) {
    var controller = (MachineControllerBlockEntity) RecipeResourceRegressionGameTests.build(helper, false).owner();
    var bus = input(helper, controller);
    var names = List.of("first", "second");
    for (int slot = 0; slot < names.size(); slot++) {
      var iron = new ItemStack(Items.IRON_INGOT);
      iron.set(DataComponents.CUSTOM_NAME, Component.literal(names.get(slot)));
      bus.inventory().setStackInSlot(slot, iron);
    }

    var json = source();
    var optional = originalAndCopies(helper, json);
    json.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject().addProperty("optional", false);
    var required = originalAndCopies(helper, json);
    assertPayments(helper, controller, optional, Map.of(0, 1L, 1, 1L));
    assertNoMatch(helper, controller, required);

    bus.inventory().setStackInSlot(2, new ItemStack(Items.DIAMOND));
    var withDiamond = Map.of(0, 1L, 1, 1L, 2, 1L);
    assertPayments(helper, controller, optional, withDiamond);
    assertPayments(helper, controller, required, withDiamond);
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void exactGroupAndItemOnlyInputSharePhysicalIron(GameTestHelper helper) {
    var controller = (MachineControllerBlockEntity) RecipeResourceRegressionGameTests.build(helper, false).owner();
    var inventory = input(helper, controller).inventory();
    inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
    var named = new ItemStack(Items.IRON_INGOT);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("spare"));
    inventory.setStackInSlot(1, named);
    var json = source();
    var group = json.getAsJsonArray("ingredient_arrays").get(0).getAsJsonObject();
    group.addProperty("optional", false);
    group.getAsJsonArray("alternatives").get(0).getAsJsonObject().getAsJsonObject("item")
        .addProperty("id", "minecraft:iron_ingot");
    var recipes = originalAndCopies(helper, json);
    assertNoMatch(helper, controller, recipes);
    inventory.setStackInSlot(1, named.copyWithCount(2));
    assertPayments(helper, controller, recipes, Map.of(0, 1L, 1, 2L));
    RecipeResourceRegressionGameTests.assertMixedIronCheckerSlices(helper, controller, json);
    inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 2));
    for (var recipe : recipes) {
      var plan = search(helper, controller, recipe).plan().orElseThrow();
      helper.assertValueEqual(plan.maxRepeatableInputs(2), 1, "Four iron ingots cannot pay two three-ingot runs");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void defaultCopyKeepsAllSixtyFourGroups(GameTestHelper helper) {
    var json = source();
    var groups = json.getAsJsonArray("ingredient_arrays");
    var water = JsonParser.parseString("""
        {"alternatives":[{"resource":"fluid","fluid":{"id":"minecraft:water","amount":1},"amount":1}]}
        """);
    while (groups.size() < IngredientArrayData.MAX_GROUPS) {
      groups.add(water.deepCopy());
    }
    var copied = copy(helper, json, RecipeAdapterCustomization.NONE);
    helper.assertValueEqual(
        copied.ingredientArrays().inputs().size(),
        IngredientArrayData.MAX_GROUPS,
        "Copied group count");
    helper.assertTrue(copied.inputs().getFirst().ignoresComponents(), "Direct item-only input");
    helper.succeed();
  }

  private static JsonObject source() {
    return JsonParser
        .parseString(
            """
                {"type":"mmcece:machine","machine":"test:source","duration":5,
                 "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":2,"match":"item"}],
                 "ingredient_arrays":[{"optional":true,"alternatives":[{"resource":"item","item":{"id":"minecraft:diamond"},"amount":1}]}],
                 "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
                """)
        .getAsJsonObject();
  }

  private static MachinePortBlockEntity input(GameTestHelper helper, MachineControllerBlockEntity controller) {
    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(controller.structurePosition().offset(-1, 0, 1));
  }

  private static List<MachineRecipe> originalAndCopies(GameTestHelper helper, JsonObject json) {
    var nativeJson = json.deepCopy();
    nativeJson.addProperty("machine", TARGET.toString());
    var nativeRecipe = MachineRecipeCompiler
        .compile(MachineRecipeJson.decode(nativeJson, helper.getLevel().registryAccess()));
    return List.of(nativeRecipe, copy(helper, json, RecipeAdapterCustomization.NONE), copy(helper, json, TOOLTIP));
  }

  private static MachineRecipe copy(GameTestHelper helper, JsonObject json, RecipeAdapterCustomization customization) {
    var source = MachineRecipeJson.decode(json, helper.getLevel().registryAccess());
    var settings = new RecipeAdapterImport("copy", source.machineId(), List.of(), true, 0, 0, 0, customization);
    return MachineRecipeCompiler.compile(
        MachineRecipeCopies.copy(source, TARGET, settings, new RecipeAdapterCustomizations(settings.customization())));
  }

  private static void assertPayments(GameTestHelper helper, MachineControllerBlockEntity controller, List<MachineRecipe> recipes, Map<Integer, Long> expected) {
    var inventory = input(helper, controller).inventory();
    for (var recipe : recipes) {
      var result = search(helper, controller, recipe);
      helper.assertValueEqual(result.status(), RecipePlanner.Status.SELECTED, "Payable recipe");
      var counts = new HashMap<Integer, Long>();
      for (var deduction : result.plan().orElseThrow().deductions()) {
        int slot = deduction.slot().slot();
        var key = ItemResourceKey.of(inventory.getStackInSlot(slot), helper.getLevel().registryAccess());
        helper.assertValueEqual(deduction.key(), key, "Exact components of slot " + slot);
        counts.merge(slot, deduction.count(), Long::sum);
      }
      helper.assertValueEqual(counts, expected, "Physical deductions by inventory slot");
    }
  }

  private static void assertNoMatch(GameTestHelper helper, MachineControllerBlockEntity controller, List<MachineRecipe> recipes) {
    for (var recipe : recipes) {
      helper.assertValueEqual(
          search(helper, controller, recipe).status(),
          RecipePlanner.Status.NO_MATCH,
          "Missing required item");
    }
  }

  private static RecipeSearch.Result search(GameTestHelper helper, MachineControllerBlockEntity controller, MachineRecipe recipe) {
    var candidate = RecipeItemCallbackService.compilePrepared(recipe, ID, helper.getLevel().registryAccess());
    var catalog = RecipeCatalog
        .fromDefinitions(TARGET, List.of(candidate), Map.of(ID.toString(), recipe), () -> true, null, List.of());
    var search = RecipeSearch.begin(controller, catalog).orElseThrow();
    var result = search.advance(8192);
    for (int slice = 1; slice < 64 && result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED; slice++) {
      result = search.advance(8192);
    }
    return result;
  }
}
