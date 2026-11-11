package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.machine.structure.StructureService;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/** Internal one-time placement attribution; not a script transfer API. */
@ApiStatus.Internal
public interface ControllerOwnershipSubject extends StructureService.StructureSubject {
  ControllerOwnershipState ownershipState();

  void initializePlayerOwner(UUID playerId);
}
