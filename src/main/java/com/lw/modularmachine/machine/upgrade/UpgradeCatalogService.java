package com.lw.modularmachine.machine.upgrade;

import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.api.upgrade.UpgradeRuntimeData;
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
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Atomic, immutable fixed-item upgrade catalog owned by the server. */
@ApiStatus.Internal
public final class UpgradeCatalogService {
  public static final int MAX_DEFINITIONS = 1024;

  private record ItemIndex(UpgradeDefinition fallback, Map<ItemResourceKey, UpgradeDefinition> exact) {
    private ItemIndex {
      exact = Map.copyOf(exact);
    }
  }

  public static final class Snapshot {
    private final long epoch;
    private final List<UpgradeDefinition> definitions;
    private final boolean tickCosts;
    private final boolean tickData;
    private final boolean finishData;
    private final Map<ResourceLocation, List<UpgradeDefinition>> byItem;
    private final Map<ResourceLocation, ItemIndex> indexes;
    private final Map<ResourceLocation, UpgradeScriptType> types;
    private final Set<ResourceLocation> supportedItems;

    private Snapshot(long epoch, List<UpgradeDefinition> definitions, Map<ResourceLocation, List<UpgradeDefinition>> byItem, Map<ResourceLocation, ItemIndex> indexes) {
      this(epoch, definitions, byItem, indexes, Map.of(), Set.of());
    }

    private Snapshot(long epoch, List<UpgradeDefinition> definitions, Map<ResourceLocation, List<UpgradeDefinition>> byItem, Map<ResourceLocation, ItemIndex> indexes, Map<ResourceLocation, UpgradeScriptType> types, Set<ResourceLocation> supportedItems) {
      this.epoch = epoch;
      this.definitions = List.copyOf(definitions);
      var effects = new ArrayList<>(definitions);
      types.values().forEach(type -> effects.add(type.template()));
      tickCosts = effects.stream().anyMatch(definition -> definition.tickCost().present());
      tickData = effects.stream().anyMatch(definition -> definition.tickDataHandler() != null);
      finishData = effects.stream().anyMatch(definition -> definition.finishDataHandler() != null);
      var groups = new LinkedHashMap<ResourceLocation, List<UpgradeDefinition>>();
      byItem.forEach((item, values) -> groups.put(item, List.copyOf(values)));
      this.byItem = Map.copyOf(groups);
      this.indexes = Map.copyOf(indexes);
      this.types = Map.copyOf(types);
      this.supportedItems = Set.copyOf(supportedItems);
    }

    public long epoch() {
      return epoch;
    }

    public List<UpgradeDefinition> definitions() {
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

    public boolean hasData(UpgradeRuntimeData.Phase phase) {
      return switch (phase) {
        case TICK -> tickData;
        case FINISHED -> finishData;
      };
    }

    public boolean hasWork(UpgradeRuntimeData.Phase phase) {
      return phase == UpgradeRuntimeData.Phase.TICK ? hasTickWork() : finishData;
    }

    public Map<ResourceLocation, List<UpgradeDefinition>> byItem() {
      return byItem;
    }

    public boolean matchesByItemOnly(ItemStack stack) {
      if (stack.isEmpty()) {
        return true;
      }
      var item = stack.getItem().builtInRegistryHolder().key().location();
      var index = indexes.get(item);
      return !supportedItems.contains(item) && (index == null || index.exact().isEmpty());
    }

    public Optional<UpgradeDefinition> match(ItemStack stack) {
      if (stack == null || stack.isEmpty()) {
        return Optional.empty();
      }
      var explicit = UpgradeEntryResolver.typeIdentifier(stack);
      if (explicit != null && supportedItems.contains(stack.getItem().builtInRegistryHolder().key().location())) {
        var type = types.get(explicit);
        return type == null ? Optional.empty() : Optional
            .of(type.template().forItem(stack.getItem().builtInRegistryHolder().key().location(), null));
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

    public Map<ResourceLocation, UpgradeScriptType> types() {
      return types;
    }
    public Set<ResourceLocation> supportedItems() {
      return supportedItems;
    }
  }

  private static volatile Snapshot current = new Snapshot(0, List.of(), Map.of(), Map.of());

  private UpgradeCatalogService() {
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
        previous.indexes,
        previous.types,
        previous.supportedItems);
  }

  public static void replaceDefinitions(Collection<UpgradeDefinition> definitions) {
    replaceDefinitions(definitions, Map.of(), Set.of());
  }

  public static void replaceDefinitions(Collection<UpgradeDefinition> definitions, Map<ResourceLocation, UpgradeScriptType> types, Set<ResourceLocation> supportedItems) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server == null || !server.isSameThread()) {
      throw new IllegalStateException("Upgrade definitions require the server thread and registry lookup");
    }
    replaceDefinitions(definitions, server.registryAccess(), types, supportedItems);
  }

