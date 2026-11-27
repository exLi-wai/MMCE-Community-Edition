package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.RegisterMachineRecipesEvent;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.mojang.serialization.Codec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "mmcece")
@GameTestHolder("mmcece_adapter")
@PrefixGameTestTemplate(false)
public final class FragmentedItemPaymentGameTests {
  private static final int SLOTS = 1024;
  private static final ResourceLocation ID = ResourceLocation.parse("typed_test:fragmented_iron");
  // A native recipe on the fixture machine that pays two exact dirt per run.
  private static final ResourceLocation LOCKED = ResourceLocation.parse("mmcece:mmcece/test_native_a");
  private static final String THREAD = "locked_payment";
  private static final ResourceLocation CAPACITY = ResourceLocation.parse("typed_test:output_capacity_probe");
  private record CapacityMarker(int value) {
    private static int comparisons;

    @Override
    public boolean equals(Object other) {
      comparisons++;
      return other instanceof CapacityMarker marker && marker.value == value;
    }
  }
  private static final DataComponentType<CapacityMarker> MARKER = DataComponentType.<CapacityMarker>builder()
      .persistent(Codec.INT.xmap(CapacityMarker::new, CapacityMarker::value)).build();

  @SubscribeEvent
  public static void registerMarker(RegisterEvent event) {
    event.register(Registries.DATA_COMPONENT_TYPE, registry -> registry.register(CAPACITY, MARKER));
  }

  @SubscribeEvent
  public static void registerProbeRecipe(RegisterMachineRecipesEvent event) {
    if (List.of(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(",")).contains("mmcece_adapter")) {
      event.add(
          MachineRecipeBuilder.create(CAPACITY, ResourceLocation.parse("mmcece:test_recipe_resources"), 5)
              .itemInput(new ItemStack(Items.ECHO_SHARD), 1).itemOutput(markedIron(1), 1));
    }
  }

  private static ItemStack markedIron(int count) {
    var stack = new ItemStack(Items.IRON_INGOT, count);
    stack.set(MARKER, new CapacityMarker(1));
    return stack;
  }

  private record Fixture(
      RecipeExecutionTicket ticket,
      MachinePortBlockEntity bus,
      MachineRecipe recipe,
      RecipeCatalog catalog) {
    RecipeStartPlan plan(long seed) {
      return RecipeStartPlan.prepared(
          ticket,
          catalog,
          catalog.recipes().candidate(ID.toString()).orElseThrow(),
          List.of(),
          List.of(),
          seed,
          null);
    }
  }

  private static Fixture fixture(GameTestHelper helper, boolean factory, int amount) {
    var owner = RecipeResourceRegressionGameTests.build(helper, factory).owner();
    var registries = helper.getLevel().registryAccess();
    var bus = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(owner.structurePosition().offset(-1, 0, 1));
    var saved = new ItemStackHandler(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) {
      saved.setStackInSlot(slot, new ItemStack(slot < SLOTS / 2 ? Items.IRON_INGOT : Items.GOLD_INGOT));
    }
    var tag = bus.saveWithFullMetadata(registries);
    tag.getCompound("mmcece").put("items", saved.serializeNBT(registries));
    bus.loadWithComponents(tag, registries);
    helper.assertValueEqual(bus.inventory().getSlots(), SLOTS, "All occupied physical slots restored");
    var ticket = RecipeExecutionTicket.capture(owner).orElseThrow();
    var recipe = MachineRecipeCompiler.compile(
        MachineRecipeBuilder.create(ID, owner.structureMachineId(), 10)
            .itemInput(new ItemStack(Items.IRON_INGOT), amount).itemInput(new ItemStack(Items.GOLD_INGOT), amount)
            .itemOutput(new ItemStack(Items.DIRT), 1).build(registries));
    var candidate = recipe.compile(ID, registries);
    var catalog = RecipeCatalog.fromDefinitions(
        owner.structureMachineId(),
        List.of(candidate),
        Map.of(ID.toString(), recipe),
        () -> true,
        null,
        List.of());
    return new Fixture(ticket, bus, recipe, catalog);
  }

