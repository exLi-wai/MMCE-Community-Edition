package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.PortKind;
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
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

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
  private final long capturedTick;
  private final List<PortSnapshot> ports;
  private final CaptureMode mode;
  private final List<SmartSnapshot> interfaces;
  private final int inputGroup;
  private final List<PortSnapshot> selectedPorts;
  private final Snapshot parallel;
  private final FactoryUpgradeSnapshot ordinaryUpgrades;

  private RecipeExecutionTicket(StructureService.StructureSubject owner, ServerLevel level, StructureService.Registration registration, List<PortSnapshot> ports, CaptureMode mode, List<SmartSnapshot> interfaces) {
    this.owner = owner;
    controller = owner instanceof MachineControllerBlockEntity ordinary ? ordinary : null;
    this.level = level;
    this.registration = registration;
    structureRevision = registration.verifier.revision();
    capturedTick = level.getGameTime();
    this.ports = List.copyOf(ports);
    this.mode = mode;
    this.interfaces = List.copyOf(interfaces);
    ordinaryUpgrades = mode == CaptureMode.ALL && controller != null ? controller.upgradeSnapshot() : null;
    parallel = mode == CaptureMode.ALL ? ParallelCapacity.capture(registration, level) : null;
    inputGroup = InputGroup.SHARED;
    selectedPorts = selectPorts(ports, inputGroup);
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, int inputGroup) {
    this.inputGroup = InputGroup.require(inputGroup);
    controller = source.controller;
    owner = source.owner;
    level = source.level;
    registration = source.registration;
    structureRevision = source.structureRevision;
    capturedTick = source.capturedTick;
    ports = source.ports;
    mode = source.mode;
    interfaces = source.interfaces;
    parallel = source.parallel;
    ordinaryUpgrades = source.ordinaryUpgrades;
    selectedPorts = selectPorts(ports, inputGroup);
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

    return inputGroup == group ? this : new RecipeExecutionTicket(this, group);
  }

  public int inputGroup() {
    return inputGroup;
  }

  public int parallelLimit() {
    int hardware = parallel == null ? 1 : parallel.limit();
    if (ordinaryUpgrades == null) {
      return hardware;
    }

    return (int) Math.min(
        registration.pattern.definition.maxParallelism(),
        Math.min(Integer.MAX_VALUE, hardware + ordinaryUpgrades.parallelLimit() - 1L));
  }

  FactoryUpgradeSnapshot ordinaryUpgrades() {
    return ordinaryUpgrades;
  }

  List<PortSnapshot> allPorts() {
    return ports;
  }

  public List<Integer> inputGroups() {
    var groups = new TreeSet<Integer>();
    groups.add(InputGroup.SHARED);
    ports.stream().filter(port -> port.kind() == PortKind.ITEM_INPUT || port.kind() == PortKind.FLUID_INPUT)
        .forEach(port -> groups.add(port.inputGroup()));

    return List.copyOf(groups);
  }

  boolean inputGroupsMatch(Map<String, Integer> expected) {
    requireServerThread();

    return ports.stream().allMatch(port -> expected.getOrDefault(port.partId(), 0) == port.inputGroup());
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

  static Optional<RecipeExecutionTicket> captureFluidInputs(MachineControllerBlockEntity controller) {
    return capture(controller, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureFluidInputs(StructureService.StructureSubject owner) {
    return capture(owner, CaptureMode.FLUID_INPUTS);
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
          || mode == CaptureMode.ENERGY_INPUTS && part.rule().port() != PortKind.ENERGY_INPUT
          || mode == CaptureMode.FLUID_INPUTS && part.rule().port() != PortKind.FLUID_INPUT) {
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
      if (port.kind().resource() == PortKind.Resource.ITEM && port.itemStartInProgress()) {
        return Optional.empty();
      }
      ports.add(new PortSnapshot(part.id(), position, port, level, part.selector()));
    }
    ports.sort(Comparator.comparing(PortSnapshot::partId));
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
    FLUID_INPUTS
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

  StructureService.StructureSubject owner() {
    return owner;
  }

  ServerLevel level() {
    return level;
  }

  boolean revalidateStructure() {
    return owner.revalidateStructure(registration);
  }

  void requireServerThread() {
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
    return matchesDependencies() ? capture(owner, mode).map(ticket -> ticket.withInputGroup(inputGroup)) : Optional
        .empty();
  }

  boolean dependenciesCurrent() {
    ServerThreadGuard.require(level, "Recipe resource access");
    return matchesDependencies();
  }

  private boolean matchesDependencies() {
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

    return (ordinaryUpgrades == null || controller.upgradeSnapshotCurrent(ordinaryUpgrades))
        && (parallel == null || parallel.current())
        && (mode != CaptureMode.ALL
            || !(owner instanceof FactoryControllerBlockEntity factory)
            || factory.upgradeRestrictionsCurrent());
  }

  public static final class PortSnapshot {
    private final String partId;
    private final BlockPos position;
    private final MachinePortBlockEntity identity;
    private final ServerLevel level;
    private final PortKind kind;
    private final long revision;
    private final List<ItemStack> items;
    private final List<Integer> limits;
    private final FluidStack fluid;
    private final long energy;
    private final long energyExtractable;
    private final long capacity;
    private final long transferLimit;
    private final String selector;
    private final int inputGroup;

    private PortSnapshot(String partId, BlockPos position, MachinePortBlockEntity port, ServerLevel level, String selector) {
      this.partId = partId;
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
      limits = List.copyOf(slotLimits);
      fluid = kind.resource() == PortKind.Resource.FLUID ? port.fluidTank().getFluid().copy() : FluidStack.EMPTY;
      energy = kind.resource() == PortKind.Resource.ENERGY ? port.energy().stored() : 0;
      energyExtractable = kind == PortKind.ENERGY_INPUT ? port.energy().extractableNow() : 0;
      capacity = switch (kind.resource()) {
        case ITEM -> 0;
        case FLUID -> port.fluidTank().getCapacity();
        case ENERGY -> port.energy().capacity();
      };
      transferLimit = kind.resource() == PortKind.Resource.ENERGY ? port.energy().transferLimit() : 0;
    }

    public String partId() {
      return partId;
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

    public long energy() {
      return energy;
    }

    public long energyExtractable() {
      return energyExtractable;
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
      return level.hasChunkAt(position)
          && !identity.isRemoved()
          && identity.getLevel() == level
          && level.getBlockEntity(position) == identity
          && identity.schemaSupported()
          && identity.kind() == kind
          && identity.resourceRevision() == revision
          && identity.inputGroup() == inputGroup;
    }

    private boolean matches(ServerLevel level) {
      if (!sameLiveStamp(level)) {
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
        var actual = identity.fluidTank().getFluid();
        if (identity.fluidTank().getCapacity() != capacity
            || fluid.getAmount() != actual.getAmount()
            || !FluidStack.isSameFluidSameComponents(fluid, actual)) {
          return false;
        }
      }
      return kind.resource() != PortKind.Resource.ENERGY
          || energy == identity.energy().stored()
              && capacity == identity.energy().capacity()
              && transferLimit == identity.energy().transferLimit();
    }
  }
}
