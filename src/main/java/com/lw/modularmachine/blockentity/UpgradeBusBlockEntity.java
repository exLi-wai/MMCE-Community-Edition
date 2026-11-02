package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.block.UpgradeBusBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.UpgradeBusTier;
import com.lw.modularmachine.machine.factory.FactoryUpgradeBusContext;
import com.lw.modularmachine.machine.factory.FactoryUpgradeBusData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeItemData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRecipeChecks;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRuntimeData;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCost;
import com.lw.modularmachine.machine.factory.UpgradeBusBinding;
import com.lw.modularmachine.machine.factory.UpgradeBusOwner;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.menu.UpgradeBusMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.TransactionalItemStackHandler;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Multiple binding intents and catalog interpretations, but exactly one
 * physical inventory.
 */
public final class UpgradeBusBlockEntity extends BlockEntity implements MenuProvider {
  public static final String DATA_KEY = "mmcece_upgrade_bus";
  public static final int DATA_SCHEMA = 2;
  private final UpgradeBusTier tier;
  private final int slots;
  private final TransactionalItemStackHandler inventory;
  private final Map<BlockPos, UpgradeBusBinding> bindings = new LinkedHashMap<>();
  private final Map<ResourceLocation, FactoryUpgradeBusContext> contexts = new HashMap<>();
  private final Map<BlockPos, UpgradeBusOwner> published = new HashMap<>();
  private long upgradeRevision, bindingRevision;
  private long definitionsEpoch = -1, lastBindingCheck = Long.MIN_VALUE;
  private FactoryUpgradeBusData.State customData = FactoryUpgradeBusData.State.empty();
  private boolean loading, rebuildingCatalog, lifecycleNotification;
  private CompoundTag unsupportedData;

