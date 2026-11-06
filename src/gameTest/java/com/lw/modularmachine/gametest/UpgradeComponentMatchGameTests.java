package com.lw.modularmachine.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntryResolver;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class UpgradeComponentMatchGameTests {
  private static final ResourceLocation PAPER = ResourceLocation.fromNamespaceAndPath("minecraft", "paper");
  private static final ResourceLocation MACHINE = id("test_upgrade_bus_binding");
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  @AfterBatch(batch = "upgrade_component_matching")
  public static void cleanup(ServerLevel level) {
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "upgrade_component_matching", timeoutTicks = 600)
  public static void exact_upgrade_components_precede_generic_rules_without_machine_fallback(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Exact upgrade reload failed: " + failure);
            return;
          }
          GameTestUpgradeFixtures.install();
          savedDefinitions = FactoryUpgradeService.snapshot().definitions();
          try {
            verify(helper);
            helper.succeed();
          } finally {
            cleanup(helper.getLevel());
          }
        })));
  }

  private static void verify(GameTestHelper helper) {
    var snapshot = FactoryUpgradeService.snapshot();
    var named = paper("Exact Upgrade", 2);
    var different = paper("Other Upgrade", 2);
    var data = new ItemStack(Items.PAPER, 2);
    var custom = new CompoundTag();
    custom.putInt("variant", 1);
    data.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    var plain = new ItemStack(Items.PAPER, 2);
    var before = named.copy();
    helper.assertTrue(snapshot.byItem().get(PAPER).size() == 4, "Same-item component variants were not indexed");
    assertEffect(helper, named, "test_component_named", 8);
    assertEffect(helper, different, "test_component_fallback", 2);
    assertEffect(helper, data, "test_component_data", 4);
    assertEffect(helper, plain, "test_component_default", 6);
    helper.assertTrue(
        snapshot.match(named).orElseThrow() == snapshot.match(named.copyWithCount(64)).orElseThrow()
            && ItemStack.matches(before, named),
        "Exact component identity depends on count or modifies the input");
    var extra = named.copy();
    extra.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
    assertEffect(helper, extra, "test_component_fallback", 2);
    var damaged = new ItemStack(Items.IRON_PICKAXE);
    damaged.setDamageValue(4);
    assertEffect(helper, damaged, "test_component_damage", 6);
    damaged.setDamageValue(5);
    helper.assertTrue(
        !FactoryUpgradeEntryResolver.isUpgrade(damaged) && FactoryUpgradeEntryResolver.resolve(damaged).isEmpty(),
        "An unmatched damage variant became an upgrade without a fallback");

    try {
      snapshot.byItem().get(PAPER).clear();
      helper.fail("Published component groups were mutable");
    } catch (UnsupportedOperationException expected) {
      helper.assertTrue(snapshot.byItem().get(PAPER).size() == 4, "Published component group was altered");
    }
    var namedDefinition = snapshot.match(named).orElseThrow();
    namedDefinition.components().addProperty("minecraft:custom_name", "\"Mutated copy\"");
    assertEffect(helper, named, "test_component_named", 8);
    verifyRejectedCandidates(helper, snapshot);

    var origin = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, origin, MACHINE);
    var bus = UpgradeBusGameTests.placeBus(helper, origin.south(), "normal");
    helper.getLevel().setBlockAndUpdate(origin.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    bus.inventory().setStackInSlot(0, named.copy());
    UpgradeBusGameTests.confirm(helper, factory);
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 9,
        "Exact named effect was not applied");
    bus.inventory().setStackInSlot(0, data.copy());
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 5,
        "Changing components retained the previous effect");
    bus.inventory().setStackInSlot(0, named.copy());
    var restricted = new ArrayList<>(snapshot.definitions());
    restricted.replaceAll(
        definition -> definition.effect().id().equals(namedDefinition.effect().id()) ? new FactoryUpgradeDefinition(
            definition.item(),
            definition.effect(),
            new FactoryUpgradeCompatibility(Set.of(id("different_machine")), Set.of()),
            definition.components()) : definition);
    FactoryUpgradeService.replaceDefinitions(restricted);
    bus.serverTick();
    factory.serverTick();
    var denied = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        denied.valid()
            && denied.entries().isEmpty()
            && denied.incompatible().equals(List.of(namedDefinition.effect().id()))
            && factory.scheduler().channels().getFirst().parallelLimit() == 1
            && FactoryUpgradeEntryResolver.resolve(named, MACHINE).isEmpty(),
        "A machine-incompatible exact match fell back to the generic upgrade");

    var withoutNamed = snapshot.definitions().stream()
        .filter(definition -> !definition.effect().id().equals(namedDefinition.effect().id())).toList();
    FactoryUpgradeService.replaceDefinitions(withoutNamed);
    bus.serverTick();
    factory.serverTick();
    var fallback = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 3
            && fallback.incompatible().isEmpty()
            && fallback.entries().getFirst().id().equals(id("test_component_fallback"))
            && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
        "Removing an exact definition did not expose the generic rule without rewriting the item");

    var exactOnly = withoutNamed.stream()
        .filter(definition -> !definition.item().equals(PAPER) || definition.exactComponents()).toList();
    FactoryUpgradeService.replaceDefinitions(exactOnly);
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid()
            && bus.upgradeCatalog().entries().isEmpty()
            && factory.scheduler().channels().getFirst().parallelLimit() == 1,
        "Unmatched ordinary component variants invalidated the bus or retained a stale effect");
  }

  private static void verifyRejectedCandidates(GameTestHelper helper, FactoryUpgradeService.Snapshot snapshot) {
    var effect = new FactoryUpgradeEntry(id("component_conflict"), 1, 1, 1, 1, 1, 1, 1);
    var unknown = new JsonObject();
    unknown.add("mmcece:absent_component", new JsonObject());
    var invalid = new FactoryUpgradeDefinition(PAPER, effect, FactoryUpgradeCompatibility.unrestricted(), unknown);
    var duplicateDefault = new FactoryUpgradeDefinition(
        PAPER,
        effect,
        FactoryUpgradeCompatibility.unrestricted(),
        new JsonObject());
    var duplicateGeneric = new FactoryUpgradeDefinition(PAPER, effect);
    for (var definition : List.of(invalid, duplicateDefault, duplicateGeneric)) {
      var definitions = new ArrayList<>(snapshot.definitions());
      definitions.add(definition);
      try {
        FactoryUpgradeService.replaceDefinitions(definitions);
        helper.fail("Unknown component or duplicate upgrade matcher was published");
      } catch (JsonParseException expected) {
        helper.assertTrue(
            FactoryUpgradeService.snapshot() == snapshot,
            "Rejected matcher partially replaced the catalog");
      }
    }
  }

  private static void assertEffect(GameTestHelper helper, ItemStack stack, String definition, int parallel) {
    var selected = FactoryUpgradeEntryResolver.resolve(stack).orElseThrow();
    helper.assertTrue(
        selected.id().equals(id(definition)) && selected.parallelAdd() == parallel,
        "Wrong component upgrade: " + stack + " -> " + selected);
  }

  private static ItemStack paper(String name, int count) {
    var stack = new ItemStack(Items.PAPER, count);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));

    return stack;
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
