package com.lw.modularmachine.machine.factory;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** Immutable server registrations supplied by KubeJS server scripts. */
public final class FactoryUpgradeKubeJSHandlers {
  public static final class Registration {
    private final Map<ResourceLocation, FactoryDynamicUpgrades.Evaluator> dynamic = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeRecipeChecks.Checker> checks = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeEvents.Listener> events = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeItemData.Preparer> itemData = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeBusData.Preparer> busData = new HashMap<>();
    private final Map<ResourceLocation, FactoryUpgradeRuntimeData.Preparer> runtimeData = new HashMap<>();
    private final Set<ResourceLocation> identifiers = new LinkedHashSet<>();
    private boolean valid = true;

    public void dynamic(String id, FactoryDynamicUpgrades.Evaluator handler) {
      put(dynamic, id, handler);
    }

    public void recipeCheck(String id, FactoryUpgradeRecipeChecks.Checker handler) {
      put(checks, id, handler);
    }

    public void lifecycle(String id, FactoryUpgradeEvents.Listener handler) {
      put(events, id, handler);
    }

    public void startItemData(String id, FactoryUpgradeItemData.Preparer handler) {
      put(itemData, id, handler);
    }

    public void startBusData(String id, FactoryUpgradeBusData.Preparer handler) {
      put(busData, id, handler);
    }

    public void runtimeData(String id, FactoryUpgradeRuntimeData.Preparer handler) {
      put(runtimeData, id, handler);
    }

    private <T> void put(Map<ResourceLocation, T> handlers, String text, T handler) {
      ResourceLocation id = ResourceLocation.tryParse(text);
      if (id == null
          || !id.getNamespace().equals("kubejs")
          || id.getPath().isEmpty()
          || id.toString().length() > 256
          || !id.toString().equals(text)
          || handler == null
          || handlers.containsKey(id)
          || !identifiers.contains(id) && identifiers.size() >= FactoryUpgradeHandlers.MAX_HANDLERS) {
        valid = false;
        return;
      }
      identifiers.add(id);
      handlers.put(id, handler);
    }

    public boolean valid() {
      return valid;
    }

    public Snapshot snapshot() {
      return !valid ? Snapshot.EMPTY : new Snapshot(dynamic, checks, events, itemData, busData, runtimeData);
    }
  }

  public record Snapshot(
      Map<ResourceLocation, FactoryDynamicUpgrades.Evaluator> dynamic,
      Map<ResourceLocation, FactoryUpgradeRecipeChecks.Checker> checks,
      Map<ResourceLocation, FactoryUpgradeEvents.Listener> events,
      Map<ResourceLocation, FactoryUpgradeItemData.Preparer> itemData,
      Map<ResourceLocation, FactoryUpgradeBusData.Preparer> busData,
      Map<ResourceLocation, FactoryUpgradeRuntimeData.Preparer> runtimeData) {
    public static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public Snapshot {
      dynamic = Map.copyOf(dynamic);
      checks = Map.copyOf(checks);
      events = Map.copyOf(events);
      itemData = Map.copyOf(itemData);
      busData = Map.copyOf(busData);
      runtimeData = Map.copyOf(runtimeData);
    }
  }

  private static volatile Snapshot current = Snapshot.EMPTY;

  private FactoryUpgradeKubeJSHandlers() {
  }

  public static Registration registration() {
    return new Registration();
  }

  public static void install(Snapshot candidate) {
    FactoryUpgradeHandlers.requireServer();
    var next = Objects.requireNonNull(candidate, "candidate");
    if (!next.equals(current)) {
      current = next;
      FactoryUpgradeService.invalidateDynamicEffects();
    }
  }

  public static FactoryDynamicUpgrades.Evaluator dynamic(ResourceLocation id) {
    return current.dynamic().get(id);
  }

  public static FactoryUpgradeRecipeChecks.Checker recipeCheck(ResourceLocation id) {
    return current.checks().get(id);
  }

  public static FactoryUpgradeEvents.Listener lifecycle(ResourceLocation id) {
    return current.events().get(id);
  }

  public static boolean hasEvents() {
    return !current.events().isEmpty();
  }

  public static FactoryUpgradeItemData.Preparer startItemData(ResourceLocation id) {
    return current.itemData().get(id);
  }

  public static FactoryUpgradeBusData.Preparer startBusData(ResourceLocation id) {
    return current.busData().get(id);
  }

  public static FactoryUpgradeRuntimeData.Preparer runtimeData(ResourceLocation id) {
    return current.runtimeData().get(id);
  }

  static void clearOnServerStop() {
    current = Snapshot.EMPTY;
  }
}
