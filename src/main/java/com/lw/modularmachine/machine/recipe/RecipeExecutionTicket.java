package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.network.NetworkPortResource;
import com.lw.modularmachine.machine.network.pattern.PatternContainerRef;
import com.lw.modularmachine.machine.network.pattern.PatternResourceKey;
import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.component.ChemicalStackData;
import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.api.upgrade.FactoryUpgradeSnapshot;
import com.lw.modularmachine.machine.parallel.ParallelCapacity.Snapshot;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ephemeral server-thread resource ticket, not a serializable run or an
 * authority to write.
 */
public final class RecipeExecutionTicket {
  private final MachineControllerBlockEntity controller;
  private final StructureService.StructureSubject owner;
  private final ServerLevel level;
  private final StructureService.Registration registration;
  private final long structureRevision;
  private final long scriptRevision;
  private final long capturedTick;
  private final List<PortSnapshot> ports;
  private final CaptureMode mode;
  private final List<SmartSnapshot> interfaces;
  private final int inputGroup;
  private final List<PortSnapshot> selectedPorts;
  private final List<PatternInputSource> patternSources;
  private final PatternInputSource patternSource;
  private final Snapshot parallel;
  private final FactoryUpgradeSnapshot ordinaryUpgrades;

  private RecipeExecutionTicket(StructureService.StructureSubject owner, ServerLevel level, StructureService.Registration registration, List<PortSnapshot> ports, CaptureMode mode, List<SmartSnapshot> interfaces) {
    this.owner = owner;
    controller = owner instanceof MachineControllerBlockEntity ordinary ? ordinary : null;
    this.level = level;
    this.registration = registration;
    structureRevision = registration.verifier.revision();
    scriptRevision = mode == CaptureMode.ALL ? MachineControllerScriptDataService.revision(owner) : -1;
    capturedTick = level.getGameTime();
    this.ports = List.copyOf(ports);
    this.mode = mode;
    this.interfaces = List.copyOf(interfaces);
    ordinaryUpgrades = mode == CaptureMode.ALL && controller != null ? controller.upgradeSnapshot() : null;
    parallel = mode == CaptureMode.ALL ? ParallelCapacity.capture(registration, level) : null;
    inputGroup = InputGroup.SHARED;
    patternSource = null;
    patternSources = mode == CaptureMode.ALL ? PatternProductionService.sources(owner) : List.of();
    selectedPorts = selectPorts(ports, inputGroup);
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, int inputGroup, PatternInputSource patternSource) {
    this.inputGroup = InputGroup.require(inputGroup);
    controller = source.controller;
    owner = source.owner;
    level = source.level;
    registration = source.registration;
    structureRevision = source.structureRevision;
    scriptRevision = source.scriptRevision;
    capturedTick = source.capturedTick;
    ports = source.ports;
    mode = source.mode;
    interfaces = source.interfaces;
    parallel = source.parallel;
    ordinaryUpgrades = source.ordinaryUpgrades;
    patternSources = source.patternSources;
    this.patternSource = patternSource;
    var selected = new ArrayList<PortSnapshot>();
    if (patternSource != null) {
      selected.addAll(patternPorts(patternSource));
    }
    selected.addAll(selectPorts(ports, inputGroup));
    selectedPorts = List.copyOf(selected);
  }

  private static List<PortSnapshot> selectPorts(List<PortSnapshot> ports, int group) {
    var selected = new ArrayList<PortSnapshot>();
    if (group > 0) {
      for (var port : ports) {
        if (port.inputGroup() == group) {
          selected.add(port);
        }
      }
    }
    for (var port : ports) {
      if (port.inputGroup() == InputGroup.SHARED) {
        selected.add(port);
      }
    }

    return List.copyOf(selected);
  }

  public RecipeExecutionTicket withInputGroup(int group) {
    requireServerThread();

    return inputGroup == group ? this : new RecipeExecutionTicket(this, group, patternSource);
  }

  public PatternInputSource patternSource() {
    return patternSource;
  }

