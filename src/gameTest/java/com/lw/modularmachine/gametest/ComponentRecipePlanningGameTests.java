package com.lw.modularmachine.gametest;

import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.ItemRecipeCatalog;
import com.lw.modularmachine.machine.recipe.ItemRecipePlan;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.ItemResourceSnapshot;
import com.lw.modularmachine.machine.recipe.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipePlanner;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class ComponentRecipePlanningGameTests {
  private static final ResourceLocation MACHINE = ResourceLocation
      .fromNamespaceAndPath("mmcece", "test_recipe_resources");

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void exact_keys_ignore_count_but_preserve_components_and_defensive_prototypes(GameTestHelper helper) {
    var a = named(Items.DIRT, "A", 3);
    var b = named(Items.DIRT, "B", 3);
    var key = ItemResourceKey.of(a);
    var equivalent = ItemResourceKey.of(named(Items.DIRT, "A", 50));
    helper.assertTrue(
        key.equals(equivalent) && key.hashCode() == equivalent.hashCode(),
        "Equal component keys differ by count/hash");
    helper.assertTrue(
        !key.equals(ItemResourceKey.of(b)) && !key.equals(ItemResourceKey.of(new ItemStack(Items.DIRT))),
        "Names/default components collapsed");
    var map = new HashMap<ItemResourceKey, Integer>();
    map.put(key, 1);
    helper.assertTrue(map.get(equivalent) == 1, "Equivalent component key cannot retrieve counted resources");
    a.set(DataComponents.CUSTOM_NAME, Component.literal("changed source"));
    key.stack(1).set(DataComponents.CUSTOM_NAME, Component.literal("changed copy"));
    ((MutableComponent) equivalent.stack(1).get(DataComponents.CUSTOM_NAME)).append("nested mutation");
    var nested = named(Items.DIRT, "nested", 1);
    var frozen = ItemResourceKey.of(nested);
    ((MutableComponent) nested.get(DataComponents.CUSTOM_NAME)).append("changed without replacing component");
    helper.assertTrue(frozen.matches(named(Items.DIRT, "nested", 1)), "Key shares nested mutable component data");
    helper.assertTrue(key.matches(named(Items.DIRT, "A", 1)), "Key exposes mutable prototype");
    helper.assertTrue(
        ItemResourceKey.of(new ItemStack(Items.ENDER_PEARL)).maxStackSize() == 16,
        "Native pearl limit lost");
    var transientStack = new ItemStack(Items.DIRT);
    transientStack.set(DataComponents.CREATIVE_SLOT_LOCK, Unit.INSTANCE);
    try {
      helper.assertTrue(
          ItemResourceKey.of(transientStack).matches(transientStack),
          "Nonpersistent component disappeared silently");
    } catch (IllegalArgumentException expected) {
      helper.assertTrue(
          transientStack.has(DataComponents.CREATIVE_SLOT_LOCK),
          "Unsupported capture modified the original item");
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void input_matching_does_not_merge_distinct_components_of_the_same_item(GameTestHelper helper) {
    var controller = build(helper);
    var in = port(helper, controller, "item_in");
    in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 64));
    var catalog = catalog(recipe("a", 1, ItemResourceKey.of(named(Items.DIRT, "A", 1)), 1, key(Items.IRON_INGOT), 1));
    helper.assertTrue(
        search(helper, controller, catalog).advance(1000).status() == RecipePlanner.Status.NO_MATCH,
        "Plain dirt satisfied named input");
    in.inventory().setStackInSlot(1, named(Items.DIRT, "A", 2));
    in.inventory().setStackInSlot(2, named(Items.DIRT, "B", 20));
    var plan = plan(helper, controller, catalog);
    helper.assertTrue(
        plan.deductions().size() == 1
            && plan.deductions().getFirst().slot().slot() == 1
            && plan.deductions().getFirst().count() == 1,
        "Wrong component slot selected");
    helper.assertTrue(
        in.inventory().getStackInSlot(0).getCount() == 64 && in.inventory().getStackInSlot(1).getCount() == 2,
        "Read-only planning deducted input");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void duplicate_inputs_and_output_b_occupancy_produce_exact_physical_slot_plans(GameTestHelper helper) {
    var controller = build(helper);
    var in = port(helper, controller, "item_in");
    var out = port(helper, controller, "item_out");
    in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    in.inventory().setStackInSlot(1, new ItemStack(Items.DIRT, 2));
    fill(out, new ItemStack(Items.DIAMOND, 63));
    var b = new RecipeCandidate<>(
        "b",
        1,
        List.of(new RecipeCandidate.Input<>(key(Items.DIRT), 2), new RecipeCandidate.Input<>(key(Items.DIRT), 3)),
        List.of(new RecipeCandidate.Output<>(key(Items.DIAMOND), 2)));
    var catalog = catalog(recipe("a", 10, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1), b);
    var plan = plan(helper, controller, catalog);
    var ticket = RecipeExecutionTicket.capture(controller).orElseThrow();
    helper.assertTrue(
        plan.recipe().id().equals("b")
            && plan.deductions().stream().mapToInt(ItemRecipePlan.Deduction::count).sum() == 5,
        "Duplicate inputs or priority/output rule failed");
    helper.assertTrue(
        plan.deductions().stream().allMatch(d -> logicalPartId(ticket, d.slot().partId()).equals("item_in"))
            && plan.placements().stream().allMatch(p -> logicalPartId(ticket, p.slot().partId()).equals("item_out")),
        "World position replaced stable logical slot");
    helper.assertTrue(
        plan.placements().stream().mapToInt(ItemRecipePlan.Placement::count).sum() == 2
            && plan.validateForStart(catalog),
        "Physical plan does not fit current storage");
    helper.assertTrue(
        in.inventory().getStackInSlot(0).getCount() == 3 && out.inventory().getStackInSlot(0).getCount() == 63,
        "Planning/validation wrote inventories");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void output_components_participate_in_fallback_selection(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var out = port(helper, controller, "item_out");
    fill(out, new ItemStack(Items.DIRT, 63));
    out.inventory().setStackInSlot(0, named(Items.DIRT, "A", 63));
    var named = ItemResourceKey.of(named(Items.DIRT, "A", 1));
    var catalog = catalog(
        recipe("named", 10, key(Items.DIRT), 1, named, 2),
        recipe("plain", 1, key(Items.DIRT), 1, key(Items.DIRT), 1));
    var plan = plan(helper, controller, catalog);
    helper.assertTrue(
        plan.recipe().id().equals("plain") && plan.placements().getFirst().slot().slot() != 0,
        "Component-incompatible output was treated as stackable");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void real_item_stack_limits_are_used_for_existing_and_empty_output_slots(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var out = port(helper, controller, "item_out");
    fill(out, new ItemStack(Items.STONE, 64));
    out.inventory().setStackInSlot(0, new ItemStack(Items.ENDER_PEARL, 15));
    var catalog = catalog(recipe("pearls", 1, key(Items.DIRT), 1, key(Items.ENDER_PEARL), 2));
    helper.assertTrue(
        search(helper, controller, catalog).advance(1000).status() == RecipePlanner.Status.NO_MATCH,
        "Pearls exceeded their 16-item stack limit");
    out.inventory().setStackInSlot(1, ItemStack.EMPTY);
    var plan = plan(helper, controller, catalog);
    helper.assertTrue(
        plan.placements().size() == 2 && plan.placements().stream().allMatch(p -> p.count() == 1),
        "Existing/empty pearl capacity planning differs");
    helper.assertTrue(out.inventory().getStackInSlot(1).isEmpty(), "Future output inserted during admission");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void component_override_stack_limit_splits_output_across_distinct_slots(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var out = port(helper, controller, "item_out");
    fill(out, new ItemStack(Items.STONE, 64));
    out.inventory().setStackInSlot(0, ItemStack.EMPTY);
    out.inventory().setStackInSlot(1, ItemStack.EMPTY);
    var single = new ItemStack(Items.STICK);
    single.set(DataComponents.MAX_STACK_SIZE, 1);
    var plan = plan(
        helper,
        controller,
        catalog(recipe("single", 0, key(Items.DIRT), 1, ItemResourceKey.of(single), 2)));
    helper.assertTrue(
        plan.placements().size() == 2
            && plan.placements().stream().allMatch(p -> p.count() == 1)
            && !plan.placements().get(0).slot().equals(plan.placements().get(1).slot()),
        "Component stack limit or slot exclusivity lost");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void sliced_native_search_keeps_progress_and_never_mutates_storage(GameTestHelper helper) {
    var controller = build(helper);
    var in = port(helper, controller, "item_in");
    in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var catalog = catalog(recipe("a", 10, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1));
    var search = search(helper, controller, catalog);
    ItemRecipeSearch.Result result;
    int calls = 0;
    do {
      result = search.advance(1);
      helper.assertTrue(result.workUsed() <= 1 && ++calls < 1000, "Search restarted or exceeded its work slice");
    } while (result.status() == RecipePlanner.Status.BUDGET_EXHAUSTED);
    helper.assertTrue(
        result.plan().isPresent() && search.totalWork() == calls && in.inventory().getStackInSlot(0).getCount() == 1,
        "Sliced native search failed or consumed input");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "component_plan_resume", timeoutTicks = 80)
  public static void unchanged_item_admission_can_be_revalidated_on_a_new_tick_without_reusing_an_old_tick_proof(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 3));
    var controller = RecipeResourceTicketGameTests.build(helper, origin, MachineOrientation.IDENTITY);
    var in = port(helper, controller, "item_in");
    in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var catalog = catalog(recipe("a", 1, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1));
    // First BE onLoad and other batches' definition reloads are real structure
    // invalidations.
    // Wait for initial notifications before capturing the stable continuation case.
    helper.runAfterDelay(2, () -> {
      for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
        StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
      }
      var registration = controller.registration();
      long revision = registration.verifier.revision();
      var plan = plan(helper, controller, catalog);
      helper.runAfterDelay(1, () -> {
        helper.assertTrue(
            plan.validateForStart(catalog) && in.inventory().getStackInSlot(0).getCount() == 2,
            "Unchanged snapshot could not refresh: same registration=" + (registration == controller
                .registration()) + ", old revision=" + revision + ", " + controller.statusMessage().getString());
        in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
        helper.assertTrue(!plan.validateForStart(catalog), "Changed revision refreshed an old item plan");
        helper.succeed();
      });
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void structural_reverification_does_not_refresh_an_old_item_plan_even_with_identical_inventory(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var catalog = catalog(recipe("a", 1, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1));
    var old = plan(helper, controller, catalog);
    StructureService.requestAudit(controller);
    for (int i = 0; i < 200 && !controller.canCommitProduction(); i++) {
      StructureService.advance(helper.getLevel(), 512, 64, 50_000_000L);
    }
    helper.assertTrue(
        controller.canCommitProduction() && !old.validateForStart(catalog),
        "New structure proof refreshed an old planning snapshot");
    helper.assertTrue(
        plan(helper, controller, catalog).validateForStart(catalog),
        "Fresh plan could not use current structure proof");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void live_input_or_output_changes_invalidate_the_old_admission_and_reselect_priority(GameTestHelper helper) {
    var controller = build(helper);
    var in = port(helper, controller, "item_in");
    var out = port(helper, controller, "item_out");
    in.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    fill(out, new ItemStack(Items.DIAMOND, 63));
    var catalog = catalog(
        recipe("a", 10, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1),
        recipe("b", 1, key(Items.DIRT), 1, key(Items.DIAMOND), 1));
    var old = plan(helper, controller, catalog);
    helper.assertTrue(old.recipe().id().equals("b"), "B occupancy did not select B");
    out.inventory().setStackInSlot(0, ItemStack.EMPTY);
    helper.assertTrue(
        !old.validateForStart(catalog) && plan(helper, controller, catalog).recipe().id().equals("a"),
        "Output-only change failed to re-evaluate priority");
    var current = plan(helper, controller, catalog);
    in.inventory().getStackInSlot(0).set(DataComponents.CUSTOM_NAME, Component.literal("direct change"));
    helper.assertTrue(!current.validateForStart(catalog), "Unnotified component change authorized old deduction");
    in.inventory().setStackInSlot(0, named(Items.DIRT, "nested", 1));
    var nestedCatalog = catalog(
        recipe("nested", 0, ItemResourceKey.of(named(Items.DIRT, "nested", 1)), 1, key(Items.IRON_INGOT), 1));
    var nestedPlan = plan(helper, controller, nestedCatalog);
    ((MutableComponent) in.inventory().getStackInSlot(0).get(DataComponents.CUSTOM_NAME))
        .append("direct nested change");
    helper
        .assertTrue(!nestedPlan.validateForStart(nestedCatalog), "Nested component mutation retained source authority");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void catalog_machine_and_orientation_binding_are_checked_before_start_admission(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var recipe = recipe("a", 1, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1);
    var catalog = catalog(recipe);
    var plan = plan(helper, controller, catalog);
    helper.assertTrue(!plan.validateForStart(catalog(recipe)), "New recipe catalog inherited previous admission");
    helper.assertTrue(
        ItemRecipeSearch.begin(
            controller,
            new ItemRecipeCatalog(ResourceLocation.fromNamespaceAndPath("mmcece", "other_machine"), List.of(recipe)))
            .isEmpty(),
        "Recipe catalog matched the wrong machine");
    StructureService.reorient(controller, MachineOrientation.of(Direction.NORTH, 1));
    helper.assertTrue(!plan.validateForStart(catalog), "Pending orientation authorized item admission");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void detached_search_is_pure_but_native_bridge_and_validation_reject_worker_threads(GameTestHelper helper) {
    var controller = build(helper);
    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var inputKey = key(Items.DIRT);
    var outputKey = key(Items.IRON_INGOT);
    var catalog = catalog(recipe("a", 0, inputKey, 1, outputKey, 1));
    var search = search(helper, controller, catalog);
    var pure = RecipePlanner.prepare(
        new ItemResourceSnapshot<>(
            0,
            Map.of(inputKey, 1L),
            List.of(new ItemResourceSnapshot.OutputSlot<ItemResourceKey>(null, 0, 64, Map.of(outputKey, 64L)))),
        catalog.recipes());
    helper.assertTrue(
        CompletableFuture.supplyAsync(() -> pure.advance(1000).plan().isPresent()).join(),
        "Detached planner required a live world");
    boolean bridgeDenied = CompletableFuture.supplyAsync(() -> {
      try {
        search.advance(1);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    var plan = search.advance(1000).plan().orElseThrow();
    boolean denied = CompletableFuture.supplyAsync(() -> {
      try {
        plan.validateForStart(catalog);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        bridgeDenied && denied && plan.validateForStart(catalog),
        "Worker validation or main-thread handback failed");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 100)
  public static void item_deduction_and_output_slot_addresses_stay_local_across_all_24_poses(GameTestHelper helper) {
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var catalog = catalog(recipe("a", 1, key(Items.DIRT), 1, key(Items.IRON_INGOT), 1));
    for (var orientation : MachineOrientation.all()) {
      var controller = RecipeResourceTicketGameTests.build(helper, origin, orientation);
      port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
      var plan = plan(helper, controller, catalog);
      var ticket = RecipeExecutionTicket.capture(controller).orElseThrow();
      helper.assertTrue(
          logicalPartId(ticket, plan.deductions().getFirst().slot().partId()).equals("item_in")
              && plan.deductions().getFirst().slot().slot() == 0
              && logicalPartId(ticket, plan.placements().getFirst().slot().partId()).equals("item_out")
              && plan.validateForStart(catalog),
          "Rotation reordered logical slots");
      for (var port : ticket.ports()) {
        helper.getLevel().setBlockAndUpdate(port.position(), Blocks.AIR.defaultBlockState());
      }
      helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
    }
    helper.succeed();
  }

  private static MachineControllerBlockEntity build(GameTestHelper helper) {
    return RecipeResourceTicketGameTests
        .build(helper, helper.absolutePos(new BlockPos(7, 5, 7)), MachineOrientation.IDENTITY);
  }

  private static String logicalPartId(RecipeExecutionTicket ticket, String address) {
    return ticket.ports().stream().filter(port -> port.partId().equals(address))
        .map(RecipeExecutionTicket.PortSnapshot::logicalPartId).findFirst().orElseThrow();
  }

  private static MachinePortBlockEntity port(GameTestHelper helper, MachineControllerBlockEntity controller, String id) {
    return RecipeResourceTicketGameTests.port(helper, controller, id);
  }

  private static ItemResourceKey key(Item item) {
    return ItemResourceKey.of(new ItemStack(item));
  }

  private static ItemStack named(Item item, String name, int count) {
    var stack = new ItemStack(item, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    return stack;
  }

  private static RecipeCandidate<ItemResourceKey> recipe(String id, int priority, ItemResourceKey in, long amount, ItemResourceKey out, long count) {
    return new RecipeCandidate<>(
        id,
        priority,
        List.of(new RecipeCandidate.Input<>(in, amount)),
        List.of(new RecipeCandidate.Output<>(out, count)));
  }

  @SafeVarargs
  private static ItemRecipeCatalog catalog(RecipeCandidate<ItemResourceKey>... recipes) {
    return new ItemRecipeCatalog(MACHINE, List.of(recipes));
  }

  private static ItemRecipeSearch search(GameTestHelper helper, MachineControllerBlockEntity controller, ItemRecipeCatalog catalog) {
    var search = ItemRecipeSearch.begin(controller, catalog);
    helper.assertTrue(search.isPresent(), "Could not capture native item inventory");
    return search.orElseThrow();
  }

  private static ItemRecipePlan plan(GameTestHelper helper, MachineControllerBlockEntity controller, ItemRecipeCatalog catalog) {
    var result = search(helper, controller, catalog).advance(10_000);
    helper.assertTrue(
        result.status() == RecipePlanner.Status.SELECTED,
        "Expected a complete exact-component plan: " + result.status());
    return result.plan().orElseThrow();
  }

  private static void fill(MachinePortBlockEntity port, ItemStack stack) {
    for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
      port.inventory().setStackInSlot(slot, stack.copy());
    }
  }
}
