package com.lw.modularmachine.integration.kubejs;

import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.RecipePreparationEvents;
import com.lw.modularmachine.api.recipe.RegisterMachineRecipesEvent;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipePreparationHandlers;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "mmcece")
@GameTestHolder("mmcece_typed_reload")
@PrefixGameTestTemplate(false)
public final class TypedRecipeReloadGameTests {
  private static final ResourceLocation JAVA = ResourceLocation.parse("mmcece_reload:java");
  private static final ResourceLocation WINNER = ResourceLocation.parse("mmcece_reload:winner");
  private static final ResourceLocation NATIVE = ResourceLocation.parse("mmcece:mmcece/test_missing_resource_energy");
  private static final ResourceLocation ORDINARY = ResourceLocation.parse("minecraft:stick");
  private static final ResourceLocation REPLACED = ResourceLocation.parse("mmcece:mmcece/test_fluid_row_routes");
  private static final ResourceLocation CONDITIONAL = ResourceLocation.parse("mmcece_reload:disabled");
  private static final ResourceLocation EDITED = ResourceLocation.parse("mmcece_reload:edited");
  private static final ResourceLocation EDITED_DISABLED = ResourceLocation.parse("mmcece_reload:edited_disabled");
  private static final ResourceLocation REMOVED = ResourceLocation.parse("mmcece_reload:removed");
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:test_missing_resource_ports");
  private static final Map<RecipeManager, Map<ResourceLocation, Integer>> EARLY_DURATIONS = Collections
      .synchronizedMap(new WeakHashMap<>());
  private static int generations;

  /**
   * Called during the KubeJS event, before Rhino wraps any of its added recipes.
   */
  public static int addedRecipeDuration(RecipesKubeEvent event, String id) {
    for (var entry : event.addedRecipes) {
      if (entry.getId().equals(id)) {
        return entry.json.get("duration").getAsInt();
      }
    }
    throw new IllegalArgumentException("Missing KubeJS recipe " + id);
  }

  @SubscribeEvent
  public static void register(RegisterMachineRecipesEvent event) {
    if (Boolean.getBoolean("mmcece.test.typedReload")) {
      generations++;
      event.add(
          MachineRecipeBuilder.create(JAVA, MACHINE, generations).itemInput(new ItemStack(Items.DIRT), 1)
              .itemOutput(new ItemStack(Items.IRON_INGOT), 1));
    }
  }

  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public static void beforeMachineCatalog(AddReloadListenerEvent event) {
    if (Boolean.getBoolean("mmcece.test.typedReload")) {
      var manager = event.getServerResources().getRecipeManager();
      event.addListener((ResourceManagerReloadListener) resources -> {
        var durations = new HashMap<ResourceLocation, Integer>();
        for (var id : List.of(WINNER, EDITED, CONDITIONAL, EDITED_DISABLED, REMOVED)) {
          var holder = manager.byKey(id).orElse(null);
          if (holder != null && holder.value() instanceof MachineRecipe recipe) {
            durations.put(id, recipe.duration());
          }
        }
        EARLY_DURATIONS.put(manager, durations);
      });
    }
  }

  @GameTest(template = "empty16", timeoutTicks = 600)
  public static void typed_winners_and_callbacks_survive_real_recipe_reload(GameTestHelper helper) {
    if (!Boolean.getBoolean("mmcece.test.typedReload")) {
      helper.succeed();
      return;
    }
    var server = helper.getLevel().getServer();
    checkPublished(helper);
    int count = generations;
    long callbackGeneration = RecipePreparationHandlers.epoch();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Recipe reload failed: " + failure);
            return;
          }
          helper.assertValueEqual(generations, count + 1, "Java registration event fired once on reload");
          checkPublished(helper);
          helper.assertTrue(RecipePreparationHandlers.epoch() > callbackGeneration, "New callbacks were installed");
          helper.succeed();
        })));
  }

  private static void checkPublished(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    var occurrences = new HashMap<ResourceLocation, Integer>();
    for (var holder : server.getRecipeManager().getRecipes()) {
      occurrences.merge(holder.id(), 1, Integer::sum);
    }
    var guardedWinner = RecipePreparationHandlers.has(MACHINE, WINNER, RecipePreparationEvents.Type.PRE_CHECK);
    var early = EARLY_DURATIONS.remove(server.getRecipeManager());
    helper.assertTrue(early != null, "The early reload listener must observe the installed manager");
    helper.assertValueEqual(
        early,
        Map.of(WINNER, guardedWinner ? 41 : 42, EDITED, 80),
        "Selected machine recipes visible immediately after RecipeManager.apply");
    var durations = Map
        .of(JAVA, generations, WINNER, guardedWinner ? 41 : 42, NATIVE, 20, ORDINARY, 31, REPLACED, 32, EDITED, 80);
    for (var entry : durations.entrySet()) {
      var id = entry.getKey();
      var recipe = MachineRecipeService.definition(server, id).orElseThrow();
      helper.assertTrue(
          server.getRecipeManager().byKey(id).orElseThrow().value() == recipe,
          "Stale recipe holder for " + id);
      helper.assertValueEqual(occurrences.getOrDefault(id, 0), 1, "One holder for " + id);
      helper.assertValueEqual(
          recipe.duration(),
          entry.getValue(),
          "Winner values and callback ownership agree for " + id);
      if (id.equals(EDITED)) {
        helper.assertTrue(
            RecipePreparationHandlers.has(MACHINE, id, RecipePreparationEvents.Type.PRE_CHECK),
            "The edited declaration lost its callback");
      } else if (!id.equals(WINNER)) {
        helper.assertFalse(
            RecipePreparationHandlers.has(MACHINE, id, RecipePreparationEvents.Type.PRE_CHECK),
            "A discarded declaration leaked its callback for " + id);
      }
    }
    for (var id : List.of(CONDITIONAL, EDITED_DISABLED, REMOVED)) {
      helper.assertTrue(
          server.getRecipeManager().byKey(id).isEmpty()
              && MachineRecipeService.definition(server, id).isEmpty()
              && !RecipePreparationHandlers.has(MACHINE, id, RecipePreparationEvents.Type.PRE_CHECK),
          "A removed or disabled KubeJS recipe reached the catalog or installed a callback: " + id);
    }
  }
}
