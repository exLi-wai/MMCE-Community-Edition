package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonParser;
import com.lw.modularmachine.api.recipe.RecipeItemCallbacks;
import com.lw.modularmachine.api.recipe.RegisterMachineRecipesEvent;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "mmcece")
@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class CheckedItemPaymentGameTests {
  private static final ResourceLocation ID = ResourceLocation.parse("typed_test:checked_iron");
  private static final ResourceLocation CHECKER = ResourceLocation.parse("typed_test:only_b");
  private static final ResourceLocation PLAIN = ResourceLocation.parse("typed_test:plain_iron");
  private static final ResourceLocation PASS = ResourceLocation.parse("typed_test:checked_iron_pass");
  private static final ResourceLocation PASS_CHECKER = ResourceLocation.parse("typed_test:iron_pass");
  private static final ResourceLocation FACTORY_CHECKER = ResourceLocation.parse("typed_test:factory_fixed_checker");
  private static final ResourceLocation FIXED = ResourceLocation.parse("typed_test:factory_fixed_iron");

  @SubscribeEvent
  public static void register(RegisterMachineRecipesEvent event) {
    if (!List.of(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(",")).contains("mmcece_adapter")) {
      return;
    }
    var json = JsonParser
        .parseString(
            """
                {"machine":"mmcece:test_recipe_resources","duration":5,
                 "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1,"match":"item"}],
                 "item_callbacks":[{"io":"input","row":0,"checker":"typed_test:only_b"}],
                 "ingredient_arrays":[{"chance":0,"alternatives":[{"resource":"item","item":{"id":"minecraft:iron_ingot"},"amount":1}]}],
                 "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
                """)
        .getAsJsonObject();
    event.add(ID, MachineRecipeJson.decode(json, event.registries()));
    json.getAsJsonArray("item_callbacks").get(0).getAsJsonObject().addProperty("checker", PASS_CHECKER.toString());
    event.add(PASS, MachineRecipeJson.decode(json, event.registries()));
    json.remove("item_callbacks");
    event.add(PLAIN, MachineRecipeJson.decode(json, event.registries()));
    event.add(FIXED, MachineRecipeJson.decode(fixedRecipeJson(), event.registries()));
  }

  private static com.google.gson.JsonObject fixedRecipeJson() {
    return JsonParser.parseString("""
        {"machine":"mmcece:test_recipe_resources","duration":5,
         "inputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1,"match":"item","parallel_unaffected":true}],
         "item_callbacks":[{"io":"input","row":0,"checker":"typed_test:factory_fixed_checker"}],
         "ingredient_arrays":[{"alternatives":[{"resource":"item","item":{"id":"minecraft:diamond"},"amount":1}]}],
         "outputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}]}
        """).getAsJsonObject();
  }

  @GameTest(template = "empty16")
  public static void zeroChanceExactGroupKeepsCheckedAliasPaymentOnNamedIron(GameTestHelper helper) {
    var ticket = RecipeResourceRegressionGameTests.build(helper, false);
    var controller = (MachineControllerBlockEntity) ticket.owner();
    var input = (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(controller.structurePosition().offset(-1, 0, 1));
    var inventory = input.inventory();
    var named = new ItemStack(Items.IRON_INGOT, 2);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
    inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
    inventory.setStackInSlot(1, named);
    inventory.setStackInSlot(2, new ItemStack(Items.IRON_INGOT));
    var seen = new ArrayList<String>();
    RecipeItemCallbacks.registerChecker(CHECKER, (context, stack) -> {
      String name = stack.getHoverName().getString();
      seen.add(name);
      return name.equals("B");
    });
    try {
      var plan = search(helper, controller, ID, 8192);
      var namedKey = ItemResourceKey.of(named, helper.getLevel().registryAccess());
      assertNamedDebit(helper, plan, namedKey, 1);
      assertNamedDebit(helper, plan.scaled(2, 65536).plan(), namedKey, 2);
      assertNamedDebit(helper, RecipeStartPlan.combineInputs(List.of(plan, plan), 65536).plan(), namedKey, 2);
      var result = AtomicRecipeStarter.start(plan);
      for (int slice = 1; slice < 64 && result == AtomicRecipeStarter.Result.BUSY; slice++) {
        result = AtomicRecipeStarter.start(plan);
      }
      helper.assertTrue(result.committed(), "Checked start committed: " + result);
      helper
          .assertTrue(!seen.isEmpty() && seen.stream().allMatch("B"::equals), "Only the named iron passed the checker");
      helper.assertValueEqual(inventory.getStackInSlot(0).getCount(), 1, "Reserved zero-chance stack retained");
      helper.assertValueEqual(inventory.getStackInSlot(1).getCount(), 1, "One approved iron paid");
      helper.assertValueEqual(inventory.getStackInSlot(2).getCount(), 1, "Other ordinary iron retained");
    } finally {
      RecipeItemCallbackService.removeChecker(CHECKER);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void alwaysTrueCheckerAndPlainRecipeReserveTheSamePhysicalInputs(GameTestHelper helper) {
    var ticket = RecipeResourceRegressionGameTests.build(helper, false);
    var controller = (MachineControllerBlockEntity) ticket.owner();
    var inventory = ((MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(controller.structurePosition().offset(-1, 0, 1))).inventory();
    var named = new ItemStack(Items.IRON_INGOT, 2);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
    inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
    inventory.setStackInSlot(1, named);
    inventory.setStackInSlot(2, new ItemStack(Items.IRON_INGOT));
    RecipeItemCallbacks.registerChecker(PASS_CHECKER, (context, stack) -> true);
    try {
      var plain = search(helper, controller, PLAIN, 8192);
      var checked = search(helper, controller, PASS, 8192);
      var namedKey = ItemResourceKey.of(named, helper.getLevel().registryAccess());
      for (var plan : List.of(plain, checked)) {
        assertNamedDebit(helper, plan, namedKey, 1);
        assertNamedDebit(helper, plan.scaled(2, 65536).plan(), namedKey, 2);
        assertNamedDebit(helper, RecipeStartPlan.combineInputs(List.of(plan, plan), 65536).plan(), namedKey, 2);
      }
      var incremental = search(helper, controller, PASS, 1);
      helper.assertValueEqual(
          incremental.deductions(),
          checked.deductions(),
          "Small and large search budgets select the same slots");
      helper.assertTrue(AtomicRecipeStarter.start(plain).committed(), "Plain recipe committed");
      helper.assertValueEqual(inventory.getStackInSlot(0).getCount(), 1, "Reserved exact iron retained");
      helper.assertValueEqual(inventory.getStackInSlot(1).getCount(), 1, "Named iron consumed");
    } finally {
      RecipeItemCallbackService.removeChecker(PASS_CHECKER);
    }
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void uncheckedFactoryBatchPaysTheSameNamedStacks(GameTestHelper helper) {
    var ticket = RecipeResourceRegressionGameTests.build(helper, true);
    var factory = (FactoryControllerBlockEntity) ticket.owner();
    String thread = "payment_check";
    var state = new FactoryThreadState(
        thread,
        factory.structureMachineId(),
        PLAIN,
        true,
        0,
        2,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        new UpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 1));
    factory.threadConfiguration().replaceThreads(List.of(state));
    var inventory = ((MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(factory.structurePosition().offset(-1, 0, 1))).inventory();
    var named = new ItemStack(Items.IRON_INGOT, 2);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("B"));
    inventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT));
    inventory.setStackInSlot(1, named);
    inventory.setStackInSlot(2, new ItemStack(Items.IRON_INGOT));
    var catalog = MachineRecipeService.catalogFor(factory).orElseThrow();
    var search = RecipeSearch.beginFactory(factory, thread, catalog, Set.of(PLAIN.toString())).orElseThrow();
    var plan = search(helper, search, 8192);
    var started = FactoryParallelStarter.commit(factory, thread, plan, 2, 1);
    helper
        .assertValueEqual(started, FactoryParallelStarter.Result.CHECK_PENDING, "Factory yields before batch payment");
    helper.assertValueEqual(inventory.getStackInSlot(1).getCount(), 2, "Pending batch leaves inventory intact");
    helper.assertTrue(
        factory.runState().view().values().stream().allMatch(channel -> channel.runs().isEmpty()),
        "Pending batch creates no runs");
    for (int step = 0; step < 128 && started == FactoryParallelStarter.Result.CHECK_PENDING; step++) {
      started = FactoryParallelStarter.commit(factory, thread, plan, 2, 16);
    }
    helper.assertValueEqual(started, FactoryParallelStarter.Result.STARTED, "Independent batch started");
    helper.assertValueEqual(inventory.getStackInSlot(0).getCount(), 1, "First exact iron retained");
    helper.assertTrue(inventory.getStackInSlot(1).isEmpty(), "Both named iron payments committed");
    helper.assertValueEqual(inventory.getStackInSlot(2).getCount(), 1, "Second exact iron retained");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void factoryCheckerSeesBothIndependentFixedPayments(GameTestHelper helper) {
    var initial = RecipeResourceRegressionGameTests.build(helper, true);
    var factory = (FactoryControllerBlockEntity) initial.owner();
    String thread = "fixed_checker";
    var state = new FactoryThreadState(
        thread,
        factory.structureMachineId(),
        FIXED,
        true,
        0,
        2,
        0,
        FactoryThreadState.State.IDLE,
        FactoryThreadState.Reason.NONE,
        0,
        new UpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 1));
    factory.threadConfiguration().replaceThreads(List.of(state));
    helper.assertValueEqual(factory.effectiveParallelLimit(thread), 2, "Thread permits two independent runs");
    var inventory = ((MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(initial.owner().structurePosition().offset(-1, 0, 1))).inventory();
    inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
    for (int i = 0; i < 2; i++) {
      var iron = new ItemStack(Items.IRON_INGOT);
      iron.set(DataComponents.CUSTOM_NAME, Component.literal(i == 0 ? "B" : "C"));
      inventory.setStackInSlot(i + 1, iron);
    }
    var ticket = RecipeExecutionTicket.capture(initial.owner()).orElseThrow();
    var registries = helper.getLevel().registryAccess();
    var source = MachineRecipeCompiler.compile(MachineRecipeJson.decode(fixedRecipeJson(), registries));
    var selected = RecipeIngredientArrayResolution
        .resolve(source, List.of(new RecipeIngredientArrayResolution.Selection(0, Optional.empty())), registries);
    var effective = MachineRecipeJson
        .compileResolved(selected, registries.createSerializationContext(JsonOps.INSTANCE));
    var slices = RecipeIngredientArrayResolution.checkerSlices(effective, ticket, 2).orElseThrow();
    helper.assertValueEqual(
        slices.stream().map(s -> s.item().getHoverName().getString()).toList(),
        List.of("B", "C"),
        "Both independently paid stacks presented during search");
    var large = RecipeItemInputAllocation.capture(effective, ticket, 2, false).orElseThrow();
    var cursor = RecipeItemInputAllocation.begin(effective, ticket, 2, false);
    helper.assertValueEqual(cursor.advance(0), 0, "Empty allocation budget");
    int steps = 0;
    while (!cursor.complete() && steps < 4096) {
      int used = cursor.advance(1);
      helper.assertTrue(used >= 0 && used <= 1, "Each step respects its allocation budget");
      steps++;
    }
    helper.assertTrue(steps > 2 && cursor.complete(), "Capture resumes across rows and slots");
    helper.assertValueEqual(cursor.advance(1), 0, "Completed capture consumes no further work");
    var actual = new ArrayList<RecipeItemCallbackService.InputItem>();
    cursor.allocation().orElseThrow().appendCheckerSlices(actual);
    helper.assertValueEqual(
        actual.stream().map(s -> s.item().getHoverName().getString()).toList(),
        List.of("B", "C"),
        "Allocated payment slices agree with search");
    helper.assertValueEqual(
        cursor.allocation().orElseThrow().deductions(new long[]{2, 2}),
        large.deductions(new long[]{2, 2}),
        "Small and large allocation budgets agree");
    for (boolean acceptC : List.of(true, false)) {
      var seen = new ArrayList<String>();
      RecipeItemCallbacks.registerChecker(FACTORY_CHECKER, (context, stack) -> {
        String name = stack.getHoverName().getString();
        if (context.parallelInstances() == 2) {
          seen.add(name);
        }
        return name.equals("B") || acceptC && name.equals("C");
      });
      try {
        var catalog = MachineRecipeService.catalogFor(factory).orElseThrow();
        var plan = search(
            helper,
            RecipeSearch.beginFactory(factory, thread, catalog, Set.of(FIXED.toString())).orElseThrow(),
            8192);
        helper.assertTrue(
            seen.contains("B") && seen.contains("C"),
            "Search inspected both fixed payments for two independent runs");
        var resolved = plan.preparedInputDefinition().orElseThrow();
        int limit = plan.prepareParallel(resolved, 2, 8192);
        helper.assertValueEqual(limit, acceptC ? 2 : 1, "Selected factory branch retains the checked parallel limit");
      } finally {
        RecipeItemCallbackService.removeChecker(FACTORY_CHECKER);
      }
    }
    helper.succeed();
  }

  private static RecipeStartPlan search(GameTestHelper helper, MachineControllerBlockEntity controller, ResourceLocation id, int budget) {
    return search(helper, RecipeSearch.begin(controller, Set.of(id.toString())).orElseThrow(), budget);
  }

  private static RecipeStartPlan search(GameTestHelper helper, RecipeSearch search, int budget) {
    var found = search.advance(budget);
    int slices = 1;
    while (found.status() == RecipePlanner.Status.BUDGET_EXHAUSTED && slices < 8192) {
      helper.assertTrue(found.workUsed() <= budget, "Search work stays within the slice budget");
      found = search.advance(budget);
      slices++;
    }
    helper
        .assertTrue(found.workUsed() <= budget && (budget > 1 || slices > 1), "Search completed within its last slice");
    helper.assertValueEqual(found.status(), RecipePlanner.Status.SELECTED, "Ingredient recipe selected");
    return found.plan().orElseThrow();
  }

  private static void assertNamedDebit(GameTestHelper helper, RecipeStartPlan plan, ItemResourceKey namedKey, long count) {
    helper.assertValueEqual(plan.deductions().size(), 1, "Only the checked stack is debited");
    var debit = plan.deductions().getFirst();
    helper.assertValueEqual(debit.slot().slot(), 1, "Named iron slot");
    helper.assertValueEqual(debit.key(), namedKey, "Checked iron components");
    helper.assertValueEqual(debit.count(), count, "Checked iron quantity");
  }
}
