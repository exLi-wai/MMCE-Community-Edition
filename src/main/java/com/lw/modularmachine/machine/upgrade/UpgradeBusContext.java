package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeEntry;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.blockentity.UpgradeBusBlockEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Immutable per-machine interpretation of one physical bus, compiled under its
 * write guard.
 */
@ApiStatus.Internal
public record UpgradeBusContext(
    ResourceLocation machine,
    long epoch,
    long revision,
    boolean valid,
    UpgradeCatalog catalog,
    List<ResourceLocation> incompatible,
    Requirements required,
    boolean restrictionsCaptured,
    List<UpgradeRestrictions.Installed> restrictions,
    UpgradeRestrictions.Result localRestrictions,
    List<UpgradeLifecyclePublisher.Subscription> lifecycle,
    List<UpgradeRecipeCheckHandlers.Subscription> checks,
    List<UpgradeBusBlockEntity.StartCostSlot> costs,
    List<UpgradeBusBlockEntity.StartCostSlot> tickCosts,
    List<UpgradeRuntimeDataHandlers.Subscription> tickData,
    List<UpgradeRuntimeDataHandlers.Subscription> finishData,
    List<UpgradeItemDataHandlers.Subscription> itemData,
    List<UpgradeBusDataHandlers.Subscription> busData) {
  public record Requirements(
      boolean checks,
      boolean modifiers,
      boolean costs,
      boolean itemData,
      boolean busData,
      boolean runtimeData) {
    public static final Requirements UNKNOWN = new Requirements(true, true, true, true, true, true);
  }

  private record Selected(int slot, ItemStack stack, UpgradeDefinition definition) {
  }

  public UpgradeBusContext {
    incompatible = List.copyOf(incompatible);
    restrictions = List.copyOf(restrictions);
    lifecycle = List.copyOf(lifecycle);
    checks = List.copyOf(checks);
    costs = List.copyOf(costs);
    tickCosts = List.copyOf(tickCosts);
    tickData = List.copyOf(tickData);
    finishData = List.copyOf(finishData);
    itemData = List.copyOf(itemData);
    busData = List.copyOf(busData);
  }

  public List<UpgradeRuntimeDataHandlers.Subscription> runtimeData(UpgradeRuntimeData.Phase phase) {
    return phase == UpgradeRuntimeData.Phase.TICK ? tickData : finishData;
  }

  public static UpgradeBusContext unavailable(ResourceLocation machine, long revision, long epoch) {
    return new UpgradeBusContext(
        machine,
        epoch,
        revision,
        false,
        UpgradeCatalog.compile(List.of(), revision),
        List.of(),
        Requirements.UNKNOWN,
        false,
        List.of(),
        UpgradeRestrictions.evaluate(List.of()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  public static UpgradeBusContext compile(UpgradeCatalogService.Snapshot definitions, List<ItemStack> contents, ResourceLocation machine, BlockPos position, long revision, UpgradeBusDataHandlers.State state) {
    var requirements = Requirements.UNKNOWN;
    boolean captured = false;
    List<UpgradeRestrictions.Installed> restrictions = List.of();
    var local = UpgradeRestrictions.evaluate(List.of());
    var tickCosts = new ArrayList<UpgradeBusBlockEntity.StartCostSlot>();
    var runtimeGroups = new UpgradeRuntimeDataHandlers.Subscriptions();
    var finishGroups = new UpgradeRuntimeDataHandlers.Subscriptions(UpgradeRuntimeData.Phase.FINISHED);
    try {
      var slots = new ArrayList<Selected>();
      var costs = new ArrayList<UpgradeBusBlockEntity.StartCostSlot>();
      boolean checksRequired = false, modifiersRequired = false, costsRequired = false, itemDataRequired = false,
          busDataRequired = false;
      boolean runtimeDataRequired = false;
      for (int index = 0; index < contents.size(); index++) {
        var stack = contents.get(index);
        var definition = definitions.match(stack).orElse(null);
        slots.add(new Selected(index, stack, definition));
        if (definition == null || !definition.compatibility().allows(machine)) {
          continue;
        }
        checksRequired |= definition.recipeCheckHandler() != null;
        modifiersRequired |= !definition.effect().recipeModifiers().isEmpty();
        costsRequired |= definition.startCost().present();
        itemDataRequired |= definition.startDataHandler() != null;
        busDataRequired |= definition.startBusDataHandler() != null;
        runtimeDataRequired |= definition.tickDataHandler() != null || definition.finishDataHandler() != null;
        if (definition.startCost().present()) {
          costs.add(new UpgradeBusBlockEntity.StartCostSlot(index, definition.startCost()));
        }
        if (definition.tickCost().present()) {
          tickCosts.add(new UpgradeBusBlockEntity.StartCostSlot(index, definition.tickCost()));
        }
        runtimeGroups.add(definition, index);
        finishGroups.add(definition, index);
      }
      requirements = new Requirements(
          checksRequired,
          modifiersRequired,
          costsRequired,
          itemDataRequired,
          busDataRequired,
          runtimeDataRequired);
      restrictions = slots.stream()
          .filter(slot -> slot.definition() != null && slot.definition().compatibility().allows(machine))
          .map(
              slot -> new UpgradeRestrictions.Installed(
                  slot.definition().effect().id(),
                  slot.stack().getCount(),
                  slot.definition().restrictions()))
          .toList();
      captured = true;
      local = UpgradeRestrictions.evaluate(restrictions);
      var tickData = runtimeGroups.build();
      var finishData = finishGroups.build();
      if (Stream.concat(tickData.stream(), finishData.stream())
          .anyMatch(subscription -> !UpgradeRuntimeData.isRegistered(subscription.handlerId()))) {
        throw new IllegalArgumentException("Missing runtime upgrade handler");
      }
      var incompatible = slots.stream()
          .filter(slot -> slot.definition() != null && !slot.definition().compatibility().allows(machine))
          .map(slot -> slot.definition().effect().id()).toList();
      if (!local.valid()) {
        return new UpgradeBusContext(
            machine,
            definitions.epoch(),
            revision,
            true,
            UpgradeCatalog.compile(List.of(), revision, restrictions),
            incompatible,
            requirements,
            true,
            restrictions,
            local,
            List.of(),
            List.of(),
            List.of(),
            tickCosts,
            tickData,
            finishData,
            List.of(),
            List.of());
      }
      var entries = new ArrayList<UpgradeEntry>();
      var lifecycle = new ArrayList<UpgradeLifecyclePublisher.Subscription>();
      var checks = new ArrayList<UpgradeRecipeCheckHandlers.Subscription>();
      var dataHandlers = new ArrayList<UpgradeItemDataHandlers.Subscription>();
      var groups = new LinkedHashMap<ResourceLocation, List<UpgradeEvents.Upgrade>>();
      var busHandlers = new LinkedHashMap<ResourceLocation, ResourceLocation>();
      for (var slot : slots) {
        var selected = slot.definition();
        if (selected == null && !UpgradeEntryResolver.isMarked(slot.stack())) {
          continue;
        }
        if (selected != null && !selected.compatibility().allows(machine)) {
          continue;
        }
        var busData = selected == null ? new CompoundTag() : state.get(selected.effect().id());
        var entry = UpgradeEntryResolver
            .resolveForBus(slot.stack(), selected, machine, position, slot.slot(), definitions.epoch(), busData)
            .orElseThrow(() -> new IllegalArgumentException("Invalid upgrade item"));
        entries.add(entry);
        if (selected == null
            || selected.lifecycleHandler() == null
                && selected.recipeCheckHandler() == null
                && selected.startDataHandler() == null
                && selected.startBusDataHandler() == null) {
          continue;
        }
        if (selected.lifecycleHandler() != null && !UpgradeEvents.isRegistered(selected.lifecycleHandler())
            || selected.recipeCheckHandler() != null && !UpgradeRecipeChecks.isRegistered(selected.recipeCheckHandler())
            || selected.startDataHandler() != null && !UpgradeItemData.isRegistered(selected.startDataHandler())
            || selected.startBusDataHandler() != null && !UpgradeBusData.isRegistered(selected.startBusDataHandler())) {
          throw new IllegalArgumentException("Missing upgrade callback handler");
        }
        var source = UpgradeLifecyclePublisher
            .captureUpgrade(selected, slot.stack(), position, slot.slot(), entry, busData);
        if (selected.lifecycleHandler() != null) {
          lifecycle.add(new UpgradeLifecyclePublisher.Subscription(selected.lifecycleHandler(), source));
        }
        if (selected.recipeCheckHandler() != null) {
          checks.add(new UpgradeRecipeCheckHandlers.Subscription(selected.recipeCheckHandler(), source));
        }
        if (selected.startDataHandler() != null) {
          dataHandlers.add(new UpgradeItemDataHandlers.Subscription(selected.startDataHandler(), source));
        }
        if (selected.startBusDataHandler() != null) {
          groups.computeIfAbsent(selected.effect().id(), ignored -> new ArrayList<>()).add(source);
          busHandlers.put(selected.effect().id(), selected.startBusDataHandler());
        }
      }
      var busData = groups.entrySet().stream()
          .map(
              group -> new UpgradeBusDataHandlers.Subscription(
                  busHandlers.get(group.getKey()),
                  group.getKey(),
                  position,
                  group.getValue()))
          .toList();

      return new UpgradeBusContext(
          machine,
          definitions.epoch(),
          revision,
          true,
          UpgradeCatalog.compile(entries, revision, restrictions),
          incompatible,
          requirements,
          true,
          restrictions,
          local,
          lifecycle,
          checks,
          costs,
          tickCosts,
          tickData,
          finishData,
          dataHandlers,
          busData);
    } catch (RuntimeException invalid) {
      return new UpgradeBusContext(
          machine,
          definitions.epoch(),
          revision,
          false,
          UpgradeCatalog.compile(List.of(), revision, restrictions),
          List.of(),
          requirements,
          captured,
          restrictions,
          local,
          List.of(),
          List.of(),
          List.of(),
          tickCosts,
          runtimeGroups.build(),
          finishGroups.build(),
          List.of(),
          List.of());
    }
  }
}
