package com.lw.modularmachine.machine.structure;

import com.lw.modularmachine.api.controller.VerificationFailure;
import com.lw.modularmachine.api.controller.VerificationState;
import com.lw.modularmachine.api.orientation.MachineOrientation;
import java.util.concurrent.CompletableFuture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import com.lw.modularmachine.machine.structure.StructureService.Registration;
import com.lw.modularmachine.machine.structure.StructureService.StructureSubject;

/**
 * Controller identity and in-flight layout generation; owned by one level
 * state.
 */
@ApiStatus.Internal
final class StructureAnchor implements StructureWork {
  final StructureSubject controller;
  boolean alive = true, turning, schemaSupported, preparing;
  long epoch = -1, generation, roundStarted, projectionRevision;
  long actualRevision = Long.MIN_VALUE;
  boolean actual;
  ResourceLocation machineId;
  MachineDefinition definition;
  MachineDefinition layoutDefinition;
  MachineOrientation orientation;
  CompletableFuture<PreparedStructure> builder;
  CompletableFuture<DynamicStructureMatcher> dynamicPreparation;
  DynamicStructureMatcher dynamicMatcher;
  final DynamicStructureWatches dynamicWatches = new DynamicStructureWatches();
  Registration registration, draft, retired;
  VerificationFailure failure = VerificationFailure.UNVERIFIED;

  StructureAnchor(StructureSubject controller) {
    this.controller = controller;
  }

  boolean needsWork() {
    boolean waitingForBuilder = preparing && builder != null && !builder.isDone();
    boolean waitingForDynamic = dynamicPreparation != null && !dynamicPreparation.isDone();
    boolean waitingForMatch = !preparing && registration != null && registration.snapshotWork.waiting();
    return !waitingForBuilder
        && !waitingForDynamic
        && !waitingForMatch
        && (preparing
            || draft != null
            || registration != null
                && (registration.verifier.state() == VerificationState.PENDING
                    || registration.valid
                        && registration.verifier.canCommit()
                        && registration.modifiers.canCommit()
                        && registration.interfacesReady
                        && (!registration.visuals.colorsComplete() || !registration.visuals.visibilityComplete())));
  }
}
