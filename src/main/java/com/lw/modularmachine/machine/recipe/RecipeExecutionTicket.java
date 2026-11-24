package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.RecipeResourceCapture.CaptureMode;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.machine.recipe.search.RecipeCatalog;
import com.lw.modularmachine.machine.recipe.search.RecipeResourceRequirements.RequiredPort;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import com.google.common.collect.Iterators;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.Objects;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ephemeral server-thread resource ticket, not a serializable run or an
 * authority to write.
 */
@ApiStatus.Internal
public final class RecipeExecutionTicket {
  /**
   * Factory-owned, server-thread cache. Each hit still checks live contents
   * before reuse.
   */
  public static final class TickCache {
    private final EnumMap<CaptureMode, RecipeExecutionTicket> tickets = new EnumMap<>(CaptureMode.class);
    private final Map<Set<RequiredPort>, RecipeExecutionTicket> outputTickets = new HashMap<>();
    private record SlotImages(MachinePortBlockEntity port, ItemStack[] images) {
    }
    private final Map<BlockPos, SlotImages> itemPorts = new HashMap<>();
    private StructureService.Registration itemRegistration;
    private long tick = Long.MIN_VALUE;

    public void beginTick(long now) {
      if (tick != now) {
        tickets.clear();
        outputTickets.clear();
        tick = now;
      }
    }

    RecipeExecutionTicket current(CaptureMode mode, Set<RequiredPort> required) {
      var ticket = required == null ? tickets.get(mode) : outputTickets.get(required);
      return ticket != null && ticket.matchesLive() ? ticket : null;
    }

    void remember(CaptureMode mode, Set<RequiredPort> required, RecipeExecutionTicket ticket) {
      if (required == null) {
        tickets.put(mode, ticket);
      } else {
        outputTickets.put(required, ticket);
      }
    }

    RecipeExecutionTicket searchCapture(StructureService.Registration registration) {
      var ticket = tickets.get(CaptureMode.ALL);
      return ticket != null
          && ticket.proof.registration == registration
          && ticket.proof.structureRevision == registration.verifier.revision() ? ticket : null;
    }

    ItemStack[] itemImages(StructureService.Registration registration, MachinePortBlockEntity port) {
      if (itemRegistration != registration) {
        itemPorts.clear();
        itemRegistration = registration;
      }
      int size = port.inventory().getSlots();
      var pos = port.getBlockPos();
      var previous = itemPorts.get(pos);
      if (previous == null || previous.port() != port || previous.images().length != size) {
        previous = new SlotImages(port, new ItemStack[size]);
        itemPorts.put(pos, previous);
      }
      return previous.images();
    }

    public void clear() {
      tickets.clear();
      outputTickets.clear();
      itemPorts.clear();
      itemRegistration = null;
    }
  }

  private final RecipeTicketProof proof;
  private final int inputGroup;
  private final List<RecipePortSnapshot> selectedPorts;
  private final List<RecipePortSnapshot> virtualPorts;
  private final PatternInputSource patternSource;
  private final Set<RequiredPort> recipePorts;

  RecipeExecutionTicket(RecipeTicketProof proof, Set<RequiredPort> capturedRoles) {
    this(proof, selectPorts(proof.ports, InputGroup.SHARED), capturedRoles);
  }

  private RecipeExecutionTicket(RecipeTicketProof proof, List<RecipePortSnapshot> selectedPorts, Set<RequiredPort> capturedRoles) {
    this.proof = proof;
    inputGroup = InputGroup.SHARED;
    patternSource = null;
    virtualPorts = List.of();
    recipePorts = capturedRoles == null ? null : Set.copyOf(capturedRoles);
    this.selectedPorts = selectedPorts;
  }

