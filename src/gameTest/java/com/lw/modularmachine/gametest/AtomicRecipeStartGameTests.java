package com.lw.modularmachine.gametest;

import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.recipe.RecipeExecutionTicket;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeCandidate;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.authlib.GameProfile;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class AtomicRecipeStartGameTests {
  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void quiet_item_leases_restore_aborted_swaps_and_publish_one_revision_after_commit(GameTestHelper helper) {
    var controller = standard(helper);
    var input = port(helper, controller, "item_in");
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    long before = input.resourceRevision();
    var prepared = NonNullList.withSize(input.inventory().getSlots(), ItemStack.EMPTY);
    prepared.set(0, new ItemStack(Items.DIRT, 1));
    try (var lease = input.acquireItemStart(before)) {
      lease.replacePrepared(prepared);
    }
    helper.assertTrue(
        input.inventory().getStackInSlot(0).getCount() == 3
            && input.resourceRevision() == before
            && !input.itemStartInProgress(),
        "Aborted lease changed contents/revision or stayed locked");
    try (var lease = input.acquireItemStart(before)) {
      lease.replacePrepared(prepared);
      lease.resolve(true);
      helper.assertTrue(
          input.resourceRevision() == before + 1 && input.inventory().getStackInSlot(0).getCount() == 1,
          "Committed lease did not expose complete new state");
      lease.publish();
      lease.publish();
    }
    helper.assertTrue(input.resourceRevision() == before + 1, "Repeated publish double-counted resource revision");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void leased_item_inventory_rejects_reentrant_io_resizing_and_deserialization(GameTestHelper helper) {
    var controller = standard(helper);
    var input = port(helper, controller, "item_in");
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3));
    var saved = input.inventory().serializeNBT(helper.getLevel().registryAccess());
    var full = input.saveWithoutMetadata(helper.getLevel().registryAccess());
    int size = input.inventory().getSlots();
    long revision = input.resourceRevision();
    try (var lease = input.acquireItemStart(revision)) {
      denied(helper, () -> input.inventory().getStackInSlot(0));
      denied(helper, () -> input.inventory().setStackInSlot(0, ItemStack.EMPTY));
      denied(helper, () -> input.inventory().insertItem(0, new ItemStack(Items.DIRT), false));
      denied(helper, () -> input.inventory().extractItem(0, 1, false));
      denied(helper, () -> input.inventory().extractItem(0, 1, true));
      denied(helper, () -> input.inventory().setSize(size));
      denied(helper, () -> input.inventory().deserializeNBT(helper.getLevel().registryAccess(), saved));
      denied(helper, () -> input.loadWithComponents(full, helper.getLevel().registryAccess()));
    }
    helper.assertTrue(
        input.resourceRevision() == revision && input.inventory().getStackInSlot(0).getCount() == 3,
        "Denied access modified leased contents");
    helper.succeed();
  }

  @GameTest(template = "empty16", batch = "atomic_recipe_start", timeoutTicks = 600)
  public static void native_atomic_start_persistence_and_failure_matrix(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.fail("Atomic start catalog setup failed: " + failure);
            return;
          }
          try {
            paidStartAndPortableRecovery(helper);
            stalePlansAndThreadGuards(helper);
            allOrientationsStart(helper);
            var frozen = multiBusStart(helper);
            multiBusRollbackAndBusyRetry(helper, frozen);
            sharedBusCompetingControllers(helper);
            reentrantStartAndResourceChange(helper);
            var legacy = legacy(helper);
            var input = legacyInput(helper, legacy);
            input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
            var plan = plan(legacy);
            var manager = server.getRecipeManager();
            var recipes = List.copyOf(manager.getRecipes());
            TestPredicateBlock.CALLBACK.set(() -> manager.replaceRecipes(recipes));
            try {
              helper.assertTrue(
                  plan.start() == AtomicRecipeStarter.Result.STALE
                      && input.inventory().getStackInSlot(0).getCount() == 1
                      && legacy.runningRecipe().isEmpty(),
                  "Catalog callback committed stale deductions");
            } finally {
              TestPredicateBlock.CALLBACK.set(null);
            }
            server.reloadResources(server.getPackRepository().getSelectedIds())
                .whenComplete((again, recoveryFailure) -> server.execute(() -> {
                  if (recoveryFailure != null) {
                    helper.fail("Atomic start recovery reload failed: " + recoveryFailure);
                    return;
                  }
                  try {
                    var controller = standard(helper);
                    port(helper, controller, "item_in").inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(
                        plan(controller).start() == AtomicRecipeStarter.Result.STARTED,
                        "Fresh native start did not recover after reload");
                    helper.succeed();
                  } catch (RuntimeException | AssertionError error) {
                    helper.runAfterDelay(1, () -> helper.fail("Atomic start recovery: " + error));
                  }
                }));
          } catch (RuntimeException | AssertionError error) {
            TestPredicateBlock.CALLBACK.set(null);
            helper.runAfterDelay(1, () -> helper.fail("Atomic start matrix: " + error));
          }
        }));
  }

  private static void paidStartAndPortableRecovery(GameTestHelper helper) {
    var controller = standard(helper);
    var input = port(helper, controller, "item_in");
    var output = port(helper, controller, "item_out");
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    input.inventory().setStackInSlot(1, new ItemStack(Items.DIRT, 1));
    fill(output, new ItemStack(Items.DIAMOND, 63));
    long revision = input.resourceRevision();
    var plan = plan(controller);
    helper.assertTrue(plan.recipe().id().endsWith("test_native_b"), "B occupancy did not select B before start");
    helper.assertTrue(
        plan.start(0) == AtomicRecipeStarter.Result.BUSY
            && controller.runningRecipe().isEmpty()
            && input.resourceRevision() == revision,
        "An exhausted start budget consumed inputs or installed a run");
    helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.STARTED, "Native paid start failed");
    var run = controller.runningRecipe().orElseThrow();
    helper.assertTrue(
        input.inventory().getStackInSlot(0).isEmpty()
            && input.inventory().getStackInSlot(1).isEmpty()
            && input.resourceRevision() == revision + 1,
        "Start did not deduct both slots as one resource commit");
    helper.assertTrue(
        output.inventory().getStackInSlot(0).getCount() == 63
            && run.recipeId().getPath().endsWith("test_native_b")
            && run.duration() == 20
            && run.progress() == 0,
        "Start generated outputs or lost frozen recipe metadata");
    helper.assertTrue(
        plan.start(0) == AtomicRecipeStarter.Result.ALREADY_RUNNING && RecipeSearch.begin(controller).isEmpty(),
        "Repeated start consumed twice or selected another recipe");
    fill(output, ItemStack.EMPTY);
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 5));
    for (int i = 0; i < 10; i++) {
      controller.serverTick();
    }
    helper.assertTrue(
        controller.runningRecipe().orElseThrow().runId().equals(run.runId())
            && controller.runningRecipe().orElseThrow().progress() == 0
            && input.inventory().getStackInSlot(0).getCount() == 5,
        "Duplicate same-tick calls advanced or switched a paid run");
    var saved = controller.saveWithoutMetadata(helper.getLevel().registryAccess());
    controller.loadWithComponents(saved, helper.getLevel().registryAccess());
    helper.assertTrue(
        controller.runningRecipe().orElseThrow().runId().equals(run.runId())
            && input.inventory().getStackInSlot(0).getCount() == 5,
        "Save/load restarted paid input consumption");
    StructureService.reorient(controller, MachineOrientation.of(Direction.NORTH, 1));
    helper.assertTrue(
        controller.runningRecipe().orElseThrow().runId().equals(run.runId()),
        "Reorientation discarded paid run");
    var drops = Block.getDrops(controller.getBlockState(), helper.getLevel(), controller.getBlockPos(), controller);
    helper.assertTrue(drops.size() == 1, "Paid controller produced an invalid number of drops");
    var origin = controller.getBlockPos();
    helper.getLevel().setBlockAndUpdate(origin, Blocks.AIR.defaultBlockState());
    helper.getLevel().setBlockAndUpdate(origin.below(), Blocks.STONE.defaultBlockState());
    var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(new UUID(0, 0x241600L), "mmcece_start"));
    player.setGameMode(GameType.SURVIVAL);
    player.setPos(origin.getX() + 0.5, origin.getY() + 2, origin.getZ() + 0.5);
    player.setYRot(0);
    player.setXRot(90);
    player.setItemInHand(InteractionHand.MAIN_HAND, drops.getFirst());
    player.gameMode.useItemOn(
        player,
        helper.getLevel(),
        player.getMainHandItem(),
        InteractionHand.MAIN_HAND,
        new BlockHitResult(
            new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5),
            Direction.UP,
            origin.below(),
            false));
    var placed = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(origin);
    helper.assertTrue(
        placed != null
            && placed.schemaSupported()
            && placed.runningRecipe().orElseThrow().runId().equals(run.runId())
            && player.getMainHandItem().isEmpty(),
        "Dropped paid run was lost or controller item was duplicated on placement");
    denied(helper, () -> placed.bindMachine(id("transformer")));
    var good = placed.saveWithoutMetadata(helper.getLevel().registryAccess());
    var corrupt = good.copy();
    corrupt.getCompound("mmcece").getCompound("run").putInt("schema", 99);
    placed.loadWithComponents(corrupt, helper.getLevel().registryAccess());
    helper.assertTrue(
        !placed.schemaSupported()
            && !placed.canCommitProduction()
            && placed.portableData(helper.getLevel().registryAccess()).getCompound("mmcece")
                .equals(corrupt.getCompound("mmcece")),
        "Future run data became executable or was rewritten");
    placed.loadWithComponents(good, helper.getLevel().registryAccess());
    helper.assertTrue(
        placed.runningRecipe().orElseThrow().runId().equals(run.runId()),
        "Repair did not restore preserved run identity");
  }

  private static void checkFuelLimits(GameTestHelper helper, MachineControllerBlockEntity controller, MachinePortBlockEntity input) {
    var definition = MachineRecipe.CODEC.codec()
        .parse(
            helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE),
            JsonParser.parseString("""
                {"machine":"mmcece:test_recipe_resources","duration":1,"inputs":[],
                 "outputs":[{"stack":{"id":"minecraft:iron_ingot"},"amount":1}],
                 "item_payments":{"fuels":[{"burn_time":1600}]}}
                """))
        .getOrThrow();
    var candidate = new RecipeCandidate<ItemResourceKey>("mmcece:fuel_limit", 0, List.of(), List.of());
    var catalog = RecipeCatalog.fromDefinitions(
        controller.structureMachineId(),
        List.of(candidate),
        Map.of(candidate.id(), definition),
        () -> true,
        null,
        List.of());
    var ticket = RecipeExecutionTicket.capture(controller).orElseThrow();
    var plan = RecipeStartPlan.prepared(ticket, catalog, candidate, List.of(), List.of(), 1, null);
    helper.assertTrue(
        plan.maxRepeatableInputs(0) == 0
            && plan.maxRepeatableInputs(1) == 1
            && plan.maxRepeatableInputs(3) == 2
            && input.inventory().getStackInSlot(0).getCount() == 2,
        "Fuel admission exceeded its request, ignored physical fuel, or consumed it while probing");
    helper.assertTrue(
        plan.start() == AtomicRecipeStarter.Result.UNMANAGED_RECIPE && controller.runningRecipe().isEmpty(),
        "A current input plan from an unpublished fuel catalog passed managed-recipe admission");
  }

  @GameTest(template = "empty16", batch = "atomic_start_budget", timeoutTicks = 600)
  public static void ordinary_search_and_start_share_one_tick_budget(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> {
          if (failure != null) {
            helper.runAfterDelay(1, () -> helper.fail("Budget test catalog setup failed: " + failure));
            return;
          }
          try {
            checkOrdinarySearchBudget(helper);
          } catch (RuntimeException | AssertionError error) {
            helper.runAfterDelay(1, () -> helper.fail("Ordinary search budget: " + error));
          }
        }));
  }

  private static void checkOrdinarySearchBudget(GameTestHelper helper) {
    var controller = standard(helper);
    var input = port(helper, controller, "item_in");
    input.inventory().setStackInSlot(0, new ItemStack(Items.COAL, 2));
    checkFuelLimits(helper, controller, input);
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var measured = RecipeSearch.begin(controller).orElseThrow().advance(10_000);
    int budget = measured.workUsed() + 1;
    var search = RecipeSearch.begin(controller).orElseThrow().advance(budget);
    helper.assertTrue(
        search.plan().isPresent() && budget - search.workUsed() == 1,
        "Expected one unit left after selection, measured " + measured.status() + "/" + measured
            .workUsed() + ", bounded " + search.status() + "/" + search.workUsed());
    long revision = input.resourceRevision();
    int configured = MachineConfig.RECIPE_OUTPUT_WORK.get();
    try {
      MachineConfig.RECIPE_OUTPUT_WORK.set(budget);
      controller.serverTick();
      helper.assertTrue(
          controller.runningRecipe().isEmpty() && input.resourceRevision() == revision,
          "An ordinary controller spent a second budget on the tick that selected its recipe");
    } finally {
      MachineConfig.RECIPE_OUTPUT_WORK.set(configured);
    }
    helper.runAfterDelay(1, () -> {
      if (controller.runningRecipe().isEmpty()) {
        controller.serverTick();
      }
      helper.assertTrue(
          controller.runningRecipe().isPresent()
              && input.inventory().getStackInSlot(0).isEmpty()
              && input.resourceRevision() == revision + 1,
          "The selected recipe did not charge exactly once with the next tick's start budget");
      helper.succeed();
    });
  }

  private static void stalePlansAndThreadGuards(GameTestHelper helper) {
    var controller = standard(helper);
    var input = port(helper, controller, "item_in");
    var output = port(helper, controller, "item_out");
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    var plan = plan(controller);
    fill(output, new ItemStack(Items.STONE, 64));
    helper.assertTrue(
        plan.start() == AtomicRecipeStarter.Result.STALE
            && input.inventory().getStackInSlot(0).getCount() == 2
            && controller.runningRecipe().isEmpty(),
        "Changed output consumed an old plan");
    fill(output, ItemStack.EMPTY);
    var current = plan(controller);
    boolean denied = CompletableFuture.supplyAsync(() -> {
      try {
        current.start();
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        denied && input.inventory().getStackInSlot(0).getCount() == 2 && controller.runningRecipe().isEmpty(),
        "Worker reached live recipe mutation");
    StructureService.reorient(controller, MachineOrientation.of(Direction.NORTH, 1));
    var result = current.start();
    int remaining = input.inventory().getStackInSlot(0).getCount();
    helper.assertTrue(
        result == AtomicRecipeStarter.Result.STALE && remaining == 2,
        "Pending orientation returned " + result + " with " + remaining + " input remaining");
  }

  private static FrozenRecipeRun multiBusStart(GameTestHelper helper) {
    var controller = multi(helper);
    var a = port(helper, controller, "input_a");
    var b = port(helper, controller, "input_b");
    a.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    b.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    long ar = a.resourceRevision(), br = b.resourceRevision();
    helper.assertTrue(plan(controller).start() == AtomicRecipeStarter.Result.STARTED, "Cross-bus start failed");
    helper.assertTrue(
        a.inventory().getStackInSlot(0).isEmpty()
            && b.inventory().getStackInSlot(0).isEmpty()
            && a.resourceRevision() == ar + 1
            && b.resourceRevision() == br + 1,
        "Cross-bus deductions are partial");
    return controller.runningRecipe().orElseThrow();
  }

  private static void allOrientationsStart(GameTestHelper helper) {
    var p = origin(helper);
    for (var pose : MachineOrientation.all()) {
      clear(helper, p);
      var controller = RecipeResourceTicketGameTests.build(helper, p, pose);
      var input = port(helper, controller, "item_in");
      var output = port(helper, controller, "item_out");
      input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
      helper.assertTrue(
          plan(controller).start() == AtomicRecipeStarter.Result.STARTED
              && input.inventory().getStackInSlot(0).isEmpty()
              && output.inventory().getStackInSlot(0).isEmpty(),
          "Orientation start failed or generated early output: " + pose);
      helper.assertTrue(
          controller.runningRecipe().orElseThrow().logicalPorts().get("item_in") == PortKind.ITEM_INPUT
              && controller.runningRecipe().orElseThrow().logicalPorts().get("item_out") == PortKind.ITEM_OUTPUT,
          "Frozen logical port roles changed with orientation");
    }
  }

  private static void multiBusRollbackAndBusyRetry(GameTestHelper helper, FrozenRecipeRun run) {
    var controller = multi(helper);
    var a = port(helper, controller, "input_a");
    var b = port(helper, controller, "input_b");
    a.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    b.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    long ar = a.resourceRevision(), br = b.resourceRevision();
    int size = a.inventory().getSlots();
    try (var owner = controller.runState().acquireStart();
        var first = a.acquireItemStart(ar);
        var second = b.acquireItemStart(br)) {
      first.replacePrepared(NonNullList.withSize(size, ItemStack.EMPTY));
      owner.install(run);
      try {
        second.replacePrepared(NonNullList.withSize(size + 1, ItemStack.EMPTY));
        helper.fail("Bad second swap was accepted");
      } catch (IllegalArgumentException expected) {
      }
    }
    helper.assertTrue(
        controller.runningRecipe().isEmpty()
            && !controller.recipeStartInProgress()
            && a.inventory().getStackInSlot(0).getCount() == 1
            && b.inventory().getStackInSlot(0).getCount() == 1
            && a.resourceRevision() == ar
            && b.resourceRevision() == br,
        "Aborted cross-bus operation did not restore run and both inventories");
    var plan = plan(controller);
    try (var peer = b.acquireItemStart(br)) {
      helper.assertTrue(plan.start() == AtomicRecipeStarter.Result.BUSY, "Leased shared bus was not detected");
    }
    helper.assertTrue(
        plan.start() == AtomicRecipeStarter.Result.STARTED
            && a.inventory().getStackInSlot(0).isEmpty()
            && b.inventory().getStackInSlot(0).isEmpty(),
        "Busy retry lost a lock or charged twice");
  }

  private static void reentrantStartAndResourceChange(GameTestHelper helper) {
    var controller = legacy(helper);
    var input = legacyInput(helper, controller);
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var plan = plan(controller);
    var nested = new AtomicReference<AtomicRecipeStarter.Result>();
    final var initialPlan = plan;
    TestPredicateBlock.CALLBACK.set(() -> nested.set(initialPlan.start()));
    try {
      var result = plan.start();
      int remaining = input.inventory().getStackInSlot(0).getCount();
      helper.assertTrue(
          result == AtomicRecipeStarter.Result.STARTED
              && nested.get() == AtomicRecipeStarter.Result.BUSY
              && remaining == 0,
          "Reentrant start returned " + result + ", nested " + nested.get() + ", remaining " + remaining);
    } finally {
      TestPredicateBlock.CALLBACK.set(null);
    }
    controller = legacy(helper);
    input = legacyInput(helper, controller);
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    plan = plan(controller);
    final var changed = input;
    TestPredicateBlock.CALLBACK.set(() -> changed.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 3)));
    try {
      helper.assertTrue(
          plan.start() == AtomicRecipeStarter.Result.STALE
              && input.inventory().getStackInSlot(0).getCount() == 3
              && controller.runningRecipe().isEmpty(),
          "Callback resource change was charged or rolled back as owned input");
    } finally {
      TestPredicateBlock.CALLBACK.set(null);
    }
  }

  private static void sharedBusCompetingControllers(GameTestHelper helper) {
    var p = origin(helper);
    clear(helper, p);
    var first = multiAt(helper, p.offset(-1, -1, 0));
    var second = multiAt(helper, p.offset(-1, 0, 0));
    confirm(helper, first);
    confirm(helper, second);
    var a = port(helper, first, "input_a");
    var shared = port(helper, first, "input_b");
    var b = port(helper, second, "input_b");
    helper.assertTrue(shared == port(helper, second, "input_a"), "Competition fixture does not share a physical bus");
    a.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    shared.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    b.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    var one = plan(first);
    var two = plan(second);
    helper.assertTrue(
        one.start() == AtomicRecipeStarter.Result.STARTED && two.start() == AtomicRecipeStarter.Result.STALE,
        "Shared input was consumed by two stale plans");
    helper.assertTrue(
        second.runningRecipe().isEmpty()
            && shared.inventory().getStackInSlot(0).isEmpty()
            && b.inventory().getStackInSlot(0).getCount() == 1,
        "Losing controller partially paid its unshared input");
    var firstId = first.runningRecipe().orElseThrow().runId();
    shared.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 1));
    helper.assertTrue(
        plan(second).start() == AtomicRecipeStarter.Result.STARTED
            && first.runningRecipe().orElseThrow().runId().equals(firstId),
        "New shared-bus start invalidated another owner's paid run");
  }

  private static MachineControllerBlockEntity standard(GameTestHelper helper) {
    var p = origin(helper);
    clear(helper, p);
    return RecipeResourceTicketGameTests.build(helper, p, MachineOrientation.IDENTITY);
  }

  private static MachineControllerBlockEntity multi(GameTestHelper helper) {
    var p = origin(helper);
    clear(helper, p);
    return multiAt(helper, p);
  }

  private static MachineControllerBlockEntity multiAt(GameTestHelper helper, BlockPos p) {
    helper.getLevel().setBlockAndUpdate(p, MachineRegistries.CONTROLLER.get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        p.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        p.offset(-1, 1, 1),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        p.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    var c = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(p);
    c.bindMachine(id("test_recipe_multi_input"));
    confirm(helper, c);
    return c;
  }

  private static MachineControllerBlockEntity legacy(GameTestHelper helper) {
    var p = origin(helper);
    clear(helper, p);
    var pose = MachineOrientation.of(Direction.EAST, 0);
    helper.getLevel().setBlockAndUpdate(
        p,
        MachineControllerBlock.withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), pose));
    helper.getLevel().setBlockAndUpdate(
        p.offset(pose.toWorldOffset(new BlockPos(-1, 0, 1))),
        MachineRegistries.PORTS.get("item_input_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        p.offset(pose.toWorldOffset(new BlockPos(1, 0, 1))),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    helper.getLevel().setBlockAndUpdate(
        p.offset(pose.toWorldOffset(new BlockPos(0, 1, 1))),
        TestPredicateBlock.INSTANCE.defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
    var c = (MachineControllerBlockEntity) helper.getLevel().getBlockEntity(p);
    c.bindMachine(id("test_recipe_reentrant"));
    confirm(helper, c);
    return c;
  }

  private static MachinePortBlockEntity legacyInput(GameTestHelper helper, MachineControllerBlockEntity c) {
    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(
        c.getBlockPos().offset(MachineOrientation.of(Direction.EAST, 0).toWorldOffset(new BlockPos(-1, 0, 1))));
  }

  private static void clear(GameTestHelper helper, BlockPos p) {
    for (int x = -2; x <= 2; x++) {
      for (int y = -1; y <= 2; y++) {
        for (int z = -2; z <= 2; z++) {
          helper.getLevel().setBlockAndUpdate(p.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
      }
    }
  }

  private static BlockPos origin(GameTestHelper helper) {
    return helper.absolutePos(new BlockPos(7, 5, 7));
  }

  private static MachinePortBlockEntity port(GameTestHelper h, MachineControllerBlockEntity c, String id) {
    return RecipeResourceTicketGameTests.port(h, c, id);
  }

  private static RecipeStartPlan plan(MachineControllerBlockEntity c) {
    return RecipeSearch.begin(c).orElseThrow().advance(10_000).plan().orElseThrow();
  }

  private static void confirm(GameTestHelper h, MachineControllerBlockEntity c) {
    for (int i = 0; i < 200 && !c.canCommitProduction(); i++) {
      StructureService.advance(h.getLevel(), 512, 64, 50_000_000L);
    }
    h.assertTrue(c.canCommitProduction(), c.statusMessage().getString());
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }

  private static void fill(MachinePortBlockEntity port, ItemStack value) {
    for (int i = 0; i < port.inventory().getSlots(); i++) {
      port.inventory().setStackInSlot(i, value.copy());
    }
  }

  private static void denied(GameTestHelper h, Runnable action) {
    try {
      action.run();
      h.fail("Reentrant or paid-run mutation was allowed");
    } catch (IllegalStateException expected) {
    }
  }
}
