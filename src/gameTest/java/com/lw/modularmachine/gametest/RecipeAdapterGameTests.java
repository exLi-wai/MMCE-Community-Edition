package com.lw.modularmachine.gametest;

import java.util.stream.Collectors;
import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterContext;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterImport;
import com.lw.modularmachine.api.recipe.adapter.RecipeAdapterTags;
import com.lw.modularmachine.machine.recipe.adapter.SmeltingRecipeAdapter;
import com.lw.modularmachine.machine.recipe.adapter.RecipeAdapterScriptSources;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class RecipeAdapterGameTests {
  @GameTest(template = "empty16", timeoutTicks = 160)
  public static void absent_energy_component_registers_without_paying_start_inputs(GameTestHelper helper) {
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_missing_resource_ports");
    var recipe = ResourceLocation.fromNamespaceAndPath("mmcece", "mmcece/test_missing_resource_energy");
    helper.assertTrue(
        MachineRecipeService.definition(helper.getLevel().getServer(), recipe).isPresent(),
        "Positive-energy recipe must register without a declared energy input component");
    var origin = helper.absolutePos(new BlockPos(8, 3, 8));
    var inputPosition = origin.offset(-1, 0, 1);
    var outputPosition = origin.offset(1, 0, 1);
    helper.getLevel().setBlockAndUpdate(
        origin,
        MachineControllerBlock
            .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    helper.getLevel().setBlockAndUpdate(
        inputPosition,
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        outputPosition,
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var controller = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    var input = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(inputPosition);
    var output = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(outputPosition);
    controller.bindMachine(machine);
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
    for (int attempt = 0; attempt < 200 && !controller.canCommitProduction(); attempt++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.succeedWhen(() -> {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
      helper.assertTrue(controller.canCommitProduction(), "Missing-resource fixture must be structurally confirmed");
      controller.serverTick();
      helper.assertTrue(
          controller.recipeRunStatus() == ItemRunEngine.Result.MISSING_ENERGY_INPUT,
          "Controller must report the absent energy input component: " + controller.recipeRunStatus());
      helper.assertTrue(
          controller.runningRecipe().isEmpty(),
          "Missing component must not create a paid run with frozen incomplete bindings");
      helper.assertTrue(
          input.inventory().getStackInSlot(0).is(Items.DIRT) && input.inventory().getStackInSlot(0).getCount() == 1,
          "Missing energy component consumed startup dirt");
      for (int slot = 0; slot < output.inventory().getSlots(); slot++) {
        helper
            .assertTrue(output.inventory().getStackInSlot(slot).isEmpty(), "Missing energy component produced output");
      }
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void vanilla_smelting_or_and_staged_tags_are_preserved(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    var registries = server.registryAccess();
    var sourceId = ResourceLocation.withDefaultNamespace("gold_nugget_from_smelting");
    var source = server.getRecipeManager().byKey(sourceId).orElseThrow();
    var adapter = new SmeltingRecipeAdapter();
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "adapter_smoke");
    var settings = new RecipeAdapterImport("smoke", SmeltingRecipeAdapter.ID, List.of(sourceId), false, 0, 32, 0);
    var gold = adapter.adapt(
        new RecipeAdapterContext(machine, settings, registries, List.of(source), RecipeAdapterTags.from(registries)));
    var expected = Set.of(
        "golden_pickaxe",
        "golden_shovel",
        "golden_axe",
        "golden_hoe",
        "golden_sword",
        "golden_helmet",
        "golden_chestplate",
        "golden_leggings",
        "golden_boots",
        "golden_horse_armor");
    var goldInputs = inputs(gold, registries);
    helper.assertTrue(
        goldInputs.equals(expected.stream().map(item -> "minecraft:" + item).collect(Collectors.toSet())),
        "Vanilla gold recycling must retain all ten ordinary OR inputs");
    for (var builder : gold) {
      var json = builder.toJson(registries);
      var output = json.getAsJsonArray("outputs").get(0).getAsJsonObject();
      helper.assertTrue(
          output.getAsJsonObject("stack").get("id").getAsString().equals("minecraft:gold_nugget")
              && output.get("amount").getAsInt() == 1,
          "Gold recycling output must remain one nugget");
    }
    var vanilla = new ArrayList<RecipeHolder<?>>();
    server.getRecipeManager().getAllRecipesFor(RecipeType.SMELTING).stream()
        .filter(recipe -> recipe.id().getNamespace().equals("minecraft")).forEach(vanilla::add);
    adapter.adapt(
        new RecipeAdapterContext(
            machine,
            new RecipeAdapterImport("all", SmeltingRecipeAdapter.ID, List.of(), true, 0, 0, 0),
            registries,
            vanilla,
            RecipeAdapterTags.from(registries)));

    var tagId = ResourceLocation.fromNamespaceAndPath("mmcece", "adapter_unbound_tag");
    var tagged = Ingredient.of(TagKey.create(Registries.ITEM, tagId));
    var inner = new Ingredient(new CompoundIngredient(List.of(tagged, Ingredient.of(Items.IRON_ORE))));
    var outer = new Ingredient(new CompoundIngredient(List.of(inner, Ingredient.of(Items.IRON_ORE))));
    var nestedId = ResourceLocation.fromNamespaceAndPath("mmcece", "adapter_nested_or");
    var nested = new RecipeHolder<>(
        nestedId,
        new SmeltingRecipe("", CookingBookCategory.MISC, outer, new ItemStack(Items.IRON_INGOT), 0, 200));
    var nestedSettings = new RecipeAdapterImport("nested", SmeltingRecipeAdapter.ID, List.of(nestedId), false, 0, 0, 0);
    var tags = new RecipeAdapterTags(Map.of(tagId, List.of(Items.IRON_ORE, Items.DEEPSLATE_IRON_ORE)));
    var expanded = adapter.adapt(new RecipeAdapterContext(machine, nestedSettings, registries, List.of(nested), tags));
    helper.assertTrue(
        inputs(expanded, registries).equals(Set.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")),
        "Nested OR must use staged tag members and deduplicate repeated item leaves");
    var empty = adapter.adapt(
        new RecipeAdapterContext(
            machine,
            nestedSettings,
            registries,
            List.of(nested),
            new RecipeAdapterTags(Map.of())));
    helper.assertTrue(
        inputs(empty, registries).equals(Set.of("minecraft:iron_ore")),
        "Empty staged tag must add no BARRIER or stale member to an OR");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void crt_named_kubejs_adapter_builders_publish_only_built_declarations(GameTestHelper helper) {
    if (!Boolean.getBoolean("mmcece.adapter.kubejsSmoke")) {
      helper.succeed();
      return;
    }
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_missing_resource_ports");
    var declarations = RecipeAdapterScriptSources.snapshot().imports().getOrDefault(machine, List.of());
    helper.assertTrue(
        declarations.stream().noneMatch(declaration -> declaration.id().equals("not_built")),
        "A KubeJS adapter builder without build() must not publish a declaration");
    var declaration = declarations.stream().filter(value -> value.id().equals("crt_naming_smoke")).findFirst()
        .orElse(null);
    helper.assertTrue(declaration != null, "The real KubeJS register/create/build script did not publish its adapter");
    var source = ResourceLocation.withDefaultNamespace("iron_ingot_from_smelting_iron_ore");
    helper.assertTrue(
        declaration.adapter().equals(ResourceLocation.withDefaultNamespace("furnace"))
            && declaration.energyPerTick() == 32
            && declaration.duration() == 20
            && declaration.priority() == 5
            && !declaration.allRecipes()
            && declaration.recipes().equals(List.of(source)),
        "CRT-named KubeJS setter chain or source array conversion changed its values");
    var server = helper.getLevel().getServer();
    var holder = server.getRecipeManager().byKey(source).orElseThrow();
    var context = new RecipeAdapterContext(
        machine,
        declaration,
        server.registryAccess(),
        List.of(holder),
        RecipeAdapterTags.from(server.registryAccess()));
    var imported = context.builder(source, "minecraft:iron_ore", 200).id();
    var recipe = MachineRecipeService.definition(server, imported).orElse(null);
    helper.assertTrue(recipe != null, "The KubeJS declaration did not generate a registered native recipe");
    helper.assertTrue(
        recipe.machineId().equals(machine)
            && recipe.energyPerTick() == 32
            && recipe.duration() == 20
            && recipe.priority() == 5,
        "The native recipe did not retain accumulated energy or configured duration/priority");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void crt_named_kubejs_recipe_builder_preserves_id_rows_and_last_requirement_settings(GameTestHelper helper) {
    if (!Boolean.getBoolean("mmcece.adapter.kubejsSmoke")) {
      helper.succeed();
      return;
    }
    var server = helper.getLevel().getServer();
    var recipeId = ResourceLocation.fromNamespaceAndPath("kubejs", "builder_smoke/iron_to_gold");
    var recipe = MachineRecipeService.definition(server, recipeId).orElse(null);
    helper.assertTrue(recipe != null, "The real KubeJS RecipeBuilder script did not publish its exact requested ID");
    helper.assertTrue(
        recipe.machineId().equals(ResourceLocation.fromNamespaceAndPath("mmcece", "test_missing_resource_ports"))
            && recipe.duration() == 100
            && !recipe.parallelized(),
        "RecipeBuilder changed its machine, processing time or parallelized setting");
    helper.assertTrue(
        recipe.inputs().size() == 1 && recipe.outputs().size() == 1,
        "RecipeBuilder did not preserve its single input and output rows");
    var input = recipe.inputs().getFirst();
    var output = recipe.outputs().getFirst();
    helper.assertTrue(
        input.amount() == 2
            && input.chance() == 1
            && input.selector().isEmpty()
            && !input.ignoresComponents()
            && ItemStack.isSameItemSameComponents(input.displayStack(), new ItemStack(Items.IRON_INGOT)),
        "Counted input or default exact components changed; last output settings must not affect the input");
    helper.assertTrue(
        output.amount() == 1
            && output.chance() == 0.5
            && output.selector().equals("gold_out")
            && !output.ignoresComponents()
            && ItemStack.isSameItemSameComponents(output.displayStack(), new ItemStack(Items.GOLD_INGOT)),
        "RecipeBuilder changed its output components, chance, selector or amount");
    helper.assertTrue(
        server.getRecipeManager().byKey(recipeId).isPresent(),
        "RecipeBuilder did not keep the user KubeJS recipe ID in the actual manager");
    helper.assertTrue(
        MachineRecipeService
            .definition(server, ResourceLocation.fromNamespaceAndPath("kubejs", "mmcece/builder_smoke/iron_to_gold"))
            .isEmpty(),
        "RecipeBuilder must not replace the requested ID with its internal serialization prefix");
    for (var id : List
        .of("mmcece:builder_short_id", "my_pack:gold", "minecraft:builder_gold", "mmcece:mmcece/builder_gold")) {
      var requestedId = ResourceLocation.parse(id);
      var custom = MachineRecipeService.definition(server, requestedId).orElse(null);
      helper.assertTrue(
          custom != null && custom.duration() == 80 && server.getRecipeManager().byKey(requestedId).isPresent(),
          "RecipeBuilder did not publish the custom or default namespace ID unchanged: " + id);
    }
    helper.succeed();
  }

  private static Set<String> inputs(List<MachineRecipeBuilder> recipes, HolderLookup.Provider registries) {
    var values = new HashSet<String>();
    for (var builder : recipes) {
      var input = builder.toJson(registries).getAsJsonArray("inputs").get(0).getAsJsonObject();
      if (!input.get("match").getAsString().equals("item")) {
        throw new IllegalStateException("Converted smelting inputs must preserve item-only matching");
      }
      values.add(input.getAsJsonObject("stack").get("id").getAsString());
    }
    return values;
  }
}