  RecipeExecutionTicket renewSearch(List<PatternInputSource> sources) {
    var next = new RecipeTicketProof(
        proof.owner,
        proof.level,
        proof.registration,
        proof.ports,
        proof.mode,
        proof.interfaces,
        proof.groups,
        sources);
    return new RecipeExecutionTicket(next, selectedPorts, null);
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, int inputGroup, PatternInputSource patternSource) {
    this(source, inputGroup, patternSource, selectPorts(source.proof.ports, inputGroup), patternSource == null ? List
        .of() : RecipeResourceCapture.patternPorts(source.proof.level, source.proof.mode, patternSource));
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, int inputGroup, PatternInputSource patternSource, List<RecipePortSnapshot> physical, List<RecipePortSnapshot> virtual) {
    this.inputGroup = InputGroup.require(inputGroup);
    proof = source.proof;
    this.patternSource = patternSource;
    virtualPorts = virtual;
    recipePorts = source.recipePorts;
    List<RecipePortSnapshot> selected;
    if (virtual.isEmpty()) {
      selected = physical;
    } else {
      selected = new ArrayList<RecipePortSnapshot>(virtual.size() + physical.size());
      selected.addAll(virtual);
      selected.addAll(physical);
      selected = List.copyOf(selected);
    }
    selectedPorts = filterPorts(selected, recipePorts);
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, Set<RequiredPort> recipePorts) {
    proof = source.proof;
    inputGroup = source.inputGroup;
    patternSource = source.patternSource;
    virtualPorts = source.virtualPorts;
    this.recipePorts = Set.copyOf(recipePorts);
    selectedPorts = filterPorts(source.selectedPorts, this.recipePorts);
  }

  private static List<RecipePortSnapshot> filterPorts(List<RecipePortSnapshot> ports, Set<RequiredPort> required) {
    return required == null ? ports : ports.stream()
        .filter(port -> required.contains(new RequiredPort(port.kind(), port.selector()))).toList();
  }

  /**
   * Restrict both the start planner's view and its content proof to the selected
   * recipe's roles.
   */
  public RecipeExecutionTicket forRecipePorts(Set<RequiredPort> required) {
    requireServerThread();
    return new RecipeExecutionTicket(this, required);
  }

  RecipeExecutionTicket forOutputParts(java.util.function.Predicate<RecipePortSnapshot> include) {
    requireServerThread();
    var ports = selectedPorts.stream().filter(include).toList();
    var narrowed = new RecipeTicketProof(proof, ports, proof.capturedTick);
    return new RecipeExecutionTicket(narrowed, ports, recipePorts);
  }

  private static List<RecipePortSnapshot> selectPorts(List<RecipePortSnapshot> ports, int group) {
    var selected = new ArrayList<RecipePortSnapshot>();
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
    return source == patternSource ? this : new RecipeExecutionTicket(this, inputGroup, source);
  }

  public Optional<RecipeExecutionTicket> forRun(FrozenRecipeRun run) {
    return PatternRunSources.forRun(this, run);
  }

  public List<RecipeExecutionTicket> searchContexts() {
    var groups = inputGroups();
    if (groups.size() == 1 && proof.patternSources.isEmpty()) {
      return List.of(this);
    }
    return new AbstractList<>() {
      private final int size = Math.multiplyExact(groups.size(), proof.patternSources.size() + 1);
      private final Map<Integer, RecipeExecutionTicket> visits = new HashMap<>();
      private final Map<Integer, List<RecipePortSnapshot>> physical = new HashMap<>();
      private final Map<PatternInputSource, List<RecipePortSnapshot>> virtual = new HashMap<>();

      @Override
      public RecipeExecutionTicket get(int index) {
        requireServerThread();
        Objects.checkIndex(index, size);
        return visits.computeIfAbsent(index, this::create);
      }

      private RecipeExecutionTicket create(int index) {
        int group = groups.get(index % groups.size());
        int sourceIndex = index / groups.size();
        var source = sourceIndex == proof.patternSources.size() ? null : proof.patternSources.get(sourceIndex);
        var ports = physical.computeIfAbsent(group, key -> selectPorts(proof.ports, key));
        var patternPorts = source == null ? List.<RecipePortSnapshot>of() : virtual
            .computeIfAbsent(source, key -> RecipeResourceCapture.patternPorts(proof.level, proof.mode, key));
        return new RecipeExecutionTicket(RecipeExecutionTicket.this, group, source, ports, patternPorts);
      }

      @Override
      public int size() {
        return size;
      }
    };
  }

  public int inputGroup() {
    return inputGroup;
  }

  Map<String, Integer> dynamicLengths() {
    return proof.registration.dynamicLengths();
  }

  public int parallelLimit() {
    int hardware = proof.parallel == null ? 1 : proof.parallel.limit();
    if (proof.ordinaryUpgrades == null) {
      return hardware;
    }

    return (int) Math.min(
        proof.registration.pattern.definition.maxParallelism(),
        Math.min(Integer.MAX_VALUE, (long) hardware + proof.ordinaryUpgrades.parallelLimit() - 1L));
  }

