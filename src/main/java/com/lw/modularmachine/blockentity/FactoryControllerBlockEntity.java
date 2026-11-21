package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.machine.recipe.FactoryRunResourceResult;
import com.lw.modularmachine.machine.factory.FactoryExecutionCoordinator;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSources;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.controller.GenericControllerDiscovery;
import com.lw.modularmachine.machine.controller.ControllerScriptState;
import com.lw.modularmachine.machine.controller.ControllerScriptStateOwner;
import com.lw.modularmachine.machine.controller.ControllerVisualData;
import com.lw.modularmachine.machine.controller.ControllerVisualDataSync;
import com.lw.modularmachine.machine.controller.ControllerOwnershipState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandSubject;
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.machine.factory.FactoryRunState;
import com.lw.modularmachine.machine.factory.FactoryRunCoordinator;
import com.lw.modularmachine.machine.factory.FactoryScheduler;
import com.lw.modularmachine.machine.factory.FactorySearchCoordinator;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.MachineRecipe;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration;
import com.lw.modularmachine.machine.factory.FactoryThreadConfiguration.ConfigurationResult;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.upgrade.UpgradeRestrictions.Result;
import com.lw.modularmachine.machine.upgrade.UpgradeRuntimeWork;
import com.lw.modularmachine.machine.upgrade.UpgradeStartCosts;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.machine.upgrade.UpgradeStartState;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.machine.recipe.FactoryOutputEngine;
import com.lw.modularmachine.machine.recipe.FactoryParallelResourceCommitter;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.FactoryRunResourceCommitter;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.PatternRunSources;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.controller.MachineEventPublisher;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.api.recipe.RecipeExecutionOptions;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.machine.controller.ControllerBlueprintInventory;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.machine.recipe.RecipeTickPreparation;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import java.util.HashSet;

public final class FactoryControllerBlockEntity extends BlockEntity implements MenuProvider, UpgradeBusOwner, ControllerScriptStateOwner, ControllerCommandSubject {
  private static final int FACTORY_SCHEMA = 7;
  private final ControllerMetrics metrics = new ControllerMetrics();
  private final FactoryThreadConfiguration threadConfiguration = new FactoryThreadConfiguration(
      new ThreadConfigurationHost());
  private boolean persistentStateValid = true;
  private CompoundTag opaqueFactoryState;
  private ControllerScriptState controllerScriptState = ControllerScriptState.empty();
  private ControllerVisualData clientVisualData;
  private Tag unsupportedScriptData;
  private ControllerOwnershipState ownership = ControllerOwnershipState.empty();
  private ControllerCommandState commandState = new ControllerCommandState();
  private PatternRunSources patternRunSources = new PatternRunSources();
  private final GenericControllerDiscovery genericDiscovery = new GenericControllerDiscovery();
  private ResourceLocation machineId = ControllerPlacementData.UNBOUND_MACHINE;
  private final ControllerBlueprintInventory blueprintInventory = new ControllerBlueprintInventory(
      this::checkBlueprintWrite,
      this::blueprintChanged);
  private StructureService.Registration registration;
  private VerificationState detachedState = VerificationState.PENDING;
  private VerificationFailure detachedFailure = VerificationFailure.UNVERIFIED;
  private final FactorySearchCoordinator searchCoordinator = new FactorySearchCoordinator(
      threadConfiguration,
      new SearchHost());
  private final FactoryRunState runState = new FactoryRunState(threadConfiguration, new RunStateHost());
  private final FactoryUpgradeSources upgrades = new FactoryUpgradeSources(
      this,
      threadConfiguration,
      new UpgradeSourcesHost());
  private final UpgradeRuntimeWork runtimeUpgrades = new UpgradeRuntimeWork(this);
  private final FactoryRunCoordinator runCoordinator = new FactoryRunCoordinator(
      threadConfiguration,
      runState,
      new RunHost());
  private final FactoryExecutionCoordinator execution = new FactoryExecutionCoordinator(
      threadConfiguration,
      upgrades,
      runCoordinator,
      searchCoordinator,
      metrics,
      new ExecutionHost());

  private final class ExecutionHost implements FactoryExecutionCoordinator.Host {
    @Override
    public RecipeCatalog catalog() {
      return MachineRecipeService.catalogFor(FactoryControllerBlockEntity.this).orElse(null);
    }
    @Override
    public VerificationState verificationState() {
      return FactoryControllerBlockEntity.this.verificationState();
    }
    @Override
    public boolean persistentStateValid() {
      return persistentStateValid;
    }
  }

