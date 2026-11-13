package com.lw.modularmachine.blockentity;

import java.util.stream.Collectors;
import com.lw.modularmachine.machine.factory.UpgradeRecipeChecks;
import com.lw.modularmachine.machine.factory.UpgradeLifecyclePublisher;
import com.lw.modularmachine.block.FactoryControllerBlock;
import com.lw.modularmachine.config.MachineConfig;
import com.lw.modularmachine.machine.appearance.ControllerAppearance;
import com.lw.modularmachine.machine.appearance.MachineComponentType;
import com.lw.modularmachine.machine.controller.GenericControllerDiscovery;
import com.lw.modularmachine.machine.controller.ControllerScriptState;
import com.lw.modularmachine.machine.controller.ControllerScriptStateOwner;
import com.lw.modularmachine.machine.controller.ControllerOwnershipState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandState;
import com.lw.modularmachine.machine.recipe.command.ControllerCommandSubject;
import com.lw.modularmachine.machine.recipe.command.RecipeCommandService;
import com.lw.modularmachine.machine.controller.ControllerOwnershipService;
import com.lw.modularmachine.machine.factory.FactoryRunSet;
import com.lw.modularmachine.machine.factory.FactoryRunSetCodec;
import com.lw.modularmachine.machine.factory.FactoryScheduler;
import com.lw.modularmachine.machine.factory.FactoryThreadPreset;
import com.lw.modularmachine.machine.factory.FactoryThreadState;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements;
import com.lw.modularmachine.machine.factory.FactoryThreadStateCodec;
import com.lw.modularmachine.machine.factory.FactoryUpgradeCatalog;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeEntry;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions.Installed;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRestrictions.Result;
import com.lw.modularmachine.machine.factory.FactoryUpgradeRuntimeWork;
import com.lw.modularmachine.machine.factory.FactoryUpgradeService;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.factory.FactoryUpgradeStartCosts;
import com.lw.modularmachine.machine.factory.UpgradeBusOwner;
import com.lw.modularmachine.machine.factory.UpgradeBusParts;
import com.lw.modularmachine.machine.factory.UpgradeStartState;
import com.lw.modularmachine.machine.orientation.ControllerPlacementData;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.machine.recipe.FactoryOutputEngine;
import com.lw.modularmachine.machine.recipe.FactoryParallelResourceCommitter;
import com.lw.modularmachine.machine.recipe.FactoryParallelStarter;
import com.lw.modularmachine.machine.recipe.ControllerMetrics;
import com.lw.modularmachine.machine.recipe.FactoryRecipeStarter;
import com.lw.modularmachine.machine.recipe.FactoryRunResourceCommitter;
import com.lw.modularmachine.machine.recipe.FrozenItemRun;
import com.lw.modularmachine.machine.recipe.RecipeStartPlan;
import com.lw.modularmachine.machine.recipe.search.RecipeSearch;
import com.lw.modularmachine.machine.recipe.MachineRecipeService;
import com.lw.modularmachine.api.recipe.RecipeLifecycleEvents.Type;
import com.lw.modularmachine.machine.recipe.RecipeLifecyclePublisher;
import com.lw.modularmachine.machine.smart.SmartInterfaceEventPublisher;
import com.lw.modularmachine.machine.structure.MachineDefinition;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.machine.structure.VerificationFailure;
import com.lw.modularmachine.machine.structure.VerificationState;
import com.lw.modularmachine.menu.FactoryControllerMenu;
import com.lw.modularmachine.registry.MachineRegistries;
import com.lw.modularmachine.storage.ControllerBlueprintInventory;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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

public final class FactoryControllerBlockEntity extends BlockEntity implements MenuProvider, UpgradeBusOwner, ControllerScriptStateOwner, ControllerCommandSubject {
  private static final int FACTORY_SCHEMA = 7;
  private static final int SEARCH_RETRY_INITIAL = 5;
  private static final int SEARCH_RETRY_MAX = 80;
  private FactoryScheduler scheduler = new FactoryScheduler(List.of());
  private boolean threadsInitialized;
  private long configurationRevision;
  private boolean persistentStateValid = true;
  private CompoundTag opaqueFactoryState;
  private ControllerScriptState controllerScriptState = ControllerScriptState.empty();
  private Tag unsupportedScriptData;
  private ControllerOwnershipState ownership = ControllerOwnershipState.empty();
  private ControllerCommandState commandState = new ControllerCommandState();
  private final GenericControllerDiscovery genericDiscovery = new GenericControllerDiscovery();
  private ResourceLocation machineId = ControllerPlacementData.UNBOUND_MACHINE;
  private final ControllerBlueprintInventory blueprintInventory = new ControllerBlueprintInventory(
      this::checkBlueprintWrite,
      this::blueprintChanged);
  private StructureService.Registration registration;
  private VerificationState detachedState = VerificationState.PENDING;
  private VerificationFailure detachedFailure = VerificationFailure.UNVERIFIED;
  private final Map<String, RecipeSearch> searches = new HashMap<>();
  private final Map<String, RecipeStartPlan> plans = new HashMap<>();
  private final Map<String, Long> searchRetryAfter = new HashMap<>();
  private final Map<String, Integer> searchRetryInterval = new HashMap<>();
  private final Map<String, FactoryRunSet> runSets = new HashMap<>();
  private final FactoryUpgradeRuntimeWork runtimeUpgrades = new FactoryUpgradeRuntimeWork(this);
  private final FactoryOutputEngine outputEngine = new FactoryOutputEngine();
  private final Map<BlockPos, UpgradeBusBlockEntity> upgradeBusSources = new HashMap<>();
  private StructureService.Registration upgradeDiscoveryRegistration;
  private List<BlockPos> upgradeDiscoveryPositions = List.of();
  private Set<BlockPos> upgradeDiscoveryMembership = Set.of();
  private long lastUpgradeDiscoveryTick = Long.MIN_VALUE;
  private final Set<String> startingThreads = new HashSet<>();
  private List<UpgradeBusSource> appliedUpgradeSourceView;
  private FactoryUpgradeSnapshot pendingUpgradeSnapshot;
  private long upgradeSourceEpoch;
  private boolean upgradeSourcesManaged;
  private final Map<String, Integer> parallelAdmissionLimits = new HashMap<>();
  private boolean upgradeSourcesValid = true;
  private List<UpgradeRestrictionSource> appliedRestrictionSources = List.of();
  private boolean upgradeRestrictionSourcesValid = true;
  private Result upgradeRestrictionStatus = FactoryUpgradeRestrictions.evaluate(List.of());

  private record UpgradeRestrictionSource(
      BlockPos position,
      UpgradeBusBlockEntity identity,
      long revision,
      long definitionEpoch,
      ResourceLocation machine,
      boolean valid,
      List<Installed> installed) {
    private UpgradeRestrictionSource {
      position = position.immutable();
      installed = List.copyOf(installed);
    }
  }

  private void blueprintChanged() {
    setChanged();
    if (level instanceof ServerLevel) {
      boolean formed = registration != null && registration.verifier.state() == VerificationState.CONFIRMED;
      genericDiscovery.clear();
      clearSearchState();
      var target = blueprintInventory.machine();
      if (!formed
          && target != null
          && !target.equals(machineId)
          && configureMachine(target) == ConfigurationResult.SUCCESS) {
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
        && startingThreads.isEmpty()
        && !UpgradeLifecyclePublisher.isDispatchingAny()
        && !SmartInterfaceEventPublisher.isDispatching();
  }

  @Override
  @ApiStatus.Internal
  public void initializePlayerOwner(UUID playerId) {
    ControllerOwnershipService.requireInitialization(this);
    checkBlueprintWrite();
    if (ownership.valid() && ownership.ownerId().isEmpty()) {
      ownership = ControllerOwnershipState.ownedBy(playerId);
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
      configureMachine(blueprintInventory.machine());
    }
    StructureService.register(this);
    refreshAppearanceState();
    RecipeCommandService.schedule(this);
  }

  @Override
  public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
    return ownership.clientTag();
  }

  @Override
  public ClientboundBlockEntityDataPacket getUpdatePacket() {
    return ClientboundBlockEntityDataPacket.create(this);
  }

  @Override
  public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
    if (level != null && level.isClientSide) {
      ownership = ControllerOwnershipState.load(tag);
    }
  }