  public UpgradeBusBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.UPGRADE_BUS_ENTITY.get(), pos, state);
    tier = ((UpgradeBusBlock) state.getBlock()).tier();
    slots = MachineConfig.upgradeBusSlots(tier);
    inventory = new TransactionalItemStackHandler(
        slots,
        this::requireMutationThread,
        this::beforeInventoryChange,
        this::onInventoryChanged);
    rebuildContexts();
  }

  public UpgradeBusTier tier() {
    return tier;
  }

  public int slotCount() {
    return slots;
  }

  public ItemStackHandler inventory() {
    return inventory;
  }

  public boolean schemaSupported() {
    return unsupportedData == null;
  }

  public long upgradeRevision() {
    return upgradeRevision;
  }

  public long bindingRevision() {
    return bindingRevision;
  }

  public List<UpgradeBusBinding> bindings() {
    return UpgradeBusBinding.ordered(bindings.values());
  }

  public Optional<ResourceLocation> boundMachine() {
    return bindings().stream().findFirst().map(UpgradeBusBinding::machine);
  }

  public Optional<BlockPos> boundFactory() {
    return bindings().stream().findFirst().map(UpgradeBusBinding::controller);
  }

  private FactoryUpgradeBusContext context(ResourceLocation machine) {
    requireMutationThread();
    var result = contexts.get(machine);
    return result != null ? result : FactoryUpgradeBusContext
        .unavailable(machine, upgradeRevision, FactoryUpgradeService.snapshot().epoch());
  }

  private FactoryUpgradeBusContext context(UpgradeBusOwner factory) {
    requireServer();
    return context(factory == null ? null : factory.machineId());
  }

  private FactoryUpgradeBusContext displayContext() {
    return context(boundMachine().orElse(null));
  }

  private boolean current(FactoryUpgradeBusContext context) {
    return schemaSupported()
        && !loading
        && !rebuildingCatalog
        && !inventory.isInTransaction()
        && context.epoch() == FactoryUpgradeService.snapshot().epoch()
        && context.revision() == upgradeRevision;
  }

  public FactoryUpgradeCatalog upgradeCatalog() {
    return displayContext().catalog();
  }

  public FactoryUpgradeCatalog upgradeCatalog(UpgradeBusOwner factory) {
    return context(factory).catalog();
  }

  public boolean upgradeCatalogValid() {
    var context = displayContext();
    return current(context) && context.valid();
  }

  public boolean upgradeCatalogValid(UpgradeBusOwner factory) {
    var context = context(factory);
    return current(context) && context.valid() && hasFactoryBinding(factory);
  }

  public List<ResourceLocation> incompatibleUpgrades() {
    return displayContext().incompatible();
  }

  public List<ResourceLocation> incompatibleUpgrades(ResourceLocation machine) {
    return context(machine).incompatible();
  }

  public boolean upgradeRestrictionSourcesValid(UpgradeBusOwner factory) {
    requireServer();
    var context = context(factory);
    return current(context) && context.restrictionsCaptured() && hasFactoryBinding(factory);
  }

  public boolean upgradeRestrictionSourcesValid() {
    requireServer();
    var context = displayContext();
    return current(context) && context.restrictionsCaptured();
  }

  public List<FactoryUpgradeRestrictions.Installed> upgradeRestrictions(UpgradeBusOwner factory) {
    return upgradeRestrictionSourcesValid(factory) ? context(factory).restrictions() : List.of();
  }

  public List<FactoryUpgradeRestrictions.Installed> upgradeRestrictions() {
    return upgradeRestrictionSourcesValid() ? displayContext().restrictions() : List.of();
  }

  public FactoryUpgradeRestrictions.Result upgradeRestrictionStatus(UpgradeBusOwner factory) {
    requireServer();
    var local = context(factory).localRestrictions();
    return !local.valid() || !hasFactoryBinding(factory) ? local : factory.upgradeRestrictionStatus();
  }

  public FactoryUpgradeRestrictions.Result upgradeRestrictionStatus() {
    var server = requireServer();
    var owner = boundFactory().orElse(null);
    if (owner != null && server.hasChunkAt(owner) && server.getBlockEntity(owner) instanceof UpgradeBusOwner factory) {
      return upgradeRestrictionStatus(factory);
    }
    return displayContext().localRestrictions();
  }

  public FactoryUpgradeBusContext description(UpgradeBusBinding binding) {
    requireServer();
    return context(binding.machine());
  }

  public boolean descriptionValid(UpgradeBusBinding binding) {
    requireServer();
    var context = context(binding.machine());
    return binding.equals(bindings.get(binding.controller())) && current(context) && context.valid();
  }

  public boolean bindingActive(UpgradeBusBinding binding) {
    var server = requireServer();
    return server.hasChunkAt(binding.controller())
        && server.getBlockEntity(binding.controller()) instanceof UpgradeBusOwner factory
        && upgradeCatalogValid(factory);
  }

  public FactoryUpgradeRestrictions.Result upgradeRestrictionStatus(UpgradeBusBinding binding) {
    var server = requireServer();
    if (server.hasChunkAt(binding.controller())
        && server.getBlockEntity(binding.controller()) instanceof UpgradeBusOwner factory
        && hasFactoryBinding(factory)) {
      return upgradeRestrictionStatus(factory);
    }
    return context(binding.machine()).localRestrictions();
  }

  public List<FactoryUpgradeEvents.Subscription> lifecycleSubscriptions(UpgradeBusOwner factory) {
    return upgradeCatalogValid(factory) ? context(factory).lifecycle() : List.of();
  }

  public List<FactoryUpgradeEvents.Subscription> lifecycleSubscriptions() {
    return upgradeCatalogValid() ? displayContext().lifecycle() : List.of();
  }

  public boolean recipeCheckRequired(UpgradeBusOwner factory) {
    return context(factory).required().checks();
  }

  public boolean recipeCheckRequired() {
    return displayContext().required().checks();
  }

  public List<FactoryUpgradeRecipeChecks.Subscription> recipeCheckSubscriptions(UpgradeBusOwner factory) {
    return upgradeCatalogValid(factory) ? context(factory).checks() : List.of();
  }

  public List<FactoryUpgradeRecipeChecks.Subscription> recipeCheckSubscriptions() {
    return upgradeCatalogValid() ? displayContext().checks() : List.of();
  }

  public boolean recipeModifiersRequired(UpgradeBusOwner factory) {
    return context(factory).required().modifiers();
  }

  public boolean recipeModifiersRequired() {
    return displayContext().required().modifiers();
  }

  public boolean startCostRequired(UpgradeBusOwner factory) {
    return context(factory).required().costs();
  }

  public boolean startCostRequired() {
    return displayContext().required().costs();
  }

  public List<StartCostSlot> startCostSlots(UpgradeBusOwner factory) {
    return upgradeCatalogValid(factory) ? context(factory).costs() : List.of();
  }

  public boolean tickCostsReady(UpgradeBusOwner factory) {
    var context = context(factory);

    return current(context) && context.restrictionsCaptured() && hasFactoryBinding(factory);
  }

  public List<StartCostSlot> tickCostSlots(UpgradeBusOwner factory) {
    var context = context(factory);

    return tickCostsReady(factory) ? context.tickCosts() : List.of();
  }

  public boolean tickDataReady(UpgradeBusOwner factory) {
    var context = context(factory);

    return current(context) && context.restrictionsCaptured() && hasFactoryBinding(factory);
  }

  public List<FactoryUpgradeRuntimeData.Subscription> tickDataSubscriptions(UpgradeBusOwner factory) {
    var context = context(factory);

    return tickDataReady(factory) ? context.tickData() : List.of();
  }

  public boolean runtimeDataRequired(UpgradeBusOwner factory) {
    return context(factory).required().runtimeData();
  }

  public List<FactoryUpgradeRuntimeData.Subscription> runtimeDataSubscriptions(UpgradeBusOwner factory, FactoryUpgradeRuntimeData.Phase phase) {
    var context = context(factory);

    return current(context) && context.restrictionsCaptured() && hasFactoryBinding(factory) ? context
        .runtimeData(phase) : List.of();
  }

  public boolean auditTickCosts(UpgradeBusOwner factory) {
    requireServer();
    if (!bindingMutationAllowed() || !tickCostsReady(factory) || upgradeRevision == Long.MAX_VALUE) {
      return false;
    }
    var definitions = FactoryUpgradeService.snapshot();
    long revision = upgradeRevision;
    var expected = context(factory).tickCosts();
    var expectedData = context(factory).tickData();
    var expectedFinishData = context(factory).finishData();
    var actual = new ArrayList<StartCostSlot>();
    var actualData = new FactoryUpgradeRuntimeData.Subscriptions();
    var actualFinishData = new FactoryUpgradeRuntimeData.Subscriptions(FactoryUpgradeRuntimeData.Phase.FINISHED);
    rebuildingCatalog = true;
    try {
      for (int slot = 0; slot < slots; slot++) {
        var definition = definitions.match(inventory.getStackInSlot(slot)).orElse(null);
        if (definition != null && definition.compatibility().allows(factory.machineId())) {
          if (definition.tickCost().present()) {
            actual.add(new StartCostSlot(slot, definition.tickCost()));
          }
          actualData.add(definition, slot);
          actualFinishData.add(definition, slot);
        }
      }
    } finally {
      rebuildingCatalog = false;
    }
    if (revision != upgradeRevision || definitions != FactoryUpgradeService.snapshot()) {
      return false;
    }
    if (!actual.equals(expected)
        || !actualData.build().equals(expectedData)
        || !actualFinishData.build().equals(expectedFinishData)) {
      onInventoryChanged();
    }

    return tickCostsReady(factory);
  }

  public List<StartCostSlot> startCostSlots() {
    return upgradeCatalogValid() ? displayContext().costs() : List.of();
  }

  public boolean startDataRequired(UpgradeBusOwner factory) {
    return context(factory).required().itemData();
  }

  public boolean startDataRequired() {
    return displayContext().required().itemData();
  }

  public List<FactoryUpgradeItemData.Subscription> startDataSubscriptions(UpgradeBusOwner factory) {
    return upgradeCatalogValid(factory) ? context(factory).itemData() : List.of();
  }

  public List<FactoryUpgradeItemData.Subscription> startDataSubscriptions() {
    return upgradeCatalogValid() ? displayContext().itemData() : List.of();
  }

  public boolean startBusDataRequired(UpgradeBusOwner factory) {
    return context(factory).required().busData();
  }

  public boolean startBusDataRequired() {
    return displayContext().required().busData();
  }

  public List<FactoryUpgradeBusData.Subscription> startBusDataSubscriptions(UpgradeBusOwner factory) {
    return upgradeCatalogValid(factory) ? context(factory).busData() : List.of();
  }

  public List<FactoryUpgradeBusData.Subscription> startBusDataSubscriptions() {
    return upgradeCatalogValid() ? displayContext().busData() : List.of();
  }

  public FactoryUpgradeBusData.State busDataSnapshot() {
    requireServer();
    if (!schemaSupported() || inventory.isInTransaction()) {
      throw new IllegalStateException("Upgrade bus data is unavailable");
    }
    return customData;
  }

  public CompoundTag upgradeCustomData(ResourceLocation definitionId) {
    return busDataSnapshot().get(definitionId);
  }

  public boolean beginLifecycleNotification(UpgradeBusOwner factory) {
    requireServer();
    if (lifecycleNotification || loading || rebuildingCatalog || !upgradeCatalogValid(factory)) {
      return false;
    }
    lifecycleNotification = true;
    return true;
  }

  public boolean beginRuntimeNotification(UpgradeBusOwner factory) {
    requireServer();
    if (lifecycleNotification || loading || rebuildingCatalog || !tickDataReady(factory)) {
      return false;
    }
    lifecycleNotification = true;

    return true;
  }

  public void endLifecycleNotification() {
    requireServer();
    lifecycleNotification = false;
  }

  boolean bindDiscoveredFactory(UpgradeBusOwner factory) {
    return bindFactory(factory);
  }

  public boolean bindFactory(UpgradeBusOwner factory) {
    var server = requireServer();
    if (FactoryUpgradeEvents.isDispatchingAny()
        || !bindingMutationAllowed()
        || !schemaSupported()
        || !liveBus(server)
        || !liveFactory(server, factory)
        || !structureMember(factory)) {
      return false;
    }
    var next = new UpgradeBusBinding(factory.getBlockPos(), factory.machineId());
    var previous = bindings.get(next.controller());
    if (!next.equals(previous)) {
      if (bindingRevision == Long.MAX_VALUE || previous == null && bindings.size() >= UpgradeBusBinding.MAX_BINDINGS) {
        return false;
      }
      withdraw(next.controller());
      bindings.put(next.controller(), next);
      bindingRevision++;
      setChanged();
      retainContexts();
    }
    refreshUpgradeDefinitions();
    refreshBinding();
    return published.get(next.controller()) == factory;
  }

  public void unbindFactory(UpgradeBusOwner factory) {
    requireServer();
    if (FactoryUpgradeEvents.isDispatchingAny() || !bindingMutationAllowed()) {
      throw new IllegalStateException("Binding changes are not allowed during upgrade evaluation");
    }
    if (factory != null) {
      removeBinding(factory.getBlockPos());
    }
  }

  public void unbindFactory() {
    requireServer();
    if (FactoryUpgradeEvents.isDispatchingAny() || !bindingMutationAllowed() || bindingRevision == Long.MAX_VALUE) {
      throw new IllegalStateException("Upgrade bindings are read-only");
    }
    withdrawAll();
    if (!bindings.isEmpty()) {
      bindings.clear();
      bindingRevision++;
      setChanged();
      retainContexts();
      rebuildContexts();
    }
  }

  public boolean hasFactoryBinding(UpgradeBusOwner factory) {
    var server = requireServer();
    if (!schemaSupported() || factory == null) {
      return false;
    }
    var binding = bindings.get(factory.getBlockPos());
    return binding != null
        && binding.machine().equals(factory.machineId())
        && liveBus(server)
        && liveFactory(server, factory)
        && structureMember(factory);
  }

  public boolean isBoundTo(UpgradeBusOwner factory) {
    requireServer();
    return upgradeCatalogValid(factory);
  }

  public void serverTick() {
    var server = requireServer();
    if (!bindingMutationAllowed()) {
      return;
    }
    refreshUpgradeDefinitions();
    long now = server.getGameTime();
    if (lastBindingCheck == Long.MIN_VALUE || now < lastBindingCheck || now - lastBindingCheck >= 20) {
      lastBindingCheck = now;
      refreshBinding();
    }
  }

  public void refreshBinding() {
    var server = requireServer();
    if (!bindingMutationAllowed()) {
      return;
    }
    ensureDefinitionsCurrent();
    rebuildContexts();
    if (!schemaSupported() || !liveBus(server)) {
      withdrawAll();
      return;
    }
    for (var binding : bindings()) {
      if (!server.hasChunkAt(binding.controller())) {
        withdraw(binding.controller());
        continue;
      }
      if (!(server.getBlockEntity(binding.controller()) instanceof UpgradeBusOwner factory)
          || !liveFactory(server, factory)
          || !factory.machineId().equals(binding.machine())) {
        removeBinding(binding.controller());
        continue;
      }
      if (!factory.canCommitStructure()) {
        withdraw(binding.controller());
        continue;
      }
      if (!structureMember(factory)) {
        removeBinding(binding.controller());
        continue;
      }
      if (upgradeCatalogValid(factory) && factory.publishUpgradeBus(this)) {
        var previous = published.put(binding.controller(), factory);
        if (previous != null && previous != factory) {
          previous.withdrawUpgradeBus(this);
        }
      } else {
        withdraw(binding.controller());
      }
    }
  }

  private void removeBinding(BlockPos position) {
    withdraw(position);
    if (bindings.containsKey(position) && bindingRevision < Long.MAX_VALUE) {
      bindings.remove(position);
      bindingRevision++;
      setChanged();
      retainContexts();
    }
  }

  private boolean bindingMutationAllowed() {
    return !loading
        && !rebuildingCatalog
        && !lifecycleNotification
        && !inventory.isInTransaction()
        && !RecipeLifecycleEvents.isDispatching()
        && !SmartInterfaceEvents.isDispatching();
  }

  private boolean liveBus(ServerLevel server) {
    return !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockState(worldPosition).getBlock() instanceof UpgradeBusBlock block
        && block.tier() == tier
        && server.getBlockEntity(worldPosition) == this;
  }

  private boolean liveFactory(ServerLevel server, UpgradeBusOwner factory) {
    return factory != null
        && factory.getLevel() == server
        && !factory.isRemoved()
        && factory.getBlockPos().distSqr(worldPosition) <= 64 * 64
        && server.hasChunkAt(factory.getBlockPos())
        && server.getBlockEntity(factory.getBlockPos()) == factory;
  }

  private boolean structureMember(UpgradeBusOwner factory) {
    return factory.containsUpgradeBusPart(worldPosition);
  }

  private void withdraw(BlockPos position) {
    var factory = published.remove(position);
    if (factory != null) {
      factory.withdrawUpgradeBus(this);
    }
  }

  private void withdrawAll() {
    for (var position : List.copyOf(published.keySet())) {
      withdraw(position);
    }
  }

  private ServerLevel requireServer() {
    if (!(level instanceof ServerLevel server)) {
      throw new IllegalStateException("Upgrade bus access requires a server level");
    }
    ServerThreadGuard.require(server, "Upgrade bus accessed");
    return server;
  }

  private void requireMutationThread() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Upgrade bus inventory modified");
    }
  }

  private void beforeInventoryChange() {
    requireMutationThread();
    if (lifecycleNotification
        || rebuildingCatalog
        || level instanceof ServerLevel
            && (RecipeLifecycleEvents.isDispatching() || SmartInterfaceEvents.isDispatching())
        || FactoryUpgradeEvents.isDispatchingAny()
        || !loading && (!schemaSupported() || upgradeRevision == Long.MAX_VALUE)) {
      throw new IllegalStateException("Upgrade bus inventory is read-only");
    }
  }

  private Set<ResourceLocation> requiredMachines() {
    var result = new HashSet<ResourceLocation>();
    if (bindings.isEmpty()) {
      result.add(null);
    } else {
      bindings.values().forEach(binding -> result.add(binding.machine()));
    }
    return result;
  }

  private void retainContexts() {
    contexts.keySet().retainAll(requiredMachines());
  }

  private void rebuildContexts() {
    if (!schemaSupported() || rebuildingCatalog || lifecycleNotification || inventory.isInTransaction()) {
      return;
    }
    var definitions = FactoryUpgradeService.snapshot();
    definitionsEpoch = definitions.epoch();
    var machines = requiredMachines();
    contexts.keySet().retainAll(machines);
    if (machines.stream().allMatch(machine -> {
      var cached = contexts.get(machine);
      return cached != null && cached.revision() == upgradeRevision && cached.epoch() == definitions.epoch();
    })) {
      return;
    }
    rebuildingCatalog = true;
    long revision = upgradeRevision;
    try {
      var contents = new ArrayList<ItemStack>();
      for (int slot = 0; slot < inventory.getSlots(); slot++) {
        contents.add(inventory.getStackInSlot(slot).copy());
      }
      var ordered = machines.stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
      for (var machine : ordered) {
        var cached = contexts.get(machine);
        if (cached != null && cached.revision() == revision && cached.epoch() == definitions.epoch()) {
          continue;
        }
        var context = FactoryUpgradeBusContext
            .compile(definitions, contents, machine, worldPosition, revision, customData);
        if (upgradeRevision != revision || FactoryUpgradeService.snapshot().epoch() != definitions.epoch()) {
          contexts.put(machine, FactoryUpgradeBusContext.unavailable(machine, revision, definitions.epoch()));
          break;
        }
        contexts.put(machine, context);
      }
    } finally {
      rebuildingCatalog = false;
    }
  }

  private void ensureDefinitionsCurrent() {
    long epoch = FactoryUpgradeService.snapshot().epoch();
    if (definitionsEpoch != epoch) {
      withdrawAll();
      contexts.clear();
      if (upgradeRevision == Long.MAX_VALUE) {
        return;
      }
      upgradeRevision++;
      definitionsEpoch = epoch;
      setChanged();
    }
  }

  public void refreshUpgradeDefinitions() {
    requireServer();
    if (!bindingMutationAllowed() || !schemaSupported()) {
      return;
    }
    ensureDefinitionsCurrent();
    rebuildContexts();
  }

  private void onInventoryChanged() {
    if (!loading && (level == null || !level.isClientSide)) {
      upgradeRevision = Math.incrementExact(upgradeRevision);
      contexts.clear();
      withdrawAll();
      rebuildContexts();
      setChanged();
      if (level instanceof ServerLevel) {
        refreshBinding();
      }
    }
  }

  public record StartCostSlot(int stackSlot, FactoryUpgradeStartCost cost) {
    public StartCostSlot {
      Objects.requireNonNull(cost, "cost");
      if (stackSlot < 0 || stackSlot >= FactoryUpgradeCatalog.MAX_SLOTS || !cost.present()) {
        throw new IllegalArgumentException("Invalid upgrade start cost slot");
      }
    }
  }

  public StartCostLease acquireStartCost(UpgradeBusOwner factory, long expectedRevision, FactoryUpgradeCatalog expectedCatalog, NonNullList<ItemStack> contents) {
    if (startBusDataRequired(factory)) {
      throw new IllegalStateException("Bus data needs a prepared startup state");
    }
    return acquireStartup(factory, expectedRevision, expectedCatalog, contents, customData);
  }

  public StartCostLease acquireStartup(UpgradeBusOwner factory, long expectedRevision, FactoryUpgradeCatalog expectedCatalog, NonNullList<ItemStack> contents, FactoryUpgradeBusData.State preparedData) {
    requireServer();
    var context = context(factory);
    if (expectedRevision != upgradeRevision
        || expectedRevision == Long.MAX_VALUE
        || expectedCatalog != context.catalog()
        || !upgradeCatalogValid(factory)
        || !context.required().costs() && !context.required().itemData() && !context.required().busData()
        || context.costs().isEmpty() && context.itemData().isEmpty() && context.busData().isEmpty()
        || contents == null
        || preparedData == null
        || contents.size() != slots
        || !bindingMutationAllowed()) {
      throw new IllegalStateException("Stale upgrade startup transaction");
    }
    var storage = inventory.acquire();
    storage.replacePrepared(contents);
    var before = customData;
    customData = preparedData;
    return new StartCostLease(storage, before);
  }

  public StartCostLease acquireTickCost(UpgradeBusOwner factory, long expectedRevision, long expectedEpoch, List<StartCostSlot> expectedCosts, NonNullList<ItemStack> contents) {
    requireServer();
    var context = context(factory);
    if (expectedRevision != upgradeRevision
        || expectedRevision == Long.MAX_VALUE
        || expectedEpoch != context.epoch()
        || expectedCosts != context.tickCosts()
        || expectedCosts.isEmpty()
        || !tickCostsReady(factory)
        || contents == null
        || contents.size() != slots
        || !bindingMutationAllowed()) {
      throw new IllegalStateException("Stale upgrade tick cost transaction");
    }
    var storage = inventory.acquire();
    storage.replacePrepared(contents);

    return new StartCostLease(storage, customData);
  }

  public StartCostLease acquireRuntime(UpgradeBusOwner factory, FactoryUpgradeRuntimeData.Phase phase, long expectedRevision, long expectedEpoch, List<StartCostSlot> expectedCosts, List<FactoryUpgradeRuntimeData.Subscription> expectedData, NonNullList<ItemStack> contents, FactoryUpgradeBusData.State preparedData) {
    requireServer();
    Objects.requireNonNull(phase, "phase");
    var context = context(factory);
    if (expectedRevision != upgradeRevision
        || expectedRevision == Long.MAX_VALUE
        || expectedEpoch != context.epoch()
        || phase == FactoryUpgradeRuntimeData.Phase.TICK && expectedCosts != context.tickCosts()
        || phase == FactoryUpgradeRuntimeData.Phase.FINISHED && !expectedCosts.isEmpty()
        || expectedData != context.runtimeData(phase)
        || expectedCosts.isEmpty() && expectedData.isEmpty()
        || !tickDataReady(factory)
        || contents == null
        || preparedData == null
        || contents.size() != slots
        || !bindingMutationAllowed()) {
      throw new IllegalStateException("Stale runtime upgrade transaction");
    }
    var storage = inventory.acquire();
    storage.replacePrepared(contents);
    var before = customData;
    customData = preparedData;

    return new StartCostLease(storage, before);
  }

  public final class StartCostLease implements AutoCloseable {
    private final TransactionalItemStackHandler.Lease storage;
    private final FactoryUpgradeBusData.State beforeData;
    private boolean resolved, committed, published;

    private StartCostLease(TransactionalItemStackHandler.Lease storage, FactoryUpgradeBusData.State beforeData) {
      this.storage = storage;
      this.beforeData = beforeData;
    }

    public void resolve(boolean commit) {
      requireServer();
      if (resolved) {
        throw new IllegalStateException("Resolved upgrade startup lease");
      }
      storage.resolve(commit);
      if (!commit) {
        customData = beforeData;
      } else {
        upgradeRevision++;
        contexts.clear();
        committed = true;
      }
      resolved = true;
    }

    public void publish() {
      requireServer();
      if (!resolved) {
        throw new IllegalStateException("Unresolved upgrade startup lease");
      }
      if (committed && !published) {
        published = true;
        setChanged();
        withdrawAll();
        rebuildContexts();
        refreshBinding();
      }
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel) {
      refreshBinding();
    }
  }

  @Override
  public void setRemoved() {
    if (level instanceof ServerLevel) {
      requireServer();
      withdrawAll();
      contexts.clear();
    }
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    if (level instanceof ServerLevel) {
      requireServer();
      withdrawAll();
      contexts.clear();
    }
    super.onChunkUnloaded();
  }

  @Override
  public Component getDisplayName() {
    return Component.translatable("block.mmcece.upgrade_bus_" + tier.name().toLowerCase(Locale.ROOT));
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return schemaSupported() ? new UpgradeBusMenu(id, inventory, this) : null;
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    if (!schemaSupported()) {
      tag.merge(unsupportedData.copy());
      return;
    }
    var data = new CompoundTag();
    data.putInt("schema", DATA_SCHEMA);
    data.put("inventory", inventory.serializeNBT(registries));
    data.putLong("upgrade_revision", upgradeRevision);
    data.putLong("binding_revision", bindingRevision);
    data.put("bindings", UpgradeBusBinding.write(bindings.values()));
    if (!customData.isEmpty()) {
      data.put("custom_data", customData.save());
    }
    tag.put(DATA_KEY, data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    beforeInventoryChangeForLoad();
    boolean live = level instanceof ServerLevel server && liveBus(server);
    long previousRevision = upgradeRevision;
    long previousBindingRevision = bindingRevision;
    super.loadAdditional(tag, registries);
    withdrawAll();
    unsupportedData = null;
    bindings.clear();
    contexts.clear();
    customData = FactoryUpgradeBusData.State.empty();
    upgradeRevision = 0;
    bindingRevision = 0;
    definitionsEpoch = -1;
    lastBindingCheck = Long.MIN_VALUE;
    loading = true;
    try {
      if (!tag.contains(DATA_KEY)) {
        if (tag.contains("inventory")
            || tag.contains("upgrade_revision")
            || tag.contains("bound_factory")
            || tag.contains("bound_machine")
            || tag.contains(FactoryUpgradeBusData.NBT_KEY)) {
          throw new IllegalArgumentException("Unsupported unpublished upgrade bus format");
        }
        for (int slot = 0; slot < slots; slot++) {
          inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
      } else {
        if (!tag.contains(DATA_KEY, Tag.TAG_COMPOUND)) {
          throw new IllegalArgumentException("Upgrade bus data must be a compound");
        }
        var data = tag.getCompound(DATA_KEY);
        if (!Set.of("schema", "inventory", "upgrade_revision", "binding_revision", "bindings", "custom_data")
            .containsAll(data.getAllKeys())
            || !data.contains("schema", Tag.TAG_INT)
            || data.getInt("schema") != DATA_SCHEMA
            || !data.contains("inventory", Tag.TAG_COMPOUND)
            || !data.contains("upgrade_revision", Tag.TAG_LONG)
            || data.getLong("upgrade_revision") < 0
            || !data.contains("binding_revision", Tag.TAG_LONG)
            || data.getLong("binding_revision") < 0
            || !(data.get("bindings") instanceof ListTag savedBindings)
            || data.contains("custom_data") && !data.contains("custom_data", Tag.TAG_COMPOUND)) {
          throw new IllegalArgumentException("Invalid upgrade bus header");
        }
        var restored = new ItemStackHandler(slots);
        var inventoryData = data.getCompound("inventory");
        validateInventory(inventoryData);
        restored.deserializeNBT(registries, inventoryData);
        var restoredBindings = UpgradeBusBinding.read(savedBindings);
        var restoredData = data.contains("custom_data") ? FactoryUpgradeBusData.State
            .load(data.getCompound("custom_data")) : FactoryUpgradeBusData.State.empty();
        for (int slot = 0; slot < slots; slot++) {
          inventory.setStackInSlot(slot, restored.getStackInSlot(slot));
        }
        bindings.putAll(restoredBindings);
        customData = restoredData;
        upgradeRevision = data.getLong("upgrade_revision");
        bindingRevision = data.getLong("binding_revision");
      }
    } catch (RuntimeException invalid) {
      unsupportedData = new CompoundTag();
      for (String key : List.of(
          DATA_KEY,
          "inventory",
          "upgrade_revision",
          "bound_factory",
          "bound_machine",
          FactoryUpgradeBusData.NBT_KEY)) {
        if (tag.contains(key)) {
          unsupportedData.put(key, tag.get(key).copy());
        }
      }
      bindings.clear();
      contexts.clear();
    } finally {
      loading = false;
    }
    if (schemaSupported()) {
      if (live) {
        upgradeRevision = advanceRestoredRevision(previousRevision, upgradeRevision);
        bindingRevision = advanceRestoredRevision(previousBindingRevision, bindingRevision);
        setChanged();
      }
      rebuildContexts();
    }
    if (level instanceof ServerLevel) {
      refreshBinding();
    }
  }

  private static long advanceRestoredRevision(long previous, long restored) {
    long latest = Math.max(previous, restored);

    return latest == Long.MAX_VALUE ? latest : latest + 1;
  }

  private void beforeInventoryChangeForLoad() {
    requireMutationThread();
    if (lifecycleNotification
        || rebuildingCatalog
        || inventory.isInTransaction()
        || level instanceof ServerLevel
            && (RecipeLifecycleEvents.isDispatching() || SmartInterfaceEvents.isDispatching())) {
      throw new IllegalStateException("Upgrade bus load during an active transaction or callback");
    }
  }

  private void validateInventory(CompoundTag data) {
    if (!data.contains("Size", Tag.TAG_INT)
        || data.getInt("Size") != slots
        || !(data.get("Items") instanceof ListTag items)
        || items.size() > slots
        || !items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND) {
      throw new IllegalArgumentException("Upgrade bus inventory does not match its tier");
    }
    var occupied = new HashSet<Integer>();
    for (var item : items) {
      var entry = (CompoundTag) item;
      int slot = entry.getInt("Slot");
      if (!entry.contains("Slot", Tag.TAG_INT) || slot < 0 || slot >= slots || !occupied.add(slot)) {
        throw new IllegalArgumentException("Invalid upgrade bus inventory slot");
      }
    }
  }
}
