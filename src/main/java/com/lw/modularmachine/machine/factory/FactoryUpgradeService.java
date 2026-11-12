package com.lw.modularmachine.machine.factory;

import com.lw.modularmachine.api.upgrade.FactoryUpgradeRuntimeData;
import com.google.gson.JsonParseException;
import com.lw.modularmachine.machine.recipe.ItemResourceKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Atomic, immutable fixed-item upgrade catalog owned by the server. */
public final class FactoryUpgradeService {
  public static final int MAX_DEFINITIONS = 1024;

  private record ItemIndex(FactoryUpgradeDefinition fallback, Map<ItemResourceKey, FactoryUpgradeDefinition> exact) {
    private ItemIndex {
      exact = Map.copyOf(exact);
    }
  }

  public static final class Snapshot {
    private final long epoch;
    private final List<FactoryUpgradeDefinition> definitions;
    private final boolean tickCosts;
    private final boolean tickData;
    private final boolean finishData;
    private final Map<ResourceLocation, List<FactoryUpgradeDefinition>> byItem;
    private final Map<ResourceLocation, ItemIndex> indexes;

    private Snapshot(long epoch, List<FactoryUpgradeDefinition> definitions, Map<ResourceLocation, List<FactoryUpgradeDefinition>> byItem, Map<ResourceLocation, ItemIndex> indexes) {
      this.epoch = epoch;
      this.definitions = List.copyOf(definitions);
      tickCosts = definitions.stream().anyMatch(definition -> definition.tickCost().present());
      tickData = definitions.stream().anyMatch(definition -> definition.tickDataHandler() != null);
      finishData = definitions.stream().anyMatch(definition -> definition.finishDataHandler() != null);
      var groups = new LinkedHashMap<ResourceLocation, List<FactoryUpgradeDefinition>>();
      byItem.forEach((item, values) -> groups.put(item, List.copyOf(values)));
      this.byItem = Map.copyOf(groups);
      this.indexes = Map.copyOf(indexes);
    }

    public long epoch() {
      return epoch;
    }

    public List<FactoryUpgradeDefinition> definitions() {
      return definitions;
    }

    public boolean hasTickCosts() {
      return tickCosts;
    }

    public boolean hasTickData() {
      return tickData;
    }

    public boolean hasRuntimeUpgrades() {
      return tickCosts || tickData || finishData;
    }

    public boolean hasTickWork() {
      return tickCosts || tickData;
    }

    public boolean hasData(FactoryUpgradeRuntimeData.Phase phase) {
      return switch (phase) {
        case TICK -> tickData;
        case FINISHED -> finishData;
      };
    }

    public boolean hasWork(FactoryUpgradeRuntimeData.Phase phase) {
      return phase == FactoryUpgradeRuntimeData.Phase.TICK ? hasTickWork() : finishData;
    }

    public Map<ResourceLocation, List<FactoryUpgradeDefinition>> byItem() {
      return byItem;
    }

    public Optional<FactoryUpgradeDefinition> match(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
        return Optional.empty();
      }
      var index = indexes.get(stack.getItem().builtInRegistryHolder().key().location());
      if (index == null) {
        return Optional.empty();
      }
      if (!index.exact().isEmpty()) {
        var selected = index.exact().get(ItemResourceKey.of(stack));
        if (selected != null) {
          return Optional.of(selected);
        }
      }

      return Optional.ofNullable(index.fallback());
    }
  }

  private static volatile Snapshot current = new Snapshot(0, List.of(), Map.of(), Map.of());

  private FactoryUpgradeService() {
  }

  public static Snapshot snapshot() {
    return current;
  }

  static void invalidateDynamicEffects() {
    var previous = current;
    current = new Snapshot(
        Math.incrementExact(previous.epoch()),
        previous.definitions(),
        previous.byItem(),
        previous.indexes);
  }

  public static void replaceDefinitions(Collection<FactoryUpgradeDefinition> definitions) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null || !server.isSameThread()) {
      throw new IllegalStateException("Upgrade definitions require the server thread and registry lookup");
    }
    replaceDefinitions(definitions, server.registryAccess());
  }

  static void replaceDefinitions(Collection<FactoryUpgradeDefinition> definitions, HolderLookup.Provider registries) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && !server.isSameThread()) {
      throw new IllegalStateException("Upgrade definitions published outside server thread");
    }
    if (definitions.size() > MAX_DEFINITIONS) {
      throw new JsonParseException("Too many fixed upgrade definitions");
    }
    var ordered = definitions.stream().sorted(Comparator.comparing(definition -> definition.effect().id())).toList();
    var grouped = new LinkedHashMap<ResourceLocation, List<FactoryUpgradeDefinition>>();
    var identifiers = new HashSet<ResourceLocation>();
    for (var definition : ordered) {
      if (!BuiltInRegistries.ITEM.containsKey(definition.item())
          || BuiltInRegistries.ITEM.get(definition.item()) == Items.AIR
          || !identifiers.add(definition.effect().id())) {
        throw new JsonParseException("Unknown item or duplicate fixed upgrade ID: " + definition.item());
      }
      grouped.computeIfAbsent(definition.item(), ignored -> new ArrayList<>()).add(definition);
    }
    for (var definition : ordered) {
      for (var incompatible : definition.restrictions().incompatible()) {
        if (!identifiers.contains(incompatible)) {
          throw new JsonParseException(definition.effect().id() + ": Unknown incompatible upgrade " + incompatible);
        }
      }
    }
    var indexes = new LinkedHashMap<ResourceLocation, ItemIndex>();
    for (var group : grouped.entrySet()) {
      FactoryUpgradeDefinition fallback = null;
      var exact = new LinkedHashMap<ItemResourceKey, FactoryUpgradeDefinition>();
      for (var definition : group.getValue()) {
        if (definition.exactComponents()) {
          try {
            var key = definition.compileMatcher(registries);
            if (exact.putIfAbsent(key, definition) != null) {
              throw new JsonParseException("Duplicate exact component matcher: " + definition.effect().id());
            }
          } catch (RuntimeException invalid) {
            throw new JsonParseException(definition.effect().id() + ": Invalid exact upgrade matcher", invalid);
          }
        } else if (fallback != null) {
          throw new JsonParseException("Duplicate item-wide upgrade mapping: " + group.getKey());
        } else {
          fallback = definition;
        }
      }
      indexes.put(group.getKey(), new ItemIndex(fallback, exact));
    }
    current = new Snapshot(Math.incrementExact(current.epoch()), ordered, grouped, indexes);
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    DynamicUpgradeHandlers.clearOnServerStop();
    UpgradeLifecyclePublisher.clearOnServerStop();
    UpgradeRecipeChecks.clearOnServerStop();
    UpgradeItemDataHandlers.clearOnServerStop();
    UpgradeBusDataHandlers.clearOnServerStop();
    UpgradeRuntimeDataHandlers.clearOnServerStop();
    FactoryUpgradeKubeJSHandlers.clearOnServerStop();
    FactoryUpgradeCallbacks.clear();
    current = new Snapshot(Math.incrementExact(current.epoch()), List.of(), Map.of(), Map.of());
  }
}