  private final class UpgradeSourcesHost implements FactoryUpgradeSources.Host {
    @Override
    public boolean confirmed() {
      return verificationState() == VerificationState.CONFIRMED;
    }
    @Override
    public boolean idle(String threadId) {
      return runSet(threadId).runs().isEmpty();
    }
    @Override
    public boolean persistentStateValid() {
      return persistentStateValid;
    }
    @Override
    public void markChanged() {
      setChanged();
    }
    @Override
    public void invalidateSearch() {
      searchCoordinator.invalidate();
    }
    @Override
    public void invalidateSearch(String threadId, long tick) {
      searchCoordinator.invalidateThread(threadId, tick);
    }
  }

  @ApiStatus.Internal
  public FactoryUpgradeSources upgrades() {
    return upgrades;
  }

  @Override
  public boolean publishUpgradeBus(UpgradeBusBlockEntity bus) {
    return upgrades.publishUpgradeBus(bus);
  }
  @Override
  public void withdrawUpgradeBus(UpgradeBusBlockEntity bus) {
    upgrades.withdrawUpgradeBus(bus);
  }
  @Override
  public boolean containsUpgradeBusPart(BlockPos position) {
    return upgrades.containsUpgradeBusPart(position);
  }
  @Override
  public List<BlockPos> upgradeBusPositions() {
    return upgrades.upgradeBusPositions();
  }
  @Override
  public List<UpgradeBusBlockEntity> upgradeCallbackBuses() {
    return upgrades.upgradeCallbackBuses();
  }
  @Override
  public Result upgradeRestrictionStatus() {
    return upgrades.upgradeRestrictionStatus();
  }
  public boolean upgradeRestrictionsCurrent() {
    return upgrades.upgradeRestrictionsCurrent();
  }

  private void requireUpgradeThread() {
    if (!(level instanceof ServerLevel server)) {
      throw new IllegalStateException("Factory upgrade sources require a server level");
    }
    ServerThreadGuard.require(server, "Factory upgrade sources accessed");
  }

