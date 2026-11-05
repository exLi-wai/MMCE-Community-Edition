package com.lw.modularmachine.blockentity;

import com.lw.modularmachine.ModularMachine;
import com.lw.modularmachine.api.MachineControllerApi;
import com.lw.modularmachine.block.MachineControllerBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.controller.GenericControllerDiscovery;
import com.lw.modularmachine.machine.factory.ControllerUpgrades;
import com.lw.modularmachine.machine.factory.FactoryUpgradeEvents;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRuntimeWork;
import com.lw.modularmachine.machine.factory.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.factory.UpgradeBusOwner;
import com.lw.modularmachine.machine.factory.UpgradeStartState;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.machine.orientation.MachineOrientation;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.machine.recipe.AtomicRecipeStarter.Result;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.ItemRecipePlan;
import com.lw.modularmachine.machine.recipe.ItemRecipeSearch;
import com.lw.modularmachine.machine.recipe.ItemRunEngine;
import com.lw.modularmachine.machine.recipe.RecipeLifecycleEvents;
import com.lw.modularmachine.machine.recipe.RecipePlanner.Status;
import com.lw.modularmachine.machine.smart.SmartInterfaceEvents;
import com.lw.modularmachine.machine.structure.FullScanReference;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.menu.MachineControllerMenu;
import com.lw.modularmachine.network.MachinePayloads;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.ControllerBlueprintInventory;
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

public final class MachineControllerBlockEntity extends BlockEntity implements MenuProvider, UpgradeBusOwner {
  private static final int RECIPE_RETRY_INITIAL = 5;
  private static final int RECIPE_RETRY_MAX = 80;
  private final GenericControllerDiscovery genericDiscovery = new GenericControllerDiscovery();
  private ResourceLocation machineId = ControllerPlacementData.UNBOUND_MACHINE;
  private StructureService.Registration registration;
  private MachinePortBlockEntity input;
  private MachinePortBlockEntity output;
  private long successfulTransfers;
  private long bindingRebuilds;
  private long conservativeChecks;
  private StructureService.Registration conservativeRegistration;
  private long conservativeRevision = Long.MIN_VALUE;
  private boolean conservativeValidated;
  private MachinePayloads.ControllerStatusPayload lastObserverStatus;
  private final Set<UUID> observerRecipients = new HashSet<>();
  private Tag unsupportedData;
  private FrozenItemRun runningRecipe;
  private RunStartLease recipeStart;
  private RunUpdateLease recipeUpdate;
  private final ItemRunEngine recipeEngine = new ItemRunEngine();
  /**
   * Blueprint selection changes the formed machine, never the registered block
   * identity.
   */
  private final ControllerBlueprintInventory blueprintInventory = new ControllerBlueprintInventory(
      this::checkBlueprintWrite,
      this::blueprintChanged);
  private final ControllerUpgrades upgrades = new ControllerUpgrades(this);
  private final FactoryUpgradeRuntimeWork runtimeUpgrades = new FactoryUpgradeRuntimeWork(this);
  private ItemRecipeSearch recipeSearch;
  private ItemRecipePlan preparedRecipe;
  private long recipeRetryAfter;
  private int recipeRetryInterval = RECIPE_RETRY_INITIAL;
  private String lastRecipeFailure;
  private VerificationState detachedState = VerificationState.PENDING;
  private VerificationFailure detachedFailure = VerificationFailure.UNVERIFIED;

  public MachineControllerBlockEntity(BlockPos pos, BlockState state) {
    super(MachineRegistries.CONTROLLER_ENTITY.get(), pos, state);
    if (state.getBlock() instanceof MachineControllerBlock block && block.dedicated() != null) {
      machineId = block.dedicated().machine();
    }
  }

  @Override
  public void onLoad() {
    super.onLoad();
    if (level instanceof ServerLevel && runningRecipe == null && blueprintMachineId() != null) {
      machineId = blueprintMachineId();
    }
    StructureService.register(this);
  }

