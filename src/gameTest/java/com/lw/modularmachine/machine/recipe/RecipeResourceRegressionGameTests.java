package com.lw.modularmachine.machine.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.api.recipe.MachineRecipeBuilder;
import com.lw.modularmachine.api.recipe.RecipeSelectors;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.recipe.resource.RecipeResourceDefinitions;
import com.lw.modularmachine.machine.recipe.search.CompiledRecipeSet;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipePlanner;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements.RequiredPort;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.registry.MachineRegistries;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.lw.modularmachine.api.component.ResourceType;

/**
 * Focused physical-payment, selector persistence and parallel admission
 * regressions.
 */
@GameTestHolder("mmcece_resource_regression")
@PrefixGameTestTemplate(false)
public final class RecipeResourceRegressionGameTests {
  private static final ResourceLocation MACHINE = ResourceLocation.parse("mmcece:test_recipe_resources");
  private static final int[][] OFFSETS = {{-1, 0, 1}, {1, 0, 1}, {0, 1, 1}, {0, -1, 1}, {-1, 1, 1}, {1, -1, 1}};
  private static final String[] PORTS = {"item_input_bus_normal", "item_output_bus_normal", "fluid_input_hatch_normal",
      "fluid_output_hatch_normal", "energy_input_hatch_normal", "energy_output_hatch_normal"};

