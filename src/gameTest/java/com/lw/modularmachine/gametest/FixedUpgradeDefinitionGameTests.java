package com.lw.modularmachine.gametest;

import com.google.gson.JsonParseException;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCompatibility;
import com.lw.modularmachine.machine.factory.FactoryUpgradeDefinition;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEntryResolver;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.menu.UpgradeBusMenu;
import com.lw.modularmachine.network.UpgradeBusPayloads;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("mmcece")
@PrefixGameTestTemplate(false)
public final class FixedUpgradeDefinitionGameTests {
  private static List<FactoryUpgradeDefinition> savedDefinitions;

  @AfterBatch(batch = "fixed_upgrade_definitions")
  public static void cleanup(ServerLevel level) {
    if (savedDefinitions != null) {
      FactoryUpgradeService.replaceDefinitions(savedDefinitions);
      savedDefinitions = null;
    }
  }

  @GameTest(template = "empty16", batch = "fixed_upgrade_definitions", timeoutTicks = 600)
  public static void fixed_item_upgrades_reload_revoke_and_sync_without_rewriting_items(GameTestHelper helper) {
    var server = helper.getLevel().getServer();
    server.reloadResources(server.getPackRepository().getSelectedIds())
        .whenComplete((ignored, failure) -> server.execute(() -> helper.runAfterDelay(1, () -> {
          if (failure != null) {
            helper.fail("Fixed upgrade resource reload failed: " + failure);
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
    var level = helper.getLevel();
    var position = helper.absolutePos(new BlockPos(7, 5, 7));
    var factory = UpgradeBusGameTests.buildUpgradeFactory(helper, position, id("test_upgrade_bus_binding"));
    var bus = UpgradeBusGameTests.placeBus(helper, position.south(), "normal");
    level.setBlockAndUpdate(position.south().east(), Blocks.STONE_BRICKS.defaultBlockState());
    var stack = new ItemStack(Items.AMETHYST_SHARD, 2);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal("Unmodified fixed upgrade"));
    var before = stack.copy();
    helper.assertTrue(!FactoryUpgradeEntryResolver.isMarked(stack), "Fixed item fixture already had override data");
    bus.inventory().setStackInSlot(0, stack);
    UpgradeBusGameTests.confirm(helper, factory);
    factory.serverTick();
    var source = factory.upgradeSources().getFirst().catalog().snapshot();
    helper.assertTrue(
        source.parallelLimit() == 5
            && source.energyNumerator() == 9
            && source.energyDenominator() == 4
            && source.fluidNumerator() == 1
            && source.fluidDenominator() == 4
            && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
        "Fixed item definition did not compose its stack or rewrote its components");
    var payload = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        payload.valid()
            && payload.entries().getFirst().id().equals(id("test_fixed_parallel"))
            && payload.entries().getFirst().parallelAdd() == 4,
        "Menu status did not capture the server-resolved fixed effect");
    verifyPacket(helper, payload);
    var player = FakePlayerFactory.get(level, new GameProfile(new UUID(2, 71), "mmce_upgrade_menu"));
    var menu = new UpgradeBusMenu(7, player.getInventory(), bus);
    menu.acceptStatus(payload);
    menu.acceptStatus(new UpgradeBusPayloads.Status(8, payload.position(), payload.revision(), true, List.of()));
    menu.acceptStatus(
        new UpgradeBusPayloads.Status(7, payload.position().above(), payload.revision(), true, List.of()));
    helper.assertTrue(menu.status().equals(payload), "A different menu or bus replaced the active description");

    var restricted = new FactoryUpgradeDefinition(
        ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard"),
        new FactoryUpgradeEntry(id("restricted_fixed_parallel"), 2, 1, 1, 1, 1, 1, 1),
        new FactoryUpgradeCompatibility(Set.of(id("test_upgrade_bus_binding")), Set.of()));
    FactoryUpgradeService.replaceDefinitions(List.of(restricted));
    bus.unbindFactory();
    bus.refreshUpgradeDefinitions();
    var unboundStatus = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        unboundStatus.valid()
            && unboundStatus.machine() == null
            && unboundStatus.incompatible().contains(id("restricted_fixed_parallel"))
            && unboundStatus.entries().isEmpty()
            && factory.upgradeSources().isEmpty(),
        "Unbound filtered effects were applied without a machine context");
    helper.assertTrue(bus.bindFactory(factory), "Machine-compatible fixed effect could not be rebound");
    factory.serverTick();
    helper.assertTrue(
        factory.upgradeSources().size() == 1 && factory.scheduler().channels().getFirst().parallelLimit() == 5,
        "Machine-compatible fixed effect was not restored after rebinding");

    var override = before.copy();
    var data = new CompoundTag();
    data.putBoolean("mmcece:factory_upgrade", true);
    data.putInt("parallel_add", 1);
    override.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    helper.assertTrue(
        FactoryUpgradeEntryResolver.resolve(override).orElseThrow().parallelAdd() == 2,
        "Explicit per-stack effects were combined with fixed definitions twice");
    data.putInt("parallel_add", -1);
    override.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    helper.assertTrue(
        FactoryUpgradeEntryResolver.resolve(override).isEmpty(),
        "Malformed explicit effects fell back to a fixed definition");

    var replacement = new FactoryUpgradeDefinition(
        ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard"),
        new FactoryUpgradeEntry(id("replacement_fixed_parallel"), 4, 1, 1, 1, 1, 1, 1));
    FactoryUpgradeService.replaceDefinitions(List.of(replacement));
    helper.assertTrue(
        !bus.upgradeCatalogValid() && factory.upgradeSources().isEmpty(),
        "Old definition epoch continued publishing an upgrade source");
    bus.serverTick();
    factory.serverTick();
    var changed = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 9
            && changed.revision() > payload.revision()
            && changed.entries().getFirst().id().equals(id("replacement_fixed_parallel")),
        "Definition replacement did not rebuild the live bus and menu status");
    menu.acceptStatus(changed);
    menu.acceptStatus(payload);
    helper.assertTrue(menu.status().equals(changed), "A stale description overwrote a newer upgrade revision");
    var published = FactoryUpgradeService.snapshot();
    try {
      FactoryUpgradeService.replaceDefinitions(List.of(replacement, replacement));
      helper.fail("Duplicate fixed item mapping was accepted");
    } catch (JsonParseException expected) {
      helper.assertTrue(FactoryUpgradeService.snapshot() == published, "Failed compile partially published mappings");
    }
    try {
      FactoryUpgradeService
          .replaceDefinitions(List.of(new FactoryUpgradeDefinition(id("absent_item"), replacement.effect())));
      helper.fail("Unknown upgrade item was accepted");
    } catch (JsonParseException expected) {
      helper.assertTrue(FactoryUpgradeService.snapshot() == published, "Unknown item replaced the last valid catalog");
    }
    boolean workerRejected = CompletableFuture.supplyAsync(() -> {
      try {
        FactoryUpgradeService.replaceDefinitions(List.of());
        return false;
      } catch (IllegalStateException expected) {
        return true;
      }
    }).join();
    helper.assertTrue(
        workerRejected && FactoryUpgradeService.snapshot() == published,
        "Worker changed the upgrade catalog");
    FactoryUpgradeService.replaceDefinitions(List.of());
    bus.serverTick();
    factory.serverTick();
    helper.assertTrue(
        factory.scheduler().channels().getFirst().parallelLimit() == 1
            && UpgradeBusPayloads.capture(7, bus).entries().isEmpty()
            && ItemStack.matches(before, bus.inventory().getStackInSlot(0)),
        "Removing a mapping retained effects, stale descriptions, or modified the stored item");
    verifyMachineFiltering(helper, factory, bus, before);
  }

  private static void verifyMachineFiltering(GameTestHelper helper, FactoryControllerBlockEntity first, UpgradeBusBlockEntity bus, ItemStack original) {
    var firstMachine = id("test_upgrade_bus_binding");
    var secondMachine = id("test_upgrade_bus_shared_binding");
    var upgradeId = id("machine_filtered_upgrade");
    var itemId = ResourceLocation.fromNamespaceAndPath("minecraft", "amethyst_shard");
    var effect = new FactoryUpgradeEntry(upgradeId, 2, 2, 1, 1, 1, 1, 1);
    var definition = new FactoryUpgradeDefinition(
        itemId,
        effect,
        new FactoryUpgradeCompatibility(Set.of(firstMachine), Set.of(secondMachine)));
    FactoryUpgradeService.replaceDefinitions(List.of(definition));
    bus.serverTick();
    first.serverTick();
    helper.assertTrue(
        first.scheduler().channels().getFirst().parallelLimit() == 5
            && first.scheduler().channels().getFirst().upgradeSnapshot().speedNumerator() == 4,
        "Allowed machine did not receive the fixed upgrade");
    var firstStatus = UpgradeBusPayloads.capture(7, bus);

    var second = UpgradeBusGameTests.buildUpgradeFactory(helper, first.getBlockPos().east(2), secondMachine);
    UpgradeBusGameTests.confirm(helper, second);
    second.serverTick();
    helper.assertTrue(bus.isBoundTo(first), "Second machine stole the existing binding");
    helper.assertTrue(bus.bindFactory(second), "Could not bind the shared bus to the second machine");
    first.serverTick();
    second.serverTick();
    var rejected = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        rejected.valid()
            && secondMachine.equals(rejected.machine())
            && rejected.entries().isEmpty()
            && rejected.incompatible().equals(List.of(upgradeId))
            && rejected.revision() > firstStatus.revision()
            && second.scheduler().channels().getFirst().parallelLimit() == 1
            && first.scheduler().channels().getFirst().parallelLimit() == 1,
        "Rebinding retained an incompatible effect or failed to report it");
    verifyPacket(helper, rejected);

    var huge = original.copyWithCount(64);
    bus.inventory().setStackInSlot(0, huge);
    helper.assertTrue(
        FactoryUpgradeEntryResolver.resolve(huge).isEmpty()
            && bus.upgradeCatalogValid()
            && bus.upgradeCatalog().entries().isEmpty()
            && bus.incompatibleUpgrades().equals(List.of(upgradeId)),
        "An incompatible stack was multiplied before filtering and invalidated the catalog");
    var marked = original.copy();
    var overrideData = new CompoundTag();
    overrideData.putBoolean("mmcece:factory_upgrade", true);
    overrideData.putInt("parallel_add", 1);
    marked.set(DataComponents.CUSTOM_DATA, CustomData.of(overrideData));
    helper.assertTrue(
        FactoryUpgradeEntryResolver.resolve(marked, firstMachine).isPresent()
            && FactoryUpgradeEntryResolver.resolve(marked, secondMachine).isEmpty(),
        "Per-stack override bypassed the fixed item's machine compatibility");
    bus.inventory().setStackInSlot(0, original.copy());

    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(
                itemId,
                effect,
                new FactoryUpgradeCompatibility(Set.of(secondMachine), Set.of(firstMachine)))));
    helper.assertTrue(
        !bus.upgradeCatalogValid() && second.upgradeSources().isEmpty(),
        "Previous compatibility rules remained authoritative after reload");
    bus.serverTick();
    second.serverTick();
    var allowedAfterReload = UpgradeBusPayloads.capture(7, bus);
    helper.assertTrue(
        allowedAfterReload.valid()
            && secondMachine.equals(allowedAfterReload.machine())
            && allowedAfterReload.entries().size() == 1
            && allowedAfterReload.incompatible().isEmpty()
            && allowedAfterReload.revision() > rejected.revision()
            && second.scheduler().channels().getFirst().parallelLimit() == 5,
        "Reloaded whitelist did not apply to the already bound machine");
    verifyPacket(helper, allowedAfterReload);

    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(
                itemId,
                effect,
                new FactoryUpgradeCompatibility(Set.of(), Set.of(secondMachine)))));
    bus.serverTick();
    second.serverTick();
    helper.assertTrue(
        second.scheduler().channels().getFirst().parallelLimit() == 1 && !bus.incompatibleUpgrades().isEmpty(),
        "Blacklist did not revoke the previously allowed effect");
    helper.assertTrue(bus.bindFactory(first), "Could not rebind the bus to the non-blacklisted machine");
    first.serverTick();
    helper.assertTrue(
        first.scheduler().channels().getFirst().parallelLimit() == 5
            && bus.incompatibleUpgrades().isEmpty()
            && ItemStack.matches(original, bus.inventory().getStackInSlot(0)),
        "Blacklist rejected another machine or modified the original item");

    FactoryUpgradeService.replaceDefinitions(
        List.of(
            new FactoryUpgradeDefinition(
                itemId,
                effect,
                new FactoryUpgradeCompatibility(Set.of(id("future_unknown_machine")), Set.of()))));
    bus.serverTick();
    first.serverTick();
    helper.assertTrue(
        bus.upgradeCatalogValid()
            && bus.incompatibleUpgrades().equals(List.of(upgradeId))
            && first.scheduler().channels().getFirst().parallelLimit() == 1,
        "An unknown whitelist identifier accidentally enabled the effect");
  }

  private static void verifyPacket(GameTestHelper helper, UpgradeBusPayloads.Status payload) {
    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
    try {
      UpgradeBusPayloads.CODEC.encode(buffer, payload);
      helper.assertTrue(
          payload.equals(UpgradeBusPayloads.CODEC.decode(buffer)) && buffer.readableBytes() == 0,
          "Upgrade description codec lost fields or left unread bytes");
      buffer.clear();
      buffer.writeVarInt(7);
      buffer.writeBlockPos(payload.position());
      buffer.writeVarLong(payload.revision());
      buffer.writeBoolean(true);
      buffer.writeVarInt(257);
      try {
        UpgradeBusPayloads.CODEC.decode(buffer);
        helper.fail("Oversized upgrade description list was accepted");
      } catch (IllegalArgumentException expected) {
        // The count is rejected before reading or allocating unbounded entry data.
      }
    } finally {
      buffer.release();
    }
  }

  private static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath("mmcece", path);
  }
}
