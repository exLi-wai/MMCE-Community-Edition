package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.machine.controller.ControllerEnergyTransfer;
import com.lw.modularmachine.machine.controller.ControllerExecutionCoordinator;
import com.lw.modularmachine.api.recipe.RecipeRunStatus;
import com.lw.modularmachine.machine.upgrade.UpgradeLifecyclePublisher;
import com.lw.modularmachine.machine.controller.ControllerInspection;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.controller.GenericControllerDiscovery;
import com.lw.modularmachine.machine.controller.ControllerRecipeSearch;
import com.lw.modularmachine.machine.controller.ControllerRunState;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.controller.ControllerScriptState;
import com.lw.modularmachine.machine.controller.ControllerScriptStateOwner;
import com.lw.modularmachine.machine.controller.ControllerVisualData;
import com.lw.modularmachine.machine.controller.ControllerVisualDataSync;
import com.lw.modularmachine.machine.controller.ControllerOwnershipState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandSubject;
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.upgrade.ControllerUpgrades;
import com.lw.modularmachine.machine.upgrade.UpgradeRestrictions;
import com.lw.modularmachine.machine.upgrade.UpgradeRuntimeWork;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.upgrade.UpgradeBusOwner;
import com.lw.modularmachine.machine.upgrade.UpgradeStartState;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.machine.recipe.FrozenRecipeRun;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.PatternRunSources;
import com.lw.modularmachine.machine.recipe.PatternProductionService;
import com.lw.modularmachine.machine.recipe.TickOutputWork;
import com.lw.modularmachine.machine.recipe.RecipeRunEngine;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.controller.MachineEventPublisher;
import com.lw.modularmachine.api.controller.MachineEvents;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.FullScanReference;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.machine.controller.ControllerBlueprintInventory;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

public final class MachineControllerBlockEntity extends BlockEntity implements MenuProvider, UpgradeBusOwner, ControllerScriptStateOwner, ControllerCommandSubject {
  private final GenericControllerDiscovery genericDiscovery = new GenericControllerDiscovery();
  private ResourceLocation machineId = ControllerPlacementData.UNBOUND_MACHINE;
  private StructureService.Registration registration;
  private final ControllerEnergyTransfer energyTransfer = new ControllerEnergyTransfer(new EnergyTransferHost());
  private long conservativeChecks;
  private StructureService.Registration conservativeRegistration;
  private long conservativeRevision = Long.MIN_VALUE;
  private boolean conservativeValidated;
  private MachinePayloads.ControllerStatusPayload lastObserverStatus;
  private final Set<UUID> observerRecipients = new HashSet<>();
  private Tag unsupportedData;
  private ControllerScriptState controllerScriptState = ControllerScriptState.empty();
  private ControllerVisualData clientVisualData;
  private Tag unsupportedScriptData;
  private ControllerOwnershipState ownership = ControllerOwnershipState.empty();
  private ControllerCommandState commandState = new ControllerCommandState();
  private PatternRunSources patternRunSources = new PatternRunSources();
  private final ControllerRunState runState = new ControllerRunState(new RunStateHost());
  /**
   * Blueprint selection changes the formed machine, never the registered block
   * identity.
   */
  private final ControllerBlueprintInventory blueprintInventory = new ControllerBlueprintInventory(
      this::checkBlueprintWrite,
      this::blueprintChanged);
  private final ControllerUpgrades upgrades = new ControllerUpgrades(this);
  private final UpgradeRuntimeWork runtimeUpgrades = new UpgradeRuntimeWork(this);
  private final ControllerMetrics metrics = new ControllerMetrics();
  private final ControllerRecipeSearch recipeSearch = new ControllerRecipeSearch(new RecipeSearchHost(), metrics);
  private final ControllerExecutionCoordinator execution = new ControllerExecutionCoordinator(
      runState,
      recipeSearch,
      energyTransfer,
      metrics,
      new ExecutionHost());
  private VerificationState detachedState = VerificationState.PENDING;
  private VerificationFailure detachedFailure = VerificationFailure.UNVERIFIED;