  public RecipeExecutionTicket withPatternSource(PatternInputSource source) {
    return new RecipeExecutionTicket(this, inputGroup, source);
  }

  public Optional<RecipeExecutionTicket> forRun(FrozenItemRun run) {
    return PatternRunSources.forRun(this, run);
  }

  public List<RecipeExecutionTicket> searchContexts() {
    var result = new ArrayList<RecipeExecutionTicket>();
    for (var source : patternSources) {
      for (int group : inputGroups()) {
        result.add(withInputGroup(group).withPatternSource(source));
      }
    }
    for (int group : inputGroups()) {
      result.add(withInputGroup(group));
    }
    return List.copyOf(result);
  }

  public int inputGroup() {
    return inputGroup;
  }

  Map<String, Integer> dynamicLengths() {
    return registration.dynamicLengths();
  }

  public int parallelLimit() {
    int hardware = parallel == null ? 1 : parallel.limit();
    if (ordinaryUpgrades == null) {
      return hardware;
    }

    return (int) Math.min(
        registration.pattern.definition.maxParallelism(),
        Math.min(Integer.MAX_VALUE, (long) hardware + ordinaryUpgrades.parallelLimit() - 1L));
  }

  FactoryUpgradeSnapshot ordinaryUpgrades() {
    return ordinaryUpgrades;
  }

  List<PortSnapshot> allPorts() {
    var result = new ArrayList<>(ports);
    selectedPorts.stream().filter(PortSnapshot::patternInput).forEach(result::add);
    return List.copyOf(result);
  }

  public List<Integer> inputGroups() {
    var groups = new TreeSet<Integer>();
    groups.add(InputGroup.SHARED);
    ports.stream()
        .filter(
            port -> port.kind() == PortKind.ITEM_INPUT
                || port.kind() == PortKind.FLUID_INPUT
                || port.kind() == PortKind.CHEMICAL_INPUT)
        .forEach(port -> groups.add(port.inputGroup()));

    return List.copyOf(groups);
  }

  boolean inputGroupsMatch(Map<String, Integer> expected) {
    requireServerThread();

    return ports.stream().allMatch(port -> expected.getOrDefault(port.logicalPartId(), 0) == port.inputGroup());
  }

  /**
   * No capability callbacks, writes, chunk loads, or reduction of
   * component-bearing stacks to IDs.
   */
  public static Optional<RecipeExecutionTicket> capture(MachineControllerBlockEntity controller) {
    return capture((StructureService.StructureSubject) controller, CaptureMode.ALL);
  }

