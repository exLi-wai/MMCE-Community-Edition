package com.lw.modularmachine.machine.controller;

import com.lw.modularmachine.machine.structure.StructureService;
import org.jetbrains.annotations.ApiStatus;

/**
 * Internal owner contract; script edits are independent from physical sources.
 */
@ApiStatus.Internal
public interface ControllerScriptStateOwner extends StructureService.StructureSubject {
  ControllerScriptState scriptState();

  boolean scriptStateAvailable();

  boolean scriptStateEditingAllowed();

  boolean replaceControllerScriptState(ControllerScriptState expected, ControllerScriptState replacement);

  boolean clearControllerScriptState();
}
