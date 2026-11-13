package com.lw.modularmachine.gametest;

import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeRecipeCheckGameTests {
  private static final ResourceLocation MACHINE = id("test_upgrade_recipe_checks");
  private static final ResourceLocation HANDLER = id("test_upgrade_recipe_gate");
  private static final ResourceLocation A = id("mmcece/test_upgrade_check_a");
  private static final ResourceLocation B = id("mmcece/test_upgrade_check_b");
  private static final String THREAD = "upgrade_thread";
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  private record Fixture(
      FactoryControllerBlockEntity factory,
      UpgradeBusBlockEntity bus,
      MachinePortBlockEntity input,
      MachinePortBlockEntity output) {
  }

  @AfterBatch(batch = "upgrade_recipe_check_veto")
  public static void cleanupVeto(ServerLevel level) {
    cleanup();
  }

  @AfterBatch(batch = "upgrade_recipe_check_guards")
  public static void cleanupGuards(ServerLevel level) {
    cleanup();
  }

  private static void cleanup() {
    FactoryUpgradeRecipeChecks.remove(HANDLER);
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_recipe_check_veto", timeoutTicks = 800)
  public static void full_resource_matching_precedes_budgeted_veto_and_accepted_proofs_guard_starts(GameTestHelper helper) {
    begin(helper, fixture -> verifyVeto(helper, fixture));
  }

  private static void verifyVeto(GameTestHelper helper, Fixture fixture) {
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    fixture.factory().serverTick();
    helper
        .assertTrue(
            fixture.factory().run(THREAD).isEmpty()
                && fixture.bus().recipeCheckRequired()
                && !fixture.bus().upgradeCatalogValid()
                && fixture.input().inventory().getStackInSlot(0).getCount() == 2
                && fixture.factory().scheduler().channels().getFirst()
                    .reason() == FactoryThreadState.Reason.CHECK_ERROR,
            "Missing required checker was treated as an absent optional upgrade");
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    var mode = new AtomicInteger();
    var calls = new ArrayList<ResourceLocation>();
    FactoryUpgradeRecipeChecks.Checker checker = (context, upgrade) -> {
      calls.add(context.recipeId());
      helper.assertTrue(
          context.machine().equals(MACHINE)
              && context.threadId().equals(THREAD)
              && context.controllerPosition().equals(fixture.factory().getBlockPos())
              && context.dimension().equals(helper.getLevel().dimension().location())
              && UpgradeLifecyclePublisher.isDispatching(fixture.factory()),
          "Checker lost source identity or callback guard");
      context.recipeData().putInt("duration", 999);
      helper.assertTrue(
          context.recipeData().getInt("duration") == 20,
          "Checker mutated native recipe definition snapshot");
      if (context.recipeId().equals(B)) {
        helper.assertTrue(
            context.recipeData().getList("outputs", Tag.TAG_COMPOUND).size() == 2,
            "Check context dropped probabilistic output metadata");
      }

      return mode.get() != 0
          && context.recipeId()
              .equals(B) ? FactoryUpgradeRecipeChecks.Decision.ALLOW : FactoryUpgradeRecipeChecks.Decision.REJECT;
    };
    FactoryUpgradeRecipeChecks.register(HANDLER, checker);
    synchronizeUpgrades(helper, fixture);
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var rejected = RecipeSearch.beginFactory(fixture.factory(), THREAD).orElseThrow();
    helper.assertTrue(
        budgeted(helper, rejected, calls).status() == RecipePlanner.Status.NO_MATCH && calls.size() == 2,
        "A rejection did not continue to B or repeated the same slot");
    mode.set(1);
    for (int attempt = 0; attempt < 100; attempt++) {
      helper.assertTrue(
          rejected.advance(65_536).status() == RecipePlanner.Status.NO_MATCH,
          "External state changed cached verdict without invalidation");
    }
    helper.assertTrue(
        calls.size() == 2 && fixture.input().inventory().getStackInSlot(0).getCount() == 2,
        "Cached rejections reran callbacks or consumed input");
    FactoryUpgradeRecipeChecks.invalidate(HANDLER);
    synchronizeUpgrades(helper, fixture);
    helper.assertTrue(!rejected.dependenciesCurrent(), "Invalidation retained negative search proof");

    for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
      fixture.output().inventory().setStackInSlot(slot, new ItemStack(Items.GOLD_INGOT, slot == 0 ? 63 : 64));
    }
    int beforeConstrained = calls.size();
    var constrained = RecipeSearch.beginFactory(fixture.factory(), THREAD).orElseThrow();
    helper.assertTrue(
        budgeted(helper, constrained, calls).plan().orElseThrow().recipe().id().equals(B.toString())
            && calls.subList(beforeConstrained, calls.size()).equals(List.of(B, B)),
        "Output-incompatible A reached checkers or prevented B selection");
    for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
      fixture.output().inventory().setStackInSlot(slot, ItemStack.EMPTY);
    }
    int beforeAccepted = calls.size();
    var search = RecipeSearch.beginFactory(fixture.factory(), THREAD).orElseThrow();
    var accepted = budgeted(helper, search, calls).plan().orElseThrow();
    helper.assertTrue(
        accepted.recipe().id().equals(B.toString())
            && calls.subList(beforeAccepted, calls.size()).equals(List.of(A, B, B)),
        "Priority veto fallback or per-slot budget continuation differs");
    int acceptedCalls = calls.size();
    for (int attempt = 0; attempt < 100; attempt++) {
      helper
          .assertTrue(search.advance(1).plan().orElseThrow() == accepted, "Unchanged selected plan lost its admission");
    }
    helper.assertTrue(calls.size() == acceptedCalls, "Selected plan repeated pure checkers");
    var rawA = rawPlan(fixture, A);
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, rawA) == FactoryRecipeStarter.Result.CHECK_REJECTED
            && FactoryParallelStarter.commit(
                fixture.factory(),
                THREAD,
                Collections.nCopies(2, rawA)) == FactoryParallelStarter.Result.CHECK_REJECTED
            && fixture.input().inventory().getStackInSlot(0).getCount() == 2
            && fixture.factory().run(THREAD).isEmpty(),
        "Direct single or parallel starter bypassed checker veto");
    int beforeCommit = calls.size();
    helper.assertTrue(
        FactoryRecipeStarter.start(fixture.factory(), THREAD, accepted) == FactoryRecipeStarter.Result.STARTED
            && calls.size() == beforeCommit
            && fixture.factory().run(THREAD).orElseThrow().recipeId().equals(B)
            && fixture.input().inventory().getStackInSlot(0).getCount() == 1,
        "Admitted plan reran callbacks or failed atomic startup");
    fixture.input().inventory().setStackInSlot(0, ItemStack.EMPTY);
    FactoryUpgradeRecipeChecks.register(HANDLER, (context, upgrade) -> {
      calls.add(context.recipeId());
      return FactoryUpgradeRecipeChecks.Decision.REJECT;
    });
    int frozenCalls = calls.size();
    awaitOutput(helper, fixture, 1, 120, () -> {
      helper.assertTrue(calls.size() == frozenCalls, "Paid run was rechecked against a replacement policy");
      FactoryUpgradeRecipeChecks.register(HANDLER, checker);
      fixture.bus().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR));
      fixture.bus().inventory().setStackInSlot(1, ItemStack.EMPTY);
      synchronizeUpgrades(helper, fixture);
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
      int beforeAuto = calls.size();
      fixture.factory().serverTick();
      helper.assertTrue(
          fixture.factory().runSet(THREAD).runs().size() == 2
              && calls.subList(beforeAuto, calls.size()).equals(List.of(A, B))
              && fixture.factory().runSet(THREAD).runs().stream().allMatch(run -> run.recipeId().equals(B)),
          "Automatic parallel search did not apply fallback once per candidate");
      int parallelCalls = calls.size();
      awaitOutput(helper, fixture, 3, 120, () -> {
        helper.assertTrue(
            calls.size() == parallelCalls && !UpgradeLifecyclePublisher.isDispatching(fixture.factory()),
            "Paid parallel run repeated checker calls or leaked guard");
        helper.succeed();
      });
    });
  }

  @GameTest(template = "empty16", batch = "upgrade_recipe_check_guards", timeoutTicks = 800)
  public static void checker_failures_stale_results_and_direct_parallel_budget_never_consume_early(GameTestHelper helper) {
    begin(helper, fixture -> verifyGuards(helper, fixture));
  }

  private static void verifyGuards(GameTestHelper helper, Fixture fixture) {
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var invalid = new ItemStack(Items.STONE);
    var data = new CompoundTag();
    data.putBoolean("mmcece:factory_upgrade", true);
    data.putInt("parallel_add", -1);
    invalid.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    fixture.bus().inventory().setStackInSlot(0, invalid);
    fixture.factory().serverTick();
    helper
        .assertTrue(
            fixture.bus().recipeCheckRequired()
                && fixture.factory().run(THREAD).isEmpty()
                && fixture.factory().scheduler().channels().getFirst()
                    .reason() == FactoryThreadState.Reason.CHECK_ERROR,
            "Earlier invalid slot hid a required checker in the next slot");
    fixture.bus().inventory().setStackInSlot(0, new ItemStack(Items.SUGAR));
    var count = new AtomicInteger();
    FactoryUpgradeRecipeChecks.Checker throwing = (context, upgrade) -> {
      count.incrementAndGet();
      throw new IllegalStateException("expected recipe check failure");
    };
    FactoryUpgradeRecipeChecks.register(HANDLER, throwing);
    var published = FactoryUpgradeService.snapshot();
    FactoryUpgradeRecipeChecks.register(HANDLER, throwing);
    FactoryUpgradeRecipeChecks.remove(id("unknown_recipe_checker"));
    boolean workerRejected = CompletableFuture.supplyAsync(() -> {
      try {
        FactoryUpgradeRecipeChecks.register(HANDLER, throwing);
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        workerRejected && FactoryUpgradeService.snapshot() == published,
        "Worker or no-op registration changed checker catalog");
    synchronizeUpgrades(helper, fixture);
    for (int attempt = 0; attempt < 100; attempt++) {
      fixture.factory().serverTick();
    }
    helper
        .assertTrue(
            count.get() == 1
                && fixture.factory().run(THREAD).isEmpty()
                && fixture.input().inventory().getStackInSlot(0).getCount() == 2
                && fixture.factory().scheduler().channels().getFirst()
                    .reason() == FactoryThreadState.Reason.CHECK_ERROR,
            "Checker exception retried every tick, fell back, or consumed input");
    FactoryUpgradeRecipeChecks.invalidate(HANDLER);
    synchronizeUpgrades(helper, fixture);
    fixture.factory().serverTick();
    helper.assertTrue(
        count.get() == 2 && fixture.factory().run(THREAD).isEmpty(),
        "Explicit invalidation did not retry failed check");
    FactoryUpgradeRecipeChecks.register(HANDLER, (context, upgrade) -> null);
    synchronizeUpgrades(helper, fixture);
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.factory().scheduler().channels().getFirst().reason() == FactoryThreadState.Reason.CHECK_ERROR,
        "Null check result was treated as acceptance");
    FactoryUpgradeRecipeChecks.register(HANDLER, (context, upgrade) -> {
      fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      return FactoryUpgradeRecipeChecks.Decision.ALLOW;
    });
    synchronizeUpgrades(helper, fixture);
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.input().inventory().getStackInSlot(0).getCount() == 1
            && fixture.factory().scheduler().channels().getFirst()
                .reason() == FactoryThreadState.Reason.STALE_RESOURCES,
        "Resource mutation inside checker retained a start proof");
    fixture.input().inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    FactoryUpgradeRecipeChecks.Checker stable = (context, upgrade) -> {
      count.incrementAndGet();
      try {
        fixture.bus().inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND));
        helper.fail("Checker modified owned upgrade inventory");
      } catch (IllegalStateException expected) {
      }
      helper.assertTrue(!fixture.factory().beginRunStart(THREAD), "Checker reentered factory startup");
      fixture.factory().serverTick();

      return context.recipeId()
          .equals(B) ? FactoryUpgradeRecipeChecks.Decision.ALLOW : FactoryUpgradeRecipeChecks.Decision.REJECT;
    };
    FactoryUpgradeRecipeChecks.register(HANDLER, (context, upgrade) -> {
      FactoryUpgradeRecipeChecks.register(HANDLER, stable);
      return FactoryUpgradeRecipeChecks.Decision.ALLOW;
    });
    synchronizeUpgrades(helper, fixture);
    fixture.factory().serverTick();
    helper.assertTrue(
        fixture.factory().run(THREAD).isEmpty()
            && fixture.input().inventory().getStackInSlot(0).getCount() == 2
            && fixture.factory().scheduler().channels().getFirst()
                .reason() == FactoryThreadState.Reason.STALE_RESOURCES,
        "Self-replacing checker published an old accepted result");
    synchronizeUpgrades(helper, fixture);
    var rawB = rawPlan(fixture, B);
    int oldBudget = MachineConfig.RECIPE_OUTPUT_WORK.get();
    int before = count.get();
    try {
      MachineConfig.RECIPE_OUTPUT_WORK.set(1);
      helper.assertTrue(
          FactoryParallelStarter.commit(
              fixture.factory(),
              THREAD,
              Collections.nCopies(2, rawB)) == FactoryParallelStarter.Result.CHECK_PENDING
              && fixture.factory().run(THREAD).isEmpty()
              && count.get() == before + 1
              && fixture.input().inventory().getStackInSlot(0).getCount() == 2,
          "Partial checker budget consumed input or failed to resume");
      helper.assertTrue(
          FactoryParallelStarter
              .commit(fixture.factory(), THREAD, Collections.nCopies(2, rawB)) == FactoryParallelStarter.Result.STARTED
              && count.get() == before + 2
              && fixture.factory().runSet(THREAD).runs().size() == 2,
          "Direct parallel startup repeated checks per run or lost continuation");
    } finally {
      MachineConfig.RECIPE_OUTPUT_WORK.set(oldBudget);
    }
    awaitOutput(helper, fixture, 2, 120, () -> {
      helper.assertTrue(
          count.get() == before + 2 && !UpgradeLifecyclePublisher.isDispatching(fixture.factory()),
          "Checker proof did not remain frozen for paid runs");
      helper.succeed();
    });
  }

  private static void begin(GameTestHelper helper, Consumer<Fixture> next) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Recipe checker reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          var definition = savedDefinitions.stream()
              .filter(value -> value.effect().id().equals(id("test_recipe_check_upgrade"))).findFirst().orElseThrow();
          helper.assertTrue(HANDLER.equals(definition.recipeCheckHandler()), "Recipe check declaration was not loaded");
          FactoryUpgradeService.replaceDefinitions(List.of(definition));
          FactoryUpgradeRecipeChecks.remove(HANDLER);
          var origin = helper.absolutePos(new BlockPos(7, 5, 7));
          var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
          helper.getLevel().setBlockAndUpdate(
              origin.offset(-1, 0, 1),
              MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
          helper.getLevel().setBlockAndUpdate(
              origin.offset(1, 0, 1),
              MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
          var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
          bus.inventory().setStackInSlot(0, new ItemStack(Items.SUGAR, 2));
          bus.inventory().setStackInSlot(1, new ItemStack(Items.SUGAR));
          UpgradeBusGameTests.confirm(helper, factory);
          next.accept(
              new Fixture(
                  factory,
                  bus,
                  (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(-1, 0, 1)),
                  (MachinePortBlockEntity) helper.getLevel().getBlockEntity(origin.offset(1, 0, 1))));
        })));
  }

  private static void synchronizeUpgrades(GameTestHelper helper, Fixture fixture) {
    fixture.bus().serverTick();
    helper
        .assertTrue(fixture.factory().beginRunStart(THREAD), "Checker fixture could not synchronize upgrade snapshot");
    fixture.factory().finishRunStart(THREAD, false);
  }

  private static RecipeSearch.Result budgeted(GameTestHelper helper, RecipeSearch search, List<ResourceLocation> calls) {
    for (int attempt = 0; attempt < 600; attempt++) {
      var result = search.advance(1);
      int count = calls.size();
      helper.assertTrue(
          result.workUsed() <= 1 && search.advance(0).workUsed() == 0 && calls.size() == count,
          "Recipe checks exceeded supplied work budget");
      if (result.status() != RecipePlanner.Status.BUDGET_EXHAUSTED) {
        return result;
      }
    }
    throw new IllegalStateException("Recipe checker search did not finish in bounded work");
  }

  private static RecipeStartPlan rawPlan(Fixture fixture, ResourceLocation recipe) {
    return RecipeSearch.begin(fixture.factory(), Set.of(recipe.toString())).orElseThrow().advance(65_536).plan()
        .orElseThrow();
  }

  private static void awaitOutput(GameTestHelper helper, Fixture fixture, int expected, int remaining, Runnable complete) {
    helper.runAfterDelay(1, () -> {
      fixture.bus().serverTick();
      fixture.factory().serverTick();
      int gold = 0;
      for (int slot = 0; slot < fixture.output().inventory().getSlots(); slot++) {
        var stack = fixture.output().inventory().getStackInSlot(slot);
        helper.assertTrue(stack.isEmpty() || stack.is(Items.GOLD_INGOT), "Vetoed A or zero-chance output was produced");
        gold += stack.getCount();
      }
      if (fixture.factory().run(THREAD).isEmpty() && gold == expected) {
        complete.run();
      } else if (remaining == 0) {
        helper.fail("Accepted recipe did not produce exactly the frozen B outputs");
      } else {
        awaitOutput(helper, fixture, expected, remaining - 1, complete);
      }
    });
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
