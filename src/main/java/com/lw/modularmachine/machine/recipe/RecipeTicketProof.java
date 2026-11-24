package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachinePortBlockEntity;
import com.lw.modularmachine.api.smart.SmartInterfaceBinding;
import com.lw.modularmachine.blockentity.SmartInterfaceBlockEntity;
import com.lw.modularmachine.machine.controller.MachineControllerScriptDataService;
import com.lw.modularmachine.api.upgrade.UpgradeSnapshot;
import com.lw.modularmachine.machine.parallel.ParallelCapacity.Snapshot;
import com.lw.modularmachine.machine.parallel.ParallelCapacity;
import com.lw.modularmachine.machine.structure.StructureService;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.recipe.RecipeResourceCapture.CaptureMode;

/**
 * Original structure/script/upgrade/parallel/interface proofs shared by grouped
 * ticket views.
 */
@ApiStatus.Internal
final class RecipeTicketProof {
  final MachineControllerBlockEntity controller;
  final StructureService.StructureSubject owner;
  final ServerLevel level;
  final StructureService.Registration registration;
  final long structureRevision;
  final long scriptRevision;
  final long capturedTick;
  final List<RecipePortSnapshot> ports;
  final List<GroupStamp> groups;
  final CaptureMode mode;
  final List<SmartSnapshot> interfaces;
  final List<PatternInputSource> patternSources;
  final Snapshot parallel;
  final UpgradeSnapshot ordinaryUpgrades;

  RecipeTicketProof(StructureService.StructureSubject owner, ServerLevel level, StructureService.Registration registration, List<RecipePortSnapshot> ports, CaptureMode mode, List<SmartSnapshot> interfaces, List<GroupStamp> groups, List<PatternInputSource> patternSources) {
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
    this.groups = List.copyOf(groups);
    ordinaryUpgrades = mode == CaptureMode.ALL && controller != null ? controller.upgradeSnapshot() : null;
    parallel = mode == CaptureMode.ALL ? ParallelCapacity.capture(registration, level) : null;
    this.patternSources = patternSources != null ? patternSources : mode == CaptureMode.ALL ? PatternProductionService
        .sources(owner) : List.of();
  }

  record SmartSnapshot(BlockPos position, SmartInterfaceBlockEntity identity, SmartInterfaceBinding binding) {
  }

  RecipeTicketProof(RecipeTicketProof outputs, List<RecipePortSnapshot> ports, long tick) {
    if (outputs.mode != CaptureMode.OUTPUTS) {
      throw new IllegalArgumentException("Expected a paid output ticket");
    }
    owner = outputs.owner;
    controller = outputs.controller;
    level = outputs.level;
    registration = outputs.registration;
    structureRevision = outputs.structureRevision;
    scriptRevision = outputs.scriptRevision;
    capturedTick = tick;
    this.ports = ports;
    mode = outputs.mode;
    interfaces = outputs.interfaces;
    groups = outputs.groups;
    parallel = outputs.parallel;
    ordinaryUpgrades = outputs.ordinaryUpgrades;
    patternSources = outputs.patternSources;
  }

  record GroupStamp(String part, MachinePortBlockEntity port, int group, long revision) {
    boolean current(ServerLevel level) {
      return !port.isRemoved()
          && port.getLevel() == level
          && port.schemaSupported()
          && level.hasChunkAt(port.getBlockPos())
          && level.getBlockEntity(port.getBlockPos()) == port
          && port.groupRevision() == revision
          && port.inputGroup() == group;
    }
  }

  boolean matchesDependencies(PatternInputSource patternSource, List<RecipePortSnapshot> contents) {
    if (patternSource != null && !patternSource.current()) {
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
    } else if (owner.structureLevel() != level
        || owner.structureRegistration() != registration
        || registration.verifier.revision() != structureRevision
        || !owner.revalidateStructure(registration)) {
      return false;
    }
    for (var snapshot : interfaces) {
      if (!level.hasChunkAt(snapshot.position())
          || level.getBlockEntity(snapshot.position()) != snapshot.identity()
          || snapshot.identity().read(owner, snapshot.binding().type()).orElse(null) != snapshot.binding()) {
        return false;
      }
    }
    for (var port : contents) {
      if (!port.matches(level)) {
        return false;
      }
    }
    // A component comparator may reenter. Recheck plain identity/version fences
    // after all content
    // reads.
    for (var port : contents) {
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
      qualified = owner.structureRegistration() == registration
          && registration.verifier.revision() == structureRevision
          && registration.canCommit(level);
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

    for (var group : groups) {
      if (!group.current(level)) {
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

}
