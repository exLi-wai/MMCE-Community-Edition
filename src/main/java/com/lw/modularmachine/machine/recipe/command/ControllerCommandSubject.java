package com.lw.modularmachine.machine.recipe.command;

import com.lw.modularmachine.machine.controller.ControllerOwnershipSubject;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal controller-owned command outbox, separate from resource
 * transactions.
 */
@ApiStatus.Internal
public interface ControllerCommandSubject extends ControllerOwnershipSubject {
  ControllerCommandState commandState();

  boolean commandExecutionAllowed();
}
