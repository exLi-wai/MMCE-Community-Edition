package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
import com.lw.modularmachine.api.upgrade.UpgradeBusData;
import com.lw.modularmachine.api.upgrade.UpgradeItemData;
import com.lw.modularmachine.api.upgrade.UpgradeRecipeChecks;
import com.lw.modularmachine.api.upgrade.UpgradeEvents;
import com.lw.modularmachine.api.upgrade.DynamicUpgrades;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable declared upgrade callbacks and definitions; the active batch is
 * owned by the upgrade domain.
 */
@ApiStatus.Internal
public final class UpgradeScriptHandlers {
  public record Snapshot(
      Map<ResourceLocation, DynamicUpgrades.Evaluator> dynamic,
      Map<ResourceLocation, UpgradeRecipeChecks.Checker> checks,
      Map<ResourceLocation, UpgradeEvents.Listener> events,
      Map<ResourceLocation, UpgradeItemData.Preparer> itemData,
      Map<ResourceLocation, UpgradeBusData.Preparer> busData,
      Map<ResourceLocation, UpgradeRuntimeData.Preparer> runtimeData,
      List<UpgradeDefinition> definitions,
      Map<ResourceLocation, UpgradeScriptType> types,
      Set<ResourceLocation> supportedItems) {
    public Snapshot(Map<ResourceLocation, DynamicUpgrades.Evaluator> dynamic, Map<ResourceLocation, UpgradeRecipeChecks.Checker> checks, Map<ResourceLocation, UpgradeEvents.Listener> events, Map<ResourceLocation, UpgradeItemData.Preparer> itemData, Map<ResourceLocation, UpgradeBusData.Preparer> busData, Map<ResourceLocation, UpgradeRuntimeData.Preparer> runtimeData, List<UpgradeDefinition> definitions) {
      this(dynamic, checks, events, itemData, busData, runtimeData, definitions, Map.of(), Set.of());
    }
    public static final Snapshot EMPTY = new Snapshot(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        List.of());

    public Snapshot {
      dynamic = Map.copyOf(dynamic);
      checks = Map.copyOf(checks);
      events = Map.copyOf(events);
      itemData = Map.copyOf(itemData);
      busData = Map.copyOf(busData);
      runtimeData = Map.copyOf(runtimeData);
      definitions = List.copyOf(definitions);
      types = Map.copyOf(types);
      supportedItems = Set.copyOf(supportedItems);
    }
  }

  private static volatile Snapshot current = Snapshot.EMPTY;

  private UpgradeScriptHandlers() {
  }

  public static void install(Snapshot candidate) {
    UpgradeHandlerRegistry.requireServer();
    var next = Objects.requireNonNull(candidate, "candidate");
    UpgradeCatalogService.replaceDefinitions(next.definitions(), next.types(), next.supportedItems());
    if (!next.equals(current)) {
      current = next;
      UpgradeCatalogService.invalidateDynamicEffects();
    }
  }

  public static DynamicUpgrades.Evaluator dynamic(ResourceLocation id) {
    return current.dynamic().get(id);
  }

  public static UpgradeScriptType type(ResourceLocation id) {
    return current.types().get(id);
  }

  public static UpgradeRecipeChecks.Checker recipeCheck(ResourceLocation id) {
    return current.checks().get(id);
  }

  public static UpgradeEvents.Listener lifecycle(ResourceLocation id) {
    return current.events().get(id);
  }

  public static boolean hasEvents() {
    return !current.events().isEmpty();
  }

  public static UpgradeItemData.Preparer startItemData(ResourceLocation id) {
    return current.itemData().get(id);
  }

  public static UpgradeBusData.Preparer startBusData(ResourceLocation id) {
    return current.busData().get(id);
  }

  public static UpgradeRuntimeData.Preparer runtimeData(ResourceLocation id) {
    return current.runtimeData().get(id);
  }

  static void clearOnServerStop() {
    current = Snapshot.EMPTY;
  }
}