  private RecipeResourceRegressionGameTests() {
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void factory_output_capture_reuses_only_current_role_sets(GameTestHelper helper) {
    var owner = build(helper, true).owner();
    var item = new RequiredPort(PortKind.ITEM_OUTPUT, "");
    var fluid = new RequiredPort(PortKind.FLUID_OUTPUT, "");
    var output = (MachinePortBlockEntity) helper.getLevel().getBlockEntity(owner.structurePosition().offset(1, 0, 1));
    output.inventory().setStackInSlot(0, new ItemStack(Items.STICK));
    var roles = new HashSet<>(Set.of(item));
    var original = RecipeResourceCapture.captureOutputs(owner, roles).orElseThrow();
    roles.add(fluid);
    var combined = RecipeResourceCapture.captureOutputs(owner, roles).orElseThrow();
    helper.assertTrue(
        combined != original && combined.ports().stream().anyMatch(p -> p.kind() == PortKind.FLUID_OUTPUT),
        "Changing a caller-owned role set changed the cached item-only view");
    helper.assertTrue(
        RecipeResourceCapture.captureOutputs(owner, Set.of(item)).orElseThrow() == original,
        "Equal output roles rebuilt an unchanged same-tick capture");
    var all = RecipeResourceCapture.captureOutputs(owner, null).orElseThrow();
    helper.assertTrue(
        all != original && all == RecipeExecutionTicket.captureOutputs(owner).orElseThrow(),
        "Unfiltered output capture reused the item-only view");
    long revision = output.resourceRevision();
    output.inventory().getStackInSlot(0).grow(1);
    helper.assertTrue(output.resourceRevision() == revision, "Fixture did not exercise an unstamped slot edit");
    var updated = RecipeResourceCapture.captureOutputs(owner, Set.of(item)).orElseThrow();
    helper.assertTrue(
        updated != original && updated.ports().getFirst().itemAmount(0) == 2,
        "Reusing a filtered output capture ignored a live slot edit");
    helper.runAfterDelay(1, () -> {
      var later = RecipeResourceCapture.captureOutputs(owner, Set.of(item)).orElseThrow();
      helper.assertTrue(
          later != updated && later.ports().getFirst().itemAmount(0) == 2,
          "Filtered output capture crossed the server tick boundary");
      helper.succeed();
    });
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void same_tick_factory_search_reuses_and_revalidates_physical_ports(GameTestHelper helper) {
    var owner = build(helper, true).owner();
    var first = RecipeExecutionTicket.captureForSearch(owner).orElseThrow();
    var second = RecipeExecutionTicket.captureForSearch(owner).orElseThrow();
    helper.assertTrue(first.ports() == second.ports(), "Two searches rebuilt an unchanged physical port list");
    helper.assertTrue(!first.sharesCapture(second), "Separate searches shared commit authority");
    var independent = RecipeExecutionTicket.capture(owner).orElseThrow();
    helper.assertTrue(first.ports() != independent.ports(), "An independent full capture reused the search ticket");

    var input = input(helper, first);
    long revision = input.resourceRevision();
    input.inventory().getStackInSlot(0).set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
    helper.assertTrue(revision == input.resourceRevision(), "Fixture did not exercise an unstamped component edit");
    helper.assertTrue(!first.dependenciesCurrent(), "Old input proof survived an in-place component edit");
    var updated = RecipeExecutionTicket.captureForSearch(owner).orElseThrow();
    var itemPort = updated.ports().stream().filter(port -> port.identity() == input).findFirst().orElseThrow();
    helper.assertTrue(
        Component.literal("changed").equals(itemPort.items().getFirst().get(DataComponents.CUSTOM_NAME)),
        "A same-tick search returned stale item components");

    helper.assertTrue(input.configureInputGroup(1, input.groupRevision()), "Could not isolate the edited input");
    var regrouped = RecipeExecutionTicket.captureForSearch(owner).orElseThrow();
    helper.assertTrue(
        regrouped.ports().stream().noneMatch(port -> port.identity() == input)
            && regrouped.withInputGroup(1).ports().stream().anyMatch(port -> port.identity() == input),
        "The cached shared group retained a reassigned port");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void later_group_can_win_and_rejections_are_local_to_each_group(GameTestHelper helper) {
    var ticket = build(helper, false);
    var items = input(helper, ticket);
    helper.assertTrue(items.configureInputGroup(1, items.groupRevision()), "Could not isolate the dirt input");
    var fluid = (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(ticket.owner().structurePosition().offset(0, 1, 1));
    helper.assertTrue(fluid.inputGroup() == InputGroup.SHARED, "Fixture needs a shared water input");
    fluid.fluidTank().setFluid(new FluidStack(Fluids.WATER, 100));

    var itemId = ResourceLocation.parse("mmcece:a_group_dirt");
    var fluidId = ResourceLocation.parse("mmcece:z_shared_water");
    var itemRecipe = decode(helper, root());
    var fluidSource = root();
    fluidSource.add("inputs", JsonParser.parseString("[]"));
    fluidSource.add("fluid_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":100}]"));
    var fluidRecipe = decode(helper, fluidSource);
    var registries = helper.getLevel().registryAccess();
    var catalog = RecipeCatalog.fromDefinitions(
        MACHINE,
        List.of(fluidRecipe.compile(fluidId, registries), itemRecipe.compile(itemId, registries)),
        Map.of(fluidId.toString(), fluidRecipe, itemId.toString(), itemRecipe),
        () -> true,
        null,
        List.of());

    var search = RecipeSearch.begin((MachineControllerBlockEntity) ticket.owner(), catalog).orElseThrow();
    var first = search.advance(4096).plan().orElseThrow();
    helper.assertTrue(
        first.recipe().id().equals(itemId.toString()) && first.source().inputGroup() == 1,
        "Earlier recipe ID in group 1 lost to the shared group's first match");
    helper.assertTrue(search.rejectSelected(first), "Could not reject the group 1 dirt plan");

    var second = search.advance(4096).plan().orElseThrow();
    helper.assertTrue(
        second.recipe().id().equals(fluidId.toString()) && second.source().inputGroup() == InputGroup.SHARED,
        "Shared water was not restored after rejecting the better group");
    helper.assertTrue(search.rejectSelected(second), "Could not reject the shared water plan");

    var third = search.advance(4096).plan().orElseThrow();
    helper.assertTrue(
        third.recipe().id().equals(fluidId.toString()) && third.source().inputGroup() == 1,
        "Rejecting shared water also removed the same recipe from group 1");
    helper.assertTrue(search.rejectSelected(third), "Could not reject the group 1 water plan");
    helper.assertTrue(
        search.advance(4096).status() == RecipePlanner.Status.NO_MATCH,
        "An already rejected plan returned");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void paid_fluid_and_item_tickets_check_only_required_contents_and_all_group_routes(GameTestHelper helper) {
    var owner = build(helper, true).owner();
    var items = input(helper, RecipeExecutionTicket.capture(owner).orElseThrow());
    var fluid = RecipeExecutionTicket.captureTickFluids(owner).orElseThrow();
    var item = RecipeExecutionTicket.captureTickItems(owner).orElseThrow();
    helper.assertTrue(
        fluid.ports().stream().allMatch(port -> port.kind().resource() == ResourceType.FLUID),
        "Fluid planning captured unrelated contents");
    helper.assertTrue(item.currentForCommit().orElseThrow() == item, "Same-tick commit recaptured the inventory");
    items.inventory().getStackInSlot(0).grow(1);
    helper.assertTrue(item.currentForCommit().isEmpty(), "In-place stack edit escaped same-tick validation");
    helper.assertTrue(RecipeCommitGuard.validate(fluid), "Unrelated item contents invalidated the fluid ticket");
    helper.assertTrue(items.configureInputGroup(1, items.groupRevision()), "Group edit failed");
    helper.assertTrue(!RecipeCommitGuard.validate(fluid), "Group change escaped the narrow fluid capture");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void independent_runs_reserve_and_pay_whole_fuels_separately(GameTestHelper helper) {
    var ticket = build(helper, true);
    var input = input(helper, ticket);
    var source = root();
    source.add("inputs", JsonParser.parseString("[]"));
    source.add("item_payments", JsonParser.parseString("""
        {"fuels":[{"burn_time":100,"parallel_unaffected":true}]}
        """));
    var recipe = decode(helper, source);
    input.inventory().setStackInSlot(0, new ItemStack(Items.COAL));
    ticket = RecipeExecutionTicket.capture(ticket.owner()).orElseThrow();
    helper
        .assertTrue(!RecipeItemPayments.available(ticket, recipe, 2, false), "One coal admitted two independent runs");
    helper.assertTrue(
        RecipeItemPayments.available(ticket, recipe, 2, true),
        "Aggregated fixed fuel stopped sharing its payment");
    input.inventory().setStackInSlot(0, new ItemStack(Items.COAL, 2));
    ticket = RecipeExecutionTicket.capture(ticket.owner()).orElseThrow();
    helper.assertTrue(RecipeItemPayments.limit(ticket, recipe, 8, false) == 2, "Factory fuel limit shared excess heat");
    var changes = new HashMap<String, NonNullList<ItemStack>>();
    helper.assertTrue(RecipeItemPayments.apply(ticket, recipe, 2, false, 42, changes), "Independent payment failed");
    helper.assertTrue(
        changes.values().stream().flatMap(List::stream).allMatch(ItemStack::isEmpty),
        "Independent runs paid fewer than two coal");
    helper.assertTrue(
        input.inventory().getStackInSlot(0).getCount() == 2,
        "Preparation wrote physical fuel before commit");
    source.getAsJsonObject("item_payments").getAsJsonArray("fuels").get(0).getAsJsonObject()
        .addProperty("parallel_unaffected", false);
    changes.clear();
    helper.assertTrue(
        RecipeItemPayments.apply(ticket, decode(helper, source), 2, true, 42, changes),
        "Aggregate payment failed");
    helper.assertTrue(
        changes.values().stream().flatMap(List::stream).mapToInt(ItemStack::getCount).sum() == 1,
        "Ordinary aggregate stopped rounding its combined demand once");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void zero_chance_still_proves_each_run_and_container_fuels_return_each_bucket(GameTestHelper helper) {
    var ticket = build(helper, true);
    var input = input(helper, ticket);
    var source = root();
    source.add("inputs", JsonParser.parseString("[]"));
    source.add("item_payments", JsonParser.parseString("{\"fuels\":[{\"burn_time\":100,\"chance\":0}]}"));
    var recipe = decode(helper, source);
    input.inventory().setStackInSlot(0, new ItemStack(Items.COAL));
    ticket = RecipeExecutionTicket.capture(ticket.owner()).orElseThrow();
    helper.assertTrue(
        !RecipeItemPayments.available(ticket, recipe, 2, false),
        "Zero chance bypassed the full fuel proof");
    input.inventory().setStackInSlot(1, new ItemStack(Items.COAL));
    ticket = RecipeExecutionTicket.capture(ticket.owner()).orElseThrow();
    var changes = new HashMap<String, NonNullList<ItemStack>>();
    helper.assertTrue(
        RecipeItemPayments.apply(ticket, recipe, 2, false, 42, changes) && changes.isEmpty(),
        "Zero chance consumed fuel");
    input.inventory().setStackInSlot(0, new ItemStack(Items.LAVA_BUCKET));
    input.inventory().setStackInSlot(1, new ItemStack(Items.LAVA_BUCKET));
    ticket = RecipeExecutionTicket.capture(ticket.owner()).orElseThrow();
    source.getAsJsonObject("item_payments").getAsJsonArray("fuels").get(0).getAsJsonObject().addProperty("chance", 1);
    helper.assertTrue(
        RecipeItemPayments.apply(ticket, decode(helper, source), 2, false, 42, changes),
        "Container payment failed");
    helper.assertTrue(
        changes.values().stream().flatMap(List::stream).filter(stack -> stack.is(Items.BUCKET)).count() == 2,
        "Container fuel shared heat or lost a bucket");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void start_and_finish_fluid_rows_keep_distinct_selectors_through_the_native_codec(GameTestHelper helper) {
    var registries = helper.getLevel().registryAccess();
    var water = new FluidStack(Fluids.WATER, 50);
    var defaults = new RecipeSelectors("feed", "waste", "", "coolant");
    var source = MachineRecipeBuilder.create(ResourceLocation.parse("mmcece:mmcece/routed_fluid"), MACHINE, 5)
        .selectors(defaults).fluidInput(water).fluidInput(water, 1, "").fluidInput(water, 1, "rinse").fluidOutput(water)
        .fluidOutput(water, 0.5, "").fluidOutput(water, 1, "steam").fluidPerTickInput(water).toJson(registries);
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    try {
      MachineRecipe.STREAM_CODEC.encode(buffer, decode(helper, source));
      var recipe = MachineRecipe.STREAM_CODEC.decode(buffer);
      helper.assertTrue(
          recipe.fluidInputRequirements().stream().map(row -> row.selector(defaults.fluidInput())).toList()
              .equals(List.of("feed", "", "rinse")),
          "Native codec changed startup fluid selectors");
      helper.assertTrue(
          recipe.fluidOutputRequirements().stream().map(row -> row.selector(defaults.fluidOutput())).toList()
              .equals(List.of("waste", "", "steam")),
          "Native codec changed completion fluid selectors");
      helper.assertTrue(
          recipe.guaranteedFluidOutputRequirements().stream().map(row -> row.selector(defaults.fluidOutput())).toList()
              .equals(List.of("waste", "steam")),
          "Guaranteed output declarations lost their selectors or included an optional output");
      helper.assertTrue(
          recipe.fluidPerTickInputs().getFirst().selector(defaults.fluidPerTick()).equals("coolant"),
          "An omitted builder selector replaced the tick phase default");
    } finally {
      buffer.release();
    }
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void explicit_empty_fluid_selector_survives_wire_freeze_and_saved_run(GameTestHelper helper) {
    var ticket = build(helper, false);
    var source = root();
    source.add("selectors", JsonParser.parseString("{\"fluid_per_tick\":\"tagged\"}"));
    source.add(
        "fluid_per_tick_inputs",
        JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":10,\"selector\":\"\"}]"));
    var recipe = decode(helper, source);
    var registries = helper.getLevel().registryAccess();
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
    try {
      Recipe.STREAM_CODEC.encode(buffer, recipe);
      recipe = (MachineRecipe) Recipe.STREAM_CODEC.decode(buffer);
    } finally {
      buffer.release();
    }
    helper.assertTrue(
        RecipeResourceRequirements.missing(ticket, recipe).isEmpty(),
        "Explicit empty selector required a tagged hatch");
    var run = FrozenRecipeFactory
        .freeze(ResourceLocation.parse("mmcece:selector_regression"), recipe, 0, registries, ticket.ports(), -1);
    var restored = FrozenRecipeRun.read(run.save(), registries);
    var definition = restored.save().getCompound("definition");
    var row = definition.getList("fluid_per_tick_inputs", Tag.TAG_COMPOUND).getCompound(0);
    helper.assertTrue(
        row.contains("selector", Tag.TAG_STRING) && row.getString("selector").isEmpty(),
        "Frozen encoding omitted the explicit empty selector");
    var effective = RecipeTriggeredResources.effective(restored, restored.resourceData(registries))
        .get(RecipeResourceDefinitions.FLUID);
    helper.assertTrue(
        effective.perTickInputs().getFirst().selector().isEmpty(),
        "Runtime replaced explicit empty with the phase default");
    source.getAsJsonArray("fluid_per_tick_inputs").get(0).getAsJsonObject().remove("selector");
    var omitted = decode(helper, source);
    helper.assertTrue(
        omitted.fluidPerTickInputs().getFirst().selector("tagged").equals("tagged"),
        "Omitted selector stopped inheriting the phase default");
    helper.assertTrue(
        RecipeResourceRequirements.missing(ticket, omitted).isPresent(),
        "Omitted selector ignored the tagged phase default");
    var encoded = MachineRecipe.CODEC.codec()
        .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), omitted).getOrThrow().getAsJsonObject();
    helper.assertTrue(
        !encoded.getAsJsonArray("fluid_per_tick_inputs").get(0).getAsJsonObject().has("selector"),
        "Omitted selector became explicitly empty during encoding");
    source.remove("fluid_per_tick_inputs");
    source.add("fluid_per_tick", JsonParser.parseString("{\"id\":\"minecraft:water\",\"amount\":10}"));
    helper.assertTrue(
        decode(helper, source).fluidPerTickInputs().getFirst().selector("tagged").equals("tagged"),
        "Legacy tick input stopped inheriting the phase default");
    helper.succeed();
  }

  @GameTest(template = "empty16", timeoutTicks = 80)
  public static void factory_tick_fluids_use_per_run_int_bounds_and_long_batch_totals(GameTestHelper helper) {
    var ticket = build(helper, true);
    var source = root();
    source.add("fluid_per_tick_inputs", JsonParser.parseString("[{\"id\":\"minecraft:water\",\"amount\":1500000000}]"));
    source.add("fluid_per_tick_outputs", JsonParser.parseString("[{\"id\":\"minecraft:lava\",\"amount\":1500000000}]"));
    var recipe = decode(helper, source);
    helper.assertTrue(
        admit(helper, ticket, recipe, false, UpgradeSnapshot.identity(0)) == 2,
        "Independent runs were restricted by aggregate int fluid totals");
    helper.assertTrue(
        admit(helper, ticket, recipe, true, UpgradeSnapshot.identity(0)) == 1,
        "Aggregated fluid stacks exceeded int bounds");
    source.remove("fluid_per_tick_outputs");
    var cheaper = new UpgradeSnapshot(0, 2, 1, 1, 1, 1, 1, 2);
    helper.assertTrue(
        admit(helper, ticket, decode(helper, source), true, cheaper) == 2,
        "Admission used pre-upgrade fluid amounts");
    helper.succeed();
  }

  private static int admit(GameTestHelper helper, RecipeExecutionTicket ticket, MachineRecipe recipe, boolean aggregate, UpgradeSnapshot upgrades) {
    var id = ResourceLocation.parse("mmcece:fluid_admission_regression");
    var candidate = recipe.compile(id, helper.getLevel().registryAccess());
    var catalog = new RecipeCatalog(
        recipe.machineId(),
        new CompiledRecipeSet<>(List.of(candidate)),
        Map.of(),
        Map.of(),
        () -> true,
        null,
        Map.of(id.toString(), recipe),
        List.of());
    var plan = RecipeStartPlan.prepared(ticket, catalog, candidate, List.of(), List.of(), 42, null);
    return new ParallelRecipeAdmission(plan, recipe, 2, aggregate, upgrades).advance(4096);
  }

  private static JsonObject root() {
    return JsonParser.parseString("""
        {"machine":"mmcece:test_recipe_resources","duration":20,
         "inputs":[{"stack":{"id":"minecraft:dirt"},"amount":1}],
         "outputs":[{"stack":{"id":"minecraft:stone"},"amount":1}]}
        """).getAsJsonObject();
  }

  private static MachineRecipe decode(GameTestHelper helper, JsonObject source) {
    return MachineRecipe.CODEC.codec()
        .parse(helper.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE), source).getOrThrow();
  }

  private static MachinePortBlockEntity input(GameTestHelper helper, RecipeExecutionTicket ticket) {
    return (MachinePortBlockEntity) helper.getLevel()
        .getBlockEntity(ticket.owner().structurePosition().offset(-1, 0, 1));
  }

  private static RecipeExecutionTicket build(GameTestHelper helper, boolean factory) {
    var level = helper.getLevel();
    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    level.setBlockAndUpdate(
        origin,
        factory ? FactoryControllerBlock.withOrientation(
            MachineRegistries.FACTORY_CONTROLLER.get().defaultBlockState(),
            MachineOrientation.IDENTITY) : MachineControllerBlock
                .withOrientation(MachineRegistries.CONTROLLER.get().defaultBlockState(), MachineOrientation.IDENTITY));
    for (int i = 0; i < OFFSETS.length; i++) {
      level.setBlockAndUpdate(
          origin.offset(OFFSETS[i][0], OFFSETS[i][1], OFFSETS[i][2]),
          MachineRegistries.PORTS.get(PORTS[i]).get().defaultBlockState());
    }
    var owner = (StructureService.StructureSubject) level.getBlockEntity(origin);
    if (owner instanceof FactoryControllerBlockEntity controller) {
      controller.bindMachine(MACHINE);
    } else {
      ((MachineControllerBlockEntity) owner).bindMachine(MACHINE);
    }
    for (int i = 0; i < 200 && RecipeExecutionTicket.capture(owner).isEmpty(); i++) {
      StructureService.advance(level, 512, 64, 50_000_000L);
    }
    var input = (MachinePortBlockEntity) level.getBlockEntity(origin.offset(-1, 0, 1));
    input.inventory().setStackInSlot(0, new ItemStack(Items.DIRT, 2));
    return RecipeExecutionTicket.capture(owner).orElseThrow();
  }
}