  UpgradeSnapshot ordinaryUpgrades() {
    return proof.ordinaryUpgrades;
  }

  public RecipeInputMiss.Capture beginMissingFactoryInputs(String threadId, RecipeCatalog catalog) {
    requireServerThread();
    return RecipeInputMiss.begin(proof, threadId, catalog);
  }

  List<RecipePortSnapshot> allPorts() {
    var result = new ArrayList<>(proof.ports);
    result.addAll(virtualPorts);
    return List.copyOf(result);
  }

  public List<Integer> inputGroups() {
    var groups = new TreeSet<Integer>();
    groups.add(InputGroup.SHARED);
    proof.ports.stream()
        .filter(
            port -> port.kind() == PortKind.ITEM_INPUT
                || port.kind() == PortKind.FLUID_INPUT
                || port.kind() == PortKind.CHEMICAL_INPUT)
        .forEach(port -> groups.add(port.inputGroup()));

    return List.copyOf(groups);
  }

  boolean inputGroupsMatch(Map<String, Integer> expected) {
    requireServerThread();

    return proof.groups.stream().allMatch(group -> expected.getOrDefault(group.part(), 0) == group.group())
        && proof.ports.stream().allMatch(port -> expected.getOrDefault(port.logicalPartId(), 0) == port.inputGroup());
  }

  /**
   * No capability callbacks, writes, chunk loads, or reduction of
   * component-bearing stacks to IDs.
   */
  public static Optional<RecipeExecutionTicket> capture(MachineControllerBlockEntity controller) {
    return RecipeResourceCapture.capture((StructureService.StructureSubject) controller, CaptureMode.ALL);
  }

