package com.lw.modularmachine.machine.recipe;

import com.lw.modularmachine.blockentity.FactoryControllerBlockEntity;
import com.lw.modularmachine.blockentity.MachineControllerBlockEntity;
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
  final CaptureMode mode;
  final List<SmartSnapshot> interfaces;
  final List<PatternInputSource> patternSources;
  final Snapshot parallel;
  final UpgradeSnapshot ordinaryUpgrades;

  RecipeTicketProof(StructureService.StructureSubject owner, ServerLevel level, StructureService.Registration registration, List<RecipePortSnapshot> ports, CaptureMode mode, List<SmartSnapshot> interfaces) {
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
    patternSources = mode == CaptureMode.ALL ? PatternProductionService.sources(owner) : List.of();
  }

  record SmartSnapshot(BlockPos position, SmartInterfaceBlockEntity identity, SmartInterfaceBinding binding) {
  }

  boolean matchesDependencies(PatternInputSource patternSource) {
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

}