  private final class EnergyTransferHost implements ControllerEnergyTransfer.Host {
    @Override
    public StructureService.Registration registration() {
      return registration;
    }
    @Override
    public boolean canCommitProduction() {
      return MachineControllerBlockEntity.this.canCommitProduction();
    }
    @Override
    public boolean revalidateProduction(StructureService.Registration registration) {
      return MachineControllerBlockEntity.this.revalidateProduction(registration);
    }
    @Override
    public void markChanged() {
      setChanged();
    }
  }

  private final class ExecutionHost implements ControllerExecutionCoordinator.Host {
    @Override
    public RecipeRunStatus advanceRun(RecipeRunEngine engine, ServerLevel server) {
      return engine.tick(MachineControllerBlockEntity.this, server);
    }
    @Override
    public void broadcastObserverStatus(ServerLevel server) {
      MachineControllerBlockEntity.this.broadcastObserverStatus(server);
    }
    @Override
    public boolean canCommitProduction() {
      return MachineControllerBlockEntity.this.canCommitProduction();
    }
    @Override
    public StructureService.Registration registration() {
      return registration;
    }
  }

  private final class RunStateHost implements ControllerRunState.Host {
    @Override
    public boolean onServerThread() {
      return level instanceof ServerLevel server && server.getServer().isSameThread();
    }
    @Override
    public boolean current() {
      return schemaSupported()
          && !isRemoved()
          && level.hasChunkAt(worldPosition)
          && level.getBlockEntity(worldPosition) == MachineControllerBlockEntity.this;
    }
    @Override
    public ResourceLocation machineId() {
      return machineId;
    }
    @Override
    public void requireLoadAllowed() {
      if (level instanceof ServerLevel server && !server.getServer().isSameThread()) {
        throw new IllegalStateException("Controller load outside server thread");
      }
      if (runState.busy() || level instanceof ServerLevel && SmartInterfaceEventPublisher.isDispatching()) {
        throw new IllegalStateException("Cannot reload a recipe transaction");
      }
    }
  }

  private final class RecipeSearchHost implements ControllerRecipeSearch.Host {
    @Override
    public RecipeCatalog catalog() {
      return MachineRecipeService.catalogFor(MachineControllerBlockEntity.this).orElse(null);
    }
    @Override
    public RecipeSearch beginSearch(RecipeCatalog catalog) {
      return RecipeSearch.begin(MachineControllerBlockEntity.this, catalog).orElse(null);
    }
    @Override
    public void started() {
      execution.started();
    }
    @Override
    public BlockPos position() {
      return worldPosition;
    }
  }