  @GameTest(template = "empty16")
  public static void reservationAndDebitPublicationEachRespectTheirOwnRemainingBudget(GameTestHelper helper) {
    var f = fixture(helper, false, SLOTS / 2);
    var rows = RecipeItemInputAllocation.begin(f.recipe(), f.ticket(), 1, true);
    helper.assertValueEqual(rows.advance(0), 0, "No reservation work with zero budget");
    int reservationSteps = 0;
    while (!rows.complete() && reservationSteps < 8192) {
      helper.assertValueEqual(rows.advance(1), 1, "One reservation step");
      reservationSteps++;
    }
    helper.assertTrue(rows.complete() && reservationSteps >= SLOTS, "Each physical slot was visited");
    var reserved = rows.allocation().orElseThrow();
    var counts = new long[]{SLOTS / 2, SLOTS / 2};
    var debits = reserved.beginDeductions(counts);
    helper.assertValueEqual(debits.advance(0), 0, "No debit work after the reservation consumed the budget");
    for (int i = 0; i < SLOTS - 1; i++) {
      helper.assertFalse(debits.complete(), "Fragmented debits published before the final slices");
      helper.assertValueEqual(debits.advance(1), 1, "One debit step");
    }
    helper.assertFalse(debits.complete(), "At least one debit remains pending");
    while (!debits.complete()) {
      helper.assertValueEqual(debits.advance(1), 1, "One final debit step");
    }
    var large = reserved.beginDeductions(counts);
    large.advance(65536);
    helper.assertValueEqual(debits.deductions(), large.deductions(), "Incremental and large-budget debits");
    assertAllSlots(helper, debits.deductions());
    var controller = (MachineControllerBlockEntity) f.ticket().owner();
    var search = RecipeSearch.begin(controller, f.catalog()).orElseThrow();
    var found = search.advance(0);
    helper.assertValueEqual(found.workUsed(), 0, "Search with exhausted budget");
    int steps = 0;
    while (found.status() == RecipePlanner.Status.BUDGET_EXHAUSTED && steps < SLOTS * 32) {
      found = search.advance(1);
      helper.assertTrue(found.workUsed() <= 1, "Search kept its single-step budget");
      steps++;
    }
    helper.assertValueEqual(found.status(), RecipePlanner.Status.SELECTED, "Fragmented search completed");
    helper.assertTrue(steps >= 3 * SLOTS, "Search charges for inventory capture, reservations and debits");
    var fast = RecipeSearch.begin(controller, f.catalog()).orElseThrow().advance(65536);
    helper.assertValueEqual(
        found.plan().orElseThrow().deductions(),
        fast.plan().orElseThrow().deductions(),
        "Search budgets agree on every physical slot");
    assertAllSlots(helper, found.plan().orElseThrow().deductions());
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void aggregatedPaymentResumesBeforePublishingAllPhysicalSlots(GameTestHelper helper) {
    var f = fixture(helper, false, SLOTS / 4);
    var plan = f.plan(123);
    var actual = resume(helper, budget -> plan.scaled(2, budget));
    var expected = f.plan(123).scaled(2, 65536).plan();
    helper.assertValueEqual(actual.deductions(), expected.deductions(), "Aggregated deductions by slot");
    assertAllSlots(helper, actual.deductions());
    assertFullInput(helper, f.bus());
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void independentBatchResumesBeforePublishingAllPhysicalSlots(GameTestHelper helper) {
    var f = fixture(helper, true, SLOTS / 4);
    var plans = List.of(f.plan(123), f.plan(456));
    var mixed = resume(helper, budget -> RecipeStartPlan.combineInputs(plans, budget));
    var repeated = f.plan(123);
    var repeatedBatch = resume(helper, budget -> repeated.independentInputs(2, budget));
    var expected = RecipeStartPlan.combineInputs(List.of(f.plan(123), f.plan(456)), 65536).plan();
    helper.assertValueEqual(mixed.deductions(), expected.deductions(), "Combined debits by physical slot");
    helper.assertValueEqual(repeatedBatch.deductions(), expected.deductions(), "Repeated-plan debits by physical slot");
    assertAllSlots(helper, mixed.deductions());
    assertFullInput(helper, f.bus());
    helper.succeed();
  }

  private static final class CountingBus extends MachinePortBlockEntity {
    int inventoryReads;

    CountingBus(BlockPos pos, BlockState state) {
      super(pos, state);
    }

    @Override
    public ItemStackHandler inventory() {
      inventoryReads++;
      return super.inventory();
    }
  }

  private record LockedFixture(FactoryControllerBlockEntity factory, CountingBus bus, RecipeCatalog catalog) {
    RecipeSearch search(RecipeSearch.LockedRestart hint) {
      return RecipeSearch.beginFactory(factory, THREAD, catalog, Set.of(LOCKED.toString()), hint).orElseThrow();
    }
  }

  private static LockedFixture lockedFactory(GameTestHelper helper) {
    return lockedFactory(helper, LOCKED);
  }

  private static LockedFixture lockedFactory(GameTestHelper helper, ResourceLocation recipe) {
    var factory = (FactoryControllerBlockEntity) RecipeResourceRegressionGameTests.build(helper, true).owner();
    factory.threadConfiguration().replaceThreads(
        List.of(
            new FactoryThreadState(
                THREAD,
                factory.structureMachineId(),
                recipe,
                true,
                0,
                2,
                0,
                FactoryThreadState.State.IDLE,
                FactoryThreadState.Reason.NONE,
                0,
                new UpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 1))));
    var level = helper.getLevel();
    var pos = factory.structurePosition().offset(-1, 0, 1);
    var bus = new CountingBus(pos, level.getBlockState(pos));
    level.removeBlockEntity(pos);
    level.setBlockEntity(bus);
    for (int i = 0; i < 200 && RecipeExecutionTicket.capture(factory).isEmpty(); i++) {
      StructureService.advance(level, 512, 64, 50_000_000L);
    }
    return new LockedFixture(factory, bus, MachineRecipeService.catalogFor(factory).orElseThrow());
  }

  @GameTest(template = "empty16")
  public static void parallelStartReusesOutputCapacityAndRechecksFilledSlots(GameTestHelper helper) {
    var f = lockedFactory(helper, CAPACITY);
    f.bus().inventory().setStackInSlot(0, new ItemStack(Items.ECHO_SHARD, 2));
    var output = (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(f.factory().structurePosition().offset(1, 0, 1));
    var registries = helper.getLevel().registryAccess();
    var saved = new ItemStackHandler(SLOTS);
    for (int slot = 0; slot < SLOTS; slot++) {
      saved.setStackInSlot(slot, markedIron(slot == 0 ? 62 : 64));
    }
    var tag = output.saveWithFullMetadata(registries);
    tag.getCompound("mmcece").put("items", saved.serializeNBT(registries));
    output.loadWithComponents(tag, registries);
    helper.assertValueEqual(output.inventory().getSlots(), SLOTS, "All occupied output slots retained");
    var search = RecipeSearch.beginFactory(f.factory(), THREAD, f.catalog(), Set.of(CAPACITY.toString())).orElseThrow();
    var plan = search.advance(65536).plan().orElseThrow();
    int control = CapacityMarker.comparisons;
    helper.assertTrue(
        plan.recipe().outputs().getFirst().key().matches(output.inventory().getStackInSlot(0))
            && CapacityMarker.comparisons > control,
        "The probe observes matching a stored output stack");
    helper.assertValueEqual(plan.maxRepeatableOutputs(), 2, "Two outputs fit before the start");
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1),
        FactoryParallelStarter.Result.CHECK_PENDING,
        "Two-run start still preparing");
    int before = CapacityMarker.comparisons;
    for (int retry = 0; retry < 16; retry++) {
      helper.assertValueEqual(plan.maxRepeatableOutputs(), 2, "Repeat admission on the waiting plan");
    }
    helper.assertValueEqual(
        CapacityMarker.comparisons - before,
        0,
        "Repeated capacity lookups re-compared occupied output slots");
    long revision = output.resourceRevision();
    output.inventory().getStackInSlot(0).grow(2);
    helper.assertValueEqual(output.resourceRevision(), revision, "In-place output change retains the revision");
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 65536),
        FactoryParallelStarter.Result.STALE,
        "Final check rejects the filled bus");
    helper.assertValueEqual(f.bus().inventory().getStackInSlot(0).getCount(), 2, "Rejected start preserves input");
    helper.assertTrue(f.factory().run(THREAD).isEmpty(), "Rejected batch installs no runs");
    helper.succeed();
  }

  private static void loadSparseInput(GameTestHelper helper, CountingBus bus, int paymentSlot) {
    var registries = helper.getLevel().registryAccess();
    var saved = new ItemStackHandler(SLOTS);
    saved.setStackInSlot(paymentSlot == 0 ? SLOTS - 1 : 0, new ItemStack(Items.COBBLESTONE));
    saved.setStackInSlot(paymentSlot, new ItemStack(Items.DIRT, 8));
    var tag = bus.saveWithFullMetadata(registries);
    tag.getCompound("mmcece").put("items", saved.serializeNBT(registries));
    bus.loadWithComponents(tag, registries);
    helper.assertValueEqual(bus.inventory().getSlots(), SLOTS, "Sparse bus retains all 1024 physical slots");
  }

  @GameTest(template = "empty16")
  public static void lockedDeductionsUseTheSlotFoundByTheInputFilter(GameTestHelper helper) {
    var fixture = lockedFactory(helper);
    loadSparseInput(helper, fixture.bus(), 0);
    var cold = fixture.search(null);
    helper.assertValueEqual(
        cold.advance(65_536).plan().orElseThrow().deductions().getFirst().slot().slot(),
        0,
        "Cold search first-slot payment");
    var hint = cold.lockedRestart(LOCKED.toString(), fixture.factory().threadConfiguration().revision());
    helper.assertTrue(hint != null, "Exact recipe has a locked restart");
    var first = fixture.search(hint);
    var firstPlan = first.advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(firstPlan.deductions().getFirst().slot().slot(), 0, "First-slot payment");
    loadSparseInput(helper, fixture.bus(), SLOTS - 1);
    var coldLast = fixture.search(null);
    var coldLastPlan = coldLast.advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(
        coldLastPlan.deductions().getFirst().slot().slot(),
        SLOTS - 1,
        "Cold search last-slot payment");
    helper.assertValueEqual(coldLast.totalWork(), cold.totalWork(), "Cold search reuses its filtered slots");
    var last = fixture.search(hint);
    var lastPlan = last.advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(lastPlan.deductions().getFirst().slot().slot(), SLOTS - 1, "Last-slot payment");
    helper.assertValueEqual(last.totalWork(), first.totalWork(), "Moving the only match does not add a second scan");
    helper.succeed();
  }

  @GameTest(template = "empty16")
  public static void tinyFactorySlicesReuseInputCapacityAndStillRejectStaleStock(GameTestHelper helper) {
    var f = lockedFactory(helper);
    int slot = SLOTS - 1;
    loadSparseInput(helper, f.bus(), slot);
    var inventory = f.bus().inventory();
    var plan = f.search(null).advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(plan.maxRepeatableInputs(1), 1, "Small capacity cap");
    helper.assertValueEqual(plan.maxRepeatableInputs(8), 4, "Higher cap sees all eight dirt");
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1),
        FactoryParallelStarter.Result.CHECK_PENDING,
        "First batch is pending");
    inventory.setStackInSlot(slot, new ItemStack(Items.DIRT, 7));
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1),
        FactoryParallelStarter.Result.STALE,
        "Stamped input change invalidates pending batch");

    plan = f.search(null).advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1),
        FactoryParallelStarter.Result.CHECK_PENDING,
        "Second batch is pending");
    long stamp = f.bus().resourceRevision();
    inventory.getStackInSlot(slot).shrink(1);
    helper.assertValueEqual(f.bus().resourceRevision(), stamp, "In-place edit bypasses the resource stamp");
    var result = FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 65_536);
    helper.assertValueEqual(result, FactoryParallelStarter.Result.STALE, "Commit rechecks unstamped live contents");
    helper.assertValueEqual(inventory.getStackInSlot(slot).getCount(), 6, "Stale batches debit nothing");
    helper.assertTrue(f.factory().run(THREAD).isEmpty(), "Stale batches install no runs");

    plan = f.search(null).advance(65_536).plan().orElseThrow();
    helper.assertValueEqual(
        FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1),
        FactoryParallelStarter.Result.CHECK_PENDING,
        "Fresh batch began preparing");
    f.bus().inventoryReads = 0;
    for (int paused = 0; paused < 16; paused++) {
      helper.assertValueEqual(
          FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 0),
          FactoryParallelStarter.Result.CHECK_PENDING,
          "An exhausted slice keeps the batch pending");
    }
    helper.assertTrue(
        f.bus().inventoryReads < SLOTS,
        "Paused batch scanned live input slots: " + f.bus().inventoryReads + " inventory accesses");
    int calls = 1;
    do {
      result = FactoryParallelStarter.commit(f.factory(), THREAD, plan, 2, 1);
      calls++;
    } while (result == FactoryParallelStarter.Result.CHECK_PENDING && calls < SLOTS * 8);
    helper.assertValueEqual(result, FactoryParallelStarter.Result.STARTED, "One-unit slices finish the fresh batch");
    helper.assertTrue(calls < 128, "Sparse batch should reuse the locked search's last-slot match: " + calls);
    helper.assertTrue(
        f.bus().inventoryReads < SLOTS * 128,
        "Live inventory accesses across " + calls + " commits: " + f.bus().inventoryReads);
    helper.assertValueEqual(inventory.getStackInSlot(slot).getCount(), 2, "Two runs paid four dirt exactly once");
    helper.succeed();
  }

  private static RecipeStartPlan resume(GameTestHelper helper, IntFunction<RecipeStartPlan.BatchInputs> prepare) {
    var batch = prepare.apply(0);
    helper.assertValueEqual(batch.workUsed(), 0, "Zero budget consumed no batch work");
    int steps = 0;
    while (!batch.complete() && steps < 16384) {
      helper.assertTrue(batch.plan() == null, "Pending batch has no payable plan");
      batch = prepare.apply(1);
      helper.assertTrue(batch.workUsed() <= 1, "Batch stayed within its remaining work");
      steps++;
    }
    helper.assertTrue(
        batch.complete() && batch.plan() != null && steps >= SLOTS,
        "Batch resumes across every physical slot");
    return batch.plan();
  }

  private static void assertAllSlots(GameTestHelper helper, List<RecipeStartPlan.Deduction> debits) {
    helper.assertValueEqual(debits.size(), SLOTS, "One physical deduction per occupied slot");
    for (int slot = 0; slot < SLOTS; slot++) {
      helper.assertTrue(
          debits.get(slot).slot().slot() == slot && debits.get(slot).count() == 1,
          "Full debit for slot " + slot);
    }
  }

  private static void assertFullInput(GameTestHelper helper, MachinePortBlockEntity bus) {
    for (int slot = 0; slot < SLOTS; slot++) {
      helper.assertValueEqual(
          bus.inventory().getStackInSlot(slot).getCount(),
          1,
          "Planning retained stock in slot " + slot);
    }
  }
}