  public static Optional<RecipeExecutionTicket> capture(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.ALL);
  }

  static Optional<RecipeExecutionTicket> captureOutputs(MachineControllerBlockEntity controller) {
    return capture(controller, CaptureMode.OUTPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureOutputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.OUTPUTS);
  }

  static Optional<RecipeExecutionTicket> captureEnergyInputs(MachineControllerBlockEntity controller) {
    return capture(controller, CaptureMode.ENERGY_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureEnergyInputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.ENERGY_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureEnergyOutputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.ENERGY_OUTPUTS);
  }

  static Optional<RecipeExecutionTicket> captureFluidInputs(MachineControllerBlockEntity controller) {
    return capture(controller, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureFluidInputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureChemicalInputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.CHEMICAL_INPUTS);
  }

  private static Optional<RecipeExecutionTicket> capture(StructureService.StructureSubject owner, CaptureMode mode) {
    if (mode == CaptureMode.ALL
        && owner instanceof MachineControllerBlockEntity ordinary
        && owner.structureLevel() instanceof ServerLevel server) {
      ServerThreadGuard.require(server, "Ordinary upgrade source capture");
      ordinary.refreshUpgradeSources();
    }
    if (!(owner.structureLevel() instanceof ServerLevel level)) {
      return Optional.empty();
    }
    ServerThreadGuard.require(level, "Recipe resource access");
    var registration = owner.structureRegistration();
    if (registration == null
        || !registration.canCommit(level)
        || owner instanceof MachineControllerBlockEntity ordinary && !ordinary.canCommitProduction()) {
      return Optional.empty();
    }
    var ports = new ArrayList<PortSnapshot>();
    var interfaces = new ArrayList<SmartSnapshot>();
    var positions = new HashSet<BlockPos>();
    var definition = registration.pattern.definition;
    for (int index = 0; index < definition.parts().size(); index++) {
      var part = definition.parts().get(index);
      if (mode == CaptureMode.ALL && !part.interfaceType().isEmpty()) {
        var position = registration.pattern.position(index);
        if (!level.hasChunkAt(position)
            || !(level.getBlockEntity(position) instanceof SmartInterfaceBlockEntity smart)) {
          return Optional.empty();
        }
        var binding = smart.read(owner, registration.interfaceType(index)).orElse(null);
        if (binding == null) {
          return Optional.empty();
        }
        interfaces.add(new SmartSnapshot(position, smart, binding));
      }
      if (part.rule().port() == null) {
        continue;
      }
      if (mode == CaptureMode.OUTPUTS
          && part.rule().port() != PortKind.ITEM_OUTPUT
          && part.rule().port() != PortKind.FLUID_OUTPUT
          && part.rule().port() != PortKind.CHEMICAL_OUTPUT
          || mode == CaptureMode.ENERGY_INPUTS && part.rule().port() != PortKind.ENERGY_INPUT
          || mode == CaptureMode.ENERGY_OUTPUTS && part.rule().port() != PortKind.ENERGY_OUTPUT
          || mode == CaptureMode.FLUID_INPUTS && part.rule().port() != PortKind.FLUID_INPUT
          || mode == CaptureMode.CHEMICAL_INPUTS && part.rule().port() != PortKind.CHEMICAL_INPUT) {
        continue;
      }
      var position = registration.pattern.position(index);
      if (!positions.add(position)
          || !level.hasChunkAt(position)
          || !(level.getBlockEntity(position) instanceof MachinePortBlockEntity port)
          || !port.schemaSupported()
          || port.kind() != part.rule().port()) {
        return Optional.empty();
      }
      if (port.resourceTransactionInProgress()) {
        return Optional.empty();
      }
      int tankCount = switch (port.kind().resource()) {
        case FLUID -> port.fluidTankCount();
        case CHEMICAL -> port.chemicalTankCount();
        default -> 1;
      };
      for (int tank = 0; tank < tankCount; tank++) {
        ports.add(new PortSnapshot(part.id(), position, port, level, part.selector(), tank, tankCount));
      }
    }
    ports.sort(Comparator.comparing(PortSnapshot::logicalPartId).thenComparingInt(PortSnapshot::tankIndex));
    var ticket = new RecipeExecutionTicket(owner, level, registration, ports, mode, interfaces);
    return ticket.matchesLive() ? Optional.of(ticket) : Optional.empty();
  }

  /**
   * Views contain defensive copies; actual BlockEntity identities stay private to
   * this ticket.
   */
  public List<PortSnapshot> ports() {
    return selectedPorts;
  }

  public boolean interfacesMatch(List<SmartNumberRequirement> requirements) {
    requireServerThread();
    for (var requirement : requirements) {
      boolean found = interfaces.stream().anyMatch(
          snapshot -> snapshot.binding().type().equals(requirement.type())
              && requirement.matches(snapshot.binding().value()));
      if (!found) {
        return false;
      }
    }

    return true;
  }

  private record SmartSnapshot(BlockPos position, SmartInterfaceBlockEntity identity, SmartInterfaceBinding binding) {
  }

  private enum CaptureMode {
    ALL,
    OUTPUTS,
    ENERGY_INPUTS,
    ENERGY_OUTPUTS,
    FLUID_INPUTS,
    CHEMICAL_INPUTS
  }

  public long structureRevision() {
    return structureRevision;
  }

  public long definitionEpoch() {
    return registration.epoch();
  }

  public long generation() {
    return registration.generation();
  }

  MachineControllerBlockEntity controller() {
    return controller;
  }

  @ApiStatus.Internal
  public StructureService.StructureSubject owner() {
    return owner;
  }

  @ApiStatus.Internal
  public ServerLevel level() {
    return level;
  }

  boolean revalidateStructure() {
    return owner.revalidateStructure(registration);
  }

  @ApiStatus.Internal
  public void requireServerThread() {
    ServerThreadGuard.require(level, "Recipe resource access");
  }

  boolean matchesLive() {
    ServerThreadGuard.require(level, "Recipe resource access");
    // FE tick limits are not resource revisions. This initial ticket is same-tick
    // only.
    return capturedTick == level.getGameTime() && matchesDependencies();
  }

  /**
   * Recapture current-tick authority only when all original identities, revisions
   * and contents still match.
   */
  public Optional<RecipeExecutionTicket> refreshIfUnchanged() {
    ServerThreadGuard.require(level, "Recipe resource access");
    return matchesDependencies() ? capture(owner, mode)
        .map(ticket -> ticket.withInputGroup(inputGroup).withPatternSource(patternSource)) : Optional.empty();
  }

  @ApiStatus.Internal
  public boolean dependenciesCurrent() {
    ServerThreadGuard.require(level, "Recipe resource access");
    return matchesDependencies();
  }

  private boolean matchesDependencies() {
    if (patternSource != null && !patternSource.current()) {
      return false;
    }
    if (patternSource == null && patternSources.stream().anyMatch(source -> !source.current())) {
      return false;
    }
    if (scriptRevision >= 0 && MachineControllerScriptDataService.revision(owner) != scriptRevision) {
      return false;
    }
    if (ordinaryUpgrades != null && !controller.upgradeSnapshotCurrent(ordinaryUpgrades)) {
      return false;
    }
    if (mode == CaptureMode.ALL
        && owner instanceof FactoryControllerBlockEntity factory
        && !factory.upgradeRestrictionsCurrent()) {
      return false;
    }
    if (parallel != null && !parallel.current()) {
      return false;
    }
    if (owner instanceof MachineControllerBlockEntity ordinary) {
      if (ordinary.registration() != registration
          || registration.verifier.revision() != structureRevision
          || !ordinary.canCommitProduction()) {
        return false;
      }
    } else if (owner.structureLevel() != level || !registration.canCommit(level)) {
      return false;
    }
    for (var snapshot : interfaces) {
      if (!level.hasChunkAt(snapshot.position())
          || level.getBlockEntity(snapshot.position()) != snapshot.identity()
          || snapshot.identity().read(owner, snapshot.binding().type()).orElse(null) != snapshot.binding()) {
        return false;
      }
    }
    for (var port : ports) {
      if (!port.matches(level)) {
        return false;
      }
    }
    // A component comparator may reenter. Recheck plain identity/version fences
    // after all content
    // reads.
    for (var port : ports) {
      if (!port.sameLiveStamp(level)) {
        return false;
      }
    }
    boolean qualified;
    if (owner instanceof MachineControllerBlockEntity ordinary) {
      qualified = ordinary.registration() == registration
          && registration.verifier.revision() == structureRevision
          && ordinary.canCommitProduction();
    } else {
      qualified = owner.revalidateStructure(registration) && registration.verifier.revision() == structureRevision;
    }
    if (!qualified) {
      return false;
    }
    for (var snapshot : interfaces) {
      if (!level.hasChunkAt(snapshot.position())
          || level.getBlockEntity(snapshot.position()) != snapshot.identity()
          || !snapshot.identity().matchesBinding(snapshot.binding())) {
        return false;
      }
    }

    return (scriptRevision < 0 || MachineControllerScriptDataService.revision(owner) == scriptRevision)
        && (ordinaryUpgrades == null || controller.upgradeSnapshotCurrent(ordinaryUpgrades))
        && (parallel == null || parallel.current())
        && (mode != CaptureMode.ALL
            || !(owner instanceof FactoryControllerBlockEntity factory)
            || factory.upgradeRestrictionsCurrent());
  }

  private List<PortSnapshot> patternPorts(PatternInputSource source) {
    var result = new ArrayList<PortSnapshot>();
    var itemStacks = new ArrayList<ItemStack>();
    var amounts = new ArrayList<Long>();
    var itemKeys = new ArrayList<PatternResourceKey>();
    int fluidIndex = 0;
    int chemicalIndex = 0;
    for (var entry : source.resources().entrySet()) {
      var resource = entry.getKey().resource(level.registryAccess());
      switch (resource) {
        case NetworkPortResource.Item item -> {
          itemStacks.add(item.stack());
          amounts.add(entry.getValue());
          itemKeys.add(entry.getKey());
        }
        case NetworkPortResource.Fluid fluid -> result.add(
            new PortSnapshot(
                source,
                PortKind.FLUID_INPUT,
                fluidIndex++,
                false,
                List.of(),
                List.of(),
                List.of(entry.getKey()),
                fluid.stack().copyWithAmount((int) Math.min(Integer.MAX_VALUE, entry.getValue())),
                ChemicalStackData.EMPTY));
        case NetworkPortResource.Chemical chemical -> result.add(
            new PortSnapshot(
                source,
                PortKind.CHEMICAL_INPUT,
                chemicalIndex++,
                false,
                List.of(),
                List.of(),
                List.of(entry.getKey()),
                FluidStack.EMPTY,
                new ChemicalStackData(chemical.id(), entry.getValue())));
      }
    }
    if (mode == CaptureMode.ALL) {
      result.add(
          new PortSnapshot(
              source,
              PortKind.ITEM_INPUT,
              0,
              false,
              itemStacks,
              amounts,
              itemKeys,
              FluidStack.EMPTY,
              ChemicalStackData.EMPTY));
      var stacks = new ArrayList<ItemStack>();
      var counts = new ArrayList<Long>();
      var inventory = source.provider().independentItems();
      for (int slot = 0; slot < inventory.getSlots(); slot++) {
        var stack = ItemStackSnapshots.detached(inventory.getStackInSlot(slot), level.registryAccess());
        stacks.add(stack);
        counts.add((long) stack.getCount());
      }
      result.add(
          new PortSnapshot(
              source,
              PortKind.ITEM_INPUT,
              0,
              true,
              stacks,
              counts,
              List.of(),
              FluidStack.EMPTY,
              ChemicalStackData.EMPTY));
    }
    result.add(
        new PortSnapshot(
            source,
            PortKind.FLUID_INPUT,
            0,
            true,
            List.of(),
            List.of(),
            List.of(),
            source.provider().independentFluid().getFluid(),
            ChemicalStackData.EMPTY));
    return result.stream()
        .filter(
            port -> mode == CaptureMode.ALL
                || mode == CaptureMode.FLUID_INPUTS && port.kind() == PortKind.FLUID_INPUT
                || mode == CaptureMode.CHEMICAL_INPUTS && port.kind() == PortKind.CHEMICAL_INPUT)
        .toList();
  }

  public static final class PortSnapshot {
    private final String partId;
    private final String logicalPartId;
    private final int tankIndex;
    private final int tankCount;
    private final boolean bulkItems;
    private final BlockPos position;
    private final MachinePortBlockEntity identity;
    private final ServerLevel level;
    private final PortKind kind;
    private final long revision;
    private final List<ItemStack> items;
    private final List<Long> itemAmounts;
    private final PatternInputSource pattern;
    private final List<PatternResourceKey> patternKeys;
    private final boolean independent;
    private final List<Integer> limits;
    private final FluidStack fluid;
    private final ChemicalStackData chemical;
    private final long energy;
    private final long energyExtractable;
    private final long energyReceivable;
    private final long capacity;
    private final long transferLimit;
    private final String selector;
    private final int inputGroup;

    private PortSnapshot(String logicalPartId, BlockPos position, MachinePortBlockEntity port, ServerLevel level, String selector, int tankIndex, int tankCount) {
      this.logicalPartId = logicalPartId;
      pattern = null;
      patternKeys = List.of();
      independent = false;
      // Encode every transient address, including single tanks and item buses.
      // Length framing makes arbitrary user IDs unambiguous; only logical IDs
      // persist.
      partId = logicalPartId.length() + ":" + logicalPartId + ":" + tankIndex;
      this.tankIndex = tankIndex;
      this.tankCount = tankCount;
      bulkItems = port.bulkItems();
      this.selector = ResourceSelector.require(selector);
      inputGroup = port.inputGroup();
      this.position = position.immutable();
      identity = port;
      this.level = level;
      kind = port.kind();
      revision = port.resourceRevision();
      var stacks = new ArrayList<ItemStack>();
      var slotLimits = new ArrayList<Integer>();
      if (kind.resource() == PortKind.Resource.ITEM) {
        for (int slot = 0; slot < port.inventory().getSlots(); slot++) {
          stacks.add(ItemStackSnapshots.detached(port.inventory().getStackInSlot(slot), level.registryAccess()));
          slotLimits.add(port.inventory().getSlotLimit(slot));
        }
      }
      items = List.copyOf(stacks);
      itemAmounts = stacks.stream().map(stack -> (long) stack.getCount()).toList();
      limits = List.copyOf(slotLimits);
      fluid = kind.resource() == PortKind.Resource.FLUID ? port.fluidTank(tankIndex).getFluid()
          .copy() : FluidStack.EMPTY;
      chemical = kind.resource() == PortKind.Resource.CHEMICAL ? port.chemicalTank(tankIndex)
          .getStack() : ChemicalStackData.EMPTY;
      energy = kind.resource() == PortKind.Resource.ENERGY ? port.energy().stored() : 0;
      energyExtractable = kind == PortKind.ENERGY_INPUT ? port.energy().extractableNow() : 0;
      energyReceivable = kind == PortKind.ENERGY_OUTPUT ? port.energy().receivableNow() : 0;
      capacity = switch (kind.resource()) {
        case ITEM -> 0;
        case FLUID -> port.fluidTank(tankIndex).getCapacity();
        case CHEMICAL -> port.chemicalTank(tankIndex).getCapacity();
        case ENERGY -> port.energy().capacity();
      };
      transferLimit = kind.resource() == PortKind.Resource.ENERGY ? port.energy().transferLimit() : 0;
    }

    private PortSnapshot(PatternInputSource source, PortKind kind, int index, boolean independent, List<ItemStack> items, List<Long> amounts, List<PatternResourceKey> keys, FluidStack fluid, ChemicalStackData chemical) {
      pattern = source;
      this.independent = independent;
      patternKeys = List.copyOf(keys);
      identity = null;
      level = (ServerLevel) source.provider().getLevel();
      position = source.provider().getBlockPos();
      this.kind = kind;
      selector = source.selector();
      inputGroup = InputGroup.SHARED;
      revision = source.revision();
      logicalPartId = "pattern:" + source.provider().providerId();
      String scope = independent ? "independent" : source.container() + ":" + source.job();
      partId = logicalPartId + ":" + scope + ":" + kind + ":" + index;
      tankIndex = index;
      tankCount = 1;
      bulkItems = true;
      this.items = List.copyOf(items);
      itemAmounts = List.copyOf(amounts);
      limits = items.stream().map(stack -> Integer.MAX_VALUE).toList();
      this.fluid = fluid.copy();
      this.chemical = chemical;
      energy = 0;
      energyExtractable = 0;
      energyReceivable = 0;
      capacity = kind == PortKind.CHEMICAL_INPUT ? Long.MAX_VALUE : Integer.MAX_VALUE;
      transferLimit = 0;
    }

    public boolean patternInput() {
      return pattern != null;
    }
    public PatternInputSource patternSource() {
      return pattern;
    }
    public boolean independentPatternStorage() {
      return independent;
    }
    public PatternResourceKey patternKey(int slot) {
      return patternKeys.get(slot);
    }
    public long itemAmount(int slot) {
      return itemAmounts.get(slot);
    }
    public long fluidAmount() {
      return patternInput() && !independentPatternStorage() && kind == PortKind.FLUID_INPUT ? pattern.resources()
          .getOrDefault(patternKey(0), 0L) : fluid.getAmount();
    }
    public Object physicalIdentity() {
      if (!patternInput()) {
        return new PhysicalTank(identity, tankIndex);
      }

      return new PatternPhysical(
          pattern.providerId(),
          independent ? null : pattern.container(),
          independent ? null : pattern.job(),
          kind,
          independent ? null : patternKeys.isEmpty() ? null : patternKeys.getFirst());
    }
    private record PatternPhysical(
        UUID provider,
        PatternContainerRef container,
        UUID job,
        PortKind kind,
        PatternResourceKey resource) {
    }

    private record PhysicalTank(MachinePortBlockEntity port, int tank) {
    }

    public String partId() {
      return partId;
    }

    public String logicalPartId() {
      return logicalPartId;
    }

    public int tankIndex() {
      return tankIndex;
    }

    public boolean bulkItems() {
      return bulkItems;
    }

    public String selector() {
      return selector;
    }

    public int inputGroup() {
      return inputGroup;
    }

    public BlockPos position() {
      return position;
    }

    public PortKind kind() {
      return kind;
    }

    public long revision() {
      return revision;
    }

    public List<ItemStack> items() {
      ServerThreadGuard.require(level, "Recipe resource access");
      return items.stream().map(stack -> ItemStackSnapshots.detached(stack, level.registryAccess())).toList();
    }

    public List<Integer> slotLimits() {
      return limits;
    }

    public FluidStack fluid() {
      return fluid.copy();
    }

    public ChemicalStackData chemical() {
      return chemical;
    }

    public long energy() {
      return energy;
    }

    public long energyExtractable() {
      return energyExtractable;
    }

    public long energyReceivable() {
      return energyReceivable;
    }

    public long capacity() {
      return capacity;
    }

    public long transferLimit() {
      return transferLimit;
    }

    MachinePortBlockEntity identity() {
      return identity;
    }

    private boolean sameLiveStamp(ServerLevel level) {
      if (pattern != null) {
        return pattern.current();
      }
      return level.hasChunkAt(position)
          && !identity.isRemoved()
          && identity.getLevel() == level
          && level.getBlockEntity(position) == identity
          && identity.schemaSupported()
          && identity.kind() == kind
          && identity.bulkItems() == bulkItems
          && identity.resourceRevision() == revision
          && identity.inputGroup() == inputGroup;
    }

    private boolean matches(ServerLevel level) {
      if (pattern != null) {
        return pattern.current();
      }
      if (!sameLiveStamp(level) || identity.resourceTransactionInProgress()) {
        return false;
      }
      if (kind.resource() == PortKind.Resource.ITEM) {
        if (identity.inventory().getSlots() != items.size()) {
          return false;
        }
        for (int slot = 0; slot < items.size(); slot++) {
          if (identity.inventory().getSlotLimit(slot) != limits.get(slot)
              || !ItemStack.matches(items.get(slot), identity.inventory().getStackInSlot(slot))) {
            return false;
          }
        }
      }
      if (kind.resource() == PortKind.Resource.FLUID) {
        if (identity.fluidTankCount() != tankCount) {
          return false;
        }
        var actual = identity.fluidTank(tankIndex).getFluid();
        if (identity.fluidTank(tankIndex).getCapacity() != capacity
            || fluid.getAmount() != actual.getAmount()
            || !FluidStack.isSameFluidSameComponents(fluid, actual)) {
          return false;
        }
      }
      if (kind.resource() == PortKind.Resource.CHEMICAL
          && (identity.chemicalTankCount() != tankCount
              || !chemical.equals(identity.chemicalTank(tankIndex).getStack())
              || capacity != identity.chemicalTank(tankIndex).getCapacity())) {
        return false;
      }
      return kind.resource() != PortKind.Resource.ENERGY
          || energy == identity.energy().stored()
              && capacity == identity.energy().capacity()
              && transferLimit == identity.energy().transferLimit();
    }
  }
}
