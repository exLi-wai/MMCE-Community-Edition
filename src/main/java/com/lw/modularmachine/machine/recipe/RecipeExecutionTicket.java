package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.machine.recipe.RecipeResourceCapture.CaptureMode;
import com.lw.modularmachine.machine.recipe.RecipeTicketProof.SmartSnapshot;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.machine.PortKind;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.api.smart.SmartNumberRequirement;
import com.lw.modularmachine.machine.structure.StructureService;
import com.lw.modularmachine.util.thread.ServerThreadGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Ephemeral server-thread resource ticket, not a serializable run or an
 * authority to write.
 */
@ApiStatus.Internal
public final class RecipeExecutionTicket {
  private final RecipeTicketProof proof;
  private final int inputGroup;
  private final List<RecipePortSnapshot> selectedPorts;
  private final PatternInputSource patternSource;

  RecipeExecutionTicket(StructureService.StructureSubject owner, ServerLevel level, StructureService.Registration registration, List<RecipePortSnapshot> ports, CaptureMode mode, List<SmartSnapshot> interfaces) {
    proof = new RecipeTicketProof(owner, level, registration, ports, mode, interfaces);
    inputGroup = InputGroup.SHARED;
    patternSource = null;
    selectedPorts = selectPorts(ports, inputGroup);
  }

  private RecipeExecutionTicket(RecipeExecutionTicket source, int inputGroup, PatternInputSource patternSource) {
    this.inputGroup = InputGroup.require(inputGroup);
    proof = source.proof;
    this.patternSource = patternSource;
    var selected = new ArrayList<RecipePortSnapshot>();
    if (patternSource != null) {
      selected.addAll(RecipeResourceCapture.patternPorts(proof.level, proof.mode, patternSource));
    }
    selected.addAll(selectPorts(proof.ports, inputGroup));
    selectedPorts = List.copyOf(selected);
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
    return new RecipeExecutionTicket(this, inputGroup, source);
  }

  public Optional<RecipeExecutionTicket> forRun(FrozenRecipeRun run) {
    return PatternRunSources.forRun(this, run);
  }

  public List<RecipeExecutionTicket> searchContexts() {
    var result = new ArrayList<RecipeExecutionTicket>();
    for (var source : proof.patternSources) {
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

  List<RecipePortSnapshot> allPorts() {
    var result = new ArrayList<>(proof.ports);
    selectedPorts.stream().filter(RecipePortSnapshot::patternInput).forEach(result::add);
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

    return proof.ports.stream().allMatch(port -> expected.getOrDefault(port.logicalPartId(), 0) == port.inputGroup());
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

  static Optional<RecipeExecutionTicket> captureFluidInputs(MachineControllerBlockEntity controller) {
    return RecipeResourceCapture.capture(controller, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureFluidInputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.FLUID_INPUTS);
  }

  public static Optional<RecipeExecutionTicket> captureChemicalInputs(StructureService.StructureSubject owner) {
    return RecipeResourceCapture.capture(owner, CaptureMode.CHEMICAL_INPUTS);
  }

  /**
   * Views contain defensive copies; actual BlockEntity identities stay private to
   * this ticket.
   */
  public List<RecipePortSnapshot> ports() {
    return selectedPorts;
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
    return proof.capturedTick == proof.level.getGameTime() && proof.matchesDependencies(patternSource);
  }

  /**
   * Recapture current-tick authority only when all original identities, revisions
   * and contents still match.
   */
  public Optional<RecipeExecutionTicket> refreshIfUnchanged() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
    return proof.matchesDependencies(patternSource) ? RecipeResourceCapture.capture(proof.owner, proof.mode)
        .map(ticket -> ticket.withInputGroup(inputGroup).withPatternSource(patternSource)) : Optional.empty();
  }

  @ApiStatus.Internal
  public boolean dependenciesCurrent() {
    ServerThreadGuard.require(proof.level, "Recipe resource access");
    return proof.matchesDependencies(patternSource);
  }

}
