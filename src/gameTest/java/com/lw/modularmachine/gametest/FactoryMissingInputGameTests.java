package com.lw.modularmachine.gametest;

import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeInputMiss;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.registry.MachineRegistries;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class FactoryMissingInputGameTests {
  private static final String THREAD = "idle_search";

  @GameTest(template = "empty16", batch = "factory_missing_inputs", timeoutTicks = 220)
  public static void energy_notifications_recheck_missing_inputs_without_restarting_search(GameTestHelper helper) {
    var level = helper.getLevel();
    var pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var machine = ResourceLocation.parse("mmcece:test_recipe_energy_lifecycle");
    level.setBlockAndUpdate(pos, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    var input = placePort(helper, pos.offset(-1, 0, 1), "item_input_bus_normal");
    var output = placePort(helper, pos.offset(1, 0, 1), "item_output_bus_normal");
    var energy = placePort(helper, pos.offset(0, 1, 1), "energy_input_hatch_normal");
    placePort(helper, pos.offset(0, -1, 1), "energy_input_hatch_normal");
    placePort(helper, pos.offset(1, 1, 1), "fluid_input_hatch_normal");
    placePort(helper, pos.offset(1, -1, 1), "fluid_input_hatch_normal");
    var factory = (FactoryControllerBlockEntity) level.getBlockEntity(pos);
    factory.bindMachine(machine);
    var threads = List.of("dirt", "gravel");
    factory.threadConfiguration().replaceThreads(
        threads.stream()
            .map(
                id -> new FactoryThreadState(
                    id,
                    machine,
                    ResourceLocation.parse("mmcece:mmcece/test_energy_paid_" + id),
                    true,
                    0,
                    1,
                    0,
                    FactoryThreadState.State.IDLE,
                    FactoryThreadState.Reason.NONE,
                    0,
                    UpgradeSnapshot.identity(0)))
            .toList());

    var sequence = helper.startSequence();
    sequence.thenWaitUntil(
        () -> helper.assertTrue(
            factory.canCommitStructure()
                && threads.stream().allMatch(
                    id -> factory.scheduler().channel(id).reason() == FactoryThreadState.Reason.INPUT_MISSING),
            "Both locked recipes must first finish a missing-input search"));
    int[] ages = new int[threads.size()];
    long[] start = {0};
    sequence.thenExecute(() -> {
      for (int i = 0; i < threads.size(); i++) {
        ages[i] = factory.scheduler().waitingAges().getOrDefault(threads.get(i), 0);
      }
      start[0] = level.getGameTime();
    });
    sequence.thenWaitUntil(() -> {
      energy.energy().receiveEnergy(20, false);
      helper.assertTrue(level.getGameTime() - start[0] >= 16, "Publishing FE changes over multiple ticks");
    });
    sequence.thenExecute(() -> {
      helper.assertTrue(energy.energy().stored() >= 16 * 20, "Fixture did not generate the FE notifications");
      for (int i = 0; i < threads.size(); i++) {
        helper.assertTrue(
            factory.scheduler().waitingAges().getOrDefault(threads.get(i), 0) == ages[i],
            "An unrelated FE change restarted the missing-input search for " + threads.get(i));
      }
      input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      start[0] = level.getGameTime();
    });
    sequence.thenWaitUntil(
        () -> helper.assertTrue(
            output.inventory().getStackInSlot(0).is(Items.IRON_INGOT),
            "Dirt insertion did not wake its locked thread"));
    sequence
        .thenExecute(
            () -> helper.assertTrue(level.getGameTime() - start[0] < 80, "Notified input waited for a periodic retry"))
        .thenSucceed();
  }

  private static MachinePortBlockEntity placePort(GameTestHelper helper, BlockPos pos, String name) {
    helper.getLevel().setBlockAndUpdate(pos, MachineRegistries.PORTS.get(name).get().defaultBlockState());
    return (MachinePortBlockEntity) helper.getLevel().getBlockEntity(pos);
  }

  @GameTest(template = "empty16", batch = "factory_missing_inputs", timeoutTicks = 440)
  public static void unchanged_grouped_inputs_sleep_and_resume_after_unreported_component_edit(GameTestHelper helper) {
    var level = helper.getLevel();
    var pos = helper.absolutePos(new BlockPos(7, 5, 7));
    var machine = ResourceLocation.fromNamespaceAndPath("mmcece", "test_factory_output_planning");
    level.setBlockAndUpdate(pos, MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState());
    level.setBlockAndUpdate(
        pos.offset(-1, 0, 1),
        MachineRegistries.PORTS.get("item_input_bus_ludicrous").get().defaultBlockState());
    level.setBlockAndUpdate(
        pos.offset(1, 0, 1),
        MachineRegistries.PORTS.get("item_output_bus_normal").get().defaultBlockState());
    level.setBlockAndUpdate(
        pos.offset(0, 1, 1),
        MachineRegistries.PORTS.get("fluid_output_hatch_normal").get().defaultBlockState());
    var factory = (FactoryControllerBlockEntity) level.getBlockEntity(pos);
    var input = (MachinePortBlockEntity) level.getBlockEntity(pos.offset(-1, 0, 1));
    var output = (MachinePortBlockEntity) level.getBlockEntity(pos.offset(1, 0, 1));
    factory.bindMachine(machine);
    factory.threadConfiguration().replaceThreads(
        List.of(
            new FactoryThreadState(
                THREAD,
                machine,
                null,
                true,
                0,
                1,
                0,
                FactoryThreadState.State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                UpgradeSnapshot.identity(0))));
    helper.assertTrue(input.configureInputGroup(1, input.groupRevision()), "Failed to configure the grouped route");
    var named = new ItemStack(Items.DIRT);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("wrong components"));
    input.inventory().setStackInSlot(0, named);
    var sequence = helper.startSequence();
    sequence.thenWaitUntil(
        () -> helper.assertTrue(
            factory.canCommitStructure()
                && factory.scheduler().channel(THREAD).reason() == FactoryThreadState.Reason.NO_RECIPE,
            "Factory did not finish its initial search"));
    int[] age = {0};
    long[] start = {0};
    sequence.thenExecute(() -> {
      var catalog = MachineRecipeService.catalogFor(factory).orElseThrow();
      var search = RecipeSearch.beginFactory(factory, THREAD, catalog, null).orElseThrow();
      var completed = search.advance(65_536);
      helper.assertTrue(
          completed.status() == RecipePlanner.Status.NO_MATCH && search.missingInputs() != null,
          "Missing exact item anchors in every group did not produce a valid input proof");
      var sliced = RecipeSearch.beginFactory(factory, THREAD, catalog, null).orElseThrow();
      var partial = sliced.advance(completed.workUsed() - 1);
      helper.assertTrue(
          partial.status() == RecipePlanner.Status.BUDGET_EXHAUSTED
              && partial.workUsed() == completed.workUsed() - 1
              && sliced.missingInputs() == null,
          "Missing-input capture exceeded the search budget or published an unfinished position index");
      var expired = sliced.advance(1, System.nanoTime() - 1);
      helper.assertTrue(
          expired.status() == RecipePlanner.Status.BUDGET_EXHAUSTED
              && expired.workUsed() == 0
              && sliced.missingInputs() == null,
          "An expired search deadline allowed input proof construction");
      var last = sliced.advance(1);
      helper.assertTrue(
          last.status() == RecipePlanner.Status.NO_MATCH
              && last.workUsed() == 1
              && sliced.missingInputs() != null
              && sliced.totalWork() == search.totalWork(),
          "Missing-input proof construction lost work when resumed at its final group");
      verifySlicedScan(helper, input, search.missingInputs(), catalog);
      age[0] = factory.scheduler().waitingAges().getOrDefault(THREAD, 0);
      start[0] = level.getGameTime();
    });
    sequence
        .thenWaitUntil(() -> helper.assertTrue(level.getGameTime() - start[0] >= 170, "Waiting for two retry periods"));
    sequence.thenExecute(() -> {
      helper.assertTrue(
          factory.scheduler().waitingAges().getOrDefault(THREAD, 0) == age[0],
          "Unchanged physical inputs restarted the catalog search");
      long revision = input.resourceRevision();
      input.inventory().getStackInSlot(0).remove(DataComponents.CUSTOM_NAME);
      helper
          .assertTrue(input.resourceRevision() == revision, "The component edit unexpectedly notified the controller");
    });
    sequence.thenWaitUntil(
        () -> helper.assertTrue(
            output.inventory().getStackInSlot(0).is(Items.IRON_INGOT),
            "Periodic content revalidation did not restore production"));
    sequence.thenWaitUntil(
        () -> helper.assertTrue(
            factory.scheduler().channel(THREAD).reason() == FactoryThreadState.Reason.NO_RECIPE,
            "Factory did not return to the missing-input state"));
    sequence.thenExecute(() -> {
      input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT));
      start[0] = level.getGameTime();
    });
    sequence.thenWaitUntil(() -> {
      helper
          .assertTrue(output.inventory().getStackInSlot(0).getCount() == 2, "Normal insertion did not wake production");
    });
    sequence.thenExecute(
        () -> helper
            .assertTrue(level.getGameTime() - start[0] < 80, "Normal insertion waited for the periodic proof check"))
        .thenSucceed();
  }

  private static void verifySlicedScan(GameTestHelper helper, MachinePortBlockEntity input, RecipeInputMiss miss, RecipeCatalog catalog) {
    long now = helper.getLevel().getGameTime();
    helper.assertTrue(
        miss.advance(catalog, now, 0, Long.MAX_VALUE) == RecipeInputMiss.Status.PENDING
            && miss.advance(catalog, now, 100, System.nanoTime() - 1) == RecipeInputMiss.Status.PENDING
            && miss.nextCheck() == Long.MIN_VALUE,
        "A probe ran with no work or after the deadline");
    helper.assertTrue(
        miss.advance(catalog, now, 2, Long.MAX_VALUE) == RecipeInputMiss.Status.PENDING,
        "Two work units completed a multi-slot, grouped probe");
    var live = input.inventory().getStackInSlot(0);
    var name = live.remove(DataComponents.CUSTOM_NAME);
    try {
      var status = RecipeInputMiss.Status.PENDING;
      int visits = 0;
      while (status == RecipeInputMiss.Status.PENDING && visits < input.inventory().getSlots() + 16) {
        status = miss.advance(catalog, now + 1, 1, Long.MAX_VALUE);
        visits++;
      }
      helper.assertTrue(
          status == RecipeInputMiss.Status.UNCHANGED && visits >= input.inventory().getSlots() - 1,
          "A one-unit probe must visit the remaining captured slots and groups");
      helper.assertTrue(
          miss.advance(catalog, miss.nextCheck(), 2, Long.MAX_VALUE) == RecipeInputMiss.Status.STALE,
          "The next pass skipped a silent edit to a previously visited slot");
    } finally {
      live.set(DataComponents.CUSTOM_NAME, name);
    }
  }
}