  @Override
  public void setRemoved() {
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    upgrades.invalidate();
    recipeEngine.invalidate();
    observerRecipients.clear();
    lastObserverStatus = null;
    StructureService.unregister(this);
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    upgrades.invalidate();
    recipeEngine.invalidate();
    observerRecipients.clear();
    lastObserverStatus = null;
    StructureService.unregister(this);
    super.onChunkUnloaded();
  }

  public void attach(StructureService.Registration registration) {
    runtimeUpgrades.clear();
    upgrades.invalidate();
    recipeEngine.invalidate();
    recipeSearch = null;
    preparedRecipe = null;
    resetRecipeRetry(0L);
    this.registration = registration;
    conservativeRegistration = null;
    conservativeRevision = Long.MIN_VALUE;
    conservativeValidated = false;
    input = null;
    output = null;
    detachedState = VerificationState.PENDING;
    detachedFailure = VerificationFailure.UNVERIFIED;
  }

  public void failPreparation(VerificationFailure failure) {
    attach(null);
    detachedState = VerificationState.INVALID;
    detachedFailure = failure;
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
    setChanged();
    if (level instanceof ServerLevel) {
      boolean formed = registration != null && registration.verifier.state() == VerificationState.CONFIRMED;
      genericDiscovery.clear();
      recipeSearch = null;
      preparedRecipe = null;
      resetRecipeRetry(level.getGameTime());
      var target = blueprintMachineId();
      if (!formed && runningRecipe == null && target != null && !target.equals(machineId)) {
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
          || FactoryUpgradeEvents.isDispatchingAny()
          || RecipeLifecycleEvents.isDispatching()
          || SmartInterfaceEvents.isDispatching(this)) {
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
    return unsupportedData == null && blueprintInventory.valid();
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
        && !FactoryUpgradeEvents.isDispatchingAny()
        && canCommitStructure()
        && server.getDirectSignalTo(worldPosition) == 0;
  }

  public void refreshUpgradeSources() {
    upgrades.refresh();
  }

  public FactoryUpgradeSnapshot upgradeSnapshot() {
    return upgrades.snapshot();
  }

  public boolean upgradeSnapshotCurrent(FactoryUpgradeSnapshot expected) {
    return upgrades.current(expected);
  }

  @Override
  public FactoryUpgradeRestrictions.Result upgradeRestrictionStatus() {
    return upgrades.restrictions();
  }

  @Override
  public boolean containsUpgradeBusPart(BlockPos position) {
    return upgrades.contains(position);
  }

  @Override
  public boolean publishUpgradeBus(UpgradeBusBlockEntity bus) {
    return bus.hasFactoryBinding(this) && upgrades.contains(bus.getBlockPos());
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
    return "main".equals(threadId) && runningRecipe == null;
  }

  @Override
  public List<BlockPos> upgradeBusPositions() {
    return upgrades.positions();
  }

  @Override
  public List<FrozenItemRun> upgradeRuns(String threadId) {
    if (!"main".equals(threadId) || runningRecipe == null || !canCommitProduction()) {
      return List.of();
    }

    return List.of(runningRecipe);
  }

  @Override
  public FactoryUpgradeRuntimeWork upgradeRuntimeWork() {
    return runtimeUpgrades;
  }

  public long successfulTransfers() {
    return successfulTransfers;
  }

  public long bindingRebuilds() {
    return bindingRebuilds;
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

  public Optional<FrozenItemRun> runningRecipe() {
    return Optional.ofNullable(runningRecipe);
  }

  public boolean recipeStartInProgress() {
    return recipeStart != null || recipeUpdate != null || FactoryUpgradeEvents.isDispatchingAny();
  }

  public ItemRunEngine.Result recipeRunStatus() {
    if (runningRecipe == null && level instanceof ServerLevel && canCommitStructure() && !upgrades.current()) {
      return upgrades.restrictions()
          .valid() ? ItemRunEngine.Result.UPGRADE_ERROR : ItemRunEngine.Result.UPGRADE_CONFLICT;
    }
    return recipeEngine.status();
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
    if (getBlockState().getBlock() instanceof MachineControllerBlock block && block.dedicated() != null) {
      return block.asItem().getName(block.asItem().getDefaultInstance());
    }
    return Component.translatable("block.mmcece.machine_controller");
  }

  @Override
  public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
    return new MachineControllerMenu(id, inventory, this);
  }

  /**
   * No callbacks or persistence calls in lease operations; the transaction
   * publishes after resolving all ports.
   */
  public RunStartLease acquireRecipeStart() {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Recipe start outside server thread");
    }
    if (recipeStartInProgress()
        || runningRecipe != null
        || !schemaSupported()
        || isRemoved()
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this) {
      throw new IllegalStateException("Controller cannot accept another recipe start");
    }
    recipeStart = new RunStartLease();
    return recipeStart;
  }

  public RunUpdateLease acquireRunUpdate(FrozenItemRun expected) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Run update outside server thread");
    }
    if (recipeStartInProgress()
        || expected == null
        || runningRecipe != expected
        || !schemaSupported()
        || isRemoved()
        || !server.hasChunkAt(worldPosition)
        || server.getBlockEntity(worldPosition) != this) {
      throw new IllegalStateException("Stale paid run owner");
    }
    recipeUpdate = new RunUpdateLease(expected);
    return recipeUpdate;
  }

  public final class RunUpdateLease implements AutoCloseable {
    private final FrozenItemRun before;
    private boolean resolved, installed;

    private RunUpdateLease(FrozenItemRun before) {
      this.before = before;
    }

    private void check() {
      if (!(level instanceof ServerLevel server)
          || !server.getServer().isSameThread()
          || resolved
          || recipeUpdate != this) {
        throw new IllegalStateException("Stale run update lease");
      }
    }

    public void install(FrozenItemRun replacement) {
      check();
      if (replacement == null) {
        if (!before.pendingOutput()) {
          throw new IllegalStateException("Unfinished run cannot be cleared");
        }
      } else {
        if (!before.sameRecipe(replacement)
            || replacement.progress() < before.progress()
            || (long) replacement.progress() > (long) before.progress() + 1
            || before.pendingOutput() && replacement != before) {
          throw new IllegalArgumentException("Invalid run state transition");
        }
      }
      runningRecipe = replacement;
      installed = true;
    }

    public void installFailure(FrozenItemRun replacement) {
      check();
      if (!before.sameRecipe(replacement)
          || before.pendingOutput()
          || replacement.pendingOutput()
          || replacement.progress() < 0
          || replacement.progress() > before.progress()) {
        throw new IllegalArgumentException("Invalid failed run state transition");
      }
      runningRecipe = replacement;
      installed = true;
    }

    public void installDiscard() {
      check();
      if (before.pendingOutput()) {
        throw new IllegalArgumentException("Cannot discard a pending output run");
      }
      runningRecipe = null;
      installed = true;
    }

    public void resolve(boolean commit) {
      check();
      if (commit && !installed) {
        throw new IllegalStateException("No prepared run update");
      }
      if (!commit) {
        runningRecipe = before;
      }
      recipeUpdate = null;
      resolved = true;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
  }

  public final class RunStartLease implements AutoCloseable {
    private final FrozenItemRun before = runningRecipe;
    private boolean resolved;

    private void check() {
      if (!(level instanceof ServerLevel server)
          || !server.getServer().isSameThread()
          || resolved
          || recipeStart != this) {
        throw new IllegalStateException("Stale controller start lease");
      }
    }

    public void install(FrozenItemRun run) {
      check();
      if (run == null || !run.machineId().equals(machineId)) {
        throw new IllegalArgumentException("Wrong run owner");
      }
      runningRecipe = run;
    }

    public void resolve(boolean commit) {
      check();
      if (commit && runningRecipe == before) {
        throw new IllegalStateException("No installed recipe run");
      }
      if (!commit) {
        runningRecipe = before;
      }
      recipeStart = null;
      resolved = true;
    }

    @Override
    public void close() {
      if (!resolved) {
        resolve(false);
      }
    }
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
    if (recipeStartInProgress() || runningRecipe != null && !id.equals(machineId)) {
      throw new IllegalStateException("Cannot rebind a paid or starting recipe");
    }
    machineId = id;
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
    if (recipeStartInProgress()) {
      return;
    }
    try {
      tickController(server);
    } finally {
      RecipeLifecycleEvents.machineTick(this);
    }
  }

  private void tickController(ServerLevel server) {
    if (getBlockState().getBlock() instanceof MachineControllerBlock block
        && schemaSupported()
        && runningRecipe == null
        && !FactoryUpgradeEvents.isDispatchingAny()
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
    if (runningRecipe != null) {
      long tickStarted = System.nanoTime();
      var runResult = recipeEngine.tick(this, server);
      ControllerMetrics.recordTick(System.nanoTime() - tickStarted, server.getServer().getTickCount());
      broadcastObserverStatus(server);
      if (runningRecipe != null || runResult != ItemRunEngine.Result.FINISHED) {
        return;
      }
      recipeEngine.acknowledgeFinish();
    }
    if (!canCommitProduction()) {
      return;
    }
    var ticket = registration;
    long amount = ticket.pattern.definition.energyPerTick();
    if (amount <= 0) {
      if (server.getGameTime() >= recipeRetryAfter) {
        try {
          advanceNativeRecipe(server);
        } catch (RuntimeException failure) {
          recipeSearch = null;
          preparedRecipe = null;
          backoffRecipeRetry(server.getGameTime());
          if (!failure.toString().equals(lastRecipeFailure)) {
            lastRecipeFailure = failure.toString();
            ModularMachine.LOGGER.warn("Ordinary recipe preparation failed at {}", worldPosition, failure);
          }
        }
      }
      return;
    }
    if (!(server.getBlockEntity(ticket.pattern.energyInputPosition()) instanceof MachinePortBlockEntity foundInput)
        || !(server.getBlockEntity(ticket.pattern.energyOutputPosition()) instanceof MachinePortBlockEntity foundOutput)
        || !foundInput.schemaSupported()
        || !foundOutput.schemaSupported()) {
      return;
    }
    if (input != foundInput || output != foundOutput) {
      input = foundInput;
      output = foundOutput;
      bindingRebuilds = addTelemetry(bindingRebuilds, 1);
    }
    var source = input;
    var target = output;
    if (source == null || target == null || source == target || registration != ticket || !canCommitProduction()) {
      return;
    }
    if (!source.energy().canTransferExactTo(target.energy(), amount)) {
      return;
    }
    if (!revalidateProduction(ticket)) {
      return;
    }
    if (registration != ticket
        || !canCommitProduction()
        || server.getBlockEntity(ticket.pattern.energyInputPosition()) != source
        || server.getBlockEntity(ticket.pattern.energyOutputPosition()) != target) {
      return;
    }
    if (source.energy().transferExactTo(target.energy(), amount)) {
      successfulTransfers = addTelemetry(successfulTransfers, 1);
      setChanged();
    }
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
    var snapshot = MachineControllerApi.inspectForMenu(this);
    if (snapshot.isEmpty()) {
      return;
    }
    var payload = MachinePayloads.ControllerStatusPayload.from(snapshot.orElseThrow());
    for (ServerPlayer player : nearbyPlayers) {
      if (!payload.equals(lastObserverStatus) || !observerRecipients.contains(player.getUUID())) {
        PacketDistributor.sendToPlayer(player, payload);
      }
    }
    observerRecipients.retainAll(nearby);
    observerRecipients.addAll(nearby);
    lastObserverStatus = payload;
  }

  private void advanceNativeRecipe(ServerLevel server) {
    if (recipeSearch != null && !recipeSearch.dependenciesCurrent()) {
      recipeSearch = null;
      preparedRecipe = null;
      resetRecipeRetry(server.getGameTime());
    }
    if (recipeSearch == null) {
      recipeSearch = ItemRecipeSearch.begin(this).orElse(null);
      if (recipeSearch == null) {
        backoffRecipeRetry(server.getGameTime());
        return;
      }
    }
    if (preparedRecipe == null) {
      long searchStarted = System.nanoTime();
      try {
        var result = recipeSearch.advance(MachineConfig.RECIPE_OUTPUT_WORK.get());
        if (result.status() == Status.BUDGET_EXHAUSTED) {
          return;
        }
        if (result.status() == Status.NO_MATCH) {
          recipeSearch = null;
          backoffRecipeRetry(server.getGameTime());
          return;
        }
        preparedRecipe = result.plan().orElseThrow();
      } finally {
        ControllerMetrics.recordSearch(System.nanoTime() - searchStarted, server.getServer().getTickCount());
      }
    }
    var result = preparedRecipe.start();
    if (result == Result.STARTED) {
      recipeEngine.started();
      resetRecipeRetry(server.getGameTime());
    }
    if (result != Result.BUSY) {
      recipeSearch = null;
      preparedRecipe = null;
      if (result == Result.STALE) {
        resetRecipeRetry(server.getGameTime());
      } else if (result != Result.STARTED) {
        backoffRecipeRetry(server.getGameTime());
      }
    }
  }

  private void resetRecipeRetry(long now) {
    recipeRetryInterval = RECIPE_RETRY_INITIAL;
    recipeRetryAfter = now;
  }

  private void backoffRecipeRetry(long now) {
    recipeRetryAfter = now + recipeRetryInterval;
    recipeRetryInterval = Math.min(RECIPE_RETRY_MAX, recipeRetryInterval << 1);
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
        ticket.verifier.rejectProof(full.failure(), full.failedIndex(), server.getGameTime());
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
        successfulTransfers,
        bindingRebuilds);
  }

  @Override
  protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (recipeStartInProgress()) {
      throw new IllegalStateException("Cannot save an unresolved recipe transaction");
    }
    super.saveAdditional(tag, registries);
    tag.put("controller_blueprint", blueprintInventory.save(registries));
    if (unsupportedData != null) {
      tag.put("mmcece", unsupportedData.copy());
      return;
    }
    CompoundTag data = new CompoundTag();
    data.putInt("schema", runningRecipe == null ? 1 : 2);
    data.putString("machine", machineId.toString());
    data.putLong("successful_transfers", successfulTransfers);
    if (runningRecipe != null) {
      data.put("run", runningRecipe.save());
    }
    tag.put("mmcece", data);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server && !server.getServer().isSameThread()) {
      throw new IllegalStateException("Controller load outside server thread");
    }
    if (recipeStartInProgress()) {
      throw new IllegalStateException("Cannot reload a recipe transaction");
    }
    runtimeUpgrades.clear();
    upgrades.invalidate();
    recipeEngine.invalidate();
    super.loadAdditional(tag, registries);
    genericDiscovery.clear();
    blueprintInventory.load(registries, tag.get("controller_blueprint"));
    recipeSearch = null;
    preparedRecipe = null;
    CompoundTag data = tag.getCompound("mmcece");
    var parsed = ControllerPlacementData.fromTag(tag);
    if (parsed.opaque()) {
      unsupportedData = tag.get("mmcece").copy();
      runningRecipe = null;
    } else {
      unsupportedData = null;
      machineId = parsed.machineId();
      successfulTransfers = Math.max(0, data.getLong("successful_transfers"));
      try {
        runningRecipe = data.contains("run", Tag.TAG_COMPOUND) ? FrozenItemRun
            .read(data.getCompound("run"), registries) : null;
      } catch (RuntimeException invalidRun) {
        unsupportedData = data.copy();
        runningRecipe = null;
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
    root.put("controller_blueprint", blueprintInventory.save(registries));
    if (unsupportedData != null) {
      root.put("mmcece", unsupportedData.copy());
    } else {
      CompoundTag data = new CompoundTag();
      data.putInt("schema", runningRecipe == null ? 1 : 2);
      data.putString("machine", machineId.toString());
      if (runningRecipe != null) {
        data.put("run", runningRecipe.save());
      }
      root.put("mmcece", data);
    }
    return root;
  }
}