  private final class RunHost implements FactoryRunCoordinator.Host {
    @Override
    public boolean confirmed() {
      return verificationState() == VerificationState.CONFIRMED;
    }
    @Override
    public HolderLookup.Provider registries() {
      return level.registryAccess();
    }
    @Override
    public long gameTime() {
      return level.getGameTime();
    }
    @Override
    public boolean bindingsCurrent(FrozenRecipeRun run) {
      return canCommitStructure()
          && run.matchesBindings(registration.pattern.definition)
          && PatternRunSources.available(FactoryControllerBlockEntity.this, run);
    }
    @Override
    public boolean beforeTick(String threadId, List<FrozenRecipeRun> runs) {
      return runtimeUpgrades.prepareRecipeTick(threadId, runs) == RecipeTickPreparation.Status.ALLOWED
          && runs.stream().allMatch(this::bindingsCurrent)
          && runSet(threadId).runs().containsAll(runs);
    }
    @Override
    public FactoryRunResourceResult tickParallel(String threadId, FactoryRunSet set) {
      return FactoryParallelResourceCommitter.tick(FactoryControllerBlockEntity.this, threadId, set);
    }
    @Override
    public FactoryRunResourceResult tickSingle(String threadId, FrozenRecipeRun run) {
      return FactoryRunResourceCommitter.tick(FactoryControllerBlockEntity.this, threadId, run);
    }
    @Override
    public FactoryOutputEngine.Result advanceOutput(FactoryOutputEngine engine, String threadId, FactoryRunSet set) {
      if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
        throw new IllegalStateException("Factory output planning outside server thread");
      }
      return engine.advance(FactoryControllerBlockEntity.this, threadId, set);
    }
    @Override
    public void advanced(String threadId, List<FrozenRecipeRun> before, List<FrozenRecipeRun> after) {
      RecipeLifecyclePublisher.advanced(FactoryControllerBlockEntity.this, threadId, before, after);
    }
    @Override
    public void markChanged() {
      setChanged();
    }
  }

  private final class RunStateHost implements FactoryRunState.Host {
    @Override
    public void requireThread() {
      requireUpgradeThread();
    }
    @Override
    public void requireLoadAllowed() {
      if (level instanceof ServerLevel server) {
        ServerThreadGuard.require(server, "Factory load");
        if (UpgradeLifecyclePublisher.isDispatchingAny()
            || SmartInterfaceEventPublisher.isDispatching()
            || !runState.startingThreads().isEmpty()) {
          throw new IllegalStateException("Factory load during an active upgrade callback or startup");
        }
      }
    }
    @Override
    public void refreshUpgrades() {
      upgrades.refreshSnapshot();
    }
    @Override
    public boolean validSources() {
      return persistentStateValid && upgrades.valid();
    }
    @Override
    public boolean startAllowed(String threadId) {
      return threadStartAllowed(threadId);
    }
    @Override
    public boolean upgradesMatch(FactoryThreadState thread) {
      return upgrades.appliedTo(thread);
    }
    @Override
    public int parallelLimit(String threadId) {
      return effectiveParallelLimit(threadId);
    }
    @Override
    public boolean recipeAllowed(String threadId, ResourceLocation recipeId, RecipeExecutionOptions options) {
      return recipeThreadAllowed(threadId, recipeId, options);
    }
  }

  private final class SearchHost implements FactorySearchCoordinator.Host {
    @Override
    public boolean available() {
      return verificationState() == VerificationState.CONFIRMED && upgrades.valid();
    }
    @Override
    public long gameTime() {
      return level.getGameTime();
    }
    @Override
    public boolean hasRuns(String threadId) {
      return !runSet(threadId).runs().isEmpty();
    }
    @Override
    public boolean startAllowed(String threadId) {
      return threadStartAllowed(threadId);
    }
    @Override
    public RecipeSearch beginSearch(String threadId, RecipeCatalog catalog, Set<String> allowed) {
      return RecipeSearch.beginFactory(FactoryControllerBlockEntity.this, threadId, catalog, allowed).orElse(null);
    }
    @Override
    public int parallelLimit(String threadId) {
      return effectiveParallelLimit(threadId);
    }
    @Override
    public int maximumUpgradeRuns() {
      return UpgradeStartCosts.maxRuns(FactoryControllerBlockEntity.this);
    }
    @Override
    public MachineRecipe definitionForStart(RecipeStartPlan plan) {
      return MachineRecipeService.definitionForStart(FactoryControllerBlockEntity.this, plan).orElse(null);
    }
    @Override
    public FactoryRecipeStarter.Result startSingle(String threadId, RecipeStartPlan plan, int workBudget) {
      return FactoryRecipeStarter.start(FactoryControllerBlockEntity.this, threadId, plan, workBudget);
    }
    @Override
    public FactoryParallelStarter.Result startParallel(String threadId, RecipeStartPlan plan, int count, int workBudget) {
      return FactoryParallelStarter
          .commit(FactoryControllerBlockEntity.this, threadId, Collections.nCopies(count, plan), workBudget);
    }
  }

  private final class ThreadConfigurationHost implements FactoryThreadConfiguration.Host {
    @Override
    public void requireThread() {
      requireUpgradeThread();
    }
    @Override
    public void requireUpdateThread() {
      if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
        throw new IllegalStateException("Factory thread update outside server thread");
      }
    }
    @Override
    public boolean persistentStateValid() {
      return persistentStateValid;
    }
    @Override
    public boolean current() {
      return !isRemoved()
          && level.hasChunkAt(worldPosition)
          && level.getBlockEntity(worldPosition) == FactoryControllerBlockEntity.this;
    }
    @Override
    public boolean dispatching() {
      return UpgradeLifecyclePublisher.isDispatching(FactoryControllerBlockEntity.this);
    }
    @Override
    public ResourceLocation machineId() {
      return machineId;
    }
    @Override
    public boolean acceptsMachine(ResourceLocation machine) {
      return !(getBlockState().getBlock() instanceof FactoryControllerBlock block)
          || block.dedicated() == null
          || block.dedicated().machine().equals(machine)
          || Objects.equals(machine, blueprintInventory.machine());
    }
    @Override
    public void bindMachine(ResourceLocation machine) {
      FactoryControllerBlockEntity.this.bindMachine(machine);
    }
    @Override
    public boolean hasPaidRuns() {
      return !runState.view().isEmpty();
    }
    @Override
    public boolean hasPaidRuns(String threadId) {
      return !runSet(threadId).runs().isEmpty();
    }
    @Override
    public boolean hasStartingThreads() {
      return !runState.startingThreads().isEmpty();
    }
    @Override
    public boolean threadBusy(String threadId) {
      return hasPaidRuns(threadId) || runState.startingThreads().contains(threadId);
    }
    @Override
    public int maximumThreads() {
      return maximumBusinessThreads();
    }
    @Override
    public boolean recipeBelongsTo(ResourceLocation machine, ResourceLocation recipe) {
      return MachineRecipeService.recipeBelongsTo(((ServerLevel) level).getServer(), machine, recipe).orElse(false);
    }
    @Override
    public void beforeInstall() {
      PatternProductionService.invalidate(FactoryControllerBlockEntity.this);
    }
    @Override
    public void installed() {
      searchCoordinator.invalidate();
      runCoordinator.invalidateOutput();
      upgrades.configurationInstalled();
      setChanged();
    }
    @Override
    public void threadChanged(String threadId) {
      searchCoordinator.invalidateThread(threadId, level.getGameTime());
    }
    @Override
    public void markChanged() {
      setChanged();
    }
  }

  private void blueprintChanged() {
    PatternProductionService.invalidate(this);
    setChanged();
    if (level instanceof ServerLevel) {
      boolean formed = registration != null && registration.verifier.state() == VerificationState.CONFIRMED;
      genericDiscovery.clear();
      searchCoordinator.invalidate();
      var target = blueprintInventory.machine();
      if (!formed
          && target != null
          && !target.equals(machineId)
          && threadConfiguration.configureMachine(target) == ConfigurationResult.SUCCESS) {
        return;
      }
      StructureService.selectionChanged(this);
    }
  }

  public FactoryControllerBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.FACTORY_CONTROLLER_ENTITY.get(), pos, state);
    if (state.getBlock() instanceof FactoryControllerBlock block && block.dedicated() != null) {
      machineId = block.dedicated().machine();
    }
  }

  @Override
  @ApiStatus.Internal
  public ControllerOwnershipState ownershipState() {
    return ownership;
  }

  @ApiStatus.Internal
  public PatternRunSources patternRunSources() {
    return patternRunSources;
  }

  public void patternResourcesChanged() {
    if (level instanceof ServerLevel server) {
      if (!server.getServer().isSameThread()) {
        throw new IllegalStateException("Pattern resource notification outside server thread");
      }
      // Wake idle retries; in-flight searches retain their bounded progress and
      // invalidate themselves only when their actual dependencies change.
      searchCoordinator.wakeRetries();
      setChanged();
    }
  }

  @Override
  @ApiStatus.Internal
  public ControllerCommandState commandState() {
    return commandState;
  }

  @Override
  @ApiStatus.Internal
  public boolean commandExecutionAllowed() {
    return commandState.valid()
        && persistentStateValid()
        && runState.startingThreads().isEmpty()
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  @Override
  @ApiStatus.Internal
  public void initializePlayerOwner(UUID playerId) {
    ControllerOwnershipService.requireInitialization(this);
    checkBlueprintWrite();
    var claimed = ownership.claimIfUnowned(playerId);
    if (claimed != ownership) {
      ownership = claimed;
      setChanged();
      ControllerOwnershipService.synchronize(this);
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel
        && blueprintInventory.machine() != null
        && !blueprintInventory.machine().equals(machineId)) {
      threadConfiguration.configureMachine(blueprintInventory.machine());
    }
    StructureService.register(this);
    refreshAppearanceState();
    RecipeCommandService.schedule(this);
    ControllerVisualDataSync.schedule(this);
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    var tag = ownership.clientTag();
    ControllerVisualData.save(tag, this);

    return tag;
  }

  @Override
  public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }

  @Override
  public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
    if (level != null && level.isClientSide) {
      ownership = ControllerOwnershipState.load(tag);
      var previousVisualData = clientVisualData;
      clientVisualData = ControllerVisualData.load(tag);
      if (clientVisualData != null) {
        machineId = clientVisualData.machine();
      }
      if (!Objects.equals(previousVisualData, clientVisualData)) {
        requestModelDataUpdate();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8);
      }
    }
  }

  @Override
  public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
    handleUpdateTag(packet.getTag(), registries);
  }

  @Override
  public void setRemoved() {
    detachRuntime();
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    detachRuntime();
    super.onChunkUnloaded();
  }

  private void detachRuntime() {
    ControllerVisualDataSync.remove(this);
    clientVisualData = null;
    PatternProductionService.invalidate(this);
    RecipeCommandService.unschedule(this);
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    upgrades.clearDiscovery();
    upgrades.clearBuses();
    searchCoordinator.invalidate();
    runState.clearRuns();
    runCoordinator.unloadOutput();
    runState.clearStarts();
    upgrades.clearPublished();
    StructureService.unregister(this);

  }

  public FactoryScheduler scheduler() {
    return threadConfiguration.scheduler();
  }

  public int threadCount() {
    return threadConfiguration.scheduler().channels().size();
  }

  @ApiStatus.Internal
  public int maximumBusinessThreads() {
    requireUpgradeThread();
    var definition = StructureService.definition(machineId);

    return definition == null ? MachineConfig.defaultFactoryMaxThreads() : definition.maximumFactoryThreads();
  }

  private boolean threadStartAllowed(String threadId) {
    var definition = StructureService.definition(machineId);
    var preset = definition == null ? FactoryThreadPreset.defaults() : definition.factoryPreset();
    var occupied = new HashSet<>(runState.startingThreads());
    for (var entry : runState.view().entrySet()) {
      if (!entry.getValue().runs().isEmpty()) {
        occupied.add(entry.getKey());
      }
    }

    return preset
        .startAllowed(threadId, threadConfiguration.scheduler().channels(), occupied, maximumBusinessThreads());
  }

  /** Internal start admission; existing paid runs keep their admitted thread. */
  @ApiStatus.Internal
  public boolean recipeThreadAllowed(String threadId, ResourceLocation recipeId, RecipeExecutionOptions options) {
    requireUpgradeThread();
    var thread = threadId == null ? null : threadConfiguration.scheduler().channel(threadId);
    if (threadId == null
        || recipeId == null
        || options == null
        || thread == null
        || !thread.enabled()
        || !options.accepts(threadId)
        || !FactoryThreadPreset.accepts(machineId, threadId, recipeId)
        || thread.lockedRecipe() != null && !thread.lockedRecipe().equals(recipeId)) {
      return false;
    }
    if (options.maxThreads() == 0) {
      return true;
    }
    int occupied = 0;
    for (var entry : runState.view().entrySet()) {
      if (!entry.getKey().equals(threadId)
          && entry.getValue().runs().stream().anyMatch(run -> run.recipeId().equals(recipeId))) {
        occupied++;
      }
    }
    return occupied < options.maxThreads();
  }

  public String recipeInterfaceFailure(String threadId) {
    return run(threadId).isEmpty() ? searchCoordinator.interfaceFailure(threadId) : "";
  }

  public boolean persistentStateValid() {
    return persistentStateValid && unsupportedScriptData == null && blueprintInventory.valid();
  }

  @Override
  @ApiStatus.Internal
  public ControllerScriptState scriptState() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Factory controller script state read");
    }

    return controllerScriptState;
  }

  @Override
  @ApiStatus.Internal
  public boolean scriptStateAvailable() {
    return persistentStateValid();
  }

  @Override
  @ApiStatus.Internal
  public boolean scriptStateEditingAllowed() {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Factory controller script state edited");

    return persistentStateValid()
        && !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockEntity(worldPosition) == this
        && matchesStructureBlock(server.getBlockState(worldPosition))
        && runState.startingThreads().isEmpty()
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !RecipeLifecyclePublisher.isDispatching()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  @Override
  @ApiStatus.Internal
  public boolean replaceControllerScriptState(ControllerScriptState expected, ControllerScriptState replacement) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(replacement, "replacement");
    if (!scriptStateEditingAllowed()
        || controllerScriptState != expected
        || expected.revision() == Long.MAX_VALUE
        || replacement.revision() != expected.revision() + 1) {
      return false;
    }
    PatternProductionService.invalidate(this);
    controllerScriptState = replacement;
    if (!expected.snapshot().customData().equals(replacement.snapshot().customData())) {
      ControllerVisualDataSync.schedule(this);
    }
    searchCoordinator.invalidate();
    MachineRecipeService.invalidateControllerScriptData(this);
    setChanged();

    return true;
  }

  @Override
  @ApiStatus.Internal
  public boolean clearControllerScriptState() {
    if (!scriptStateEditingAllowed()) {
      return false;
    }
    var previous = controllerScriptState;
    if (previous.isEmpty()) {
      return true;
    }

    return replaceControllerScriptState(previous, previous.cleared());
  }

  /** Client presentation data, independent from saved server script state. */
  @ApiStatus.Internal
  public String clientCustomData() {
    return clientVisualData == null ? "{}" : clientVisualData.customData();
  }

  public ControllerBlueprintInventory blueprintInventory() {
    return blueprintInventory;
  }

  private void checkBlueprintWrite() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Factory blueprint edit");
      if (!runState.startingThreads().isEmpty()
          || UpgradeLifecyclePublisher.isDispatchingAny()
          || RecipeLifecyclePublisher.isDispatching()
          || SmartInterfaceEventPublisher.isDispatching(this)) {
        throw new IllegalStateException("Factory blueprint edit during an active transaction or callback");
      }
    }
  }

  @Override
  public boolean allowsStructureDefinition(MachineDefinition definition) {
    return !definition.requiresBlueprint() || definition.id().equals(blueprintInventory.machine());
  }

  public ControllerMetrics.Snapshot performance() {
    return metrics.snapshot(level);
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public VerificationState verificationState() {
    if (registration != null) {
      return registration.verifier.state();
    }
    return persistentStateValid
        && StructureService.definition(machineId) != null
        && orientationAllowed() ? detachedState : VerificationState.INVALID;
  }

  public VerificationFailure verificationFailure() {
    if (!persistentStateValid) {
      return VerificationFailure.UNSUPPORTED_SCHEMA;
    }
    if (StructureService.definition(machineId) == null) {
      return VerificationFailure.UNKNOWN_DEFINITION;
    }
    if (!orientationAllowed()) {
      return VerificationFailure.ORIENTATION_NOT_ALLOWED;
    }
    return registration == null ? detachedFailure : registration.verifier.failure();
  }

  public boolean canCommitStructure() {
    return level instanceof ServerLevel server
        && patternRunSources.valid()
        && registration != null
        && registration.canCommit(server);
  }

  public StructureService.Registration registration() {
    return registration;
  }

  @Override
  public StructureService.Registration structureRegistration() {
    return registration;
  }

  public void bindMachine(ResourceLocation id) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Binding outside server thread");
    }
    if (getBlockState().getBlock() instanceof FactoryControllerBlock block
        && block.dedicated() != null
        && !block.dedicated().machine().equals(id)
        && !id.equals(blueprintInventory.machine())) {
      throw new IllegalArgumentException("Dedicated factory needs a blueprint to select another machine");
    }
    if (id == null || !persistentStateValid) {
      throw new IllegalArgumentException("Invalid factory machine binding");
    }
    if (UpgradeLifecyclePublisher.isDispatchingAny()
        || !runState.startingThreads().isEmpty()
        || !runState.view().isEmpty() && !id.equals(machineId)) {
      throw new IllegalStateException("Factory binding during an active callback or startup");
    }
    machineId = id;
    ControllerVisualDataSync.schedule(this);
    upgrades.clearDiscovery();
    runCoordinator.invalidateOutput();
    searchCoordinator.invalidate();
    setChanged();
    StructureService.register(this);
  }

  @Override
  public void attach(StructureService.Registration registration) {
    PatternProductionService.invalidate(this);
    runtimeUpgrades.clear();
    upgrades.clearDiscovery();
    runCoordinator.invalidateOutput();
    this.registration = registration;
    detachedState = VerificationState.PENDING;
    detachedFailure = VerificationFailure.UNVERIFIED;
    refreshAppearanceState();
  }

  @Override
  public void failPreparation(VerificationFailure failure) {
    upgrades.clearDiscovery();
    runCoordinator.invalidateOutput();
    registration = null;
    detachedState = VerificationState.INVALID;
    detachedFailure = failure;
    refreshAppearanceState();
  }

  @ApiStatus.Internal
  public void refreshAppearanceState() {
    if (!(level instanceof ServerLevel server) || !(getBlockState().getBlock() instanceof FactoryControllerBlock)) {
      return;
    }
    ServerThreadGuard.require(server, "Factory appearance refreshed");
    if (isRemoved() || !server.hasChunkAt(worldPosition) || server.getBlockEntity(worldPosition) != this) {
      return;
    }
    var definition = StructureService.definition(machineId);
    var verification = verificationState();
    boolean formed = verification == VerificationState.CONFIRMED && definition != null;
    boolean hidden = formed && definition.hidesComponent(MachineComponentType.CONTROLLER);
    var appearance = ControllerAppearance
        .factory(verification, threadConfiguration.scheduler().channels(), runState.view(), upgrades.valid());
    ControllerAppearance.sync(this, formed, hidden, appearance);
  }

  @Override
  public boolean revalidateStructure(StructureService.Registration registration) {
    return level instanceof ServerLevel server
        && this.registration == registration
        && registration != null
        && registration.canCommit(server)
        && registration.revalidateModifiers(server);
  }

  @ApiStatus.Internal
  public FactoryThreadConfiguration threadConfiguration() {
    return threadConfiguration;
  }

  @ApiStatus.Internal
  public FactoryRunCoordinator runCoordinator() {
    return runCoordinator;
  }

  @ApiStatus.Internal
  public FactoryRunState runState() {
    return runState;
  }

  public Optional<FrozenRecipeRun> run(String threadId) {
    var set = runState.view().get(threadId);
    return set == null || set.runs().isEmpty() ? Optional.empty() : Optional.of(set.runs().get(0));
  }

  public FactoryRunSet runSet(String threadId) {
    return runState.runSet(threadId);
  }

  public int effectiveParallelLimit(String threadId) {
    var thread = threadConfiguration.scheduler().channel(threadId);
    if (thread == null) {
      return 1;
    }
    var definition = StructureService.definition(machineId);
    if (definition != null && !definition.parallelizable()) {
      return 1;
    }
    if (!(level instanceof ServerLevel server) || registration == null || !registration.canCommit(server)) {
      return thread.upgradeSnapshot().parallelLimit();
    }
    int hardware = ParallelCapacity.capture(registration, server).limit();
    long usedHardware = 0;
    long usedExtra = 0;
    for (var entry : runState.view().entrySet()) {
      if (!entry.getValue().runs().isEmpty()) {
        int paidBase = entry.getValue().runs().getFirst().parallelBase();
        usedHardware += Math.max(0L, (long) entry.getValue().runs().size() - paidBase);
      }
      usedExtra += Math.max(0, entry.getValue().runs().size() - 1);
    }
    long available = Math.max(0L, hardware - 1L - usedHardware);
    long requested = Math.min(Integer.MAX_VALUE, thread.upgradeSnapshot().parallelLimit() + available);
    long global = Math.max(1L, registration.pattern.definition.maxParallelism() - usedExtra);

    return (int) Math.max(1, Math.min(requested, global));
  }

  public void serverTick() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      return;
    }
    RecipeCommandService.schedule(this);
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return;
    }
    try {
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_PRE_TICK);
      tickController(server);
    } finally {
      refreshAppearanceState();
      RecipeLifecyclePublisher.machineTick(this);
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_POST_TICK);
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_TICK);
    }
  }

  private void tickController(ServerLevel server) {
    if (getBlockState().getBlock() instanceof FactoryControllerBlock block
        && persistentStateValid()
        && runState.view().isEmpty()
        && runState.startingThreads().isEmpty()
        && (registration == null || registration.verifier.state() == VerificationState.INVALID)) {
      var found = genericDiscovery.advance(
          server,
          worldPosition,
          structureOrientation(server),
          blueprintInventory.machine(),
          block.dedicated() == null ? null : block.dedicated().machine(),
          true);
      if (found != null && !found.equals(machineId)) {
        threadConfiguration.configureMachine(found);
      }
    }
    StructureService.register(this);
    execution.advance(server);
  }

  @Override
  public UpgradeStartState upgradeStartState(String threadId) {
    requireUpgradeThread();
    var thread = threadConfiguration.scheduler().channel(threadId);

    return thread == null ? null : new UpgradeStartState(
        thread.threadId(),
        thread.machineId(),
        thread.enabled(),
        thread.lockedRecipe(),
        thread.upgradeSnapshot());
  }

  @Override
  public boolean upgradeRunIdle(String threadId) {
    requireUpgradeThread();

    return runSet(threadId).runs().isEmpty();
  }

  @Override
  public List<FrozenRecipeRun> upgradeRuns(String threadId) {
    requireUpgradeThread();
    var thread = threadConfiguration.scheduler().channel(threadId);

    return thread == null || !thread.enabled() ? List.of() : runSet(threadId).runs();
  }

  @Override
  public UpgradeRuntimeWork upgradeRuntimeWork() {
    return runtimeUpgrades;
  }

  private boolean orientationAllowed() {
    var definition = StructureService.definition(machineId);
    return level instanceof ServerLevel server && definition != null && definition.allows(structureOrientation(server));
  }

  @Override
  public BlockPos structurePosition() {
    return worldPosition;
  }

  @Override
  public Level structureLevel() {
    return level;
  }

  @Override
  public ResourceLocation structureMachineId() {
    return machineId;
  }

  @Override
  public boolean structureSchemaSupported() {
    return persistentStateValid();
  }

  @Override
  public boolean structureRemoved() {
    return isRemoved();
  }

  @Override
  public MachineOrientation structureOrientation(ServerLevel server) {
    BlockState state = server.getBlockState(worldPosition);
    if (state.hasProperty(FactoryControllerBlock.FACING) && state.hasProperty(FactoryControllerBlock.ROLL)) {
      return MachineOrientation
          .of(state.getValue(FactoryControllerBlock.FACING), state.getValue(FactoryControllerBlock.ROLL));
    }
    return MachineOrientation.of(Direction.NORTH, 0);
  }

  @Override
  public boolean matchesStructureBlock(BlockState state) {
    return state.getBlock() instanceof FactoryControllerBlock && state.is(getBlockState().getBlock());
  }

  @Override
  public Component getDisplayName() {
    if (verificationState() == VerificationState.CONFIRMED) {
      var definition = StructureService.definition(machineId);
      if (definition != null) {
        return Component.translatable(definition.translationKey());
      }
    }
    if (getBlockState().getBlock() instanceof FactoryControllerBlock block && block.dedicated() != null) {
      return block.asItem().getName(block.asItem().getDefaultInstance());
    }
    return Component.translatable("block.mmcece.factory_controller");
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return new FactoryControllerMenu(id, inventory, this);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    super.saveAdditional(tag, registries);
    ownership.save(tag);
    tag.put(ControllerCommandState.NBT_KEY, commandState.save(false));
    tag.put(PatternRunSources.NBT_KEY, patternRunSources.save());
    tag.put("controller_blueprint", blueprintInventory.save(registries));
    tag.put(
        ControllerScriptState.NBT_KEY,
        unsupportedScriptData == null ? controllerScriptState.save() : unsupportedScriptData.copy());
    if (!persistentStateValid && opaqueFactoryState != null) {
      tag.merge(opaqueFactoryState);
      return;
    }
    tag.putInt("mmcece_factory_schema", FACTORY_SCHEMA);
    tag.putBoolean("upgrade_sources_managed", upgrades.managed());
    threadConfiguration.save(tag);
    tag.putString("machine", machineId.toString());
    runState.save(tag);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Factory load");
      if (UpgradeLifecyclePublisher.isDispatchingAny()
          || SmartInterfaceEventPublisher.isDispatching()
          || !runState.startingThreads().isEmpty()) {
        throw new IllegalStateException("Factory load during an active upgrade callback or startup");
      }
    }
    runtimeUpgrades.clear();
    PatternProductionService.invalidate(this);
    super.loadAdditional(tag, registries);
    ownership = ControllerOwnershipState.load(tag);
    commandState = ControllerCommandState.load(tag.get(ControllerCommandState.NBT_KEY));
    patternRunSources = PatternRunSources.load(tag.get(PatternRunSources.NBT_KEY));
    controllerScriptState = ControllerScriptState.empty();
    unsupportedScriptData = null;
    try {
      controllerScriptState = ControllerScriptState.load(tag.get(ControllerScriptState.NBT_KEY));
    } catch (RuntimeException invalidScriptData) {
      unsupportedScriptData = tag.get(ControllerScriptState.NBT_KEY).copy();
    }
    ControllerVisualDataSync.schedule(this);
    genericDiscovery.clear();
    blueprintInventory.load(registries, tag.get("controller_blueprint"));
    upgrades.clearDiscovery();
    runCoordinator.invalidateOutput();
    persistentStateValid = false;
    threadConfiguration.resetState();
    opaqueFactoryState = null;
    upgrades.resetState();
    searchCoordinator.invalidate();
    runState.clearRuns();
    runState.clearStarts();
    if (!tag.contains("mmcece_factory_schema", Tag.TAG_INT)
        || tag.getInt("mmcece_factory_schema") != FACTORY_SCHEMA
        || !tag.contains("upgrade_sources_managed", Tag.TAG_BYTE)
        || tag.getByte("upgrade_sources_managed") < 0
        || tag.getByte("upgrade_sources_managed") > 1
        || !FactoryThreadConfiguration.hasSavedFields(tag)
        || !tag.contains("machine", Tag.TAG_STRING)
        || !tag.contains("runs", Tag.TAG_LIST)) {
      threadConfiguration.resetState();
      preserveOpaqueFactoryState(tag);
      return;
    }
    try {
      upgrades.restoreManaged(tag.getBoolean("upgrade_sources_managed"));
      machineId = ResourceLocation.parse(tag.getString("machine"));
      threadConfiguration.load(tag);
      runState.load(tag, registries);
      persistentStateValid = true;
    } catch (RuntimeException exception) {
      threadConfiguration.resetState();
      runState.clearRuns();
      preserveOpaqueFactoryState(tag);
    }
  }

  private void preserveOpaqueFactoryState(CompoundTag tag) {
    opaqueFactoryState = new CompoundTag();
    for (String key : List.of(
        "mmcece_factory_schema",
        "upgrade_sources_managed",
        "threads_initialized",
        "configuration_revision",
        "machine",
        "scheduler_cursor",
        "scheduler_round",
        "scheduler_waiting",
        "threads",
        "runs")) {
      if (tag.contains(key)) {
        opaqueFactoryState.put(key, tag.get(key).copy());
      }
    }
  }

  /**
   * Carry paid state and command markers, never a deferred world command outbox.
   */
  public CompoundTag portableData(HolderLookup.Provider registries) {
    var root = saveWithoutMetadata(registries);
    root.put(ControllerCommandState.NBT_KEY, commandState.save(true));
    root.put(PatternRunSources.NBT_KEY, patternRunSources.save());

    return root;
  }
}