  public static Optional<RecipeExecutionTicket> capture(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.ALL);
  }

  /** Each factory search observes fresh orders and its own pattern rotation. */
  public static Optional<RecipeExecutionTicket> captureForSearch(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.ALL, true);
  }

  public static Optional<RecipeExecutionTicket> captureComponents(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.COMPONENTS);
  }

  static Optional<RecipeExecutionTicket> captureOutputs(MachineControllerBlockEntity controller) {
    return RecipeResourceCapture.capture(controller, CaptureMode.OUTPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureOutputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.OUTPUTS);
  }

  static Optional<RecipeExecutionTicket> captureEnergyInputs(MachineControllerBlockEntity controller) {
    return RecipeResourceCapture.capture(controller, CaptureMode.ENERGY_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureEnergyInputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.ENERGY_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureEnergyOutputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.ENERGY_OUTPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureTickItems(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.TICK_ITEMS);
  }

  public static Optional<RecipeExecutionTicket> captureTickFluids(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.TICK_FLUIDS);
  }

  static Optional<RecipeExecutionTicket> captureFluidInputs(MachineControllerBlockEntity controller) {
    return RecipeResourceCapture.capture(controller, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureFluidInputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureChemicalInputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.CHEMICAL_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureChemicalOutputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.CHEMICAL_OUTPUTS);
  }

  /**
   * Views contain defensive copies; actual BlockEntity identities stay private to
   * this ticket.
   */
  public List<RecipePortSnapshot> ports() {
    return selectedPorts;
  }

  public List<BlockPos> componentPositions(Block block) {
    return componentPositions(Set.of(block));
  }

  public Iterator<BlockPos> componentPositionCandidates(Block block) {
    requireServerThread();
    var pattern = proof.registration.pattern;
    return pattern.componentIndices(Set.of(block)).stream().map(pattern::position).iterator();
  }

  public Iterator<BlockPos> componentPositionCandidates(Set<? extends Block> blocks) {
    requireServerThread();
    var pattern = proof.registration.pattern;
    var sources = blocks.stream().map(block -> pattern.componentIndices(Set.of(block)).iterator()).toList();
    return Iterators.transform(Iterators.mergeSorted(sources, Comparator.naturalOrder()), pattern::position);
  }

  public List<BlockPos> componentPositions(Set<? extends Block> blocks) {
    requireServerThread();
    if (blocks.isEmpty()) {
      return List.of();
    }
    var pattern = proof.registration.pattern;
    var positions = new ArrayList<BlockPos>();
    for (int index : pattern.componentIndices(blocks)) {
      var position = pattern.position(index);
      var chunk = proof.level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
      if (chunk != null && blocks.contains(chunk.getBlockState(position).getBlock())) {
        positions.add(position);
      }
    }
    return List.copyOf(positions);
  }

  public boolean interfacesMatch(List<SmartNumberRequirement> requirements) {
    requireServerThread();
    for (var requirement : requirements) {
      boolean found = proof.interfaces.stream().anyMatch(
          snapshot -> snapshot.binding().type().equals(requirement.type())
              && requirement.matches(snapshot.binding().value()));
      if (!found) {
        return false;
      }
    }

    return true;
  }

  public String interfaceFailureMessage(List<SmartNumberRequirement> requirements) {
    requireServerThread();
    var definition = proof.registration.pattern.definition;
    for (var requirement : requirements) {
      var values = proof.interfaces.stream().filter(snapshot -> snapshot.binding().type().equals(requirement.type()))
          .toList();
      if (!values.isEmpty() && values.stream().noneMatch(snapshot -> requirement.matches(snapshot.binding().value()))) {
        var type = definition.interfaceTypes().get(requirement.type());
        if (type != null && !type.notEqualMessage().isEmpty()) {
          return type.notEqualMessage();
        }
      }
    }

    return "";
  }

  public long structureRevision() {
    return proof.structureRevision;
  }

  public long definitionEpoch() {
    return proof.registration.epoch();
  }

  public long generation() {
    return proof.registration.generation();
  }

  MachineControllerBlockEntity controller() {
    return proof.controller;
  }

  @ApiStatus.Internal
  public StructureService.StructureSubject owner() {
    return proof.owner;
  }

  @ApiStatus.Internal
  public ServerLevel level() {
    return proof.level;
  }

  boolean revalidateStructure() {
    return proof.owner.revalidateStructure(proof.registration);
  }

  @ApiStatus.Internal
  public void requireServerThread() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
  }

  boolean matchesLive() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
    // FE tick limits are not resource revisions. This initial ticket is same-tick
    // only.
    return proof.capturedTick == proof.level.getGameTime() && dependenciesCurrent();
  }

  boolean commitCurrent() {
    requireServerThread();
    if (proof.capturedTick != proof.level.getGameTime()
        || proof.owner.structureRegistration() != proof.registration
        || proof.registration.verifier.revision() != proof.structureRevision) {
      return false;
    }
    // Factory dependency validation already revalidates modifiers. Run the ordinary
    // controller's conservative predicates before comparing live resource contents.
    return (proof.controller == null || revalidateStructure()) && matchesLive();
  }

  boolean sharesCapture(RecipeExecutionTicket other) {
    return proof == other.proof;
  }

  /**
   * Recapture current-tick authority only when all original identities, revisions
   * and contents still match.
   */
  public Optional<RecipeExecutionTicket> refreshIfUnchanged() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
    if (!dependenciesCurrent()) {
      return Optional.empty();
    }
    if (proof.capturedTick == proof.level.getGameTime()) {
      return Optional.of(this);
    }
    if (proof.mode == CaptureMode.OUTPUTS && recipePorts != null) {
      // Output-only snapshots have no per-tick FE limit. Retain the exact physical
      // contents and original structure proof just verified above.
      var refreshed = new RecipeTicketProof(proof, proof.ports, proof.level.getGameTime());
      return Optional.of(new RecipeExecutionTicket(refreshed, selectedPorts, recipePorts));
    }
    return RecipeResourceCapture.capture(proof.owner, proof.mode)
        .map(ticket -> ticket.withInputGroup(inputGroup).withPatternSource(patternSource))
        .map(ticket -> recipePorts == null ? ticket : ticket.forRecipePorts(recipePorts));
  }

  public Optional<RecipeExecutionTicket> currentForCommit() {
    requireServerThread();
    if (proof.capturedTick == proof.level.getGameTime()) {
      return RecipeCommitGuard.validate(this) ? Optional.of(this) : Optional.empty();
    }
    return refreshIfUnchanged().filter(RecipeCommitGuard::validate);
  }

  @ApiStatus.Internal
  public boolean dependenciesCurrent() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
    return (recipePorts != null
        || patternSource != null
        || proof.patternSources.stream().allMatch(PatternInputSource::current))
        && proof.matchesDependencies(patternSource, recipePorts == null ? proof.ports : selectedPorts);
  }

  /** The detached search may continue while unrelated inventories change. */
  public boolean searchContextCurrent() {
    requireServerThread();
    return proof.matchesDependencies(patternSource, List.of());
  }

}