  public MachineControllerBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.CONTROLLER_ENTITY.get(), pos, state);
    if (state.getBlock() instanceof MachineControllerBlock block && block.dedicated() != null) {
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
      recipeSearch.wakeRetry(server.getGameTime());
      setChanged();
    }
  }

  @Override
  public void wakeRecipeSearch() {
    if (level instanceof ServerLevel server) {
      recipeSearch.wakeIdleRetry(server.getGameTime());
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
        && schemaSupported()
        && !recipeStartInProgress()
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
    if (level instanceof ServerLevel && runState.current() == null && blueprintMachineId() != null) {
      machineId = blueprintMachineId();
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
    TickOutputWork.forget(this);
    ControllerVisualDataSync.remove(this);
    clientVisualData = null;
    PatternProductionService.invalidate(this);
    RecipeCommandService.unschedule(this);
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    upgrades.invalidate();
    execution.invalidate();
    observerRecipients.clear();
    lastObserverStatus = null;
    StructureService.unregister(this);

  }

  public void attach(StructureService.Registration registration) {
    PatternProductionService.invalidate(this);
    runtimeUpgrades.clear();
    upgrades.invalidate();
    execution.invalidate();
    recipeSearch.structureAttached();
    this.registration = registration;
    conservativeRegistration = null;
    conservativeRevision = Long.MIN_VALUE;
    conservativeValidated = false;
    energyTransfer.detach();
    detachedState = VerificationState.PENDING;
    detachedFailure = VerificationFailure.UNVERIFIED;
    refreshAppearanceState();
  }

  public void failPreparation(VerificationFailure failure) {
    attach(null);
    detachedState = VerificationState.INVALID;
    detachedFailure = failure;
    refreshAppearanceState();
  }

  @ApiStatus.Internal
  public void refreshAppearanceState() {
    if (!(level instanceof ServerLevel server) || !(getBlockState().getBlock() instanceof MachineControllerBlock)) {
      return;
    }
    ServerThreadGuard.require(server, "Controller appearance refreshed");
    if (isRemoved() || !server.hasChunkAt(worldPosition) || server.getBlockEntity(worldPosition) != this) {
      return;
    }
    var definition = StructureService.definition(machineId);
    var verification = verificationState();
    boolean formed = verification == VerificationState.CONFIRMED && definition != null;
    boolean hidden = formed && definition.hidesComponent(MachineComponentType.CONTROLLER);
    var appearance = ControllerAppearance.ordinary(
        verification,
        runState.current() != null,
        recipeRunStatus(),
        server.getDirectSignalTo(worldPosition) != 0,
        energyTransfer.transferred(),
        recipeSearch.idleAppearance());
    ControllerAppearance.sync(this, formed, hidden, appearance);
  }

  public ControllerMetrics.Snapshot performance() {
    return metrics.snapshot(level);
  }

  public ResourceLocation machineId() {
    return machineId;
  }

  public ControllerBlueprintInventory blueprintInventory() {
    return blueprintInventory;
  }

  public ResourceLocation blueprintMachineId() {
    return blueprintInventory.machine();
  }

  private void blueprintChanged() {
    PatternProductionService.invalidate(this);
    setChanged();
    if (level instanceof ServerLevel) {
      boolean formed = registration != null && registration.verifier.state() == VerificationState.CONFIRMED;
      genericDiscovery.clear();
      recipeSearch.invalidate();
      recipeSearch.wakeRetry(level.getGameTime());
      var target = blueprintMachineId();
      if (!formed && runState.current() == null && target != null && !target.equals(machineId)) {
        bindMachine(target);
      } else {
        StructureService.selectionChanged(this);
      }
    }
  }

  private void checkBlueprintWrite() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Controller blueprint edit");
      if (recipeStartInProgress()
          || UpgradeLifecyclePublisher.isDispatchingAny()
          || RecipeLifecyclePublisher.isDispatching()
          || SmartInterfaceEventPublisher.isDispatching(this)) {
        throw new IllegalStateException("Controller blueprint edit during an active transaction or callback");
      }
    }
  }

  @Override
  public boolean allowsStructureDefinition(MachineDefinition definition) {
    return !definition.requiresBlueprint() || definition.id().equals(blueprintMachineId());
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
    return schemaSupported();
  }

  @Override
  public boolean structureRemoved() {
    return isRemoved();
  }

  @Override
  public MachineOrientation structureOrientation(ServerLevel server) {
    return MachineControllerBlock.orientationOf(server.getBlockState(worldPosition));
  }

  @Override
  public boolean matchesStructureBlock(BlockState state) {
    return state.getBlock() instanceof MachineControllerBlock && state.is(getBlockState().getBlock());
  }

  public boolean schemaSupported() {
    return unsupportedData == null && unsupportedScriptData == null && blueprintInventory.valid();
  }

  @Override
  @ApiStatus.Internal
  public ControllerScriptState scriptState() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Controller script state read");
    }

    return controllerScriptState;
  }

  @Override
  @ApiStatus.Internal
  public boolean scriptStateAvailable() {
    return schemaSupported();
  }

  @Override
  @ApiStatus.Internal
  public boolean scriptStateEditingAllowed() {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    ServerThreadGuard.require(server, "Controller script state edited");

    return schemaSupported()
        && !isRemoved()
        && server.hasChunkAt(worldPosition)
        && server.getBlockEntity(worldPosition) == this
        && matchesStructureBlock(server.getBlockState(worldPosition))
        && !recipeStartInProgress()
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
    recipeSearch.invalidate();
    recipeSearch.wakeRetry(level.getGameTime());
    if (runState.current() == null) {
      execution.invalidate();
    }
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

  public VerificationState verificationState() {
    if (registration != null) {
      return registration.verifier.state();
    }
    return schemaSupported()
        && !isRemoved()
        && StructureService.definition(machineId) != null
        && orientationAllowed() ? detachedState : VerificationState.INVALID;
  }

  public VerificationFailure verificationFailure() {
    if (!schemaSupported()) {
      return VerificationFailure.UNSUPPORTED_SCHEMA;
    }
    if (StructureService.definition(machineId) != null && !orientationAllowed()) {
      return VerificationFailure.ORIENTATION_NOT_ALLOWED;
    }
    return registration == null ? (StructureService.definition(
        machineId) == null ? VerificationFailure.UNKNOWN_DEFINITION : detachedFailure) : registration.verifier
            .failure();
  }

  private boolean orientationAllowed() {
    var definition = StructureService.definition(machineId);
    BlockState state = getBlockState();
    if (level instanceof ServerLevel server) {
      if (!server.getServer().isSameThread()) {
        throw new IllegalStateException("Controller status read outside server thread");
      }
      if (server.hasChunkAt(worldPosition)) {
        state = server.getBlockState(worldPosition);
      }
    }
    return definition != null
        && state.hasProperty(MachineControllerBlock.FACING)
        && state.hasProperty(MachineControllerBlock.ROLL)
        && definition.allows(MachineControllerBlock.orientationOf(state));
  }

  public boolean canCommitStructure() {
    return level instanceof ServerLevel server
        && registration != null
        && schemaSupported()
        && !isRemoved()
        && registration.canCommit(server);
  }

  public boolean canCommitProduction() {
    return level instanceof ServerLevel server
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && canCommitStructure()
        && patternRunSources.valid()
        && server.getDirectSignalTo(worldPosition) == 0;
  }

  public void refreshUpgradeSources() {
    upgrades.refresh();
  }

  public UpgradeSnapshot upgradeSnapshot() {
    return upgrades.snapshot();
  }

  public boolean upgradeSnapshotCurrent(UpgradeSnapshot expected) {
    return upgrades.current(expected);
  }

  @Override
  public UpgradeRestrictions.Result upgradeRestrictionStatus() {
    return upgrades.restrictions();
  }

  @Override
  public boolean containsUpgradeBusPart(BlockPos position) {
    return upgrades.contains(position);
  }

  @Override
  public boolean publishUpgradeBus(UpgradeBusBlockEntity bus) {
    return bus.hasOwnerBinding(this) && upgrades.contains(bus.getBlockPos());
  }

  @Override
  public void withdrawUpgradeBus(UpgradeBusBlockEntity bus) {
    upgrades.withdraw();
  }

  @Override
  public List<UpgradeBusBlockEntity> upgradeCallbackBuses() {
    return upgrades.buses();
  }

  @Override
  public UpgradeStartState upgradeStartState(String threadId) {
    if (!"main".equals(threadId)) {
      return null;
    }

    return new UpgradeStartState("main", machineId, upgrades.current(), null, upgrades.snapshot());
  }

  @Override
  public boolean upgradeRunIdle(String threadId) {
    return "main".equals(threadId) && runState.current() == null;
  }

  @Override
  public List<BlockPos> upgradeBusPositions() {
    return upgrades.positions();
  }

  @Override
  public List<FrozenRecipeRun> upgradeRuns(String threadId) {
    if (!"main".equals(threadId) || runState.current() == null || !canCommitProduction()) {
      return List.of();
    }

    return List.of(runState.current());
  }

  @Override
  public UpgradeRuntimeWork upgradeRuntimeWork() {
    return runtimeUpgrades;
  }

  public long successfulTransfers() {
    return energyTransfer.successfulTransfers();
  }

  public long bindingRebuilds() {
    return energyTransfer.bindingRebuilds();
  }

  public long conservativeChecks() {
    return conservativeChecks;
  }

  public StructureService.Registration registration() {
    return registration;
  }

  @Override
  public StructureService.Registration structureRegistration() {
    return registration;
  }

  @ApiStatus.Internal
  public ControllerRunState runState() {
    return runState;
  }

  public Optional<FrozenRecipeRun> runningRecipe() {
    return Optional.ofNullable(runState.current());
  }

  public boolean recipeStartInProgress() {
    return runState.busy();
  }

  public RecipeRunStatus recipeRunStatus() {
    if (runState.current() == null && level instanceof ServerLevel && canCommitStructure() && !upgrades.current()) {
      return upgrades.restrictions().valid() ? RecipeRunStatus.UPGRADE_ERROR : RecipeRunStatus.UPGRADE_CONFLICT;
    }
    if (runState.current() == null && recipeSearch.idleStatus() != RecipeRunStatus.IDLE) {
      return recipeSearch.idleStatus();
    }
    return execution.status();
  }

  public int parallelLimit() {
    if (!(level instanceof ServerLevel server) || registration == null || !registration.canCommit(server)) {
      return 1;
    }
    int hardware = ParallelCapacity.capture(registration, server).limit();
    long extra = upgrades.current() ? upgrades.snapshot().parallelLimit() - 1L : 0L;

    return (int) Math
        .min(registration.pattern.definition.maxParallelism(), Math.min(Integer.MAX_VALUE, hardware + extra));
  }

  @Override
  public Component getDisplayName() {
    if (verificationState() == VerificationState.CONFIRMED) {
      var definition = StructureService.definition(machineId);
      if (definition != null) {
        return Component.translatable(definition.translationKey());
      }
    }
    if (getBlockState().getBlock() instanceof MachineControllerBlock block && block.dedicated() != null) {
      return block.asItem().getName(block.asItem().getDefaultInstance());
    }
    return Component.translatable("block.mmcece.machine_controller");
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return new MachineControllerMenu(id, inventory, this);
  }

  public void bindMachine(ResourceLocation id) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Binding outside server thread");
    }
    Objects.requireNonNull(id, "machine id");
    if (getBlockState().getBlock() instanceof MachineControllerBlock block
        && block.dedicated() != null
        && !block.dedicated().machine().equals(id)
        && !id.equals(blueprintMachineId())) {
      throw new IllegalArgumentException("Dedicated controller needs a blueprint to select another machine");
    }
    if (recipeStartInProgress() || runState.current() != null && !id.equals(machineId)) {
      throw new IllegalStateException("Cannot rebind a paid or starting recipe");
    }
    machineId = id;
    ControllerVisualDataSync.schedule(this);
    setChanged();
    StructureService.register(this);
  }

  public void serverTick() {
    if (!(level instanceof ServerLevel server)) {
      return;
    }
    if (!server.getServer().isSameThread()) {
      throw new IllegalStateException("Controller tick outside server thread");
    }
    TickOutputWork.beginTick(this, server.getGameTime(), () -> runningRecipe().stream().map(FrozenRecipeRun::runId));
    RecipeCommandService.schedule(this);
    if (recipeStartInProgress() || RecipeLifecyclePublisher.isDispatching()) {
      return;
    }
    try {
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_PRE_TICK);
      energyTransfer.beginTick();
      tickController(server);
    } finally {
      refreshAppearanceState();
      RecipeLifecyclePublisher.machineTick(this);
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_POST_TICK);
      MachineEventPublisher.tick(this, MachineEvents.Type.MACHINE_TICK);
    }
  }

  private void tickController(ServerLevel server) {
    if (getBlockState().getBlock() instanceof MachineControllerBlock block
        && schemaSupported()
        && runState.current() == null
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && (registration == null || registration.verifier.state() == VerificationState.INVALID)) {
      var found = genericDiscovery.advance(
          server,
          worldPosition,
          structureOrientation(server),
          blueprintMachineId(),
          block.dedicated() == null ? null : block.dedicated().machine(),
          false);
      if (found != null && !found.equals(machineId)) {
        bindMachine(found);
      }
    }
    // O(1) metadata refresh also catches an untracked change to the controller
    // state itself.
    StructureService.register(this);
    refreshUpgradeSources();
    execution.advance(server);
  }

  private void broadcastObserverStatus(ServerLevel server) {
    var nearbyPlayers = new ArrayList<ServerPlayer>();
    var nearby = new HashSet<UUID>();
    for (ServerPlayer player : server.players()) {
      if (player
          .distanceToSqr(worldPosition.getX() + .5, worldPosition.getY() + .5, worldPosition.getZ() + .5) <= 32 * 32) {
        nearbyPlayers.add(player);
        nearby.add(player.getUUID());
      }
    }
    if (nearbyPlayers.isEmpty()) {
      observerRecipients.clear();
      return;
    }
    var snapshot = ControllerInspection.inspectForMenu(this);
    if (snapshot.isEmpty()) {
      return;
    }
    var payload = MachinePayloads.ControllerStatusPayload.from(snapshot.orElseThrow(), performance());
    for (ServerPlayer player : nearbyPlayers) {
      if (!payload.equals(lastObserverStatus) || !observerRecipients.contains(player.getUUID())) {
        PacketDistributor.sendToPlayer(player, payload);
      }
    }
    observerRecipients.retainAll(nearby);
    observerRecipients.addAll(nearby);
    lastObserverStatus = payload;
  }

  /**
   * Shared structural gate; resource-specific callers must recheck their captured
   * ports after callbacks.
   */
  public boolean revalidateProduction(StructureService.Registration ticket) {
    if (!(level instanceof ServerLevel server)) {
      return false;
    }
    if (!server.getServer().isSameThread()) {
      throw new IllegalStateException("Production check outside server thread");
    }
    if (ticket == null || registration != ticket || !canCommitProduction()) {
      return false;
    }
    if (MachineConfig.CONSERVATIVE_COMMIT_CHECK.get()) {
      long revision = ticket.verifier.revision();
      if (conservativeRegistration == ticket && conservativeRevision == revision) {
        return conservativeValidated && registration == ticket && canCommitProduction();
      }
      var reference = ticket.pattern.modifierReference(server);
      var full = ticket.pattern.fullScan(server, reference);
      conservativeChecks = addTelemetry(conservativeChecks, full.checks());
      if (full.state() != VerificationState.CONFIRMED) {
        ticket.rejectProof(full.failure(), full.failedIndex(), server.getGameTime());
        conservativeRegistration = ticket;
        conservativeRevision = ticket.verifier.revision();
        conservativeValidated = false;
        return false;
      }
      if (!ticket.verifyModifierReference(server, reference)) {
        conservativeRegistration = ticket;
        conservativeRevision = ticket.verifier.revision();
        conservativeValidated = false;
        return false;
      }
      conservativeRegistration = ticket;
      conservativeRevision = ticket.verifier.revision();
      conservativeValidated = true;
    }
    return registration == ticket && canCommitProduction();
  }

  @Override
  public boolean revalidateStructure(StructureService.Registration registration) {
    return revalidateProduction(registration);
  }

  /**
   * Statistics must not throw after a successful resource transaction; this is
   * never used for revisions.
   */
  private static long addTelemetry(long value, long delta) {
    return Long.MAX_VALUE - value < delta ? Long.MAX_VALUE : value + delta;
  }

  public FullScanReference.Result fullScan() {
    if (!(level instanceof ServerLevel server) || registration == null) {
      throw new IllegalStateException("Unbound controller");
    }
    var ticket = registration;
    return ticket.pattern.fullScan(server);
  }

  public Component statusMessage() {
    String detail = "";
    if (registration != null && registration.verifier.failurePosition() >= 0) {
      detail = registration.pattern.position(registration.verifier.failurePosition()).toShortString();
    }
    return Component.translatable(
        "message.mmcece.controller_status",
        machineId.toString(),
        verificationState().name(),
        verificationFailure().name(),
        detail,
        energyTransfer.successfulTransfers(),
        energyTransfer.bindingRebuilds());
  }

  public String recipeInterfaceFailure() {
    return runningRecipe().isEmpty() ? recipeSearch.interfaceFailure() : "";
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (recipeStartInProgress()) {
      throw new IllegalStateException("Cannot save an unresolved recipe transaction");
    }
    super.saveAdditional(tag, registries);
    ownership.save(tag);
    tag.put(ControllerCommandState.NBT_KEY, commandState.save(false));
    tag.put(PatternRunSources.NBT_KEY, patternRunSources.save());
    tag.put("controller_blueprint", blueprintInventory.save(registries));
    tag.put(
        ControllerScriptState.NBT_KEY,
        unsupportedScriptData == null ? controllerScriptState.save() : unsupportedScriptData.copy());
    if (unsupportedData != null) {
      tag.put("mmcece", unsupportedData.copy());
      return;
    }
    CompoundTag data = new CompoundTag();
    data.putInt("schema", runState.current() == null ? 1 : 2);
    data.putString("machine", machineId.toString());
    data.putLong("successful_transfers", energyTransfer.successfulTransfers());
    if (runState.current() != null) {
      data.put("run", runState.current().save());
    }
    tag.put("mmcece", data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server && !server.getServer().isSameThread()) {
      throw new IllegalStateException("Controller load outside server thread");
    }
    if (recipeStartInProgress() || level instanceof ServerLevel && SmartInterfaceEventPublisher.isDispatching()) {
      throw new IllegalStateException("Cannot reload a recipe transaction");
    }
    runtimeUpgrades.clear();
    upgrades.invalidate();
    execution.invalidate();
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
    recipeSearch.invalidate();
    CompoundTag data = tag.getCompound("mmcece");
    var parsed = ControllerPlacementData.fromTag(tag);
    if (parsed.opaque()) {
      unsupportedData = tag.get("mmcece").copy();
      runState.load(null, registries);
    } else {
      unsupportedData = null;
      machineId = parsed.machineId();
      energyTransfer.restoreTransfers(data.getLong("successful_transfers"));
      try {
        runState.load(data, registries);
      } catch (RuntimeException invalidRun) {
        unsupportedData = data.copy();
        runState.load(null, registries);
      }
    }
    if (level instanceof ServerLevel) {
      StructureService.register(this);
    }
  }

  /**
   * Explicit paid-run whitelist; counters and transient production/structure
   * proofs are never portable.
   */
  public CompoundTag portableData(HolderLookup.Provider registries) {
    CompoundTag root = new CompoundTag();
    ownership.save(root);
    root.put(ControllerCommandState.NBT_KEY, commandState.save(true));
    root.put(PatternRunSources.NBT_KEY, patternRunSources.save());
    root.put("controller_blueprint", blueprintInventory.save(registries));
    root.put(
        ControllerScriptState.NBT_KEY,
        unsupportedScriptData == null ? controllerScriptState.save() : unsupportedScriptData.copy());
    if (unsupportedData != null) {
      root.put("mmcece", unsupportedData.copy());
    } else {
      CompoundTag data = new CompoundTag();
      data.putInt("schema", runState.current() == null ? 1 : 2);
      data.putString("machine", machineId.toString());
      if (runState.current() != null) {
        data.put("run", runState.current().save());
      }
      root.put("mmcece", data);
    }
    return root;
  }
}