  static void replaceDefinitions(Collection<UpgradeDefinition> definitions, HolderLookup.Provider registries) {
    replaceDefinitions(definitions, registries, Map.of(), Set.of());
  }

  private static void replaceDefinitions(Collection<UpgradeDefinition> definitions, HolderLookup.Provider registries, Map<ResourceLocation, UpgradeScriptType> types, Set<ResourceLocation> supportedItems) {
    var server = ServerLifecycleHooks.getCurrentServer();
    if (server != null && !server.isSameThread()) {
      throw new IllegalStateException("Upgrade definitions published outside server thread");
    }
    if (definitions.size() > MAX_DEFINITIONS
        || types.size() > MAX_DEFINITIONS
        || supportedItems.size() > MAX_DEFINITIONS) {
      throw new JsonParseException("Too many fixed upgrade definitions");
    }
    var ordered = definitions.stream().sorted(Comparator.comparing(definition -> definition.effect().id())).toList();
    var grouped = new LinkedHashMap<ResourceLocation, List<UpgradeDefinition>>();
    var identifiers = new HashSet<ResourceLocation>();
    identifiers.addAll(types.keySet());
    for (var entry : types.entrySet()) {
      if (!entry.getKey().equals(entry.getValue().type().id())) {
        throw new JsonParseException("Upgrade type identity disagrees with its catalog key");
      }
    }
    for (var item : supportedItems) {
      if (!BuiltInRegistries.ITEM.containsKey(item) || BuiltInRegistries.ITEM.get(item) == Items.AIR) {
        throw new JsonParseException("Unknown supported upgrade item: " + item);
      }
    }
    for (var definition : ordered) {
      var type = types.get(definition.effect().id());
      if (!BuiltInRegistries.ITEM.containsKey(definition.item())
          || BuiltInRegistries.ITEM.get(definition.item()) == Items.AIR
          || !identifiers.add(definition.effect().id()) && type == null
          || type != null && !definition.equals(type.template().forItem(definition.item(), definition.components()))) {
        throw new JsonParseException("Unknown item or duplicate fixed upgrade ID: " + definition.item());
      }
      grouped.computeIfAbsent(definition.item(), ignored -> new ArrayList<>()).add(definition);
    }
    var effects = new ArrayList<>(ordered);
    types.values().forEach(type -> effects.add(type.template()));
    for (var definition : effects) {
      for (var incompatible : definition.restrictions().incompatible()) {
        if (!identifiers.contains(incompatible)) {
          throw new JsonParseException(definition.effect().id() + ": Unknown incompatible upgrade " + incompatible);
        }
      }
    }
    var indexes = new LinkedHashMap<ResourceLocation, ItemIndex>();
    for (var group : grouped.entrySet()) {
      UpgradeDefinition fallback = null;
      var exact = new LinkedHashMap<ItemResourceKey, UpgradeDefinition>();
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
    current = new Snapshot(Math.incrementExact(current.epoch()), ordered, grouped, indexes, types, supportedItems);
  }

  @SubscribeEvent
  public static void stopped(ServerStoppedEvent event) {
    DynamicUpgradeHandlers.clearOnServerStop();
    UpgradeLifecyclePublisher.clearOnServerStop();
    UpgradeRecipeCheckHandlers.clearOnServerStop();
    UpgradeItemDataHandlers.clearOnServerStop();
    UpgradeBusDataHandlers.clearOnServerStop();
    UpgradeRuntimeDataHandlers.clearOnServerStop();
    UpgradeScriptHandlers.clearOnServerStop();
    UpgradeDescriptionService.clear();
    UpgradeCallbackGuard.clear();
    current = new Snapshot(Math.incrementExact(current.epoch()), List.of(), Map.of(), Map.of());
  }
}