  @Override
  public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
    handleUpdateTag(packet.getTag(), registries);
  }

  @Override
  public void setRemoved() {
    RecipeCommandService.unschedule(this);
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    clearUpgradeDiscovery();
    upgradeBusSources.clear();
    clearSearchState();
    runSets.clear();
    outputEngine.clear();
    startingThreads.clear();
    appliedUpgradeSourceView = null;
    parallelAdmissionLimits.clear();
    pendingUpgradeSnapshot = null;
    StructureService.unregister(this);
    super.setRemoved();
  }

  @Override
  public void onChunkUnloaded() {
    RecipeCommandService.unschedule(this);
    genericDiscovery.clear();
    runtimeUpgrades.clear();
    clearUpgradeDiscovery();
    upgradeBusSources.clear();
    clearSearchState();
    runSets.clear();
    outputEngine.clear();
    startingThreads.clear();
    appliedUpgradeSourceView = null;
    parallelAdmissionLimits.clear();
    pendingUpgradeSnapshot = null;
    StructureService.unregister(this);
    super.onChunkUnloaded();
  }

  public FactoryScheduler scheduler() {
    return scheduler;
  }

  public int threadCount() {
    return scheduler.channels().size();
  }

  @ApiStatus.Internal
  public int maximumBusinessThreads() {
    requireUpgradeThread();
    var definition = StructureService.definition(machineId);

    return definition == null ? MachineConfig.defaultFactoryMaxThreads() : definition.maximumFactoryThreads();
  }

  private boolean threadStartAllowed(String threadId) {
    int limit = maximumBusinessThreads();
    int index = 0;
    boolean available = false;
    for (var thread : scheduler.channels()) {
      if (thread.threadId().equals(threadId)) {
        available = index < limit;
        break;
      }
      index++;
    }
    if (!available) {
      return false;
    }
    int occupied = 0;
    for (var entry : runSets.entrySet()) {
      if (!entry.getKey().equals(threadId) && !entry.getValue().runs().isEmpty()) {
        occupied++;
      }
    }
    for (String starting : startingThreads) {
      if (!starting.equals(threadId) && runSet(starting).runs().isEmpty()) {
        occupied++;
      }
    }

    return occupied < limit;
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
        && startingThreads.isEmpty()
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
    controllerScriptState = replacement;
    clearSearchState();
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

  public ControllerBlueprintInventory blueprintInventory() {
    return blueprintInventory;
  }

  private void checkBlueprintWrite() {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Factory blueprint edit");
      if (!startingThreads.isEmpty()
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
    return level instanceof ServerLevel server && registration != null && registration.canCommit(server);
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
        || !startingThreads.isEmpty()
        || !runSets.isEmpty() && !id.equals(machineId)) {
      throw new IllegalStateException("Factory binding during an active callback or startup");
    }
    machineId = id;
    clearUpgradeDiscovery();
    outputEngine.invalidate();
    clearSearchState();
    setChanged();
    StructureService.register(this);
  }

  @Override
  public void attach(StructureService.Registration registration) {
    runtimeUpgrades.clear();
    clearUpgradeDiscovery();
    outputEngine.invalidate();
    this.registration = registration;
    detachedState = VerificationState.PENDING;
    detachedFailure = VerificationFailure.UNVERIFIED;
    refreshAppearanceState();
  }

  @Override
  public void failPreparation(VerificationFailure failure) {
    clearUpgradeDiscovery();
    outputEngine.invalidate();
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
    var appearance = ControllerAppearance.factory(verification, scheduler.channels(), runSets, upgradeSourcesValid);
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

  public void replaceThreads(List<FactoryThreadState> threads) {
    if (!configurationAllowed() || !runSets.isEmpty() || !startingThreads.isEmpty()) {
      throw new IllegalStateException("Factory configuration is unavailable or owns paid runs");
    }
    installConfiguration(new FactoryScheduler(threads));
  }

  public enum ConfigurationResult {
    SUCCESS,
    NO_CHANGE,
    UNAVAILABLE,
    BUSY,
    UNKNOWN_DEFINITION,
    THREAD_NOT_FOUND,
    INVALID,
    LIMIT_REACHED
  }

  public boolean threadsInitialized() {
    return threadsInitialized;
  }

  public long configurationRevision() {
    return configurationRevision;
  }

  /**
   * Internal whitelist snapshot; paid runs and installed upgrades are not copied.
   */
  @ApiStatus.Internal
  public Optional<FactoryThreadPreset> copyThreadConfiguration() {
    requireUpgradeThread();
    if (!persistentStateValid
        || !threadsInitialized
        || isRemoved()
        || !level.hasChunkAt(worldPosition)
        || level.getBlockEntity(worldPosition) != this
        || StructureService.definition(machineId) == null) {
      return Optional.empty();
    }
    return Optional.of(
        new FactoryThreadPreset(
            scheduler.channels().stream()
                .map(
                    thread -> new FactoryThreadPreset.Thread(
                        thread.threadId(),
                        thread.lockedRecipe(),
                        thread.enabled(),
                        thread.priority(),
                        thread.parallelLimit()))
                .toList()));
  }

  /**
   * Internal atomic configuration paste. Compatibility metadata is checked, never
   * installed as identity.
   */
  @ApiStatus.Internal
  public ConfigurationResult pasteThreadConfiguration(ResourceLocation expectedMachine, long expectedRevision, FactoryThreadPreset preset) {
    if (!configurationAllowed()
        || UpgradeLifecyclePublisher.isDispatchingAny()
        || RecipeLifecyclePublisher.isDispatching()
        || SmartInterfaceEventPublisher.isDispatching()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (!machineId.equals(expectedMachine) || configurationRevision != expectedRevision || preset == null) {
      return ConfigurationResult.INVALID;
    }
    if (!runSets.isEmpty() || !startingThreads.isEmpty()) {
      return ConfigurationResult.BUSY;
    }
    var definition = StructureService.definition(machineId);
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    if (preset.threads().size() > definition.maximumFactoryThreads()) {
      return ConfigurationResult.LIMIT_REACHED;
    }
    var server = (ServerLevel) level;
    long epoch = StructureService.definitionEpoch();
    long revision = configurationRevision;
    for (var current : scheduler.channels()) {
      revision = Math.max(revision, current.revision());
    }
    if (revision >= Long.MAX_VALUE - 1) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var copied = new ArrayList<FactoryThreadState>();
    for (var thread : preset.threads()) {
      if (thread.lockedRecipe() != null
          && !MachineRecipeService.recipeBelongsTo(server.getServer(), machineId, thread.lockedRecipe())
              .orElse(false)) {
        return ConfigurationResult.INVALID;
      }
      var initial = thread.instantiate(machineId);
      copied.add(
          new FactoryThreadState(
              initial.threadId(),
              machineId,
              initial.lockedRecipe(),
              initial.enabled(),
              initial.priority(),
              initial.parallelLimit(),
              initial.upgradeRevision(),
              initial.state(),
              initial.reason(),
              revision + 1,
              initial.upgradeSnapshot()));
    }
    if (configurationRevision != expectedRevision
        || !configurationAllowed()
        || !runSets.isEmpty()
        || !startingThreads.isEmpty()
        || StructureService.definitionEpoch() != epoch
        || StructureService.definition(machineId) != definition
        || !machineId.equals(expectedMachine)) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var current = copyThreadConfiguration();
    if (current.isPresent() && current.get().equals(preset)) {
      return ConfigurationResult.NO_CHANGE;
    }
    installConfiguration(new FactoryScheduler(copied));

    return ConfigurationResult.SUCCESS;
  }

  private boolean configurationAllowed() {
    requireUpgradeThread();

    return persistentStateValid
        && configurationRevision < Long.MAX_VALUE
        && scheduler.channels().stream().noneMatch(thread -> thread.revision() == Long.MAX_VALUE)
        && !UpgradeLifecyclePublisher.isDispatching(this)
        && !isRemoved()
        && level.hasChunkAt(worldPosition)
        && level.getBlockEntity(worldPosition) == this;
  }

  private void installConfiguration(FactoryScheduler replacement) {
    if (replacement.channels().size() > maximumBusinessThreads()) {
      var existing = scheduler.channels().stream().map(FactoryThreadState::threadId).collect(Collectors.toSet());
      if (replacement.channels().size() >= scheduler.channels().size()
          || replacement.channels().stream().anyMatch(thread -> !existing.contains(thread.threadId()))) {
        throw new IllegalArgumentException("Factory configuration exceeds its machine business thread limit");
      }
    }
    // Retired channels must not lose their revision watermark before a later paste
    // reuses an ID.
    long highestRevision = configurationRevision;
    for (var thread : scheduler.channels()) {
      highestRevision = Math.max(highestRevision, thread.revision());
    }
    for (var thread : replacement.channels()) {
      highestRevision = Math.max(highestRevision, thread.revision());
    }
    long nextRevision = Math.incrementExact(highestRevision);
    scheduler = replacement;
    threadsInitialized = true;
    configurationRevision = nextRevision;
    clearSearchState();
    outputEngine.invalidate();
    appliedUpgradeSourceView = null;
    pendingUpgradeSnapshot = null;
    upgradeSourcesValid = true;
    setChanged();
  }

  private void initializeThreadsIfNeeded() {
    if (threadsInitialized
        || !persistentStateValid
        || !runSets.isEmpty()
        || !startingThreads.isEmpty()
        || configurationRevision == Long.MAX_VALUE) {
      return;
    }
    var definition = StructureService.definition(machineId);
    if (definition != null && configurationAllowed()) {
      installConfiguration(new FactoryScheduler(definition.factoryPreset().instantiate(machineId)));
    }
  }

  public ConfigurationResult resetThreadPreset() {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (!runSets.isEmpty() || !startingThreads.isEmpty()) {
      return ConfigurationResult.BUSY;
    }
    var definition = StructureService.definition(machineId);
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    installConfiguration(new FactoryScheduler(definition.factoryPreset().instantiate(machineId)));

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult configureMachine(ResourceLocation machine) {
    if (getBlockState().getBlock() instanceof FactoryControllerBlock block
        && block.dedicated() != null
        && !block.dedicated().machine().equals(machine)
        && !Objects.equals(machine, blueprintInventory.machine())) {
      return ConfigurationResult.INVALID;
    }
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (!runSets.isEmpty() || !startingThreads.isEmpty()) {
      return ConfigurationResult.BUSY;
    }
    var definition = machine == null ? null : StructureService.definition(machine);
    if (definition == null) {
      return ConfigurationResult.UNKNOWN_DEFINITION;
    }
    var replacement = new FactoryScheduler(definition.factoryPreset().instantiate(machine));
    bindMachine(machine);
    installConfiguration(replacement);

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult addThread(String id, int priority) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (!startingThreads.isEmpty()) {
      return ConfigurationResult.BUSY;
    }
    if (scheduler.channels().size() >= maximumBusinessThreads()) {
      return ConfigurationResult.LIMIT_REACHED;
    }
    if (scheduler.channel(id) != null) {
      return ConfigurationResult.INVALID;
    }
    try {
      var threads = new ArrayList<>(scheduler.channels());
      threads.add(new FactoryThreadPreset.Thread(id, null, true, priority, 1).instantiate(machineId));
      installConfiguration(new FactoryScheduler(threads));

      return ConfigurationResult.SUCCESS;
    } catch (IllegalArgumentException invalid) {
      return ConfigurationResult.INVALID;
    }
  }

  public ConfigurationResult removeThread(String id) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    if (scheduler.channel(id) == null) {
      return ConfigurationResult.THREAD_NOT_FOUND;
    }
    if (!runSet(id).runs().isEmpty() || !startingThreads.isEmpty()) {
      return ConfigurationResult.BUSY;
    }
    installConfiguration(
        new FactoryScheduler(scheduler.channels().stream().filter(thread -> !thread.threadId().equals(id)).toList()));

    return ConfigurationResult.SUCCESS;
  }

  public ConfigurationResult setThreadPriority(String id, int priority) {
    if (!configurationAllowed()) {
      return ConfigurationResult.UNAVAILABLE;
    }
    var current = scheduler.channel(id);
    if (current == null) {
      return ConfigurationResult.THREAD_NOT_FOUND;
    }
    try {
      var next = current.withPriority(priority);
      if (next == current) {
        return ConfigurationResult.NO_CHANGE;
      }
      scheduler = scheduler.replace(next);
      configurationRevision++;
      setChanged();

      return ConfigurationResult.SUCCESS;
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return ConfigurationResult.INVALID;
    }
  }

  public boolean updateThread(String threadId, long expectedRevision, boolean changeLock, ResourceLocation lockedRecipe, Boolean enabled) {
    return updateThread(threadId, expectedRevision, changeLock, lockedRecipe, enabled, null, null);
  }

  public boolean updateThread(String threadId, long expectedRevision, boolean changeLock, ResourceLocation lockedRecipe, Boolean enabled, Integer parallelLimit, Long upgradeRevision) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Factory thread update outside server thread");
    }
    if (!configurationAllowed() || threadId == null || expectedRevision < 0) {
      return false;
    }
    var current = scheduler.channel(threadId);
    if (current == null || current.revision() != expectedRevision) {
      return false;
    }
    if (changeLock && (!runSet(threadId).runs().isEmpty() || startingThreads.contains(threadId))) {
      return false;
    }
    if (changeLock
        && lockedRecipe != null
        && !MachineRecipeService.recipeBelongsTo(server.getServer(), current.machineId(), lockedRecipe).orElse(false)) {
      return false;
    }
    var next = current;
    if (changeLock) {
      next = next.withLockedRecipe(lockedRecipe);
    }
    if (enabled != null) {
      next = next.withEnabled(enabled);
    }
    if (parallelLimit != null || upgradeRevision != null) {
      if (parallelLimit == null || upgradeRevision == null) {
        return false;
      }
      next = next.withParallelLimit(parallelLimit, upgradeRevision);
    }
    if (next == current) {
      return true;
    }
    scheduler = scheduler.replace(next);
    configurationRevision++;
    removeSearchState(threadId, server.getGameTime());
    setChanged();
    return true;
  }

  public boolean updateUpgradeSnapshot(String threadId, long expectedRevision, FactoryUpgradeSnapshot snapshot) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Factory upgrade snapshot updated outside server thread");
    }
    if (!persistentStateValid
        || threadId == null
        || snapshot == null
        || expectedRevision < 0
        || !runSet(threadId).runs().isEmpty()) {
      return false;
    }
    var current = scheduler.channel(threadId);
    if (current == null || current.revision() != expectedRevision) {
      return false;
    }
    final FactoryThreadState next;
    try {
      next = current.withUpgradeSnapshot(snapshot);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    if (next == current) {
      return true;
    }
    scheduler = scheduler.replace(next);
    removeSearchState(threadId, server.getGameTime());
    setChanged();
    return true;
  }

  public boolean updateUpgradeCatalog(String threadId, long expectedRevision, List<FactoryUpgradeEntry> entries, long revision) {
    if (entries == null || revision < 0) {
      return false;
    }
    try {
      var catalog = FactoryUpgradeCatalog.compile(entries, revision);
      return updateUpgradeSnapshot(threadId, expectedRevision, catalog.snapshot());
    } catch (IllegalArgumentException | ArithmeticException invalid) {
      return false;
    }
  }

  /**
   * Validated server-side sources used to publish immutable thread snapshots.
   */
  public record UpgradeBusSource(BlockPos position, FactoryUpgradeCatalog catalog) {
    public UpgradeBusSource {
      position = position.immutable();
      Objects.requireNonNull(catalog, "catalog");
    }
  }

  public List<UpgradeBusSource> upgradeSources() {
    requireUpgradeThread();
    upgradeBusSources.entrySet().removeIf(entry -> !entry.getValue().isBoundTo(this));

    return upgradeBusSources.entrySet().stream().sorted(Comparator.comparingLong(entry -> entry.getKey().asLong()))
        .map(entry -> new UpgradeBusSource(entry.getKey(), entry.getValue().upgradeCatalog(this))).toList();
  }

  public boolean publishUpgradeBus(UpgradeBusBlockEntity bus) {
    requireUpgradeThread();
    if (!bus.isBoundTo(this)) {
      return false;
    }
    var position = bus.getBlockPos().immutable();
    if (!upgradeBusSources.containsKey(position) && upgradeBusSources.size() >= FactoryUpgradeCatalog.MAX_SLOTS) {
      return false;
    }
    upgradeBusSources.put(position, bus);

    return true;
  }

  public void withdrawUpgradeBus(UpgradeBusBlockEntity bus) {
    requireUpgradeThread();
    upgradeBusSources.remove(bus.getBlockPos(), bus);
  }

  private void requireUpgradeThread() {
    if (!(level instanceof ServerLevel server)) {
      throw new IllegalStateException("Factory upgrade sources require a server level");
    }
    ServerThreadGuard.require(server, "Factory upgrade sources accessed");
  }

  public Optional<FrozenItemRun> run(String threadId) {
    var set = runSets.get(threadId);
    return set == null || set.runs().isEmpty() ? Optional.empty() : Optional.of(set.runs().get(0));
  }

  public FactoryRunSet runSet(String threadId) {
    return runSets.getOrDefault(threadId, FactoryRunSet.empty());
  }

  public FactoryOutputEngine.Result advanceOutput(String threadId, FactoryRunSet set) {
    if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
      throw new IllegalStateException("Factory output planning outside server thread");
    }
    return outputEngine.advance(this, threadId, set);
  }

  public boolean beginRunStart(String threadId) {
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    refreshUpgradeSnapshot();
    var thread = scheduler.channel(threadId);
    if (!persistentStateValid
        || !upgradeSourcesValid
        || thread == null
        || !threadStartAllowed(threadId)
        || pendingUpgradeSnapshot != null && !thread.upgradeSnapshot().equals(pendingUpgradeSnapshot)
        || !runSet(threadId).runs().isEmpty()
        || !startingThreads.add(threadId)) {
      return false;
    }
    try {
      parallelAdmissionLimits.put(threadId, effectiveParallelLimit(threadId));
    } catch (RuntimeException unavailable) {
      startingThreads.remove(threadId);
      parallelAdmissionLimits.remove(threadId);
      return false;
    }
    return true;
  }

  public int effectiveParallelLimit(String threadId) {
    var thread = scheduler.channel(threadId);
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
    for (var entry : runSets.entrySet()) {
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

  public void installRun(String threadId, FrozenItemRun run) {
    installRuns(threadId, List.of(run));
  }

  public void installRuns(String threadId, List<FrozenItemRun> runs) {
    if (!startingThreads.contains(threadId) || !threadStartAllowed(threadId) || runs == null || runs.isEmpty()) {
      throw new IllegalStateException("Invalid factory run installation");
    }
    var thread = scheduler.channels().stream().filter(candidate -> candidate.threadId().equals(threadId)).findFirst()
        .orElseThrow();
    if (runs.size() > parallelAdmissionLimits.getOrDefault(threadId, thread.parallelLimit())) {
      throw new IllegalArgumentException("Batch exceeds current factory thread parallel limit");
    }
    runSets.put(threadId, runSets.getOrDefault(threadId, FactoryRunSet.empty()).addAll(runs));
  }

  public void finishRunStart(String threadId, boolean committed) {
    startingThreads.remove(threadId);
    parallelAdmissionLimits.remove(threadId);
  }

  public boolean removeRunIfSame(String threadId, FrozenItemRun expected) {
    requireUpgradeThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    var set = runSets.get(threadId);
    if (set == null || set.find(expected.runId()).orElse(null) != expected) {
      return false;
    }
    runSets.put(threadId, set.remove(expected.runId()));
    if (runSets.get(threadId).runs().isEmpty()) {
      runSets.remove(threadId);
    }
    return true;
  }

  public boolean replaceRunIfSame(String threadId, FrozenItemRun expected, FrozenItemRun replacement) {
    requireUpgradeThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    var set = runSets.get(threadId);
    if (set == null || replacement == null || set.find(expected.runId()).orElse(null) != expected) {
      return false;
    }
    runSets.put(threadId, set.replace(replacement));
    return true;
  }

  public boolean removeRunSetIfSame(String threadId, FactoryRunSet expected) {
    requireUpgradeThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    if (runSets.get(threadId) != expected) {
      return false;
    }
    runSets.remove(threadId);
    return true;
  }

  public boolean replaceRunSetIfSame(String threadId, FactoryRunSet expected, FactoryRunSet replacement) {
    requireUpgradeThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny()) {
      return false;
    }
    if (runSets.get(threadId) != expected || replacement == null) {
      return false;
    }
    runSets.put(threadId, replacement);
    return true;
  }

  public boolean applyRunFailureDecisionsIfSame(String threadId, FactoryRunSet expected, Map<UUID, FrozenItemRun> replacements, Set<UUID> discarded) {
    requireUpgradeThread();
    if (UpgradeLifecyclePublisher.isDispatchingAny() || runSets.get(threadId) != expected) {
      return false;
    }
    FactoryRunSet replacement = expected.applyFailureDecisions(replacements, discarded);
    if (replacement == expected) {
      return false;
    }
    if (replacement.runs().isEmpty()) {
      runSets.remove(threadId);
    } else {
      runSets.put(threadId, replacement);
    }

    return true;
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
      tickController(server);
    } finally {
      refreshAppearanceState();
      RecipeLifecyclePublisher.machineTick(this);
    }
  }

  private void tickController(ServerLevel server) {
    if (getBlockState().getBlock() instanceof FactoryControllerBlock block
        && persistentStateValid()
        && runSets.isEmpty()
        && startingThreads.isEmpty()
        && (registration == null || registration.verifier.state() == VerificationState.INVALID)) {
      var found = genericDiscovery.advance(
          server,
          worldPosition,
          structureOrientation(server),
          blueprintInventory.machine(),
          block.dedicated() == null ? null : block.dedicated().machine(),
          true);
      if (found != null && !found.equals(machineId)) {
        configureMachine(found);
      }
    }
    StructureService.register(this);
    initializeThreadsIfNeeded();
    refreshAutomaticUpgradeBindings(server);
    refreshThreadGates();
    refreshLockedRecipeGates(server);
    long tickStarted = System.nanoTime();
    advanceFactoryRuns(server);
    ControllerMetrics.recordTick(System.nanoTime() - tickStarted, server.getServer().getTickCount());
    refreshUpgradeSnapshot();
    long searchStarted = System.nanoTime();
    advanceRecipeSearches();
    ControllerMetrics.recordSearch(System.nanoTime() - searchStarted, server.getServer().getTickCount());
  }

  private void clearUpgradeDiscovery() {
    appliedRestrictionSources = List.of();
    upgradeRestrictionSourcesValid = true;
    upgradeRestrictionStatus = FactoryUpgradeRestrictions.evaluate(List.of());
    upgradeDiscoveryRegistration = null;
    upgradeDiscoveryPositions = List.of();
    upgradeDiscoveryMembership = Set.of();
    lastUpgradeDiscoveryTick = Long.MIN_VALUE;
  }

  private void refreshAutomaticUpgradeBindings(ServerLevel server) {
    if (!canCommitStructure()) {
      clearUpgradeDiscovery();
      return;
    }
    prepareUpgradeDiscovery();
    long now = server.getGameTime();
    if (lastUpgradeDiscoveryTick != Long.MIN_VALUE
        && now >= lastUpgradeDiscoveryTick
        && now - lastUpgradeDiscoveryTick < 20) {
      return;
    }
    lastUpgradeDiscoveryTick = now;
    for (var position : upgradeDiscoveryPositions) {
      if (server.hasChunkAt(position) && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus) {
        bus.bindDiscoveredFactory(this);
      }
    }
  }

  public boolean containsUpgradeBusPart(BlockPos position) {
    requireUpgradeThread();
    if (!canCommitStructure()) {
      return false;
    }
    prepareUpgradeDiscovery();

    return upgradeDiscoveryMembership.contains(position);
  }

  private void prepareUpgradeDiscovery() {
    if (upgradeDiscoveryRegistration != registration) {
      var parts = UpgradeBusParts.compile(registration);
      upgradeDiscoveryRegistration = registration;
      upgradeDiscoveryPositions = parts.positions();
      upgradeDiscoveryMembership = parts.membership();
      lastUpgradeDiscoveryTick = Long.MIN_VALUE;
    }
  }

  @Override
  public UpgradeStartState upgradeStartState(String threadId) {
    requireUpgradeThread();
    var thread = scheduler.channel(threadId);

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
  public List<BlockPos> upgradeBusPositions() {
    requireUpgradeThread();
    if (!canCommitStructure()) {
      return List.of();
    }
    prepareUpgradeDiscovery();

    return upgradeDiscoveryPositions;
  }

  public List<UpgradeBusBlockEntity> upgradeCallbackBuses() {
    requireUpgradeThread();
    if (!canCommitStructure()) {
      return List.of();
    }
    prepareUpgradeDiscovery();
    var server = (ServerLevel) level;
    var buses = new ArrayList<UpgradeBusBlockEntity>();
    for (var position : upgradeDiscoveryPositions) {
      if (server.hasChunkAt(position)
          && server.getBlockEntity(position) instanceof UpgradeBusBlockEntity bus
          && bus.hasFactoryBinding(this)) {
        buses.add(bus);
      }
    }

    return List.copyOf(buses);
  }

  @Override
  public List<FrozenItemRun> upgradeRuns(String threadId) {
    requireUpgradeThread();
    var thread = scheduler.channel(threadId);

    return thread == null || !thread.enabled() ? List.of() : runSet(threadId).runs();
  }

  @Override
  public FactoryUpgradeRuntimeWork upgradeRuntimeWork() {
    return runtimeUpgrades;
  }

  private void refreshUpgradeSnapshot() {
    if (verificationState() != VerificationState.CONFIRMED || scheduler.channels().isEmpty()) {
      return;
    }
    if (!refreshUpgradeRestrictions()) {
      upgradeSourcesManaged = true;
      upgradeSourcesValid = false;
      appliedUpgradeSourceView = null;
      pendingUpgradeSnapshot = null;
      clearSearchState();
      var reason = upgradeRestrictionStatus
          .valid() ? FactoryThreadState.Reason.CHECK_ERROR : FactoryThreadState.Reason.UPGRADE_CONFLICT;
      for (var thread : List.copyOf(scheduler.channels())) {
        if (runSet(thread.threadId()).runs().isEmpty()) {
          updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
        }
      }
      return;
    }
    var sources = upgradeSources();
    if (sources.isEmpty() && !upgradeSourcesManaged) {
      upgradeSourcesValid = true;
      return;
    }
    if (!sources.equals(appliedUpgradeSourceView)) {
      upgradeSourcesManaged = true;
      try {
        long maximumRevision = scheduler.channels().stream().mapToLong(FactoryThreadState::upgradeRevision).max()
            .orElse(0L);
        long nextEpoch = Math.incrementExact(Math.max(upgradeSourceEpoch, maximumRevision));
        var composed = sources.isEmpty() ? FactoryUpgradeSnapshot.identity(nextEpoch) : FactoryUpgradeSnapshot
            .compose(sources.stream().map(source -> source.catalog().snapshot()).toList());
        pendingUpgradeSnapshot = composed.withRevision(nextEpoch);
        upgradeSourceEpoch = nextEpoch;
        upgradeSourcesValid = true;
      } catch (IllegalArgumentException | ArithmeticException invalid) {
        pendingUpgradeSnapshot = null;
        upgradeSourcesValid = false;
        clearSearchState();
      }
      appliedUpgradeSourceView = List.copyOf(sources);
      setChanged();
    }
    if (!upgradeSourcesValid) {
      for (var thread : List.copyOf(scheduler.channels())) {
        if (runSet(thread.threadId()).runs().isEmpty()) {
          updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.CHECK_ERROR);
        }
      }
      return;
    }
    if (pendingUpgradeSnapshot == null) {
      return;
    }
    boolean complete = true;
    for (var thread : List.copyOf(scheduler.channels())) {
      if (thread.upgradeSnapshot().equals(pendingUpgradeSnapshot)) {
        continue;
      }
      complete = false;
      if (runSet(thread.threadId()).runs().isEmpty()) {
        updateUpgradeSnapshot(thread.threadId(), thread.revision(), pendingUpgradeSnapshot);
      }
    }
    if (complete) {
      pendingUpgradeSnapshot = null;
    }
  }

  private List<UpgradeRestrictionSource> captureUpgradeRestrictionSources(boolean refresh) {
    var result = new ArrayList<UpgradeRestrictionSource>();
    for (var bus : upgradeCallbackBuses()) {
      if (refresh) {
        bus.refreshUpgradeDefinitions();
      }
      boolean valid = bus.upgradeRestrictionSourcesValid(this);
      result.add(
          new UpgradeRestrictionSource(
              bus.getBlockPos(),
              bus,
              bus.upgradeRevision(),
              FactoryUpgradeService.snapshot().epoch(),
              machineId,
              valid,
              valid ? bus.upgradeRestrictions(this) : List.of()));
    }

    return List.copyOf(result);
  }

  private boolean refreshUpgradeRestrictions() {
    var sources = captureUpgradeRestrictionSources(true);
    if (!sources.equals(appliedRestrictionSources)) {
      upgradeRestrictionSourcesValid = sources.stream().allMatch(UpgradeRestrictionSource::valid);
      try {
        upgradeRestrictionStatus = upgradeRestrictionSourcesValid ? FactoryUpgradeRestrictions.evaluate(
            sources.stream().flatMap(source -> source.installed().stream()).toList()) : FactoryUpgradeRestrictions
                .evaluate(List.of());
      } catch (IllegalArgumentException | ArithmeticException inconsistent) {
        upgradeRestrictionSourcesValid = false;
        upgradeRestrictionStatus = FactoryUpgradeRestrictions.evaluate(List.of());
      }
      appliedRestrictionSources = sources;
      setChanged();
    }

    return upgradeRestrictionSourcesValid && upgradeRestrictionStatus.valid();
  }

  public Result upgradeRestrictionStatus() {
    requireUpgradeThread();

    return upgradeRestrictionStatus;
  }

  /** Passive final fence: this read never runs a dynamic upgrade callback. */
  public boolean upgradeRestrictionsCurrent() {
    requireUpgradeThread();

    return canCommitStructure()
        && upgradeRestrictionSourcesValid
        && upgradeRestrictionStatus.valid()
        && captureUpgradeRestrictionSources(false).equals(appliedRestrictionSources);
  }

  private void advanceFactoryRuns(ServerLevel server) {
    if (verificationState() != VerificationState.CONFIRMED) {
      outputEngine.invalidate();
      return;
    }
    for (var entry : List.copyOf(runSets.entrySet())) {
      String threadId = entry.getKey();
      var thread = scheduler.channel(threadId);
      if (thread == null || !thread.enabled()) {
        continue;
      }
      if (entry.getValue().runs().stream().allMatch(FrozenItemRun::pendingOutput)) {
        advancePendingOutput(threadId, entry.getValue());
        continue;
      }
      if (entry.getValue().runs().size() > 1
          && entry.getValue().runs().stream().anyMatch(
              run -> run.energyPerTick() > 0
                  || !run.fluidPerTick(server.registryAccess()).isEmpty()
                  || !run.chemicalPerTick().isEmpty())
          || FactoryUpgradeService.snapshot().hasTickWork()) {
        var result = FactoryParallelResourceCommitter.tick(this, threadId, entry.getValue());
        switch (result) {
          case WAITING_ENERGY ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_ENERGY);
          case WAITING_CHEMICAL ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_CHEMICAL);
          case MISSING_CHEMICAL_INPUT -> updateThreadState(
              thread,
              FactoryThreadState.State.WAITING,
              FactoryThreadState.Reason.MISSING_CHEMICAL_INPUT);
          case WAITING_FLUID ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_FLUID);
          case MISSING_ENERGY_INPUT ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.MISSING_ENERGY_INPUT);
          case MISSING_FLUID_INPUT ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.MISSING_FLUID_INPUT);
          case WAITING_UPGRADE ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_UPGRADE_COST);
          case UPGRADE_ERROR ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_ERROR);
          case UPGRADE_PENDING ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_DATA_PENDING);
          case STALE ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STALE_RESOURCES);
          case ADVANCED -> updateThreadState(thread, FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE);
        }
        if (result == FactoryParallelResourceCommitter.Result.ADVANCED) {
          advancePendingOutput(threadId, runSet(threadId));
        }
        continue;
      }
      FactoryThreadState.State threadState = FactoryThreadState.State.RUNNING;
      FactoryThreadState.Reason threadReason = FactoryThreadState.Reason.NONE;
      var replacements = new HashMap<UUID, FrozenItemRun>();
      for (var run : entry.getValue().runs()) {
        if (run.pendingOutput()) {
          continue;
        }
        if (run.energyPerTick() > 0
            || !run.fluidPerTick(server.registryAccess()).isEmpty()
            || !run.chemicalPerTick().isEmpty()) {
          var result = FactoryRunResourceCommitter.tick(this, threadId, run);
          if (result == FactoryRunResourceCommitter.Result.WAITING_ENERGY) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.WAITING_ENERGY;
          } else if (result == FactoryRunResourceCommitter.Result.MISSING_ENERGY_INPUT) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.MISSING_ENERGY_INPUT;
          } else if (result == FactoryRunResourceCommitter.Result.MISSING_FLUID_INPUT) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.MISSING_FLUID_INPUT;
          } else if (result == FactoryRunResourceCommitter.Result.WAITING_CHEMICAL) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.WAITING_CHEMICAL;
          } else if (result == FactoryRunResourceCommitter.Result.MISSING_CHEMICAL_INPUT) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.MISSING_CHEMICAL_INPUT;
          } else if (result == FactoryRunResourceCommitter.Result.WAITING_FLUID) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.WAITING_FLUID;
          } else if (result == FactoryRunResourceCommitter.Result.WAITING_UPGRADE) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.WAITING_UPGRADE_COST;
          } else if (result == FactoryRunResourceCommitter.Result.UPGRADE_ERROR) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.UPGRADE_ERROR;
          } else if (result == FactoryRunResourceCommitter.Result.UPGRADE_PENDING) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.UPGRADE_DATA_PENDING;
          } else if (result == FactoryRunResourceCommitter.Result.STALE) {
            threadState = FactoryThreadState.State.WAITING;
            threadReason = FactoryThreadState.Reason.STALE_RESOURCES;
          }
          continue;
        }
        if (!canCommitStructure() || !run.matchesBindings(registration.pattern.definition)) {
          threadState = FactoryThreadState.State.WAITING;
          threadReason = FactoryThreadState.Reason.STALE_RESOURCES;
          break;
        }
        var advanced = run.advanced(server.getGameTime());
        if (advanced == run) {
          continue;
        }
        if (advanced.progress() == advanced.duration()) {
          advanced = advanced.finished();
          threadState = FactoryThreadState.State.WAITING;
          threadReason = FactoryThreadState.Reason.OUTPUT_PLANNING;
        }
        replacements.put(run.runId(), advanced);
      }
      if (threadReason == FactoryThreadState.Reason.STALE_RESOURCES) {
        // All free-run replacements are still private; reject the whole batch.
        updateThreadState(thread, threadState, threadReason);
        continue;
      }
      if (!replacements.isEmpty()) {
        var updated = entry.getValue().replaceAll(replacements);
        if (replaceRunSetIfSame(threadId, entry.getValue(), updated)) {
          setChanged();
          if (RecipeLifecyclePublisher.hasListeners(Type.TICK)) {
            var before = entry.getValue().runs().stream().filter(run -> replacements.containsKey(run.runId())).toList();
            var after = before.stream().map(run -> replacements.get(run.runId())).toList();
            RecipeLifecyclePublisher.advanced(this, threadId, before, after);
          }
        } else {
          threadState = FactoryThreadState.State.WAITING;
          threadReason = FactoryThreadState.Reason.STALE_RESOURCES;
        }
      }
      if (runSet(threadId).runs().isEmpty()) {
        threadState = FactoryThreadState.State.IDLE;
        threadReason = FactoryThreadState.Reason.NONE;
      }
      var currentSet = runSet(threadId);
      if (!currentSet.runs().isEmpty() && currentSet.runs().stream().allMatch(FrozenItemRun::pendingOutput)) {
        advancePendingOutput(threadId, currentSet);
        continue;
      }
      var current = scheduler.channels().stream().filter(candidate -> candidate.threadId().equals(threadId)).findFirst()
          .orElse(thread);
      updateThreadState(current, threadState, threadReason);
    }
  }

  private void advancePendingOutput(String threadId, FactoryRunSet completed) {
    var thread = scheduler.channel(threadId);
    if (thread == null
        || completed == null
        || completed.runs().isEmpty()
        || completed.runs().stream().anyMatch(run -> !run.pendingOutput())) {
      return;
    }
    var result = advanceOutput(threadId, completed);
    switch (result) {
      case PLANNING ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.OUTPUT_PLANNING);
      case BLOCKED ->
        updateThreadState(thread, FactoryThreadState.State.BLOCKED, FactoryThreadState.Reason.OUTPUT_BLOCKED);
      case STALE ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STALE_RESOURCES);
      case COMMITTED -> updateThreadState(thread, FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE);
      case UPGRADE_PENDING ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_DATA_PENDING);
      case WAITING_UPGRADE ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_UPGRADE_COST);
      case UPGRADE_ERROR ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_ERROR);
    }
  }

  private void updateResourceRunState(FactoryThreadState thread, FactoryRunResourceCommitter.Result result) {
    switch (result) {
      case ADVANCED -> updateThreadState(thread, FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE);
      case WAITING_ENERGY ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_ENERGY);
      case WAITING_CHEMICAL ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_CHEMICAL);
      case MISSING_CHEMICAL_INPUT ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.MISSING_CHEMICAL_INPUT);
      case WAITING_FLUID ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_FLUID);
      case WAITING_UPGRADE ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.WAITING_UPGRADE_COST);
      case UPGRADE_ERROR ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_ERROR);
      case UPGRADE_PENDING ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.UPGRADE_DATA_PENDING);
      case STALE ->
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.STALE_RESOURCES);
    }
  }

  private void advanceRecipeSearches() {
    if (verificationState() != VerificationState.CONFIRMED || !upgradeSourcesValid) {
      clearSearchState();
      return;
    }
    var catalog = MachineRecipeService.catalogFor(this).orElse(null);
    if (catalog == null) {
      clearSearchState();
      updateWaitingThreads(FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE);
      return;
    }
    var channels = scheduler.channels();
    if (channels.isEmpty()) {
      return;
    }
    int visitBudget = Math.min(MachineConfig.FACTORY_THREADS_PER_TICK.get(), channels.size());
    long now = level.getGameTime();
    var window = nextThreadWindow(visitBudget);
    for (var visited : window) {
      var thread = scheduler.channel(visited.threadId());
      if (thread == null
          || !thread.enabled()
          || !runSet(thread.threadId()).runs().isEmpty()
          || thread.state() == FactoryThreadState.State.INVALID) {
        continue;
      }
      if (!threadStartAllowed(thread.threadId())) {
        removeSearchState(thread.threadId(), now);
        updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.THREAD_LIMIT);
        continue;
      }
      var search = searches.get(thread.threadId());
      if (search != null && !search.dependenciesCurrent()) {
        removeSearchState(thread.threadId(), now);
        search = null;
      }
      if (search == null) {
        if (now < searchRetryAfter.getOrDefault(thread.threadId(), 0L)) {
          continue;
        }
        var allowed = thread.lockedRecipe() == null ? null : Set.of(thread.lockedRecipe().toString());
        try {
          search = RecipeSearch.beginFactory(this, thread.threadId(), catalog, allowed).orElse(null);
        } catch (UpgradeRecipeChecks.FailedException invalid) {
          updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.CHECK_ERROR);
          backoffSearch(thread.threadId(), now);
          recordSearchAttempt(thread.threadId());
          continue;
        }
        if (search == null) {
          var reason = thread.upgradeSnapshot().recipeModifiers()
              .isEmpty() ? FactoryThreadState.Reason.STALE_RESOURCES : FactoryThreadState.Reason.SEARCH_BUDGET;
          updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
          backoffSearch(thread.threadId(), now);
          recordSearchAttempt(thread.threadId());
          continue;
        }
        searches.put(thread.threadId(), search);
      }
      try {
        var result = search.advance(MachineConfig.FACTORY_SEARCH_WORK.get());
        switch (result.status()) {
          case SELECTED -> {
            result.plan().ifPresent(plan -> {
              plans.put(thread.threadId(), plan);
              int resourceLimit = Math.min(
                  effectiveParallelLimit(thread.threadId()),
                  Math.min(plan.maxRepeatableInputs(), plan.maxRepeatableOutputs()));
              int upper = Math.min(resourceLimit, FactoryUpgradeStartCosts.maxRuns(this));
              var nativeRecipe = MachineRecipeService.definitionForStart(this, plan).orElse(null);
              if (nativeRecipe == null) {
                removeSearchState(thread.threadId(), now);
                backoffSearch(thread.threadId(), now);
                return;
              }
              int maxInstances = upper == 0 ? 0 : plan
                  .prepareParallel(nativeRecipe, upper, MachineConfig.FACTORY_SEARCH_WORK.get());
              if (maxInstances < 0) {
                updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.SEARCH_BUDGET);
                return;
              }
              var startResult = maxInstances == 0
                  && resourceLimit > 0 ? FactoryRecipeStarter.Result.UPGRADE_COST_MISSING : FactoryRecipeStarter.Result.STALE;
              if (maxInstances > 1) {
                var batch = Collections.nCopies(maxInstances, plan);
                var parallelResult = FactoryParallelStarter.commit(this, thread.threadId(), batch);
                startResult = switch (parallelResult) {
                  case STARTED -> FactoryRecipeStarter.Result.STARTED;
                  case CHECK_REJECTED -> FactoryRecipeStarter.Result.CHECK_REJECTED;
                  case CHECK_PENDING -> FactoryRecipeStarter.Result.CHECK_PENDING;
                  case CHECK_ERROR -> FactoryRecipeStarter.Result.CHECK_ERROR;
                  case UPGRADE_COST_MISSING -> FactoryRecipeStarter.Result.UPGRADE_COST_MISSING;
                  default -> FactoryRecipeStarter.Result.STALE;
                };
              } else if (maxInstances == 1) {
                startResult = FactoryRecipeStarter.start(this, thread.threadId(), plan);
              }
              if (startResult == FactoryRecipeStarter.Result.STARTED) {
                updateThreadState(thread, FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE);
                resetSearchRetry(thread.threadId(), now);
              } else {
                var reason = switch (startResult) {
                  case CHECK_REJECTED -> FactoryThreadState.Reason.NO_RECIPE;
                  case CHECK_PENDING -> FactoryThreadState.Reason.SEARCH_BUDGET;
                  case CHECK_ERROR -> FactoryThreadState.Reason.CHECK_ERROR;
                  case UPGRADE_COST_MISSING -> FactoryThreadState.Reason.UPGRADE_COST_MISSING;
                  default -> FactoryThreadState.Reason.STALE_RESOURCES;
                };
                updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
                if (startResult != FactoryRecipeStarter.Result.CHECK_PENDING) {
                  removeSearchState(thread.threadId(), now);
                  backoffSearch(thread.threadId(), now);
                }
              }
            });
          }
          case BUDGET_EXHAUSTED ->
            updateThreadState(thread, FactoryThreadState.State.WAITING, FactoryThreadState.Reason.SEARCH_BUDGET);
          case NO_MATCH -> {
            var missing = search.missingResource();
            FactoryThreadState.Reason reason;
            if (missing.isPresent()) {
              reason = RecipeResourceRequirements.factoryReason(missing.orElseThrow());
            } else if (thread.lockedRecipe() == null) {
              reason = FactoryThreadState.Reason.NO_RECIPE;
            } else {
              reason = FactoryThreadState.Reason.INPUT_MISSING;
            }
            updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
            removeSearchState(thread.threadId(), now);
            backoffSearch(thread.threadId(), now);
          }
          default -> {
          }
        }
      } catch (RuntimeException failure) {
        removeSearchState(thread.threadId(), now);
        backoffSearch(thread.threadId(), now);
        var reason = failure instanceof UpgradeRecipeChecks.StaleException ? FactoryThreadState.Reason.STALE_RESOURCES : FactoryThreadState.Reason.CHECK_ERROR;
        updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
      }
      recordSearchAttempt(thread.threadId());
    }
  }

  private List<FactoryThreadState> nextThreadWindow(int budget) {
    int previousCursor = scheduler.cursor();
    var previousOrder = scheduler.roundOrder();
    var window = scheduler.visitWindow(budget);
    if (previousCursor != scheduler.cursor() || !previousOrder.equals(scheduler.roundOrder())) {
      setChanged();
    }

    return window;
  }

  private void recordSearchAttempt(String threadId) {
    if (scheduler.recordAttempt(threadId, !runSet(threadId).runs().isEmpty())) {
      setChanged();
    }
  }

  private void clearSearchState() {
    searches.clear();
    plans.clear();
    searchRetryAfter.clear();
    searchRetryInterval.clear();
  }

  private void removeSearchState(String threadId, long now) {
    searches.remove(threadId);
    plans.remove(threadId);
    resetSearchRetry(threadId, now);
  }

  private void resetSearchRetry(String threadId, long now) {
    searchRetryInterval.put(threadId, SEARCH_RETRY_INITIAL);
    searchRetryAfter.put(threadId, now);
  }

  private void backoffSearch(String threadId, long now) {
    int interval = searchRetryInterval.getOrDefault(threadId, SEARCH_RETRY_INITIAL);
    searchRetryAfter.put(threadId, now + interval);
    searchRetryInterval.put(threadId, Math.min(SEARCH_RETRY_MAX, interval << 1));
  }

  private void updateWaitingThreads(FactoryThreadState.Reason reason) {
    var channels = scheduler.channels();
    if (channels.isEmpty()) {
      return;
    }
    int visitBudget = Math.min(MachineConfig.FACTORY_THREADS_PER_TICK.get(), channels.size());
    for (var thread : nextThreadWindow(visitBudget)) {
      if (thread.enabled() && thread.state() != FactoryThreadState.State.RUNNING) {
        updateThreadState(thread, FactoryThreadState.State.WAITING, reason);
      }
    }
  }

  private void updateThreadState(FactoryThreadState previous, FactoryThreadState.State state, FactoryThreadState.Reason reason) {
    var current = scheduler.channel(previous.threadId());
    if (current == null) {
      return;
    }
    var next = current.withState(state, reason);
    if (next != current) {
      scheduler = scheduler.replace(next);
      setChanged();
    }
  }

  private void refreshThreadGates() {
    var state = verificationState();
    var reason = switch (state) {
      case CONFIRMED -> FactoryThreadState.Reason.NONE;
      case PENDING -> FactoryThreadState.Reason.STRUCTURE_PENDING;
      case INVALID ->
        persistentStateValid ? FactoryThreadState.Reason.STRUCTURE_INVALID : FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE;
    };
    var target = state == VerificationState.CONFIRMED ? FactoryThreadState.State.IDLE : state == VerificationState.PENDING ? FactoryThreadState.State.WAITING : FactoryThreadState.State.INVALID;
    var next = scheduler;
    for (var thread : scheduler.channels()) {
      if (thread.state() == FactoryThreadState.State.RUNNING) {
        continue;
      }
      if (state == VerificationState.CONFIRMED
          && thread.reason() != FactoryThreadState.Reason.STRUCTURE_PENDING
          && thread.reason() != FactoryThreadState.Reason.STRUCTURE_INVALID
          && thread.reason() != FactoryThreadState.Reason.CONTROLLER_UNAVAILABLE) {
        continue;
      }
      next = next.replace(thread.withState(target, reason));
    }
    if (next != scheduler) {
      scheduler = next;
      setChanged();
    }
  }

  private void refreshLockedRecipeGates(ServerLevel server) {
    if (verificationState() != VerificationState.CONFIRMED) {
      return;
    }
    var next = scheduler;
    for (var thread : scheduler.channels()) {
      if (thread.lockedRecipe() == null || thread.state() == FactoryThreadState.State.RUNNING) {
        continue;
      }
      var belongs = MachineRecipeService.recipeBelongsTo(server.getServer(), thread.machineId(), thread.lockedRecipe());
      // A valid lock only clears this gate's own error, not resource/run status.
      if (belongs.isEmpty() || belongs.get() && thread.reason() != FactoryThreadState.Reason.LOCKED_RECIPE_MISSING) {
        continue;
      }
      var desired = belongs.get() ? thread
          .withState(FactoryThreadState.State.IDLE, FactoryThreadState.Reason.NONE) : thread
              .withState(FactoryThreadState.State.INVALID, FactoryThreadState.Reason.LOCKED_RECIPE_MISSING);
      next = next.replace(desired);
    }
    if (next != scheduler) {
      scheduler = next;
      setChanged();
    }
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
    tag.put("controller_blueprint", blueprintInventory.save(registries));
    tag.put(
        ControllerScriptState.NBT_KEY,
        unsupportedScriptData == null ? controllerScriptState.save() : unsupportedScriptData.copy());
    if (!persistentStateValid && opaqueFactoryState != null) {
      tag.merge(opaqueFactoryState);
      return;
    }
    tag.putInt("mmcece_factory_schema", FACTORY_SCHEMA);
    tag.putBoolean("upgrade_sources_managed", upgradeSourcesManaged);
    tag.putBoolean("threads_initialized", threadsInitialized);
    tag.putLong("configuration_revision", configurationRevision);
    tag.putString("machine", machineId.toString());
    tag.putInt("scheduler_cursor", scheduler.cursor());
    var round = new ListTag();
    for (var id : scheduler.roundOrder()) {
      round.add(StringTag.valueOf(id));
    }
    tag.put("scheduler_round", round);
    var waiting = new CompoundTag();
    scheduler.waitingAges().forEach(waiting::putInt);
    tag.put("scheduler_waiting", waiting);
    tag.put("threads", FactoryThreadStateCodec.write(scheduler.channels()));
    var savedRuns = new ListTag();
    for (var entry : runSets.entrySet()) {
      var saved = new CompoundTag();
      saved.putString("thread", entry.getKey());
      saved.put("set", FactoryRunSetCodec.write(entry.getValue()));
      savedRuns.add(saved);
    }
    tag.put("runs", savedRuns);
  }

  @Override
  protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
    if (level instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Factory load");
      if (UpgradeLifecyclePublisher.isDispatchingAny()
          || SmartInterfaceEventPublisher.isDispatching()
          || !startingThreads.isEmpty()) {
        throw new IllegalStateException("Factory load during an active upgrade callback or startup");
      }
    }
    runtimeUpgrades.clear();
    super.loadAdditional(tag, registries);
    ownership = ControllerOwnershipState.load(tag);
    commandState = ControllerCommandState.load(tag.get(ControllerCommandState.NBT_KEY));
    controllerScriptState = ControllerScriptState.empty();
    unsupportedScriptData = null;
    try {
      controllerScriptState = ControllerScriptState.load(tag.get(ControllerScriptState.NBT_KEY));
    } catch (RuntimeException invalidScriptData) {
      unsupportedScriptData = tag.get(ControllerScriptState.NBT_KEY).copy();
    }
    genericDiscovery.clear();
    blueprintInventory.load(registries, tag.get("controller_blueprint"));
    clearUpgradeDiscovery();
    outputEngine.invalidate();
    persistentStateValid = false;
    threadsInitialized = false;
    configurationRevision = 0;
    scheduler = new FactoryScheduler(List.of());
    opaqueFactoryState = null;
    upgradeBusSources.clear();
    appliedUpgradeSourceView = null;
    pendingUpgradeSnapshot = null;
    upgradeSourceEpoch = 0;
    upgradeSourcesManaged = false;
    upgradeSourcesValid = true;
    clearSearchState();
    runSets.clear();
    startingThreads.clear();
    parallelAdmissionLimits.clear();
    if (!tag.contains("mmcece_factory_schema", Tag.TAG_INT)
        || tag.getInt("mmcece_factory_schema") != FACTORY_SCHEMA
        || !tag.contains("upgrade_sources_managed", Tag.TAG_BYTE)
        || tag.getByte("upgrade_sources_managed") < 0
        || tag.getByte("upgrade_sources_managed") > 1
        || !tag.contains("threads_initialized", Tag.TAG_BYTE)
        || tag.getByte("threads_initialized") < 0
        || tag.getByte("threads_initialized") > 1
        || !tag.contains("configuration_revision", Tag.TAG_LONG)
        || tag.getLong("configuration_revision") < 0
        || !tag.contains("scheduler_cursor", Tag.TAG_INT)
        || !tag.contains("scheduler_round", Tag.TAG_LIST)
        || !tag.contains("scheduler_waiting", Tag.TAG_COMPOUND)
        || !tag.contains("machine", Tag.TAG_STRING)
        || !tag.contains("threads", Tag.TAG_LIST)
        || !tag.contains("runs", Tag.TAG_LIST)) {
      scheduler = new FactoryScheduler(List.of());
      preserveOpaqueFactoryState(tag);
      return;
    }
    try {
      upgradeSourcesManaged = tag.getBoolean("upgrade_sources_managed");
      threadsInitialized = tag.getBoolean("threads_initialized");
      configurationRevision = tag.getLong("configuration_revision");
      machineId = ResourceLocation.parse(tag.getString("machine"));
      if (!(tag.get("threads") instanceof ListTag threadsTag)
          || !threadsTag.isEmpty() && threadsTag.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException("Invalid factory thread list type");
      }
      var loaded = FactoryThreadStateCodec.read(threadsTag);
      int cursor = tag.getInt("scheduler_cursor");
      var roundTag = (ListTag) tag.get("scheduler_round");
      if (roundTag.size() > FactoryThreadStateCodec.MAX_THREADS
          || !roundTag.isEmpty() && roundTag.getElementType() != Tag.TAG_STRING) {
        throw new IllegalArgumentException("Invalid factory round order");
      }
      var round = new ArrayList<String>(roundTag.size());
      for (int i = 0; i < roundTag.size(); i++) {
        round.add(roundTag.getString(i));
      }
      var waitingTag = tag.getCompound("scheduler_waiting");
      if (waitingTag.size() > FactoryThreadStateCodec.MAX_THREADS) {
        throw new IllegalArgumentException("Too many factory waiting entries");
      }
      var waiting = new HashMap<String, Integer>();
      for (var key : waitingTag.getAllKeys()) {
        if (!waitingTag.contains(key, Tag.TAG_INT)) {
          throw new IllegalArgumentException("Invalid factory waiting age type");
        }
        waiting.put(key, waitingTag.getInt(key));
      }
      scheduler = new FactoryScheduler(loaded, cursor, round, waiting);
      if (!(tag.get("runs") instanceof ListTag runsTag)
          || !runsTag.isEmpty() && runsTag.getElementType() != Tag.TAG_COMPOUND) {
        throw new IllegalArgumentException("Invalid factory run list type");
      }
      ListTag savedRuns = runsTag;
      if (savedRuns.size() > FactoryThreadStateCodec.MAX_THREADS) {
        throw new IllegalArgumentException("Too many factory thread run entries");
      }
      if (!threadsInitialized && (!loaded.isEmpty() || !savedRuns.isEmpty())) {
        throw new IllegalArgumentException("Uninitialized factory contains threads or paid runs");
      }
      for (int i = 0; i < savedRuns.size(); i++) {
        var saved = savedRuns.getCompound(i);
        if (!saved.contains("thread", Tag.TAG_STRING) || !saved.contains("set", Tag.TAG_LIST)) {
          throw new IllegalArgumentException("Invalid factory run entry");
        }
        String threadId = saved.getString("thread");
        var runSet = FactoryRunSetCodec.read(saved.getList("set", Tag.TAG_COMPOUND), registries);
        if (runSets.put(threadId, runSet) != null) {
          throw new IllegalArgumentException("Duplicate factory run thread");
        }
        if (runSet.runs().isEmpty()) {
          throw new IllegalArgumentException("Empty factory run set entry");
        }
        var thread = scheduler.channels().stream().filter(candidate -> candidate.threadId().equals(threadId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Run refers to missing factory thread"));
        scheduler = scheduler
            .replace(thread.withState(FactoryThreadState.State.RUNNING, FactoryThreadState.Reason.NONE));
      }
      persistentStateValid = true;
    } catch (RuntimeException exception) {
      scheduler = new FactoryScheduler(List.of());
      threadsInitialized = false;
      configurationRevision = 0;
      runSets.clear();
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

    return root;
  }
}
